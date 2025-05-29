package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyResponse
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import net.logstash.logback.marker.Markers.append
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Encasulates an API Gateway Proxy Request as an Application Call that can be processed by
 * the Ktor server pipeline.
 */
public class ApiGatewayKtorCall(
    override val application: Application,
    private val proxyRequest: ApiGatewayProxyRequest
) : PipelineCall {
    override val attributes: Attributes = Attributes().also {
        it.put(ApiGatewayRequest, proxyRequest)
    }
    override val coroutineContext: CoroutineContext = EmptyCoroutineContext
    override val parameters: Parameters get() = request.queryParameters
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
                        get() = proxyRequest.path

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
                    return ApplicationReceivePipeline(
                        call.application.developmentMode
                    ).apply {
                        resetFrom(call.application.receivePipeline)
                    }
                }

            override val queryParameters: Parameters by lazy {
                parametersOf().apply {
                    proxyRequest.queryStringParameters?.forEach { (key, value) ->
                        append(key, value)
                    }
                }
            }
            override val rawQueryParameters: Parameters get() = queryParameters

            override fun receiveChannel(): ByteReadChannel {
                return if (proxyRequest.body != null) {
                    val bodyBytes = if (proxyRequest.isBase64Encoded == true) {
                        Base64.getDecoder().decode(proxyRequest.body)
                    } else {
                        proxyRequest.body!!.toByteArray(Charsets.UTF_8)
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

    private val responseHeadersBuilder = HeadersBuilder()
    private val statusCode = AtomicInteger(0)
    private var responseContent: OutgoingContent? = null

    override val response: PipelineResponse
        get() = object : PipelineResponse {
            override val headers: ResponseHeaders = object : ResponseHeaders() {
                override fun engineAppendHeader(name: String, value: String) {
                    responseHeadersBuilder.append(name, value)
                }

                override fun getEngineHeaderNames(): List<String> {
                    return responseHeadersBuilder.names().toList()
                }

                override fun getEngineHeaderValues(name: String): List<String> {
                    return responseHeadersBuilder.getAll(name) ?: emptyList()
                }
            }
            override val call: PipelineCall
                get() = this@ApiGatewayKtorCall
            override val cookies: ResponseCookies by lazy { ResponseCookies(this) }
            override val isCommitted: Boolean = responseContent != null
            override var isSent: Boolean = false
            override val pipeline: ApplicationSendPipeline
                get() {
                    return ApplicationSendPipeline(application.sendPipeline.developmentMode).apply {
                        merge(application.sendPipeline)

                        // Add an interceptor to capture the response body
                        intercept(ApplicationSendPipeline.Engine) { body ->
                            if (body !is OutgoingContent) {
                                throw IllegalArgumentException(
                                    "Response pipeline couldn't transform '${body::class}' to the OutgoingContent"
                                )
                            }
                            responseContent = body
                            isSent = true
                        }
                    }
                }

            @UseHttp2Push
            override fun push(builder: ResponsePushBuilder) {
            }

            override fun status(): HttpStatusCode = HttpStatusCode.fromValue(statusCode.get())

            override fun status(value: HttpStatusCode) {
                statusCode.set(value.value)
            }
        }

    private suspend fun getBodyContent(): ByteArray? {
        val body = responseContent ?: return null
        return when (body) {
            is OutgoingContent.ByteArrayContent -> {
                body.bytes()
            }

            is OutgoingContent.WriteChannelContent -> {
                val channel = ByteChannel()
                body.writeTo(channel)
                channel.close()
                channel.toByteArray()
            }

            is OutgoingContent.ReadChannelContent -> {
                body.readFrom().toByteArray()
            }

            else -> null
        }
    }

    /**
     * Called to determine if the response is text, or if it should be encoded as Base64.
     */
    protected fun isTextContent(contentType: ContentType): Boolean {
        return contentType.contentType == "text" ||
                contentType.match(ContentType.Application.Json) ||
                contentType.match(ContentType.Application.JavaScript) ||
                contentType.match(ContentType.Application.Xml) ||
                contentType.match(ContentType.Application.FormUrlEncoded)
    }

    /**
     * Should we encode to base 64?
     */
    protected fun encodeBase64(response: ApplicationResponse): Boolean {
        val responseHeaders = response.headers
        val contentType = responseHeaders["Content-Type"]?.let {
            ContentType.parse(it)
        } ?: ContentType.Application.OctetStream

        val contentEncoding = responseHeaders["Content-Encoding"]

        return contentEncoding != null || !isTextContent(contentType)
    }

    public suspend fun response(): ApiGatewayProxyResponse {
        var body: String? = null
        var isBase64 = false

        val responseBytes = getBodyContent()

        responseContent?.let {
            val contentType = it.contentType
            if (contentType != null) {
                if (responseHeadersBuilder["Content-Type"] == null) {
                    responseHeadersBuilder["Content-Type"] = contentType.toString()
                }
            }
            // if we had content, the result is 200 by default.
            statusCode.set(it.status?.value ?: 200)
        }

        val responseHeaders = responseHeadersBuilder.build()
        responseBytes?.let { bytes ->
            if (bytes.isNotEmpty()) {
                val contentType = responseHeaders["Content-Type"]?.let {
                    ContentType.parse(it)
                } ?: responseContent?.contentType ?: ContentType.Application.OctetStream

                val contentEncoding = responseHeaders["Content-Encoding"]

                body = if (contentEncoding == null && isTextContent(contentType)) {
                    String(bytes)
                } else {
                    isBase64 = true
                    Base64.getEncoder().encodeToString(bytes)
                }.ifEmpty { null }
            }
        }

        val multiValueHeaders = mutableMapOf<String, List<String>>()
        responseHeaders.forEach { name, values ->
            multiValueHeaders[name] = values.flatMap { value ->
                value.split(",").map { it.trim() }
            }
        }

        val resolvedStatusCode = statusCode.get().let {
            if (it == 0) 404 else it
        }

        return ApiGatewayProxyResponse(
            resolvedStatusCode,
            multiValueHeaders = multiValueHeaders,
            body = body,
            isBase64Encoded = isBase64
        )
    }

    @OptIn(InternalAPI::class)
    override suspend fun respond(message: Any?, typeInfo: TypeInfo?) {
        response.responseType = typeInfo
        response.pipeline.execute(this, message ?: NullBody)
    }
}