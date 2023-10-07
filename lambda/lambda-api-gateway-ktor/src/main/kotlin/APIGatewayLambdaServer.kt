package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyHandler
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.ktor.util.*
import java.net.URLEncoder
import java.util.*

/**
 * Allows us to build AWS ApiGateway lambdas using the Ktor web server. All routing
 * and parsing is handled by Ktor. Useful for building FAT APIs, where a single lambda
 * serves many different requests.
 */
public abstract class APIGatewayLambdaServer : ApiGatewayProxyHandler() {
    private val engine: TestApplicationEngine by lazy {
        TestApplicationEngine().apply {
            start()
            this.application.apply {
                module()
            }
        }
    }

    override suspend fun handle(input: ApiGatewayProxyRequest): ApiGatewayProxyResponse {
        val queryString = input.queryStringParameters?.entries?.joinToString("&") {
            it.key + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val stage = input.requestContext.stage
        val pathWithoutStage = stage?.let { input.path.substringAfter(stage) } ?: input.path
        val uri = if (queryString.isNullOrEmpty()) {
            pathWithoutStage
        } else {
            "$pathWithoutStage?$queryString"
        }

        val call = this.engine.handleRequest(HttpMethod.parse(input.httpMethod), uri) {
            input.headers?.forEach { (key, value) ->
                this.addHeader(key, value)
            }
            this.call.attributes.put(ApiGatewayRequest, input)
            if (input.body != null) {
                setBody(input.body!!)
            }
        }
        var body: String? = null
        var isBase64 = false
        val responseHeaders = call.response.headers
        call.response.byteContent?.let { bytes ->
            if (bytes.isNotEmpty()) {
                val contentType = responseHeaders["Content-Type"]?.let {
                    ContentType.parse(it)
                } ?: ContentType.Application.OctetStream
                body = if (contentType.match(ContentType("application", "json")) ||
                    contentType.match(ContentType("application", "js")) ||
                    contentType.contentType == "text"
                ) {
                    String(bytes)
                } else {
                    isBase64 = true
                    Base64.getEncoder().encodeToString(bytes)
                }.ifEmpty { null }
            }
        }

        return ApiGatewayProxyResponse(
            call.response.status()?.value ?: 200,
            multiValueHeaders = responseHeaders.allValues().entries().map {
                it.key to it.value
            }.toMap(),
            body = body,
            isBase64Encoded = isBase64
        )
    }

    /**
     * Implement this method to install your application
     */
    context(Application)
    protected abstract fun module()
}

/**
 * The attribute for the ApiGateway request.
 */
private val ApiGatewayRequest: AttributeKey<ApiGatewayProxyRequest> = AttributeKey("ApiGatewayRequest")
public val ApplicationCall.apiGatewayRequest: ApiGatewayProxyRequest get() = attributes[ApiGatewayRequest]