package com.steamstreet.awskt.secretsmanager

import com.steamstreet.awskt.core.AwsServiceException

/**
 * Base for every Secrets Manager failure.
 *
 * Extends `aws-core`'s [AwsServiceException] so every failure carries `code`, `statusCode`,
 * `requestId` and `extendedRequestId` — declared now rather than later because re-parenting a
 * public exception hierarchy after publication is an API revision.
 *
 * [cause] is the transport-level exception this was rebuilt from, and it is what carries the stack
 * trace through `aws-core`'s send and retry loop. Without it the failure appears to originate at
 * [mapErrors].
 *
 * ### Nothing in this hierarchy ever holds a secret
 *
 * Worth stating because it is a property that has to be maintained rather than one that holds
 * itself. The messages come from AWS and name secrets by id, never by value; this module never
 * interpolates a [PutSecretValueRequest] or a [GetSecretValueResponse] into an exception message,
 * and the `toString()` on both redacts anyway. An exception is the one object in a program that is
 * *guaranteed* to be logged in full, so the invariant is worth more here than anywhere else.
 */
public open class SecretsManagerException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : AwsServiceException(code, message, statusCode, requestId, extendedRequestId, cause)

/**
 * The secret does not exist, or the version stage names no version.
 *
 * Indistinguishable from "exists, but this principal has no `secretsmanager:GetSecretValue`
 * permission on it" only when the resource policy denies `DescribeSecret` too — otherwise a denial
 * arrives as an `AccessDeniedException` and falls through to [SecretsManagerException]. Treating a
 * not-found as a configuration error rather than a transient one is safe: `aws-core` lists
 * `ResourceNotFoundException` in `NEVER_RETRY_CODES`, so this is never replayed.
 */
public class ResourceNotFoundException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SecretsManagerException(
    "ResourceNotFoundException", message, statusCode, requestId, extendedRequestId, cause,
)

/**
 * A parameter's value is not valid for this request — a `VersionId` and a `VersionStage` that
 * disagree, both `SecretString` and `SecretBinary` set, an unparseable ARN.
 */
public class InvalidParameterException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SecretsManagerException(
    "InvalidParameterException", message, statusCode, requestId, extendedRequestId, cause,
)

/**
 * The request is well-formed but not valid against the secret's current state.
 *
 * The one worth recognising: **a secret scheduled for deletion answers here**, not with
 * [ResourceNotFoundException]. During the recovery window the secret still exists and still cannot
 * be read, so a caller that only catches not-found sees an unrecognised failure at exactly the
 * moment it could still cancel the deletion.
 */
public class InvalidRequestException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SecretsManagerException(
    "InvalidRequestException", message, statusCode, requestId, extendedRequestId, cause,
)

/**
 * Secrets Manager could not decrypt the secret with its KMS key.
 *
 * The failure is KMS's, surfaced through Secrets Manager: the key is disabled, pending deletion, or
 * this principal has no `kms:Decrypt` on it. Note the code is `DecryptionFailure` — no `Exception`
 * suffix — which is why the class name and the wire code differ here.
 */
public class DecryptionFailureException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SecretsManagerException("DecryptionFailure", message, statusCode, requestId, extendedRequestId, cause)

/** Secrets Manager could not encrypt the value with its KMS key. The mirror of [DecryptionFailureException]. */
public class EncryptionFailureException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SecretsManagerException("EncryptionFailure", message, statusCode, requestId, extendedRequestId, cause)

/**
 * A fault inside Secrets Manager. Answered with a 500, so `aws-core` has already retried it to
 * exhaustion by the time it reaches a caller.
 */
public class InternalServiceErrorException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SecretsManagerException("InternalServiceError", message, statusCode, requestId, extendedRequestId, cause)

/** An account quota was exceeded — too many versions on one secret, most often. */
public class LimitExceededException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SecretsManagerException("LimitExceededException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * A version with the supplied `ClientRequestToken` already exists **with different content**.
 *
 * Not a generic "already exists": an identical replay of a [PutSecretValueRequest] is a no-op that
 * returns the existing version, which is the property [SecretsManager.putSecretValue] relies on to
 * be safely retryable. Seeing this means the same token was reused for a *different* value —
 * either a caller supplied its own token and reused it, or two different values were written under
 * one workflow step's token.
 */
public class ResourceExistsException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SecretsManagerException("ResourceExistsException", message, statusCode, requestId, extendedRequestId, cause)

/** The account's request rate was exceeded. Paced by `aws-core`'s throttling backoff. */
public class ThrottlingException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SecretsManagerException("ThrottlingException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * A [getSecretValues] batch in which at least one secret could not be read.
 *
 * The code is the synthetic `"BatchGetSecretValuePartialFailure"` and the status is **200**,
 * because that is literally what happened: Secrets Manager answered 200 and reported the failures
 * per entry in the body. It mirrors how `aws-eventbridge` raises
 * `PutEventsPartialFailureException` for a partially published batch and how `aws-dynamodb` raises
 * a synthetic-code `DynamoDbException` for `UnprocessedKeys` that outlived their budget.
 *
 * Unlike EventBridge's, this failure is **fully recoverable by retrying the failed ids alone**:
 * reading a secret has no side effect, so nothing was double-applied and [resolved] can simply be
 * kept. That is why the partition is carried — a caller that can start without an optional secret
 * has everything it needs to decide that here, rather than having to re-issue the whole batch.
 *
 * @property resolved every secret that *was* read, including from earlier pages and chunks.
 * @property errors one entry per secret that was not, as Secrets Manager reported it.
 * @property missingSecretIds ids that were requested and appear in neither [resolved] nor
 *   [errors]. Should always be empty; a non-empty value means Secrets Manager returned a response
 *   that accounts for fewer secrets than were asked for, which is not a documented behaviour and is
 *   surfaced rather than silently treated as success.
 */
public class BatchGetSecretValuePartialFailureException(
    public val resolved: List<SecretValueEntry>,
    public val errors: List<ApiError>,
    public val missingSecretIds: List<String>,
    message: String?,
    requestId: String? = null,
) : SecretsManagerException("BatchGetSecretValuePartialFailure", message, 200, requestId)

/**
 * Maps `aws-core`'s protocol-level exception onto this module's hierarchy.
 *
 * Unknown codes — `AccessDeniedException`, `MalformedPolicyDocumentException`, anything AWS adds —
 * fall through to [SecretsManagerException] rather than being swallowed, so an operation added
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
        "ResourceNotFoundException" ->
            ResourceNotFoundException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "InvalidParameterException" ->
            InvalidParameterException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "InvalidRequestException" ->
            InvalidRequestException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "DecryptionFailure" ->
            DecryptionFailureException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "EncryptionFailure" ->
            EncryptionFailureException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "InternalServiceError" ->
            InternalServiceErrorException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "LimitExceededException" ->
            LimitExceededException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "ResourceExistsException" ->
            ResourceExistsException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "ThrottlingException" ->
            ThrottlingException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        else -> SecretsManagerException(
            e.code, e.message, e.statusCode, e.requestId, e.extendedRequestId, e,
        )
    }
}
