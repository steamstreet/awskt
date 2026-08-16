package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyHandler
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyResponse
import io.ktor.server.application.*

/**
 * Allows us to build AWS ApiGateway lambdas using the Ktor web server. All routing
 * and parsing is handled by Ktor. Useful for building FAT APIs, where a single lambda
 * serves many different requests.
 */
public abstract class APIGatewayLambdaServer : ApiGatewayProxyHandler() {
    private val server by lazy {
        APIGatewayKtorServer {
            module()
        }
    }

    override suspend fun handle(input: ApiGatewayProxyRequest): ApiGatewayProxyResponse {
        return server.processRequest(input)
    }

    /**
     * Implement this method to install your application
     */
    protected abstract fun Application.module()
}
