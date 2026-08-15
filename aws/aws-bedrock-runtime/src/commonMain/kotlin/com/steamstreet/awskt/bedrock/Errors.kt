package com.steamstreet.awskt.bedrock

import com.steamstreet.awskt.core.AwsServiceException

/**
 * Base for every Bedrock Runtime failure.
 *
 * Extends `aws-core`'s [AwsServiceException] so every failure carries `code`, `statusCode`,
 * `requestId` and `extendedRequestId` — declared now rather than later because re-parenting a
 * public exception hierarchy after publication is an API revision.
 *
 * ### These arrive two ways, and both land here
 *
 * A `Converse` failure is an ordinary restJson1 error response, with its code in the
 * `x-amzn-errortype` header. A `ConverseStream` failure can be that — when it happens before the
 * stream opens — **or a frame in the middle of a successful HTTP 200**, because a model can fail
 * half-way through generating. [mapErrors] handles the first and [mapStreamException] the second,
 * and both produce the same types, so a caller writes one `catch`.
 */
public open class BedrockException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : AwsServiceException(code, message, statusCode, requestId, extendedRequestId, cause)

/**
 * The request is structurally invalid, or invalid for this model.
 *
 * The two that account for most of them: a **conversation that does not alternate** — Converse
 * requires user and assistant turns to alternate and to start with a user turn — and a
 * `ContentBlock` the chosen model does not accept, such as a document sent to a text-only model.
 *
 * `aws-core` lists `ValidationException` in `NEVER_RETRY_CODES`, so this is never replayed.
 */
public class ValidationException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : BedrockException("ValidationException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The model, inference profile or endpoint does not exist in this region.
 *
 * **Bedrock model availability is per-region**, and this is what a model id that exists in
 * `us-east-1` and not in the configured region looks like. An inference profile id
 * (`us.anthropic.…`) rather than a bare model id is usually the fix.
 */
public class ResourceNotFoundException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : BedrockException(
    "ResourceNotFoundException", message, statusCode, requestId, extendedRequestId, cause,
)

/**
 * The principal is not authorized, or the account has not been granted access to this model.
 *
 * Distinct causes with one code, and the second is the surprising one: Bedrock foundation models
 * require **per-model access to be requested and granted in the account** before any principal can
 * invoke them. An IAM role with full `bedrock:InvokeModel` still gets this until that is done.
 */
public class AccessDeniedException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : BedrockException("AccessDeniedException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The request rate or token throughput was exceeded.
 *
 * The one to expect in production. Bedrock's on-demand quotas are per-model, per-region and
 * measured in **tokens per minute as well as requests per minute**, so a workload can throttle on
 * volume while nowhere near the request limit. Paced by `aws-core`'s throttling backoff, which
 * is generally not enough on its own — a caller under sustained load needs a queue, not a retry.
 */
public class ThrottlingException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : BedrockException("ThrottlingException", message, statusCode, requestId, extendedRequestId, cause)

/** The model took too long. Retried by status (408/500), and worth a shorter prompt rather than a longer wait. */
public class ModelTimeoutException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : BedrockException("ModelTimeoutException", message, statusCode, requestId, extendedRequestId, cause)

/** The model itself errored. */
public class ModelErrorException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : BedrockException("ModelErrorException", message, statusCode, requestId, extendedRequestId, cause)

/** The model is not ready — a provisioned or custom model still warming. Transient. */
public class ModelNotReadyException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : BedrockException("ModelNotReadyException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The stream failed part-way through.
 *
 * **Only ever arrives mid-stream, inside an HTTP 200**, which is what makes it different from
 * everything else here: by the time it appears the caller has already received and probably
 * displayed part of an answer. There is no safe automatic retry — replaying the request produces a
 * second, different answer that does not continue the first — so this propagates and the caller
 * decides whether to discard the partial reply or keep it.
 */
public class ModelStreamErrorException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : BedrockException(
    "ModelStreamErrorException", message, statusCode, requestId, extendedRequestId, cause,
)

/** A fault inside Bedrock. Answered with a 500, so `aws-core` has already retried it to exhaustion. */
public class InternalServerException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : BedrockException(
    "InternalServerException", message, statusCode, requestId, extendedRequestId, cause,
)

/** Bedrock is temporarily unable to serve the request. Retried by status. */
public class ServiceUnavailableException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : BedrockException(
    "ServiceUnavailableException", message, statusCode, requestId, extendedRequestId, cause,
)

/** Builds the typed exception for a Bedrock error code. Shared by both arrival paths. */
private fun bedrockException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String?,
    extendedRequestId: String?,
    cause: Throwable?,
): BedrockException = when (code) {
    "ValidationException" ->
        ValidationException(message, statusCode, requestId, extendedRequestId, cause)

    "ResourceNotFoundException" ->
        ResourceNotFoundException(message, statusCode, requestId, extendedRequestId, cause)

    "AccessDeniedException" ->
        AccessDeniedException(message, statusCode, requestId, extendedRequestId, cause)

    "ThrottlingException" ->
        ThrottlingException(message, statusCode, requestId, extendedRequestId, cause)

    "ModelTimeoutException" ->
        ModelTimeoutException(message, statusCode, requestId, extendedRequestId, cause)

    "ModelErrorException" ->
        ModelErrorException(message, statusCode, requestId, extendedRequestId, cause)

    "ModelNotReadyException" ->
        ModelNotReadyException(message, statusCode, requestId, extendedRequestId, cause)

    "ModelStreamErrorException" ->
        ModelStreamErrorException(message, statusCode, requestId, extendedRequestId, cause)

    "InternalServerException" ->
        InternalServerException(message, statusCode, requestId, extendedRequestId, cause)

    "ServiceUnavailableException" ->
        ServiceUnavailableException(message, statusCode, requestId, extendedRequestId, cause)

    else -> BedrockException(code, message, statusCode, requestId, extendedRequestId, cause)
}

/**
 * Maps `aws-core`'s protocol-level exception onto this module's hierarchy.
 *
 * Unknown codes fall through to [BedrockException] rather than being swallowed, so an operation
 * added downstream through the extension seam still gets a useful typed failure.
 *
 * Every branch threads `cause = e` and both request ids. The typed exception is a *rebuild* rather
 * than a wrapper, so whatever is not carried across is destroyed here — including the stack trace
 * of the call that actually failed.
 */
internal inline fun <T> mapErrors(block: () -> T): T = try {
    block()
} catch (e: AwsServiceException) {
    throw bedrockException(e.code, e.message, e.statusCode, e.requestId, e.extendedRequestId, e)
}

/**
 * Builds the exception for an `exception` frame arriving mid-stream.
 *
 * **The status is 200 and that is not a bug.** The HTTP exchange succeeded; the failure was
 * reported inside it, several seconds in, after part of an answer had already been delivered.
 * Reporting some other status would misdescribe what happened and would make the failure look
 * retryable to anything reading `statusCode`.
 */
internal fun mapStreamException(exceptionType: String?, message: String?): BedrockException =
    bedrockException(exceptionType, message, 200, null, null, null)
