package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpHandler
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpResponse
import io.ktor.server.application.Application

/**
 * Allows us to build AWS API Gateway **HTTP API** lambdas using the Ktor web server, with routing
 * and parsing handled by Ktor.
 *
 * The v1 equivalent is [APIGatewayLambdaServer], and [module] has the same signature in both, so
 * moving an application from a REST API to an HTTP API means changing the base class and nothing
 * else.
 */
public abstract class APIGatewayV2LambdaServer : ApiGatewayV2HttpHandler() {
    private val server by lazy {
        APIGatewayV2KtorServer {
            module()
        }
    }

    override suspend fun handle(input: ApiGatewayV2HttpRequest): ApiGatewayV2HttpResponse {
        return server.processRequest(input)
    }

    /**
     * Implement this method to install your application
     */
    protected abstract fun Application.module()
}
