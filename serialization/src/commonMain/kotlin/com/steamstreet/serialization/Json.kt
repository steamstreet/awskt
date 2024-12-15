package com.steamstreet.serialization

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

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

