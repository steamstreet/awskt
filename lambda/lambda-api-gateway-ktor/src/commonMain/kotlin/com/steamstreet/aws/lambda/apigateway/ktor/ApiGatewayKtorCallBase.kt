package com.steamstreet.aws.lambda.apigateway.ktor

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.encoding.Base64

/**
 * Everything about driving a Ktor call that does not depend on which API Gateway payload format the
 * event arrived in.
 *
 * The two formats differ only at the edges — how you read a method, a path and a query out of the
 * event, and how you shape the result object. The middle, which is the part with the sharp corners,
 * is identical: capture the [OutgoingContent] the send pipeline produces, keep `isCommitted` and
 * `isSent` honest across repeated `call.response` accesses, drain the body out of whichever
 * `OutgoingContent` subtype turned up, and decide whether the result is text or base64. That is what
 * lives here, so v1 and v2 cannot drift apart on it.
 *
 * Subclasses supply [request] and [attributes], and build their own response object from
 * [resolveResponse].
 *
 * The constructor is internal: this is a shared implementation, not an extension point. It is public
 * only because [ApiGatewayKtorCall] is.
 */
public abstract class ApiGatewayKtorCallBase internal constructor(
    override val application: Application
) : PipelineCall {
    override val coroutineContext: CoroutineContext = EmptyCoroutineContext
    override val parameters: Parameters get() = request.queryParameters

    private val responseHeadersBuilder = HeadersBuilder()
    private var statusCode: Int = 0
    private var responseContent: OutgoingContent? = null

    // isCommitted/isSent must survive across calls to the `response` getter (which
    // returns a fresh anonymous PipelineResponse each access). Ktor's CallFailed
    // hook reads `call.response.isSent` after the StatusPages handler runs and
    // re-throws the original exception if it sees false — so per-instance state
    // wouldn't work.
    //
    // These are plain properties rather than atomics. What they need is for a write to be visible
    // to a later read, and the pipeline only interleaves them across coroutine suspension points,
    // which already establish happens-before — the same reason `responseContent` beside them is an
    // ordinary `var`. They are never contended: one call object serves one request.
    private var isSentFlag: Boolean = false

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
                get() = this@ApiGatewayKtorCallBase
            override val cookies: ResponseCookies by lazy { ResponseCookies(this) }
            override val isCommitted: Boolean get() = responseContent != null
            override var isSent: Boolean
                get() = isSentFlag
                set(value) {
                    isSentFlag = value
                }
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

            override fun status(): HttpStatusCode = HttpStatusCode.fromValue(statusCode)

            override fun status(value: HttpStatusCode) {
                statusCode = value.value
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
    internal fun isTextContent(contentType: ContentType): Boolean {
        return contentType.contentType == "text" ||
                contentType.match(ContentType.Application.Json) ||
                contentType.match(ContentType.Application.JavaScript) ||
                contentType.match(ContentType.Application.Xml) ||
                contentType.match(ContentType.Application.FormUrlEncoded)
    }

    /**
     * The response reduced to the four things both payload formats need, leaving only the shape of
     * the result object to the subclass.
     */
    internal suspend fun resolveResponse(): ResolvedResponse {
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
            statusCode = it.status?.value ?: this.statusCode.let { status ->
                if (status == 0) {
                    200
                } else {
                    status
                }
            }
        }

        val responseHeaders = responseHeadersBuilder.build()
        responseBytes?.let { bytes ->
            if (bytes.isNotEmpty()) {
                val contentType = responseHeaders["Content-Type"]?.let {
                    ContentType.parse(it)
                } ?: responseContent?.contentType ?: ContentType.Application.OctetStream

                val contentEncoding = responseHeaders["Content-Encoding"]

                body = if (contentEncoding == null && isTextContent(contentType)) {
                    bytes.decodeToString()
                } else {
                    isBase64 = true
                    Base64.Default.encode(bytes)
                }.ifEmpty { null }
            }
        }

        return ResolvedResponse(
            // A pipeline that never set a status and never produced content means nothing matched
            // the route — which for API Gateway is a 404, not a 0.
            statusCode = statusCode.let { if (it == 0) 404 else it },
            headers = responseHeaders,
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

/**
 * The format-independent result of running the pipeline.
 *
 * [headers] is kept as multi-valued [Headers] rather than pre-flattened, because that is precisely
 * where the two payload formats diverge: v1 can carry the values as-is in `multiValueHeaders`,
 * whereas v2 has to comma-join them and lift `Set-Cookie` into a separate array. Flattening here
 * would force one of the two to un-flatten.
 */
internal class ResolvedResponse(
    val statusCode: Int,
    val headers: Headers,
    val body: String?,
    val isBase64Encoded: Boolean
)
