package com.steamstreet.aws.lambda

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The field names [redactCredentials] redacts by default, in lower case. Matching ignores case,
 * because HTTP header names are case-insensitive and API Gateway and AppSync pass them through as
 * the client sent them.
 *
 * - `authorization`, `proxy-authorization`: bearer tokens and basic credentials.
 * - `cookie`, `set-cookie`, `cookies`: session cookies. `cookies` is the array API Gateway's
 *   payload format 2.0 uses for both request and response cookies.
 * - `x-api-key`, `apikey`: the API key as a header, and as the value API Gateway copies into a
 *   REST API request's `requestContext.identity.apiKey`.
 * - `x-amz-security-token`: the session token of temporary AWS credentials.
 * - `access_token`, `id_token`, `refresh_token`: OAuth tokens, which some clients send as query
 *   parameters.
 */
public val credentialFieldNames: Set<String> = setOf(
    "authorization",
    "proxy-authorization",
    "cookie",
    "set-cookie",
    "cookies",
    "x-api-key",
    "apikey",
    "x-amz-security-token",
    "access_token",
    "id_token",
    "refresh_token",
)

/** What a redacted value is replaced with. */
public const val REDACTED: String = "[REDACTED]"

/**
 * Returns [payload] with the value of every field named in [fieldNames] replaced by [REDACTED], at
 * any depth, so that a request can be logged without the credentials it carries.
 *
 * This is the redactor the API Gateway and AppSync handlers log through. It walks the whole
 * payload rather than naming paths, so it covers `headers`, `multiValueHeaders`,
 * `queryStringParameters`, API Gateway's `requestContext.identity` and AppSync's
 * `request.headers` alike. It leaves the shape alone: a redacted array keeps its length, so a
 * field's type is the same in every log line. A null stays null.
 *
 * What it does not see: the contents of a string. API Gateway delivers the request body as a
 * string, so a credential inside a JSON or form body is logged as it is. Decoded authorizer claims
 * are not credentials, and are left in place.
 *
 * @param fieldNames names to redact, compared ignoring case. Add to [credentialFieldNames] to
 *   redact an application's own headers: `credentialFieldNames + "x-session-token"`.
 */
public fun redactCredentials(
    payload: JsonElement,
    fieldNames: Set<String> = credentialFieldNames
): JsonElement {
    val names = if (fieldNames === credentialFieldNames) fieldNames else fieldNames.mapTo(HashSet()) { it.lowercase() }
    return redact(payload, names)
}

private fun redact(element: JsonElement, names: Set<String>): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.mapValues { (key, value) ->
            if (key.lowercase() in names) redacted(value) else redact(value, names)
        }
    )
    is JsonArray -> JsonArray(element.map { redact(it, names) })
    is JsonPrimitive -> element
}

private fun redacted(value: JsonElement): JsonElement = when (value) {
    JsonNull -> JsonNull
    is JsonArray -> JsonArray(value.map { redacted(it) })
    else -> JsonPrimitive(REDACTED)
}
