package com.steamstreet.dynamokt

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.util.*

/**
 * A version of AttributeValue that is serialization-friendly, allowing for
 * conversion back and forth for eventing.
 */
@Serializable
public class SerializableAttributeValue(
    public val l: List<SerializableAttributeValue>? = null,
    public val m: Map<String, SerializableAttributeValue>? = null,
    public val ss: List<String>? = null,
    public val ns: List<String>? = null,
    public val bs: List<String>? = null,
    public val n: String? = null,
    public val s: String? = null,
    public val bool: Boolean? = null,
    public val b: String? = null
) {
    public fun toAttributeValue(): AttributeValue {
        return AttributeValue.builder().apply {
            m?.let { m(it.mapValues { entry -> entry.value.toAttributeValue() }) }
            l?.let { l(it.map { it.toAttributeValue() }) }
            ss?.let { ss(it) }
            ns?.let { ns(it) }
            bs?.let {
                bs(it.map {
                    SdkBytes.fromByteArray(Base64.getDecoder().decode(it))
                })
            }
            n?.let { n(it) }
            s?.let { s(it) }
            bool?.let { bool(it) }
            b?.let { b(SdkBytes.fromByteArray(Base64.getDecoder().decode(it))) }
        }.build()
    }
}

/**
 * A 'constructor' for a serializable attribute value from the SDK attribute value.
 */
public fun SerializableAttributeValue(value: AttributeValue): SerializableAttributeValue = value.toSerializable()

public fun AttributeValue.toSerializable(): SerializableAttributeValue {
    val attribute = this
    return when {
        attribute.hasL() -> {
            SerializableAttributeValue(l = attribute.l().map { it.toSerializable() })
        }
        attribute.hasM() -> {
            SerializableAttributeValue(m = attribute.m().mapValues { entry ->
                entry.value.toSerializable()
            })
        }
        attribute.hasSs() -> {
            SerializableAttributeValue(ss = attribute.ss())
        }
        attribute.hasNs() -> {
            SerializableAttributeValue(ns = attribute.ns())
        }
        attribute.hasBs() -> {
            SerializableAttributeValue(bs = attribute.bs().map {
                String(Base64.getEncoder().encode(it.asByteArray()))
            })
        }
        attribute.n() != null -> {
            SerializableAttributeValue(n = attribute.n())
        }
        attribute.bool() != null -> {
            SerializableAttributeValue(bool = attribute.bool())
        }
        attribute.b() != null -> {
            SerializableAttributeValue(b = String(Base64.getEncoder().encode(attribute.b().asByteArray())))
        }
        attribute.s() != null -> {
            SerializableAttributeValue(s = attribute.s())
        }
        else -> throw IllegalArgumentException()
    }
}

public fun AttributeValue.toJsonString(): String {
    return attributeValueJson.encodeToString(SerializableAttributeValue.serializer(), this.toSerializable())
}

public fun AttributeValue.toJsonElement(): JsonElement {
    return attributeValueJson.encodeToJsonElement(SerializableAttributeValue.serializer(), this.toSerializable())
}

public fun String.fromJsonToAttributeValue(): AttributeValue {
    return attributeValueJson.decodeFromString(SerializableAttributeValue.serializer(), this).toAttributeValue()
}

public fun Map<String, AttributeValue>.toJsonItemString(): String = attributeValueJson.encodeToString(
    mapSerializer, this.mapValues { SerializableAttributeValue(it.value) }
)

private val mapSerializer = MapSerializer(String.serializer(), SerializableAttributeValue.serializer())

public fun JsonElement.fromJsonToItem(): Map<String, AttributeValue> = attributeValueJson.decodeFromJsonElement(
    mapSerializer, this
).mapValues {
    it.value.toAttributeValue()
}

public fun String.fromJsonToItem(): Map<String, AttributeValue> = attributeValueJson.decodeFromString(
    mapSerializer, this
).mapValues {
    it.value.toAttributeValue()
}

public val attributeValueJson: Json = Json {
    this.encodeDefaults = false
    this.ignoreUnknownKeys = true
}