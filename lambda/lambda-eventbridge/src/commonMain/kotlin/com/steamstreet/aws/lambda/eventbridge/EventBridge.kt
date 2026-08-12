@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.steamstreet.aws.lambda.eventbridge

import com.steamstreet.aws.lambda.lambdaJson
import com.steamstreet.events.EventSchema
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The event model and the handler-registration DSL, in `commonMain` so that handler bodies compile
 * unchanged on the JVM and on Kotlin/Native.
 *
 * The Lambda plumbing — `InputStream`/`OutputStream`, the AWS `Context`, and the SQS-wrapped
 * variant — stays in `jvmMain`, because none of it exists on Native.
 */

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

internal val eventBridgeEventDecoder = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
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
 * Emits the "Processing event" log line.
 *
 * `expect`/`actual` rather than a shared implementation so the JVM keeps logging through slf4j with
 * the logstash JSON marker exactly as before this module became multiplatform — the alternative,
 * moving the whole DSL onto the common `Log` API, would have silently changed the log shape that
 * existing JVM consumers parse.
 */
internal expect fun logProcessingEvent(message: String, key: String, json: String)

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
            logProcessingEvent("Processing event", "event", event.detail.toString())
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
            logProcessingEvent("Processing event", "event", event.detail.toString())
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
        logProcessingEvent("Processing event", "event", event.detail.toString())
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
