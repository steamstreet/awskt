package com.steamstreet.awskt.sqs

import com.steamstreet.awskt.core.Base64BlobSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request and response types for SQS's data plane.
 *
 * ### The rules, restated from `aws-s3` because they apply unchanged
 *
 * **Nothing that carries a `ByteArray` is a `data class`.** Here that is exactly one type —
 * [MessageAttributeValue], whose `BinaryValue` is a blob — and it gets hand-written
 * `equals`/`hashCode` over `contentEquals`/`contentHashCode`. Everything else is an ordinary data
 * class and gains `copy()` legitimately, [Message] included: it *contains* attribute values but
 * holds no array itself, so its generated members delegate to a type that handles its own.
 *
 * ### SQS speaks AWS-JSON now, and that is why this module looks like `aws-dynamodb`
 *
 * SQS was an `awsQuery` service — form-encoded requests, XML responses — until AWS added a JSON
 * protocol in 2023. This client speaks **`application/x-amz-json-1.0` with
 * `X-Amz-Target: AmazonSQS.<Operation>`**, which is DynamoDB's dialect exactly, so none of the
 * hand-written form encoding and XML scanning that `aws-sns` needs appears here. `aws-sns` is the
 * module to read if you want to see what SQS used to require.
 *
 * ### `QueueUrl` is a body field, not a destination
 *
 * Every operation takes a `QueueUrl` that looks like an endpoint —
 * `https://sqs.us-west-2.amazonaws.com/123456789012/my-queue` — and **is not one**. Under the JSON
 * protocol the request goes to the ordinary regional endpoint (`POST /`) and the queue URL rides in
 * the body; the old query protocol did `POST` to the queue URL's own path, which is where the
 * intuition comes from. Verified against the AWS SDK's own endpoint provider, whose parameters do
 * not include the queue URL at all. The practical consequence is that a **cross-account queue works
 * without any endpoint override** — the URL names the account, the endpoint names the region.
 */

/**
 * A message attribute: caller-defined metadata carried alongside the body.
 *
 * **Not a data class** — it carries bytes. See the file KDoc.
 *
 * @property dataType required, and one of `String`, `Number`, `Binary`, or one of those with a
 *   custom suffix (`Number.float`). SQS rejects an attribute whose type does not match which of
 *   [stringValue] / [binaryValue] is populated, so this is not derivable and is not defaulted.
 * @property stringValue set for `String` and `Number` types.
 * @property binaryValue set for `Binary`. Base64 on the wire, bytes here.
 *
 * `StringListValues` and `BinaryListValues` are deliberately absent: AWS documents both as
 * "not implemented — reserved for future use", so modelling them would offer callers a field the
 * service ignores.
 */
@Serializable
public class MessageAttributeValue(
    @SerialName("DataType") public val dataType: String,
    @SerialName("StringValue") public val stringValue: String? = null,
    @SerialName("BinaryValue") @Serializable(with = Base64BlobSerializer::class)
    public val binaryValue: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is MessageAttributeValue &&
                dataType == other.dataType &&
                stringValue == other.stringValue &&
                binaryValue.contentEquals(other.binaryValue)
            )

    override fun hashCode(): Int {
        var result = dataType.hashCode()
        result = 31 * result + (stringValue?.hashCode() ?: 0)
        result = 31 * result + (binaryValue?.contentHashCode() ?: 0)
        return result
    }

    /** Reports the binary value's size, never its bytes — `aws-s3`'s rule. */
    override fun toString(): String =
        "MessageAttributeValue(dataType=$dataType, stringValue=$stringValue" +
            (binaryValue?.let { ", binaryValue=${it.size} bytes" } ?: "") + ")"
}

/**
 * A message *system* attribute. Structurally identical to [MessageAttributeValue] and a separate
 * type because SQS treats them separately: system attributes are drawn from a closed set AWS owns
 * ([MessageSystemAttributeNameForSends]) and are not returned as message attributes.
 */
@Serializable
public class MessageSystemAttributeValue(
    @SerialName("DataType") public val dataType: String,
    @SerialName("StringValue") public val stringValue: String? = null,
    @SerialName("BinaryValue") @Serializable(with = Base64BlobSerializer::class)
    public val binaryValue: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is MessageSystemAttributeValue &&
                dataType == other.dataType &&
                stringValue == other.stringValue &&
                binaryValue.contentEquals(other.binaryValue)
            )

    override fun hashCode(): Int {
        var result = dataType.hashCode()
        result = 31 * result + (stringValue?.hashCode() ?: 0)
        result = 31 * result + (binaryValue?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "MessageSystemAttributeValue(dataType=$dataType, stringValue=$stringValue)"
}

/**
 * The only system attribute a *sender* may set.
 *
 * A `const val` rather than an enum for the same reason `aws-kms`'s algorithms are: it appears on
 * responses too, and AWS extends the set.
 */
public object MessageSystemAttributeNameForSends {
    public const val AWS_TRACE_HEADER: String = "AWSTraceHeader"
}

/**
 * System attribute names a *receiver* may ask for, via
 * [ReceiveMessageRequest.messageSystemAttributeNames].
 *
 * [ALL] is the wildcard. The three `SENT_TIMESTAMP` / `APPROXIMATE_*` values are the ones worth
 * asking for by name: they are how a consumer measures queue latency and detects a message that has
 * been redriven repeatedly without a dead-letter queue catching it.
 */
public object MessageSystemAttributeName {
    public const val ALL: String = "All"
    public const val SENDER_ID: String = "SenderId"
    public const val SENT_TIMESTAMP: String = "SentTimestamp"
    public const val APPROXIMATE_RECEIVE_COUNT: String = "ApproximateReceiveCount"
    public const val APPROXIMATE_FIRST_RECEIVE_TIMESTAMP: String = "ApproximateFirstReceiveTimestamp"
    public const val SEQUENCE_NUMBER: String = "SequenceNumber"
    public const val MESSAGE_DEDUPLICATION_ID: String = "MessageDeduplicationId"
    public const val MESSAGE_GROUP_ID: String = "MessageGroupId"
    public const val AWS_TRACE_HEADER: String = "AWSTraceHeader"
    public const val DEAD_LETTER_QUEUE_SOURCE_ARN: String = "DeadLetterQueueSourceArn"
}

// -- SendMessage ---------------------------------------------------------------------------------

/**
 * `SendMessage`.
 *
 * @param messageBody at most **256 KiB**, and it must be valid XML characters — SQS rejects most
 *   control characters even though the protocol is now JSON, a leftover from the query protocol
 *   this service used to speak. Binary payloads belong in a [MessageAttributeValue] with
 *   `DataType = "Binary"`, or in S3 with a pointer here.
 * @param delaySeconds 0–900. Ignored on FIFO queues, which take their delay from the queue.
 * @param messageDeduplicationId **required on a FIFO queue** unless the queue has content-based
 *   deduplication enabled — and the thing that makes this call safe to retry. See
 *   [Sqs.sendMessage].
 * @param messageGroupId required on a FIFO queue. Messages sharing a group are delivered in order,
 *   and a group is the unit of parallelism: one slow consumer blocks its own group and nothing else.
 */
@Serializable
public data class SendMessageRequest(
    @SerialName("QueueUrl") public val queueUrl: String,
    @SerialName("MessageBody") public val messageBody: String,
    @SerialName("DelaySeconds") public val delaySeconds: Int? = null,
    @SerialName("MessageAttributes")
    public val messageAttributes: Map<String, MessageAttributeValue>? = null,
    @SerialName("MessageSystemAttributes")
    public val messageSystemAttributes: Map<String, MessageSystemAttributeValue>? = null,
    @SerialName("MessageDeduplicationId") public val messageDeduplicationId: String? = null,
    @SerialName("MessageGroupId") public val messageGroupId: String? = null,
)

/**
 * `SendMessage`'s result.
 *
 * ### The MD5 fields are not checked by this client
 *
 * SQS returns them so a caller can verify the body survived the network. This client models them
 * and **does not verify them**, deliberately: verifying needs an MD5 implementation, which is a new
 * dependency in every native target for a check that TLS already makes near-redundant, and which
 * AWS's own newer SDKs have moved to off-by-default for exactly that reason. A caller who wants the
 * check has the field and the body.
 */
@Serializable
public data class SendMessageResponse(
    @SerialName("MessageId") public val messageId: String? = null,
    @SerialName("MD5OfMessageBody") public val md5OfMessageBody: String? = null,
    @SerialName("MD5OfMessageAttributes") public val md5OfMessageAttributes: String? = null,
    @SerialName("MD5OfMessageSystemAttributes") public val md5OfMessageSystemAttributes: String? = null,
    /** FIFO only: the large, non-consecutive number SQS assigns within a message group. */
    @SerialName("SequenceNumber") public val sequenceNumber: String? = null,
)

// -- SendMessageBatch ----------------------------------------------------------------------------

/**
 * One entry in a `SendMessageBatch`.
 *
 * @param id **unique within the batch**, and meaningful only within it — it is how the response
 *   pairs a result back to a request, not a message id. SQS rejects a batch with duplicate ids
 *   ([BatchEntryIdsNotDistinctException]) rather than silently coalescing them.
 */
@Serializable
public data class SendMessageBatchRequestEntry(
    @SerialName("Id") public val id: String,
    @SerialName("MessageBody") public val messageBody: String,
    @SerialName("DelaySeconds") public val delaySeconds: Int? = null,
    @SerialName("MessageAttributes")
    public val messageAttributes: Map<String, MessageAttributeValue>? = null,
    @SerialName("MessageSystemAttributes")
    public val messageSystemAttributes: Map<String, MessageSystemAttributeValue>? = null,
    @SerialName("MessageDeduplicationId") public val messageDeduplicationId: String? = null,
    @SerialName("MessageGroupId") public val messageGroupId: String? = null,
)

@Serializable
public data class SendMessageBatchRequest(
    @SerialName("QueueUrl") public val queueUrl: String,
    @SerialName("Entries") public val entries: List<SendMessageBatchRequestEntry>,
)

@Serializable
public data class SendMessageBatchResultEntry(
    @SerialName("Id") public val id: String,
    @SerialName("MessageId") public val messageId: String,
    @SerialName("MD5OfMessageBody") public val md5OfMessageBody: String? = null,
    @SerialName("MD5OfMessageAttributes") public val md5OfMessageAttributes: String? = null,
    @SerialName("MD5OfMessageSystemAttributes") public val md5OfMessageSystemAttributes: String? = null,
    @SerialName("SequenceNumber") public val sequenceNumber: String? = null,
)

/**
 * One entry's failure, from any of the three batch operations.
 *
 * @property senderFault **the field that decides whether a retry is pointless.** True means the
 *   entry itself is at fault — malformed body, duplicate id, oversized message — and resubmitting
 *   the identical bytes produces the identical rejection. False means SQS failed transiently and
 *   the entry deserves a resubmission. [sendMessagesAll] and [deleteMessagesAll] both partition on
 *   exactly this.
 */
@Serializable
public data class BatchResultErrorEntry(
    @SerialName("Id") public val id: String,
    @SerialName("SenderFault") public val senderFault: Boolean = false,
    @SerialName("Code") public val code: String? = null,
    @SerialName("Message") public val message: String? = null,
)

/**
 * `SendMessageBatch`'s result.
 *
 * **[failed] can be non-empty on an HTTP 200** — the same trap as EventBridge's `PutEvents` and
 * Secrets Manager's `BatchGetSecretValue`. See [sendMessagesAll], which handles it.
 */
@Serializable
public data class SendMessageBatchResponse(
    @SerialName("Successful") public val successful: List<SendMessageBatchResultEntry> = emptyList(),
    @SerialName("Failed") public val failed: List<BatchResultErrorEntry> = emptyList(),
)

// -- ReceiveMessage ------------------------------------------------------------------------------

/**
 * `ReceiveMessage`.
 *
 * @param maxNumberOfMessages 1–10, default 1. **SQS may return fewer than asked for, including
 *   zero, even when the queue is not empty** — it samples a subset of its servers. Treating a short
 *   result as "the queue is drained" is the classic SQS bug; poll again.
 * @param waitTimeSeconds 0–20. Anything above 0 is long polling, which is what you want: it removes
 *   the empty-response billing of short polling and reduces latency. **It must be comfortably
 *   shorter than the HTTP timeouts** — see [SqsConfig.httpTimeouts], which defaults higher than the
 *   rest of this library for precisely this reason.
 * @param visibilityTimeout overrides the queue's default for the messages this call returns.
 * @param receiveRequestAttemptId FIFO only, and the thing that makes this call safe to retry — see
 *   [Sqs.receiveMessage].
 * @param messageSystemAttributeNames the modern spelling. AWS's `AttributeNames` is deprecated and
 *   is not modelled; ask for [MessageSystemAttributeName.ALL] to get everything.
 */
@Serializable
public data class ReceiveMessageRequest(
    @SerialName("QueueUrl") public val queueUrl: String,
    @SerialName("MaxNumberOfMessages") public val maxNumberOfMessages: Int? = null,
    @SerialName("VisibilityTimeout") public val visibilityTimeout: Int? = null,
    @SerialName("WaitTimeSeconds") public val waitTimeSeconds: Int? = null,
    @SerialName("MessageSystemAttributeNames")
    public val messageSystemAttributeNames: List<String>? = null,
    @SerialName("MessageAttributeNames") public val messageAttributeNames: List<String>? = null,
    @SerialName("ReceiveRequestAttemptId") public val receiveRequestAttemptId: String? = null,
)

/**
 * A received message.
 *
 * A data class: it holds no `ByteArray` itself, and the nested [MessageAttributeValue] redacts its
 * own bytes.
 *
 * @property receiptHandle **not** a message id, and not stable: a fresh handle is issued every time
 *   the message is received, and only the newest one deletes it. Storing a handle across a
 *   visibility timeout and deleting with it later is how a message gets processed twice.
 * @property attributes the system attributes asked for, as strings — timestamps are epoch
 *   milliseconds in string form, receive counts are integers in string form. Passed through
 *   unparsed, as `aws-s3` does for its date headers: no parse means no parse failure.
 */
@Serializable
public data class Message(
    @SerialName("MessageId") public val messageId: String? = null,
    @SerialName("ReceiptHandle") public val receiptHandle: String? = null,
    @SerialName("Body") public val body: String? = null,
    @SerialName("MD5OfBody") public val md5OfBody: String? = null,
    @SerialName("Attributes") public val attributes: Map<String, String> = emptyMap(),
    @SerialName("MessageAttributes")
    public val messageAttributes: Map<String, MessageAttributeValue> = emptyMap(),
    @SerialName("MD5OfMessageAttributes") public val md5OfMessageAttributes: String? = null,
)

/**
 * `ReceiveMessage`'s result.
 *
 * [messages] defaults to empty rather than null: SQS omits the field entirely when nothing was
 * available, and "no messages" is the single most common outcome of a long poll. A nullable list
 * would put a `?: emptyList()` at every call site in every consumer loop.
 */
@Serializable
public data class ReceiveMessageResponse(
    @SerialName("Messages") public val messages: List<Message> = emptyList(),
)

// -- DeleteMessage / ChangeMessageVisibility -----------------------------------------------------

@Serializable
public data class DeleteMessageRequest(
    @SerialName("QueueUrl") public val queueUrl: String,
    @SerialName("ReceiptHandle") public val receiptHandle: String,
)

@Serializable
public data class DeleteMessageBatchRequestEntry(
    @SerialName("Id") public val id: String,
    @SerialName("ReceiptHandle") public val receiptHandle: String,
)

@Serializable
public data class DeleteMessageBatchRequest(
    @SerialName("QueueUrl") public val queueUrl: String,
    @SerialName("Entries") public val entries: List<DeleteMessageBatchRequestEntry>,
)

@Serializable
public data class DeleteMessageBatchResultEntry(
    @SerialName("Id") public val id: String,
)

/** `DeleteMessageBatch`'s result. **[failed] can be non-empty on an HTTP 200** — see [deleteMessagesAll]. */
@Serializable
public data class DeleteMessageBatchResponse(
    @SerialName("Successful") public val successful: List<DeleteMessageBatchResultEntry> = emptyList(),
    @SerialName("Failed") public val failed: List<BatchResultErrorEntry> = emptyList(),
)

/**
 * `ChangeMessageVisibility`.
 *
 * @param visibilityTimeout 0–43200 (12 hours), measured **from now**, not from when the message was
 *   received. Zero returns the message to the queue immediately, which is the correct way to
 *   surrender work a handler has decided it cannot do — it beats letting the timeout lapse, and it
 *   beats deleting the message.
 */
@Serializable
public data class ChangeMessageVisibilityRequest(
    @SerialName("QueueUrl") public val queueUrl: String,
    @SerialName("ReceiptHandle") public val receiptHandle: String,
    @SerialName("VisibilityTimeout") public val visibilityTimeout: Int,
)

@Serializable
public data class ChangeMessageVisibilityBatchRequestEntry(
    @SerialName("Id") public val id: String,
    @SerialName("ReceiptHandle") public val receiptHandle: String,
    @SerialName("VisibilityTimeout") public val visibilityTimeout: Int? = null,
)

@Serializable
public data class ChangeMessageVisibilityBatchRequest(
    @SerialName("QueueUrl") public val queueUrl: String,
    @SerialName("Entries") public val entries: List<ChangeMessageVisibilityBatchRequestEntry>,
)

@Serializable
public data class ChangeMessageVisibilityBatchResultEntry(
    @SerialName("Id") public val id: String,
)

@Serializable
public data class ChangeMessageVisibilityBatchResponse(
    @SerialName("Successful")
    public val successful: List<ChangeMessageVisibilityBatchResultEntry> = emptyList(),
    @SerialName("Failed") public val failed: List<BatchResultErrorEntry> = emptyList(),
)

// -- GetQueueUrl ---------------------------------------------------------------------------------

/**
 * `GetQueueUrl`: resolves a queue *name* to the URL every other operation needs.
 *
 * In scope despite being a lookup rather than a message operation, because without it nothing else
 * in this module is reachable from configuration that names a queue rather than a URL — and
 * constructing the URL by string concatenation, which is the alternative, hardcodes the endpoint
 * format this module otherwise never assumes.
 *
 * @param queueOwnerAwsAccountId required for a queue owned by another account.
 */
@Serializable
public data class GetQueueUrlRequest(
    @SerialName("QueueName") public val queueName: String,
    @SerialName("QueueOwnerAWSAccountId") public val queueOwnerAwsAccountId: String? = null,
)

@Serializable
public data class GetQueueUrlResponse(
    @SerialName("QueueUrl") public val queueUrl: String? = null,
)

/** The shape of every SQS response that carries nothing. Internal — those operations return `Unit`. */
@Serializable
internal class EmptyResponse
