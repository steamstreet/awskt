package com.steamstreet.exceptions

/**
 * Exception thrown when a resource or item isn't found.
 */
public class NotFoundException(message: String? = null, public val resourceId: String? = null): Exception(message)

/**
 * Thrown when an item is a duplicate
 */
public class DuplicateItemException(public val id: String? = null, message: String? = null) : Exception(message)