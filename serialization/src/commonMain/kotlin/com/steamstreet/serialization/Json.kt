package com.steamstreet.serialization

import kotlinx.serialization.json.*

/**
 * Copy the given object, allowing for augmenting or replacing fields.
 */
public fun JsonObject.copy(block: JsonObjectBuilder.() -> Unit): JsonObject {
    return buildJsonObject {
        this@copy.forEach { (key, value) ->
            this.put(key, value)
        }
        block()
    }
}

/**
 * Copy and apply the update or create a new object
 */
public fun JsonObject?.copyOrBuild(block: JsonObjectBuilder.() -> Unit): JsonObject {
    return this?.copy(block) ?: buildJsonObject(block)
}

/**
 * Compare two JsonObjects for deep equality.
 * Returns true if both objects have the same keys and values, recursively.
 */
public fun JsonObject.deepEquals(other: JsonObject): Boolean {
    if (this.size != other.size) return false

    return this.all { (key, value) ->
        val otherValue = other[key] ?: return false
        compareJsonElements(value, otherValue)
    }
}

/**
 * Compare two JsonElements for deep equality.
 */
private fun compareJsonElements(left: JsonElement, right: JsonElement): Boolean {
    return when {
        left is JsonObject && right is JsonObject -> left.deepEquals(right)
        left is JsonArray && right is JsonArray -> {
            left.size == right.size && left.zip(right).all { (l, r) ->
                compareJsonElements(l, r)
            }
        }

        left is JsonPrimitive && right is JsonPrimitive -> left == right
        else -> false
    }
}

/**
 * Create a new JsonObject containing the differences between two JsonObjects.
 * The result includes:
 * - Keys that exist in 'other' but not in 'this' (additions)
 * - Keys that exist in both but have different values (modifications)
 * - Does NOT include keys that exist only in 'this' (removals)
 *
 * For nested objects, recursively computes differences.
 */
public fun JsonObject.diff(other: JsonObject): JsonObject {
    return buildJsonObject {
        other.forEach { (key, otherValue) ->
            val thisValue = this@diff[key]

            when {
                // Key doesn't exist in this object - it's an addition
                thisValue == null -> {
                    put(key, otherValue)
                }
                // Both are objects - compute recursive diff
                thisValue is JsonObject && otherValue is JsonObject -> {
                    val nestedDiff = thisValue.diff(otherValue)
                    if (nestedDiff.isNotEmpty()) {
                        put(key, nestedDiff)
                    }
                }
                // Values are different - it's a modification
                !compareJsonElements(thisValue, otherValue) -> {
                    put(key, otherValue)
                }
                // Values are the same - no difference, don't include
            }
        }
    }
}

/**
 * Create a comprehensive diff that includes additions, modifications, and removals.
 * Returns a JsonObject with three optional fields:
 * - "added": keys that exist in 'other' but not in 'this'
 * - "modified": keys that exist in both but have different values
 * - "removed": keys that exist in 'this' but not in 'other'
 */
public fun JsonObject.comprehensiveDiff(other: JsonObject): JsonObject {
    val added = buildJsonObject {
        other.forEach { (key, value) ->
            if (key !in this@comprehensiveDiff) {
                put(key, value)
            }
        }
    }

    val modified = buildJsonObject {
        this@comprehensiveDiff.forEach { (key, thisValue) ->
            val otherValue = other[key]
            if (otherValue != null && !compareJsonElements(thisValue, otherValue)) {
                when {
                    thisValue is JsonObject && otherValue is JsonObject -> {
                        val nestedDiff = thisValue.diff(otherValue)
                        if (nestedDiff.isNotEmpty()) {
                            put(key, nestedDiff)
                        }
                    }

                    else -> put(key, otherValue)
                }
            }
        }
    }

    val removed = buildJsonObject {
        this@comprehensiveDiff.forEach { (key, value) ->
            if (key !in other) {
                put(key, value)
            }
        }
    }

    return buildJsonObject {
        if (added.isNotEmpty()) put("added", added)
        if (modified.isNotEmpty()) put("modified", modified)
        if (removed.isNotEmpty()) put("removed", removed)
    }
}

