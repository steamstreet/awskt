package com.steamstreet.awskt.kms

import com.steamstreet.awskt.core.AwsServiceException

/**
 * Base for every KMS failure.
 *
 * Extends `aws-core`'s [AwsServiceException] so every failure carries `code`, `statusCode`,
 * `requestId` and `extendedRequestId` — declared now rather than later because re-parenting a
 * public exception hierarchy after publication is an API revision.
 *
 * [cause] is the transport-level exception this was rebuilt from, and it is what carries the stack
 * trace through `aws-core`'s send and retry loop. Without it the failure appears to originate at
 * [mapErrors].
 */
public open class KmsException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : AwsServiceException(code, message, statusCode, requestId, extendedRequestId, cause)

/**
 * The key, alias or grant named in the request does not exist.
 *
 * Note the name: KMS says `NotFoundException`, **not** the `ResourceNotFoundException` that
 * EventBridge, DynamoDB and Secrets Manager all use. That matters beyond spelling — `aws-core`'s
 * `NEVER_RETRY_CODES` lists `ResourceNotFoundException` and therefore does not match this one. It
 * is not retried anyway, because KMS answers 400 and no status-based rule fires, but a reader
 * comparing the two modules should know the protection is coming from a different place here.
 */
public class NotFoundException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KmsException("NotFoundException", message, statusCode, requestId, extendedRequestId, cause)

/** The key exists and is disabled. Enabling it makes the same request succeed; retrying will not. */
public class DisabledException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KmsException("DisabledException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The key is not available right now — a transient condition, and one AWS documents as retryable.
 *
 * The retry comes from the **status code**, not the error code: KMS answers 500 here, which
 * `aws-core`'s `KNOWN_STATUS_CODES` classifies as `TRANSIENT`. `KeyUnavailableException` is not in
 * `KNOWN_ERROR_TYPES`, and does not need to be.
 */
public class KeyUnavailableException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KmsException("KeyUnavailableException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The key is in a state — `PendingDeletion`, `PendingImport`, `Unavailable` — that forbids the
 * operation.
 *
 * Distinct from [DisabledException] and worth distinguishing at the call site: a key pending
 * deletion is a *scheduled outage of the data encrypted under it*, and treating it as a generic
 * failure means the alarm fires when the deletion completes rather than during the waiting period,
 * which is the only window in which it can be cancelled.
 */
public class KmsInvalidStateException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KmsException("KMSInvalidStateException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The ciphertext is corrupt, truncated, or was not produced by KMS.
 *
 * **Also what a mismatched `EncryptionContext` looks like.** KMS does not distinguish "these bytes
 * are damaged" from "the additional authenticated data you supplied is not the data this ciphertext
 * was sealed with", because both are the same AEAD tag failure. So the first thing to check when
 * this appears on a blob known to be intact is that the [DecryptRequest.encryptionContext] matches
 * the [EncryptRequest.encryptionContext] exactly — same keys, same values, same case.
 */
public class InvalidCiphertextException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KmsException("InvalidCiphertextException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The key cannot do what was asked: an `ENCRYPT_DECRYPT` key asked to sign, a `SIGN_VERIFY` key
 * asked to generate a data key, or an algorithm the key's spec does not support.
 */
public class InvalidKeyUsageException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KmsException("InvalidKeyUsageException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The named key is not the key that encrypted the ciphertext.
 *
 * This is the error that naming [DecryptRequest.keyId] buys, and the reason that KDoc argues for
 * setting it. Without a key id the same request succeeds under whatever key the ciphertext points
 * at; with one, a substituted blob fails here.
 */
public class IncorrectKeyException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KmsException("IncorrectKeyException", message, statusCode, requestId, extendedRequestId, cause)

/** A grant token was malformed or has expired. Grants take up to five minutes to become eventually consistent. */
public class InvalidGrantTokenException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KmsException("InvalidGrantTokenException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * **The signature did not verify.**
 *
 * This is the single most surprising thing about KMS's data plane and the reason [Kms.verify] has
 * the KDoc it does: a bad signature is an *error response*, not a [VerifyResponse] with
 * `SignatureValid: false`. A caller that writes `if (kms.verify(request).signatureValid)` has
 * written a check that can only ever be true, and whose false branch is an uncaught exception
 * propagating out of the request handler.
 *
 * [verifySignature] exists to make the correct shape the easy one. Reach for this type directly
 * only when a failed verification needs to be told apart from a *different* failure — an expired
 * grant, a disabled key — which is exactly when the boolean is not enough.
 */
public class KmsInvalidSignatureException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KmsException("KMSInvalidSignatureException", message, statusCode, requestId, extendedRequestId, cause)

/** A fault inside KMS. Answered with a 500, so `aws-core` has already retried it to exhaustion. */
public class KmsInternalException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KmsException("KMSInternalException", message, statusCode, requestId, extendedRequestId, cause)

/** KMS could not reach a service it depends on, typically CloudHSM. Retried by status, like [KmsInternalException]. */
public class DependencyTimeoutException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KmsException("DependencyTimeoutException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * An account quota was exceeded.
 *
 * ### This one is retried, and for KMS that is usually wrong
 *
 * `LimitExceededException` is in `aws-core`'s `KNOWN_ERROR_TYPES` as `THROTTLING`, ported from the
 * AWS SDK's shared table. For the services that table was written against it means "you are going
 * too fast". For KMS it means "you have too many keys, aliases or grants" — a standing condition
 * that no amount of backoff clears, so the call spends the full throttling budget (four attempts,
 * seconds of sleep) arriving at the same answer.
 *
 * Left alone deliberately. Special-casing it means either editing a table shared by every service
 * on this transport, or adding a per-service retry override that exists solely for this code —
 * both of which cost more than the seconds they save on a request that is failing anyway. KMS's
 * actual rate limit surfaces as `ThrottlingException`, which the same table paces correctly, so the
 * common case is right and this is the documented exception to it.
 */
public class LimitExceededException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KmsException("LimitExceededException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The request carried `DryRun: true` and **would have succeeded**.
 *
 * A success reported as an exception, which is KMS's design rather than this client's. Nothing was
 * encrypted, decrypted or signed; the call proved that the caller's permissions, key state and
 * encryption context are all sufficient. Catching it is the way to assert authorization at startup
 * without generating key material or a CloudTrail entry that looks like real use.
 */
public class DryRunOperationException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : KmsException("DryRunOperationException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * Maps `aws-core`'s protocol-level exception onto this module's hierarchy.
 *
 * Unknown codes fall through to [KmsException] rather than being swallowed, so an operation added
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
    throw when (e.code) {
        "NotFoundException" ->
            NotFoundException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "DisabledException" ->
            DisabledException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "KeyUnavailableException" ->
            KeyUnavailableException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "KMSInvalidStateException" ->
            KmsInvalidStateException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "InvalidCiphertextException" ->
            InvalidCiphertextException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "InvalidKeyUsageException" ->
            InvalidKeyUsageException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "IncorrectKeyException" ->
            IncorrectKeyException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "InvalidGrantTokenException" ->
            InvalidGrantTokenException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "KMSInvalidSignatureException" ->
            KmsInvalidSignatureException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "KMSInternalException" ->
            KmsInternalException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "DependencyTimeoutException" ->
            DependencyTimeoutException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "LimitExceededException" ->
            LimitExceededException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "DryRunOperationException" ->
            DryRunOperationException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        else -> KmsException(e.code, e.message, e.statusCode, e.requestId, e.extendedRequestId, e)
    }
}
