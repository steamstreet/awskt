package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyHandler
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
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

    /**
     * Called to determine if the response is text, or if it should be encoded as Base64.
     */
    protected open fun isTextContent(contentType: ContentType): Boolean {
        return contentType.contentType == "text" ||
                contentType.match(ContentType.Application.Json) ||
                contentType.match(ContentType.Application.JavaScript) ||
                contentType.match(ContentType.Application.Xml) ||
                contentType.match(ContentType.Application.FormUrlEncoded)
    }

    /**
     * Should we encode to base 64?
     */
    protected open fun encodeBase64(response: ApplicationResponse): Boolean {
        val responseHeaders = response.headers
        val contentType = responseHeaders["Content-Type"]?.let {
            ContentType.parse(it)
        } ?: ContentType.Application.OctetStream

        val contentEncoding = responseHeaders["Content-Encoding"]

        return contentEncoding != null || !isTextContent(contentType)
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
                if (input.isBase64Encoded == true) {
                    setBody(Base64.getDecoder().decode(input.body))
                } else {
                    setBody(input.body!!)
                }
            }
        }
        var body: String? = null
        var isBase64 = false
        val responseHeaders = call.response.headers
        call.response.byteContent?.let { bytes ->
            if (bytes.isNotEmpty()) {
                body = if (encodeBase64(call.response)) {
                    isBase64 = true
                    Base64.getEncoder().encodeToString(bytes)
                } else {
                    String(bytes)
                }.ifEmpty { null }
            }
        }

        return ApiGatewayProxyResponse(
            call.response.status()?.value ?: 200,
            multiValueHeaders = responseHeaders.allValues().entries().associate {
                it.key to it.value
            },
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