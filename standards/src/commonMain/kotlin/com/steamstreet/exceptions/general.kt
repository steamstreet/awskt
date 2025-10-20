package com.steamstreet.exceptions

import kotlinx.serialization.json.JsonElement

/**
 * Exception thrown when a resource or item isn't found.
 */
public class NotFoundException(message: String? = null, public val resourceId: String? = null): Exception(message)

/**
 * Thrown when an item is a duplicate
 */
public open class DuplicateItemException(
    public val id: String? = null,
    message: String? = null
) : Exception(message), MDCExceptionMixin {
    override val mdcAttributes: MutableMap<String, Any?> = hashMapOf(
        "itemId" to id
    )
}

public interface MDCExceptionMixin {
    public val mdcAttributes: MutableMap<String, Any?>
}

public class IllegalAccessException(message: String?, cause: Throwable) : Exception(message, cause)

/**
 * Exception that carries additional state as structured data.
 * When printed via printStackTrace or logging, the state will be included in the output.
 */
public open class StatefulException(
    message: String?,
    cause: Throwable? = null,
    public val state: Map<String, JsonElement> = emptyMap()
) : Exception(message, cause) {
    override fun toString(): String {
        val baseString = super.toString()
        return if (state.isNotEmpty()) {
            val stateJson = state.entries.joinToString(", ", prefix = "{", postfix = "}") { (key, value) ->
                "\"$key\": $value"
            }
            "$baseString | State: $stateJson"
        } else {
            baseString
        }
    }
}