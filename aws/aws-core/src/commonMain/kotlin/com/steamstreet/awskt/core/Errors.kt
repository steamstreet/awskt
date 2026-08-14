package com.steamstreet.awskt.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Base type for every error AWS returned as a well-formed service response.
 *
 * [requestId] and [extendedRequestId] are carried for all services, not just S3. AWS Support will
 * not act on an S3 report without both, and DynamoDB benefits equally — but neither is available
 * once the response object has been discarded, so the transport captures them at the throw site.
 *
 * [rawErrorBody] exists for the same reason, one level further out. Some AWS errors carry
 * *structured payload* beyond a code and a message — DynamoDB's `ConditionalCheckFailedException`
 * returns the item that failed the condition, and its `TransactionCanceledException` returns a
 * per-item reason list — and a service module cannot recover any of that once the transport has
 * reduced the response to a code and a message. Carrying the bytes is what lets the mapping from
 * generic error to *typed* error stay in the service module, where the schema knowledge is, rather
 * than forcing protocol-agnostic `aws-core` to learn DynamoDB's error shapes.
 *
 * It is diagnostic data, not a mutable buffer: treat it as read-only.
 */
public open class AwsServiceException(
    public val code: String?,
    message: String?,
    public val statusCode: Int,
    public val requestId: String? = null,
    public val extendedRequestId: String? = null,
    cause: Throwable? = null,
    public val rawErrorBody: ByteArray? = null,
) : Exception(message ?: code ?: "AWS request failed with status $statusCode", cause) {
    override fun toString(): String = buildString {
        append("AwsServiceException(code=").append(code)
        append(", status=").append(statusCode)
        requestId?.let { append(", requestId=").append(it) }
        append(", message=").append(message)
        append(')')
    }
}

/**
 * A redirect AWS returned that we deliberately did not follow.
 *
 * Following it automatically would replay an `Authorization` header computed over one authority to
 * a different host — a broken signature and a credential sent off-origin. See `awsHttpClient`.
 */
public class AwsRedirectException(
    code: String?,
    message: String?,
    statusCode: Int,
    public val location: String?,
    public val bucketRegion: String?,
    requestId: String? = null,
    extendedRequestId: String? = null,
) : AwsServiceException(code, message, statusCode, requestId, extendedRequestId)

/** The parsed shape of an error response, protocol-independent by design. */
public class ErrorDetails(
    public val code: String?,
    public val message: String?,
)

/**
 * Turns an error response into a [ErrorDetails].
 *
 * A strategy rather than a concrete class because S3 speaks XML while everything else in v1 speaks
 * JSON — and because everything downstream (the retry table, the never-retry deny-list, the
 * clock-skew triggers) keys on a plain `String` code, so a second protocol costs one implementation
 * and changes nothing else.
 */
public interface AwsErrorParser {
    public fun parse(status: Int, headers: Map<String, String>, body: ByteArray?): ErrorDetails
}

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Parses `awsJson1_0` / `awsJson1_1` / `restJson1` error responses.
 *
 * Every rule here is load-bearing:
 *
 * - The code is read **depth-1 only and case-sensitively**. DynamoDB nests a `__type` inside
 *   `ErrorDetails[]` from a different namespace, and a `TransactionCanceledException` body carries
 *   `CancellationReasons[].Code` whose values include `ThrottlingError` and
 *   `ProvisionedThroughputExceeded`. A recursive or case-insensitive search would find one of those
 *   and classify a permanently-failed atomic transaction as retryable — replaying a write that
 *   AWS already refused.
 * - `CancellationReasons` is never consulted by the classifier. It is diagnostic only.
 * - The message may arrive as `message`, `Message` (DynamoDB) or `errorMessage`.
 */
public object AwsJsonErrorParser : AwsErrorParser {
    override fun parse(status: Int, headers: Map<String, String>, body: ByteArray?): ErrorDetails {
        val json = body?.decodeToString()?.let { text ->
            runCatching { lenientJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        }

        val rawCode = headers.headerValue("x-amzn-errortype")
            ?: json?.stringAtDepth1("code")
            ?: json?.stringAtDepth1("__type")

        val message = json?.stringAtDepth1("message")
            ?: json?.stringAtDepth1("Message")
            ?: json?.stringAtDepth1("errorMessage")
            ?: headers.headerValue("x-amzn-error-message")

        return ErrorDetails(rawCode?.let(::sanitizeErrorCode), message)
    }
}

/**
 * Parses S3's `restXml` error bodies with a ~40-line scanner rather than an XML library.
 *
 * Tolerates unknown children — the Intelligent-Tiering `InvalidObjectState` variant adds
 * `<StorageClass>` and `<AccessTier>` — and degrades to a null code on a malformed or empty body
 * instead of throwing. Throwing out of the error path replaces a useful service error with a parse
 * error, which is strictly worse for whoever has to debug it.
 */
public object RestXmlErrorParser : AwsErrorParser {
    override fun parse(status: Int, headers: Map<String, String>, body: ByteArray?): ErrorDetails {
        val xml = body?.decodeToString().orEmpty()
        return ErrorDetails(
            code = firstTagText(xml, "Code")?.let(::sanitizeErrorCode),
            message = firstTagText(xml, "Message"),
        )
    }

    private fun firstTagText(xml: String, tag: String): String? {
        val open = "<$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val end = xml.indexOf("</$tag>", start + open.length)
        if (end < 0) return null
        return unescapeXml(xml.substring(start + open.length, end)).takeIf { it.isNotBlank() }
    }

    private fun unescapeXml(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        // Must be last: unescaping it first would let "&amp;lt;" collapse to "<".
        .replace("&amp;", "&")
}

/**
 * `com.amazon.coral.service#InvalidSignatureException` and `ThrottlingException:http://…` both
 * carry a bare code that the classifier can match.
 */
internal fun sanitizeErrorCode(raw: String): String =
    raw.substringAfter('#').substringBefore(':').trim()

/** Reads only the top level, and only the exact key. Both properties are deliberate. */
private fun JsonObject.stringAtDepth1(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

/**
 * Reads a response header.
 *
 * **The invariant this relies on:** response-header maps in this library are built with lowercased
 * keys — `AwsServiceClient.send` lowercases every name as it collects them, which is the only place
 * an [AwsHttpResponse] is constructed on the request path — so one direct lookup answers what a
 * case-insensitive scan of every entry used to. [name] is lowercased here rather than required
 * lowercase because [AwsErrorParser] is public and its `headers` map comes from whoever calls it.
 *
 * A map that does *not* hold that invariant is a bug in whatever built it, not something to absorb
 * here: absorbing it hides a header map that is also being read directly elsewhere.
 */
internal fun Map<String, String>.headerValue(name: String): String? = this[name.lowercase()]

/**
 * The cancellation reasons of a `TransactionCanceledException`, for diagnostics only.
 *
 * Explicitly separate from [AwsErrorParser] so that these codes can never reach the retry
 * classifier — see [AwsJsonErrorParser].
 */
public fun transactionCancellationReasons(body: ByteArray?): List<String> {
    val json = body?.decodeToString()?.let {
        runCatching { lenientJson.parseToJsonElement(it) as? JsonObject }.getOrNull()
    } ?: return emptyList()

    val reasons = json["CancellationReasons"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return reasons.mapNotNull { element ->
        runCatching { (element as? JsonObject)?.get("Code")?.jsonPrimitive?.contentOrNull }.getOrNull()
    }
}
