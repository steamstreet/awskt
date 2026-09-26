package com.steamstreet.awskt.sts

import com.steamstreet.awskt.signing.sigV4UriEncode

/**
 * The AWS **query protocol** codec for STS: a form-encoded request out, XML back.
 *
 * ### Why this duplicates `aws-sns`'s `Wire.kt` rather than sharing it
 *
 * STS is the second query-protocol service in this library, after SNS, and `aws-sns`'s own KDoc
 * says the moment to generalise is the *third*. The overlap is a twenty-line form accumulator and a
 * tag scanner; moving either into `aws-core` would make it public API of the transport module to
 * save one copy, and `aws-sns`'s reader is internal for a reason — it is deliberately too small to
 * be anybody's general XML parser.
 *
 * ### The encoder is `sigV4UriEncode`, deliberately
 *
 * The query protocol percent-encodes with exactly SigV4's unreserved set, and a space is `%20`
 * rather than `+`. This matters more here than in SNS: a session policy is a JSON document full of
 * spaces, quotes, colons and braces, and a `+`-for-space encoder produces a policy STS accepts and
 * reads differently.
 */

/** Accumulates form fields in insertion order and encodes them. A null value is omitted. */
internal class FormBody {
    private val fields = mutableListOf<Pair<String, String>>()

    fun put(name: String, value: String?): FormBody = apply {
        if (value != null) fields += name to value
    }

    fun put(name: String, value: Long?): FormBody = put(name, value?.toString())

    fun encode(): ByteArray =
        fields.joinToString("&") { (k, v) -> "${sigV4UriEncode(k)}=${sigV4UriEncode(v)}" }
            .encodeToByteArray()
}

/**
 * Flattens a list under [prefix] as `<prefix>.member.<n>`, with **`n` starting at 1**.
 *
 * Zero-based indices do not fail: STS reads the indices it recognises and ignores the rest, so an
 * off-by-one silently drops the first entry — for `PolicyArns`, that widens the session.
 */
internal fun FormBody.putMembers(prefix: String, values: List<String>, field: String? = null) {
    values.forEachIndexed { index, value ->
        val member = "$prefix.member.${index + 1}"
        put(if (field == null) member else "$member.$field", value)
    }
}

/** Flattens session tags as `Tags.member.<n>.Key` / `.Value` — STS's `Tag` structure, not a map. */
internal fun FormBody.putTags(tags: Map<String, String>) {
    tags.entries.forEachIndexed { index, (key, value) ->
        val member = "Tags.member.${index + 1}"
        put("$member.Key", key)
        put("$member.Value", value)
    }
}

/**
 * A deliberately small XML reader: enough for the two responses this module reads, and no more.
 *
 * Same premise and same limits as `aws-sns`'s: a scanner, not a parser; tolerant of namespace
 * declarations and unknown children; no attributes, prefixed tag names, CDATA or comments. STS emits
 * none of those in `AssumeRole` or `GetCallerIdentity`.
 */
internal object Xml {

    /** The text of the first `<tag>…</tag>` at any depth, or null. */
    fun text(xml: String?, tag: String): String? = block(xml, tag)?.let(::unescape)

    /** The raw inner XML of the first `<tag>…</tag>`, or null — including for a self-closing tag. */
    fun block(xml: String?, tag: String): String? {
        if (xml == null) return null
        val open = "<$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val end = xml.indexOf("</$tag>", start + open.length)
        if (end < 0) return null
        return xml.substring(start + open.length, end)
    }

    /** The five predefined entities, `&amp;` last so `&amp;lt;` stays the text "&lt;". */
    private fun unescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
