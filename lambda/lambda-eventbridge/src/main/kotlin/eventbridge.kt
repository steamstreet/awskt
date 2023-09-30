package com.steamstreet.aws.lambda.eventbridge

import com.amazonaws.services.lambda.runtime.Context
import com.steamstreet.aws.lambda.MockLambdaContext
import com.steamstreet.aws.lambda.lambdaInput
import com.steamstreet.aws.lambda.lambdaJson
import com.steamstreet.aws.lambda.logger
import com.steamstreet.awskt.logging.logInfo
import com.steamstreet.awskt.logging.logJson
import com.steamstreet.awskt.logging.mdcContext
import com.steamstreet.events.EventSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import net.logstash.logback.marker.Markers
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import kotlin.system.measureTimeMillis

/**
 * Callback interface for event bridge handler installation.
 */
public interface EventBridgeHandlerConfig {
    /**
     * Get all events of the given type
     */
    public fun eventsOfType(type: String): List<Event>

    public suspend operator fun <T> EventSchema<T>.invoke(block: suspend context(Event) (T) -> Unit) {
        type(this, block)
    }
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
    config: suspend EventBridgeHandlerConfig.() -> Unit
) {
    lambdaInput<JsonElement>(input, context) { element ->
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

                val responseString = lambdaJson.encodeToString(response)
                if (response.batchItemFailures.isNotEmpty()) {
                    logger.info(Markers.appendRaw("batch-response", responseString), "Batch response partial failure")
                }
                withContext(Dispatchers.IO) {
                    output.write(responseString.encodeToByteArray())
                }
            }
        } else {
            // add the detail type to the mdc for tracing.
            val detailType =
                obj["detail-type"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing detail type")
            val detail = obj["detail"]?.jsonObject ?: throw IllegalArgumentException("Missing detail for $detailType")
            mdcContext("event-detail-type" to detailType) {
                val processing = measureTimeMillis {
                    val handlerConfig = DefaultEventBridgeHandlerConfig(detailType, detail)
                    handlerConfig.config()
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

/**
 * A default implementation of the handler config
 */
public class DefaultEventBridgeHandlerConfig(
    private val detailType: String,
    private val detail: JsonObject
) : EventBridgeHandlerConfig {
    public var error: Throwable? = null

    override fun eventsOfType(type: String): List<Event> {
        return if (detailType == type) {
            listOf(object : Event {
                override val id: String = "__"
                override val type: String = detailType
                override val detail: JsonObject = this@DefaultEventBridgeHandlerConfig.detail

                override fun failed(t: Throwable?) {
                    error = t
                }

                override val sourceEvent: Any get() = this
            })
        } else {
            emptyList()
        }
    }
}

public interface Event {
    public val id: String
    public val type: String
    public val detail: JsonObject

    /**
     * Report failure
     */
    public fun failed(t: Throwable?)

    public val sourceEvent: Any?
}

private class SQSEventBridge(sqsEvent: JsonObject) : EventBridgeHandlerConfig {
    val records = sqsEvent["Records"]!!.jsonArray.map {
        it.jsonObject
    }

    val failures = mutableMapOf<String, Throwable?>()

    val events: List<Event> by lazy {
        records.mapNotNull { record ->
            val body = record["body"]?.jsonPrimitive?.content
            if (body != null) {
                val eventBridgeRecord = lambdaJson.parseToJsonElement(body).jsonObject
                val type = eventBridgeRecord["detail-type"]?.jsonPrimitive?.content
                val detail = eventBridgeRecord["detail"]?.jsonObject

                if (type == null || detail == null) {
                    throw IllegalArgumentException("Missing detail type or detail from message")
                }

                object : Event {
                    override val id: String =
                        record["messageId"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                    override val type: String = type
                    override val detail: JsonObject = detail

                    override fun failed(t: Throwable?) {
                        logger.error("Event processing failed")
                        failures[id] = t
                    }

                    override val sourceEvent: Any = record
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
}

@Serializable
public class BatchResponse(
    public val batchItemFailures: List<RecordResponse>
)

@Serializable
public class RecordResponse(
    public val itemIdentifier: String
)

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

    context(EventBridgeHandlerConfig)
    public suspend fun onEvent()
}


context(EventBridgeHandlerConfig)
public suspend fun <T> on(type: EventSchema<T>, handler: suspend context(Event) (T) -> Any?): Unit =
    type(type, handler)

/**
 * Register a handler for a given event.
 */
public suspend fun <T, R> EventBridgeHandlerConfig.type(
    type: EventSchema<T>,
    handler: suspend context(Event) (T) -> R
) {
    eventsOfType(type.type).forEach { event ->
        logger.logJson("Processing event", "event", event.detail.toString())

        try {
            val value = lambdaJson.decodeFromJsonElement(type.serializer, event.detail)
            coroutineScope {
                handler(event, value)
            }
        } catch (t: Throwable) {
            event.failed(t)
        }
    }
}

public suspend fun EventBridgeHandlerConfig.type(
    detailType: String,
    handler: suspend context(Event) (JsonObject) -> Any?
) {
    eventsOfType(detailType).forEach { event ->
        logger.logJson("Processing event", "event", event.detail.toString())
        try {
            coroutineScope {
                handler(event, event.detail)
            }
        } catch (t: Throwable) {
            event.failed(t)
        }
    }
}

@Serializable
public class EventBusEvent(
    public val version: String? = null,
    public val account: String? = null,
    public val region: String? = null,
    public val detail: JsonElement,

    @SerialName("detail-type")
    public val detailType: String? = null,

    public val otherType: String? = null,
    public val source: String? = null,
    public val id: String? = null,
    public val resources: List<String>? = null
)

public fun JsonElement.string(): String? {
    return this.jsonPrimitive.contentOrNull
}

public fun JsonElement.string(key: String): String? {
    return this.jsonObject[key]?.string()
}

public fun JsonElement.strings(): List<String> {
    return jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
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
        Json.encodeToString(
            EventBusEvent(
                detailType = schema.type,
                detail = Json.encodeToJsonElement(schema.serializer, payload),
                source = source ?: "aws-kt"
            )
        )
    )
}