package com.steamstreet.awskt.kinesis

import com.steamstreet.awskt.core.AwsServiceException

/**
 * Base for every Kinesis failure.
 *
 * Extends `aws-core`'s [AwsServiceException] so every failure carries `code`, `statusCode`,
 * `requestId` and `extendedRequestId` — declared now rather than later because re-parenting a
 * public exception hierarchy after publication is an API revision.
 */
public open class KinesisException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : AwsServiceException(code, message, statusCode, requestId, extendedRequestId, cause)

/**
 * The stream, shard or iterator does not exist.
 *
 * On a call that named the stream by **name** rather than ARN, suspect a cross-account read before
 * suspecting a typo: a bare name resolves against the calling account, so a consumer reading a
 * producer's stream in another account is told the stream does not exist rather than that it lacks
 * access.
 */
public class ResourceNotFoundException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KinesisException("ResourceNotFoundException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The read or write exceeded the shard's provisioned throughput.
 *
 * The one Kinesis error that is routine rather than exceptional. A shard allows 1 MB/s or 1,000
 * records/s in, and 2 MB/s out shared across all consumers, so a hot partition key produces this
 * under otherwise normal load.
 *
 * `aws-core`'s retry layer treats it as retryable and backs off. What it cannot help with is the
 * *per-record* form: `PutRecords` reports throttled entries inside an HTTP 200, where no retry
 * layer sees them at all — see [PutRecordsResponse] and prefer [Kinesis.putRecordsAll].
 */
public class ProvisionedThroughputExceededException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KinesisException(
    "ProvisionedThroughputExceededException", message, statusCode, requestId, extendedRequestId, cause,
)

/**
 * The shard iterator has expired.
 *
 * Iterators live **5 minutes** from the moment they are returned, not from first use. A consumer
 * that fetches an iterator, does slow work, and then reads gets this — and the fix is to hold the
 * `NextShardIterator` from each [GetRecordsResponse] and keep reading, rather than re-deriving an
 * iterator from a stored sequence number on every poll.
 *
 * Recovering means calling `GetShardIterator` again with `AFTER_SEQUENCE_NUMBER` and the last
 * sequence number actually processed. Recovering with `LATEST` instead silently skips everything
 * written in between.
 */
public class ExpiredIteratorException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KinesisException("ExpiredIteratorException", message, statusCode, requestId, extendedRequestId, cause)

/** A parameter was missing, malformed, or combined with one it excludes. */
public class InvalidArgumentException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KinesisException("InvalidArgumentException", message, statusCode, requestId, extendedRequestId, cause)

/** The account's shard or stream limit would be exceeded. Not retryable within a call. */
public class LimitExceededException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KinesisException("LimitExceededException", message, statusCode, requestId, extendedRequestId, cause)

/** The stream is not `ACTIVE` — usually mid-resharding or still being created. */
public class ResourceInUseException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KinesisException("ResourceInUseException", message, statusCode, requestId, extendedRequestId, cause)

/** KMS refused or could not perform the decrypt for a server-side-encrypted stream. */
public class KmsAccessDeniedException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KinesisException("KMSAccessDeniedException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * Maps `aws-core`'s generic service exception onto the typed hierarchy above.
 *
 * Kinesis reports its error codes with the `Exception` suffix included
 * (`ResourceNotFoundException`, not `ResourceNotFound`), unlike SQS's JSON dialect. Both bare and
 * suffixed forms are matched so a future change in either direction does not silently fall through
 * to the base type.
 */
internal inline fun <T> mapErrors(block: () -> T): T = try {
    block()
} catch (e: AwsServiceException) {
    throw when (e.code) {
        "ResourceNotFoundException", "ResourceNotFound" ->
            ResourceNotFoundException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "ProvisionedThroughputExceededException", "ProvisionedThroughputExceeded" ->
            ProvisionedThroughputExceededException(
                e.message, e.statusCode, e.requestId, e.extendedRequestId, e,
            )

        "ExpiredIteratorException", "ExpiredIterator" ->
            ExpiredIteratorException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "InvalidArgumentException", "InvalidArgument" ->
            InvalidArgumentException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "LimitExceededException", "LimitExceeded" ->
            LimitExceededException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "ResourceInUseException", "ResourceInUse" ->
            ResourceInUseException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "KMSAccessDeniedException", "KMSAccessDenied" ->
            KmsAccessDeniedException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        else -> KinesisException(
            e.code, e.message, e.statusCode, e.requestId, e.extendedRequestId, e,
        )
    }
}
