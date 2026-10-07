package com.steamstreet.awskt.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * [RetiringHttpClientEngine] replaces its engine after a cancelled request, and closes the old one
 * once its calls are done. The defect it guards against is in the curl engine, and
 * `CurlStaleCancellationTest` (native) exercises that; these tests hold the bookkeeping to account
 * with mock engines, on every platform.
 *
 * The engines run on Ktor's dispatcher, not the test's, so nothing here relies on virtual time: a
 * request is cancelled only once the engine has it, and closing is awaited in real time.
 */
class RetiringEngineTest {
    /** Mock engines that answer by path: `/stall` never, `/fail` with an error, `/slow` on release. */
    private class Engines {
        val created = mutableListOf<MockEngine>()
        val slowRelease = CompletableDeferred<Unit>()
        val slowReached = CompletableDeferred<Unit>()
        var stallReached = CompletableDeferred<Unit>()

        fun create(): MockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/stall" -> {
                    stallReached.complete(Unit)
                    awaitCancellation()
                }
                "/fail" -> throw IOException("connection reset")
                "/slow" -> {
                    slowReached.complete(Unit)
                    slowRelease.await()
                    respond("slow")
                }
                else -> respond("ok")
            }
        }.also { created += it }
    }

    private fun client(engines: Engines) = HttpClient(RetiringHttpClientEngine { engines.create() })

    /** Sends a request that stalls, and cancels it once the engine has it. */
    private suspend fun CoroutineScope.cancelAStalledRequest(client: HttpClient, reached: () -> CompletableDeferred<Unit>) {
        val call = launch { client.get("http://host/stall") }
        reached().await()
        call.cancelAndJoin()
    }

    private val MockEngine.isClosed: Boolean get() = !coroutineContext.isActive

    /**
     * Waits for [engine] to close. A call's job completes, and so releases its engine, a moment after
     * the call returns, on Ktor's dispatcher rather than the test's.
     */
    private suspend fun awaitClosed(engine: MockEngine) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!engine.isClosed) delay(10) }
    }

    @Test
    fun requestsShareOneEngine() = runTest {
        val engines = Engines()
        val client = client(engines)
        repeat(3) { assertEquals("ok", client.get("http://host/ok").bodyAsText()) }
        assertEquals(1, engines.created.size)
    }

    @Test
    fun aCancelledRequestRetiresItsEngine() = runTest {
        val engines = Engines()
        val client = client(engines)

        cancelAStalledRequest(client) { engines.stallReached }
        assertEquals("ok", client.get("http://host/ok").bodyAsText())

        assertEquals(2, engines.created.size)
        awaitClosed(engines.created[0])
        assertFalse(engines.created[1].isClosed)

        // The new engine is kept for the requests that follow.
        assertEquals("ok", client.get("http://host/ok").bodyAsText())
        assertEquals(2, engines.created.size)
    }

    @Test
    fun aFailureThatIsNotACancellationKeepsTheEngine() = runTest {
        val engines = Engines()
        val client = client(engines)

        assertFailsWith<IOException> { client.get("http://host/fail") }
        assertEquals("ok", client.get("http://host/ok").bodyAsText())
        assertEquals(1, engines.created.size)
    }

    @Test
    fun aRetiredEngineClosesOnlyAfterItsLastCall() = runTest {
        val engines = Engines()
        val client = client(engines)

        val slow = async { client.get("http://host/slow").bodyAsText() }
        engines.slowReached.await()

        cancelAStalledRequest(client) { engines.stallReached }
        assertFalse(engines.created[0].isClosed, "a call is still running on the retired engine")

        engines.slowRelease.complete(Unit)
        assertEquals("slow", slow.await())
        awaitClosed(engines.created[0])
    }

    @Test
    fun theFactoryAppliesItsConfigurationToEveryEngine() = runTest {
        var configured = 0
        val stallReached = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine.retiringOnCancellation()) {
            engine {
                addHandler { request ->
                    if (request.url.encodedPath == "/stall") {
                        stallReached.complete(Unit)
                        awaitCancellation()
                    } else {
                        respond("ok")
                    }
                }
                configured++
            }
        }

        cancelAStalledRequest(client) { stallReached }
        assertEquals("ok", client.get("http://host/ok").bodyAsText())
        assertEquals(2, configured, "one configuration per engine created")
    }
}
