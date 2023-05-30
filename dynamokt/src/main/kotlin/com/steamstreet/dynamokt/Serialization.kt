package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import kotlinx.serialization.json.*
import java.util.*

/**
 * Some helpers for saving serializable objects to Dynamo. Much of this code should be
 * replaced with using the AttributeValueSerializer.
 */

private fun JsonObject.toAttributeMap(): AttributeValue? {
    if (this.isEmpty()) return null

    return this.mapValues {
        it.value.toAttributeValue()
    }.filterNullValues().takeIf { it.isNotEmpty() }?.let {
        AttributeValue.M(it)
    }
}

private fun JsonArray.toAttributeList(): AttributeValue? {
    if (this.isEmpty()) return null
    return this.mapNotNull {
        it.toAttributeValue()
    }.let {
        AttributeValue.L(it)
    }
}

private fun JsonPrimitive.toPrimitiveValue(): AttributeValue {
    return if (this.isString) {
        this.content.attributeValue()
    } else {
        this.booleanOrNull?.attributeValue() ?: this.intOrNull?.attributeValue() ?: this.longOrNull?.attributeValue()
        ?: this.floatOrNull?.attributeValue() ?: this.doubleOrNull?.attributeValue()
        ?: throw IllegalStateException("Unknown content ${this.content}")
    }
}

private fun JsonElement.toAttributeValue(): AttributeValue? {
    return when (this) {
        is JsonNull -> null
        is JsonObject -> this.toAttributeMap()
        is JsonArray -> this.toAttributeList()
        is JsonPrimitive -> this.toPrimitiveValue()
        else -> throw IllegalStateException()
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
            JsonArray(attribute.asNs().map { JsonPrimitive(it.toBigDecimal()) })
        }
        attribute.asBsOrNull() != null -> {
            JsonArray(attribute.asBs().map { JsonPrimitive(String(Base64.getEncoder().encode(it))) })
        }
        attribute.asNOrNull() != null -> {
            JsonPrimitive(attribute.asN().toBigDecimal())
        }
        attribute.asBoolOrNull() != null -> {
            JsonPrimitive(attribute.asBool())
        }
        attribute.asBOrNull() != null -> {
            JsonPrimitive(String(Base64.getEncoder().encode(attribute.asB())))
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
    value.toAttributeValue()?.let {
        set(key, it)
    }
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
    set(key, attributeValueJson.encodeToJsonElement(value))
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

