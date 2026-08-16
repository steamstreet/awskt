package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.util.*

/**
 * A server that can process API Gateway **HTTP API** requests in payload format 2.0.
 *
 * Same mechanism as [APIGatewayKtorServer] — both run [KtorApplicationHost], both execute the call
 * against the application directly — and the [Application] block you pass is identical, so an
 * application can be moved between the two integrations without touching a route.
 *
 * They are separate classes rather than one server with two entry points because an API Gateway
 * stage is configured for exactly one payload format; a Lambda that could receive either would be a
 * misconfiguration, not a feature.
 */
public class APIGatewayV2KtorServer(module: Application.() -> Unit) {
    private val host = KtorApplicationHost(module)

    public suspend fun processRequest(request: ApiGatewayV2HttpRequest): ApiGatewayV2HttpResponse {
        val application = host.application
        val call = ApiGatewayV2KtorCall(application, request)
        try {
            application.execute(call, Unit)
        } catch (e: Exception) {
            // If an exception is thrown, set the status code to 500
            call.response.status(HttpStatusCode.InternalServerError)
        }
        return call.response()
    }
}

/**
 * The attribute for the HTTP API (format 2.0) request.
 *
 * Deliberately a second key rather than a widening of [ApiGatewayRequest]: the two payloads are
 * different types, and a handler written against one integration should not compile against the
 * other and then fail at run time on a missing attribute.
 */
public val ApiGatewayV2Request: AttributeKey<ApiGatewayV2HttpRequest> = AttributeKey("ApiGatewayV2Request")
public val ApplicationCall.apiGatewayV2Request: ApiGatewayV2HttpRequest get() = attributes[ApiGatewayV2Request]
