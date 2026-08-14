package com.steamstreet.awskt.s3

import com.steamstreet.awskt.core.AwsServiceException

/**
 * Base for every S3 failure.
 *
 * Extends `aws-core`'s [AwsServiceException] so every failure carries `code`, `statusCode`,
 * `requestId` and `extendedRequestId`. **Both request ids matter for S3 specifically**: AWS support
 * asks for `x-amz-id-2` as well as `x-amz-request-id`, and an exception that dropped it makes a
 * support case unanswerable.
 */
public open class S3Exception(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
) : AwsServiceException(code, message, statusCode, requestId, extendedRequestId)

/** The key does not exist. */
public class NoSuchKeyException(message: String?, statusCode: Int, requestId: String?, extendedRequestId: String?) :
    S3Exception("NoSuchKey", message, statusCode, requestId, extendedRequestId)

/** The bucket does not exist. */
public class NoSuchBucketException(message: String?, statusCode: Int, requestId: String?, extendedRequestId: String?) :
    S3Exception("NoSuchBucket", message, statusCode, requestId, extendedRequestId)

/**
 * Refused. Also the code S3 returns for `HeadersNotSigned` — an `x-amz-*` header sent with a
 * presigned URL that did not sign it.
 */
public class AccessDeniedException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String?,
    extendedRequestId: String?,
) : S3Exception(code, message, statusCode, requestId, extendedRequestId)

/** The object is in GLACIER or DEEP_ARCHIVE and must be restored before it can be read. */
public class InvalidObjectStateException(
    message: String?,
    statusCode: Int,
    requestId: String?,
    extendedRequestId: String?,
) : S3Exception("InvalidObjectState", message, statusCode, requestId, extendedRequestId)

/** An `If-Match` / `If-Unmodified-Since` precondition evaluated false. */
public class PreconditionFailedException(
    message: String?,
    statusCode: Int,
    requestId: String?,
    extendedRequestId: String?,
) : S3Exception("PreconditionFailed", message, statusCode, requestId, extendedRequestId)

/** A 304 from `If-None-Match` / `If-Modified-Since`. Carries **no body** by protocol. */
public class NotModifiedException(statusCode: Int, requestId: String?, extendedRequestId: String?) :
    S3Exception("NotModified", "Not modified", statusCode, requestId, extendedRequestId)

/**
 * S3's throttle. Classified **Throttling**, not Transient — it backs off on the 1000 ms base rather
 * than the 25 ms one, which is the difference between shedding load and adding to it.
 */
public class SlowDownException(message: String?, statusCode: Int, requestId: String?, extendedRequestId: String?) :
    S3Exception("SlowDown", message, statusCode, requestId, extendedRequestId)

/**
 * The bucket lives in another region. **Never retried** — a replay goes to the same wrong endpoint
 * and fails identically, so retrying only delays a clear diagnosis.
 */
public class PermanentRedirectException(
    message: String?,
    statusCode: Int,
    requestId: String?,
    extendedRequestId: String?,
) : S3Exception("PermanentRedirect", message, statusCode, requestId, extendedRequestId)

/** A concurrent operation conflicted with a conditional write. Documented by AWS as retryable. */
public class ConditionalRequestConflictException(
    message: String?,
    statusCode: Int,
    requestId: String?,
    extendedRequestId: String?,
) : S3Exception("ConditionalRequestConflict", message, statusCode, requestId, extendedRequestId)

/**
 * The response body did not match the length S3 declared.
 *
 * **This exists because TLS gives per-record integrity, not stream completeness.** A connection
 * dying mid-body yields a well-formed, silently short `ByteArray`; whether the engine notices is
 * engine-dependent, and this library runs on CIO for the JVM and Curl for native — the very engine
 * whose response-body handling forced the Ktor bump. Treating "the engine will throw" as given
 * would leave silent truncation as the failure mode on `linuxArm64`, the one target that cannot run
 * tests locally.
 *
 * Classified retryable: a truncated download is exactly the kind of failure a replay fixes.
 */
public class S3IncompleteDownloadException(
    public val expectedBytes: Long,
    public val actualBytes: Long,
    requestId: String?,
    extendedRequestId: String?,
) : S3Exception(
    "IncompleteBody",
    "Download was truncated: expected $expectedBytes bytes, received $actualBytes",
    200,
    requestId,
    extendedRequestId,
)

/** A payload exceeded the configured in-memory ceiling. */
public class S3PayloadTooLargeException(message: String) : Exception(message)

/**
 * Maps `aws-core`'s protocol-level exception onto this module's hierarchy.
 *
 * Unknown codes fall through to [S3Exception] rather than being swallowed.
 */
internal inline fun <T> mapS3Errors(block: () -> T): T = try {
    block()
} catch (e: S3Exception) {
    // Already one of ours, so there is nothing to map — and mapping it anyway would *lose*
    // information. [S3IncompleteDownloadException] is raised by this module from inside `callRaw`'s
    // retry loop and comes back out through here; its code, `IncompleteBody`, matches no branch
    // below, so the catch-all would rebuild it as a bare [S3Exception] and drop `expectedBytes`,
    // `actualBytes` and the type the caller catches on.
    throw e
} catch (e: AwsServiceException) {
    val id = e.requestId
    val id2 = e.extendedRequestId
    throw when {
        e.code == "NoSuchKey" -> NoSuchKeyException(e.message, e.statusCode, id, id2)
        e.code == "NoSuchBucket" -> NoSuchBucketException(e.message, e.statusCode, id, id2)
        e.code == "AccessDenied" || e.code == "HeadersNotSigned" ->
            AccessDeniedException(e.code, e.message, e.statusCode, id, id2)

        e.code == "InvalidObjectState" -> InvalidObjectStateException(e.message, e.statusCode, id, id2)
        e.code == "PreconditionFailed" -> PreconditionFailedException(e.message, e.statusCode, id, id2)
        e.code == "SlowDown" -> SlowDownException(e.message, e.statusCode, id, id2)
        e.code == "PermanentRedirect" -> PermanentRedirectException(e.message, e.statusCode, id, id2)
        e.code == "ConditionalRequestConflict" ->
            ConditionalRequestConflictException(e.message, e.statusCode, id, id2)

        // HeadObject has no response body by protocol, so its errors classify from status alone —
        // AWS documents that the specific exception is not retrievable for HEAD.
        e.code == null && e.statusCode == 404 -> NoSuchKeyException(e.message, 404, id, id2)
        e.code == null && e.statusCode == 403 -> AccessDeniedException(null, e.message, 403, id, id2)
        e.code == null && e.statusCode == 412 -> PreconditionFailedException(e.message, 412, id, id2)
        e.statusCode == 304 -> NotModifiedException(304, id, id2)

        else -> S3Exception(e.code, e.message, e.statusCode, id, id2)
    }
}
