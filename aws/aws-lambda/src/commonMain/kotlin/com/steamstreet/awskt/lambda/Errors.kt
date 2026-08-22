package com.steamstreet.awskt.lambda

import com.steamstreet.awskt.core.AwsServiceException
import com.steamstreet.awskt.core.awsJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Base for every Lambda invoke failure.
 *
 * Extends `aws-core`'s [AwsServiceException] so every failure carries `code`, `statusCode`,
 * `requestId` and `extendedRequestId` — declared now rather than later because re-parenting a
 * public exception hierarchy after publication is an API revision.
 *
 * ### Where the codes come from
 *
 * Lambda is restJson1, so the code arrives in the **`x-amzn-errortype` response header**.
 * `AwsJsonErrorParser` already reads that header first and falls back to the body, which is why
 * this module needs no error parser of its own.
 *
 * ### These are not the same thing as a function that failed
 *
 * Everything here is the **service** refusing, or failing, to run the function. A function that ran
 * and threw answers `200` with `X-Amz-Function-Error` set, and is reported by
 * [InvokeResponse.functionError] / [FunctionErrorException] — not by anything in this file. Keeping
 * the two apart is the most important distinction in this module: they want different handling,
 * different alarms and different retry decisions.
 */
public open class LambdaException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : AwsServiceException(code, message, statusCode, requestId, extendedRequestId, cause)

/**
 * The function, version or alias does not exist.
 *
 * Also what a **wrong qualifier** looks like: the function plainly exists, and
 * [InvokeRequest.qualifier] names an alias that was never published.
 *
 * `aws-core` lists `ResourceNotFoundException` in `NEVER_RETRY_CODES`, so this is never replayed.
 */
public class ResourceNotFoundException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LambdaException(
    "ResourceNotFoundException", message, statusCode, requestId, extendedRequestId, cause,
)

/**
 * The function exists but is in a state that cannot be invoked.
 *
 * The one that surprises people: a function attached to a VPC, or one whose code was just updated,
 * spends time in `Pending` before it is `Active`, and an invoke inside that window lands here
 * rather than queueing.
 */
public class ResourceConflictException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LambdaException(
    "ResourceConflictException", message, statusCode, requestId, extendedRequestId, cause,
)

/**
 * Lambda could not get the function ready to run — it is not `Active` yet.
 *
 * Answered with a `502`, which `aws-core` classifies as transient and retries. A cold VPC function
 * usually becomes invokable inside that window; one that does not has an ENI or subnet problem
 * underneath, and [ENILimitReachedException] or [SubnetIPAddressLimitReachedException] is the next
 * thing to look for.
 */
public class ResourceNotReadyException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LambdaException(
    "ResourceNotReadyException", message, statusCode, requestId, extendedRequestId, cause,
)

/** The request body was not valid JSON, or was empty where the function required one. */
public class InvalidRequestContentException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LambdaException(
    "InvalidRequestContentException", message, statusCode, requestId, extendedRequestId, cause,
)

/**
 * The payload was over Lambda's limit — 6 MB synchronous, 256 KB asynchronous.
 *
 * [Lambda.invoke] checks both locally and raises [LambdaPayloadTooLargeException] before sending,
 * so seeing *this* one means the request was inside the client's understanding of the limit and
 * outside the service's. Lambda measures the whole request rather than the payload alone, so a
 * payload sitting just under the ceiling can still be refused.
 */
public class RequestTooLargeException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LambdaException(
    "RequestTooLargeException", message, statusCode, requestId, extendedRequestId, cause,
)

/** The content type was not one Lambda accepts. This client always sends `application/json`. */
public class UnsupportedMediaTypeException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LambdaException(
    "UnsupportedMediaTypeException", message, statusCode, requestId, extendedRequestId, cause,
)

/**
 * The invocation was throttled.
 *
 * `aws-core` classifies `TooManyRequestsException` as THROTTLING and paces retries with the
 * throttling backoff, so reaching a caller means the attempt budget ran out.
 *
 * **[reason] is what makes this actionable**, and it is why this type carries a field the others do
 * not: the same code covers "the account is at its concurrency ceiling"
 * (`ConcurrentInvocationLimitExceeded`), "this function is at the reserved concurrency *you* set on
 * it" (`ReservedFunctionConcurrentInvocationLimitExceeded`) and "the caller is calling too fast"
 * (`CallerRateLimitExceeded`). The second is a configuration change on one function, the first is a
 * quota request, the third is a client problem.
 *
 * Lambda also sends a `Retry-After` header here. `aws-core`'s backoff reads `x-amz-retry-after`
 * rather than the plain HTTP header, so that hint is **not** applied and the ordinary throttling
 * backoff runs instead. Deliberate rather than an oversight: the standard backoff is already the
 * right shape for a concurrency ceiling, and sleeping for an arbitrary server-supplied duration
 * inside a Lambda is how a retryable throttle becomes an unreported function timeout.
 */
public class TooManyRequestsException(
    message: String?,
    statusCode: Int,
    /** `ConcurrentInvocationLimitExceeded`, `ReservedFunctionConcurrentInvocationLimitExceeded`, … */
    public val reason: String?,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LambdaException(
    "TooManyRequestsException", message, statusCode, requestId, extendedRequestId, cause,
)

/**
 * Lambda refused the call because it would have closed an invocation loop.
 *
 * The service follows a chain of invocations through its own trace header and stops one that
 * revisits the same function more than 16 times — a function that invokes itself, or two that
 * invoke each other. It exists because that mistake is otherwise billed at full rate until somebody
 * notices.
 *
 * **Never worth retrying.** It is not a transient condition; the loop is in the code.
 */
public class RecursiveInvocationException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LambdaException(
    "RecursiveInvocationException", message, statusCode, requestId, extendedRequestId, cause,
)

/**
 * Lambda could not attach a network interface for a VPC function — the account is at its ENI limit.
 *
 * Not a client-side problem, and not fixed by retrying past the budget: it wants a quota increase
 * or fewer VPC functions.
 */
public class ENILimitReachedException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LambdaException(
    "ENILimitReachedException", message, statusCode, requestId, extendedRequestId, cause,
)

/** A VPC function's subnet has no free IP addresses left. Wants a larger subnet, not a retry. */
public class SubnetIPAddressLimitReachedException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LambdaException(
    "SubnetIPAddressLimitReachedException", message, statusCode, requestId, extendedRequestId, cause,
)

/**
 * Lambda could not use the KMS key that encrypts the function's environment variables.
 *
 * Four distinct causes share one shape here — the key is disabled, deleted, in an invalid state, or
 * the function's role has lost `kms:Decrypt` on it — and all four present the same way: an
 * invocation that used to work and now does not, with nothing having changed in the function.
 * [code] distinguishes them: `KMSAccessDeniedException`, `KMSDisabledException`,
 * `KMSInvalidStateException`, `KMSNotFoundException`.
 */
public class KmsException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LambdaException(code, message, statusCode, requestId, extendedRequestId, cause)

/** A fault inside Lambda. Answered with a 500, so `aws-core` has already retried it to exhaustion. */
public class ServiceException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LambdaException("ServiceException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The function ran and failed. **Raised by this client; Lambda never sends it.**
 *
 * A failed invocation answers `200`, so nothing in the transport treats it as an error and the
 * failure arrives in a header. This is the typed form of that header, raised by
 * [InvokeResponse.orThrow] and by the conveniences that call it.
 *
 * Not a [LambdaException]: there is no service failure to report, no error code AWS Support can
 * look up, and treating it as one would put "your code threw" in the same bucket as "Lambda is
 * down".
 *
 * @param functionError `"Handled"` — the runtime caught it and reported it — or `"Unhandled"`,
 *   which also covers a timeout, an out-of-memory kill and a runtime crash. The distinction is what
 *   tells you whether a retry could possibly help.
 * @param errorType the runtime's error type, when the payload followed the managed runtimes'
 *   convention. Null for a custom runtime that answered with something else.
 * @param errorMessage likewise.
 * @param payload the error payload, verbatim. **Always populated**, and the thing to log when
 *   [errorType] is null.
 */
public class FunctionErrorException(
    public val functionError: String,
    public val errorType: String?,
    public val errorMessage: String?,
    public val payload: ByteArray,
) : Exception(
    buildString {
        append("Lambda function failed ($functionError)")
        errorType?.let { append(": ").append(it) }
        errorMessage?.let { append(" — ").append(it) }
        if (errorType == null && errorMessage == null) {
            append(": ").append(payload.decodeToString().take(512))
        }
    },
)

/**
 * A payload exceeded the ceiling for its invocation type, refused before anything was sent.
 *
 * Deliberately **not** a [LambdaException]: no request was made, so there is no status code, no
 * request id and nothing for AWS Support to look up. Compare [RequestTooLargeException], which is
 * the service's own answer to the same mistake.
 */
public class LambdaPayloadTooLargeException(message: String) : Exception(message)

/** The `Reason` field Lambda puts in a `TooManyRequestsException` body. */
@Serializable
private class ThrottleBody(@SerialName("Reason") val reason: String? = null)

/**
 * Reads `Reason` out of a throttling error body, tolerantly.
 *
 * Best-effort by construction: the field is absent on some throttle paths, and a body that does not
 * parse must not turn a throttle into a deserialization failure.
 */
private fun throttleReason(body: ByteArray?): String? {
    val text = body?.decodeToString()?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { awsJson.decodeFromString(ThrottleBody.serializer(), text).reason }.getOrNull()
}

/**
 * Maps `aws-core`'s protocol-level exception onto this module's hierarchy.
 *
 * Unknown codes — `AccessDeniedException`, the SnapStart family, anything AWS adds — fall through
 * to [LambdaException] rather than being swallowed, so an operation added downstream through the
 * extension seam still gets a useful typed failure without registering anything.
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

        "ResourceConflictException" ->
            ResourceConflictException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "ResourceNotReadyException" ->
            ResourceNotReadyException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "InvalidRequestContentException" ->
            InvalidRequestContentException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "RequestTooLargeException" ->
            RequestTooLargeException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "UnsupportedMediaTypeException" ->
            UnsupportedMediaTypeException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "TooManyRequestsException" -> TooManyRequestsException(
            e.message, e.statusCode, throttleReason(e.rawErrorBody), e.requestId, e.extendedRequestId, e,
        )

        "RecursiveInvocationException" ->
            RecursiveInvocationException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "ENILimitReachedException" ->
            ENILimitReachedException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "SubnetIPAddressLimitReachedException" -> SubnetIPAddressLimitReachedException(
            e.message, e.statusCode, e.requestId, e.extendedRequestId, e,
        )

        "KMSAccessDeniedException", "KMSDisabledException", "KMSInvalidStateException",
        "KMSNotFoundException",
        -> KmsException(e.code, e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "ServiceException" ->
            ServiceException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        else -> LambdaException(e.code, e.message, e.statusCode, e.requestId, e.extendedRequestId, e)
    }
}
