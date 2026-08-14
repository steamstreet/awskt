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
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
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
 * Carries whatever a caller's `inspectBeforeBody` threw out through the send, so the retry loop can
 * tell a local policy refusal apart from a transport failure.
 *
 * The wrapper is what makes that distinction possible at all. Both arrive at the same `catch`, and
 * [classifyTransportFailure] answers [TransportFailure.AMBIGUOUS] for anything it does not
 * recognise — so without a marker the caller's deliberate refusal is read as "the network might
 * have eaten this" and replayed.
 */
private class InspectionRefusal(val refusal: Throwable) : Throwable(refusal)

/**
 * The retry capacity one [AwsServiceClient.callRaw] invocation has taken from the shared bucket and
 * not yet handed back.
 *
 * A plain `var` rather than an atomic, and that is not an oversight: this object is created inside
 * one call and touched only by the coroutine running it, so there is nothing to race against. The
 * shared state is the bucket it draws from, which is atomic. Making this atomic too would suggest a
 * sharing that does not exist.
 */
private class RetryBudget {
    var spent: Int = 0
}

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
 *
 * ### Thread safety
 *
 * One instance is safe to share across concurrent coroutines, and is meant to be — a handler that
 * fans out with `async { }` runs those calls on `Dispatchers.Default`, which is multi-threaded on
 * the JVM and on Kotlin/Native alike. The two pieces of mutable state that outlive a single call —
 * the retry [RetryTokenBucket] and the learned [clockSkewOffsetMillis] — are therefore atomic
 * rather than plain `var`s. Everything else in [callRaw] is call-local.
 */
@OptIn(ExperimentalAtomicApi::class)
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
    /**
     * Internal rather than private so a test can assert the retry budget's invariant after a run —
     * a property with no observable proxy from outside. Internal declarations do not appear in the
     * ABI dump, so this is not published surface.
     *
     * The invariant is **not** "back at full capacity once the work is done". That held only while
     * [prepareRetry] refunded unconditionally, and it was precisely why the breaker could never
     * open: the bucket then bounded only *concurrently sleeping* retries, so a warm container
     * calling a hard-down dependency retried at full `maxAttempts` forever.
     *
     * What holds now: the capacity missing from the bucket is the summed retry cost of every call
     * that never succeeded, plus the cost already acquired by calls still in flight, less the +1
     * credited by each clean first-attempt success since (saturating at capacity). A call that
     * succeeds refunds exactly what it acquired, so a run of calls that all eventually work still
     * ends at full capacity; a run that all fail does not, and that is the point.
     */
    internal val tokenBucket = RetryTokenBucket()

    /**
     * Learned offset applied to signing time when AWS says our clock is wrong.
     *
     * Atomic because it is written from the response path of one call and read by the signing path
     * of every other in-flight call. Last writer wins, which is the correct resolution: concurrent
     * calls are all measuring the same single quantity — this process's disagreement with AWS —
     * so the most recent measurement is the best one, and there is nothing to accumulate.
     */
    private val clockSkewOffsetMillis = AtomicLong(0)

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
     * @param inspectBeforeBody called with the status and response headers **after they arrive but
     *   before the body is read**, so a caller can reject a response without paying to materialize
     *   it. Throwing from here aborts the call. This is the only point at which that is possible:
     *   everything past it has the whole body in memory, so a check made on the returned
     *   [AwsHttpResponse] runs after the allocation it exists to prevent. `aws-s3` uses it for
     *   `maxBufferedDownloadBytes`, where the failure being prevented is an OOM kill inside Lambda —
     *   which produces no stack trace, no typed exception and no CloudWatch error entry, only a
     *   truncated invocation.
     *
     *   Whatever it throws is a **local policy decision about a response that arrived intact**, so
     *   it propagates unclassified and unretried — see [InspectionRefusal].
     *
     *   This parameter was added in M3.5b, *after* this signature was frozen in the ABI dump at M2.
     *   Source-compatible, binary-incompatible. Ratified 2026-08-12 on the grounds that `aws-core`
     *   had no released version and no external consumer at the time. Do not read the precedent
     *   more broadly than that.
     * @param validateBody called with a **successful** response once its body is in memory, from
     *   inside the retry loop. Its retry semantics are the exact opposite of [inspectBeforeBody]'s,
     *   which is the whole reason it is a separate parameter rather than a second use of that one:
     *   throwing from here reports that *this response is defective*, and is retried as
     *   [RetryErrorType.TRANSIENT] until the attempt budget runs out, after which the exception is
     *   surfaced to the caller unchanged.
     *
     *   The two are a matched pair, and the pairing is the point. A check that a replay cannot fix
     *   (`aws-s3`'s download ceiling — the object really is that big, and will be on the next
     *   attempt too) belongs in [inspectBeforeBody]. A check that a replay *can* fix (`aws-s3`'s
     *   `checkDownloadComplete`, where a connection died mid-body) belongs here. Putting either in
     *   the other's slot is silently wrong rather than broken: the ceiling refusal turns into
     *   several pointless refetches of an oversized object, and the truncation becomes permanent.
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
        inspectBeforeBody: ((status: Int, headers: Map<String, String>) -> Unit)? = null,
        validateBody: ((response: AwsHttpResponse) -> Unit)? = null,
    ): AwsHttpResponse {
        val invocationId = newInvocationId()
        val deadline = clock() + retryConfig.maxTotalRetryDuration.inWholeMilliseconds
        val budget = RetryBudget()
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
                signingInstantMillis = clock() + clockSkewOffsetMillis.load(),
            )

            val response: AwsHttpResponse
            try {
                response = send(method, path, query, signed.headers, body, inspectBeforeBody)
            } catch (refusal: InspectionRefusal) {
                // Caught *before* the classifier, deliberately. `inspectBeforeBody` fired on a
                // response that arrived intact; the caller is refusing it on policy grounds, and no
                // number of replays changes a policy. Let this reach `classifyTransportFailure` and
                // it comes back AMBIGUOUS — which on an IDEMPOTENT operation retries, so `aws-s3`
                // answers "this object is too big to buffer" by fetching the same too-big object
                // several more times, with backoff, before surfacing the identical exception.
                throw refusal.refusal
            } catch (failure: Throwable) {
                // Cancellation is not a transport failure, and must never reach the classifier:
                // `classifyTransportFailure` answers AMBIGUOUS for anything it does not recognise,
                // and AMBIGUOUS on an IDEMPOTENT operation retries. So a cancelled scope would be
                // answered by sending the request again — the client keeps issuing calls precisely
                // when the caller has said to stop. `transportFailureOrNull` answers null for one,
                // and this rethrows it untouched.
                val transportFailure = transportFailureOrNull(failure) ?: throw failure
                lastFailure = transportFailure
                val kind = classifyTransportFailure(transportFailure)
                val mayRetry = when (kind) {
                    TransportFailure.NOT_SENT -> true
                    TransportFailure.AMBIGUOUS ->
                        safety == OperationSafety.IDEMPOTENT || retryConfig.retryAmbiguousWrites
                }
                // `transportFailure`, not `failure`: the two differ only for a timeout that arrived
                // dressed as a cancellation, and there the caller wants the timeout. Surfacing the
                // cancellation instead would report a live call as a cancelled scope.
                if (!mayRetry) throw transportFailure

                attempt++
                if (!prepareRetry(RetryErrorType.TRANSIENT, attempt, deadline, null, budget)) {
                    throw transportFailure
                }
                continue
            }

            if (response.isSuccess) {
                // 2xx is the transport's opinion, not the last word: a body can be short, or
                // otherwise defective, on a response the status called fine. Asked here rather than
                // by the caller on the returned value, because everything past this loop is past
                // the last point at which a replay is still possible.
                val rejection = validateBody?.let { validate ->
                    try {
                        validate(response)
                        null
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        failure
                    }
                }

                if (rejection == null) {
                    // "Returned by succeeding": whatever retries this call paid for bought a working
                    // response, so their cost goes back. The +1 credit is a different thing and is
                    // reserved for a call that needed no retry at all — it is the only way the
                    // bucket refills past what it lent out, and a call that had to retry is not the
                    // evidence of health that earns it.
                    if (attempt == 0) tokenBucket.onCleanSuccess()
                    else tokenBucket.refundCost(budget.spent)
                    return response
                }

                lastFailure?.let { rejection.addSuppressed(it) }
                lastFailure = rejection

                attempt++
                if (!prepareRetry(RetryErrorType.TRANSIENT, attempt, deadline, null, budget)) {
                    throw rejection
                }
                continue
            }

            val details = protocol.errorParser.parse(response.status, response.headers, response.body)
            val exception = toException(details, response)

            // Redirects are surfaced, never followed — see awsHttpClient.
            if (exception is AwsRedirectException) throw exception

            if (!skewCorrectionUsed && shouldCorrectClockSkew(details.code, response)) {
                skewCorrectionUsed = true
                // Only stored when we actually measured one; an unmeasurable response leaves the
                // previous learning in place, exactly as the `?: clockSkewOffsetMillis` did — but
                // without reading and writing the field as two separate steps.
                serverTimeOffset(response)?.let { clockSkewOffsetMillis.store(it) }
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
            if (!prepareRetry(type, attempt, deadline, retryAfter, budget)) throw exception
        }
    }

    /**
     * Returns false when the caller should give up rather than sleep, and charges [budget] for the
     * capacity a retry it green-lights has taken.
     *
     * The charge is *not* returned here. It is returned by [callRaw] when the call it belongs to
     * finally succeeds, and by nothing else — see [tokenBucket]. The one exception is the deadline
     * abort below, where the retry this paid for is not going to happen at all.
     */
    private suspend fun prepareRetry(
        type: RetryErrorType,
        attempt: Int,
        deadlineMillis: Long,
        retryAfterHeader: String?,
        budget: RetryBudget,
    ): Boolean {
        if (attempt >= retryConfig.maxAttempts) return false
        if (!tokenBucket.tryAcquire(type)) return false
        val cost = tokenBucket.costOf(type)
        budget.spent += cost

        val delayMillis = applyRetryAfter(
            backoffMillis(type, attempt - 1, retryConfig, random),
            retryAfterHeader,
        )
        if (clock() + delayMillis > deadlineMillis) {
            // Nothing was retried, so nothing is owed. Charging for a retry the deadline cancelled
            // would open the breaker on evidence that was never gathered.
            tokenBucket.refund(type)
            budget.spent -= cost
            return false
        }
        sleep(delayMillis)
        return true
    }

    private suspend fun send(
        method: String,
        path: String,
        query: List<Pair<String, String>>,
        signedHeaders: List<Pair<String, String>>,
        body: ByteArray,
        inspectBeforeBody: ((status: Int, headers: Map<String, String>) -> Unit)?,
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

        // The last point at which a caller can refuse a response without paying for it. Everything
        // after this line has the whole body in memory, so a size check performed by the caller on
        // the returned `AwsHttpResponse` is a check that runs *after* the allocation it exists to
        // prevent — which is no protection at all against an OOM kill.
        //
        // Wrapped on the way out so the retry loop can tell this apart from a socket dying at the
        // same instant: both surface as a throw from this method, and the classifier's default
        // answer for an unrecognised throw is "retry it".
        if (inspectBeforeBody != null) {
            try {
                inspectBeforeBody(response.status.value, responseHeaders)
            } catch (refusal: Throwable) {
                throw InspectionRefusal(refusal)
            }
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
 * The failure to hand to [classifyTransportFailure], or null when this is the caller's cancellation
 * and must simply propagate.
 *
 * Everything that is not a [CancellationException] is itself. The interesting case is the one that
 * is: Ktor's `HttpTimeout` plugin enforces `requestTimeoutMillis` by **cancelling the call's job**
 * with an `HttpRequestTimeoutException` as the cancellation cause. Ktor unwraps that back to the
 * typed exception on the paths that go through `unwrapRequestTimeoutException`, but a streaming read
 * outside those paths surfaces the cancellation as-is — and read as a cancelled scope, the timeout
 * this library just added would be neither retried nor reported as a timeout.
 *
 * A cause that is itself a [CancellationException] is skipped rather than unwrapped, and that
 * exclusion is the whole safety of this function: a caller's `withTimeout` cancels with a
 * `TimeoutCancellationException`, whose name matches the same pattern. Unwrapping it would answer a
 * caller's "stop now" by sending the request again.
 */
internal fun transportFailureOrNull(failure: Throwable): Throwable? {
    if (failure !is CancellationException) return failure
    var current: Throwable? = failure.cause
    var depth = 0
    while (current != null && depth < 8) {
        if (current !is CancellationException && current::class.simpleName.orEmpty().contains("Timeout")) {
            return current
        }
        current = current.cause
        depth++
    }
    return null
}

/**
 * Classifies a transport failure.
 *
 * The default is [TransportFailure.AMBIGUOUS] on purpose: an unrecognised failure might have
 * reached AWS, and treating it as provably-not-sent would let a non-idempotent write replay.
 *
 * ### The three timeouts land on two different answers
 *
 * A connect timeout (`ConnectTimeoutException`, matched by name below and again by its message) is
 * [TransportFailure.NOT_SENT]: the socket never carried a byte, so replaying it cannot double-apply
 * anything, and it is retried even for a write.
 *
 * A request or socket timeout (`HttpRequestTimeoutException`, `SocketTimeoutException`) is
 * **deliberately left to the AMBIGUOUS default**, and neither name nor message may be added to the
 * list above it: both fire after the request was already on the wire, so AWS may well have applied
 * it. Retried for an IDEMPOTENT operation, surfaced for a write. Matching either as NOT_SENT would
 * silently make every timed-out `PutEvents` replayable.
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
            // Belt to the name check's braces. `ConnectTimeoutException` (Ktor's wording) covers
            // CIO and Curl; "connect timed out" is the JDK's, which some engines raise as a plain
            // `java.net.SocketTimeoutException` — a name that must otherwise stay AMBIGUOUS,
            // because the same class also carries "Read timed out". Both are narrow enough not to
            // collide with "Request timeout has expired".
            "connect timeout has expired" in message ||
            "connect timed out" in message ||
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
