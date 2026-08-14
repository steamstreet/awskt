package com.steamstreet.awskt.sns

import com.steamstreet.awskt.core.AwsCallObserver
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsHttpTimeouts
import com.steamstreet.awskt.core.AwsProtocol
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.BatchRetry
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.awsHttpClient
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.core.resolveRegion
import io.ktor.client.HttpClient

/**
 * SNS's **query protocol** dialect: form-encoded requests, XML responses.
 *
 * The odd one out in this library. Every other service module here speaks AWS-JSON or REST-JSON;
 * SNS still speaks the oldest wire format AWS serves, which is why this module carries `Wire.kt`
 * and no `@Serializable` anything. See that file for what the protocol costs and why it is paid
 * here rather than generalized into `aws-core`.
 */
public val SNS_PROTOCOL: AwsProtocol = AwsProtocol.awsQuery(endpointPrefix = "sns")

/**
 * The API version every query-protocol request must name.
 *
 * Not cosmetic and not derivable: the query protocol has no equivalent of `X-Amz-Target`, so
 * `Action` plus `Version` is how a request identifies itself. Omitting it is a `MissingParameter`
 * error on every call.
 */
internal const val SNS_API_VERSION: String = "2010-03-31"

/**
 * An SNS client covering the **data plane**: publishing.
 *
 * `Publish` and `PublishBatch`, and nothing else. Topic and subscription lifecycle — `CreateTopic`,
 * `Subscribe`, `SetTopicAttributes`, `SetSubscriptionAttributes`, the platform-endpoint calls — is
 * out of scope: those are provisioning, and the Lambdas this library exists to serve publish to
 * topics somebody else declared. They are reachable through the extension seam below, though note
 * the seam is harder work here than in the JSON modules, because an added operation has to encode
 * its own form body and read its own XML.
 *
 * ### Two things worth knowing before you use it
 *
 * 1. **A successful publish is not a successful delivery.** SNS accepts a message and fans it out
 *    asynchronously; a returned message id means SNS has it, not that any subscriber received it.
 *    Delivery failures surface in CloudWatch and delivery-status logs, never here.
 * 2. **Message attributes are what subscription filter policies match on.** An attribute a policy
 *    expects and does not find causes SNS to discard the message for that subscriber, silently and
 *    successfully. See [MessageAttributeValue].
 *
 * ### Extending it
 *
 * [client] is public and neither operation below has privileged access to it. An added operation
 * builds a `FormBody`, calls `client.callRaw("POST", body = …)` and reads the XML back:
 *
 * ```kotlin
 * suspend fun Sns.listTopicArns(): List<String> {
 *     val response = client.callRaw(
 *         method = "POST",
 *         body = "Action=ListTopics&Version=2010-03-31".encodeToByteArray(),
 *         operation = "ListTopics",
 *     )
 *     val xml = response.body.decodeToString()
 *     // …then scan it. `Wire.kt`'s reader is internal; a downstream extension brings its own.
 *     return Regex("<TopicArn>(.*?)</TopicArn>").findAll(xml).map { it.groupValues[1] }.toList()
 * }
 * ```
 */
public interface Sns : AutoCloseable {
    /** The signed transport. Public because it is the extension seam — see the interface KDoc. */
    public val client: AwsServiceClient

    /**
     * Publishes one message to a topic, a mobile endpoint, or a phone number.
     *
     * ### Retry safety is derived from the request
     *
     * A `Publish` with no [PublishRequest.messageDeduplicationId] is
     * [OperationSafety.NOT_IDEMPOTENT]: SNS has no request token, so if the bytes arrived and the
     * socket died before the response came back, a replay fans the message out to **every
     * subscriber a second time** — and unlike an SQS message there is nothing to delete and no
     * visibility timeout to let it lapse. A `Publish` carrying a deduplication id (FIFO topics
     * only) is `IDEMPOTENT`, because SNS recognises the replay for five minutes.
     *
     * The same construction as `aws-sqs`'s `sendMessage` and `aws-dynamodb`'s `writeSafety`:
     * safety is a property of the request, not of the operation name.
     *
     * @throws IllegalArgumentException if not exactly one of [PublishRequest.topicArn],
     *   [PublishRequest.targetArn] and [PublishRequest.phoneNumber] is set. Checked here rather
     *   than left to SNS because the service's own answer for zero destinations and for two
     *   destinations is the same `InvalidParameter`, naming one parameter and not the conflict.
     */
    public suspend fun publish(request: PublishRequest): PublishResponse

    /**
     * Publishes up to 10 messages to one topic in a single call.
     *
     * **Read [PublishBatchResponse] before using this directly**: failures are reported per-entry
     * inside an HTTP 200. [publishAll] chunks, resubmits and raises instead.
     */
    public suspend fun publishBatch(
        topicArn: String,
        entries: List<PublishBatchRequestEntry>,
    ): PublishBatchResponse

    // -- Mobile push endpoints ---------------------------------------------------------------
    //
    // Registering one device against a platform application. See `MobilePush.kt` for the whole
    // picture, and for why `registerDevice` rather than `createPlatformEndpoint` is what a caller
    // should normally reach for.

    /**
     * Registers a device token as a platform endpoint.
     *
     * **[registerDevice] is almost certainly what you want instead.** Called directly, this
     * operation has two behaviours that make it unsafe as a device-registration primitive:
     *
     * - a token already registered **with different attributes** raises [InvalidParameterException]
     *   with the existing ARN buried in the message text, rather than returning it; and
     * - a token already registered with *matching* attributes returns the existing ARN **without
     *   re-enabling it**, so the result may be an endpoint SNS will not deliver to.
     *
     * `IDEMPOTENT` in the narrow sense that matters to the retry loop: replaying the identical
     * request returns the same endpoint ARN rather than creating a second endpoint.
     */
    public suspend fun createPlatformEndpoint(
        request: CreatePlatformEndpointRequest,
    ): CreatePlatformEndpointResponse

    /**
     * Reads an endpoint's `Token`, `Enabled` and `CustomUserData`.
     *
     * @throws NotFoundException if the endpoint has been deleted — which is how a stored ARN goes
     *   stale, and why [registerDevice] starts from the token rather than from an ARN.
     */
    public suspend fun getEndpointAttributes(endpointArn: String): EndpointAttributes

    /**
     * Writes an endpoint's attributes. Returns nothing.
     *
     * The operation that re-enables an endpoint SNS disabled and updates a rotated device token.
     * Attributes not named are left alone, so a repair can send `Token` and `Enabled` without
     * disturbing `CustomUserData`.
     */
    public suspend fun setEndpointAttributes(endpointArn: String, attributes: Map<String, String>)

    /**
     * Deletes an endpoint. Returns nothing.
     *
     * `IDEMPOTENT`, and unusually forgiving: SNS answers **success** for an endpoint that does not
     * exist, so a duplicate delete is not an error and does not need catching.
     */
    public suspend fun deleteEndpoint(endpointArn: String)

    /**
     * Lists one page of a platform application's endpoints.
     *
     * **[ListEndpointsResponse.nextToken] can be non-null on a short page.** See [listAllEndpoints].
     */
    public suspend fun listEndpointsByPlatformApplication(
        platformApplicationArn: String,
        nextToken: String? = null,
    ): ListEndpointsResponse
}

/** Configuration for [Sns]. */
public class SnsConfig {
    public var region: String? = null
    public var endpointUrl: String? = null
    public var credentialsProvider: AwsCredentialsProvider? = null

    /**
     * A client to send on, instead of one built here.
     *
     * Supplying one **bypasses [caInfo] and [httpTimeouts]**: those are arguments to the client this
     * factory would have built, and a client the caller already owns is configured by the caller. A
     * caller-supplied client with no `HttpTimeout` plugin has no attempt bound at all.
     */
    public var httpClient: HttpClient? = null
    public var retryConfig: RetryConfig = RetryConfig()

    /** CA bundle for the native Curl engine. Null uses the system trust store. */
    public var caInfo: String? = null

    /**
     * Per-attempt time limits for the client this factory builds. Ignored when [httpClient] is set.
     *
     * The library defaults suit SNS: a publish is a small request answered quickly, and the fan-out
     * that follows is asynchronous and not something this call waits on. Worth reading alongside
     * [Sns.publish]'s `NOT_IDEMPOTENT` note — shortening this turns hangs into *decisions* about
     * whether to republish, rather than into duplicate fan-outs.
     */
    public var httpTimeouts: AwsHttpTimeouts = AwsHttpTimeouts()

    /**
     * Notified of every attempt, retry decision and give-up. Null means no instrumentation.
     *
     * Worth wiring up here in particular: `Publish` without a deduplication id is
     * `NOT_IDEMPOTENT`, so ambiguous transport failures are surfaced rather than replayed, and this
     * is how you find out how often that happens before somebody notices a missing notification.
     */
    public var observer: AwsCallObserver? = null
}

/** Builds an SNS client. */
public fun Sns(configure: SnsConfig.() -> Unit = {}): Sns {
    val config = SnsConfig().apply(configure)
    val region = resolveRegion(config.region)
    // One binding for the effective client, handed to both the transport and the close path — see
    // the same note in `DynamoDb()`, where splitting the two leaked the client we had just built.
    val httpClient = config.httpClient ?: awsHttpClient(config.caInfo, config.httpTimeouts)
    return DefaultSns(
        client = AwsServiceClient(
            httpClient = httpClient,
            credentialsProvider = config.credentialsProvider ?: defaultCredentialsProvider(),
            endpoint = resolveEndpoint("sns", region, config.endpointUrl),
            region = region,
            protocol = SNS_PROTOCOL,
            retryConfig = config.retryConfig,
            observer = config.observer,
        ),
        ownsHttpClient = config.httpClient == null,
        httpClient = httpClient,
    )
}

internal class DefaultSns(
    override val client: AwsServiceClient,
    private val ownsHttpClient: Boolean = false,
    private val httpClient: HttpClient? = null,
) : Sns {

    override suspend fun publish(request: PublishRequest): PublishResponse {
        requireExactlyOneDestination(request)

        val body = FormBody()
            .put("Action", "Publish")
            .put("Version", SNS_API_VERSION)
            .put("TopicArn", request.topicArn)
            .put("TargetArn", request.targetArn)
            .put("PhoneNumber", request.phoneNumber)
            .put("Message", request.message)
            .put("Subject", request.subject)
            .put("MessageStructure", request.messageStructure)
            .put("MessageDeduplicationId", request.messageDeduplicationId)
            .put("MessageGroupId", request.messageGroupId)
            .also { it.putMessageAttributes("MessageAttributes", request.messageAttributes) }
            .encode()

        val xml = mapErrors {
            client.callRaw(
                method = "POST",
                body = body,
                operation = "Publish",
                safety = publishSafety(request.messageDeduplicationId),
            )
        }.body.decodeToString()

        return PublishResponse(
            messageId = Xml.text(xml, "MessageId"),
            sequenceNumber = Xml.text(xml, "SequenceNumber"),
        )
    }

    override suspend fun publishBatch(
        topicArn: String,
        entries: List<PublishBatchRequestEntry>,
    ): PublishBatchResponse {
        val body = FormBody()
            .put("Action", "PublishBatch")
            .put("Version", SNS_API_VERSION)
            .put("TopicArn", topicArn)
        entries.forEachIndexed { index, entry ->
            // `.member.N`, indexed from 1. Zero-based here would drop the first entry silently:
            // SNS reads the indices it recognises and ignores the rest.
            val member = "PublishBatchRequestEntries.member.${index + 1}"
            body.put("$member.Id", entry.id)
            body.put("$member.Message", entry.message)
            body.put("$member.Subject", entry.subject)
            body.put("$member.MessageStructure", entry.messageStructure)
            body.put("$member.MessageDeduplicationId", entry.messageDeduplicationId)
            body.put("$member.MessageGroupId", entry.messageGroupId)
            body.putMessageAttributes("$member.MessageAttributes", entry.messageAttributes)
        }

        val xml = mapErrors {
            client.callRaw(
                method = "POST",
                body = body.encode(),
                operation = "PublishBatch",
                // As in aws-sqs: replayable only if every entry is de-duplicated, because a replay
                // resends all of them.
                safety = if (entries.isNotEmpty() && entries.all { it.messageDeduplicationId != null }) {
                    OperationSafety.IDEMPOTENT
                } else {
                    OperationSafety.NOT_IDEMPOTENT
                },
            )
        }.body.decodeToString()

        // Scoped to each list's own block before scanning for members — `Successful` and `Failed`
        // are siblings, so a scan over the whole document would read one list's members into both.
        return PublishBatchResponse(
            successful = Xml.members(Xml.block(xml, "Successful")).map { member ->
                PublishBatchResultEntry(
                    id = Xml.text(member, "Id").orEmpty(),
                    messageId = Xml.text(member, "MessageId"),
                    sequenceNumber = Xml.text(member, "SequenceNumber"),
                )
            },
            failed = Xml.members(Xml.block(xml, "Failed")).map { member ->
                BatchResultErrorEntry(
                    id = Xml.text(member, "Id").orEmpty(),
                    senderFault = Xml.text(member, "SenderFault")?.toBoolean() ?: false,
                    code = Xml.text(member, "Code"),
                    message = Xml.text(member, "Message"),
                )
            },
        )
    }

    // -- Mobile push endpoints ---------------------------------------------------------------
    //
    // All five are IDEMPOTENT. None creates a second resource on a replay: CreatePlatformEndpoint
    // returns the existing endpoint for an identical request, Set and Delete converge on a state,
    // and Get and List are reads. This is the one group in this module where the transport may
    // safely replay an ambiguous failure.

    override suspend fun createPlatformEndpoint(
        request: CreatePlatformEndpointRequest,
    ): CreatePlatformEndpointResponse {
        val xml = mapErrors {
            client.callRaw(
                method = "POST",
                body = request.toForm(),
                operation = "CreatePlatformEndpoint",
                safety = OperationSafety.IDEMPOTENT,
            )
        }.body.decodeToString()
        return CreatePlatformEndpointResponse(endpointArn = Xml.text(xml, "EndpointArn"))
    }

    override suspend fun getEndpointAttributes(endpointArn: String): EndpointAttributes {
        val xml = mapErrors {
            client.callRaw(
                method = "POST",
                body = getEndpointAttributesForm(endpointArn),
                operation = "GetEndpointAttributes",
                safety = OperationSafety.IDEMPOTENT,
            )
        }.body.decodeToString()
        return EndpointAttributes(Xml.attributeMap(Xml.block(xml, "Attributes")))
    }

    override suspend fun setEndpointAttributes(endpointArn: String, attributes: Map<String, String>) {
        mapErrors {
            client.callRaw(
                method = "POST",
                body = setEndpointAttributesForm(endpointArn, attributes),
                operation = "SetEndpointAttributes",
                safety = OperationSafety.IDEMPOTENT,
            )
        }
    }

    override suspend fun deleteEndpoint(endpointArn: String) {
        mapErrors {
            client.callRaw(
                method = "POST",
                body = deleteEndpointForm(endpointArn),
                operation = "DeleteEndpoint",
                safety = OperationSafety.IDEMPOTENT,
            )
        }
    }

    override suspend fun listEndpointsByPlatformApplication(
        platformApplicationArn: String,
        nextToken: String?,
    ): ListEndpointsResponse {
        val xml = mapErrors {
            client.callRaw(
                method = "POST",
                body = listEndpointsForm(platformApplicationArn, nextToken),
                operation = "ListEndpointsByPlatformApplication",
                safety = OperationSafety.IDEMPOTENT,
            )
        }.body.decodeToString()
        return parseListEndpoints(xml)
    }

    override fun close() {
        if (ownsHttpClient) httpClient?.close()
    }
}

/** A de-duplicated publish is safe to replay; one without a token is not. See [Sns.publish]. */
private fun publishSafety(deduplicationId: String?): OperationSafety =
    if (deduplicationId != null) OperationSafety.IDEMPOTENT else OperationSafety.NOT_IDEMPOTENT

/**
 * Rejects a request that names no destination, or more than one, before anything is sent.
 *
 * SNS answers both mistakes with the same `InvalidParameter`, naming a parameter rather than the
 * conflict, so a request with both a topic and a phone number produces an error that reads like a
 * malformed ARN. Checking here names the actual problem.
 */
private fun requireExactlyOneDestination(request: PublishRequest) {
    val destinations = listOfNotNull(
        request.topicArn?.let { "topicArn" },
        request.targetArn?.let { "targetArn" },
        request.phoneNumber?.let { "phoneNumber" },
    )
    require(destinations.size == 1) {
        if (destinations.isEmpty()) {
            "PublishRequest names no destination: set exactly one of topicArn, targetArn or phoneNumber."
        } else {
            "PublishRequest names ${destinations.size} destinations (${destinations.joinToString()}); " +
                "SNS accepts exactly one."
        }
    }
}

// -- Conveniences --------------------------------------------------------------------------------

/** Publishes a message to a topic and returns its message id. */
public suspend fun Sns.publish(topicArn: String, message: String, subject: String? = null): String? =
    publish(PublishRequest(message = message, topicArn = topicArn, subject = subject)).messageId

// -- Batching ------------------------------------------------------------------------------------

/** `PublishBatch` accepts at most this many entries. Above it, AWS returns a 400. */
private const val PUBLISH_BATCH_MAX_ENTRIES = 10

/**
 * Publishes any number of messages to one topic: chunked to 10, with transient failures resubmitted.
 *
 * ### What this fixes
 *
 * The fourth appearance of the same trap in this library, after EventBridge's `PutEvents`, Secrets
 * Manager's `BatchGetSecretValue` and SQS's `SendMessageBatch`:
 *
 * - `PublishBatch` rejects the whole request above **10 entries**; and
 * - it reports per-entry failures **inside an HTTP 200**, with no error code on the response and no
 *   `x-amz-retry-after`, so the transport's retry loop never sees a throttled entry and never
 *   paces it.
 *
 * ### Which failures are resubmitted
 *
 * [BatchResultErrorEntry.senderFault] decides — the service says so directly, so unlike
 * `putEventsAll` there is no hand-maintained list of retryable codes to drift out of date. A
 * sender-fault entry is terminal and **stops the whole call at the end of the round that produced
 * it**, for the same reason as every sibling helper: the outcome is decided, and continuing spends
 * the backoff budget while publishing *more* messages the caller then has to reconcile.
 *
 * ### Duplicate delivery
 *
 * Every exception is a [PublishBatchPartialFailureException] carrying both halves, because
 * **published messages have already been fanned out and cannot be recalled**. SNS has no equivalent
 * of an SQS delete: once a subscriber has it, it has it. Resubmit `failed.map { it.first }`.
 *
 * @param entries the messages, with ids distinct **across the whole list** rather than merely
 *   within a chunk — checked before anything is sent, because two duplicates landing in different
 *   chunks are two requests that each look valid and a result that cannot be paired back up.
 * @return one result entry per request entry, in request order.
 * @throws IllegalArgumentException if two entries share an id.
 * @throws PublishBatchPartialFailureException if any message could not be published.
 */
public suspend fun Sns.publishAll(
    topicArn: String,
    entries: List<PublishBatchRequestEntry>,
    maxRounds: Int = 10,
    backoff: BatchRetry = BatchRetry.Default,
): List<PublishBatchResultEntry> {
    val duplicates = entries.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    require(duplicates.isEmpty()) {
        "Batch entry ids must be distinct across the whole call, not merely within a chunk of " +
            "$PUBLISH_BATCH_MAX_ENTRIES; these repeat: ${duplicates.sorted().joinToString()}"
    }
    if (entries.isEmpty()) return emptyList()

    val outcomes = mutableMapOf<String, PublishBatchResultEntry>()
    val errors = mutableMapOf<String, BatchResultErrorEntry>()
    val byId = entries.associateBy { it.id }

    fun partialFailure(failedIds: Collection<String>, message: String) =
        PublishBatchPartialFailureException(
            // Request order on both halves — the caller wrote the list, and handing it back
            // reordered makes the two impossible to line up.
            succeeded = entries.mapNotNull { outcomes[it.id] },
            failed = entries.map { it.id }.filter { it in failedIds }
                .map { byId.getValue(it) to errors.getValue(it) },
            message = message,
        )

    for (chunk in entries.chunked(PUBLISH_BATCH_MAX_ENTRIES)) {
        var pending = chunk
        var round = 0
        // Per chunk, not per call — one budget shared across a 500-entry batch would leave the last
        // chunks nothing to spend. Same note as `putEventsAll` and `batchGetAll`.
        var slept = 0L

        while (true) {
            val response = publishBatch(topicArn, pending)
            response.successful.forEach { outcomes[it.id] = it }
            response.failed.forEach { errors[it.id] = it }

            val terminal = response.failed.filter { it.senderFault }.map { it.id }
            val transient = response.failed.filterNot { it.senderFault }.map { it.id }

            if (terminal.isNotEmpty()) {
                throw partialFailure(
                    terminal + transient,
                    "PublishBatch rejected ${terminal.size} " +
                        "${if (terminal.size == 1) "entry" else "entries"} with SenderFault " +
                        "(${terminal.joinToString { "$it: ${errors[it]?.code}" }}); resubmitting " +
                        "them would fail identically, so the batch was stopped.",
                )
            }
            if (transient.isEmpty()) break

            if (++round >= maxRounds) {
                throw partialFailure(
                    transient,
                    "PublishBatch still had ${transient.size} transient per-entry " +
                        "${if (transient.size == 1) "failure" else "failures"} after $maxRounds " +
                        "rounds; reporting success on a partially published batch would be silent " +
                        "message loss.",
                )
            }
            slept = backoff.awaitResubmit(round - 1, slept) ?: throw partialFailure(
                transient,
                "PublishBatch still had ${transient.size} transient per-entry " +
                    "${if (transient.size == 1) "failure" else "failures"} after $round rounds and " +
                    "${slept}ms of backoff, which exhausted the " +
                    "${backoff.config.maxTotalRetryDuration} budget.",
            )
            pending = transient.map { byId.getValue(it) }
        }
    }

    return entries.map { outcomes.getValue(it.id) }
}
