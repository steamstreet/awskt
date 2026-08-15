package com.steamstreet.awskt.sqs

import com.steamstreet.awskt.core.AwsCallObserver
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsHttpTimeouts
import com.steamstreet.awskt.core.AwsProtocol
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.BatchRetry
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.awsHttpClient
import com.steamstreet.awskt.core.callJson
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.core.resolveRegion
import io.ktor.client.HttpClient

/**
 * SQS's AWS-JSON 1.0 dialect.
 *
 * The target prefix is `AmazonSQS` and the endpoint prefix is `sqs` — they differ, which is why
 * both are named explicitly. Note the dialect is **1.0**, DynamoDB's, not the 1.1 that EventBridge,
 * Secrets Manager and KMS use; getting it wrong is a 400 on every call.
 */
public val SQS_PROTOCOL: AwsProtocol =
    AwsProtocol.awsJson1_0(endpointPrefix = "sqs", targetPrefix = "AmazonSQS")

/**
 * An SQS client covering the **data plane**: the operations that move messages.
 *
 * Send, receive, delete, extend visibility, and resolve a queue name to a URL. Queue lifecycle —
 * `CreateQueue`, `DeleteQueue`, `SetQueueAttributes`, `PurgeQueue`, the tag and permission calls —
 * is out of scope: queues are provisioned by whatever manages the infrastructure. All of them are
 * reachable through the extension seam below.
 *
 * ### Three things worth knowing before you use it
 *
 * 1. **`QueueUrl` is a body field, not a destination.** See the `Model.kt` file KDoc. A
 *    cross-account queue needs no endpoint override; a queue from another *region* needs a client
 *    configured for that region.
 * 2. **A short `ReceiveMessage` result does not mean the queue is empty.** SQS samples its servers;
 *    asking for 10 and getting 2 — or 0 — is normal on a queue with thousands of messages.
 * 3. **Long polling needs HTTP timeouts longer than the poll.** [SqsConfig.httpTimeouts] defaults
 *    higher than the rest of this library for that reason; read its KDoc before overriding it.
 *
 * ### Extending it
 *
 * As with every other client here, [client] is public and no operation below has privileged access
 * to it — each is a `callJson` on that same object:
 *
 * ```kotlin
 * @Serializable
 * data class GetQueueAttributesRequest(
 *     @SerialName("QueueUrl") val queueUrl: String,
 *     @SerialName("AttributeNames") val attributeNames: List<String>,
 * )
 *
 * @Serializable
 * data class GetQueueAttributesResponse(
 *     @SerialName("Attributes") val attributes: Map<String, String> = emptyMap(),
 * )
 *
 * suspend fun Sqs.getQueueAttributes(
 *     request: GetQueueAttributesRequest,
 * ): GetQueueAttributesResponse =
 *     client.callJson(
 *         "GetQueueAttributes",
 *         request,
 *         GetQueueAttributesRequest.serializer(),
 *         GetQueueAttributesResponse.serializer(),
 *     )
 * ```
 */
public interface Sqs : AutoCloseable {
    /** The signed transport. Public because it is the extension seam — see the interface KDoc. */
    public val client: AwsServiceClient

    /**
     * Sends one message.
     *
     * ### Retry safety is derived from the request, not fixed for the operation
     *
     * A `SendMessage` with no [SendMessageRequest.messageDeduplicationId] is
     * [OperationSafety.NOT_IDEMPOTENT]: if the bytes reached SQS and the socket died before the
     * response came back, a replay enqueues the message **twice** and every consumer sees both. A
     * `SendMessage` that carries one is [OperationSafety.IDEMPOTENT], because SQS de-duplicates
     * against that id for five minutes and a replay of identical bytes is a no-op returning the
     * original message id.
     *
     * This is the same construction `aws-dynamodb` uses for `writeSafety` — safety is a property of
     * the *request*, not of the operation name.
     *
     * **One case is deliberately treated as unsafe when it is not**: a FIFO queue with
     * content-based deduplication enabled de-duplicates on a SHA-256 of the body, with no explicit
     * id. That is a *queue attribute*, invisible from here, so this client cannot see that a replay
     * would be safe and does not guess. Set an explicit id — which is better practice anyway, since
     * it survives a body that legitimately repeats — or accept a surfaced exception on an ambiguous
     * failure.
     */
    public suspend fun sendMessage(request: SendMessageRequest): SendMessageResponse

    /**
     * Sends up to 10 messages in one call.
     *
     * **Read [SendMessageBatchResponse] before using this directly**: failures are reported
     * per-entry inside an HTTP 200. [sendMessagesAll] chunks, resubmits and raises instead.
     */
    public suspend fun sendMessageBatch(request: SendMessageBatchRequest): SendMessageBatchResponse

    /**
     * Receives up to 10 messages, optionally long-polling.
     *
     * Safety is derived from [ReceiveMessageRequest.receiveRequestAttemptId], mirroring
     * [sendMessage]. Without one this is [OperationSafety.NOT_IDEMPOTENT] — and the reason is not
     * that a replay corrupts anything, but that it does not produce *the same answer*: the messages
     * from the lost response are now in flight and invisible, so a retry returns a different set
     * and the first set is delayed by a whole visibility timeout. Surfacing the failure lets the
     * caller's own poll loop come back around, which costs nothing; replaying silently makes
     * messages late.
     */
    public suspend fun receiveMessage(request: ReceiveMessageRequest): ReceiveMessageResponse

    /**
     * Deletes one message. Returns nothing — SQS's response body is `{}`.
     *
     * `IDEMPOTENT`: deleting an already-deleted message is not an error, and a replay after an
     * ambiguous failure removes the same message.
     */
    public suspend fun deleteMessage(request: DeleteMessageRequest)

    /**
     * Deletes up to 10 messages in one call.
     *
     * **Read [DeleteMessageBatchResponse]**: failures are reported per-entry inside an HTTP 200,
     * and a delete that quietly failed becomes a message processed twice. [deleteMessagesAll]
     * handles it.
     */
    public suspend fun deleteMessageBatch(request: DeleteMessageBatchRequest): DeleteMessageBatchResponse

    /**
     * Changes one message's visibility timeout. Returns nothing.
     *
     * The operation that keeps a slow handler's message from being redelivered underneath it: call
     * it periodically while work is in progress, and the message stays invisible. See
     * [MessageNotInflightException] for what happens when nobody does.
     */
    public suspend fun changeMessageVisibility(request: ChangeMessageVisibilityRequest)

    /** Changes up to 10 messages' visibility timeouts. Per-entry failures arrive inside an HTTP 200. */
    public suspend fun changeMessageVisibilityBatch(
        request: ChangeMessageVisibilityBatchRequest,
    ): ChangeMessageVisibilityBatchResponse

    /** Resolves a queue name to the URL every other operation needs. See [GetQueueUrlRequest]. */
    public suspend fun getQueueUrl(request: GetQueueUrlRequest): GetQueueUrlResponse
}

/** Configuration for [Sqs]. */
public class SqsConfig {
    public var region: String? = null
    public var endpointUrl: String? = null
    public var credentialsProvider: AwsCredentialsProvider? = null

    /**
     * A client to send on, instead of one built here.
     *
     * Supplying one **bypasses [caInfo] and [httpTimeouts]**: those are arguments to the client this
     * factory would have built, and a client the caller already owns is configured by the caller.
     * For this module in particular that is worth checking twice — a caller-supplied client with
     * the library's ordinary 30-second timeouts will time out on a 20-second long poll under load.
     */
    public var httpClient: HttpClient? = null
    public var retryConfig: RetryConfig = RetryConfig()

    /** CA bundle for the native Curl engine. Null uses the system trust store. */
    public var caInfo: String? = null

    /**
     * Per-attempt time limits. **Deliberately longer than every other module's default**, and this
     * is the one config value in this library that is not simply inherited.
     *
     * `AwsHttpTimeouts` defaults to a 30-second socket and request timeout, which suits a service
     * that answers as fast as it can. SQS is the exception: [ReceiveMessageRequest.waitTimeSeconds]
     * asks the service to **hold the connection open, sending nothing, for up to 20 seconds** —
     * that is what long polling *is*. Against a 30-second socket timeout a maximum-length poll
     * leaves 10 seconds of headroom for connection setup, TLS and the response itself, and a client
     * under load spends that. The failure is nasty because it looks like SQS being slow rather than
     * a misconfiguration: the poll dies at 30 seconds with a timeout, the messages it was about to
     * return stay queued, and the consumer's throughput collapses without a single service error.
     *
     * 90 seconds gives a maximum-length poll room to breathe and still bounds a genuinely stuck
     * connection. **If you lower this, keep it comfortably above your largest `waitTimeSeconds`.**
     */
    public var httpTimeouts: AwsHttpTimeouts = AwsHttpTimeouts(
        connectTimeoutMillis = 3_000,
        socketTimeoutMillis = 90_000,
        requestTimeoutMillis = 90_000,
    )

    /**
     * Notified of every attempt, retry decision and give-up. Null means no instrumentation.
     *
     * Worth wiring up in this module: `SendMessage` without a deduplication id is
     * `NOT_IDEMPOTENT`, so an ambiguous transport failure is surfaced rather than replayed, and the
     * observer is how you find out how often that is happening.
     */
    public var observer: AwsCallObserver? = null
}

/** Builds an SQS client. */
public fun Sqs(configure: SqsConfig.() -> Unit = {}): Sqs {
    val config = SqsConfig().apply(configure)
    val region = resolveRegion(config.region)
    // One binding for the effective client, handed to both the transport and the close path — see
    // the same note in `DynamoDb()`, where splitting the two leaked the client we had just built.
    val httpClient = config.httpClient ?: awsHttpClient(config.caInfo, config.httpTimeouts)
    return DefaultSqs(
        client = AwsServiceClient(
            httpClient = httpClient,
            credentialsProvider = config.credentialsProvider ?: defaultCredentialsProvider(),
            endpoint = resolveEndpoint("sqs", region, config.endpointUrl),
            region = region,
            protocol = SQS_PROTOCOL,
            retryConfig = config.retryConfig,
            observer = config.observer,
        ),
        ownsHttpClient = config.httpClient == null,
        httpClient = httpClient,
    )
}

internal class DefaultSqs(
    override val client: AwsServiceClient,
    private val ownsHttpClient: Boolean = false,
    private val httpClient: HttpClient? = null,
) : Sqs {

    override suspend fun sendMessage(request: SendMessageRequest): SendMessageResponse = mapErrors {
        client.callJson(
            "SendMessage", request, SendMessageRequest.serializer(), SendMessageResponse.serializer(),
            safety = sendSafety(request.messageDeduplicationId),
        )
    }

    override suspend fun sendMessageBatch(
        request: SendMessageBatchRequest,
    ): SendMessageBatchResponse = mapErrors {
        client.callJson(
            "SendMessageBatch", request, SendMessageBatchRequest.serializer(),
            SendMessageBatchResponse.serializer(),
            // Safe to replay only if *every* entry is de-duplicated. One entry without an id makes
            // the whole batch replayable-with-duplicates, because a replay resends all of them.
            safety = if (request.entries.isNotEmpty() && request.entries.all { it.messageDeduplicationId != null }) {
                OperationSafety.IDEMPOTENT
            } else {
                OperationSafety.NOT_IDEMPOTENT
            },
        )
    }

    override suspend fun receiveMessage(request: ReceiveMessageRequest): ReceiveMessageResponse =
        mapErrors {
            client.callJson(
                "ReceiveMessage", request, ReceiveMessageRequest.serializer(),
                ReceiveMessageResponse.serializer(),
                safety = sendSafety(request.receiveRequestAttemptId),
            )
        }

    override suspend fun deleteMessage(request: DeleteMessageRequest) {
        mapErrors {
            client.callJson(
                "DeleteMessage", request, DeleteMessageRequest.serializer(),
                EmptyResponse.serializer(), safety = OperationSafety.IDEMPOTENT,
            )
        }
    }

    override suspend fun deleteMessageBatch(
        request: DeleteMessageBatchRequest,
    ): DeleteMessageBatchResponse = mapErrors {
        client.callJson(
            "DeleteMessageBatch", request, DeleteMessageBatchRequest.serializer(),
            DeleteMessageBatchResponse.serializer(), safety = OperationSafety.IDEMPOTENT,
        )
    }

    override suspend fun changeMessageVisibility(request: ChangeMessageVisibilityRequest) {
        mapErrors {
            client.callJson(
                "ChangeMessageVisibility", request, ChangeMessageVisibilityRequest.serializer(),
                EmptyResponse.serializer(), safety = OperationSafety.IDEMPOTENT,
            )
        }
    }

    override suspend fun changeMessageVisibilityBatch(
        request: ChangeMessageVisibilityBatchRequest,
    ): ChangeMessageVisibilityBatchResponse = mapErrors {
        client.callJson(
            "ChangeMessageVisibilityBatch", request, ChangeMessageVisibilityBatchRequest.serializer(),
            ChangeMessageVisibilityBatchResponse.serializer(), safety = OperationSafety.IDEMPOTENT,
        )
    }

    override suspend fun getQueueUrl(request: GetQueueUrlRequest): GetQueueUrlResponse = mapErrors {
        client.callJson(
            "GetQueueUrl", request, GetQueueUrlRequest.serializer(), GetQueueUrlResponse.serializer(),
            safety = OperationSafety.IDEMPOTENT,
        )
    }

    override fun close() {
        if (ownsHttpClient) httpClient?.close()
    }
}

/**
 * An operation carrying a de-duplication token is safe to replay; one without is not.
 *
 * Shared by `SendMessage` (`MessageDeduplicationId`) and `ReceiveMessage`
 * (`ReceiveRequestAttemptId`) because both tokens do the same job for the same reason — SQS
 * recognises the replay and answers with the original outcome instead of performing the work twice.
 */
private fun sendSafety(deduplicationToken: String?): OperationSafety =
    if (deduplicationToken != null) OperationSafety.IDEMPOTENT else OperationSafety.NOT_IDEMPOTENT

// -- Conveniences --------------------------------------------------------------------------------

/** Sends a message body to a queue and returns its message id. */
public suspend fun Sqs.sendMessage(queueUrl: String, body: String): String? =
    sendMessage(SendMessageRequest(queueUrl, body)).messageId

/**
 * Receives messages, long-polling by default.
 *
 * The default [waitTimeSeconds] of 20 is deliberately the *maximum* rather than SQS's own default
 * of 0. Short polling is almost never what a caller wants — it bills for empty responses, adds
 * latency, and makes the "a short result does not mean an empty queue" sampling problem far more
 * visible. A caller that genuinely wants a non-blocking peek passes 0 explicitly.
 */
public suspend fun Sqs.receiveMessages(
    queueUrl: String,
    maxNumberOfMessages: Int = 10,
    waitTimeSeconds: Int = 20,
    visibilityTimeout: Int? = null,
): List<Message> = receiveMessage(
    ReceiveMessageRequest(
        queueUrl = queueUrl,
        maxNumberOfMessages = maxNumberOfMessages,
        waitTimeSeconds = waitTimeSeconds,
        visibilityTimeout = visibilityTimeout,
    ),
).messages

/** Deletes a received message. Sugar for the [Message.receiptHandle] indirection. */
public suspend fun Sqs.deleteMessage(queueUrl: String, message: Message) {
    val handle = requireNotNull(message.receiptHandle) {
        "Message ${message.messageId} has no receipt handle and cannot be deleted. A message " +
            "constructed by hand, rather than returned by receiveMessage, has nothing to delete with."
    }
    deleteMessage(DeleteMessageRequest(queueUrl, handle))
}

// -- Batching ------------------------------------------------------------------------------------

/** Every SQS batch operation accepts at most this many entries. Above it, AWS returns a 400. */
private const val BATCH_MAX_ENTRIES = 10

/**
 * Sends any number of messages: chunked to 10, with transient per-entry failures resubmitted.
 *
 * ### What this fixes
 *
 * [Sqs.sendMessageBatch] is one raw call, and it has the two failure modes this library has now met
 * three times — in EventBridge's `PutEvents`, in Secrets Manager's `BatchGetSecretValue`, and here:
 *
 * - it rejects the whole request above **10 entries**, so an eleventh message is a runtime 400; and
 * - it reports per-entry failures **inside an HTTP 200** ([SendMessageBatchResponse]), with no
 *   error code on the response and no `x-amz-retry-after`, so the transport's retry loop never sees
 *   a throttled entry and never paces it.
 *
 * ### Which failures are resubmitted
 *
 * [BatchResultErrorEntry.senderFault] decides, and it is the service telling us the answer directly:
 * `true` means the entry is at fault and resubmitting the identical bytes fails identically, so it
 * is terminal; `false` means SQS failed transiently and the entry is resubmitted, paced by
 * [backoff]. This is a better signal than EventBridge's equivalent, where the retryable codes had
 * to be enumerated by hand from the documentation.
 *
 * ### The terminal-failure rule
 *
 * A sender-fault failure **stops the whole call at the end of the round that produced it** — the
 * transient entries of that chunk are not resubmitted and no later chunk is sent. Same reasoning as
 * `putEventsAll`: the outcome is already decided, and continuing spends the backoff budget while
 * enqueuing *more* messages the caller then has to reconcile.
 *
 * ### Duplicate delivery
 *
 * Every exception this throws is a [SendMessageBatchPartialFailureException] carrying both halves,
 * because **the successful entries are already on the queue and cannot be recalled**. Resubmit
 * `failed.map { it.first }`, not the original list.
 *
 * @param entries the messages to send. Entry ids must be distinct **across the whole list**, not
 *   merely within a chunk — this checks that before sending anything, because SQS would otherwise
 *   accept two chunks that each look fine and leave the result impossible to pair back up.
 * @return one result entry per request entry, in request order.
 * @throws IllegalArgumentException if two entries share an id.
 * @throws SendMessageBatchPartialFailureException if any message could not be enqueued.
 */
public suspend fun Sqs.sendMessagesAll(
    queueUrl: String,
    entries: List<SendMessageBatchRequestEntry>,
    maxRounds: Int = 10,
    backoff: BatchRetry = BatchRetry.Default,
): List<SendMessageBatchResultEntry> {
    requireDistinctIds(entries.map { it.id })
    if (entries.isEmpty()) return emptyList()

    val outcomes = mutableMapOf<String, SendMessageBatchResultEntry>()
    val errors = mutableMapOf<String, BatchResultErrorEntry>()
    val byId = entries.associateBy { it.id }

    fun partialFailure(failedIds: Collection<String>, message: String) =
        SendMessageBatchPartialFailureException(
            // In request order, not in the order the loop classified them — the caller wrote the
            // list, and handing it back reordered makes the two impossible to line up.
            succeeded = entries.mapNotNull { outcomes[it.id] },
            failed = entries.map { it.id }.filter { it in failedIds }
                .map { byId.getValue(it) to errors.getValue(it) },
            message = message,
        )

    for (chunk in entries.chunked(BATCH_MAX_ENTRIES)) {
        var pending = chunk
        var round = 0
        var slept = 0L

        while (true) {
            val response = sendMessageBatch(SendMessageBatchRequest(queueUrl, pending))
            response.successful.forEach { outcomes[it.id] = it }
            response.failed.forEach { errors[it.id] = it }

            val terminal = response.failed.filter { it.senderFault }.map { it.id }
            val transient = response.failed.filterNot { it.senderFault }.map { it.id }

            if (terminal.isNotEmpty()) {
                throw partialFailure(
                    terminal + transient,
                    "SendMessageBatch rejected ${terminal.size} " +
                        "${if (terminal.size == 1) "entry" else "entries"} with SenderFault " +
                        "(${terminal.joinToString { "$it: ${errors[it]?.code}" }}); resubmitting " +
                        "them would fail identically, so the batch was stopped.",
                )
            }
            if (transient.isEmpty()) break

            if (++round >= maxRounds) {
                throw partialFailure(
                    transient,
                    "SendMessageBatch still had ${transient.size} transient per-entry " +
                        "${if (transient.size == 1) "failure" else "failures"} after $maxRounds " +
                        "rounds; reporting success on a partially enqueued batch would be silent " +
                        "message loss.",
                )
            }
            slept = backoff.awaitResubmit(round - 1, slept) ?: throw partialFailure(
                transient,
                "SendMessageBatch still had ${transient.size} transient per-entry " +
                    "${if (transient.size == 1) "failure" else "failures"} after $round rounds and " +
                    "${slept}ms of backoff, which exhausted the " +
                    "${backoff.config.maxTotalRetryDuration} budget.",
            )
            pending = transient.map { byId.getValue(it) }
        }
    }

    return entries.map { outcomes.getValue(it.id) }
}

/**
 * Deletes any number of messages: chunked to 10, with transient per-entry failures resubmitted.
 *
 * The sibling of [sendMessagesAll], and the reason it matters is different. A send that silently
 * half-succeeds duplicates messages; a **delete** that silently half-succeeds means the messages
 * that were not deleted come back when their visibility timeout lapses and are processed a second
 * time — hours later, with nothing in the logs connecting the two.
 *
 * Retrying is safe here in a way it is not for a send: deleting an already-deleted message is not
 * an error, so [DeleteMessageBatchPartialFailureException] can be answered by resubmitting the
 * whole original list if that is easier than partitioning it.
 *
 * @throws IllegalArgumentException if two entries share an id.
 * @throws DeleteMessageBatchPartialFailureException if any message could not be deleted.
 */
public suspend fun Sqs.deleteMessagesAll(
    queueUrl: String,
    entries: List<DeleteMessageBatchRequestEntry>,
    maxRounds: Int = 10,
    backoff: BatchRetry = BatchRetry.Default,
): List<DeleteMessageBatchResultEntry> {
    requireDistinctIds(entries.map { it.id })
    if (entries.isEmpty()) return emptyList()

    val outcomes = mutableMapOf<String, DeleteMessageBatchResultEntry>()
    val errors = mutableMapOf<String, BatchResultErrorEntry>()
    val byId = entries.associateBy { it.id }

    fun partialFailure(failedIds: Collection<String>, message: String) =
        DeleteMessageBatchPartialFailureException(
            succeeded = entries.mapNotNull { outcomes[it.id] },
            failed = entries.map { it.id }.filter { it in failedIds }
                .map { byId.getValue(it) to errors.getValue(it) },
            message = message,
        )

    for (chunk in entries.chunked(BATCH_MAX_ENTRIES)) {
        var pending = chunk
        var round = 0
        var slept = 0L

        while (true) {
            val response = deleteMessageBatch(DeleteMessageBatchRequest(queueUrl, pending))
            response.successful.forEach { outcomes[it.id] = it }
            response.failed.forEach { errors[it.id] = it }

            val terminal = response.failed.filter { it.senderFault }.map { it.id }
            val transient = response.failed.filterNot { it.senderFault }.map { it.id }

            if (terminal.isNotEmpty()) {
                throw partialFailure(
                    terminal + transient,
                    "DeleteMessageBatch rejected ${terminal.size} " +
                        "${if (terminal.size == 1) "entry" else "entries"} with SenderFault " +
                        "(${terminal.joinToString { "$it: ${errors[it]?.code}" }}). An expired or " +
                        "invalid receipt handle is the usual cause, and those messages will be " +
                        "redelivered when their visibility timeout lapses.",
                )
            }
            if (transient.isEmpty()) break

            if (++round >= maxRounds) {
                throw partialFailure(
                    transient,
                    "DeleteMessageBatch still had ${transient.size} transient per-entry " +
                        "${if (transient.size == 1) "failure" else "failures"} after $maxRounds " +
                        "rounds; those messages will be redelivered.",
                )
            }
            slept = backoff.awaitResubmit(round - 1, slept) ?: throw partialFailure(
                transient,
                "DeleteMessageBatch still had ${transient.size} transient per-entry " +
                    "${if (transient.size == 1) "failure" else "failures"} after $round rounds and " +
                    "${slept}ms of backoff, which exhausted the " +
                    "${backoff.config.maxTotalRetryDuration} budget; those messages will be redelivered.",
            )
            pending = transient.map { byId.getValue(it) }
        }
    }

    return entries.map { outcomes.getValue(it.id) }
}

/**
 * Rejects duplicate batch ids before anything is sent.
 *
 * SQS rejects a *single request* with duplicate ids ([BatchEntryIdsNotDistinctException]), but the
 * helpers above chunk, so two duplicates landing in different chunks are two requests that each
 * look valid. The result would be an outcome map that silently loses one of them. Checking across
 * the whole list is the only place this can be caught.
 */
private fun requireDistinctIds(ids: List<String>) {
    val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    require(duplicates.isEmpty()) {
        "Batch entry ids must be distinct across the whole call, not merely within a chunk of " +
            "$BATCH_MAX_ENTRIES; these repeat: ${duplicates.sorted().joinToString()}"
    }
}
