package com.steamstreet.events

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Defines an event type and the serializer used for its data.
 */
public data class EventSchema<T>(
    val type: String,
    val serializer: KSerializer<T>,
    val source: String? = null
)

/**
 * Create a schema from a generic type.
 */
public inline fun <reified T> eventSchema(typeName: String, source: String? = null): EventSchema<T> =
    EventSchema(typeName, Json.serializersModule.serializer(), source)

public val eventSchemaJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    @OptIn(ExperimentalSerializationApi::class)
    explicitNulls = false
}