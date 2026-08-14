package com.steamstreet.awskt.core

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json

/**
 * The JSON configuration every AWS-JSON service must use.
 *
 * All three settings are load-bearing on the wire, not stylistic:
 *
 * - `encodeDefaults = false` and `explicitNulls = false` keep absent fields absent. DynamoDB
 *   rejects `"ExpressionAttributeValues":{}` where it accepts the field being missing entirely.
 * - `ignoreUnknownKeys = true` means AWS adding a response field is a non-event rather than a
 *   deserialization failure in production.
 *
 * Exposed publicly so that an operation added downstream serializes identically to a built-in one.
 */
public val awsJson: Json = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
}

/**
 * Issues a typed AWS-JSON call: serialize, sign, send, retry, deserialize.
 *
 * ### This is the extension point
 *
 * Service modules in this library are thin by design, and they add no privileged capability. An
 * operation the library does not ship can be added downstream as an extension function, with the
 * same signing, retry and error handling as a built-in one — no fork, no reflection, no access to
 * anything `internal`:
 *
 * ```kotlin
 * @Serializable
 * data class DescribeLimitsRequest(val dummy: String? = null)
 *
 * @Serializable
 * data class DescribeLimitsResponse(
 *     @SerialName("TableMaxWriteCapacityUnits") val tableMaxWriteCapacityUnits: Long? = null,
 * )
 *
 * suspend fun DynamoDb.describeLimits(): DescribeLimitsResponse =
 *     client.callJson(
 *         operation = "DescribeLimits",
 *         request = DescribeLimitsRequest(),
 *         requestSerializer = DescribeLimitsRequest.serializer(),
 *         responseSerializer = DescribeLimitsResponse.serializer(),
 *     )
 * ```
 *
 * The same applies one level up: a service this library does not cover at all can be built on
 * [AwsServiceClient] and [AwsProtocol] directly, since both are public and neither is
 * DynamoDB-specific.
 *
 * Errors surface as [AwsServiceException] — with `code`, `statusCode` and the request identifiers
 * already populated — so an extension author gets useful failures without re-implementing error
 * parsing. A service module may map known codes onto typed exceptions on top of that; nothing
 * requires an extension to.
 *
 * @param safety whether an ambiguous mid-flight failure may be retried. **Default
 *   [OperationSafety.IDEMPOTENT]** — an extension author adding a read gets the right behaviour by
 *   default, and one adding a read-modify-write must say so explicitly.
 */
public suspend fun <Req, Res> AwsServiceClient.callJson(
    operation: String,
    request: Req,
    requestSerializer: SerializationStrategy<Req>,
    responseSerializer: DeserializationStrategy<Res>,
    json: Json = awsJson,
    safety: OperationSafety = OperationSafety.IDEMPOTENT,
    headers: List<Pair<String, String>> = emptyList(),
): Res {
    val body = json.encodeToString(requestSerializer, request).encodeToByteArray()
    val response = callRaw(
        method = "POST",
        path = "/",
        headers = headers,
        body = body,
        operation = operation,
        safety = safety,
    )
    return json.decodeFromString(responseSerializer, response.body.decodeToString())
}

/**
 * Issues a typed **restJson1** call: a JSON body sent to a method and path.
 *
 * The REST-shaped sibling of [callJson], and a separate function rather than a parameter on it
 * because the two differ in what *addresses* the operation. [callJson] hardcodes `POST /` and puts
 * the operation in `X-Amz-Target`; here the method and path carry it and there is no target header
 * at all. Collapsing them into one function with nullable everything would produce a signature
 * where half the parameters are illegal for half the callers.
 *
 * Use with [AwsProtocol.restJson1].
 *
 * @param path **already percent-encoded**, and passed to
 *   [AwsServiceClient.callRaw] untouched — the string used to build the URL is the string that was
 *   signed. A path segment interpolated from user data must be encoded by the caller, with
 *   `sigV4UriEncode(segment)`; interpolating it raw is a signature mismatch at best and a path
 *   traversal into a different operation at worst.
 * @param operation carried for observability only — an [AwsCallObserver] reports it, and with no
 *   `X-Amz-Target` to read it is otherwise unrecoverable from a restJson1 request.
 * @param safety **defaults to [OperationSafety.IDEMPOTENT]**, matching [callJson]. A REST verb is
 *   not evidence either way: `PUT` is idempotent by HTTP's definition and `POST /schedules/{Name}`
 *   is not, so this stays an explicit decision at the call site rather than something inferred from
 *   the method.
 */
public suspend fun <Req, Res> AwsServiceClient.callRestJson(
    method: String,
    path: String,
    request: Req,
    requestSerializer: SerializationStrategy<Req>,
    responseSerializer: DeserializationStrategy<Res>,
    query: List<Pair<String, String>> = emptyList(),
    operation: String? = null,
    json: Json = awsJson,
    safety: OperationSafety = OperationSafety.IDEMPOTENT,
    headers: List<Pair<String, String>> = emptyList(),
): Res {
    val body = json.encodeToString(requestSerializer, request).encodeToByteArray()
    val response = callRaw(
        method = method,
        path = path,
        query = query,
        headers = headers,
        body = body,
        operation = operation,
        safety = safety,
    )
    return json.decodeFromString(responseSerializer, response.body.decodeToString())
}

/**
 * [callRestJson] for the verbs that carry no request body — `GET`, `DELETE`, and the `POST`s whose
 * every input is a path or query parameter.
 *
 * A distinct name rather than an overload with a defaulted body. The overload would be ambiguous at
 * the call site — `request` and `responseSerializer` are adjacent, and one of them is generic — and
 * resolving it wrongly is silent: the request serializes the *deserializer* into the body and the
 * service answers with a validation error that names nothing useful.
 *
 * **Sends no body at all, not an empty JSON object.** The distinction reaches the wire and matters:
 * a signed zero-length payload hashes to AWS's documented empty-body SHA-256, while `{}` hashes to
 * something else and arrives with a `Content-Type` the service did not expect on a `GET`.
 *
 * @param path see [callRestJson] — already percent-encoded, never re-encoded here.
 */
public suspend fun <Res> AwsServiceClient.callRestJsonNoBody(
    method: String,
    path: String,
    responseSerializer: DeserializationStrategy<Res>,
    query: List<Pair<String, String>> = emptyList(),
    operation: String? = null,
    json: Json = awsJson,
    safety: OperationSafety = OperationSafety.IDEMPOTENT,
    headers: List<Pair<String, String>> = emptyList(),
): Res {
    val response = callRaw(
        method = method,
        path = path,
        query = query,
        headers = headers,
        operation = operation,
        safety = safety,
    )
    // A 204, or a 200 whose body AWS left empty, is a legitimate answer for these verbs — decoding
    // "" throws a SerializationException naming a JSON parse position, which describes nothing the
    // caller can act on. "{}" deserializes to a response object with every field at its default,
    // which is what an empty answer means.
    val text = response.body.decodeToString().ifBlank { "{}" }
    return json.decodeFromString(responseSerializer, text)
}
