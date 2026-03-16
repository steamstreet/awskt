@file:OptIn(ExperimentalTime::class)

package com.steamstreet.aws.lambda.eventbridge

import com.steamstreet.aws.lambda.lambdaJson
import com.steamstreet.aws.sqs.BatchResponse
import com.steamstreet.aws.sqs.RecordResponse
import com.steamstreet.awskt.logging.log
import com.steamstreet.events.EventSchema
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.measureTime
import kotlin.uuid.Uuid

/**
 * Callback interface for event bridge handler installation.
 */
public interface EventBridgeHandlerConfig {
    public var logEventProcessing: Boolean

    /**
     * Get all events of the given type
     */
    public fun eventsOfType(type: String): List<Event>

    /**
     * Get all events
     */
    public fun allEvents(): List<Event>

    /**
     * The invoke operator allows for a cleaner way to install event handlers,
     * providing only the eventSchema and a lambda to handle the event.
     */
    public suspend operator fun <T> EventSchema<T>.invoke(block: suspend context(Event) (T) -> Unit) {
        type(this, block)
    }
}

/**
 * An EventBridge event.
 */
@Serializable
public data class EventBridgeEvent(
    val version: String = "0",
    val id: String = Uuid.random().toString(),
    @SerialName("detail-type")
    val detailType: String,
    val account: String? = null,
    val time: Instant = Clock.System.now(),
    val region: String? = null,
    val resources: List<String>? = null,
    val detail: JsonObject? = null,
    val source: String? = null,
    @SerialName("replay-name")
    val replayName: String? = null
)

internal val eventBridgeEventDecoder: Json = Json {
    ignoreUnknownKeys = true
}

/**
 * Describes the event being received.
 */
public interface Event {
    /**
     * A unique identifier of the event
     */
    public val id: String

    /**
     * The event type
     */
    public val type: String

    /**
     * The event detail
     */
    public val detail: JsonObject

    /**
     * The source of the event (if available)
     */
    public val source: String?

    /**
     * A list of resources that this event applies to (if available)
     */
    public val resources: List<String>?

    /**
     * The time this event occurred
     */
    public val time: Instant?

    /**
     * Report failure
     */
    public fun failed(t: Throwable?)

    /**
     * The original source event. This could be the original event bridge event, the
     * SQS message, etc.
     */
    public val sourceEvent: Any?

    /**
     * The originating account of the event
     */
    public val sourceAccount: String?
}

/**
 * A default implementation of the handler config
 */
public class DefaultEventBridgeHandlerConfig(
    private val event: EventBridgeEvent
) : EventBridgeHandlerConfig {
    public var error: Throwable? = null
    override var logEventProcessing: Boolean = true

    private val detailType: String get() = event.detailType
    private val detail: JsonObject get() = event.detail!!

    override fun eventsOfType(type: String): List<Event> {
        return allEvents().filter {
            it.type == type
        }
    }

    override fun allEvents(): List<Event> {
        return listOf(DefaultEvent(event))
    }

    private inner class DefaultEvent(event: EventBridgeEvent) : Event {
        override val id: String = event.id
        override val type: String = detailType
        override val detail: JsonObject = this@DefaultEventBridgeHandlerConfig.detail

        override val source: String? = event.source
        override val resources: List<String>? = event.resources

        override val time: Instant = event.time

        override fun failed(t: Throwable?) {
            error = t
        }

        override val sourceAccount: String? = event.account
        override val sourceEvent: Any get() = event
    }
}

internal class SQSEventBridge(sqsEvent: JsonObject) : EventBridgeHandlerConfig {
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
 * Process an EventBridge event from a parsed JSON element. This is the core routing
 * logic shared across JVM and Native platforms.
 *
 * Returns a [BatchResponse] when processing SQS-wrapped events in batch mode,
 * or null for direct EventBridge events and non-batch SQS events.
 */
public suspend fun processEventBridge(
    element: JsonElement,
    tracePerformance: Boolean = true,
    batchSqs: Boolean = false,
    config: suspend EventBridgeHandlerConfig.() -> Unit
): BatchResponse? {
    val obj = element.jsonObject
    val records = obj["Records"]

    if (records != null) {
        val sqs = SQSEventBridge(obj)
        sqs.config()

        if (!batchSqs) {
            if (sqs.failures.isNotEmpty()) {
                sqs.failures.values.filterNotNull().firstOrNull()?.let {
                    throw it
                } ?: throw Exception("Failed processing ${sqs.failures.keys.joinToString(",")}")
            }
            return null
        } else {
            val response = BatchResponse(batchItemFailures = sqs.failures.map {
                RecordResponse(it.key)
            })

            sqs.failures.forEach { (messageId, t) ->
                if (t != null) {
                    log.error("Batch response error", t) {
                        put("messageId", JsonPrimitive(messageId))
                    }
                }
            }

            return response
        }
    } else {
        val event = eventBridgeEventDecoder.decodeFromJsonElement<EventBridgeEvent>(element)
        log.ctx({ put("event-detail-type", JsonPrimitive(event.detailType)) }) {
            val processing = measureTime {
                val handlerConfig = DefaultEventBridgeHandlerConfig(event)
                try {
                    handlerConfig.config()
                } catch (t: Throwable) {
                    log.error("Failure handling event", t) {
                        put("detail-type", JsonPrimitive(event.detailType))
                        put("source", JsonPrimitive(event.source))
                        put("time", JsonPrimitive(event.time.toString()))
                        put("account", JsonPrimitive(event.account))
                        put("detail", event.detail)
                    }
                    throw t
                }
                handlerConfig.error?.let { throw it }
            }
            if (tracePerformance) {
                log.info("Completed event processing") {
                    put("duration", JsonPrimitive(processing.toString()))
                }
            }
        }
        return null
    }
}

public suspend fun <T> EventBridgeHandlerConfig.on(
    type: EventSchema<T>,
    handler: suspend context(Event) (T) -> Any?
): Unit = typeWithContext(type, handler)

/**
 * Register a handler for a given event.
 */
public suspend fun <T, R> EventBridgeHandlerConfig.typeWithContext(
    type: EventSchema<T>,
    handler: suspend context(Event) (T) -> R
) {
    eventsOfType(type.type).forEach { event ->
        if (logEventProcessing) {
            log.info("Processing event") {
                put("event", event.detail)
            }
        }

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

/**
 * Register a handler for all events. The events will be called one by one.
 */
public suspend fun <R> EventBridgeHandlerConfig.any(
    handler: suspend context(Event) (Event) -> R
) {
    allEvents().map { event ->
        if (logEventProcessing) {
            log.info("Processing event") {
                put("event", event.detail)
            }
        }

        try {
            coroutineScope {
                handler(event, event)
            }
        } catch (t: Throwable) {
            event.failed(t)
        }
    }
}

/**
 * Register a handler for all events as a list. The callback must return
 * a list of booleans indicating success or failure for each event.
 */
public suspend fun EventBridgeHandlerConfig.any(
    handler: suspend (List<Event>) -> List<Boolean>
) {
    val allEvents = allEvents()
    val success = handler(allEvents)
    if (success.size != allEvents.size) {
        throw IllegalStateException("List of results must match size of events")
    }
    allEvents.forEachIndexed { index, event ->
        if (!success[index]) {
            event.failed(null)
        }
    }
}

public suspend fun <T, R> EventBridgeHandlerConfig.type(
    type: EventSchema<T>,
    handler: suspend context(Event) (T) -> R
) {
    typeWithContext(type, handler)
}

public suspend fun <T, R> EventBridgeHandlerConfig.type(
    type: EventSchema<T>,
    handler: suspend (T) -> R
) {
    typeWithContext(type) {
        handler(it)
    }
}

public suspend fun EventBridgeHandlerConfig.type(
    detailType: String,
    handler: suspend context(Event) (JsonObject) -> Any?
) {
    eventsOfType(detailType).forEach { event ->
        if (logEventProcessing) {
            log.info("Processing event") {
                put("event", event.detail)
            }
        }
        try {
            coroutineScope {
                handler(event, event.detail)
            }
        } catch (t: Throwable) {
            event.failed(t)
        }
    }
}

public fun JsonElement.string(): String? {
    return this.jsonPrimitive.contentOrNull
}

public fun JsonElement.string(key: String): String? {
    return this.jsonObject[key]?.string()
}

public fun JsonElement.strings(): List<String> {
    return jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
}
