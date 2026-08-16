package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import io.ktor.utils.io.*
import kotlin.io.encoding.Base64

/**
 * Encasulates an API Gateway Proxy Request as an Application Call that can be processed by
 * the Ktor server pipeline.
 *
 * This is the **payload format 1.0** (REST API, and HTTP API configured for 1.0) mapping; the v2
 * equivalent is [ApiGatewayV2KtorCall]. Everything below the format — running the pipeline and
 * capturing what it produced — is inherited from [ApiGatewayKtorCallBase].
 */
public class ApiGatewayKtorCall(
    application: Application,
    private val proxyRequest: ApiGatewayProxyRequest
) : ApiGatewayKtorCallBase(application) {
    override val attributes: Attributes = Attributes().also {
        it.put(ApiGatewayRequest, proxyRequest)
    }

    override val request: PipelineRequest
        get() = object : PipelineRequest {
            override val headers: Headers by lazy {
                Headers.build {
                    proxyRequest.headers?.forEach { (key, value) ->
                        append(key, value)
                    }
                }
            }
            override val call: PipelineCall
                get() = this@ApiGatewayKtorCall
            override val cookies: RequestCookies by lazy {
                RequestCookies(this)
            }
            override val local: RequestConnectionPoint by lazy {
                object : RequestConnectionPoint {
                    override val localPort: Int = 0
                    override val localAddress: String = "127.0.0.1"
                    override val localHost: String = "localhost"
                    override val remoteAddress: String = proxyRequest.requestContext.identity?.sourceIp ?: "unknown"
                    override val remotePort: Int = 0
                    override val host: String
                        get() = proxyRequest.requestContext.identity?.sourceIp ?: "localhost"

                    override val port: Int
                        get() = 443  // API Gateway typically uses HTTPS (port 443)

                    override val scheme: String
                        get() = "https"  // API Gateway uses HTTPS

                    override val version: String
                        get() = "HTTP/1.1"

                    override val uri: String
                        get() {
                            val params = proxyRequest.multiValueQueryStringParameters
                                ?: proxyRequest.queryStringParameters?.mapValues { (_, v) -> listOf(v) }
                            if (params.isNullOrEmpty()) return proxyRequest.path
                            val query = parametersOf(params).formUrlEncode()
                            return "${proxyRequest.path}?$query"
                        }

                    override val method: HttpMethod
                        get() = HttpMethod.parse(proxyRequest.httpMethod)

                    override val remoteHost: String
                        get() = proxyRequest.requestContext.identity?.sourceIp ?: "unknown"

                    // These are usually not applicable for serverless functions
                    override val serverHost: String
                        get() = host

                    override val serverPort: Int
                        get() = port
                }
            }
            override val pipeline: ApplicationReceivePipeline
                get() {
                    return application.receivePipeline
                }

            override val queryParameters: Parameters by lazy {
                Parameters.build {
                    proxyRequest.queryStringParameters?.forEach { (key, value) ->
                        append(key, value)
                    }
                }
            }
            override val rawQueryParameters: Parameters get() = queryParameters

            override fun receiveChannel(): ByteReadChannel {
                return if (proxyRequest.body != null) {
                    val bodyBytes = if (proxyRequest.isBase64Encoded == true) {
                        Base64.Default.decode(proxyRequest.body!!)
                    } else {
                        proxyRequest.body!!.encodeToByteArray()
                    }
                    ByteReadChannel(bodyBytes)
                } else {
                    ByteReadChannel.Empty
                }
            }

            @InternalAPI
            override fun setHeader(name: String, values: List<String>?) {
                throw NotImplementedError("Request headers cannot be modified")
            }

            @InternalAPI
            override fun setReceiveChannel(channel: ByteReadChannel) {
                throw NotImplementedError("Receive channel cannot be modified")
            }
        }

    public suspend fun response(): ApiGatewayProxyResponse {
        val resolved = resolveResponse()

        val multiValueHeaders = mutableMapOf<String, List<String>>()
        resolved.headers.forEach { name, values ->
            multiValueHeaders[name] = values.toList()
        }

        return ApiGatewayProxyResponse(
            resolved.statusCode,
            multiValueHeaders = multiValueHeaders,
            body = resolved.body,
            isBase64Encoded = resolved.isBase64Encoded
        )
    }
}
