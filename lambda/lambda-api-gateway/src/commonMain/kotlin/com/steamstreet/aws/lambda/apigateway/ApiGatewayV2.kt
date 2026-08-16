package com.steamstreet.aws.lambda.apigateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.io.encoding.Base64

/**
 * The API Gateway **HTTP API** payload format 2.0 model.
 *
 * Deliberately a separate model from [ApiGatewayProxyRequest] rather than an extension of it. The
 * two formats disagree about more than field names:
 *
 *  - the method and source IP move into a nested `http` object, and the path becomes [rawPath];
 *  - multi-valued headers and query parameters are gone — v2 comma-joins them into the single-valued
 *    maps, and [rawQueryString] carries the query verbatim;
 *  - cookies are hoisted out of the headers into a top-level [cookies] array;
 *  - the authorizer is a typed object with a `jwt` shape, not the untyped bag v1 carries.
 *
 * Modelling that as optional fields on the v1 types would produce a class where half the properties
 * are null depending on which integration is wired up, and no compiler help about which half.
 */
@Serializable
public data class ApiGatewayV2HttpRequest(
    val version: String = "2.0",
    val routeKey: String? = null,
    val rawPath: String,
    /**
     * The query string exactly as it arrived, still percent-encoded and with no leading `?`.
     *
     * This is strictly better than v1's parameter maps for reconstructing a URI: it needs no
     * re-encoding, so it cannot disagree with what the client actually sent.
     */
    val rawQueryString: String? = null,
    /**
     * Cookies, one `name=value` per entry, already split out of the `Cookie` header.
     */
    val cookies: List<String>? = null,
    val headers: Map<String, String>? = null,
    val queryStringParameters: Map<String, String>? = null,
    val pathParameters: Map<String, String>? = null,
    val stageVariables: Map<String, String>? = null,
    val requestContext: ApiGatewayV2RequestContext = ApiGatewayV2RequestContext(),
    val body: String? = null,
    val isBase64Encoded: Boolean? = null
) {
    /**
     * Decode the body into bytes (uses the base64 flag).
     */
    public fun decodedBody(): ByteArray {
        return if (isBase64Encoded == true && body != null) {
            Base64.Default.decode(body)
        } else {
            body?.encodeToByteArray() ?: ByteArray(0)
        }
    }
}

@Serializable
public data class ApiGatewayV2RequestContext(
    val accountId: String? = null,
    val apiId: String? = null,
    val domainName: String? = null,
    val domainPrefix: String? = null,
    val requestId: String? = null,
    val routeKey: String? = null,
    val stage: String? = null,
    val time: String? = null,
    val timeEpoch: Long? = null,
    val http: ApiGatewayV2Http = ApiGatewayV2Http(),
    val authorizer: ApiGatewayV2Authorizer? = null
)

/**
 * The request line, which v2 nests here rather than carrying at the top level.
 */
@Serializable
public data class ApiGatewayV2Http(
    val method: String = "GET",
    val path: String? = null,
    val protocol: String? = null,
    val sourceIp: String? = null,
    val userAgent: String? = null
)

/**
 * Whichever authorizer the route is configured with. All three are mutually exclusive in practice,
 * and which one is populated is a property of the API's configuration rather than of the request.
 */
@Serializable
public data class ApiGatewayV2Authorizer(
    val jwt: ApiGatewayV2JwtAuthorizer? = null,
    /** A `REQUEST` (Lambda) authorizer's context — free-form by definition. */
    val lambda: Map<String, JsonElement>? = null,
    val iam: Map<String, JsonElement>? = null
)

@Serializable
public data class ApiGatewayV2JwtAuthorizer(
    val claims: Map<String, JsonElement>? = null,
    val scopes: List<String>? = null
)

/**
 * A payload format 2.0 response.
 *
 * Note what is **not** here: `multiValueHeaders`. v2 has no such field, so headers with several
 * values are comma-joined into [headers] — except `Set-Cookie`, which cannot be joined safely
 * because cookie expiry dates contain commas, and therefore gets its own [cookies] array.
 *
 * The shape matters more than usual. If API Gateway cannot read this object as a response it treats
 * the *entire* object as the response body with a 200, so a mapping mistake here surfaces as a
 * wrong-looking 200 rather than as an error.
 */
@Serializable
public data class ApiGatewayV2HttpResponse(
    var statusCode: Int? = null,
    val headers: Map<String, String>? = null,
    val cookies: List<String>? = null,
    val body: String? = null,
    val isBase64Encoded: Boolean? = null
)
