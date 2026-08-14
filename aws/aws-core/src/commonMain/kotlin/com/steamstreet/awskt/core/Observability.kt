package com.steamstreet.awskt.core

/**
 * One thing that happened to one attempt of one AWS call.
 *
 * ### The event stream, precisely
 *
 * The retry loop emits **exactly one terminal event per completed attempt** —
 * [Outcome.SUCCESS], [Outcome.SERVICE_ERROR] or [Outcome.TRANSPORT_FAILURE] — carrying that
 * attempt's [durationMillis]. Counting terminal events is therefore the attempt count, and summing
 * their durations is the call's time on the wire; nothing else needs to be inferred.
 *
 * A terminal event may be followed by at most one **decision** event for the same [attempt], which
 * reports what the loop chose to do next:
 *
 * - [Outcome.RETRY_SCHEDULED] — another attempt is coming after [willRetryAfterMillis]. This is the
 *   only outcome on which that field is non-null, and the event is emitted *before* the sleep, so an
 *   observer sees the intended delay rather than inferring it afterwards.
 * - [Outcome.CLOCK_SKEW_CORRECTED] — the response said this process's clock disagrees with AWS, the
 *   offset was learned, and the request is being re-signed and resent **immediately** (no backoff,
 *   which is why [willRetryAfterMillis] is null here).
 * - [Outcome.GAVE_UP] — the call is over and is about to throw.
 *
 * [Outcome.GAVE_UP] is a decision event rather than a terminal one, and that is a deliberate
 * departure from the obvious reading. Were it terminal, the last attempt of a failed call would
 * emit two terminal events — its own outcome and then the give-up — and the "one terminal event per
 * attempt" invariant that makes attempt counting trivial would be gone. Keeping it separate also
 * keeps the metrics honest in the other direction: a `ValidationException` that is never retried
 * still emits [Outcome.SERVICE_ERROR], so a counter on that outcome sees *every* error response AWS
 * ever returned, not just the retryable ones, while a counter on [Outcome.GAVE_UP] is exactly the
 * call failure rate.
 *
 * ### The two paths that emit no terminal event
 *
 * A refusal thrown by `callRaw`'s `inspectBeforeBody` emits [Outcome.GAVE_UP] and no terminal event:
 * the caller rejected a response it had already seen the head of, on its own policy grounds, and
 * reporting that as a service or transport outcome would file the caller's decision as AWS's
 * failure. The call still failed, so the give-up still fires.
 *
 * A [kotlin.coroutines.cancellation.CancellationException] from the caller's own scope emits
 * nothing at all. A cancelled call has no outcome — the caller stopped asking.
 *
 * @param operation the operation name passed to `callRaw` (`"GetItem"`, `"PutEvents"`), or null for
 *   a raw call that named none — every `aws-s3` call, for instance.
 * @param attempt 1-based. A decision event repeats the number of the attempt it follows.
 * @param statusCode the HTTP status, or null when the attempt never got a response. Non-null on a
 *   [Outcome.TRANSPORT_FAILURE] raised by `validateBody`, which rejects a response that *did*
 *   arrive.
 * @param errorCode the parsed AWS error code (`"ThrottlingException"`), when the response carried
 *   one.
 * @param willRetryAfterMillis the delay about to be slept, non-null **exactly** on
 *   [Outcome.RETRY_SCHEDULED].
 * @param durationMillis measured with the client's injected clock from the start of the attempt —
 *   taken *before* credentials are resolved, so it includes credential resolution and signing as
 *   well as the round trip. That is the number a caller cares about: a slow IMDS hop delays the
 *   request just as surely as a slow response does, and an attempt duration that hid it would send
 *   someone hunting through the wrong service's latency.
 */
public class AwsCallEvent(
    public val operation: String?,
    public val attempt: Int,
    public val outcome: Outcome,
    public val statusCode: Int?,
    public val errorCode: String?,
    public val willRetryAfterMillis: Long?,
    public val durationMillis: Long,
) {
    /** What an [AwsCallEvent] reports. See the class KDoc for which are terminal. */
    public enum class Outcome {
        /** Terminal: the attempt returned a response the client accepted. */
        SUCCESS,

        /** Terminal: AWS answered with an error status. Retryable or not. */
        SERVICE_ERROR,

        /**
         * Terminal: no usable response. Either the send threw, or the body arrived and `validateBody`
         * found it defective — in which case [statusCode] is the status that arrived.
         */
        TRANSPORT_FAILURE,

        /** Decision: another attempt follows in [willRetryAfterMillis] ms. */
        RETRY_SCHEDULED,

        /** Decision: signing time was corrected against the server's clock; retrying immediately. */
        CLOCK_SKEW_CORRECTED,

        /** Decision: the call is over and is about to throw. Exactly one per failed call. */
        GAVE_UP,
    }

    override fun toString(): String = buildString {
        append("AwsCallEvent(")
        operation?.let { append(it).append(' ') }
        append(outcome).append(" attempt=").append(attempt)
        statusCode?.let { append(" status=").append(it) }
        errorCode?.let { append(" code=").append(it) }
        willRetryAfterMillis?.let { append(" retryIn=").append(it).append("ms") }
        append(" took=").append(durationMillis).append("ms)")
    }
}

/**
 * Notified of every attempt an [AwsServiceClient] makes.
 *
 * Deliberately **not** a suspending function. It is called from inside the retry loop, on the path
 * of a request in flight, and a seam that could suspend there would let an observer's own I/O — a
 * metrics flush, a log write — add latency to the call it is measuring. Do the work off this thread:
 * increment a counter, push onto a queue, and return.
 *
 * ### It is called concurrently
 *
 * One [AwsServiceClient] is shared by every coroutine in a handler, and `Dispatchers.Default` is
 * multi-threaded on the JVM and on Kotlin/Native alike, so an implementation must be thread-safe.
 * A `mutableListOf` accumulating events from a handler that fans out with `async { }` is a data
 * race, not a metric.
 *
 * ### A throwing observer cannot fail the call
 *
 * Every invocation is wrapped, and anything thrown is swallowed. Instrumentation is not allowed to
 * break the thing it instruments, and the failure mode without this is nasty in a specific way: a
 * null-pointer in a metrics tag surfaces as an AWS call that "failed", so the outage looks like it
 * is in DynamoDB.
 *
 * The single exception is [kotlin.coroutines.cancellation.CancellationException], which is rethrown.
 * Swallowing that would keep a cancelled coroutine running — cancellation is structured concurrency
 * talking, not an error the observer raised.
 *
 * ### Example: attempt counts and latency, per operation
 *
 * ```kotlin
 * val dynamo = DynamoDb {
 *     observer = AwsCallObserver { event ->
 *         when (event.outcome) {
 *             AwsCallEvent.Outcome.SUCCESS,
 *             AwsCallEvent.Outcome.SERVICE_ERROR,
 *             AwsCallEvent.Outcome.TRANSPORT_FAILURE -> {
 *                 // One per attempt: a latency histogram, and the retry rate falls out of
 *                 // `attempt > 1`.
 *                 metrics.timing("aws.attempt", event.durationMillis, tags(event))
 *             }
 *             AwsCallEvent.Outcome.RETRY_SCHEDULED ->
 *                 metrics.timing("aws.retry.backoff", event.willRetryAfterMillis!!, tags(event))
 *             AwsCallEvent.Outcome.CLOCK_SKEW_CORRECTED ->
 *                 metrics.increment("aws.clock_skew", tags(event))
 *             // One per failed call, so this is the call failure rate.
 *             AwsCallEvent.Outcome.GAVE_UP -> metrics.increment("aws.gave_up", tags(event))
 *         }
 *     }
 * }
 * ```
 *
 * Note the [AwsCallEvent.errorCode] tag: cardinality is bounded by AWS's error-code vocabulary, but
 * [AwsCallEvent.operation] plus [AwsCallEvent.errorCode] plus [AwsCallEvent.statusCode] is already a
 * three-dimensional tag set. Pick the dimensions the dashboard needs rather than all of them.
 */
public fun interface AwsCallObserver {
    public fun onAttempt(event: AwsCallEvent)
}
