package com.steamstreet.awskt.sns

import com.steamstreet.awskt.core.AwsServiceException

/**
 * Base for every SNS failure.
 *
 * Extends `aws-core`'s [AwsServiceException] so every failure carries `code`, `statusCode`,
 * `requestId` and `extendedRequestId` — declared now rather than later because re-parenting a
 * public exception hierarchy after publication is an API revision.
 *
 * ### The codes are the query protocol's, and they are not the Smithy shape names
 *
 * Unlike every AWS-JSON module in this library, where the error code and the modelled shape name
 * agree, the query protocol assigns each error its own wire code: the shape SNS's own SDK calls
 * `NotFoundException` arrives as `<Code>NotFound</Code>`, and `InvalidParameterValueException`
 * arrives as `ParameterValueInvalid` — note the words are *reversed*, which is the kind of detail
 * that is only ever discovered by reading the wire. The strings below were taken from the AWS
 * SDK's own generated deserializer rather than from documentation, for that reason.
 */
public open class SnsException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : AwsServiceException(code, message, statusCode, requestId, extendedRequestId, cause)

/**
 * The topic, endpoint or platform application does not exist.
 *
 * Wire code `NotFound` — *not* `NotFoundException`, and not the `ResourceNotFoundException` that
 * DynamoDB, EventBridge and Secrets Manager use. Worth knowing because `aws-core`'s
 * `NEVER_RETRY_CODES` lists the latter and therefore does not match this one; it is not retried
 * anyway, because SNS answers 404 and no status rule fires.
 *
 * On `Publish` this nearly always means a **region mismatch** rather than a deleted topic: a topic
 * ARN names its region, and publishing it through a client configured for another region reaches a
 * service where that topic genuinely does not exist.
 */
public class NotFoundException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SnsException("NotFound", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The principal is not authorized for this action.
 *
 * Wire code `AuthorizationError`. Distinct from an IAM denial at the credential level: this is
 * usually the *topic policy* refusing a cross-account publish, which is invisible in the caller's
 * own IAM role and is the first thing to check when the role plainly has `sns:Publish`.
 */
public class AuthorizationErrorException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SnsException("AuthorizationError", message, statusCode, requestId, extendedRequestId, cause)

/** A parameter is malformed or missing. Wire code `InvalidParameter`. */
public class InvalidParameterException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SnsException("InvalidParameter", message, statusCode, requestId, extendedRequestId, cause)

/**
 * A parameter's *value* is not acceptable.
 *
 * Wire code `ParameterValueInvalid` — the words reversed relative to the shape name
 * `InvalidParameterValue`. See the [SnsException] KDoc.
 */
public class InvalidParameterValueException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SnsException("ParameterValueInvalid", message, statusCode, requestId, extendedRequestId, cause)

/**
 * A mobile push endpoint is disabled.
 *
 * Wire code `EndpointDisabled`. SNS disables an endpoint when the device token stops being valid —
 * the app was uninstalled, or the push service rejected it — so this is a *durable* condition that
 * retrying never clears. The correct handling is to stop publishing to that endpoint and re-register
 * the device, not to back off.
 */
public class EndpointDisabledException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SnsException("EndpointDisabled", message, statusCode, requestId, extendedRequestId, cause)

/** The platform application is disabled. Wire code `PlatformApplicationDisabled`. */
public class PlatformApplicationDisabledException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SnsException(
    "PlatformApplicationDisabled", message, statusCode, requestId, extendedRequestId, cause,
)

/** A fault inside SNS. Answered with a 500, so `aws-core` has already retried it to exhaustion. */
public class InternalErrorException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SnsException("InternalError", message, statusCode, requestId, extendedRequestId, cause)

/** The request signature or security token was rejected. Wire code `InvalidSecurity`. */
public class InvalidSecurityException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SnsException("InvalidSecurity", message, statusCode, requestId, extendedRequestId, cause)

/** The account's publish rate was exceeded. Wire code `Throttled`, paced by `aws-core`'s backoff. */
public class ThrottledException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SnsException("Throttled", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The KMS key protecting an encrypted topic refused, or is unavailable.
 *
 * One type for the whole `KMS*` family — `KMSAccessDenied`, `KMSDisabled`, `KMSInvalidState`,
 * `KMSNotFound`, `KMSOptInRequired`, `KMSThrottling` — because a caller's response to all six is
 * the same: this topic's encryption key is not usable by this principal right now. [code] still
 * carries which one. Note SNS spells the prefix `KMS` in capitals where SQS spells it `Kms`; the
 * two services genuinely disagree, and each module matches its own service.
 *
 * `aws-kms`'s exceptions are deliberately not reused: these arrive as SNS errors with SNS's codes.
 */
public class TopicEncryptionKeyException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SnsException(code, message, statusCode, requestId, extendedRequestId, cause)

/** More than 10 entries in one `PublishBatch`. Wire code `TooManyEntriesInBatchRequest`. */
public class TooManyEntriesInBatchRequestException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SnsException(
    "TooManyEntriesInBatchRequest", message, statusCode, requestId, extendedRequestId, cause,
)

/** A `PublishBatch` with no entries. Wire code `EmptyBatchRequest`. */
public class EmptyBatchRequestException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SnsException("EmptyBatchRequest", message, statusCode, requestId, extendedRequestId, cause)

/** Two entries in one batch shared an `Id`. Wire code `BatchEntryIdsNotDistinct`. */
public class BatchEntryIdsNotDistinctException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SnsException("BatchEntryIdsNotDistinct", message, statusCode, requestId, extendedRequestId, cause)

/** A batch entry's `Id` is not a valid identifier. Wire code `InvalidBatchEntryId`. */
public class InvalidBatchEntryIdException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SnsException("InvalidBatchEntryId", message, statusCode, requestId, extendedRequestId, cause)

/** The batch's total payload exceeded SNS's limit. Wire code `BatchRequestTooLong`. */
public class BatchRequestTooLongException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SnsException("BatchRequestTooLong", message, statusCode, requestId, extendedRequestId, cause)

/**
 * A `PublishBatch` that [publishAll] could not publish in full.
 *
 * The code is a synthetic `"PublishBatchPartialFailure"` and the status is **200**, because that is
 * what happened: SNS answered 200 and reported the failures per entry in the body. It mirrors
 * `aws-eventbridge`'s `PutEventsPartialFailureException` exactly, and for the same underlying
 * reason.
 *
 * **[succeeded] entries have been published and cannot be recalled.** SNS fans a message out to
 * every subscriber the moment it accepts it; there is no delete, no visibility timeout and no
 * rollback. Resubmitting the original list delivers those entries a second time to every
 * subscriber. Resubmit `failed.map { it.first }`.
 *
 * @property succeeded every entry SNS accepted, across all chunks and rounds.
 * @property failed every entry it did not, paired with SNS's last report for it. The request
 *   entries are the caller's own objects and can be handed straight back to [publishAll].
 */
public class PublishBatchPartialFailureException(
    public val succeeded: List<PublishBatchResultEntry>,
    public val failed: List<Pair<PublishBatchRequestEntry, BatchResultErrorEntry>>,
    message: String?,
    requestId: String? = null,
) : SnsException("PublishBatchPartialFailure", message, 200, requestId)

/** The `KMS*` codes that all collapse onto [TopicEncryptionKeyException]. */
private val KMS_CODES: Set<String> = setOf(
    "KMSAccessDenied",
    "KMSDisabled",
    "KMSInvalidState",
    "KMSNotFound",
    "KMSOptInRequired",
    "KMSThrottling",
)

/**
 * Maps `aws-core`'s protocol-level exception onto this module's hierarchy.
 *
 * Unknown codes fall through to [SnsException] rather than being swallowed, so an operation added
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
        "NotFound" ->
            NotFoundException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "AuthorizationError" ->
            AuthorizationErrorException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "InvalidParameter" ->
            InvalidParameterException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "ParameterValueInvalid" ->
            InvalidParameterValueException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "EndpointDisabled" ->
            EndpointDisabledException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "PlatformApplicationDisabled" ->
            PlatformApplicationDisabledException(
                e.message, e.statusCode, e.requestId, e.extendedRequestId, e,
            )

        "InternalError" ->
            InternalErrorException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "InvalidSecurity" ->
            InvalidSecurityException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "Throttled" ->
            ThrottledException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "TooManyEntriesInBatchRequest" ->
            TooManyEntriesInBatchRequestException(
                e.message, e.statusCode, e.requestId, e.extendedRequestId, e,
            )

        "EmptyBatchRequest" ->
            EmptyBatchRequestException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "BatchEntryIdsNotDistinct" ->
            BatchEntryIdsNotDistinctException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "InvalidBatchEntryId" ->
            InvalidBatchEntryIdException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "BatchRequestTooLong" ->
            BatchRequestTooLongException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        in KMS_CODES ->
            TopicEncryptionKeyException(code, e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        else -> SnsException(e.code, e.message, e.statusCode, e.requestId, e.extendedRequestId, e)
    }
}
