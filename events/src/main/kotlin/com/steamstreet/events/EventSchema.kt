package com.steamstreet.events

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer

/**
 * Defines an event type and the serializer used for its data.
 */
public data class EventSchema<T>(
    val type: String,
    val serializer: KSerializer<T>
)

/**
 * Create a schema from a generic type.
 */
public inline fun <reified T> eventSchema(typeName: String): EventSchema<T> =
    EventSchema(typeName, Json.serializersModule.serializer())

/**
 * Send events from this schema
 */
public fun <T> EventSchema<T>.post(input: T) {
    val obj = Json.encodeToJsonElement(this.serializer, input).jsonObject
    poster.post(this.type, obj.toString())
}

/**
 * Post more than one event.
 */
public suspend fun <T> EventSchema<T>.post(input: Collection<T>) {
    val events = input.map {
        EventSchemaEvent(this, it)
    }
    poster.post(events)
}

internal class EventSchemaEvent<T>(val schema: EventSchema<T>, payload: T, override val source: String? = null) :
    Event {
    override val type: String = schema.type
    override val detail: String? by lazy {
        eventSchemaJson.encodeToString(schema.serializer, payload)
    }
}

@OptIn(ExperimentalSerializationApi::class)
public val eventSchemaJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}