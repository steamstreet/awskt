package com.steamstreet.awskt.logging

import com.steamstreet.exceptions.MDCExceptionMixin
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock

/*
 * The implementation of the non-suspending logging functions on every platform but the JVM, which
 * has SLF4J. The native and web actuals delegate here. See NonSuspendLogging.kt.
 */

/**
 * Receives each log line, without a trailing newline. Stdout, which Lambda forwards to CloudWatch;
 * replaced only by tests.
 */
internal var lineLogOutput: (String) -> Unit = ::println

private val linePublisher = JsonLogPublisher()

/**
 * Write one log event as a JSON line: the fields of every running `mdcContext` block, then the
 * throwable's MDC attributes, then [metadata] and [fields], each taking precedence over the last.
 */
internal fun writeLogLine(
    level: Log.Level,
    message: String,
    throwable: Throwable? = null,
    metadata: Array<out Pair<String, Any?>> = emptyArray(),
    fields: Map<String, JsonElement> = emptyMap()
) {
    val context = LinkedHashMap<String, JsonElement?>()
    MdcRegistry.current().forEach { (key, value) -> context[key] = JsonPrimitive(value) }
    (throwable as? MDCExceptionMixin)?.mdcAttributes?.forEach { (key, value) ->
        if (value != null) context[key] = JsonPrimitive(value.toString())
    }
    metadata.forEach { (key, value) ->
        if (value != null) context[key] = JsonPrimitive(value.toString())
    }
    context.putAll(fields)
    val event = Log.LoggingContext(context, listOfNotNull(throwable))
    lineLogOutput(linePublisher.format(level, message, event, Clock.System.now()))
}

/**
 * The fields of an `mdcContext` call, as the strings they are written as. Null values are left out.
 */
internal fun mdcFields(pairs: Array<out Pair<String, Any?>>): Map<String, String> =
    pairs.mapNotNull { (key, value) -> value?.let { key to it.toString() } }.toMap()

/**
 * Run [block] with [fields] in the registry the non-suspending functions read, and in the
 * coroutine's logging context, which [log] reads.
 */
internal suspend fun <T> withLogFields(fields: Map<String, String>, block: suspend () -> T): T {
    val frame = MdcRegistry.push(fields)
    try {
        return log.ctx({ fields.forEach { (key, value) -> put(key, JsonPrimitive(value)) } }) { block() }
    } finally {
        MdcRegistry.pop(frame)
    }
}

/**
 * The fields of every `mdcContext` block that is running, in the order the blocks began.
 *
 * A non-suspending function cannot read its coroutine's context, and Kotlin/Native has no
 * `ThreadContextElement` to carry a value across the threads a coroutine resumes on, which is how
 * the JVM's MDC does it. So the registry is process-wide. Frames are removed by identity rather than
 * popped, so blocks that end out of order, as concurrent ones do, still each remove only their own.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object MdcRegistry {
    internal class Frame(val fields: Map<String, String>)

    private val frames = AtomicReference<List<Frame>>(emptyList())

    fun push(fields: Map<String, String>): Frame = Frame(fields).also { frame -> update { it + frame } }

    fun pop(frame: Frame) = update { frames -> frames.filterNot { it === frame } }

    fun current(): Map<String, String> {
        val active = frames.load()
        if (active.isEmpty()) return emptyMap()
        val merged = LinkedHashMap<String, String>()
        active.forEach { merged.putAll(it.fields) }
        return merged
    }

    private inline fun update(change: (List<Frame>) -> List<Frame>) {
        while (true) {
            val current = frames.load()
            if (frames.compareAndSet(current, change(current))) return
        }
    }
}
