package com.steamstreet.awskt.lambda

import com.steamstreet.awskt.core.AwsCallObserver
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsHttpTimeouts
import com.steamstreet.awskt.core.AwsProtocol
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.awsHttpClient
import com.steamstreet.awskt.core.awsJson
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.core.resolveRegion
import com.steamstreet.awskt.signing.sigV4UriEncode
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlin.io.encoding.Base64

/**
 * Lambda's **restJson1** dialect. The endpoint prefix and the signing name are both `lambda`.
 */
public val LAMBDA_PROTOCOL: AwsProtocol = AwsProtocol.restJson1(endpointPrefix = "lambda")

/**
 * A Lambda client covering the **invoke plane**, and nothing else.
 *
 * ### What "the invoke plane" means, and why the rest is absent
 *
 * `Invoke` and `InvokeWithResponseStream` are the two operations a *running program* performs
 * against Lambda. Everything else the service offers — `CreateFunction`, `UpdateFunctionCode`,
 * `PublishVersion`, aliases, event source mappings, layers, concurrency, tagging — is the resource
 * plane: it provisions infrastructure, it is done by CloudFormation, CDK, Terraform or the CLI, and
 * a function that calls it at runtime is almost always a design mistake. Shipping it here would
 * roughly quintuple the module for capability nothing needs.
 *
 * `InvokeAsync` is also absent. It is AWS-deprecated, superseded by `InvocationType=Event` on
 * `Invoke`, and there is no reason to add a second spelling of something this module already does.
 *
 * ### The five things worth knowing before you use it
 *
 * 1. **A function that threw still answers `200`.** Read [InvokeResponse.functionError] — or use
 *    [InvokeResponse.orThrow] — before you read the payload. This is the mistake everybody makes
 *    once, and it surfaces far from where it happened.
 * 2. **Replaying an invocation runs the function again.** [InvokeRequest.safety] therefore defaults
 *    to `NOT_IDEMPOTENT`, so an ambiguous transport failure is surfaced rather than retried.
 * 3. **The HTTP request lives as long as the invocation does.** A synchronous invoke of a
 *    five-minute function is a five-minute HTTP response, and the library's ordinary 30-second
 *    timeouts would cut it off — see [LambdaConfig.httpTimeouts], which this module defaults
 *    differently for exactly that reason.
 * 4. **`InvocationType.Event` returning `202` means "accepted", not "ran".** Everything after that
 *    — including Lambda's own two asynchronous retries — happens where this client cannot see it.
 * 5. **Invoking a function from inside a function is a chain Lambda watches.** A cycle is stopped
 *    with [RecursiveInvocationException] rather than billed forever.
 *
 * ### Extending it
 *
 * [client] is public and neither operation has privileged access to it. A resource-plane operation
 * — should you genuinely need one — is an extension function with identical signing, retry and
 * error handling:
 *
 * ```kotlin
 * @Serializable
 * data class GetFunctionConfigurationResponse(
 *     @SerialName("Timeout") val timeout: Int? = null,
 *     @SerialName("MemorySize") val memorySize: Int? = null,
 * )
 *
 * suspend fun Lambda.getFunctionConfiguration(name: String): GetFunctionConfigurationResponse =
 *     client.callRestJsonNoBody(
 *         method = "GET",
 *         path = "/2015-03-31/functions/${sigV4UriEncode(name)}/configuration",
 *         responseSerializer = GetFunctionConfigurationResponse.serializer(),
 *         operation = "GetFunctionConfiguration",
 *     )
 * ```
 *
 * **Encode interpolated path segments with `sigV4UriEncode`**, as that example does: `callRaw` and
 * the `callRestJson*` helpers sign and send the path byte-for-byte as given, so a raw name is a
 * signature mismatch at best.
 */
public interface Lambda : AutoCloseable {
    /** The signed transport. Public because it is the extension seam — see the interface KDoc. */
    public val client: AwsServiceClient

    /**
     * Invokes a function. `POST /2015-03-31/functions/{FunctionName}/invocations`.
     *
     * The invocation type decides everything about what comes back: a payload and a `200` for
     * [InvocationType.REQUEST_RESPONSE], an empty `202` for [InvocationType.EVENT], an empty `204`
     * for [InvocationType.DRY_RUN].
     *
     * **This does not throw when the function fails.** See [InvokeResponse] and [orThrow].
     *
     * @throws LambdaPayloadTooLargeException before sending, when the payload exceeds the ceiling
     *   for its invocation type, or the encoded client context exceeds
     *   [MAX_CLIENT_CONTEXT_BYTES].
     * @throws ResourceNotFoundException if the function, version or alias does not exist.
     * @throws TooManyRequestsException if the invocation was throttled and the retries ran out.
     */
    public suspend fun invoke(request: InvokeRequest): InvokeResponse

    /**
     * Invokes a function that streams its response.
     * `POST /2021-11-15/functions/{FunctionName}/response-streaming-invocations`.
     *
     * Returns a **cold** flow: nothing is sent until it is collected, and the HTTP response stays
     * open for as long as collection continues. Two consequences that matter:
     *
     * - **Collecting it twice invokes the function twice**, with everything that implies for a
     *   handler that is not idempotent. Collect once; use `onEach` to tee.
     * - **The connection is bound to the collection.** Abandoning the flow part-way — a `take(5)`,
     *   an exception in the collector, a cancelled scope — closes the connection. The function
     *   keeps running, and you are still billed for it.
     *
     * The function must be configured with `InvokeMode = RESPONSE_STREAM`; a buffered function
     * answers a single chunk carrying the whole payload, which works but buys nothing.
     *
     * **A failure part-way through the function's own execution arrives as
     * [InvokeStreamEvent.Complete] with an `errorCode`, not as a thrown exception** — the status
     * was `200` and part of the answer has already been delivered. [payloadChunks] converts that
     * into a [FunctionErrorException] for callers who want one.
     *
     * @param request as for [invoke], except that [InvocationType.EVENT] is meaningless here —
     *   there is no stream to read from an invocation that has not run — and is rejected. That
     *   rejection, and the payload-size check, happen when the flow is **built** rather than when
     *   it is collected: both are programmer errors rather than call outcomes, and a flow that
     *   constructs happily and then fails on collection hides them behind a `collect`.
     */
    public fun invokeWithResponseStream(request: InvokeRequest): Flow<InvokeStreamEvent>
}

/** Configuration for [Lambda]. */
public class LambdaConfig {
    public var region: String? = null
    public var endpointUrl: String? = null
    public var credentialsProvider: AwsCredentialsProvider? = null

    /**
     * A client to send on, instead of one built here.
     *
     * Supplying one **bypasses [caInfo] and [httpTimeouts]**: those are arguments to the client this
     * factory would have built, and a client the caller already owns is configured by the caller.
     * For this module that is worth checking twice — a shared client carrying the library's
     * ordinary 30-second timeouts truncates any synchronous invocation slower than that.
     */
    public var httpClient: HttpClient? = null

    /**
     * Retry pacing. **Worth tuning down rather than up.**
     *
     * A retried invocation is a second full execution, billed in full and — unless the handler is
     * idempotent — applied twice. The library default of four attempts is right for a `GetItem`.
     * Note that [InvokeRequest.safety] already prevents a replay after an *ambiguous* failure; this
     * governs the unambiguous ones, where the service answered `429` or `500`.
     */
    public var retryConfig: RetryConfig = RetryConfig()

    /** CA bundle for the native Curl engine. Null uses the system trust store. */
    public var caInfo: String? = null

    /**
     * Per-attempt time limits. **Deliberately far longer than every other module's default, and the
     * setting most likely to need changing.**
     *
     * A synchronous invoke holds the HTTP request open for the entire execution of the function it
     * called, and the socket sits completely idle for that whole time — no bytes flow until the
     * handler returns. Against the library's ordinary 30-second defaults, invoking anything slower
     * than 30 seconds dies part-way with a timeout that looks like a network fault, and (because
     * [InvokeRequest.safety] defaults to `NOT_IDEMPOTENT`) is correctly *not* retried. AWS's own
     * guidance for the official SDKs is the same: set the socket timeout above the invoked
     * function's timeout.
     *
     * So the defaults here bound an attempt at Lambda's own hard ceiling — 15 minutes plus a small
     * margin — which is the only value that is safe without knowing which function is being called.
     *
     * **Lower it to just above the target function's timeout.** The 15-minute default is a
     * correctness floor, not a recommendation: it means a genuinely black-holed connection takes 15
     * minutes to surface, and a caller invoking a 3-second function should say so:
     *
     * ```kotlin
     * val lambda = Lambda {
     *     httpTimeouts = AwsHttpTimeouts(socketTimeoutMillis = 10_000, requestTimeoutMillis = 10_000)
     * }
     * ```
     *
     * The connect timeout is left at the library default: TCP establishment to a regional endpoint
     * has nothing to do with how long the function runs.
     *
     * Note that [RetryConfig.maxTotalRetryDuration] (25 s by default) covers the whole call, so a
     * long invocation exhausts it and its failures are surfaced rather than retried — which is the
     * intended behaviour for something this expensive to replay.
     */
    public var httpTimeouts: AwsHttpTimeouts = AwsHttpTimeouts(
        socketTimeoutMillis = 905_000,
        requestTimeoutMillis = 905_000,
    )

    /**
     * Notified of every attempt, retry decision and give-up. Null means no instrumentation.
     *
     * Worth wiring up here for the same reason as `aws-bedrock-runtime`: every retry is a second
     * billed execution of somebody else's function, and this is the only place that is visible.
     */
    public var observer: AwsCallObserver? = null
}

/** Builds a Lambda invoke client. */
public fun Lambda(configure: LambdaConfig.() -> Unit = {}): Lambda {
    val config = LambdaConfig().apply(configure)
    val region = resolveRegion(config.region)
    // One binding for the effective client, handed to both the transport and the close path — see
    // the same note in `DynamoDb()`, where splitting the two leaked the client we had just built.
    val httpClient = config.httpClient ?: awsHttpClient(config.caInfo, config.httpTimeouts)
    return DefaultLambda(
        client = AwsServiceClient(
            httpClient = httpClient,
            credentialsProvider = config.credentialsProvider ?: defaultCredentialsProvider(),
            endpoint = resolveEndpoint("lambda", region, config.endpointUrl),
            region = region,
            protocol = LAMBDA_PROTOCOL,
            retryConfig = config.retryConfig,
            observer = config.observer,
        ),
        ownsHttpClient = config.httpClient == null,
        httpClient = httpClient,
    )
}

internal class DefaultLambda(
    override val client: AwsServiceClient,
    private val ownsHttpClient: Boolean = false,
    private val httpClient: HttpClient? = null,
) : Lambda {

    override suspend fun invoke(request: InvokeRequest): InvokeResponse = mapErrors {
        val payload = request.payload ?: EMPTY_PAYLOAD
        checkPayloadSize(payload, request.invocationType)

        val response = client.callRaw(
            method = "POST",
            path = invocationPath(request.functionName),
            query = request.qualifier?.let { listOf("Qualifier" to it) } ?: emptyList(),
            headers = invokeHeaders(request),
            body = payload,
            operation = "Invoke",
            safety = request.safety,
        )

        InvokeResponse(
            statusCode = response.status,
            payload = response.body,
            // Lowercase because `aws-core` lowercases every response header name as it collects
            // them — see `AwsServiceClient.send`.
            functionError = response.headers["x-amz-function-error"],
            executedVersion = response.headers["x-amz-executed-version"],
            logResult = response.headers["x-amz-log-result"],
        )
    }

    override fun invokeWithResponseStream(request: InvokeRequest): Flow<InvokeStreamEvent> {
        require(request.invocationType != InvocationType.EVENT) {
            "InvocationType.EVENT cannot stream a response: an invocation that has only been " +
                "queued has produced nothing to read. Use Lambda.invoke for a queued invocation."
        }
        val payload = request.payload ?: EMPTY_PAYLOAD
        checkPayloadSize(payload, request.invocationType)
        // Headers built here rather than inside the flow so that an oversized client context is
        // refused alongside the two checks above, instead of surfacing only once somebody collects.
        return responseStream(client, request, payload, invokeHeaders(request))
    }

    override fun close() {
        if (ownsHttpClient) httpClient?.close()
    }
}

/** Shared by both operations, so the two cannot drift in how they spell a header. */
internal fun invokeHeaders(request: InvokeRequest): List<Pair<String, String>> = buildList {
    add("X-Amz-Invocation-Type" to request.invocationType.wire)
    if (request.logType != LogType.NONE) add("X-Amz-Log-Type" to request.logType.wire)
    request.clientContextJson?.let { add("X-Amz-Client-Context" to encodeClientContext(it)) }
}

/**
 * Base64-encodes the client context and bounds it.
 *
 * The encoding is this client's, not the caller's — see [InvokeRequest.clientContextJson] for why
 * the parameter is named the way it is. The bound is checked *after* encoding because that is what
 * Lambda measures, and it is checked at all because the service's own answer to an oversized
 * context is a `ValidationException` whose message does not name the header.
 */
private fun encodeClientContext(json: String): String {
    val encoded = Base64.Default.encode(json.encodeToByteArray())
    if (encoded.length > MAX_CLIENT_CONTEXT_BYTES) {
        throw LambdaPayloadTooLargeException(
            "clientContextJson is ${encoded.length} bytes once base64-encoded, over Lambda's " +
                "$MAX_CLIENT_CONTEXT_BYTES-byte X-Amz-Client-Context limit.",
        )
    }
    return encoded
}

/**
 * Refuses an oversized payload before it is uploaded.
 *
 * The service answers [RequestTooLargeException] for the same mistake, but only after the whole
 * body has crossed the network — 6 MB of upload to be told it was 6 MB — and a caller that batches
 * records into an event discovers the ceiling in production rather than at the call site.
 *
 * The two limits differ by a factor of 24, and which one applies depends on [InvocationType], which
 * is why this cannot be a single constant checked in one place.
 */
private fun checkPayloadSize(payload: ByteArray, invocationType: InvocationType) {
    val limit = when (invocationType) {
        InvocationType.EVENT -> MAX_EVENT_PAYLOAD_BYTES
        InvocationType.REQUEST_RESPONSE, InvocationType.DRY_RUN -> MAX_SYNC_PAYLOAD_BYTES
    }
    if (payload.size > limit) {
        throw LambdaPayloadTooLargeException(
            "Payload is ${payload.size} bytes, over the $limit-byte limit for " +
                "InvocationType.${invocationType.name}. Put the data in S3 and send a reference.",
        )
    }
}

internal val EMPTY_PAYLOAD: ByteArray = ByteArray(0)

/**
 * Builds `/2015-03-31/functions/{FunctionName}/invocations` with the name percent-encoded.
 *
 * The encoding is not ceremonial. A function may be addressed by ARN
 * (`arn:aws:lambda:us-west-2:123456789012:function:worker`) or by name-with-alias
 * (`worker:PROD`), both of which carry `:` — which occupies one path segment and reaches Lambda as
 * `%3A`, decoded before the name is resolved. This is the same treatment `aws-bedrock-runtime`
 * gives an inference-profile ARN, and it is the same encoder the signature is computed with, so the
 * sent path and the signed path cannot disagree.
 */
private fun invocationPath(functionName: String): String =
    "/2015-03-31/functions/${sigV4UriEncode(functionName)}/invocations"

/** As [invocationPath], for the streaming operation — a different date and a different suffix. */
internal fun responseStreamingPath(functionName: String): String =
    "/2021-11-15/functions/${sigV4UriEncode(functionName)}/response-streaming-invocations"

// -- Conveniences --------------------------------------------------------------------------------

/**
 * Returns this response, or throws [FunctionErrorException] if the function failed.
 *
 * The one-call form of the check described on [InvokeResponse]. Parsing of the error payload is
 * best-effort — a custom runtime may answer with anything — so [FunctionErrorException.payload]
 * always carries the bytes even when [FunctionErrorException.errorType] is null.
 */
public fun InvokeResponse.orThrow(): InvokeResponse {
    val error = functionError ?: return this
    val parsed = runCatching {
        awsJson.decodeFromString(FunctionErrorPayload.serializer(), payload.decodeToString())
    }.getOrNull()
    throw FunctionErrorException(error, parsed?.errorType, parsed?.errorMessage, payload)
}

/**
 * The common case: invoke synchronously, send and receive text, and fail loudly if the function did.
 *
 * Equivalent to building an [InvokeRequest], calling [Lambda.invoke], applying [orThrow] and
 * reading [InvokeResponse.payloadText] — which is what most call sites want and what most of them
 * write incorrectly, by skipping the `orThrow`.
 *
 * Reach for [Lambda.invoke] as soon as you need the executed version, the log tail, a non-UTF-8
 * payload, or the error payload as data rather than as an exception.
 *
 * @param payload the event, as text. Null sends an empty body, which a function receives as `{}`.
 * @param safety see [InvokeRequest.safety]. Left at `NOT_IDEMPOTENT`, as there.
 * @throws FunctionErrorException if the function ran and failed.
 */
public suspend fun Lambda.invokeFunction(
    functionName: String,
    payload: String? = null,
    qualifier: String? = null,
    safety: OperationSafety = OperationSafety.NOT_IDEMPOTENT,
): String = invoke(
    InvokeRequest(
        functionName = functionName,
        payload = payload?.encodeToByteArray(),
        invocationType = InvocationType.REQUEST_RESPONSE,
        qualifier = qualifier,
        safety = safety,
    ),
).orThrow().payloadText

/**
 * Queues an asynchronous invocation and returns once Lambda has accepted it.
 *
 * **Returning means "accepted", not "ran"** — see [InvocationType.EVENT], which also covers what
 * Lambda does on its own when the queued invocation fails. Nothing this function can report tells
 * the caller whether the handler ever succeeded; that answer lives in the function's logs, its
 * dead-letter target or its on-failure destination.
 *
 * The payload ceiling here is 256 KB rather than 6 MB.
 */
public suspend fun Lambda.invokeEvent(
    functionName: String,
    payload: String? = null,
    qualifier: String? = null,
) {
    invoke(
        InvokeRequest(
            functionName = functionName,
            payload = payload?.encodeToByteArray(),
            invocationType = InvocationType.EVENT,
            qualifier = qualifier,
        ),
    )
}

/**
 * Validates the function, the qualifier and the caller's permissions without running anything.
 *
 * The start-up check worth having on a function that invokes another one: an IAM policy missing
 * `lambda:InvokeFunction` otherwise surfaces on the first real request, in production, at whatever
 * hour that happens to be. It proves nothing about whether the target *works*.
 *
 * @throws ResourceNotFoundException if the function, version or alias does not exist.
 */
public suspend fun Lambda.dryRun(functionName: String, qualifier: String? = null) {
    invoke(
        InvokeRequest(
            functionName = functionName,
            invocationType = InvocationType.DRY_RUN,
            qualifier = qualifier,
            // Nothing runs, so nothing can be applied twice: a replay is free and produces the same
            // answer. The one invocation type for which IDEMPOTENT is the honest value.
            safety = OperationSafety.IDEMPOTENT,
        ),
    )
}
