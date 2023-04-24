package com.steamstreet.dynamokt


import software.amazon.awssdk.services.dynamodb.model.AttributeAction
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.AttributeValueUpdate

/**
 * Create an update from a value.
 */
public fun AttributeValue.update(action: AttributeAction = AttributeAction.PUT): AttributeValueUpdate {
    return AttributeValueUpdate(this, action)
}

public fun AttributeValue(value: String): AttributeValue = value.attributeValue()

public fun AttributeValueUpdate(value: AttributeValue?, action: AttributeAction): AttributeValueUpdate {
    return AttributeValueUpdate.builder().action(action).value(value).build()
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
            put(name, AttributeValueUpdate(attributeValue, AttributeAction.PUT))
        } else {
            put(name, AttributeValueUpdate(null, AttributeAction.DELETE))
        }
    } else {
        put(name, AttributeValueUpdate(null, AttributeAction.DELETE))
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

/**
 * Extension functions that make it easy to create attribute value objects from Kotlin data types.
 */

private val ATTRIBUTE_FALSE = AttributeValue.builder().bool(false).build()
private val ATTRIBUTE_TRUE = AttributeValue.builder().bool(true).build()

public fun Boolean.attributeValue(): AttributeValue = if (this) ATTRIBUTE_TRUE else ATTRIBUTE_FALSE
public fun String.attributeValue(): AttributeValue = AttributeValue.builder().s(this).build()
public fun Number.attributeValue(): AttributeValue = AttributeValue.builder().n(this.toString()).build()
public fun Set<String>.attributeValue(): AttributeValue = AttributeValue.builder().ss(this).build()
public fun List<AttributeValue>.attributeValue(): AttributeValue = AttributeValue.builder().l(this).build()

public fun Map<String, AttributeValue>.attributeValue(): AttributeValue = AttributeValue.builder().m(this).build()
public fun attributeMap(vararg pairs: Pair<String, AttributeValue>): AttributeValue =
    AttributeValue.builder().m(pairs.toMap()).build()