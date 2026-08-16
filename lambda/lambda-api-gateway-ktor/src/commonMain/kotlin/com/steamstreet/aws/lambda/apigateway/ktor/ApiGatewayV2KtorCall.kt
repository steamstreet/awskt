package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import io.ktor.utils.io.*
import kotlin.io.encoding.Base64

/**
 * Encapsulates an API Gateway **HTTP API** (payload format 2.0) request as an Application Call that
 * can be processed by the Ktor server pipeline.
 *
 * The v1 equivalent is [ApiGatewayKtorCall], and the pipeline machinery both sit on is
 * [ApiGatewayKtorCallBase]. Only the three places the formats actually disagree are implemented
 * here.
 */
public class ApiGatewayV2KtorCall(
    application: Application,
    private val v2Request: ApiGatewayV2HttpRequest
) : ApiGatewayKtorCallBase(application) {
    override val attributes: Attributes = Attributes().also {
        it.put(ApiGatewayV2Request, v2Request)
    }

    override val request: PipelineRequest
        get() = object : PipelineRequest {
            /**
             * v2 splits cookies out of the headers into their own array, so they have to be folded
             * back into a `Cookie` header — Ktor's [RequestCookies] parses that header and would
             * otherwise see a request with no cookies at all.
             */
            override val headers: Headers by lazy {
                Headers.build {
                    v2Request.headers?.forEach { (key, value) ->
                        append(key, value)
                    }
                    v2Request.cookies?.takeIf { it.isNotEmpty() }?.let { cookies ->
                        append(HttpHeaders.Cookie, cookies.joinToString("; "))
                    }
                }
            }
            override val call: PipelineCall
                get() = this@ApiGatewayV2KtorCall
            override val cookies: RequestCookies by lazy {
                RequestCookies(this)
            }
            override val local: RequestConnectionPoint by lazy {
                object : RequestConnectionPoint {
                    private val sourceIp: String? get() = v2Request.requestContext.http.sourceIp

                    override val localPort: Int = 0
                    override val localAddress: String = "127.0.0.1"
                    override val localHost: String = "localhost"
                    override val remoteAddress: String = sourceIp ?: "unknown"
                    override val remotePort: Int = 0
                    override val host: String
                        get() = sourceIp ?: "localhost"

                    override val port: Int
                        get() = 443  // API Gateway typically uses HTTPS (port 443)

                    override val scheme: String
                        get() = "https"  // API Gateway uses HTTPS

                    override val version: String
                        get() = v2Request.requestContext.http.protocol ?: "HTTP/1.1"

                    // No re-encoding here, unlike v1: rawQueryString is already exactly what the
                    // client sent, so appending it verbatim cannot disagree with the original.
                    override val uri: String
                        get() = v2Request.rawQueryString?.takeIf { it.isNotEmpty() }
                            ?.let { "${v2Request.rawPath}?$it" }
                            ?: v2Request.rawPath

                    override val method: HttpMethod
                        get() = HttpMethod.parse(v2Request.requestContext.http.method)

                    override val remoteHost: String
                        get() = sourceIp ?: "unknown"

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

            // Parsed from rawQueryString rather than from queryStringParameters: v2 comma-joins
            // repeated parameters into that map, so `?a=1&a=2` arrives as the single value "1,2"
            // and can no longer be told apart from a genuine literal comma. The raw string keeps
            // the repetition intact.
            override val queryParameters: Parameters by lazy {
                v2Request.rawQueryString?.let { parseQueryString(it) } ?: Parameters.Empty
            }
            override val rawQueryParameters: Parameters by lazy {
                v2Request.rawQueryString?.let { parseQueryString(it, decode = false) } ?: Parameters.Empty
            }

            override fun receiveChannel(): ByteReadChannel {
                return if (v2Request.body != null) {
                    val bodyBytes = if (v2Request.isBase64Encoded == true) {
                        Base64.Default.decode(v2Request.body!!)
                    } else {
                        v2Request.body!!.encodeToByteArray()
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

    /**
     * Builds the format 2.0 response.
     *
     * Two things differ from v1 and both are load-bearing:
     *
     *  - there is no `multiValueHeaders`, so repeated headers are comma-joined, which is what API
     *    Gateway itself does and is lossless for every list-valued header in the HTTP spec;
     *  - `Set-Cookie` is the exception, because cookie `Expires` values contain a comma and joining
     *    two of them produces one corrupt cookie. Those go into the dedicated `cookies` array.
     *
     * Empty collections are sent as null so they are omitted entirely — `lambdaJson` has
     * `explicitNulls = false`, and a response object carrying stray empty fields is more likely to
     * be misread by API Gateway as a plain body.
     */
    public suspend fun response(): ApiGatewayV2HttpResponse {
        val resolved = resolveResponse()

        val headers = mutableMapOf<String, String>()
        val cookies = mutableListOf<String>()
        resolved.headers.forEach { name, values ->
            if (name.equals(HttpHeaders.SetCookie, ignoreCase = true)) {
                cookies.addAll(values)
            } else {
                headers[name] = values.joinToString(",")
            }
        }

        return ApiGatewayV2HttpResponse(
            resolved.statusCode,
            headers = headers.ifEmpty { null },
            cookies = cookies.ifEmpty { null },
            body = resolved.body,
            isBase64Encoded = resolved.isBase64Encoded
        )
    }
}
