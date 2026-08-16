package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyResponse
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpResponse
import com.steamstreet.aws.lambda.native.nativeLambdaIO
import io.ktor.server.application.Application

/**
 * Entry point for a Kotlin/Native API Gateway Lambda that routes with Ktor.
 *
 * ```kotlin
 * fun main() = apiGatewayKtorLambda {
 *     install(ContentNegotiation) { json() }
 *     routing {
 *         get("/health") { call.respondText("ok") }
 *     }
 * }
 * ```
 *
 * The [module] block is the same `Application.() -> Unit` the JVM [APIGatewayLambdaServer] takes,
 * because it is the same Ktor application: routing, plugins and handlers move across untouched. The
 * difference is only in how the runtime finds the handler — the JVM one is a `RequestStreamHandler`
 * class, a native one is `main`.
 *
 * ### Why the module is installed inside `initialize`
 *
 * Building the [APIGatewayKtorServer] runs the whole module block: every `install`, every route
 * registration. Doing that here rather than lazily on the first request buys two things. Lambda
 * gives the init phase a full CPU allocation regardless of the function's configured memory, so
 * route-tree construction is cheaper there than in a request; and a module that throws — a
 * misconfigured plugin, a missing environment variable read at install time — is reported to
 * `POST /runtime/init/error` and shows up as an initialization failure in the console, instead of
 * surfacing as a 500 on whichever unlucky request happened to arrive first.
 *
 * [initialize] runs after the module is installed, so anything it sets up is available to handlers
 * but cannot be read by the module block itself. Configuration the module needs at install time
 * should be read inside [module].
 */
public fun apiGatewayKtorLambda(
    initialize: suspend () -> Unit = {},
    module: Application.() -> Unit
): Unit {
    lateinit var server: APIGatewayKtorServer
    nativeLambdaIO(
        ApiGatewayProxyRequest.serializer(),
        ApiGatewayProxyResponse.serializer(),
        initialize = {
            server = APIGatewayKtorServer(module)
            initialize()
        }
    ) { request ->
        server.processRequest(request)
    }
}

/**
 * Entry point for a Kotlin/Native API Gateway **HTTP API** Lambda that routes with Ktor.
 *
 * ```kotlin
 * fun main() = apiGatewayV2KtorLambda {
 *     install(ContentNegotiation) { json() }
 *     routing {
 *         get("/health") { call.respondText("ok") }
 *     }
 * }
 * ```
 *
 * Identical to [apiGatewayKtorLambda] apart from the payload format — same [module] signature, same
 * reasoning about installing it inside `initialize`. Pick this one when the API Gateway stage uses
 * payload format 2.0, which is the default for HTTP APIs.
 */
public fun apiGatewayV2KtorLambda(
    initialize: suspend () -> Unit = {},
    module: Application.() -> Unit
): Unit {
    lateinit var server: APIGatewayV2KtorServer
    nativeLambdaIO(
        ApiGatewayV2HttpRequest.serializer(),
        ApiGatewayV2HttpResponse.serializer(),
        initialize = {
            server = APIGatewayV2KtorServer(module)
            initialize()
        }
    ) { request ->
        server.processRequest(request)
    }
}
