package com.steamstreet.awskt.ses

import com.steamstreet.awskt.core.AwsServiceException

/**
 * Base for every SES v2 failure.
 *
 * Extends `aws-core`'s [AwsServiceException] so every failure carries `code`, `statusCode`,
 * `requestId` and `extendedRequestId` — declared now rather than later because re-parenting a public
 * exception hierarchy after publication is an API revision.
 *
 * ### Where the codes come from
 *
 * As in `aws-scheduler`: SES v2 is restJson1, so the code arrives in the **`x-amzn-errortype`
 * response header**, with the body's `__type` as the fallback. `AwsJsonErrorParser` reads both in
 * that order, which is why this module needs no error parser of its own.
 *
 * ### Two of these names are not what you would guess
 *
 * [MessageRejected] has **no `Exception` suffix** — it is the one SES shape here that does not, and
 * a map keyed on `"MessageRejectedException"` would compile, pass review, and silently downgrade the
 * single most common `SendEmail` failure to the base type.
 *
 * [NotFoundException] is **not** `ResourceNotFoundException`, which is the spelling `aws-core` lists
 * in `NEVER_RETRY_CODES`. The practical consequence is nil — a 404 is in neither retry table, so it
 * is not retried either way — but the near-miss is worth naming so nobody "fixes" one to match the
 * other.
 */
public open class SesException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : AwsServiceException(code, message, statusCode, requestId, extendedRequestId, cause)

/**
 * The message was refused for its content.
 *
 * **The failure a working integration actually meets**, and almost always for one reason while an
 * account is still in the SES **sandbox**: every recipient address must be verified, not only the
 * sender. A sign-in code mailed to a real user from a sandboxed account fails here, and the message
 * says so, but the shape of the failure — a 400 on a request that is structurally perfect — reads
 * like a client bug rather than an account state.
 *
 * Note the code has no `Exception` suffix; see the class KDoc above.
 */
public class MessageRejected(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SesException("MessageRejected", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The request is structurally invalid.
 *
 * SES's equivalent of the `ValidationException` the other modules here raise — a different spelling
 * of the same idea, and **not** in `aws-core`'s `NEVER_RETRY_CODES`, which lists that name. It is
 * still never retried, because it appears in no retryable table either.
 */
public class BadRequestException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SesException("BadRequestException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The account's ability to send has been **permanently** restricted.
 *
 * Not a transient condition and not one a retry, a backoff or a redeploy resolves: AWS suspended the
 * account's sending, usually after sustained bounce or complaint rates, and it is reinstated through
 * Support. Worth distinguishing from [SendingPausedException], which is the recoverable neighbour.
 */
public class AccountSuspendedException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SesException("AccountSuspendedException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * Sending is currently paused for the account.
 *
 * The recoverable sibling of [AccountSuspendedException]: sending was paused, by AWS or by the
 * account itself through `PutAccountSendingAttributes`, and can be resumed. Every send fails until
 * it is, so retrying is pointless — this is an operational alarm, not an error to swallow.
 */
public class SendingPausedException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SesException("SendingPausedException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The `MAIL FROM` domain is not verified.
 *
 * Distinct from an unverified *identity*, and the distinction is the whole difficulty: the identity
 * can be verified and sending fine while the custom MAIL FROM domain configured on it has lost its
 * MX or SPF record. The send then fails for a configuration change nobody made to the sending code.
 */
public class MailFromDomainNotVerifiedException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SesException(
    "MailFromDomainNotVerifiedException", message, statusCode, requestId, extendedRequestId, cause,
)

/**
 * A resource named by the request does not exist — a configuration set, a contact list, a template.
 *
 * Note the spelling: `NotFoundException`, not `ResourceNotFoundException`. See the [SesException]
 * KDoc.
 */
public class NotFoundException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SesException("NotFoundException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * Too many instances of a resource type exist.
 *
 * `aws-core` classifies `LimitExceededException` as **THROTTLING**, so this is backed off and
 * retried before it ever reaches a caller. That is the right default for the services that raise it
 * as a rate signal, and merely wasteful here, where it usually is not one — the cost is a few
 * seconds of backoff on a request that was rejected outright, and never a duplicate send, because
 * the rejection is an answer rather than an ambiguous failure.
 */
public class LimitExceededException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SesException("LimitExceededException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The sending rate or the daily sending quota was exceeded. Answered with a 429.
 *
 * `aws-core` classifies this as THROTTLING and paces it with the 1-second backoff base, so by the
 * time a caller sees it the retry budget is spent. **Reaching a caller means the throttle outlasted
 * four attempts**, which for the per-second rate limit is unusual and for the 24-hour sending quota
 * is expected — the quota does not clear inside a retry window, and a client that keeps trying is
 * just spending latency.
 */
public class TooManyRequestsException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SesException("TooManyRequestsException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * Maps `aws-core`'s protocol-level exception onto this module's hierarchy.
 *
 * Unknown codes — `AccessDeniedException`, anything AWS adds, anything raised by an operation added
 * downstream through the extension seam — fall through to [SesException] rather than being
 * swallowed, so an extension author still gets a useful typed failure without registering anything.
 *
 * Every branch threads `cause = e` and both request ids. The typed exception is a *rebuild* rather
 * than a wrapper, so whatever is not carried across is destroyed here — including the stack trace of
 * the call that actually failed.
 */
internal inline fun <T> mapErrors(block: () -> T): T = try {
    block()
} catch (e: AwsServiceException) {
    throw when (e.code) {
        // No `Exception` suffix. See the SesException KDoc — this is the one that gets mistyped.
        "MessageRejected" ->
            MessageRejected(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "BadRequestException" ->
            BadRequestException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "AccountSuspendedException" ->
            AccountSuspendedException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "SendingPausedException" ->
            SendingPausedException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "MailFromDomainNotVerifiedException" ->
            MailFromDomainNotVerifiedException(
                e.message, e.statusCode, e.requestId, e.extendedRequestId, e,
            )

        "NotFoundException" ->
            NotFoundException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "LimitExceededException" ->
            LimitExceededException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "TooManyRequestsException" ->
            TooManyRequestsException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        else -> SesException(e.code, e.message, e.statusCode, e.requestId, e.extendedRequestId, e)
    }
}
