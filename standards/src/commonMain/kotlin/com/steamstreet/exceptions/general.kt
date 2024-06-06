package com.steamstreet.exceptions

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