package com.steamstreet.aws.lambda.apigateway

import com.steamstreet.aws.lambda.IOLambda
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.*

@Serializable
public data class ApiGatewayProxyRequest(
    val resource: String,
    val path: String,
    val httpMethod: String,
    val headers: Map<String, String>? = null,
    val multiValueHeaders: Map<String, List<String>>? = null,
    val queryStringParameters: Map<String, String>? = null,
    val multiValueQueryStringParameters: Map<String, List<String>>? = null,
    val pathParameters: Map<String, String>? = null,
    val stageVariables: Map<String, String>? = null,
    val requestContext: ProxyRequestContext,
    val body: String? = null,
    val isBase64Encoded: Boolean? = null
) {
    /**
     * Decode the body into bytes (uses the base64 flag).
     */
    public fun decodedBody(): ByteArray {
        return if (isBase64Encoded == true && body != null) {
            Base64.getDecoder().decode(body)
        } else {
            body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        }
    }
}

@Serializable
public data class RequestIdentity(
    val cognitoIdentityPoolId: String? = null,
    val accountId: String? = null,
    val cognitoIdentityId: String? = null,
    val caller: String? = null,
    val apiKey: String? = null,
    val sourceIp: String? = null,
    val cognitoAuthenticationType: String? = null,
    val cognitoAuthenticationProvider: String? = null,
    val userArn: String? = null,
    val userAgent: String? = null,
    val user: String? = null,
    val accessKey: String? = null
)

@Serializable
public data class ProxyRequestContext(
    val accountId: String? = null,
    val stage: String? = null,
    val resourceId: String? = null,
    val requestId: String? = null,
    val operationName: String? = null,
    val identity: RequestIdentity? = null,
    val resourcePath: String? = null,
    val httpMethod: String? = null,
    val apiId: String? = null,
    val path: String? = null,
    val authorizer: Map<String, JsonElement>? = null
)

@Serializable
public data class ApiGatewayProxyResponse(
    var statusCode: Int? = null,
    val headers: Map<String, String>? = null,
    val multiValueHeaders: Map<String, List<String>>? = null,
    val body: String? = null,
    val isBase64Encoded: Boolean? = null
)

/**
 * Base class to handle ApiGateway requests.
 */
public abstract class ApiGatewayProxyHandler : IOLambda<ApiGatewayProxyRequest, ApiGatewayProxyResponse>(
    ApiGatewayProxyRequest.serializer(), ApiGatewayProxyResponse.serializer()
)