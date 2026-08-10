package com.steamstreet.awskt.core

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How a retryable failure is paced. Throttling backs off far harder than a transient blip. */
public enum class RetryErrorType { TRANSIENT, THROTTLING }

/**
 * Whether an operation may be retried when we cannot tell whether the request reached AWS.
 *
 * This is per-operation, not per-client, because it is a property of what the request *does*.
 */
public enum class OperationSafety {
    /** Replaying is harmless: the second call produces the same end state as the first. */
    IDEMPOTENT,

    /**
     * Replaying may double-apply.
     *
     * `UpdateItem` is the motivating case: `MutableItem` emits an unconditional numeric `ADD` and
     * a `list_append`, and `dynamokt-exposed` emits `ADD` too. If the bytes reached DynamoDB and
     * the socket died before the response came back, a retry increments twice.
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
 */
internal fun backoffMillis(
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
 */
internal class RetryTokenBucket(private val capacity: Int = 500) {
    private var tokens: Int = capacity

    val available: Int get() = tokens

    fun tryAcquire(type: RetryErrorType): Boolean {
        val cost = costOf(type)
        if (tokens < cost) return false
        tokens -= cost
        return true
    }

    fun refund(type: RetryErrorType) {
        tokens = minOf(capacity, tokens + costOf(type))
    }

    fun onCleanSuccess() {
        tokens = minOf(capacity, tokens + 1)
    }

    private fun costOf(type: RetryErrorType) = when (type) {
        RetryErrorType.TRANSIENT -> 14
        RetryErrorType.THROTTLING -> 5
    }
}

internal fun defaultRandom(): Double = Random.nextDouble()
