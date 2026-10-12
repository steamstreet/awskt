package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpRequest
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.auth.authentication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The name of the authentication provider that [ApiGatewayAuth] installs by default.
 *
 * It is the name `ApiGatewayJWT` uses, so an `authenticate("api-gateway-jwt") { }` block written
 * against that plugin works unchanged with this one.
 */
public const val ApiGatewayAuthProviderName: String = "api-gateway-jwt"

/**
 * The caller of an API Gateway request, as the API's authorizer described them.
 *
 * API Gateway verifies the token before it invokes the Lambda, and passes the claims on in the
 * request. This principal reads them; it verifies nothing.
 *
 * @param claims The verified claims, or null if the request carried none: a route with no
 * authorizer, or one with an IAM or Lambda authorizer.
 * @param sourceIp The address the request came from.
 */
public class ApiGatewayPrincipal(
    public val claims: JsonObject?,
    public val sourceIp: String? = null
) {
    /**
     * The principal for a REST API (payload format 1.0) request. The claims are those of a Cognito
     * user pool authorizer, at `requestContext.authorizer.claims`.
     */
    public constructor(request: ApiGatewayProxyRequest) : this(
        request.requestContext.authorizer?.get("claims") as? JsonObject,
        request.requestContext.identity?.sourceIp
    )

    /**
     * The principal for an HTTP API (payload format 2.0) request. The claims are those of a JWT
     * authorizer, at `requestContext.authorizer.jwt.claims`.
     */
    public constructor(request: ApiGatewayV2HttpRequest) : this(
        request.requestContext.authorizer?.jwt?.claims?.let(::JsonObject),
        request.requestContext.http.sourceIp
    )

    /**
     * The `sub` claim.
     */
    public val subject: String? get() = claim("sub")

    /**
     * The content of a claim as a string, whether it arrived as a string, a number or a boolean.
     * Null if the claim is missing, is JSON `null`, or is an object or an array; read those from
     * [claims].
     */
    public fun claim(name: String): String? = (claims?.get(name) as? JsonPrimitive)?.contentOrNull
}

/**
 * Configuration for [ApiGatewayAuth].
 */
public class ApiGatewayAuthConfig {
    /**
     * The name of the provider, as passed to `authenticate(name) { }`. Null installs it as the
     * default provider, for a bare `authenticate { }`.
     */
    public var name: String? = ApiGatewayAuthProviderName

    /**
     * If set, it is called for each request, and the principal it returns replaces the one read
     * from the request. This is for tests. If it returns null, the request's own principal is used.
     */
    public var mockPrincipal: (() -> ApiGatewayPrincipal?)? = null
}

/**
 * Makes the claims API Gateway has already verified available as an [ApiGatewayPrincipal].
 *
 * This does not authenticate anyone. The API's authorizer does that before the Lambda runs, so the
 * provider this installs accepts every request and sets a principal for it, with null
 * [claims][ApiGatewayPrincipal.claims] if the request carried none. Protect a route by giving it an
 * authorizer in API Gateway, not by wrapping it in `authenticate`.
 *
 * ```kotlin
 * install(ApiGatewayAuth)
 * routing {
 *     authenticate("api-gateway-jwt") {
 *         get("/me") { call.respondText(call.principal<ApiGatewayPrincipal>()?.subject.orEmpty()) }
 *     }
 * }
 * ```
 *
 * It works under both [APIGatewayKtorServer] and [APIGatewayV2KtorServer]. It adds its provider to
 * the `Authentication` plugin if the application has already installed one, and installs the
 * plugin otherwise.
 */
public val ApiGatewayAuth: ApplicationPlugin<ApiGatewayAuthConfig> =
    createApplicationPlugin("ApiGatewayAuth", ::ApiGatewayAuthConfig) {
        val config = pluginConfig
        val name = config.name
        application.authentication {
            provider(name) {
                authenticate { context ->
                    context.principal(name, config.mockPrincipal?.invoke() ?: context.call.requestPrincipal())
                }
            }
        }
    }

/**
 * The principal for whichever API Gateway request this call was built from. A call from neither
 * adapter, such as one in a Ktor `testApplication`, has a principal with no claims.
 */
private fun ApplicationCall.requestPrincipal(): ApiGatewayPrincipal {
    attributes.getOrNull(ApiGatewayRequest)?.let { return ApiGatewayPrincipal(it) }
    attributes.getOrNull(ApiGatewayV2Request)?.let { return ApiGatewayPrincipal(it) }
    return ApiGatewayPrincipal(null)
}
