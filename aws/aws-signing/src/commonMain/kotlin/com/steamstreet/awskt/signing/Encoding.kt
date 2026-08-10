package com.steamstreet.awskt.signing

import org.kotlincrypto.hash.sha2.SHA256
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

private const val HEX_LOWER = "0123456789abcdef"
private const val HEX_UPPER = "0123456789ABCDEF"

/** SHA-256 of a zero-length body, as AWS spells it. */
internal const val EMPTY_BODY_SHA256 =
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

internal const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

internal fun ByteArray.toHexLower(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX_LOWER[v ushr 4]
        out[i * 2 + 1] = HEX_LOWER[v and 0x0F]
    }
    return out.concatToString()
}

internal fun sha256(bytes: ByteArray): ByteArray = SHA256().digest(bytes)

internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    HmacSHA256(key).doFinal(data)

/**
 * RFC 3986 unreserved set: `A-Z a-z 0-9 - . _ ~`. SigV4 percent-encodes *everything* else,
 * including sub-delimiters that a general-purpose URL encoder would leave alone.
 */
private val UNRESERVED = BooleanArray(128).apply {
    for (c in 'A'..'Z') this[c.code] = true
    for (c in 'a'..'z') this[c.code] = true
    for (c in '0'..'9') this[c.code] = true
    this['-'.code] = true
    this['.'.code] = true
    this['_'.code] = true
    this['~'.code] = true
}

/**
 * Percent-encodes for SigV4 canonicalization: uppercase hex, over UTF-8 bytes, unreserved
 * characters only passed through.
 *
 * Do not substitute a platform URL encoder here. AWS explicitly warns against it, and the failure
 * mode is a signature mismatch on a small fraction of requests rather than an outright break.
 *
 * @param encodeSlash when false, `/` passes through — used for whole paths rather than segments.
 */
public fun sigV4UriEncode(value: String, encodeSlash: Boolean = true): String {
    val bytes = value.encodeToByteArray()
    val sb = StringBuilder(bytes.size)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        when {
            v < 0x80 && UNRESERVED[v] -> sb.append(v.toChar())
            v == '/'.code && !encodeSlash -> sb.append('/')
            else -> {
                sb.append('%')
                sb.append(HEX_UPPER[v ushr 4])
                sb.append(HEX_UPPER[v and 0x0F])
            }
        }
    }
    return sb.toString()
}

/**
 * Builds the canonical URI.
 *
 * Segments are encoded first and normalized second, matching AWS's reference implementation. The
 * two flags are independent and both are cleared for S3:
 *
 * - [doubleUriEncode] false leaves an already-encoded path untouched, so `baz%3Cqux` stays
 *   `baz%3Cqux` rather than becoming `baz%253Cqux`.
 * - [normalize] false preserves `.`, `..` and `//` segments, because an S3 bucket may legitimately
 *   hold an object named `my-object//example//photo.user`.
 */
internal fun canonicalUri(
    wirePath: String,
    doubleUriEncode: Boolean,
    normalize: Boolean,
): String {
    if (wirePath.isEmpty()) return "/"
    val body = wirePath.removePrefix("/")
    if (body.isEmpty()) return "/"

    var trailingSlash = body.endsWith("/")
    val core = if (trailingSlash) body.dropLast(1) else body
    var segments: List<String> = core.split('/')

    if (normalize) {
        val out = ArrayList<String>(segments.size)
        for (segment in segments) {
            when (segment) {
                ".", "" -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(segment)
            }
        }
        segments = out
        if (segments.isEmpty()) trailingSlash = true
    }

    val encoded =
        if (doubleUriEncode) segments.map { sigV4UriEncode(it, encodeSlash = true) } else segments

    return buildString {
        if (encoded.isNotEmpty()) append('/')
        append(encoded.joinToString("/"))
        if (trailingSlash) append('/')
    }
}

/**
 * Builds the canonical query string: encode first, then sort by encoded key and then encoded value.
 * Sorting before encoding gives a different — and wrong — order.
 */
internal fun canonicalQuery(parameters: List<Pair<String, String>>): String {
    if (parameters.isEmpty()) return ""
    return parameters
        .map { (k, v) -> sigV4UriEncode(k) to sigV4UriEncode(v) }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .joinToString("&") { (k, v) -> "$k=$v" }
}

/**
 * Trims a header value and collapses internal runs of spaces.
 *
 * A plain `trim()` passes `get-vanilla` and fails `get-header-value-trim`, which is the entire
 * reason this is a named function with its own test.
 */
internal fun canonicalHeaderValue(value: String): String {
    val trimmed = value.trim()
    if (!trimmed.contains("  ")) return trimmed
    return buildString(trimmed.length) {
        var previousWasSpace = false
        for (ch in trimmed) {
            if (ch == ' ') {
                if (!previousWasSpace) append(' ')
                previousWasSpace = true
            } else {
                append(ch)
                previousWasSpace = false
            }
        }
    }
}
