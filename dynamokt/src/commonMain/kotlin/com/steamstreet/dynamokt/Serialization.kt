@file:OptIn(ExperimentalEncodingApi::class)

package com.steamstreet.dynamokt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Some helpers for saving serializable objects to Dynamo. Much of this code should be
 * replaced with using the AttributeValueSerializer.
 */

private fun JsonObject.toAttributeMap(): AttributeValue {
    if (this.isEmpty()) return AttributeValue.M(emptyMap())

    return this.mapValues {
        it.value.toAttributeValue()
    }.filterNullValues().let {
        if (it.keys.any { key -> key.isBlank() }) {
            throw IllegalArgumentException("Object keys cannot be blank.")
        }
        AttributeValue.M(it)
    }
}

private fun JsonArray.toAttributeList(): AttributeValue {
    if (this.isEmpty()) return AttributeValue.L(emptyList())
    return this.mapNotNull {
        it.toAttributeValue()
    }.let {
        AttributeValue.L(it)
    }
}

/**
 * Converts a JSON scalar to an attribute.
 *
 * The number branch takes the **raw literal**, not a parsed numeric type. The chain this replaces
 * was `intOrNull ?: longOrNull ?: floatOrNull ?: doubleOrNull` — and `floatOrNull` sits *before*
 * `doubleOrNull`, so every non-integral number was coerced through a 32-bit float and truncated to
 * roughly 7 significant digits before becoming an `AttributeValue.N`. DynamoDB numbers carry up to
 * 38. A price or a large identifier was silently rounded on the way in.
 *
 * Taking `content` directly is also the only lossless option: `N` already stores an exact decimal
 * string, so parsing and re-rendering can only lose information.
 */
private fun JsonPrimitive.toPrimitiveValue(): AttributeValue {
    return if (this.isString) {
        this.content.attributeValue()
    } else {
        this.booleanOrNull?.attributeValue()
            ?: this.content.takeIf { it.isNotBlank() && it != "null" }?.let { AttributeValue.N(it) }
            ?: throw IllegalStateException("Unknown content ${this.content}")
    }
}

/**
 * Convert a standard Json Element to an Attribute Value.
 */
public fun JsonElement.toAttributeValue(): AttributeValue? {
    return when (this) {
        is JsonNull -> null
        is JsonObject -> this.toAttributeMap()
        is JsonArray -> this.toAttributeList()
        is JsonPrimitive -> this.toPrimitiveValue()
    }
}

public fun AttributeValue.asJsonElement(): JsonElement {
    val attribute = this
    return when {
        attribute.asLOrNull() != null -> {
            buildJsonArray {
                attribute.asL().forEach {
                    add(it.asJsonElement())
                }
            }
        }
        attribute.asMOrNull() != null -> {
            buildJsonObject {
                attribute.asM().forEach { mapElement ->
                    mapElement.value.asJsonElement().let {
                        this.put(mapElement.key, it)
                    }
                }
            }
        }
        attribute.asSsOrNull() != null -> {
            JsonArray(attribute.asSs().map { JsonPrimitive(it) })
        }
        attribute.asNsOrNull() != null -> {
            JsonArray(attribute.asNs().map { unquotedNumber(it) })
        }
        attribute.asBsOrNull() != null -> {
            JsonArray(attribute.asBs().map { JsonPrimitive(Base64.encode(it)) })
        }
        attribute.asNOrNull() != null -> {
            unquotedNumber(attribute.asN())
        }
        attribute.asBoolOrNull() != null -> {
            JsonPrimitive(attribute.asBool())
        }
        attribute.asBOrNull() != null -> {
            JsonPrimitive(Base64.encode(attribute.asB()))
        }
        attribute.asSOrNull() != null -> {
            JsonPrimitive(attribute.asS())
        }
        else -> throw IllegalArgumentException()
    }
}

/**
 * Set the value of a key to a JsonElement.
 */
public fun MutableItem.set(key: String, value: JsonElement) {
    val attrValue = value.toAttributeValue()
    set(key, attrValue)
}

/**
 * Put a JsonObject in the record. This will include the values of the keys
 * as root entries in the item.
 */
public fun MutableItem.putJson(value: JsonObject) {
    value.entries.forEach {
        set(it.key, it.value)
    }
}

public fun Item.getJson(): JsonObject {
    return buildJsonObject {
        allAttributes.forEach {
            this.put(it.key, it.value.asJsonElement())
        }
    }
}

/**
 * Put an object. Arrays and primitives are NOT supported for the value.
 */
public inline fun <reified T> MutableItem.put(value: T) {
    putJson(attributeValueJson.encodeToJsonElement(value).jsonObject)
}

/**
 * Set the value of the given key. The value can be an object, list or primitive.
 */
public inline fun <reified T> MutableItem.setObject(key: String, value: T) {
    set(key, this.dynamo.dynamoKt.entityJsonEncoder.encodeToJsonElement(value))
}

public suspend inline fun <reified T> Item.getObject(key: String): T? = deserialize(key)
public suspend inline fun <reified T> Item.deserialize(key: String): T? {
    return get(key)?.asJsonElement()?.let {
        attributeValueJson.decodeFromJsonElement(it)
    }
}

public inline fun <reified T> Item.deserialize(): T {
    return attributeValueJson.decodeFromJsonElement(getJson())
}

public inline fun <reified T> AttributeValue.deserialize(): T {
    return attributeValueJson.decodeFromJsonElement(this.asJsonElement())
}


/**
 * Emits `N` as a JSON number without going through any numeric type.
 *
 * `toBigDecimal()` was both a JVM-only coupling (`java.math.BigDecimal`) and a lossy one for the
 * 38-digit values DynamoDB permits. `JsonUnquotedLiteral` writes the exact decimal string as a bare
 * number instead.
 *
 * Two documented sharp edges: it **throws** when handed the literal string `"null"`, which is why
 * the caller filters that; and `JsonUnquotedLiteral("1.0") != JsonPrimitive(1.0)`, so any test
 * comparing `JsonElement` instances rather than rendered JSON sees a difference.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun unquotedNumber(value: String): JsonElement =
    if (value.isBlank() || value == "null") JsonNull else JsonUnquotedLiteral(value)
