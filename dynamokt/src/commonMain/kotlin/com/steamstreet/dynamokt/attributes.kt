package com.steamstreet.dynamokt

/**
 * How an [AttributeValueUpdate] changes an attribute.
 *
 * Project-owned rather than a wire DTO. `attributeUpdates` is **never sent to DynamoDB** — the
 * legacy `AttributeUpdates` API it mirrors was superseded by update expressions years ago, and this
 * repo only uses these types as in-memory bookkeeping while an item is being edited. Modelling them
 * as request types would imply a wire contract that does not exist.
 */
public enum class AttributeAction { Put, Delete, Add }

/** An in-memory record of one attribute change. See [AttributeAction] for why this is not a DTO. */
public data class AttributeValueUpdate(
    public val value: AttributeValue? = null,
    public val action: AttributeAction = AttributeAction.Put,
)

/** Create an update from a value. */
public fun AttributeValue.update(action: AttributeAction = AttributeAction.Put): AttributeValueUpdate =
    AttributeValueUpdate(this, action)

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
