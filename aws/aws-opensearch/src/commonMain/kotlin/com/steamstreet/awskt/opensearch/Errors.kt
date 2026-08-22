package com.steamstreet.awskt.opensearch

import com.steamstreet.awskt.core.AwsErrorParser
import com.steamstreet.awskt.core.AwsServiceException
import com.steamstreet.awskt.core.ErrorDetails
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * The code reported for any 429, whatever OpenSearch called it. See [OpenSearchErrorParser].
 *
 * Not an OpenSearch name: it is the AWS name, chosen because `aws-core`'s `KNOWN_ERROR_TYPES` is
 * keyed on AWS names and that table is what paces the retry.
 */
internal const val THROTTLING_CODE: String = "ThrottlingException"

/**
 * Parses OpenSearch's error envelope, which is **not** an AWS envelope.
 *
 * This is the reason the module exists. Every other service in this library answers a failure with
 * either the AWS-JSON shape (`x-amzn-errortype`, `__type`) or S3's XML; a managed OpenSearch domain
 * answers with the search engine's own document, and `AwsJsonErrorParser` finds nothing in it:
 *
 * ```json
 * {"error":{"type":"index_not_found_exception","reason":"no such index [venues]"},"status":404}
 * ```
 *
 * Without this parser every OpenSearch failure would surface as an `AwsServiceException` with a
 * null code and a null message — a bare status, and nothing a caller or the retry classifier could
 * key on.
 *
 * ### Four shapes, not one
 *
 * 1. **The object form above.** `error.type` is the code, `error.reason` the message.
 * 2. **`error` as a bare string**, which is what a rejected HTTP method answers with:
 *    `{"error":"Incorrect HTTP method for uri [/x] and method [PUT], allowed: [POST]","status":405}`.
 *    There is no type in it, so the code stays null and the string becomes the message.
 * 3. **`error.type` absent but `error.root_cause[0].type` present.** Rare on a top-level rejection
 *    and common on a shard-level one, and the root cause is the useful half either way.
 * 4. **The AWS front end answering before OpenSearch sees the request** — an unsigned or
 *    unauthorized call never reaches the engine, and comes back as `{"Message":"User: … is not
 *    authorized to perform: es:ESHttpPost"}` or with an `x-amzn-errortype` header. Falling back to
 *    the AWS shape is what keeps an IAM misconfiguration — by far the most common first failure
 *    against a new domain — from reporting as an empty error.
 *
 * ### A 429 is reported as [THROTTLING_CODE], not as its OpenSearch type
 *
 * The one place this parser deliberately does not report what it read. `aws-core` classifies a
 * response by code first and status second, and **429 is in neither table**: it is not in
 * `KNOWN_STATUS_CODES` (which holds 500/502/503/504) and OpenSearch's names for it —
 * `es_rejected_execution_exception` when a thread pool queue overflows,
 * `circuit_breaking_exception` when the heap breaker trips — are not in `KNOWN_ERROR_TYPES`. Left
 * honest, every throttled search would fail on its first attempt, which for a search-serving Lambda
 * is the failure that matters most and the one a retry fixes most reliably.
 *
 * Substituting the AWS name is what makes `aws-core` pace it on the throttling curve — a 1-second
 * base rather than 25 ms — with no change to `aws-core` and no OpenSearch names in its tables.
 *
 * The substitution is safe in the direction that matters. Over-retrying is the dangerous
 * misclassification, and a 429 cannot be over-retried into a duplicate write: the request was
 * *rejected*, not applied, so there is nothing to double-apply. (Per-document 429s inside a
 * `_bulk` response are a different thing entirely — they arrive inside a **200** and never reach
 * this parser. See [OpenSearch.bulk].)
 *
 * Nothing is lost by it: the real type is still in the response body, [OpenSearchException] carries
 * it as [OpenSearchException.type], and [openSearchErrorType] reads it back out.
 *
 * ### Never throws
 *
 * A malformed or empty body degrades to a null code, as [com.steamstreet.awskt.core.RestXmlErrorParser]
 * does. Throwing out of the error path replaces a useful service error with a parse error.
 */
public object OpenSearchErrorParser : AwsErrorParser {
    override fun parse(status: Int, headers: Map<String, String>, body: ByteArray?): ErrorDetails {
        val json = body.asJsonObject()
        val error = json?.get("error")

        val type = json.errorType()
            // Shape 4: the AWS front end refused before OpenSearch saw the request.
            ?: headers["x-amzn-errortype"]?.let { it.substringAfter('#').substringBefore(':').trim() }

        val message = (error as? JsonObject)?.string("reason")
            ?: (error as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: json?.string("message")
            ?: json?.string("Message")
            ?: headers["x-amzn-error-message"]

        return ErrorDetails(
            // See the KDoc: the one deliberate substitution, and only for 429.
            code = if (status == 429) THROTTLING_CODE else type,
            message = message,
        )
    }
}

/**
 * A failure reported by OpenSearch, or by the AWS front end in front of it.
 *
 * Extends `aws-core`'s [AwsServiceException] so every failure carries `code`, `statusCode`,
 * `requestId` and `extendedRequestId`, exactly as `aws-scheduler`'s hierarchy does.
 *
 * There is deliberately **no subclass per error type**. OpenSearch's type vocabulary is open-ended
 * and versioned with the engine rather than with this library — a `search_phase_execution_exception`
 * wrapping a `too_many_clauses` is one failure with two names — so a fixed set of subclasses would
 * be a list this module could not keep correct. Branch on [type] instead:
 *
 * ```kotlin
 * try {
 *     openSearch.search("venues", query)
 * } catch (e: OpenSearchException) {
 *     if (e.type == "index_not_found_exception") emptyList() else throw e
 * }
 * ```
 *
 * @param type OpenSearch's own name for the failure — `index_not_found_exception`,
 *   `search_phase_execution_exception`, `es_rejected_execution_exception`. Null when the engine
 *   never saw the request (an IAM refusal from the AWS front end) or when the body carried no type
 *   at all. **This, not [code], is the honest name**: on a 429 [code] reports
 *   [THROTTLING_CODE] so that `aws-core` retries it, and this reports what OpenSearch said.
 */
public open class OpenSearchException(
    code: String?,
    message: String?,
    statusCode: Int,
    public val type: String? = null,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
    rawErrorBody: ByteArray? = null,
) : AwsServiceException(code, message, statusCode, requestId, extendedRequestId, cause, rawErrorBody) {
    override fun toString(): String =
        "OpenSearchException(type=$type, code=$code, status=$statusCode, message=$message)"
}

/**
 * OpenSearch's `error.type` from a raw error body, or null.
 *
 * The counterpart to `aws-core`'s `transactionCancellationReasons`, and public for the same reason:
 * the structured payload of an error is schema knowledge that belongs in the service module, and a
 * caller holding an [AwsServiceException.rawErrorBody] from somewhere other than this module's
 * operations — an extension function calling `client.callRaw` directly — has no other way to read
 * it.
 */
public fun openSearchErrorType(body: ByteArray?): String? = body.asJsonObject().errorType()

/**
 * Rebuilds `aws-core`'s protocol-level exception as this module's, recovering the OpenSearch type.
 *
 * The type has to come back out of [AwsServiceException.rawErrorBody] rather than off the exception:
 * `ErrorDetails` carries a code and a message and nothing else, and on a 429 the code has been
 * substituted. Re-reading the bytes is what makes that substitution lossless.
 *
 * The rebuild threads `cause = e`, both request ids and the raw body — it is a rebuild rather than a
 * wrapper, so anything not carried across is destroyed here.
 */
internal inline fun <T> mapErrors(block: () -> T): T = try {
    block()
} catch (e: OpenSearchException) {
    // Already ours. Rethrown untouched rather than rebuilt: a nested `mapErrors` — `getDocument`
    // calls through `request` — would otherwise strip the type off on the way out.
    throw e
} catch (e: AwsServiceException) {
    throw OpenSearchException(
        code = e.code,
        message = e.message,
        statusCode = e.statusCode,
        type = openSearchErrorType(e.rawErrorBody),
        requestId = e.requestId,
        extendedRequestId = e.extendedRequestId,
        cause = e,
        rawErrorBody = e.rawErrorBody,
    )
}

/**
 * True for the body a document GET returns when the **document** is missing: `"found": false`, and
 * no error envelope at all.
 *
 * A positive test rather than "a 404 this module could read no type out of". The negative form looks
 * equivalent and is not: a 404 from the AWS front end for a mistyped path, or an HTML 404 from
 * something in between, also carries no type, and reporting either of those as "no such document"
 * hands a query path a silent empty answer for a request that never reached an index.
 */
internal fun isDocumentNotFound(body: ByteArray?): Boolean {
    val json = body.asJsonObject() ?: return false
    if (json["error"] != null) return false
    return (json["found"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "false"
}

private fun ByteArray?.asJsonObject(): JsonObject? = this?.decodeToString()?.let { text ->
    runCatching { lenientJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
}

/** `error.type`, falling back to `error.root_cause[0].type`. Null for the bare-string `error`. */
private fun JsonObject?.errorType(): String? {
    val error = this?.get("error") as? JsonObject ?: return null
    return error.string("type")
        ?: ((error["root_cause"] as? JsonArray)?.firstOrNull() as? JsonObject)?.string("type")
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
