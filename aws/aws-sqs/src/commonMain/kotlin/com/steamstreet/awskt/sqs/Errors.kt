package com.steamstreet.awskt.sqs

import com.steamstreet.awskt.core.AwsServiceException

/**
 * Base for every SQS failure.
 *
 * Extends `aws-core`'s [AwsServiceException] so every failure carries `code`, `statusCode`,
 * `requestId` and `extendedRequestId` — declared now rather than later because re-parenting a
 * public exception hierarchy after publication is an API revision.
 *
 * ### The codes here are the JSON-protocol names, not the ones the SQS docs show
 *
 * This matters when searching. SQS's documentation, its IAM examples and a decade of Stack Overflow
 * answers name errors in the **query protocol's** vocabulary —
 * `AWS.SimpleQueueService.NonExistentQueue`, `AWS.SimpleQueueService.UnsupportedOperation`. The
 * JSON protocol reports the underlying Smithy shape instead: `QueueDoesNotExist`,
 * `UnsupportedOperation`. Both name the same condition.
 *
 * AWS keeps the old vocabulary reachable through an `awsQueryCompatible` mode, opted into with an
 * `x-amzn-query-mode: true` request header, which makes the service answer with an
 * `x-amzn-query-error` header carrying the legacy code. **This client does not send that header**,
 * and the choice is deliberate: query mode exists so that SDKs which already shipped the old codes
 * do not break their users, and this client has no such users. Sending it would mean carrying the
 * legacy vocabulary forever and teaching `aws-core`'s error parser a header it otherwise never
 * reads. Each typed exception below names its legacy equivalent so the search still lands.
 */
public open class SqsException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : AwsServiceException(code, message, statusCode, requestId, extendedRequestId, cause)

/**
 * The queue does not exist, or is not visible to this principal.
 *
 * Legacy code: `AWS.SimpleQueueService.NonExistentQueue`.
 *
 * The most common cause is not a typo but a **region mismatch**: a queue URL names its region in
 * the host, and this client sends to the region it resolved from configuration, so a URL from
 * `us-east-1` used by a client configured for `us-west-2` reaches the wrong service and is answered
 * with this. The URL in the request is the one to check first.
 */
public class QueueDoesNotExistException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SqsException("QueueDoesNotExist", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The queue was deleted within the last 60 seconds and its name cannot be reused yet.
 *
 * Legacy code: `AWS.SimpleQueueService.QueueDeletedRecently`. Genuinely transient, and genuinely
 * not worth retrying inside one call — 60 seconds is far beyond
 * `RetryConfig.maxTotalRetryDuration`'s 25-second default, so `aws-core` would exhaust its budget
 * and fail anyway. Wait and try again at a level that can afford to.
 */
public class QueueDeletedRecentlyException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SqsException("QueueDeletedRecently", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The receipt handle is malformed, or belongs to a different queue.
 *
 * Legacy code: `ReceiptHandleIsInvalid`. Distinct from [MessageNotInflightException]: this one
 * means the handle was never valid, that one means it has expired.
 */
public class ReceiptHandleIsInvalidException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SqsException("ReceiptHandleIsInvalid", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The message is not in flight — its visibility timeout lapsed before the handle was used.
 *
 * Legacy code: `AWS.SimpleQueueService.MessageNotInflight`. **This is the error that says a handler
 * is slower than its queue's visibility timeout**, and the message it refers to has already been
 * redelivered to somebody else. Extending the timeout with [Sqs.changeMessageVisibility] while
 * work is in progress is the fix; raising the queue's default timeout is the blunter one.
 */
public class MessageNotInflightException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SqsException("MessageNotInflight", message, statusCode, requestId, extendedRequestId, cause)

/** The body contains characters SQS does not accept. Legacy code: `InvalidMessageContents`. */
public class InvalidMessageContentsException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SqsException("InvalidMessageContents", message, statusCode, requestId, extendedRequestId, cause)

/** More than 10 entries in one batch. Legacy code: `AWS.SimpleQueueService.TooManyEntriesInBatchRequest`. */
public class TooManyEntriesInBatchRequestException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SqsException(
    "TooManyEntriesInBatchRequest", message, statusCode, requestId, extendedRequestId, cause,
)

/** A batch with no entries. Legacy code: `AWS.SimpleQueueService.EmptyBatchRequest`. */
public class EmptyBatchRequestException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SqsException("EmptyBatchRequest", message, statusCode, requestId, extendedRequestId, cause)

/** Two entries in one batch shared an `Id`. Legacy code: `AWS.SimpleQueueService.BatchEntryIdsNotDistinct`. */
public class BatchEntryIdsNotDistinctException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SqsException("BatchEntryIdsNotDistinct", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The batch's total payload exceeded 256 KiB.
 *
 * Legacy code: `AWS.SimpleQueueService.BatchRequestTooLong`. Note this is the **whole batch**, not
 * one entry — ten 30 KiB messages are fine, ten 30 KiB messages plus one 20 KiB message are not.
 * [sendMessagesAll] chunks by count and cannot see this coming; it surfaces here.
 */
public class BatchRequestTooLongException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SqsException("BatchRequestTooLong", message, statusCode, requestId, extendedRequestId, cause)

/** A batch entry's `Id` is not a valid identifier. Legacy code: `AWS.SimpleQueueService.InvalidBatchEntryId`. */
public class InvalidBatchEntryIdException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SqsException("InvalidBatchEntryId", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The operation is not valid for this queue type.
 *
 * Legacy code: `AWS.SimpleQueueService.UnsupportedOperation`. In this module it almost always means
 * a **FIFO field sent to a standard queue, or a FIFO field omitted from a FIFO queue** — a
 * `MessageGroupId` on a standard queue, or its absence on a `.fifo` one.
 */
public class UnsupportedQueueOperationException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SqsException("UnsupportedOperation", message, statusCode, requestId, extendedRequestId, cause)

/**
 * Too many in-flight messages, or too many requests.
 *
 * Legacy code: `OverLimit`. A standard queue allows 120,000 in-flight messages and a FIFO queue
 * 20,000; hitting the ceiling means messages are being received far faster than they are deleted,
 * which is a consumer problem rather than a queue one. Not in `aws-core`'s retryable table and
 * answered with a 4xx, so it is not retried — correctly, since backing off does not delete anything.
 */
public class OverLimitException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SqsException("OverLimit", message, statusCode, requestId, extendedRequestId, cause)

/** The account's request rate was exceeded. Paced by `aws-core`'s throttling backoff. */
public class RequestThrottledException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SqsException("RequestThrottled", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The KMS key protecting a server-side-encrypted queue refused, or is unavailable.
 *
 * One type for the whole `Kms*` family — `KmsAccessDenied`, `KmsDisabled`, `KmsInvalidKeyUsage`,
 * `KmsInvalidState`, `KmsNotFound`, `KmsOptInRequired`, `KmsThrottled` — because a caller's
 * response to all seven is identical: this queue's encryption key is not usable by this principal
 * right now, and no amount of message-level handling changes that. [code] still carries which one,
 * so the distinction is available without seven near-identical classes that nothing branches on.
 *
 * `aws-kms`'s exceptions are **not** reused here: these arrive as SQS errors with SQS's codes, and
 * typing them as KMS failures would suggest the caller can act on them through a KMS client.
 */
public class QueueEncryptionKeyException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SqsException(code, message, statusCode, requestId, extendedRequestId, cause)

/**
 * A `SendMessageBatch` that [sendMessagesAll] could not get onto the queue in full.
 *
 * The code is a synthetic `"SendMessageBatchPartialFailure"` and the status is **200**, because that is
 * what happened: SQS answered 200 and reported the failures per entry in the body. It mirrors
 * `aws-eventbridge`'s `PutEventsPartialFailureException` and
 * `aws-secretsmanager`'s `BatchGetSecretValuePartialFailureException`.
 *
 * ### Read this before retrying a send
 *
 * **[succeeded] entries are already on the queue and cannot be taken back.** Resubmitting the
 * original list delivers every one of them a second time, and on a standard queue every consumer
 * sees the duplicate. Resubmit `failed.map { it.first }` instead — which is why this carries the
 * partition rather than a count.
 *
 * For a *delete* batch the calculus is inverted and much kinder: re-deleting an already-deleted
 * message is harmless, so the whole original list is a safe retry. The failures still have to be
 * dealt with, because a message that was not deleted will be redelivered.
 *
 * @property succeeded every entry that was accepted, across all chunks and rounds.
 * @property failed every entry that was not, paired with SQS's last report for it. The request
 *   entries are the caller's own objects and can be handed straight back.
 */
public class SendMessageBatchPartialFailureException(
    public val succeeded: List<SendMessageBatchResultEntry>,
    public val failed: List<Pair<SendMessageBatchRequestEntry, BatchResultErrorEntry>>,
    message: String?,
    requestId: String? = null,
) : SqsException("SendMessageBatchPartialFailure", message, 200, requestId)

/**
 * A `DeleteMessageBatch` that [deleteMessagesAll] could not complete.
 *
 * The sibling of [SendMessageBatchPartialFailureException], and the *kinder* of the two: deleting
 * an already-deleted message is harmless, so unlike a send batch the entire original list is a safe
 * retry. What is not safe is ignoring it — a message that was not deleted becomes visible again
 * when its visibility timeout lapses and is processed a second time, which is how "we handled that
 * order twice" happens hours after the delete quietly failed.
 *
 * @property succeeded every entry SQS confirmed deleted, across all chunks and rounds.
 * @property failed every entry it did not, paired with SQS's last report for it.
 */
public class DeleteMessageBatchPartialFailureException(
    public val succeeded: List<DeleteMessageBatchResultEntry>,
    public val failed: List<Pair<DeleteMessageBatchRequestEntry, BatchResultErrorEntry>>,
    message: String?,
    requestId: String? = null,
) : SqsException("DeleteMessageBatchPartialFailure", message, 200, requestId)

/** The `Kms*` codes that all collapse onto [QueueEncryptionKeyException]. See that type for why. */
private val KMS_CODES: Set<String> = setOf(
    "KmsAccessDenied",
    "KmsDisabled",
    "KmsInvalidKeyUsage",
    "KmsInvalidState",
    "KmsNotFound",
    "KmsOptInRequired",
    "KmsThrottled",
)

/**
 * Maps `aws-core`'s protocol-level exception onto this module's hierarchy.
 *
 * Unknown codes fall through to [SqsException] rather than being swallowed, so an operation added
 * downstream through the extension seam still gets a useful typed failure without registering
 * anything.
 *
 * Every branch threads `cause = e` and both request ids. The typed exception is a *rebuild* rather
 * than a wrapper, so whatever is not carried across is destroyed here — including the stack trace
 * of the call that actually failed.
 */
internal inline fun <T> mapErrors(block: () -> T): T = try {
    block()
} catch (e: AwsServiceException) {
    throw when (val code = e.code) {
        "QueueDoesNotExist" ->
            QueueDoesNotExistException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "QueueDeletedRecently" ->
            QueueDeletedRecentlyException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "ReceiptHandleIsInvalid" ->
            ReceiptHandleIsInvalidException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "MessageNotInflight" ->
            MessageNotInflightException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "InvalidMessageContents" ->
            InvalidMessageContentsException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "TooManyEntriesInBatchRequest" ->
            TooManyEntriesInBatchRequestException(
                e.message, e.statusCode, e.requestId, e.extendedRequestId, e,
            )

        "EmptyBatchRequest" ->
            EmptyBatchRequestException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "BatchEntryIdsNotDistinct" ->
            BatchEntryIdsNotDistinctException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "BatchRequestTooLong" ->
            BatchRequestTooLongException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "InvalidBatchEntryId" ->
            InvalidBatchEntryIdException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "UnsupportedOperation" ->
            UnsupportedQueueOperationException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "OverLimit" ->
            OverLimitException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "RequestThrottled" ->
            RequestThrottledException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        in KMS_CODES ->
            QueueEncryptionKeyException(code, e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        else -> SqsException(e.code, e.message, e.statusCode, e.requestId, e.extendedRequestId, e)
    }
}
