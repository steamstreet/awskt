package com.steamstreet.awskt.core

import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Pacing for the resubmission loops that batch operations need on top of the transport's retry.
 *
 * ### Why those loops need their own backoff at all
 *
 * Several AWS batch operations report *partial* failure inside an **HTTP 200**: DynamoDB's
 * `UnprocessedKeys`/`UnprocessedItems`, and EventBridge's `PutEvents` per-entry `ErrorCode`. There
 * is no error code on the response and no `x-amz-retry-after` header, so [AwsServiceClient]'s retry
 * loop never sees them and never paces them — yet a non-empty unprocessed set means exactly what a
 * `ProvisionedThroughputExceededException` means, and a per-entry `ThrottlingException` means
 * exactly what a whole-request one means: the service shed load. Resubmitting with no delay almost
 * always meets the same throttle and adds load to a service that is already shedding it, which is
 * why AWS documents exponential backoff as the required handling for these fields.
 *
 * This class lives in `aws-core` rather than in either service module because both of them need it,
 * and because two subtly different backoff implementations would be worse than one shared with
 * [backoffMillis]. Its consumers today are `aws-dynamodb`'s `batchGetAll`/`batchWriteAll` and
 * `aws-eventbridge`'s `putEventsAll`.
 *
 * ### Shape
 *
 * [random] and [sleep] mirror the seam on [AwsServiceClient], for the same reason: the backoff has
 * to be assertable in a unit test without the test actually sleeping. There is deliberately no
 * clock here — the budget below is measured by summing what was *requested* of [sleep], so a test
 * that injects a no-op sleep still exercises the real arithmetic.
 *
 * @param config supplies the pacing. `THROTTLING` base (1s) is used rather than `TRANSIENT` (25ms)
 *   because a partially-failed batch *is* a throttle, and [RetryConfig.maxTotalRetryDuration] bounds
 *   the total backoff **per chunk**, matching how the transport bounds it per call. That bound is
 *   not optional: without it, ten rounds of throttling backoff can block for roughly two and a half
 *   minutes, silently converting a fast partial failure into a Lambda timeout.
 */
public class BatchRetry(
    public val config: RetryConfig = RetryConfig(),
    internal val random: () -> Double = { Random.nextDouble() },
    internal val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * Waits before resubmitting, and reports whether the budget allowed it.
     *
     * Public — rather than the private extension it started life as in `aws-dynamodb` — because
     * [random] and [sleep] are internal to this module, so a resubmission loop in a service module
     * cannot re-derive this arithmetic even if it wanted to. One implementation, called by all of
     * them, is also the only way the budget means the same thing everywhere.
     *
     * @param retry 0-based index of the resubmission about to be made, so the first one waits
     *   `random * base` rather than `random * 2 * base` — the same off-by-one the transport avoids
     *   by passing `attempt - 1`. Callers that count rounds from one pass `round - 1`.
     * @param sleptMillis backoff already spent on this chunk. The caller owns this accumulator, and
     *   it is per *chunk* rather than per call: a batch split into many chunks would otherwise leave
     *   the later chunks with no backoff left to spend.
     * @return the new cumulative backoff — assign it back over [sleptMillis] — or `null` if sleeping
     *   again would exceed [RetryConfig.maxTotalRetryDuration], in which case nothing was slept and
     *   the caller must give up rather than resubmit.
     */
    public suspend fun awaitResubmit(retry: Int, sleptMillis: Long): Long? {
        val wait = backoffMillis(RetryErrorType.THROTTLING, retry, config, random)
        val total = sleptMillis + wait
        if (total > config.maxTotalRetryDuration.inWholeMilliseconds) return null
        sleep(wait)
        return total
    }

    public companion object {
        /** Shared so the common case does not allocate a config per batch call. */
        public val Default: BatchRetry = BatchRetry()
    }
}
