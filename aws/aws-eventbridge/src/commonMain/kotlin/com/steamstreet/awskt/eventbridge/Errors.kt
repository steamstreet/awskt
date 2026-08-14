package com.steamstreet.awskt.eventbridge

import com.steamstreet.awskt.core.AwsServiceException

/**
 * Base for every EventBridge failure.
 *
 * Extends `aws-core`'s [AwsServiceException] so every failure carries `code`, `statusCode`,
 * `requestId` and `extendedRequestId` — declared now rather than later because re-parenting a
 * public exception hierarchy after publication is an API revision.
 */
public open class EventBridgeException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
) : AwsServiceException(code, message, statusCode, requestId, extendedRequestId)

/** The named event bus does not exist. */
public class ResourceNotFoundException(message: String?, statusCode: Int, requestId: String? = null) :
    EventBridgeException("ResourceNotFoundException", message, statusCode, requestId)

/** The request was structurally rejected — a malformed `Detail`, or an entry over the size limit. */
public class ValidationException(message: String?, statusCode: Int, requestId: String? = null) :
    EventBridgeException("ValidationException", message, statusCode, requestId)

/**
 * The account's `PutEvents` rate was exceeded.
 *
 * Note this is the *whole-request* throttle. A batch that is merely partially rejected does not
 * raise anything at all — it returns HTTP 200 with per-entry `ErrorCode`s in the body. See
 * [PutEventsResponse].
 */
public class ThrottlingException(message: String?, statusCode: Int, requestId: String? = null) :
    EventBridgeException("ThrottlingException", message, statusCode, requestId)

/**
 * A `PutEvents` batch that [putEventsAll] could not get published in full.
 *
 * The code is the synthetic `"PutEventsPartialFailure"` and the status is **200**, because that is
 * literally what happened: EventBridge answered 200 and reported the failures per entry in the
 * body. It mirrors how `aws-dynamodb`'s batch helpers raise a synthetic-code `DynamoDbException`
 * for `UnprocessedKeys`/`UnprocessedItems` that outlived their resubmission budget.
 *
 * ### Read this before republishing
 *
 * **[succeeded] entries have been published, and there is no way to take them back.** `PutEvents`
 * carries no request token and no rollback — see [EventBridgeApi.putEvents]'s `NOT_IDEMPOTENT`
 * KDoc — so republishing the original batch delivers every entry in [succeeded] a *second* time,
 * and every rule targeting them fires twice. That is why this exception carries the partition
 * rather than a count: the safe retry is the [failed] entries alone, and it can only be assembled
 * if the failures are named.
 *
 * @property succeeded every entry that was accepted, in request order, each with the `EventId`
 *   EventBridge assigned it. Includes entries from earlier chunks and earlier rounds.
 * @property failed every entry that was **not** published, paired with the last result entry seen
 *   for it — the terminal error that stopped the batch, or the retryable error that was still
 *   outstanding when the round or backoff budget ran out. Request entries are the caller's own
 *   objects, so this list can be handed straight back to [putEventsAll].
 */
public class PutEventsPartialFailureException(
    public val succeeded: List<PutEventsResultEntry>,
    public val failed: List<Pair<PutEventsEntry, PutEventsResultEntry>>,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
) : EventBridgeException("PutEventsPartialFailure", message, statusCode, requestId)

/**
 * Maps `aws-core`'s protocol-level exception onto this module's hierarchy.
 *
 * Unknown codes fall through to [EventBridgeException] rather than being swallowed, so an
 * operation added downstream through the extension seam still gets a useful typed failure without
 * registering anything.
 */
internal inline fun <T> mapErrors(block: () -> T): T = try {
    block()
} catch (e: AwsServiceException) {
    throw when (e.code) {
        "ResourceNotFoundException" -> ResourceNotFoundException(e.message, e.statusCode, e.requestId)
        "ValidationException" -> ValidationException(e.message, e.statusCode, e.requestId)
        "ThrottlingException" -> ThrottlingException(e.message, e.statusCode, e.requestId)
        else -> EventBridgeException(e.code, e.message, e.statusCode, e.requestId, e.extendedRequestId)
    }
}
