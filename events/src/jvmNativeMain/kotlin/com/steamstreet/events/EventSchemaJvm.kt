package com.steamstreet.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Send events from this schema
 * @param eventPoster the poster to use, defaults to the global event poster.
 */
public suspend fun <T> EventSchema<T>.post(
    input: T, source: String? = null,
    eventPoster: ApplicationEventPoster = poster
): String? {
    val obj = Json.encodeToJsonElement(this.serializer, input).jsonObject
    return eventPoster.post(this.type, obj.toString(), source)
}

/**
 * Post more than one event.
 * @param eventPoster the poster to use, defaults to the global event poster.
 */
public suspend fun <T> EventSchema<T>.post(
    input: Collection<T>, source: String? = null,
    eventPoster: ApplicationEventPoster = poster
): List<String?> {
    val events = input.map {
        EventSchemaEvent(this, it, source)
    }
    return eventPoster.post(events)
}

internal class EventSchemaEvent<T>(val schema: EventSchema<T>, payload: T, override val source: String? = null) :
    Event {
    override val type: String = schema.type
    override val detail: String? by lazy {
        eventSchemaJson.encodeToString(schema.serializer, payload)
    }
}

/**
 * Post an event using the ApplicationEventPoster.
 */
public suspend fun <T> ApplicationEventPoster.post(schema: EventSchema<T>, input: T, source: String? = null): String? {
    val obj = Json.encodeToJsonElement(schema.serializer, input).jsonObject
    return post(schema.type, obj.toString(), source)
}


/**
 * Post more than one event using an ApplicationEventPoster.
 */
public suspend fun <T> ApplicationEventPoster.post(
    schema: EventSchema<T>,
    input: Collection<T>,
    source: String? = null
): List<String?> {
    val events = input.map {
        EventSchemaEvent(schema, it, source)
    }
    return post(events)
}