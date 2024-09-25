package com.steamstreet.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Send events from this schema
 */
public suspend fun <T> EventSchema<T>.post(input: T, source: String? = null) {
    val obj = Json.encodeToJsonElement(this.serializer, input).jsonObject
    poster.post(this.type, obj.toString(), source)
}

/**
 * Post more than one event.
 */
public suspend fun <T> EventSchema<T>.post(input: Collection<T>, source: String? = null) {
    val events = input.map {
        EventSchemaEvent(this, it, source)
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