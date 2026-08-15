package com.steamstreet.awskt.core

import com.steamstreet.awskt.core.AwsCallEvent.Outcome
import com.steamstreet.awskt.signing.PayloadHash
import com.steamstreet.awskt.signing.SigV4
import com.steamstreet.awskt.signing.SigV4Config
import com.steamstreet.awskt.signing.SignedBodyHeader
import com.steamstreet.awskt.signing.SignedRequest
import com.steamstreet.awskt.signing.SigningRequest
import com.steamstreet.awskt.signing.sigV4UriEncode
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.delay
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException

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

        /**
         * REST-shaped JSON: a plain `application/json` body addressed by **method and path** rather
         * than by `X-Amz-Target`.
         *
         * The null [targetPrefix] is the whole difference from [awsJson1_0] / [awsJson1_1] and it is
         * load-bearing: `AwsServiceClient` only emits `X-Amz-Target` when a prefix is present, so a
         * restJson1 service addressed through this protocol sends no target header at all. Pair it
         * with [callRestJson] rather than [callJson], which hardcodes `POST /`.
         *
         * Errors are [AwsJsonErrorParser]'s, unchanged — restJson1 shares awsJson's error envelope,
         * including the `x-amzn-errortype` header, which is where a restJson1 service usually puts
         * the code. EventBridge Scheduler is the consumer in this repo.
         *
         * The plan's M2 note called restJson1 "a second codec into `aws-core`" and treated that as
         * a reason to defer it. It was already stale by M3.5 — S3 forced the protocol seam and the
         * `AwsErrorParser` strategy that make this factory four lines rather than a codec.
         */
        public fun restJson1(
            endpointPrefix: String,
            signingName: String = endpointPrefix,
        ): AwsProtocol = AwsProtocol(endpointPrefix, signingName, "application/json", null, AwsJsonErrorParser)

        /**
         * The **AWS query protocol**: a form-encoded body naming an `Action`, answered with XML.
         *
         * The oldest wire format AWS still serves, and the one SNS speaks. Nothing here parses
         * either direction — a query service's request is built by flattening a structure into
         * `Name.member.1.Field` keys and its response is XML, neither of which is a codec
         * `aws-core` can supply generically. This factory contributes the three things that *are*
         * protocol-level: the content type, the absence of a target header, and the error parser.
         * The service module hand-writes the rest, which is proportionate when the module has two
         * operations and would not be if it had twenty.
         *
         * [RestXmlErrorParser] is correct here despite the name. A query-protocol error is
         * `<ErrorResponse><Error><Code>…</Code><Message>…</Message></Error></ErrorResponse>` and
         * that parser scans for the first `<Code>` and `<Message>` at any depth, which finds
         * exactly those. It is named for the protocol it was written for, not for the only one it
         * fits.
         */
        public fun awsQuery(
            endpointPrefix: String,
            signingName: String = endpointPrefix,
        ): AwsProtocol = AwsProtocol(
            endpointPrefix, signingName, "application/x-www-form-urlencoded", null, RestXmlErrorParser,
        )
    }
}

internal enum class TransportFailure { NOT_SENT, AMBIGUOUS }

/**
 * The version reported in the outgoing `User-Agent`.
 *
 * **Hardcoded, and release tooling must bump it.** There is no version to read at runtime: the build
 * derives one from git tags via the `nebula.release` plugin, so `gradle.properties` carries none and
 * a multiplatform `commonMain` source set has no equivalent of the JVM's `Package.implementation
 * Version` to fall back on. `3.0` is the line this branch releases into.
 *
 * Getting it stale is a small, silent cost — CloudTrail and S3 access logs attribute the call to the
 * wrong release — so it belongs in the release checklist next to the version bump itself, not in a
 * comment nobody reads at release time.
 */
internal const val AWSKT_VERSION: String = "3.0.0"

/**
 * The `User-Agent` sent on every call that does not bring its own.
 *
 * Shaped `awskt/<version> <transport>` after the official SDKs' `aws-sdk-kotlin/1.2.3 …` convention:
 * a token AWS Support and CloudTrail's `userAgent` field can key on, plus the transport, because
 * "which HTTP engine" is the first question asked about a transport-level failure. Without it,
 * requests arrive labelled `ktor-client` (CIO) or unlabelled (Curl), and every awskt caller in an
 * account is indistinguishable from every other Ktor program.
 *
 * Never signed — `user-agent` is in the signer's skipped set, along with everything else a proxy or
 * an engine may rewrite — so changing this string cannot break a signature.
 */
internal const val AWSKT_USER_AGENT: String = "awskt/$AWSKT_VERSION ktor"

/**
 * Carries whatever a caller's `inspectBeforeBody` threw out through the send, so the retry loop can
 * tell a local policy refusal apart from a transport failure.
 *
 * The wrapper is what makes that distinction possible at all. Both arrive at the same `catch`, and
 * [classifyTransportFailure] answers [TransportFailure.AMBIGUOUS] for anything it does not
 * recognise — so without a marker the caller's deliberate refusal is read as "the network might
 * have eaten this" and replayed.
 *
 * @param status the status the refused response arrived with, carried purely so an [AwsCallObserver]
 *   can report *which* response was refused. The retry loop itself does not look at it.
 */
private class InspectionRefusal(val refusal: Throwable, val status: Int) : Throwable(refusal)

/**
 * Carries whatever a caller's `consume` threw out through [AwsServiceClient.callStreaming]'s send,
 * so the retry loop can tell it apart from a transport failure.
 *
 * Exactly the same problem [InspectionRefusal] solves, and the same shape of answer, because
 * without a marker both arrive at the same `catch` and [classifyTransportFailure] answers
 * AMBIGUOUS for anything it does not recognise — which on an IDEMPOTENT operation retries.
 *
 * **The bug this prevents is a bad one and it was real**: a modelled failure delivered *inside* a
 * successful stream — Bedrock reporting `ModelStreamErrorException` half-way through a generation —
 * propagated out of `consume`, was read as "the network might have eaten this", and replayed the
 * entire call. The caller saw a partial answer, then a second partial answer, and was billed for
 * both.
 */
private class ConsumerFailure(val failure: Throwable) : Throwable(failure)

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
    /**
     * Notified of every attempt, every retry decision and every give-up. Null means no
     * instrumentation and costs nothing: [notify] returns before an [AwsCallEvent] is allocated.
     *
     * See [AwsCallObserver] for the shape of the stream and for the guarantee that a throwing
     * observer cannot fail a call.
     */
    private val observer: AwsCallObserver? = null,
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
     *   throwing from here reports that *this response is defective*, and on an
     *   [OperationSafety.IDEMPOTENT] operation is retried as [RetryErrorType.TRANSIENT] until the
     *   attempt budget runs out, after which the exception is surfaced to the caller unchanged.
     *
     *   **On a [OperationSafety.NOT_IDEMPOTENT] operation the rejection is surfaced immediately,
     *   unretried.** A rejection here is not an ambiguous failure: the 2xx arrived, so the request
     *   provably reached AWS and was applied, and replaying it to obtain a better copy of the
     *   answer applies the write a second time. A defective body is the cheaper of the two
     *   failures. [RetryConfig.retryAmbiguousWrites] does not unlock this — it is an opt-in for the
     *   case where we cannot tell whether the request landed, and here we can.
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
            // The 1-based number of the attempt about to be made, and the instant it starts. The
            // clock is read *before* credentials are resolved so the reported duration covers the
            // whole attempt — a stalled IMDS hop is part of what the caller waited for. See
            // AwsCallEvent.durationMillis.
            val attemptNumber = attempt + 1
            val attemptStart = clock()

            // Resolved per attempt, not once: a call spanning four attempts and a 20-second cap can
            // outlive the credentials it started with.
            val signed = signAttempt(
                method, path, query, headers, body, operation, invocationId, attempt,
                payloadHash, signedBodyHeader, doubleUriEncode, normalizeUriPath,
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
                //
                // GAVE_UP and no terminal event: the call is over, but the attempt has no outcome
                // that is honestly AWS's — see AwsCallEvent.
                notify(Outcome.GAVE_UP, operation, attemptNumber, refusal.status, null, null, attemptStart)
                throw refusal.refusal
            } catch (failure: Throwable) {
                // Cancellation is not a transport failure, and must never reach the classifier:
                // `classifyTransportFailure` answers AMBIGUOUS for anything it does not recognise,
                // and AMBIGUOUS on an IDEMPOTENT operation retries. So a cancelled scope would be
                // answered by sending the request again — the client keeps issuing calls precisely
                // when the caller has said to stop. `transportFailureOrNull` answers null for one,
                // and this rethrows it untouched.
                val transportFailure = transportFailureOrNull(failure) ?: throw failure
                notify(Outcome.TRANSPORT_FAILURE, operation, attemptNumber, null, null, null, attemptStart)
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
                if (!mayRetry) {
                    notify(Outcome.GAVE_UP, operation, attemptNumber, null, null, null, attemptStart)
                    throw transportFailure
                }

                attempt++
                if (!prepareRetry(
                        RetryErrorType.TRANSIENT, attempt, deadline, null, budget, operation, attemptStart,
                    )
                ) {
                    notify(Outcome.GAVE_UP, operation, attemptNumber, null, null, null, attemptStart)
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
                    notify(
                        Outcome.SUCCESS, operation, attemptNumber, response.status, null, null, attemptStart,
                    )
                    return response
                }

                // TRANSPORT_FAILURE with a status: the bytes arrived and were defective. `validateBody`
                // is retried exactly like a dead socket, and reporting it as anything else would put a
                // truncated download in the same bucket as a 500 from AWS.
                notify(
                    Outcome.TRANSPORT_FAILURE, operation, attemptNumber, response.status, null, null,
                    attemptStart,
                )
                lastFailure?.let { rejection.addSuppressed(it) }
                lastFailure = rejection

                // A rejection is *not* an ambiguous failure, which is why `safety` is consulted
                // here at all: the 2xx arrived, so the request provably reached AWS and was
                // applied. Replaying a write to fetch a better copy of its answer double-applies
                // it, and a defective body is the cheaper failure. Deliberately not gated on
                // `retryAmbiguousWrites` — that opts in to retrying when we cannot tell whether
                // the request landed, and here we can.
                if (safety == OperationSafety.NOT_IDEMPOTENT) {
                    notify(
                        Outcome.GAVE_UP, operation, attemptNumber, response.status, null, null, attemptStart,
                    )
                    throw rejection
                }

                attempt++
                if (!prepareRetry(
                        RetryErrorType.TRANSIENT, attempt, deadline, null, budget, operation, attemptStart,
                    )
                ) {
                    notify(
                        Outcome.GAVE_UP, operation, attemptNumber, response.status, null, null, attemptStart,
                    )
                    throw rejection
                }
                continue
            }

            val details = protocol.errorParser.parse(response.status, response.headers, response.body)
            val exception = toException(details, response)
            notify(
                Outcome.SERVICE_ERROR, operation, attemptNumber, response.status, details.code, null,
                attemptStart,
            )

            // Redirects are surfaced, never followed — see awsHttpClient.
            if (exception is AwsRedirectException) {
                notify(
                    Outcome.GAVE_UP, operation, attemptNumber, response.status, details.code, null,
                    attemptStart,
                )
                throw exception
            }

            if (!skewCorrectionUsed && shouldCorrectClockSkew(details.code, response)) {
                skewCorrectionUsed = true
                // Only stored when we actually measured one; an unmeasurable response leaves the
                // previous learning in place, exactly as the `?: clockSkewOffsetMillis` did — but
                // without reading and writing the field as two separate steps.
                serverTimeOffset(response)?.let { clockSkewOffsetMillis.store(it) }
                // The one retry that takes no backoff at all, which is why this reports itself
                // rather than arriving as a RETRY_SCHEDULED with a zero delay.
                notify(
                    Outcome.CLOCK_SKEW_CORRECTED, operation, attemptNumber, response.status, details.code,
                    null, attemptStart,
                )
                attempt++
                if (attempt >= retryConfig.maxAttempts) {
                    notify(
                        Outcome.GAVE_UP, operation, attemptNumber, response.status, details.code, null,
                        attemptStart,
                    )
                    throw exception
                }
                continue
            }

            val type = classifyRetry(details.code, response.status)
            if (type == null) {
                notify(
                    Outcome.GAVE_UP, operation, attemptNumber, response.status, details.code, null,
                    attemptStart,
                )
                throw exception
            }

            lastFailure?.let { exception.addSuppressed(it) }
            lastFailure = exception

            attempt++
            val retryAfter = response.headers.headerValue("x-amz-retry-after")
            if (!prepareRetry(type, attempt, deadline, retryAfter, budget, operation, attemptStart)) {
                notify(
                    Outcome.GAVE_UP, operation, attemptNumber, response.status, details.code, null,
                    attemptStart,
                )
                throw exception
            }
        }
    }

    /**
     * Hands one event to [observer], and absorbs whatever that does.
     *
     * The `?: return` is the reason this is cheap enough to call unconditionally from the request
     * path: with no observer configured, no [AwsCallEvent] is ever allocated.
     */
    private fun notify(
        outcome: Outcome,
        operation: String?,
        attempt: Int,
        statusCode: Int?,
        errorCode: String?,
        willRetryAfterMillis: Long?,
        attemptStartMillis: Long,
    ) {
        val target = observer ?: return
        try {
            target.onAttempt(
                AwsCallEvent(
                    operation = operation,
                    attempt = attempt,
                    outcome = outcome,
                    statusCode = statusCode,
                    errorCode = errorCode,
                    willRetryAfterMillis = willRetryAfterMillis,
                    durationMillis = clock() - attemptStartMillis,
                ),
            )
        } catch (cancellation: CancellationException) {
            // Never swallowed: this is structured concurrency tearing the call down, not a fault in
            // the observer. Absorbing it would keep a cancelled coroutine issuing AWS requests.
            throw cancellation
        } catch (_: Throwable) {
            // Swallowed by contract — see AwsCallObserver. Instrumentation does not get to fail the
            // call it is measuring; a broken metrics tag must not look like a DynamoDB outage.
        }
    }

    /**
     * Returns false when the caller should give up rather than sleep, and charges [budget] for the
     * capacity a retry it green-lights has taken.
     *
     * The charge is *not* returned here. It is returned by [callRaw] when the call it belongs to
     * finally succeeds, and by nothing else — see [tokenBucket]. The one exception is the deadline
     * abort below, where the retry this paid for is not going to happen at all.
     *
     * [AwsCallEvent.Outcome.RETRY_SCHEDULED] is emitted from here rather than from [callRaw] because
     * this is the only place that knows the chosen delay, and it is emitted *before* the sleep so an
     * observer learns of a five-second backoff when it starts rather than when it ends.
     *
     * @param attempt the 1-based number of the attempt that just failed — already incremented by the
     *   caller — which is both the count of attempts made and the number [notify] reports.
     * @param attemptStartMillis the failed attempt's start, so the emitted event's duration stays on
     *   the same baseline as that attempt's terminal event.
     */
    private suspend fun prepareRetry(
        type: RetryErrorType,
        attempt: Int,
        deadlineMillis: Long,
        retryAfterHeader: String?,
        budget: RetryBudget,
        operation: String?,
        attemptStartMillis: Long,
    ): Boolean {
        if (attempt >= retryConfig.maxAttempts) return false
        if (!tokenBucket.tryAcquire(type)) return false
        val cost = tokenBucket.costOf(type)
        budget.spent += cost

        val delayMillis = applyRetryAfter(
            backoffMillis(type, attempt - 1, retryConfig, random),
            retryAfterHeader,
            retryConfig.maxBackoffMillis,
        )
        if (clock() + delayMillis > deadlineMillis) {
            // Nothing was retried, so nothing is owed. Charging for a retry the deadline cancelled
            // would open the breaker on evidence that was never gathered.
            tokenBucket.refund(type)
            budget.spent -= cost
            return false
        }
        notify(
            Outcome.RETRY_SCHEDULED, operation, attempt, null, null, delayMillis, attemptStartMillis,
        )
        sleep(delayMillis)
        return true
    }

    /**
     * Issues a signed request and hands the **response body to [consume] as it arrives**, rather
     * than materializing it.
     *
     * ### What this is for, and what it deliberately is not
     *
     * The plan defers streaming bodies to v2, and this is a *partial* advance on that item rather
     * than its delivery. It streams **responses only**: the request body is still a materialized
     * `ByteArray`, so the signature is an ordinary payload hash over bytes we hold and none of the
     * chunked-signing machinery a streaming *request* needs exists. That is enough for AWS's
     * event-stream services — Bedrock's `ConverseStream` sends a small JSON request and answers
     * with a long stream — and is not enough for S3's `GetObject`, which also wants a ceiling, a
     * `Range` interaction and a truncation check. `aws-s3` is deliberately left alone.
     *
     * ### Retry semantics, which are narrower than [callRaw]'s and have to be
     *
     * A stream can be retried right up until the first byte of a **successful** body is handed out,
     * and not afterwards — once [consume] has seen part of the answer there is no way to un-emit it.
     * So:
     *
     * - transport failures before a response arrives retry exactly as in [callRaw], honouring
     *   [safety];
     * - a non-2xx response has its body materialized (an error body is small and bounded) and is
     *   classified and retried exactly as in [callRaw];
     * - a 2xx response is handed to [consume], and **whatever happens inside [consume] is never
     *   retried**. A failure part-way through a stream propagates to the caller with however much
     *   was already emitted still emitted.
     *
     * That last rule is why there is no `validateBody` equivalent here. [callRaw] can offer one
     * because it holds the whole body before deciding; a stream has no such moment.
     *
     * @param consume called once, with a live channel, inside the HTTP client's response scope.
     *   **The channel is only valid inside this call** — Ktor closes the connection when the block
     *   returns, so a [consume] that stashes the channel and returns hands its caller a dead one.
     *   Anything derived from the stream must be fully realized before returning.
     *
     *   **It may run on the HTTP engine's dispatcher, not the caller's.** Ktor 3.x hands the
     *   response block to the engine dispatcher on non-JVM platforms — `Dispatchers.IO` under the
     *   native Curl engine — and Ktor 4 will do so everywhere. A [consume] that feeds a `flow { }`
     *   builder's `emit` from here violates flow context preservation and fails at runtime, on the
     *   deployment target only; hopping back with `withContext` does not help, because *any*
     *   `withContext` between the builder and `emit` is itself a violation. Use `channelFlow { }`
     *   and `send`, which is legal from any context — `converseStream` in `aws-bedrock-runtime` is
     *   the worked example, including how it keeps events that preceded a mid-stream failure.
     * @return whatever [consume] returned.
     */
    public suspend fun <T> callStreaming(
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
        consume: suspend (status: Int, headers: Map<String, String>, body: ByteReadChannel) -> T,
    ): T {
        val invocationId = newInvocationId()
        val deadline = clock() + retryConfig.maxTotalRetryDuration.inWholeMilliseconds
        val budget = RetryBudget()
        var attempt = 0
        var skewCorrectionUsed = false

        while (true) {
            val attemptNumber = attempt + 1
            val attemptStart = clock()

            val signed = signAttempt(
                method, path, query, headers, body, operation, invocationId, attempt,
                payloadHash, signedBodyHeader, doubleUriEncode, normalizeUriPath,
            )

            val outcome: StreamAttempt<T>
            try {
                outcome = sendStreaming(method, path, query, signed.headers, body, consume)
            } catch (consumerFailure: ConsumerFailure) {
                // Caught *before* the classifier, deliberately. The response arrived, the status was
                // 2xx, and part of the body has already been handed to the caller — there is no
                // honest way to replay that, whatever the failure was. GAVE_UP and no terminal
                // event: the call is over, but the attempt has no outcome that is AWS's.
                notify(Outcome.GAVE_UP, operation, attemptNumber, null, null, null, attemptStart)
                throw consumerFailure.failure
            } catch (failure: Throwable) {
                // Cancellation must never reach the classifier — see the matching note in callRaw.
                val transportFailure = transportFailureOrNull(failure) ?: throw failure
                notify(Outcome.TRANSPORT_FAILURE, operation, attemptNumber, null, null, null, attemptStart)
                val mayRetry = when (classifyTransportFailure(transportFailure)) {
                    TransportFailure.NOT_SENT -> true
                    TransportFailure.AMBIGUOUS ->
                        safety == OperationSafety.IDEMPOTENT || retryConfig.retryAmbiguousWrites
                }
                if (!mayRetry) {
                    notify(Outcome.GAVE_UP, operation, attemptNumber, null, null, null, attemptStart)
                    throw transportFailure
                }
                attempt++
                if (!prepareRetry(
                        RetryErrorType.TRANSIENT, attempt, deadline, null, budget, operation, attemptStart,
                    )
                ) {
                    notify(Outcome.GAVE_UP, operation, attemptNumber, null, null, null, attemptStart)
                    throw transportFailure
                }
                continue
            }

            when (outcome) {
                is StreamAttempt.Delivered -> {
                    // "Returned by succeeding", exactly as callRaw accounts for it: retries that
                    // bought a working response give their cost back, and only a call that needed
                    // none at all earns the +1 credit that refills the bucket past what it lent.
                    if (attempt == 0) tokenBucket.onCleanSuccess()
                    else tokenBucket.refundCost(budget.spent)
                    notify(Outcome.SUCCESS, operation, attemptNumber, outcome.status, null, null, attemptStart)
                    return outcome.value
                }

                is StreamAttempt.Failed -> {
                    val response = outcome.response
                    val details = protocol.errorParser.parse(response.status, response.headers, response.body)
                    val exception = toException(details, response)
                    notify(
                        Outcome.SERVICE_ERROR, operation, attemptNumber, response.status,
                        details.code, null, attemptStart,
                    )

                    // One skew correction per call, as in callRaw: a second would mean the first
                    // measurement was wrong, and re-measuring against the same wrong clock does not
                    // improve it. Unlike callRaw this does not take the free immediate retry — the
                    // ordinary backoff path below covers it, and duplicating the fast path here
                    // would be a second place for the "only once" flag to be got wrong.
                    if (!skewCorrectionUsed && shouldCorrectClockSkew(details.code, response)) {
                        skewCorrectionUsed = true
                        serverTimeOffset(response)?.let { clockSkewOffsetMillis.store(it) }
                        notify(
                            Outcome.CLOCK_SKEW_CORRECTED, operation, attemptNumber, response.status,
                            details.code, null, attemptStart,
                        )
                    }

                    val type = classifyRetry(details.code, response.status)
                    if (type == null) {
                        notify(Outcome.GAVE_UP, operation, attemptNumber, response.status, details.code, null, attemptStart)
                        throw exception
                    }
                    attempt++
                    if (!prepareRetry(
                            type, attempt, deadline,
                            response.headers.headerValue("x-amz-retry-after"),
                            budget, operation, attemptStart,
                        )
                    ) {
                        notify(Outcome.GAVE_UP, operation, attemptNumber, response.status, details.code, null, attemptStart)
                        throw exception
                    }
                }
            }
        }
    }

    /** The two things one streaming attempt can produce: a consumed stream, or an error response. */
    private sealed interface StreamAttempt<out T> {
        class Delivered<T>(val value: T, val status: Int) : StreamAttempt<T>
        class Failed(val response: AwsHttpResponse) : StreamAttempt<Nothing>
    }

    /**
     * Sends, and either consumes a 2xx body as a stream or materializes a non-2xx one as an error.
     *
     * The whole method body runs inside Ktor's `execute { }` scope, which is what keeps the
     * connection open for the duration of [consume] and closes it afterwards. Reading the *error*
     * body inside the same scope is deliberate and not merely convenient: the classifier needs the
     * parsed code, and a bounded error body is exactly the case where materializing is right.
     */
    private suspend fun <T> sendStreaming(
        method: String,
        path: String,
        query: List<Pair<String, String>>,
        signedHeaders: List<Pair<String, String>>,
        body: ByteArray,
        consume: suspend (status: Int, headers: Map<String, String>, body: ByteReadChannel) -> T,
    ): StreamAttempt<T> {
        val builder = HttpRequestBuilder()
        builder.method = HttpMethod.parse(method)
        builder.url {
            this.protocol = endpoint.protocol
            this.host = endpoint.host
            this.port = endpoint.port
            encodedPathSegments = path.split('/')
            for ((name, value) in query) {
                encodedParameters.append(sigV4UriEncode(name), sigV4UriEncode(value))
            }
        }

        val outboundPath = builder.url.encodedPathSegments.joinToString("/")
        check(outboundPath == path) {
            "Ktor rewrote the request path after signing: signed '$path', would send " +
                "'$outboundPath'. The signature would not match."
        }

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

        return httpClient.prepareRequest(builder).execute { response ->
            val responseHeaders = buildMap {
                response.headers.forEach { name, values -> put(name.lowercase(), values.joinToString(",")) }
            }
            val status = response.status.value
            if (status in 200..299) {
                // Wrapped on the way out so the retry loop can tell a failure *inside* the caller's
                // stream apart from a socket dying at the same instant. See ConsumerFailure.
                val value = try {
                    consume(status, responseHeaders, response.bodyAsChannel())
                } catch (failure: Throwable) {
                    throw ConsumerFailure(failure)
                }
                StreamAttempt.Delivered(value, status)
            } else {
                StreamAttempt.Failed(AwsHttpResponse(status, responseHeaders, response.readRawBytes()))
            }
        }
    }

    /**
     * Resolves credentials and signs one attempt.
     *
     * Extracted from [callRaw]'s loop so [callStreaming] signs identically rather than nearly so.
     * Every line of it is load-bearing somewhere — the header ordering, the `accept-encoding`, the
     * skew offset applied to the signing instant — and two copies would drift on the first change
     * to any of them.
     *
     * Credentials are resolved **per attempt** rather than per call: a call spanning four attempts
     * and a 20-second cap can outlive the credentials it started with.
     */
    private suspend fun signAttempt(
        method: String,
        path: String,
        query: List<Pair<String, String>>,
        headers: List<Pair<String, String>>,
        body: ByteArray,
        operation: String?,
        invocationId: String,
        attempt: Int,
        payloadHash: PayloadHash,
        signedBodyHeader: SignedBodyHeader,
        doubleUriEncode: Boolean,
        normalizeUriPath: Boolean,
    ): SignedRequest {
        val credentials = credentialsProvider.resolve()

        val requestHeaders = buildList {
            protocol.contentType?.let { add("Content-Type" to it) }
            protocol.targetPrefix?.let { prefix ->
                operation?.let { add("X-Amz-Target" to "$prefix.$it") }
            }
            addAll(headers)
            // After the caller's headers, and only when they carried none: a request that
            // arrived with two User-Agents is worse than one that arrived with the wrong one,
            // and a caller identifying its own application is the case worth deferring to.
            if (headers.none { it.first.equals("user-agent", ignoreCase = true) }) {
                add("user-agent" to AWSKT_USER_AGENT)
            }
            add("amz-sdk-invocation-id" to invocationId)
            add("amz-sdk-request" to "attempt=${attempt + 1}; max=${retryConfig.maxAttempts}")
            // Signing a compressed body we never see would break the payload hash. Added here
            // for every call and nowhere else: a service module that adds its own copy sends
            // (and signs) the header twice, which works only for as long as no engine dedupes.
            add("accept-encoding" to "identity")
        }

        return SigV4.sign(
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
                throw InspectionRefusal(refusal, response.status.value)
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

    /**
     * How far AWS's clock is ahead of ours, from the response's `Date` header.
     *
     * **Measured after the body was downloaded, so it includes the transfer time**, and is therefore
     * an estimate skewed late by however long the response took to arrive — the header records when
     * AWS started writing, and [clock] is read here, after the last byte landed. That is deliberate
     * and adequate for what it is used for: SigV4 tolerates five minutes of skew, and the triggers
     * in [shouldCorrectClockSkew] only act on a disagreement of four minutes or more, so a few
     * seconds of download does not decide anything. Documented so nobody reads a precision claim
     * into it — and so nobody "fixes" it by timestamping mid-flight, which would buy accuracy this
     * has no use for at the cost of threading a clock read through [send].
     */
    private fun serverTimeOffset(response: AwsHttpResponse): Long? {
        val serverMillis = parseHttpDateOrNull(response.headers.headerValue("date") ?: return null)
            ?: return null
        return serverMillis - clock()
    }

    /**
     * `amz-sdk-invocation-id`: one id shared by every attempt of one call, so AWS can tie a request
     * and its retries together.
     *
     * UUID-shaped, which is what every AWS SDK puts in this header. It used to be 16 hex digits —
     * unique enough among one process's calls, but a different shape from the value AWS-side
     * tooling is reading, and a second home-grown generator next to `aws-dynamodb`'s.
     */
    private fun newInvocationId(): String = randomUuidString()

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
 *
 * ### Curl, which is the only engine native has
 *
 * The name and message patterns below are JVM-shaped, and Kotlin/Native never sees a single one of
 * them: `ktor-client-curl` reports every failed transfer as a plain `IllegalStateException` whose
 * message is `"Connection failed for request: $request. Reason: $errorMessage"`
 * (`CurlMultiApiHandler.kt:353`, ktor 3.5.2), where `errorMessage` is
 * `"${curl_easy_strerror(code)} (${code.name})"` (`CurlAdapters.kt:61-62`). No typed exception, no
 * recognisable class name — so before [CURL_NOT_SENT_MARKERS] existed, a DNS lookup that failed
 * before a packet left the machine came back AMBIGUOUS, and a `NOT_IDEMPOTENT` write refused to
 * retry it.
 *
 * The markers match the `CURLE_…` **enum name** first, because ktor appends it verbatim and curl's
 * `strerror` prose is not stable across curl releases: `CURLE_COULDNT_RESOLVE_HOST` reads
 * "Couldn't resolve host name" under curl 7.88 and "Could not resolve hostname" under curl 8.11.
 * Both spellings are listed too, for anything that surfaces the prose without the code.
 *
 * What must **not** be matched is the enclosing sentence. "Connection failed for request:" is the
 * *fallback* branch for every unhandled `CURLcode`, `CURLE_SEND_ERROR` and `CURLE_RECV_ERROR`
 * included — the two shapes that most certainly did reach AWS. Only the specific codes inside it
 * are safe, for the same reason the request timeout above is not.
 *
 * One inherited subtlety: ktor maps `CURLE_OPERATION_TIMEDOUT` to `ConnectTimeoutException`
 * (`CurlMultiApiHandler.kt:332-334`), which the name check above already answers NOT_SENT. That is
 * sound *only* because the engine sets `CURLOPT_CONNECTTIMEOUT_MS` and never `CURLOPT_TIMEOUT_MS`
 * (`CurlMultiApiHandler.kt:100-105`), so curl has no whole-operation deadline that could expire
 * mid-flight. Should a ktor release start setting one, that mapping becomes a post-send timeout
 * wearing a pre-send name, and this classifier would need to stop trusting it.
 */
internal fun classifyTransportFailure(failure: Throwable): TransportFailure {
    var current: Throwable? = failure
    var depth = 0
    while (current != null && depth < 8) {
        // Asked first, and it is the only check here that reasons about what a throwable *is*
        // rather than what it is called. A JVM engine's `java.net.ConnectException` carries no
        // message at all in the common case, so the string matching below cannot see it, and its
        // class name is only stable until someone subclasses it.
        platformTransportFailureHint(current)?.let { return it }

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
            "nodename nor servname" in message ||
            CURL_NOT_SENT_MARKERS.any { it in message }
        if (notSent) return TransportFailure.NOT_SENT
        current = current.cause
        depth++
    }
    return TransportFailure.AMBIGUOUS
}

/**
 * Fragments that appear only in a curl failure raised **before the request bytes were written**:
 * name resolution, TCP connect, and the TLS handshake. Matched against an already-lowercased
 * message.
 *
 * Every entry is quoted from a source, because guessing here is how a non-idempotent write gets
 * replayed. Verified against ktor 3.5.2 (the version pinned in `libs.versions.toml`) and curl's own
 * `strerror` table:
 *
 * - `curle_…` — ktor renders `"${curl_easy_strerror(this)?.toKString()} ($name)"`, where `name` is
 *   its own `when` over the `CURLcode` constants — `CurlAdapters.kt:61-62` and `:64-` for the
 *   table. The enum name is therefore in the message verbatim, and unlike the prose it does not
 *   drift between curl releases.
 * - `tls verification failed for request` — `CurlMultiApiHandler.kt:338-344`, the dedicated branch
 *   for `CURLE_PEER_FAILED_VERIFICATION`.
 * - `proxy handshake error for request` — `CurlMultiApiHandler.kt:346-350`. Listed separately
 *   because that branch interpolates `$proxyCode` (a `CURLproxycode`) instead of `$errorMessage`,
 *   so no `curle_…` marker can catch it. A proxy handshake that fails has not forwarded anything.
 * - the prose forms — curl `lib/strerror.c`: `curl-7_88_1` lines 76-83 / 211-218 and `curl-8_11_1`
 *   lines 76-83 / 205-212, which is where the "Couldn't"/"Could not" split comes from.
 * - `ssl certificate problem` — curl `lib/vtls/openssl.c:4215` (`curl-8_11_1`), a `failf` that
 *   lands in `CURLOPT_ERRORBUFFER`. ktor 3.5.2 does not set that option, so this one is not
 *   reachable through the current engine; it is here as cover for a ktor release that starts
 *   wiring the buffer, and it is safe either way because certificate verification is strictly a
 *   handshake-phase event.
 *
 * ### Deliberately absent
 *
 * The same rule as the request timeout in [classifyTransportFailure]: anything that *can* fire once
 * bytes are on the wire stays AMBIGUOUS, however clearly it names a network fault.
 * `CURLE_OPERATION_TIMEDOUT` ("timeout was reached"), `CURLE_SEND_ERROR` ("failed sending data to
 * the peer"), `CURLE_RECV_ERROR` ("failure when receiving data from the peer"),
 * `CURLE_PARTIAL_FILE` ("transfer closed with outstanding read data remaining") and
 * `CURLE_GOT_NOTHING` are all reachable *after* AWS has seen and applied the request. Adding any of
 * them would make a timed-out `UpdateItem` replayable, which is the exact bug this whole
 * classification exists to prevent.
 */
private val CURL_NOT_SENT_MARKERS = listOf(
    // DNS: the address was never resolved, so nothing was ever addressed.
    "curle_couldnt_resolve_host",
    "curle_couldnt_resolve_proxy",
    "could not resolve host",
    "couldn't resolve host",
    "could not resolve proxy",
    "couldn't resolve proxy",
    // Connect: no TCP (or QUIC) session was ever established.
    "curle_couldnt_connect",
    "curle_quic_connect_error",
    "could not connect to server",
    "couldn't connect to server",
    // TLS handshake: a session exists, but the request has not been written to it — the handshake
    // completes before the first HTTP byte goes out.
    "curle_ssl_connect_error",
    "curle_peer_failed_verification",
    "curle_ssl_certproblem",
    "curle_ssl_cipher",
    "ssl connect error",
    "ssl peer certificate",
    "problem with the local ssl certificate",
    "ssl certificate problem",
    "tls verification failed for request",
    "proxy handshake error for request",
)

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
