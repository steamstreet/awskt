package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

public fun AttributeValue.toSerializable(): AttributeValue = this

public fun String.fromJsonToAttributeValue(): AttributeValue {
    return attributeValueJson.decodeFromString(AttributeValueSerializer(), this)
}

public fun Map<String, AttributeValue>.toJsonItemString(): String =
    attributeValueJson.encodeToString(mapSerializer, this)

private val mapSerializer = MapSerializer(String.serializer(), AttributeValueSerializer())

public fun JsonElement.fromJsonToItem(): Map<String, AttributeValue> = attributeValueJson.decodeFromJsonElement(
    mapSerializer, this
)

public fun String.fromJsonToItem(): Map<String, AttributeValue> = attributeValueJson.decodeFromString(
    mapSerializer, this
)

public val attributeValueJson: Json = Json {
    this.encodeDefaults = false
    this.ignoreUnknownKeys = true
}