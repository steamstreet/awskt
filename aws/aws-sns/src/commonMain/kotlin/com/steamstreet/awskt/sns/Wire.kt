package com.steamstreet.awskt.sns

import com.steamstreet.awskt.signing.sigV4UriEncode
import kotlin.io.encoding.Base64

/**
 * The AWS **query protocol** codec: a form-encoded request out, XML back.
 *
 * ### Why this exists at all
 *
 * Every other service module in this library hands a `@Serializable` class to `callJson` and is
 * done. SNS cannot: it speaks the oldest wire format AWS still serves, where a request is a
 * `application/x-www-form-urlencoded` body naming an `Action`, structures are flattened into
 * indexed keys (`PublishBatchRequestEntries.member.1.Id`), and the response is XML.
 * `kotlinx.serialization` has no encoder for that shape and this repo has no XML deserializer, so
 * the two directions are hand-written here.
 *
 * ### Why hand-written rather than a general codec in `aws-core`
 *
 * Because the module has **two operations**. A general query encoder — flattened lists, flattened
 * maps, nested structures, the `flattened` trait, XML namespaces — is a large amount of machinery
 * to serve `Publish` and `PublishBatch`, and it would be the only consumer. `aws-core` contributes
 * the three genuinely protocol-level pieces through [com.steamstreet.awskt.core.AwsProtocol.awsQuery]
 * (content type, no target header, XML error parsing) and this file contributes the rest. If a
 * third query-protocol service ever arrives, *that* is the moment to generalize — not before.
 *
 * ### The encoder is `sigV4UriEncode`, deliberately
 *
 * The query protocol percent-encodes with exactly SigV4's unreserved set (`A-Za-z0-9-_.~`), and a
 * space is `%20` rather than the `+` that HTML form submission uses. That is precisely what
 * `aws-signing`'s [sigV4UriEncode] already does, and it is validated against AWS's own signing
 * vector corpus. Writing a second encoder here would be writing a subtly different one.
 */

/**
 * Accumulates form fields in insertion order and encodes them.
 *
 * Order is not required by AWS — the body is hashed as sent, and the signature covers those exact
 * bytes whatever order they are in — but it is deterministic, which is what lets a test assert a
 * whole body rather than pick it apart.
 */
internal class FormBody {
    private val fields = mutableListOf<Pair<String, String>>()

    /** Appends a field. A null [value] is omitted entirely, which is how the protocol says "absent". */
    fun put(name: String, value: String?): FormBody = apply {
        if (value != null) fields += name to value
    }

    fun put(name: String, value: Int?): FormBody = put(name, value?.toString())

    fun encode(): ByteArray =
        fields.joinToString("&") { (k, v) -> "${sigV4UriEncode(k)}=${sigV4UriEncode(v)}" }
            .encodeToByteArray()
}

/**
 * Flattens a message-attribute map under [prefix].
 *
 * The wire shape is `<prefix>.entry.<n>.Name` / `.Value.DataType` / `.Value.StringValue` /
 * `.Value.BinaryValue`, with **`n` starting at 1**, not 0 — an off-by-one here does not fail
 * loudly, it silently drops the first attribute, because SNS reads the indices it recognises and
 * ignores the rest.
 *
 * Iteration order is the map's own, so a `mapOf(...)` at a call site produces a stable body.
 */
internal fun FormBody.putMessageAttributes(prefix: String, attributes: Map<String, MessageAttributeValue>?) {
    attributes?.entries?.forEachIndexed { index, (name, value) ->
        val entry = "$prefix.entry.${index + 1}"
        put("$entry.Name", name)
        put("$entry.Value.DataType", value.dataType)
        put("$entry.Value.StringValue", value.stringValue)
        put("$entry.Value.BinaryValue", value.binaryValue?.let { Base64.Default.encode(it) })
    }
}

/**
 * Flattens a plain `Map<String, String>` under [prefix].
 *
 * **`key` and `value`, lowercased — not the `Name`/`Value` that [putMessageAttributes] uses.** The
 * two are genuinely different on the wire: `MessageAttributes` carries an
 * `@xmlName`-style override in SNS's model, and the endpoint-attribute maps do not, so they get the
 * query protocol's default map spelling. Using one module's spelling for the other's map produces a
 * request SNS accepts and silently ignores, which is the worst of the available failures.
 *
 * Verified against the AWS SDK's own serializers: `PublishOperationSerializer` carries a
 * `FormUrlMapName("Name", "Value")` trait and `CreatePlatformEndpointOperationSerializer` carries
 * none.
 */
internal fun FormBody.putStringMap(prefix: String, attributes: Map<String, String>?) {
    attributes?.entries?.forEachIndexed { index, (key, value) ->
        val entry = "$prefix.entry.${index + 1}"
        put("$entry.key", key)
        put("$entry.value", value)
    }
}

// -- XML reading ---------------------------------------------------------------------------------

/**
 * A deliberately small XML reader: enough for two response shapes, and no more.
 *
 * Built on the same premise as `aws-core`'s [com.steamstreet.awskt.core.RestXmlErrorParser] — a
 * scanner rather than a parser, tolerant of unknown children and of namespace declarations it does
 * not understand, degrading to null rather than throwing. Throwing out of a response reader
 * replaces a service answer with a parse error, which is strictly worse for whoever debugs it.
 *
 * **What it cannot do**, stated so nobody grows it by accident: attributes, namespace prefixes on
 * tag names (`<sns:MessageId>`), CDATA sections, comments, and self-closing tags with content. SNS
 * emits none of those in the two responses this module reads. A third operation whose response
 * needs any of them needs a real parser, not another special case here.
 */
internal object Xml {

    /**
     * The text of the first `<tag>…</tag>` at any depth, or null.
     *
     * "At any depth" is what makes this usable without modelling the envelope: a `Publish` response
     * nests `MessageId` inside `PublishResult` inside `PublishResponse`, and none of that is worth
     * describing to find one string.
     */
    fun text(xml: String, tag: String): String? {
        val open = "<$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val end = xml.indexOf("</$tag>", start + open.length)
        if (end < 0) return null
        return unescape(xml.substring(start + open.length, end))
    }

    /**
     * The raw inner XML of the first `<tag>…</tag>`, or null.
     *
     * Returns null for a self-closing `<tag/>`, which is how SNS spells an empty `Successful` or
     * `Failed` list — and treating that as "no members" rather than as a parse failure is the whole
     * reason this returns null instead of throwing.
     */
    fun block(xml: String, tag: String): String? {
        val open = "<$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val end = xml.indexOf("</$tag>", start + open.length)
        if (end < 0) return null
        return xml.substring(start + open.length, end)
    }

    /**
     * The inner XML of every `<tag>…</tag>` in [xml], in document order.
     *
     * Non-recursive by construction: it scans for open/close pairs in order and does not track
     * nesting. That is correct for every shape this module reads — batch result members, endpoint
     * members and attribute entries all contain no nested element of their own name — and it is the
     * assumption that would break first if this were reused elsewhere.
     */
    fun elements(xml: String?, tag: String): List<String> {
        if (xml == null) return emptyList()
        val open = "<$tag>"
        val close = "</$tag>"
        val out = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val start = xml.indexOf(open, cursor)
            if (start < 0) break
            val end = xml.indexOf(close, start + open.length)
            if (end < 0) break
            out += xml.substring(start + open.length, end)
            cursor = end + close.length
        }
        return out
    }

    /** The inner XML of every `<member>` in [xml]. */
    fun members(xml: String?): List<String> = elements(xml, "member")

    /**
     * Reads an `<entry><key>…</key><value>…</value></entry>` list into a map.
     *
     * The query protocol's default map encoding, which SNS uses for every endpoint and platform
     * attribute map. An entry whose `<value>` is empty or self-closing reads as `""` rather than
     * being dropped: SNS uses an empty string to mean "set but blank", and dropping it would make
     * that indistinguishable from "absent".
     */
    fun attributeMap(xml: String?): Map<String, String> = elements(xml, "entry").mapNotNull { entry ->
        val key = text(entry, "key") ?: return@mapNotNull null
        key to (text(entry, "value") ?: "")
    }.toMap()

    /**
     * The five predefined XML entities.
     *
     * `&amp;` is unescaped **last**, and that ordering is load-bearing: doing it first would let a
     * literal `&amp;lt;` — which means the text "&lt;" — collapse all the way to "<". The same note
     * appears on `RestXmlErrorParser.unescapeXml`, for the same reason.
     */
    private fun unescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
