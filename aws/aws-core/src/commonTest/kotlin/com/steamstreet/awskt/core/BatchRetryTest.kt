package com.steamstreet.awskt.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * [BatchRetry.awaitResubmit]'s arithmetic, tested directly.
 *
 * The service modules that use it (`aws-dynamodb`'s `batchGetAll`, `aws-eventbridge`'s
 * `putEventsAll`) assert the *sequence* they end up sleeping, which is the behaviour that matters
 * to their callers. These tests pin the two properties those assertions rest on — the off-by-one in
 * the retry index, and the fact that the budget refuses rather than truncates — in the module that
 * owns them, so a change here fails once instead of in every consumer.
 */
class BatchRetryTest {

    /**
     * `random` is pinned to 0.5 rather than 1.0 so this distinguishes full jitter from a bare
     * exponential: 50/100/200/400 is half of 100/200/400/800.
     *
     * `currentTime` staying at zero is the load-bearing assertion for "injected sleep, not
     * wall-clock". `runTest` runs on a virtual clock, so a real `delay(400)` would not slow this
     * test down — it would advance `currentTime` to 400 instead.
     */
    @Test
    fun eachResubmissionWaitsTwiceAsLongAsTheLast() = runTest {
        val slept = mutableListOf<Long>()
        val backoff = BatchRetry(
            config = RetryConfig(throttlingBaseDelayMillis = 100),
            random = { 0.5 },
            sleep = { slept += it },
        )

        var total = 0L
        repeat(4) { retry -> total = backoff.awaitResubmit(retry, total)!! }

        assertEquals(listOf(50L, 100L, 200L, 400L), slept)
        assertEquals(750L, total, "the return value is the cumulative sleep, not the last one")
        assertEquals(0L, testScheduler.currentTime, "backoff must go through the injected sleep")
    }

    /**
     * The index is 0-based: the *first* resubmission waits `random * base`, not `random * 2 * base`.
     *
     * This is the same off-by-one the transport avoids by passing `attempt - 1`, and it is why the
     * batch loops pass `round - 1`. Getting it wrong doubles every wait in the library at once.
     */
    @Test
    fun theFirstResubmissionUsesTheUnmultipliedBase() = runTest {
        val slept = mutableListOf<Long>()
        val backoff = BatchRetry(
            config = RetryConfig(throttlingBaseDelayMillis = 100),
            random = { 1.0 },
            sleep = { slept += it },
        )

        backoff.awaitResubmit(0, 0L)

        assertEquals(listOf(100L), slept)
    }

    /**
     * Throttling pacing, not transient pacing.
     *
     * A partially-failed batch is a throttle, so it must back off on the 1-second base rather than
     * the 25 ms one. Resubmitting a throttled batch 25 ms later meets the same throttle.
     */
    @Test
    fun usesTheThrottlingBaseNotTheTransientOne() = runTest {
        val slept = mutableListOf<Long>()
        val backoff = BatchRetry(random = { 1.0 }, sleep = { slept += it })

        backoff.awaitResubmit(0, 0L)

        assertEquals(listOf(1_000L), slept, "the transient base (25ms) is the wrong pacing here")
    }

    /**
     * The budget refuses the sleep it cannot afford — it does not sleep a shortened one.
     *
     * A truncated final wait would look like it respected the budget while still resubmitting into
     * the same throttle, which is the outcome the budget exists to prevent.
     */
    @Test
    fun refusesRatherThanTruncatingWhenTheBudgetIsExhausted() = runTest {
        val slept = mutableListOf<Long>()
        val backoff = BatchRetry(
            // 100 + 200 + 400 fits in 750ms; the fourth wait of 800ms does not.
            config = RetryConfig(throttlingBaseDelayMillis = 100, maxTotalRetryDuration = 750.milliseconds),
            random = { 1.0 },
            sleep = { slept += it },
        )

        var total = 0L
        repeat(3) { retry -> total = backoff.awaitResubmit(retry, total)!! }

        assertEquals(700L, total)
        assertNull(backoff.awaitResubmit(3, total), "the fourth wait must be refused")
        assertEquals(listOf(100L, 200L, 400L), slept, "the refused wait must not have been slept")
    }

    /** The exponential is capped, so a long-running loop does not sleep for minutes at a time. */
    @Test
    fun theBackoffIsCapped() = runTest {
        val slept = mutableListOf<Long>()
        val backoff = BatchRetry(
            config = RetryConfig(
                throttlingBaseDelayMillis = 100,
                maxBackoffMillis = 500,
                maxTotalRetryDuration = 1_000_000.milliseconds,
            ),
            random = { 1.0 },
            sleep = { slept += it },
        )

        var total = 0L
        repeat(6) { retry -> total = backoff.awaitResubmit(retry, total)!! }

        assertEquals(listOf(100L, 200L, 400L, 500L, 500L, 500L), slept)
    }

    /** The shared default must not be a per-call allocation, and must carry the default config. */
    @Test
    fun theDefaultIsShared() {
        assertTrue(BatchRetry.Default === BatchRetry.Default)
        assertEquals(25_000L, BatchRetry.Default.config.maxTotalRetryDuration.inWholeMilliseconds)
    }
}
