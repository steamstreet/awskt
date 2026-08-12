@file:OptIn(ExperimentalTime::class)

package com.steamstreet.aws.lambda.eventbridge

import com.amazonaws.services.lambda.runtime.Context
import com.steamstreet.aws.lambda.MockLambdaContext
import com.steamstreet.aws.lambda.lambdaInput
import com.steamstreet.aws.lambda.lambdaJson
import com.steamstreet.aws.lambda.logger
import com.steamstreet.aws.sqs.BatchResponse
import com.steamstreet.aws.sqs.RecordResponse
import com.steamstreet.awskt.logging.logError
import com.steamstreet.awskt.logging.logInfo
import com.steamstreet.awskt.logging.logJson
import com.steamstreet.awskt.logging.mdcContext
import com.steamstreet.events.EventSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.logstash.logback.marker.Markers
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.system.measureTimeMillis
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Unchanged from before this module became multiplatform — slf4j with the logstash JSON marker. */
internal actual fun logProcessingEvent(message: String, key: String, json: String) {
    logger.logJson(message, key, json)
}

/**
 * A handler for event bridge events. Also handles event bridge events packaged into an SQS
 * queue.
 */
public fun eventBridge(
    input: InputStream,
    context: Context,
    output: OutputStream? = null,
    tracePerformance: Boolean = true,
    batchSqs: Boolean = false,
    logRequestPayload: Boolean = false,
    config: suspend EventBridgeHandlerConfig.() -> Unit
) {
    lambdaInput<JsonElement>(input, context, logInput = logRequestPayload) { element ->
        val obj = element.jsonObject

        // check if this is an SQS message. This allows us to use the same handler for event bridge events
        // that get filtered to an SQS queue
        val records = obj["Records"]
        if (records != null) {
            val sqs = SQSEventBridge(obj)
            sqs.config()

            // if output is null, we'll just throw the first exception, and the entire
            // batch will be reprocessed
            if (!batchSqs || output == null) {
                if (sqs.failures.isNotEmpty()) {
                    sqs.failures.values.filterNotNull().firstOrNull()?.let {
                        throw it
                    } ?: throw Exception("Failed processing ${sqs.failures.keys.joinToString(",")}")
                }
            } else {
                val response = BatchResponse(batchItemFailures = sqs.failures.map {
                    RecordResponse(it.key)
                })

                sqs.failures.forEach {
                    val t = it.value
                    if (t != null) {
                        logError("Batch response error", t, "messageId" to it.key)
                    }
                }

                val responseString = lambdaJson.encodeToString(response)
                if (response.batchItemFailures.isNotEmpty()) {
                    logger.info(Markers.appendRaw("batch-response", responseString), "Batch response partial failure")
                }
                withContext(Dispatchers.IO) {
                    output.write(responseString.encodeToByteArray())
                }
            }
        } else {
            val event = eventBridgeEventDecoder.decodeFromJsonElement<EventBridgeEvent>(element)
            mdcContext("event-detail-type" to event.detailType) {
                val processing = measureTimeMillis {
                    val handlerConfig = DefaultEventBridgeHandlerConfig(event)
                    try {
                        handlerConfig.config()
                    } catch (t: Throwable) {
                        logError(
                            "Failure handling event", t,
                            "detail-type" to event.detailType,
                            "source" to event.source,
                            "time" to event.time.toString(),
                            "account" to event.account,
                            "detail" to event.detail?.toString()
                        )
                        throw t
                    }
                    handlerConfig.error?.let { throw it }
                }
                if (tracePerformance) {
                    logInfo(
                        "Completed event processing",
                        "duration" to processing.toString()
                    )
                }
            }
        }
    }
}

private class SQSEventBridge(sqsEvent: JsonObject) : EventBridgeHandlerConfig {
    val records = sqsEvent["Records"]!!.jsonArray.map {
        it.jsonObject
    }

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
                        record["messageId"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                    override val type: String = type
                    override val detail: JsonObject = detail

                    override fun failed(t: Throwable?) {
                        logger.error("Event processing failed")
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

    override fun eventsOfType(type: String): List<Event> {
        return events.filter {
            it.type == type
        }
    }

    override fun allEvents(): List<Event> {
        return events
    }
}

/**
 * Base class for a lambda that handles event bridge events.
 */
public interface EventBridgeFunction {
    public val tracePerformance: Boolean get() = true
    public val batchRetries: Boolean get() = false

    public fun execute(input: InputStream, output: OutputStream, context: Context) {
        eventBridge(input, context, output, tracePerformance, batchRetries) {
            onEvent()
        }
    }

    public suspend fun EventBridgeHandlerConfig.onEvent()
}

/**
 * Enables testability, allowing to send a raw event to an EventBridge function.
 */
public fun EventBridgeFunction.processEvent(str: String) {
    val output = ByteArrayOutputStream()
    execute(str.byteInputStream(), output, MockLambdaContext())
}

/**
 * Process an event directly. Useful for testing.
 */
public fun <T> EventBridgeFunction.processEvent(schema: EventSchema<T>, payload: T, source: String? = null) {
    processEvent(
        eventBridgeEventDecoder.encodeToString(
            EventBridgeEvent(
                id = UUID.randomUUID().toString(),
                detailType = schema.type,
                detail = Json.encodeToJsonElement(schema.serializer, payload).jsonObject,
                source = source ?: "aws-kt",
                time = Clock.System.now(),
                version = "0"
            )
        )
    )
}
