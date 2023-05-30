package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
        return when {
            m != null -> AttributeValue.M(m.mapValues { entry -> entry.value.toAttributeValue() })
            l != null -> AttributeValue.L(l.map { it.toAttributeValue() })
            ss != null -> AttributeValue.Ss(ss)
            ns != null -> AttributeValue.Ns(ns)
            bs != null -> AttributeValue.Bs(bs.map {
                Base64.getDecoder().decode(it)
            })
            n != null -> AttributeValue.N(n)
            s != null -> AttributeValue.S(s)
            bool != null -> AttributeValue.Bool(bool)
            b != null -> AttributeValue.B(Base64.getDecoder().decode(b))
            else -> throw IllegalStateException()
        }
    }
}

/**
 * A 'constructor' for a serializable attribute value from the SDK attribute value.
 */
public fun SerializableAttributeValue(value: AttributeValue): SerializableAttributeValue = value.toSerializable()

public fun AttributeValue.toSerializable(): SerializableAttributeValue {
    val attribute = this
    return when {
        attribute.asLOrNull() != null -> {
            SerializableAttributeValue(l = attribute.asL().map { it.toSerializable() })
        }
        attribute.asMOrNull() != null -> {
            SerializableAttributeValue(m = attribute.asM().mapValues { entry ->
                entry.value.toSerializable()
            })
        }
        attribute.asSsOrNull() != null -> {
            SerializableAttributeValue(ss = attribute.asSs())
        }
        attribute.asNsOrNull() != null -> {
            SerializableAttributeValue(ns = attribute.asNs())
        }
        attribute.asBsOrNull() != null -> {
            SerializableAttributeValue(bs = attribute.asBs().map {
                String(Base64.getEncoder().encode(it))
            })
        }
        attribute.asNOrNull() != null -> {
            SerializableAttributeValue(n = attribute.asN())
        }
        attribute.asBoolOrNull() != null -> {
            SerializableAttributeValue(bool = attribute.asBool())
        }
        attribute.asBOrNull() != null -> {
            SerializableAttributeValue(b = String(Base64.getEncoder().encode(attribute.asB())))
        }
        attribute.asSOrNull() != null -> {
            SerializableAttributeValue(s = attribute.asS())
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