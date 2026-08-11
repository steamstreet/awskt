package com.steamstreet.awskt.core

import com.steamstreet.awskt.signing.PayloadHash
import com.steamstreet.awskt.signing.SigV4
import com.steamstreet.awskt.signing.SigV4Config
import com.steamstreet.awskt.signing.SignedBodyHeader
import com.steamstreet.awskt.signing.SigningRequest
import com.steamstreet.awskt.signing.sigV4UriEncode
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.delay
import kotlin.random.Random

/** A raw, already-classified AWS response. */
public class AwsHttpResponse(
    public val status: Int,
    public val headers: Map<String, String>,
    public val body: ByteArray,
) {
    public val isSuccess: Boolean get() = status in 200..299
    override fun toString(): String = "AwsHttpResponse(status=$status, bytes=${body.size})"
}

/**
 * The wire dialect of a service: how an operation is addressed and how its errors are shaped.
 *
 * @param targetPrefix the `X-Amz-Target` prefix for AWS-JSON services (`DynamoDB_20120810`), or
 *   null for REST-shaped services such as S3.
 */
public class AwsProtocol(
    public val endpointPrefix: String,
    public val signingName: String,
    public val contentType: String?,
    public val targetPrefix: String?,
    public val errorParser: AwsErrorParser,
) {
    public companion object {
        public fun awsJson1_0(
            endpointPrefix: String,
            targetPrefix: String,
            signingName: String = endpointPrefix,
        ): AwsProtocol = AwsProtocol(
            endpointPrefix, signingName, "application/x-amz-json-1.0", targetPrefix, AwsJsonErrorParser,
        )

        public fun awsJson1_1(
            endpointPrefix: String,
            targetPrefix: String,
            signingName: String = endpointPrefix,
        ): AwsProtocol = AwsProtocol(
            endpointPrefix, signingName, "application/x-amz-json-1.1", targetPrefix, AwsJsonErrorParser,
        )

        public fun restXml(
            endpointPrefix: String,
            signingName: String = endpointPrefix,
        ): AwsProtocol = AwsProtocol(endpointPrefix, signingName, null, null, RestXmlErrorParser)
    }
}

internal enum class TransportFailure { NOT_SENT, AMBIGUOUS }

/**
 * A pre-materialized body with an explicit content type.
 *
 * Pre-materialized on purpose: the payload hash covers these exact bytes, so the engine must not be
 * free to re-chunk or re-encode them. A future streaming overload cannot simply inherit this.
 */
private class SignedBody(
    private val bytes: ByteArray,
    override val contentType: ContentType?,
) : OutgoingContent.ByteArrayContent() {
    override val contentLength: Long = bytes.size.toLong()
    override fun bytes(): ByteArray = bytes
}

/**
 * Signs, sends, retries and classifies. Everything above this — typed operations, DTOs — is a
 * service module's business; everything below it is Ktor's.
 *
 * The retry loop is hand-written rather than Ktor's `HttpRequestRetry` for two reasons that plugin
 * cannot satisfy: classification needs the *parsed* error code (code beats status, and a
 * never-retry deny-list runs first), and **each attempt must be re-signed** with fresh credentials
 * and a fresh timestamp. A replayed signature is a stale signature.
 */
public class AwsServiceClient(
    private val httpClient: HttpClient,
    private val credentialsProvider: AwsCredentialsProvider,
    private val endpoint: AwsEndpoint,
    private val region: String,
    private val protocol: AwsProtocol,
    private val retryConfig: RetryConfig = RetryConfig(),
    private val clock: () -> Long = ::currentEpochMillis,
    private val random: () -> Double = ::defaultRandom,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val tokenBucket = RetryTokenBucket()

    /** Learned offset applied to signing time when AWS says our clock is wrong. */
    private var clockSkewOffsetMillis: Long = 0

    /**
     * Issues one signed request, retrying per [retryConfig].
     *
     * Deliberately shaped `(method, path, query, headers, body)` rather than
     * `(operation, jsonBody)`. The narrower form presumes POST + `X-Amz-Target` + a JSON body and
     * cannot express a GET with a path and query or a raw octet-stream body — so S3 would have
     * forced retroactive surgery on this class *after* the DynamoDB client had already validated
     * against it.
     *
     * @param path already percent-encoded, and **never re-encoded here**. The byte-identical string
     *   used to build the outbound URL is the string that was signed.
     * @param safety whether an ambiguous mid-flight failure may be retried.
     */
    public suspend fun callRaw(
        method: String,
        path: String = "/",
        query: List<Pair<String, String>> = emptyList(),
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray = EMPTY_BODY,
        operation: String? = null,
        safety: OperationSafety = OperationSafety.IDEMPOTENT,
        payloadHash: PayloadHash = PayloadHash.Compute,
        signedBodyHeader: SignedBodyHeader = SignedBodyHeader.NONE,
        doubleUriEncode: Boolean = true,
        normalizeUriPath: Boolean = true,
    ): AwsHttpResponse {
        val invocationId = newInvocationId()
        val deadline = clock() + retryConfig.maxTotalRetryDuration.inWholeMilliseconds
        var attempt = 0
        var skewCorrectionUsed = false
        var lastFailure: Throwable? = null

        while (true) {
            // Resolved per attempt, not once: a call spanning four attempts and a 20-second cap can
            // outlive the credentials it started with.
            val credentials = credentialsProvider.resolve()

            val requestHeaders = buildList {
                protocol.contentType?.let { add("Content-Type" to it) }
                protocol.targetPrefix?.let { prefix ->
                    operation?.let { add("X-Amz-Target" to "$prefix.$it") }
                }
                addAll(headers)
                add("amz-sdk-invocation-id" to invocationId)
                add("amz-sdk-request" to "attempt=${attempt + 1}; max=${retryConfig.maxAttempts}")
                // Signing a compressed body we never see would break the payload hash.
                add("accept-encoding" to "identity")
            }

            val signed = SigV4.sign(
                request = SigningRequest(
                    method = method,
                    path = path,
                    host = endpoint.authority,
                    queryParameters = query,
                    headers = requestHeaders,
                    body = body,
                ),
                credentials = credentials,
                config = SigV4Config(
                    region = region,
                    service = protocol.signingName,
                    payloadHash = payloadHash,
                    signedBodyHeader = signedBodyHeader,
                    doubleUriEncode = doubleUriEncode,
                    normalizeUriPath = normalizeUriPath,
                ),
                signingInstantMillis = clock() + clockSkewOffsetMillis,
            )

            val response: AwsHttpResponse
            try {
                response = send(method, path, query, signed.headers, body)
            } catch (failure: Throwable) {
                lastFailure = failure
                val kind = classifyTransportFailure(failure)
                val mayRetry = when (kind) {
                    TransportFailure.NOT_SENT -> true
                    TransportFailure.AMBIGUOUS ->
                        safety == OperationSafety.IDEMPOTENT || retryConfig.retryAmbiguousWrites
                }
                if (!mayRetry) throw failure

                attempt++
                if (!prepareRetry(RetryErrorType.TRANSIENT, attempt, deadline, null)) throw failure
                continue
            }

            if (response.isSuccess) {
                if (attempt == 0) tokenBucket.onCleanSuccess()
                return response
            }

            val details = protocol.errorParser.parse(response.status, response.headers, response.body)
            val exception = toException(details, response)

            // Redirects are surfaced, never followed — see awsHttpClient.
            if (exception is AwsRedirectException) throw exception

            if (!skewCorrectionUsed && shouldCorrectClockSkew(details.code, response)) {
                skewCorrectionUsed = true
                clockSkewOffsetMillis = serverTimeOffset(response) ?: clockSkewOffsetMillis
                attempt++
                if (attempt >= retryConfig.maxAttempts) throw exception
                continue
            }

            val type = classifyRetry(details.code, response.status)
            if (type == null) throw exception

            lastFailure?.let { exception.addSuppressed(it) }
            lastFailure = exception

            attempt++
            val retryAfter = response.headers.headerValue("x-amz-retry-after")
            if (!prepareRetry(type, attempt, deadline, retryAfter)) throw exception
        }
    }

    /** Returns false when the caller should give up rather than sleep. */
    private suspend fun prepareRetry(
        type: RetryErrorType,
        attempt: Int,
        deadlineMillis: Long,
        retryAfterHeader: String?,
    ): Boolean {
        if (attempt >= retryConfig.maxAttempts) return false
        if (!tokenBucket.tryAcquire(type)) return false

        val delayMillis = applyRetryAfter(
            backoffMillis(type, attempt - 1, retryConfig, random),
            retryAfterHeader,
        )
        if (clock() + delayMillis > deadlineMillis) {
            tokenBucket.refund(type)
            return false
        }
        sleep(delayMillis)
        tokenBucket.refund(type)
        return true
    }

    private suspend fun send(
        method: String,
        path: String,
        query: List<Pair<String, String>>,
        signedHeaders: List<Pair<String, String>>,
        body: ByteArray,
    ): AwsHttpResponse {
        val builder = HttpRequestBuilder()
        builder.method = HttpMethod.parse(method)
        builder.url {
            this.protocol = endpoint.protocol
            this.host = endpoint.host
            this.port = endpoint.port
            // Segments, not a whole string: Ktor 3 has no encodedPath setter, and its segment list
            // is joined verbatim — which is exactly the "do not re-encode" property we need.
            encodedPathSegments = path.split('/')
            for ((name, value) in query) {
                encodedParameters.append(sigV4UriEncode(name), sigV4UriEncode(value))
            }
        }

        // Defensive: if a Ktor upgrade ever normalizes the path we hand it, the signature silently
        // stops matching and the failure looks like a signer bug. Catch it here instead.
        val outboundPath = builder.url.encodedPathSegments.joinToString("/")
        check(outboundPath == path) {
            "Ktor rewrote the request path after signing: signed '$path', would send " +
                "'$outboundPath'. The signature would not match."
        }

        // Content-Type is carried on the body, not in the header map. Ktor owns that header and
        // will invent one for a bare ByteArray body — and a Content-Type we did not write is a
        // Content-Type we did not sign. Setting it explicitly is what keeps the two identical.
        var contentType: String? = null
        for ((name, value) in signedHeaders) {
            if (name.equals("Host", ignoreCase = true)) continue
            if (name.equals("Content-Type", ignoreCase = true)) {
                contentType = value
                continue
            }
            builder.headers.append(name, value)
        }
        builder.setBody(SignedBody(body, contentType?.let { ContentType.parse(it) }))

        val response = httpClient.request(builder)
        val responseHeaders = buildMap {
            response.headers.forEach { name, values -> put(name.lowercase(), values.joinToString(",")) }
        }
        return AwsHttpResponse(response.status.value, responseHeaders, response.readRawBytes())
    }

    private fun toException(details: ErrorDetails, response: AwsHttpResponse): AwsServiceException {
        val requestId = response.headers.headerValue("x-amz-request-id")
            ?: response.headers.headerValue("x-amzn-requestid")
        val extendedRequestId = response.headers.headerValue("x-amz-id-2")

        if (response.status == 301 || response.status == 307) {
            return AwsRedirectException(
                code = details.code ?: if (response.status == 301) "PermanentRedirect" else "TemporaryRedirect",
                message = details.message
                    ?: "AWS returned ${response.status}; the request was not followed because the " +
                    "signature is bound to the original host.",
                statusCode = response.status,
                location = response.headers.headerValue("location"),
                bucketRegion = response.headers.headerValue("x-amz-bucket-region"),
                requestId = requestId,
                extendedRequestId = extendedRequestId,
            )
        }

        return AwsServiceException(
            code = details.code,
            message = details.message,
            statusCode = response.status,
            requestId = requestId,
            extendedRequestId = extendedRequestId,
            // Carried so a service module can lift structured error payload — DynamoDB's failed
            // `Item`, its `CancellationReasons` — out of an error the transport has already
            // reduced to a code and a message.
            rawErrorBody = response.body,
        )
    }

    private fun shouldCorrectClockSkew(code: String?, response: AwsHttpResponse): Boolean {
        if (code == null) return false
        if (code in CLOCK_SKEW_CODES) return true
        if (code !in SIGNATURE_FAILURE_CODES) return false
        val offset = serverTimeOffset(response) ?: return false
        return offset > 4 * 60 * 1_000 || offset < -4 * 60 * 1_000
    }

    private fun serverTimeOffset(response: AwsHttpResponse): Long? {
        val serverMillis = parseHttpDateOrNull(response.headers.headerValue("date") ?: return null)
            ?: return null
        return serverMillis - clock()
    }

    private fun newInvocationId(): String =
        Random.nextLong().toULong().toString(16).padStart(16, '0')

    private companion object {
        val EMPTY_BODY = ByteArray(0)

        val CLOCK_SKEW_CODES = setOf(
            "RequestTimeTooSkewed", "RequestExpired", "RequestInTheFuture",
        )

        /** Skew is only inferred for these when the `Date` header actually disagrees. */
        val SIGNATURE_FAILURE_CODES = setOf(
            "SignatureDoesNotMatch", "InvalidSignatureException", "AuthFailure",
        )
    }
}

/**
 * Classifies a transport failure.
 *
 * The default is [TransportFailure.AMBIGUOUS] on purpose: an unrecognised failure might have
 * reached AWS, and treating it as provably-not-sent would let a non-idempotent write replay.
 */
internal fun classifyTransportFailure(failure: Throwable): TransportFailure {
    var current: Throwable? = failure
    var depth = 0
    while (current != null && depth < 8) {
        val name = current::class.simpleName.orEmpty()
        val message = current.message?.lowercase().orEmpty()
        val notSent = name.contains("ConnectTimeout") ||
            name.contains("UnresolvedAddress") ||
            name.contains("UnknownHost") ||
            name.contains("ConnectException") ||
            name.contains("SSLHandshake") ||
            name.contains("TlsHandshake") ||
            "connection refused" in message ||
            "failed to connect" in message ||
            "unresolved address" in message ||
            "nodename nor servname" in message
        if (notSent) return TransportFailure.NOT_SENT
        current = current.cause
        depth++
    }
    return TransportFailure.AMBIGUOUS
}

/** Parses an RFC 7231 IMF-fixdate (`Sun, 06 Nov 1994 08:49:37 GMT`) to epoch millis. */
internal fun parseHttpDateOrNull(value: String): Long? = try {
    val parts = value.trim().split(' ')
    val day = parts[1].toLong()
    val month = MONTHS.indexOf(parts[2]).toLong() + 1
    val year = parts[3].toLong()
    val time = parts[4].split(':')
    val hour = time[0].toLong()
    val minute = time[1].toLong()
    val second = time[2].toLong()

    val y = if (month <= 2L) year - 1L else year
    val era = (if (y >= 0L) y else y - 399L) / 400L
    val yearOfEra = y - era * 400L
    val monthPrime = if (month > 2L) month - 3L else month + 9L
    val dayOfYear = (153L * monthPrime + 2L) / 5L + day - 1L
    val dayOfEra = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYear
    val days = era * 146_097L + dayOfEra - 719_468L

    if (month < 1 || month > 12) null
    else ((days * 86_400L) + hour * 3_600L + minute * 60L + second) * 1_000L
} catch (e: Exception) {
    null
}

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
