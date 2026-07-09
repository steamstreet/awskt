package com.steamstreet.dynamokt.exposed

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import com.steamstreet.dynamokt.AttributeValueSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * JSON configuration mirroring the base `dynamokt` library's `attributeValueJson`
 * so that pagination tokens minted here are byte-compatible with the base
 * library's `toJsonItemString()` / `fromJsonToItem()`. A token produced by one
 * layer can therefore be consumed by the other.
 */
private val pageTokenJson: Json = Json {
    encodeDefaults = false
    ignoreUnknownKeys = true
}

private val itemMapSerializer = MapSerializer(String.serializer(), AttributeValueSerializer())

/**
 * Encode a DynamoDB `LastEvaluatedKey` into an opaque, round-trippable token.
 */
internal fun Map<String, AttributeValue>.encodePageToken(): String =
    pageTokenJson.encodeToString(itemMapSerializer, this)

/**
 * Decode a token produced by [encodePageToken] (or the base library) back into
 * a DynamoDB exclusive-start key.
 */
internal fun String.decodePageToken(): Map<String, AttributeValue> =
    pageTokenJson.decodeFromString(itemMapSerializer, this)
