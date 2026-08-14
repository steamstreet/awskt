package com.steamstreet.awskt.core

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How a retryable failure is paced. Throttling backs off far harder than a transient blip. */
public enum class RetryErrorType { TRANSIENT, THROTTLING }

/**
 * Whether a call may be retried when we cannot tell whether the request reached AWS.
 *
 * Per-**request**, not per-client and not even per-operation: `PutItem` is replayable, and the same
 * `PutItem` carrying `ConditionExpression` or `ReturnValues=ALL_OLD` is not. A service module that
 * picks this value from the operation name alone will be wrong for the operations whose safety the
 * caller decides — see `aws-dynamodb`'s `writeSafety` and `aws-s3`'s `PutObject`/`ifNoneMatch`.
 */
public enum class OperationSafety {
    /** Replaying is harmless: the second call produces the same end state *and the same answer*. */
    IDEMPOTENT,

    /**
     * Replaying may double-apply, or may report a failure for work that actually succeeded.
     *
     * `UpdateItem` is the motivating case for double-application: `MutableItem` emits an
     * unconditional numeric `ADD` and a `list_append`, and `dynamokt-exposed` emits `ADD` too. If
     * the bytes reached DynamoDB and the socket died before the response came back, a retry
     * increments twice.
     *
     * The second failure mode does not touch the end state at all. A conditional create replayed
     * after its own successful write fails the condition and surfaces
     * `ConditionalCheckFailedException`; a replayed `CreateTable` surfaces `ResourceInUseException`.
     * Both are the transport lying to the caller about a write that landed.
     */
    NOT_IDEMPOTENT,
}

/**
 * Error codes that must **never** be retried, consulted *before* the retryable-code table.
 *
 * Ordering it first is the point: it means a later well-meaning addition to the retryable table
 * cannot make an atomic write replayable.
 */
internal val NEVER_RETRY_CODES: Set<String> = setOf(
    "TransactionCanceledException",
    "IdempotentParameterMismatchException",
    "ValidationException",
    "ConditionalCheckFailedException",
    "ResourceNotFoundException",
    "PermanentRedirect",
)

/**
 * Ported verbatim from `aws-sdk-kotlin`'s `AwsRetryPolicy.kt` (17 entries), plus one addition.
 *
 * The addition is `ConditionalRequestConflict`: AWS documents that a conflicting concurrent
 * operation during a conditional write returns HTTP 409 with that code and should be retried, and
 * v1 ships `PutObject` with `ifNoneMatch`. It is absent from the SDK's map entirely — there is no
 * 409 entry at all — so without it the conditional-write path arrives without its documented retry.
 */
internal val KNOWN_ERROR_TYPES: Map<String, RetryErrorType> = mapOf(
    "BandwidthLimitExceeded" to RetryErrorType.THROTTLING,
    "EC2ThrottledException" to RetryErrorType.THROTTLING,
    "IDPCommunicationError" to RetryErrorType.TRANSIENT,
    "LimitExceededException" to RetryErrorType.THROTTLING,
    "PriorRequestNotComplete" to RetryErrorType.THROTTLING,
    "ProvisionedThroughputExceededException" to RetryErrorType.THROTTLING,
    "RequestLimitExceeded" to RetryErrorType.THROTTLING,
    "RequestThrottled" to RetryErrorType.THROTTLING,
    "RequestThrottledException" to RetryErrorType.THROTTLING,
    "RequestTimeout" to RetryErrorType.TRANSIENT,
    "RequestTimeoutException" to RetryErrorType.TRANSIENT,
    "SlowDown" to RetryErrorType.THROTTLING,
    "ThrottledException" to RetryErrorType.THROTTLING,
    "Throttling" to RetryErrorType.THROTTLING,
    "ThrottlingException" to RetryErrorType.THROTTLING,
    "TooManyRequestsException" to RetryErrorType.THROTTLING,
    "TransactionInProgressException" to RetryErrorType.THROTTLING,

    // Addition — see the KDoc above.
    "ConditionalRequestConflict" to RetryErrorType.TRANSIENT,
)

internal val KNOWN_STATUS_CODES: Map<Int, RetryErrorType> = mapOf(
    500 to RetryErrorType.TRANSIENT,
    502 to RetryErrorType.TRANSIENT,
    503 to RetryErrorType.TRANSIENT,
    504 to RetryErrorType.TRANSIENT,
)

/**
 * Classifies a service response.
 *
 * **Code beats status**, matching AWS. It matters concretely for S3: a 503 carrying `SlowDown`
 * resolves to THROTTLING with a 1-second base rather than TRANSIENT with 25 ms, so a bucket that
 * is shedding load is not hammered with near-immediate retries.
 */
internal fun classifyRetry(code: String?, status: Int): RetryErrorType? {
    if (code != null && code in NEVER_RETRY_CODES) return null
    return code?.let { KNOWN_ERROR_TYPES[it] } ?: KNOWN_STATUS_CODES[status]
}

/**
 * Retry pacing and limits.
 *
 * @param maxTotalRetryDuration a ceiling on the whole call, not on one sleep. Four attempts at the
 *   20-second cap plus a clamped `x-amz-retry-after` can burn roughly a minute inside a Lambda with
 *   a 30-second timeout, silently converting a retryable throttle into an unreported timeout.
 */
public class RetryConfig(
    public val maxAttempts: Int = 4,
    public val transientBaseDelayMillis: Long = 25,
    public val throttlingBaseDelayMillis: Long = 1_000,
    public val maxBackoffMillis: Long = 20_000,
    public val maxTotalRetryDuration: Duration = 25.seconds,
    /**
     * Opt in to retrying a [OperationSafety.NOT_IDEMPOTENT] operation after an ambiguous transport
     * failure. Off by default, and an explicit behavioural difference from the AWS SDK.
     */
    public val retryAmbiguousWrites: Boolean = false,
)

/**
 * Full-jitter backoff: `random(0,1) * min(cap, base * 2^attempt)`.
 *
 * Jitter is not decoration. Without it, every client throttled by the same event retries in the
 * same millisecond and re-creates the throttle.
 *
 * Public because [AwsServiceClient]'s retry loop is not the only one in the library. An operation
 * that reports partial throttling *in a 200 response* — `BatchGetItem`'s `UnprocessedKeys` and
 * `BatchWriteItem`'s `UnprocessedItems` — never reaches the transport's retry path, so the service
 * module has to pace its own resubmissions. It should pace them with this formula rather than a
 * second, subtly different one; see `aws-dynamodb`'s `BatchRetry`.
 */
public fun backoffMillis(
    type: RetryErrorType,
    attempt: Int,
    config: RetryConfig,
    random: () -> Double,
): Long {
    val base = when (type) {
        RetryErrorType.TRANSIENT -> config.transientBaseDelayMillis
        RetryErrorType.THROTTLING -> config.throttlingBaseDelayMillis
    }
    val exponential = base.toDouble() * (1L shl attempt.coerceAtMost(30)).toDouble()
    val capped = minOf(config.maxBackoffMillis.toDouble(), exponential)
    return (random() * capped).toLong().coerceAtLeast(0)
}

/**
 * Applies the server's `x-amz-retry-after` hint.
 *
 * The header is in **milliseconds**, and is *not* the RFC `Retry-After` seconds header. Reading it
 * as seconds turns a 3-second pause into a 50-minute one. Clamped so a hostile or buggy value
 * cannot extend the wait indefinitely.
 */
internal fun applyRetryAfter(computedMillis: Long, retryAfterHeader: String?): Long {
    val hinted = retryAfterHeader?.trim()?.toLongOrNull() ?: return computedMillis
    return hinted.coerceIn(computedMillis, computedMillis + 5_000)
}

/**
 * Circuit breaker over retries.
 *
 * Capacity is consumed by retrying and returned by succeeding, so a dependency that is failing
 * broadly stops absorbing retry traffic instead of amplifying an outage. There is no time-based
 * refill: recovery is driven by successful calls, which is the only real evidence of recovery.
 *
 * ### Why this is atomic, and why it is not a `Mutex`
 *
 * One [AwsServiceClient] is shared by every coroutine in a handler, and `Dispatchers.Default` is
 * multi-threaded on the JVM *and* on Kotlin/Native. A plain `var` here would make every operation a
 * read-modify-write on shared state: two coroutines each read the same `tokens`, each conclude
 * there is capacity, and each subtract — so the breaker admits retries it exists to refuse, and the
 * count drifts below zero. Under Kotlin/Native's current memory model there is no freeze to make
 * that fail loudly; it just races.
 *
 * A CAS loop rather than a [kotlinx.coroutines.sync.Mutex] because [tryAcquire] is consulted on
 * every retry decision and must not suspend. Uncontended, each operation here is a single
 * compare-and-set.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class RetryTokenBucket(private val capacity: Int = 500) {
    private val tokens = AtomicInt(capacity)

    val available: Int get() = tokens.load()

    fun tryAcquire(type: RetryErrorType): Boolean {
        val cost = costOf(type)
        while (true) {
            val current = tokens.load()
            // Re-checked inside the loop, not once before it: the check and the subtraction have to
            // succeed against the *same* observed value or the capacity bound means nothing.
            if (current < cost) return false
            if (tokens.compareAndSet(current, current - cost)) return true
        }
    }

    fun refund(type: RetryErrorType) {
        credit(costOf(type))
    }

    fun onCleanSuccess() {
        credit(1)
    }

    /** Adds [amount], saturating at [capacity]. */
    private fun credit(amount: Int) {
        while (true) {
            val current = tokens.load()
            if (current >= capacity) return
            if (tokens.compareAndSet(current, minOf(capacity, current + amount))) return
        }
    }

    private fun costOf(type: RetryErrorType) = when (type) {
        RetryErrorType.TRANSIENT -> 14
        RetryErrorType.THROTTLING -> 5
    }
}

internal fun defaultRandom(): Double = Random.nextDouble()
