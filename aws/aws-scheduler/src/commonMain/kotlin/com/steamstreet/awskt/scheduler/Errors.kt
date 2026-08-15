package com.steamstreet.awskt.scheduler

import com.steamstreet.awskt.core.AwsServiceException

/**
 * Base for every EventBridge Scheduler failure.
 *
 * Extends `aws-core`'s [AwsServiceException] so every failure carries `code`, `statusCode`,
 * `requestId` and `extendedRequestId` — declared now rather than later because re-parenting a
 * public exception hierarchy after publication is an API revision.
 *
 * ### Where the codes come from on a restJson1 service
 *
 * Unlike the AWS-JSON services, whose code arrives in the body's `__type`, a restJson1 service
 * normally puts it in the **`x-amzn-errortype` response header**. `AwsJsonErrorParser` already reads
 * that header first and falls back to the body, which is why this module needs no error parser of
 * its own — the same parser serves both shapes. The names below are the plain Smithy shape names,
 * with no query-protocol renaming of the kind `aws-sns` has to deal with.
 */
public open class SchedulerException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : AwsServiceException(code, message, statusCode, requestId, extendedRequestId, cause)

/**
 * The schedule, schedule group, or a resource it names does not exist.
 *
 * Also what a **wrong group** looks like: schedule names are unique within a group, not within the
 * account, so a `GetSchedule` for a schedule that plainly exists in `my-group` and was asked for
 * without a `groupName` looks in `default` and answers this.
 *
 * `aws-core` lists `ResourceNotFoundException` in `NEVER_RETRY_CODES`, so this is never replayed.
 */
public class ResourceNotFoundException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SchedulerException(
    "ResourceNotFoundException", message, statusCode, requestId, extendedRequestId, cause,
)

/**
 * The request conflicts with the resource's current state.
 *
 * On `CreateSchedule` this is **"a schedule with that name already exists in that group"**, which
 * is the one every caller meets. Worth catching by name rather than treating as a generic failure:
 * for a caller creating a schedule per business event, this is the signal that the event has
 * already been handled, and it is often the *success* path rather than an error.
 */
public class ConflictException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SchedulerException("ConflictException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The request is structurally invalid.
 *
 * The two that account for most of these: a **five-field cron expression** — Scheduler's cron takes
 * six, the last being the year — and a `FlexibleTimeWindow` whose `Mode` and
 * `MaximumWindowInMinutes` disagree.
 *
 * `aws-core` lists `ValidationException` in `NEVER_RETRY_CODES`, so this is never replayed.
 */
public class ValidationException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SchedulerException("ValidationException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * An account quota was exceeded — most often the number of schedules in a group.
 *
 * The usual cause is one-shot schedules that were never cleaned up. Setting
 * [ActionAfterCompletion.DELETE] on them is the fix; deleting the backlog is the remedy.
 */
public class ServiceQuotaExceededException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SchedulerException(
    "ServiceQuotaExceededException", message, statusCode, requestId, extendedRequestId, cause,
)

/** The account's request rate was exceeded. Paced by `aws-core`'s throttling backoff. */
public class ThrottlingException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SchedulerException("ThrottlingException", message, statusCode, requestId, extendedRequestId, cause)

/** A fault inside Scheduler. Answered with a 500, so `aws-core` has already retried it to exhaustion. */
public class InternalServerException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : SchedulerException(
    "InternalServerException", message, statusCode, requestId, extendedRequestId, cause,
)

/**
 * Maps `aws-core`'s protocol-level exception onto this module's hierarchy.
 *
 * Unknown codes — `AccessDeniedException`, anything AWS adds — fall through to [SchedulerException]
 * rather than being swallowed, so an operation added downstream through the extension seam still
 * gets a useful typed failure without registering anything.
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

        "ConflictException" ->
            ConflictException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "ValidationException" ->
            ValidationException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "ServiceQuotaExceededException" ->
            ServiceQuotaExceededException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "ThrottlingException" ->
            ThrottlingException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "InternalServerException" ->
            InternalServerException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        else -> SchedulerException(e.code, e.message, e.statusCode, e.requestId, e.extendedRequestId, e)
    }
}
