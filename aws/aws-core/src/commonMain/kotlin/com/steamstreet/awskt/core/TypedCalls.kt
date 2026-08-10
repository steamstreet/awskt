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
