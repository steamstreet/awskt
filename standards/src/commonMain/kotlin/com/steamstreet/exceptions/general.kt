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
 */
public open class StatefulException(
    message: String?,
    cause: Throwable? = null,
    public val state: Map<String, JsonElement> = emptyMap()
) : Exception(message, cause)