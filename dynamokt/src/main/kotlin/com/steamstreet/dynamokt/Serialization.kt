package com.steamstreet.dynamokt

import kotlinx.serialization.json.*
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
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
        AttributeValue.builder().m(it).build()
    }
}

private fun JsonArray.toAttributeList(): AttributeValue? {
    if (this.isEmpty()) return null
    return this.map {
        it.toAttributeValue()
    }.let {
        AttributeValue.builder().l(it).build()
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
        attribute.hasL() -> {
            buildJsonArray {
                attribute.l()!!.forEach {
                    add(it.asJsonElement())
                }
            }
        }
        attribute.hasM() -> {
            buildJsonObject {
                attribute.m().forEach { mapElement ->
                    mapElement.value.asJsonElement().let {
                        this.put(mapElement.key, it)
                    }
                }
            }
        }
        attribute.hasSs() -> {
            JsonArray(attribute.ss().map { JsonPrimitive(it) })
        }
        attribute.hasNs() -> {
            JsonArray(attribute.ns().map { JsonPrimitive(it.toBigDecimal()) })
        }
        attribute.hasBs() -> {
            JsonArray(attribute.bs().map { JsonPrimitive(String(Base64.getEncoder().encode(it.asByteArray()))) })
        }
        attribute.n() != null -> {
            JsonPrimitive(attribute.n().toBigDecimal())
        }
        attribute.bool() != null -> {
            JsonPrimitive(attribute.bool())
        }
        attribute.b() != null -> {
            JsonPrimitive(String(Base64.getEncoder().encode(attribute.b().asByteArray())))
        }
        attribute.s() != null -> {
            JsonPrimitive(attribute.s())
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

public inline fun <reified T> Item.getObject(key: String): T? = deserialize(key)
public inline fun <reified T> Item.deserialize(key: String): T? {
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

