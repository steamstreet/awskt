package com.steamstreet.awskt.bedrock

import com.steamstreet.awskt.core.AwsCallObserver
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsHttpTimeouts
import com.steamstreet.awskt.core.AwsProtocol
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.awsHttpClient
import com.steamstreet.awskt.core.awsEventStream
import com.steamstreet.awskt.core.callRestJson
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.core.resolveRegion
import com.steamstreet.awskt.signing.sigV4UriEncode
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Bedrock Runtime's **restJson1** dialect.
 *
 * The endpoint prefix is `bedrock-runtime` and the **signing name is `bedrock`** — they differ, so
 * both are named explicitly. Signing against `bedrock-runtime` produces a `SignatureDoesNotMatch`
 * on every call, and the error says nothing about which of the two is wrong.
 */
public val BEDROCK_RUNTIME_PROTOCOL: AwsProtocol =
    AwsProtocol.restJson1(endpointPrefix = "bedrock-runtime", signingName = "bedrock")

/**
 * A Bedrock Runtime client covering the **Converse** APIs.
 *
 * `Converse` and `ConverseStream`: the model-independent conversation API. `InvokeModel` and
 * `InvokeModelWithResponseStream` are out of scope, and the reason is not effort — they take
 * whatever JSON the chosen model's provider defined, so a typed client for them would be a typed
 * wrapper around an untyped blob, and switching models would mean rewriting the request. Converse
 * is the API a portable client can actually be written against. Both are reachable through the
 * extension seam if a provider-specific body is genuinely needed.
 *
 * ### Three things worth knowing before you use it
 *
 * 1. **Branch on [ConverseResponse.stopReason] before reading the text.**
 *    [StopReason.TOOL_USE] means the reply is a request for tool calls rather than an answer, and
 *    [StopReason.MAX_TOKENS] means the text stops mid-thought.
 * 2. **Model access is granted per model, per account, per region.** An IAM role with full
 *    `bedrock:InvokeModel` still gets [AccessDeniedException] until access is requested in the
 *    console — see that type.
 * 3. **Timeouts here are unlike anywhere else in this library.** A long generation legitimately
 *    takes minutes; see [BedrockRuntimeConfig.httpTimeouts].
 *
 * ### Extending it
 *
 * [client] is public and neither operation has privileged access to it:
 *
 * ```kotlin
 * suspend fun BedrockRuntime.invokeModel(modelId: String, body: JsonElement): JsonElement =
 *     client.callRestJson(
 *         method = "POST",
 *         path = "/model/${sigV4UriEncode(modelId)}/invoke",
 *         request = body,
 *         requestSerializer = JsonElement.serializer(),
 *         responseSerializer = JsonElement.serializer(),
 *         operation = "InvokeModel",
 *         safety = OperationSafety.IDEMPOTENT,
 *     )
 * ```
 *
 * **Encode the model id with `sigV4UriEncode`**, as that example does — see [ConverseRequest.modelId].
 */
public interface BedrockRuntime : AutoCloseable {
    /** The signed transport. Public because it is the extension seam — see the interface KDoc. */
    public val client: AwsServiceClient

    /**
     * One conversational turn, answered in full.
     *
     * `IDEMPOTENT`, with a caveat worth stating because it is unusual: replaying this after an
     * ambiguous transport failure is *safe* — the model has no side effects and nothing is stored
     * — but it produces a **different answer**, since generation is sampled. That is the same
     * shape of reasoning as `aws-kms`'s `GenerateDataKey`: there is no end state to double-apply,
     * and the first answer is exactly what was lost, so a second one is not a discrepancy the
     * caller can observe. It does cost twice, which is the one real consequence and the reason
     * [BedrockRuntimeConfig.retryConfig] is worth tuning down for expensive models.
     */
    public suspend fun converse(request: ConverseRequest): ConverseResponse

    /**
     * One conversational turn, streamed.
     *
     * Returns a **cold** flow: nothing is sent until it is collected, and the HTTP response stays
     * open for as long as collection continues. Two consequences that matter:
     *
     * - **Collecting it twice issues two requests** and produces two different answers. Collect
     *   once; use `onEach` to tee.
     * - **The connection is bound to the collection.** Abandoning the flow part-way — a `take(5)`,
     *   an exception in the collector, a cancelled scope — closes the connection, which is the
     *   correct behaviour and does *not* stop Bedrock having charged for the tokens it generated.
     *
     * See [textDeltas] for the simplest consumption and [accumulate] for reassembling the whole
     * reply, including the token usage that arrives after the stop event.
     *
     * @throws ModelStreamErrorException mid-collection if the model fails part-way through. The
     *   HTTP status was 200 and part of the answer has already been delivered — see that type.
     */
    public fun converseStream(request: ConverseRequest): Flow<ConverseStreamEvent>
}

/** Configuration for [BedrockRuntime]. */
public class BedrockRuntimeConfig {
    public var region: String? = null
    public var endpointUrl: String? = null
    public var credentialsProvider: AwsCredentialsProvider? = null

    /**
     * A client to send on, instead of one built here.
     *
     * Supplying one **bypasses [caInfo] and [httpTimeouts]**. For this module that is worth
     * checking twice: a caller-supplied client with the library's ordinary 30-second timeouts will
     * cut off any generation longer than a short paragraph.
     */
    public var httpClient: HttpClient? = null

    /**
     * Retry pacing. **Worth tuning down rather than up for expensive models.**
     *
     * A retried `Converse` is a second full generation, billed in full. The library default of four
     * attempts is right for a `GetItem` and is a 4× bill ceiling here. A caller using a large model
     * under throttling pressure generally wants fewer attempts and a queue, not more attempts.
     */
    public var retryConfig: RetryConfig = RetryConfig()

    /** CA bundle for the native Curl engine. Null uses the system trust store. */
    public var caInfo: String? = null

    /**
     * Per-attempt time limits. **Deliberately far longer than every other module's default.**
     *
     * The library default is a 30-second socket and request timeout, which suits a service that
     * answers as fast as it can. A language model does not: a long generation legitimately takes
     * minutes, and during a *stream* the socket sits idle between tokens whenever the model pauses.
     * Against the 30-second default, a long `Converse` dies part-way with a timeout that looks like
     * a network fault, and a stream dies mid-answer.
     *
     * The socket timeout is the one that matters for streaming — it bounds the gap *between* bytes,
     * not the whole response — so it is set generously rather than disabled: a genuinely hung
     * connection should still fail rather than pin a Lambda until its own timeout.
     *
     * **These can still be shorter than a Lambda's own timeout and usually should be.** A 15-minute
     * Lambda that spends 14 of them waiting on one generation has no time left to handle the
     * answer.
     */
    public var httpTimeouts: AwsHttpTimeouts = AwsHttpTimeouts(
        connectTimeoutMillis = 5_000,
        socketTimeoutMillis = 120_000,
        requestTimeoutMillis = 600_000,
    )

    /**
     * Notified of every attempt, retry decision and give-up. Null means no instrumentation.
     *
     * Worth wiring up here more than anywhere else in this library: every retry is a second billed
     * generation, and this is the only place that fact is visible before the invoice.
     */
    public var observer: AwsCallObserver? = null
}

/** Builds a Bedrock Runtime client. */
public fun BedrockRuntime(configure: BedrockRuntimeConfig.() -> Unit = {}): BedrockRuntime {
    val config = BedrockRuntimeConfig().apply(configure)
    val region = resolveRegion(config.region)
    // One binding for the effective client, handed to both the transport and the close path — see
    // the same note in `DynamoDb()`, where splitting the two leaked the client we had just built.
    val httpClient = config.httpClient ?: awsHttpClient(config.caInfo, config.httpTimeouts)
    return DefaultBedrockRuntime(
        client = AwsServiceClient(
            httpClient = httpClient,
            credentialsProvider = config.credentialsProvider ?: defaultCredentialsProvider(),
            endpoint = resolveEndpoint("bedrock-runtime", region, config.endpointUrl),
            region = region,
            protocol = BEDROCK_RUNTIME_PROTOCOL,
            retryConfig = config.retryConfig,
            observer = config.observer,
        ),
        ownsHttpClient = config.httpClient == null,
        httpClient = httpClient,
    )
}

internal class DefaultBedrockRuntime(
    override val client: AwsServiceClient,
    private val ownsHttpClient: Boolean = false,
    private val httpClient: HttpClient? = null,
) : BedrockRuntime {

    override suspend fun converse(request: ConverseRequest): ConverseResponse = mapErrors {
        client.callRestJson(
            method = "POST",
            path = modelPath(request.modelId, "converse"),
            request = request.toBody(),
            requestSerializer = ConverseBody.serializer(),
            responseSerializer = ConverseResponse.serializer(),
            operation = "Converse",
            safety = OperationSafety.IDEMPOTENT,
        )
    }

    override fun converseStream(request: ConverseRequest): Flow<ConverseStreamEvent> = channelFlow {
        val body = com.steamstreet.awskt.core.awsJson
            .encodeToString(ConverseBody.serializer(), request.toBody())
            .encodeToByteArray()

        // `channelFlow` + `send`, not `flow` + `emit`, and the difference is load-bearing. Ktor 3.x
        // runs the response block on the *engine's* dispatcher on non-JVM platforms — under the
        // native Curl engine the lambda below is on `Dispatchers.IO` while the collector is wherever
        // it was — and `emit` from there violates flow context preservation. The first deployed
        // Graviton smoke failed with exactly that, and only there: JVM Ktor keeps the caller's
        // dispatcher. `send` is legal from any context.
        //
        // The try/catch is what preserves "events before a mid-stream failure are still delivered".
        // A `channelFlow` block that *throws* cancels the channel and discards anything buffered in
        // it; one that `close(cause)`s and returns lets the collector drain the buffer and then
        // throws the cause. Cancellation is rethrown, because it means the collector went away and
        // there is nobody to close for.
        try {
            mapErrors {
                client.callStreaming(
                    method = "POST",
                    path = modelPath(request.modelId, "converse-stream"),
                    body = body,
                    operation = "ConverseStream",
                    safety = OperationSafety.IDEMPOTENT,
                ) { _, _, channel ->
                    // Collected inside `callStreaming`'s scope, which is what keeps the connection
                    // open for the duration.
                    channel.awsEventStream().collect { frame -> send(frame.toConverseEvent()) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            close(failure)
        }
    }

    override fun close() {
        if (ownsHttpClient) httpClient?.close()
    }
}

/**
 * Builds `/model/{modelId}/{operation}` with the model id percent-encoded.
 *
 * **The encoding is not ceremonial here, unlike in `aws-scheduler`.** A Bedrock model id is
 * frequently an ARN — `arn:aws:bedrock:us-east-1:123456789012:inference-profile/us.anthropic.…` —
 * which contains both `:` and `/`. It occupies **one path segment**, so the `/` inside it must
 * become `%2F`; left raw it would split the path and address an operation that does not exist.
 * `sigV4UriEncode` with `encodeSlash = true` is exactly that, and it is the same encoder the
 * signature is computed with, so the sent path and the signed path cannot disagree.
 */
private fun modelPath(modelId: String, operation: String): String =
    "/model/${sigV4UriEncode(modelId)}/$operation"

// -- Conveniences --------------------------------------------------------------------------------

/**
 * A single-turn question, answered as text.
 *
 * The shape for the many uses that are not really conversations — classify this, summarize that,
 * extract those fields. Reach for [BedrockRuntime.converse] as soon as there is a second turn, a
 * tool, or a reason to care about [ConverseResponse.stopReason].
 */
public suspend fun BedrockRuntime.ask(
    modelId: String,
    prompt: String,
    system: String? = null,
    maxTokens: Int? = null,
): String = converse(
    ConverseRequest(
        modelId = modelId,
        messages = listOf(Message.user(prompt)),
        system = system?.let { listOf(SystemContentBlock.text(it)) },
        inferenceConfig = maxTokens?.let { InferenceConfiguration(maxTokens = it) },
    ),
).text

/**
 * Appends the model's reply to a conversation and returns the new list.
 *
 * The bookkeeping every multi-turn caller writes: Converse is stateless, so continuing a
 * conversation means sending the whole history back, **including the assistant's previous turns
 * exactly as they came**. Rebuilding an assistant turn from its text alone drops tool-use blocks
 * and reasoning, and a reasoning model rejects the next turn when its reasoning is missing.
 *
 * ```kotlin
 * var history = listOf(Message.user("What is the weather in Paris?"))
 * val reply = bedrock.converse(ConverseRequest(modelId, history, toolConfig = tools))
 * history = history.plusReply(reply) + Message(ConversationRole.USER, toolResults)
 * ```
 */
public fun List<Message>.plusReply(response: ConverseResponse): List<Message> =
    response.message?.let { this + it } ?: this
