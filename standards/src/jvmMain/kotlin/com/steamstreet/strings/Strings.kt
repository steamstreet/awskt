package com.steamstreet.strings

import java.text.Normalizer
import java.util.*
import java.util.regex.Pattern

private val NON_LATIN = Pattern.compile("[^\\w-]")
private val WHITESPACE = Pattern.compile("[\\s]")

/**
 * Get a unique identifier 'slug' from a name. We'll be using these for
 * identifiers.
 */
public fun String.toSlug(): String {
    val noWhitespace = WHITESPACE.matcher(this).replaceAll("-")
    val normalized = Normalizer.normalize(noWhitespace, Normalizer.Form.NFD)
    val slug = NON_LATIN.matcher(normalized).replaceAll("")
    return slug.lowercase(Locale.ENGLISH)
}