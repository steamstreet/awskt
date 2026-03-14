package com.steamstreet.dynamokt


import aws.sdk.kotlin.services.dynamodb.model.AttributeAction
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.AttributeValueUpdate

/**
 * Create an update from a value.
 */
public fun AttributeValue.update(action: AttributeAction = AttributeAction.Put): AttributeValueUpdate {
    return AttributeValueUpdate {
        this.action = action
        this.value = this@update
    }
}

public fun AttributeValue(value: String): AttributeValue = AttributeValue.S(value)
public fun AttributeValueUpdate(value: AttributeValue?, action: AttributeAction): AttributeValueUpdate {
    return AttributeValueUpdate {
        this.action = action
        this.value = value
    }
}

/**
 * Add a PUT update
 */
public fun <T> MutableMap<String, AttributeValueUpdate>.update(name: String, value: T?) {
    if (value != null) {
        val attributeValue = when (value) {
            is String -> AttributeValue(value)
            is Boolean -> AttributeValue(value.toString())
            is Number -> value.attributeValue()
            is List<*> -> if (value.isEmpty()) null else value.mapNotNull { it as? String }.toSet().attributeValue()
            else -> throw IllegalArgumentException()
        }
        if (attributeValue != null) {
            put(name, AttributeValueUpdate(attributeValue, AttributeAction.Put))
        } else {
            put(name, AttributeValueUpdate(null, AttributeAction.Delete))
        }
    } else {
        put(name, AttributeValueUpdate(null, AttributeAction.Delete))
    }
}

public fun HashMap<String, AttributeValueUpdate>.delete(name: String): Unit = update(name, null)

/**
 * Find the differences between two DynamoDb records. Returns a list of the keys where there are differences
 */
public fun findDifferences(record1: Map<String, AttributeValue>?, record2: Map<String, AttributeValue>?): List<String> {
    return if (record1 != null && record2 != null) {
        (record1.keys + record2.keys).filter {
            !record1.containsKey(it) || !record2.containsKey(it) || record1[it] != record2[it]
        }
    } else if (record1 != null) {
        record1.keys.toList()
    } else if (record2 != null) {
        record2.keys.toList()
    } else {
        emptyList()
    }
}

private fun diff(value1: AttributeValue, value2: AttributeValue, path: List<String> = emptyList()): List<String> {
    val diff = mutableListOf<String>()

    if (value1.asMOrNull() != null && value2.asMOrNull() != null) {
        val m1 = value1.asM()
        val m2 = value2.asM()
        m1.keys.forEach {
            val v1 = m1[it]
            val v2 = m2[it]

            if (v1 != v2) {
                diff += (path + it).joinToString(".")
                if (v1 != null && v2 != null) {
                    diff(v1, v2, path)
                }
            }
        }
    }

    return diff
}

/**
 * Diff two items
 */
public fun diff(value1: Map<String, AttributeValue>, value2: Map<String, AttributeValue>): List<String> {
    return diff(AttributeValue.M(value1), AttributeValue.M(value2))
}

public fun diff(item1: Item, item2: Item): List<String> =
    diff(item1.attributes, item2.attributes)


/**
 * Extension functions that make it easy to create attribute value objects from Kotlin data types.
 */

private val ATTRIBUTE_FALSE = AttributeValue.Bool(false)
private val ATTRIBUTE_TRUE = AttributeValue.Bool(true)

public fun Boolean.attributeValue(): AttributeValue = if (this) ATTRIBUTE_TRUE else ATTRIBUTE_FALSE
public fun String.attributeValue(): AttributeValue = AttributeValue.S(this)
public fun Number.attributeValue(): AttributeValue = AttributeValue.N(this.toString())
public fun Set<String>.attributeValue(): AttributeValue = AttributeValue.Ss(this.toList())
public fun List<AttributeValue>.attributeValue(): AttributeValue = AttributeValue.L(this)

public fun Map<String, AttributeValue>.attributeValue(): AttributeValue = AttributeValue.M(this)
public fun attributeMap(vararg pairs: Pair<String, AttributeValue>): AttributeValue =
    AttributeValue.M(pairs.toMap())