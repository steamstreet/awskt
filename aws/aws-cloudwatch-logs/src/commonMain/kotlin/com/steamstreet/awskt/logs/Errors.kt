package com.steamstreet.awskt.logs

import com.steamstreet.awskt.core.AwsServiceException

/**
 * Base for every CloudWatch Logs failure.
 *
 * Extends `aws-core`'s [AwsServiceException] so every failure carries `code`, `statusCode`,
 * `requestId` and `extendedRequestId` — declared now rather than later because re-parenting a
 * public exception hierarchy after publication is an API revision.
 */
public open class LogsException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : AwsServiceException(code, message, statusCode, requestId, extendedRequestId, cause)

/**
 * The query text does not compile.
 *
 * **The message is the useful part**, and it is more useful than most AWS error messages: Insights
 * reports the compile error with the position in the query, so surfacing `message` to whoever wrote
 * the query is nearly always the right handling. Retrying is never right — the query will not
 * compile on the second attempt either.
 */
public class MalformedQueryException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LogsException("MalformedQueryException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * A log group, or a query id, does not exist.
 *
 * On `StartQuery` this means a named log group is absent — and note Insights treats that as fatal
 * rather than searching the groups that *do* exist, so one typo in a five-group query returns
 * nothing at all. On `GetQueryResults` it means the query id has aged out; Insights keeps results
 * for a limited time after completion, so a caller that stores a query id and polls it much later
 * gets this rather than the answer.
 *
 * `aws-core` lists `ResourceNotFoundException` in `NEVER_RETRY_CODES`, so this is never replayed.
 */
public class ResourceNotFoundException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LogsException(
    "ResourceNotFoundException", message, statusCode, requestId, extendedRequestId, cause,
)

/** A parameter is invalid — a time range whose end precedes its start, a limit above 10,000. */
public class InvalidParameterException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LogsException("InvalidParameterException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * Too many **concurrent** queries in this account and region.
 *
 * The one worth recognising by name, because the obvious response to it is wrong. Insights caps
 * concurrent running queries per account — a limit in the tens, not the thousands — and this says
 * that cap is reached, **not** that the request rate is too high. `aws-core` classifies
 * `LimitExceededException` as throttling and will back off and retry, which helps only if another
 * query happens to finish meanwhile; a caller that starts queries in a loop needs to bound its own
 * concurrency, and one that abandons queries without [Logs.stopQuery] holds slots until they time
 * out on their own.
 *
 * That is the same "shared retry table means the wrong thing for this service" note `aws-kms`'s
 * `LimitExceededException` carries, and it is left alone here for the same reason: the fix is a
 * per-service retry override that would exist for one code.
 */
public class LimitExceededException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LogsException("LimitExceededException", message, statusCode, requestId, extendedRequestId, cause)

/** The service is temporarily unavailable. Retried by `aws-core`. */
public class ServiceUnavailableException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LogsException(
    "ServiceUnavailableException", message, statusCode, requestId, extendedRequestId, cause,
)

/** The account's request rate was exceeded. Paced by `aws-core`'s throttling backoff. */
public class ThrottlingException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LogsException("ThrottlingException", message, statusCode, requestId, extendedRequestId, cause)

/** The caller lacks permission. Distinct from a missing log group — see [ResourceNotFoundException]. */
public class AccessDeniedException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : LogsException("AccessDeniedException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * A query that ended in a terminal status other than `Complete`.
 *
 * Raised by [Logs.query] rather than by the transport, because **HTTP said 200 every time**: the
 * failure is a field in a successful response, which is the same shape of trap as EventBridge's
 * per-entry errors and SQS's `SenderFault`. Returning the partial rows as if they were an answer is
 * the alternative, and it is worse — an incomplete Insights result is indistinguishable from a
 * complete one by inspection.
 *
 * @property queryId the query, still addressable — `GetQueryResults` on it will return the same
 *   terminal status and whatever rows it managed, which is occasionally worth looking at.
 * @property status the terminal status. [QueryStatus.TIMEOUT] is the common one and means the
 *   *service* gave up, not this client — narrow the time range or the query.
 * @property partialResults whatever had matched when the query stopped. Present so a caller can
 *   look, deliberately **not** returned as a success.
 */
public class QueryFailedException(
    public val queryId: String,
    public val status: String?,
    public val partialResults: List<ResultRow>,
    message: String?,
) : LogsException("QueryFailed", message, 200)

/**
 * A query that had not reached a terminal status before the caller's polling budget ran out.
 *
 * **The query is still running on AWS.** [Logs.query] stops it before raising — an abandoned query
 * holds one of the account's concurrent-query slots until it times out on its own, which is how a
 * caller with a short budget and a slow query starves itself. [queryId] is carried so a caller that
 * wants to keep waiting can poll it directly instead.
 */
public class QueryTimedOutException(
    public val queryId: String,
    public val lastStatus: String?,
    message: String?,
) : LogsException("QueryTimedOut", message, 200)

/**
 * Maps `aws-core`'s protocol-level exception onto this module's hierarchy.
 *
 * Unknown codes fall through to [LogsException] rather than being swallowed, so an operation added
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
        "MalformedQueryException" ->
            MalformedQueryException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "ResourceNotFoundException" ->
            ResourceNotFoundException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "InvalidParameterException" ->
            InvalidParameterException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "LimitExceededException" ->
            LimitExceededException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "ServiceUnavailableException" ->
            ServiceUnavailableException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "ThrottlingException" ->
            ThrottlingException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "AccessDeniedException" ->
            AccessDeniedException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        else -> LogsException(e.code, e.message, e.statusCode, e.requestId, e.extendedRequestId, e)
    }
}
