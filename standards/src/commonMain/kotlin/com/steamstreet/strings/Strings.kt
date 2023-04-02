package com.steamstreet.strings

/**
 * Using the provided regex, return the result of the first capture group
 */
public fun String?.capture(regex: String): String? {
    if (this == null) return null
    return Regex(regex).find(this)?.groupValues?.get(1)
}

/**
 * Capture all values from regex and return as list (or empty if nothing captured)
 */
public fun String?.captureAll(regex: String): List<String> {
    if (this == null) return emptyList()
    return Regex(regex).find(this)?.groupValues?.drop(1) ?: emptyList()
}

/**
 * Convert a string to a simple slug. Doesn't handle all cases, just basics.
 */
public fun String?.simpleSlug(): String {
    if (this.isNullOrBlank()) return ""
    return lowercase().replace(Regex("[^a-zA-Z0-9-]"), "-")
        .replace(Regex("[-][-]+"), "-") // replace multiple dashes with a single
}

public fun String.appendIf(text: String, predicate: () -> Boolean): String = if (predicate()) this + text else this

public fun String?.isNotNullOrBlank(): Boolean = !this.isNullOrBlank()
public fun String?.isNotNullOrEmpty(): Boolean = !this.isNullOrEmpty()