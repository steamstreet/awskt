package com.steamstreet.awskt.logging

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A log publisher that writes each log event as a single line of JSON.
 *
 * This is the format CloudWatch Logs Insights and Datadog read without any parsing rules: when the
 * whole log event is a JSON object, every key becomes a queryable field. A line with anything in
 * front of the JSON, which is what [DefaultLogPublisher] writes, is indexed as plain text.
 *
 * The field names match the JVM's Logstash encoder configuration in `lambda-logging`, so the same
 * Insights and Datadog queries work against JVM and native functions:
 *
 * ```json
 * {"@timestamp":"2026-09-26T17:04:05.123Z","level":"INFO","message":"Order placed","requestId":"…","orderId":"123"}
 * ```
 *
 * - `@timestamp` is the time of the event, in ISO-8601 at millisecond precision.
 * - `level` is `INFO`, `WARN` or `ERROR`. [Log.Level.EVENT] is written as `INFO`, as it is on the
 *   JVM.
 * - `message` is omitted when the event has no message.
 * - Every entry in the logging context is written at the top level. Null values are skipped.
 * - `stack_trace` holds the full stack trace of the first exception in the context, including its
 *   causes.
 *
 * These four field names are reserved: a context entry with one of these names is dropped rather
 * than allowed to overwrite the event's own field.
 *
 * Because the line is JSON, a message or stack trace that contains newlines stays on one line, and
 * so arrives as one CloudWatch log event rather than one per line.
 *
 * @param output Receives each encoded line, without a trailing newline. Defaults to stdout, which
 *   Lambda forwards to CloudWatch.
 */
public class JsonLogPublisher(private val output: (String) -> Unit = ::println) : LogPublisher {
    override suspend fun publish(
        level: Log.Level,
        message: String?,
        context: Log.LoggingContext
    ) {
        output(format(level, message, context, Clock.System.now()))
    }

    internal fun format(
        level: Log.Level,
        message: String?,
        context: Log.LoggingContext,
        now: Instant
    ): String {
        val fields = LinkedHashMap<String, JsonElement>()
        // Truncated to milliseconds: the native clock carries nanoseconds, and not every consumer
        // parses a nine-digit fraction.
        val timestamp = Instant.fromEpochMilliseconds(now.toEpochMilliseconds())
        fields[TIMESTAMP] = JsonPrimitive(timestamp.toString())
        fields[LEVEL] = JsonPrimitive(level.outputName())
        if (message != null) {
            fields[MESSAGE] = JsonPrimitive(message)
        }
        context.contextMap.forEach { (key, value) ->
            if (value != null && key !in RESERVED) {
                fields[key] = value
            }
        }
        context.exceptions.firstOrNull()?.let {
            fields[STACK_TRACE] = JsonPrimitive(it.stackTraceToString())
        }
        return Log.encoder.encodeToString(JsonObject.serializer(), JsonObject(fields))
    }

    private fun Log.Level.outputName(): String =
        when (this) {
            Log.Level.INFO,
            Log.Level.EVENT -> "INFO"
            Log.Level.WARN -> "WARN"
            Log.Level.ERROR -> "ERROR"
        }
}

// Top level rather than in a companion: a private companion's constants still compile to public
// static fields on the JVM, which would put them in the ABI.
private const val TIMESTAMP = "@timestamp"
private const val LEVEL = "level"
private const val MESSAGE = "message"
private const val STACK_TRACE = "stack_trace"

private val RESERVED = setOf(TIMESTAMP, LEVEL, MESSAGE, STACK_TRACE)
