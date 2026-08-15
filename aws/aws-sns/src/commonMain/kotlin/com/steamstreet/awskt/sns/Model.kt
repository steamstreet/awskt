package com.steamstreet.awskt.sns

/**
 * Request and response types for SNS's data plane.
 *
 * ### Nothing here is `@Serializable`
 *
 * The rule `aws-s3` states for its own model applies here for the same reason: SNS is not a JSON
 * protocol. Requests are flattened into form fields by `Wire.kt` and responses are read out of XML
 * by the same file. A `@SerialName` in this file would be decoration that never runs.
 *
 * ### Nothing that carries a `ByteArray` is a `data class`
 *
 * Exactly one type does — [MessageAttributeValue], whose `BinaryValue` is a blob — and it gets
 * hand-written `equals`/`hashCode` over `contentEquals`/`contentHashCode`, plus a `toString()` that
 * reports the size rather than the bytes.
 */

/**
 * A message attribute: caller-defined metadata carried alongside the message.
 *
 * **Not a data class** — it carries bytes.
 *
 * Worth knowing what these are actually *for*, because it is not documentation: SNS subscription
 * **filter policies** match on message attributes, not on the message body. An attribute is
 * therefore the difference between a subscriber receiving a message and SNS discarding it before
 * delivery — and an attribute that is merely informational costs nothing, while one the filter
 * policy expects and does not find silently drops the message.
 *
 * @property dataType `String`, `Number`, `Binary`, or one of those with a custom suffix
 *   (`Number.float`). Required, and it must agree with which value field is populated.
 */
public class MessageAttributeValue(
    public val dataType: String,
    public val stringValue: String? = null,
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

    override fun toString(): String =
        "MessageAttributeValue(dataType=$dataType, stringValue=$stringValue" +
            (binaryValue?.let { ", binaryValue=${it.size} bytes" } ?: "") + ")"
}

/**
 * `Publish`.
 *
 * ### Exactly one destination
 *
 * [topicArn], [targetArn] and [phoneNumber] are three ways to name where the message goes and
 * **exactly one may be set**. This is checked before anything is sent — see [Sns.publish] — because
 * SNS's own error for the mistake names a parameter rather than the conflict.
 *
 * @param message the payload. At most **256 KiB** across the message and all its attributes. When
 *   [messageStructure] is `"json"` this must itself be a JSON object with a `"default"` key, and
 *   optionally a key per protocol (`"email"`, `"sms"`, `"https"`, …) — that is how one publish
 *   delivers different text to different subscriber types. A malformed structure here is an
 *   `InvalidParameterException` naming `Message`.
 * @param subject used only by email subscriptions. ASCII, at most 100 characters, no newlines.
 * @param messageStructure set to `"json"` to use per-protocol payloads; leave null for a single
 *   payload delivered to everyone.
 * @param messageDeduplicationId FIFO topics only, and the field that makes this call safe to
 *   retry — see [Sns.publish].
 * @param messageGroupId required on a FIFO topic. Messages in a group are delivered in order.
 */
public class PublishRequest(
    public val message: String,
    public val topicArn: String? = null,
    public val targetArn: String? = null,
    public val phoneNumber: String? = null,
    public val subject: String? = null,
    public val messageStructure: String? = null,
    public val messageAttributes: Map<String, MessageAttributeValue>? = null,
    public val messageDeduplicationId: String? = null,
    public val messageGroupId: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is PublishRequest &&
                message == other.message &&
                topicArn == other.topicArn &&
                targetArn == other.targetArn &&
                phoneNumber == other.phoneNumber &&
                subject == other.subject &&
                messageStructure == other.messageStructure &&
                messageAttributes == other.messageAttributes &&
                messageDeduplicationId == other.messageDeduplicationId &&
                messageGroupId == other.messageGroupId
            )

    override fun hashCode(): Int {
        var result = message.hashCode()
        result = 31 * result + (topicArn?.hashCode() ?: 0)
        result = 31 * result + (targetArn?.hashCode() ?: 0)
        result = 31 * result + (phoneNumber?.hashCode() ?: 0)
        result = 31 * result + (messageDeduplicationId?.hashCode() ?: 0)
        return result
    }

    /**
     * Reports the message's size, never its content.
     *
     * SNS payloads are not secrets in the way a Secrets Manager value is, but they are routinely
     * customer data — an order, an email address, a phone number — and [phoneNumber] is *always*
     * personal data. A published message that lands in CloudWatch Logs because somebody logged the
     * request is a privacy incident, not a debugging convenience.
     */
    override fun toString(): String =
        "PublishRequest(topicArn=$topicArn, targetArn=$targetArn, " +
            "phoneNumber=${if (phoneNumber != null) "<redacted>" else "null"}, " +
            "subject=$subject, message=${message.length} chars, " +
            "messageAttributes=${messageAttributes?.keys})"
}

/** `Publish`'s result. */
public data class PublishResponse(
    public val messageId: String? = null,
    /** FIFO topics only. */
    public val sequenceNumber: String? = null,
)

/**
 * One entry in a `PublishBatch`.
 *
 * @param id **unique within the batch**, and meaningful only within it — it pairs a result back to
 *   a request and is not the published message's id.
 */
public class PublishBatchRequestEntry(
    public val id: String,
    public val message: String,
    public val subject: String? = null,
    public val messageStructure: String? = null,
    public val messageAttributes: Map<String, MessageAttributeValue>? = null,
    public val messageDeduplicationId: String? = null,
    public val messageGroupId: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is PublishBatchRequestEntry &&
                id == other.id &&
                message == other.message &&
                subject == other.subject &&
                messageStructure == other.messageStructure &&
                messageAttributes == other.messageAttributes &&
                messageDeduplicationId == other.messageDeduplicationId &&
                messageGroupId == other.messageGroupId
            )

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + (messageDeduplicationId?.hashCode() ?: 0)
        return result
    }

    /** Reports the message's size, never its content — see [PublishRequest.toString]. */
    override fun toString(): String =
        "PublishBatchRequestEntry(id=$id, subject=$subject, message=${message.length} chars)"
}

/** One entry's success in a `PublishBatch`, positionally unrelated to the request — pair on [id]. */
public data class PublishBatchResultEntry(
    public val id: String,
    public val messageId: String? = null,
    public val sequenceNumber: String? = null,
)

/**
 * One entry's failure in a `PublishBatch`.
 *
 * @property senderFault **the field that decides whether a retry is pointless.** True means the
 *   entry itself is at fault and resubmitting identical bytes fails identically; false means SNS
 *   failed transiently and the entry deserves another attempt. [publishAll] partitions on exactly
 *   this, the same way `aws-sqs`'s batch helpers do.
 */
public data class BatchResultErrorEntry(
    public val id: String,
    public val senderFault: Boolean = false,
    public val code: String? = null,
    public val message: String? = null,
)

/**
 * `PublishBatch`'s result.
 *
 * **[failed] can be non-empty on an HTTP 200** — the same trap as EventBridge's `PutEvents`, Secrets
 * Manager's `BatchGetSecretValue` and SQS's `SendMessageBatch`. See [publishAll].
 */
public data class PublishBatchResponse(
    public val successful: List<PublishBatchResultEntry> = emptyList(),
    public val failed: List<BatchResultErrorEntry> = emptyList(),
)
