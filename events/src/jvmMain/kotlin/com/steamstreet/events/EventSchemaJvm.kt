package com.steamstreet.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Send events from this schema
 */
public suspend fun <T> EventSchema<T>.post(input: T, source: String? = null): String? {
    val obj = Json.encodeToJsonElement(this.serializer, input).jsonObject
    return poster.post(this.type, obj.toString(), source)
}

/**
 * Post more than one event.
 */
public suspend fun <T> EventSchema<T>.post(input: Collection<T>, source: String? = null): List<String?> {
    val events = input.map {
        EventSchemaEvent(this, it, source)
    }
    return poster.post(events)
}

internal class EventSchemaEvent<T>(val schema: EventSchema<T>, payload: T, override val source: String? = null) :
    Event {
    override val type: String = schema.type
    override val detail: String? by lazy {
        eventSchemaJson.encodeToString(schema.serializer, payload)
    }
}