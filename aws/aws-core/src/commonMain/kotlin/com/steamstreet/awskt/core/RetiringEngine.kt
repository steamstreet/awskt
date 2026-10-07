package com.steamstreet.awskt.core

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.callContext
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.job
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * This engine factory, wrapped so that an engine through which a request was cancelled is never
 * used again: the next request gets a new engine, and the old one is closed once its last call
 * finishes.
 *
 * It exists for Ktor's curl engine (3.5.2), in which a cancelled request can fail an unrelated later
 * one. When a request's coroutine is cancelled before its response starts (by a `withTimeout` around
 * the call, or by the `HttpTimeout` plugin), the engine queues `cancelRequest(easyHandle, cause)` for
 * its own thread, keyed by the address of the request's easy handle. That thread runs the task only
 * when it has no transfer in progress, which is after the cancelled transfer has run to completion
 * and `curl_easy_cleanup` has freed the handle. The next request on the engine is given a handle at
 * the same address, and the queued cancellation completes it at once with the old request's
 * exception, although the request was sent and may have been answered. A native client that had
 * one call time out therefore failed its next one instantly.
 *
 * A new engine has its own thread and its own record of cancelled handles, so no stale entry can
 * reach a request made on it. Requests that were already running on the old engine are not at risk:
 * the stale entry is recorded only once the old engine is idle, and no request is sent to it after
 * it retires. The cost is a new connection after each cancelled request, which is rare.
 *
 * The configuration block given to [HttpClientEngineFactory.create] is applied to every engine the
 * wrapper creates.
 */
public fun <T : HttpClientEngineConfig> HttpClientEngineFactory<T>.retiringOnCancellation(): HttpClientEngineFactory<T> {
    val factory = this
    return object : HttpClientEngineFactory<T> {
        override fun create(block: T.() -> Unit): HttpClientEngine =
            RetiringHttpClientEngine { factory.create(block) }
    }
}

/**
 * Sends each request through the current engine, and replaces that engine once a request through it
 * has been cancelled. See [retiringOnCancellation].
 */
@OptIn(ExperimentalAtomicApi::class)
internal class RetiringHttpClientEngine(
    private val newEngine: () -> HttpClientEngine
) : HttpClientEngineBase("awskt-retiring") {

    /** One engine, the calls running on it, and whether it has been replaced. */
    private class Generation(val engine: HttpClientEngine) {
        val active = AtomicInt(0)
        val retired = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)

        fun closeIfIdle() {
            if (retired.load() && active.load() == 0 && closed.compareAndSet(false, true)) {
                engine.close()
            }
        }
    }

    private val first = Generation(newEngine())
    private val current = AtomicReference<Generation?>(first)
    private val created = AtomicInt(1)

    // Every engine comes from the same factory and configuration, so the first one speaks for all.
    override val config: HttpClientEngineConfig get() = first.engine.config
    override val supportedCapabilities: Set<HttpClientEngineCapability<*>>
        get() = first.engine.supportedCapabilities

    /** How many engines this wrapper has created, for tests. */
    internal val enginesCreated: Int get() = created.load()

    @OptIn(InternalAPI::class)
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val generation = acquire()
        // The call ends when its response has been read or abandoned, which can be after execute
        // returns, since the body streams from the engine. Only then may a retired engine close.
        val call = callContext().job
        call.invokeOnCompletion { release(generation) }
        try {
            return generation.engine.execute(data)
        } finally {
            // Checked whether execute returned or threw: a cancellation that races the response's
            // arrival can still leave the engine's cancellation queued.
            if (call.isCancelled) retire(generation)
        }
    }

    /** The current engine, with this call counted against it. */
    private fun acquire(): Generation {
        while (true) {
            val generation = current.load() ?: Generation(newEngine()).let { fresh ->
                if (current.compareAndSet(null, fresh)) {
                    created.incrementAndFetch()
                    fresh
                } else {
                    fresh.engine.close()
                    continue
                }
            }
            generation.active.incrementAndFetch()
            if (!generation.retired.load()) return generation
            // Retired between being read and being counted: give it back and take the new one.
            release(generation)
        }
    }

    private fun release(generation: Generation) {
        generation.active.decrementAndFetch()
        generation.closeIfIdle()
    }

    private fun retire(generation: Generation) {
        generation.retired.store(true)
        current.compareAndSet(generation, null)
        generation.closeIfIdle()
    }

    override fun close() {
        super.close()
        current.exchange(null)?.let { retire(it) }
    }
}
