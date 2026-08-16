@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.steamstreet.aws.lambda.eventbridge

import com.steamstreet.aws.lambda.lambdaJson
import com.steamstreet.aws.sqs.BatchResponse
import com.steamstreet.aws.sqs.RecordResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.measureTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The EventBridge dispatch driver, in `commonMain` so the JVM `eventBridge()` entry point and the
 * native `eventBridgeLambda` run the same logic.
 *
 * This is the part that used to live only in `EventBridge.jvm.kt` and had no native counterpart at
 * all: detecting an SQS-wrapped payload, building the partial-batch-failure response, timing the
 * handler, and deciding whether a failure throws or is reported per record. None of it was
 * JVM-bound by nature — it needed `java.util.UUID`, `kotlin.system.measureTimeMillis` and slf4j,
 * and all three have common equivalents (the last through the `expect` shims below).
 */

/**
 * Run [config] against whichever kind of payload [element] turns out to be.
 *
 * EventBridge can reach a Lambda two ways, and this handles both from the one entry point:
 *  - **directly**, as a single event — the usual case;
 *  - **through an SQS queue**, as a `Records` array of events, which is what a rule that targets a
 *    queue produces.
 *
 * @param batchSqs when the payload is SQS-wrapped, return a [BatchResponse] naming the failed
 *   records instead of throwing. Requires `ReportBatchItemFailures` on the event source mapping to
 *   have any effect; without it, Lambda ignores the response and failed messages leave the queue.
 *   When false, the first failure is rethrown and the whole batch is redriven.
 * @param tracePerformance emit the "Completed event processing" timing line for a direct event.
 * @return the batch response for an SQS-wrapped payload when [batchSqs] is set, otherwise null —
 *   a direct event produces no response body.
 */
public suspend fun processEventBridgePayload(
    element: JsonElement,
    tracePerformance: Boolean = true,
    batchSqs: Boolean = false,
    config: suspend EventBridgeHandlerConfig.() -> Unit
): BatchResponse? {
    val obj = element.jsonObject

    // Check if this is an SQS message. This allows us to use the same handler for event bridge
    // events that get filtered to an SQS queue.
    return if (obj["Records"] != null) {
        processSqsWrapped(obj, batchSqs, config)
    } else {
        processDirectEvent(element, tracePerformance, config)
        null
    }
}

private suspend fun processSqsWrapped(
    obj: JsonObject,
    batchSqs: Boolean,
    config: suspend EventBridgeHandlerConfig.() -> Unit
): BatchResponse? {
    val sqs = SQSEventBridge(obj)
    sqs.config()

    // `Event.failed` used to emit this line itself, through a non-suspending slf4j call. It is
    // emitted here instead because `failed` is not a suspend function and the common `Log` API is
    // suspend-only — same line, same invocation, now with the message id it never carried.
    sqs.failures.keys.forEach { messageId ->
        logEventError("Event processing failed", null, mapOf("messageId" to messageId))
    }

    // If we are not reporting batch failures, we throw the first exception and the entire batch
    // gets reprocessed.
    if (!batchSqs) {
        if (sqs.failures.isNotEmpty()) {
            // Plain `Exception`, as before — a caller catching this by type should not have to
            // change because the driver moved source sets.
            sqs.failures.values.filterNotNull().firstOrNull()?.let { throw it }
                ?: throw Exception("Failed processing ${sqs.failures.keys.joinToString(",")}")
        }
        return null
    }

    val response = BatchResponse(batchItemFailures = sqs.failures.map { RecordResponse(it.key) })

    sqs.failures.forEach { (messageId, t) ->
        if (t != null) {
            logEventError("Batch response error", t, mapOf("messageId" to messageId))
        }
    }

    if (response.batchItemFailures.isNotEmpty()) {
        logProcessingEvent(
            "Batch response partial failure",
            "batch-response",
            lambdaJson.encodeToString(BatchResponse.serializer(), response)
        )
    }
    return response
}

private suspend fun processDirectEvent(
    element: JsonElement,
    tracePerformance: Boolean,
    config: suspend EventBridgeHandlerConfig.() -> Unit
) {
    val event = eventBridgeEventDecoder.decodeFromJsonElement<EventBridgeEvent>(element)
    withEventContext(event.detailType) {
        val processing = measureTime {
            val handlerConfig = DefaultEventBridgeHandlerConfig(event)
            try {
                handlerConfig.config()
            } catch (t: Throwable) {
                logEventError(
                    "Failure handling event", t,
                    mapOf(
                        "detail-type" to event.detailType,
                        "source" to event.source,
                        "time" to event.time.toString(),
                        "account" to event.account,
                        "detail" to event.detail?.toString()
                    )
                )
                throw t
            }
            handlerConfig.error?.let { throw it }
        }
        if (tracePerformance) {
            logEventInfo(
                "Completed event processing",
                // `measureTimeMillis` produced a Long of milliseconds and the line read e.g.
                // "duration": "42". `inWholeMilliseconds` keeps that exact rendering.
                mapOf("duration" to processing.inWholeMilliseconds.toString())
            )
        }
    }
}

/**
 * An [EventBridgeHandlerConfig] over a batch of EventBridge events delivered through SQS.
 *
 * Internal rather than private so `commonTest` can exercise the batch semantics without a Lambda
 * runtime; it is not part of the published surface.
 */
internal class SQSEventBridge(sqsEvent: JsonObject) : EventBridgeHandlerConfig {
    val records = sqsEvent["Records"]!!.jsonArray.map { it.jsonObject }

    override var logEventProcessing: Boolean = true

    val failures = mutableMapOf<String, Throwable?>()

    val events: List<Event> by lazy {
        records.mapNotNull { record ->
            val body = record["body"]?.jsonPrimitive?.content
            if (body != null) {
                val eventBridgeRecord = try {
                    lambdaJson.decodeFromString<EventBridgeEvent>(body)
                } catch (e: SerializationException) {
                    throw IllegalArgumentException(e.message, e)
                }

                val type = eventBridgeRecord.detailType
                val detail = eventBridgeRecord.detail
                    ?: throw IllegalArgumentException("Missing detail type or detail from message")

                object : Event {
                    override val id: String =
                        record["messageId"]?.jsonPrimitive?.content ?: Uuid.random().toString()
                    override val type: String = type
                    override val detail: JsonObject = detail

                    override fun failed(t: Throwable?) {
                        failures[id] = t
                    }

                    override val source: String? = eventBridgeRecord.source
                    override val resources: List<String>? = eventBridgeRecord.resources
                    override val sourceEvent: Any = record
                    override val time: Instant = eventBridgeRecord.time
                    override val sourceAccount: String? = eventBridgeRecord.account
                }
            } else {
                null
            }
        }
    }

    override fun eventsOfType(type: String): List<Event> = events.filter { it.type == type }

    override fun allEvents(): List<Event> = events
}

/**
 * Emits an error line with string metadata.
 *
 * `expect`/`actual` for the same reason as [logProcessingEvent]: the JVM keeps logging through
 * slf4j and the logging module's `logError`, so the field names and shape existing consumers parse
 * are unchanged, while native uses the coroutine-context `Log` API.
 */
internal expect suspend fun logEventError(message: String, t: Throwable?, metadata: Map<String, String?>)

/** Emits an info line with string metadata. See [logEventError]. */
internal expect suspend fun logEventInfo(message: String, metadata: Map<String, String?>)

/**
 * Runs [block] with the event's detail type attached to the logging context.
 *
 * The JVM uses slf4j's MDC, as `eventBridge()` always has; native uses the coroutine-context `Log`.
 */
internal expect suspend fun <T> withEventContext(detailType: String, block: suspend () -> T): T
