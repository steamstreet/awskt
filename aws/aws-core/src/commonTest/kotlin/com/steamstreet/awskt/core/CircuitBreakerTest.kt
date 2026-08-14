package com.steamstreet.awskt.core

import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The retry budget as a **circuit breaker**, exercised through [AwsServiceClient] rather than
 * against the bucket directly.
 *
 * [RetryTokenBucketConcurrencyTest] covers the bucket's own arithmetic under contention. What is
 * tested here is the part that arithmetic cannot see: which calls pay, which calls are refunded, and
 * therefore whether the breaker ever opens at all. It did not, before — every acquire was refunded
 * the instant its backoff finished, so the budget bounded only how many retries could be *asleep*
 * simultaneously (roughly 35), and a warm Lambda making sequential calls against a dependency that
 * was hard down retried at full `maxAttempts` on every one of them, forever.
 *
 * Everything below is single-threaded and deterministic on purpose: a frozen clock, jitter pinned to
 * zero, and a counted no-op sleep, so every assertion is an exact number and not a range.
 */
class RetryCircuitBreakerTest {

    private val credentials = AwsCredentials(
        "AKIDEXAMPLE",
        "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
        "FQoGZXIvYXdzEExampleSessionToken==",
    )

    /** [RetryTokenBucket]'s capacity and per-retry costs, restated so the arithmetic is readable. */
    private val capacity = 500
    private val transientCost = 14

    private val now = 1_700_000_000_000L

    private fun client(
        engine: MockEngine,
        retryConfig: RetryConfig = RetryConfig(maxAttempts = 4),
        jitter: Double = 0.0,
        sleep: suspend (Long) -> Unit = { },
    ) = AwsServiceClient(
        httpClient = HttpClient(engine) {
            followRedirects = false
            expectSuccess = false
        },
        credentialsProvider = StaticCredentialsProvider(credentials),
        endpoint = parseEndpoint("https://dynamodb.us-west-2.amazonaws.com"),
        region = "us-west-2",
        protocol = AwsProtocol.awsJson1_0("dynamodb", "DynamoDB_20120810"),
        retryConfig = retryConfig,
        clock = { now },
        random = { jitter },
        sleep = sleep,
    )

    /**
     * The case the whole change exists for: a dependency that is down and stays down.
     *
     * Twenty sequential calls, none of which can succeed. The first pays for its full three retries;
     * by the end the client is making a single attempt and sleeping not at all, because the budget
     * that funds retries is empty and nothing has happened to refill it. Under the old
     * refund-after-sleep behaviour every one of the twenty would have made four attempts — eighty
     * requests aimed at something already failing, instead of fifty-five.
     *
     * The totals are exact because nothing here returns capacity: every acquire costs
     * [transientCost] and the run admits exactly `500 / 14 = 35` of them however they are
     * distributed across the calls.
     */
    @Test
    fun sequentialCallsAgainstADeadDependencyStopRetrying() = runTest {
        var attemptsInCall = 0
        var sleepsInCall = 0

        val client = client(
            MockEngine {
                attemptsInCall++
                respond("", HttpStatusCode.ServiceUnavailable)
            },
            sleep = { sleepsInCall++ },
        )

        val calls = 20
        val observed = List(calls) {
            attemptsInCall = 0
            sleepsInCall = 0
            assertFailsWith<AwsServiceException> { client.callRaw("POST", operation = "GetItem") }
            attemptsInCall to sleepsInCall
        }

        assertEquals(4 to 3, observed.first(), "the first call must still get its full retry budget")
        assertEquals(
            1 to 0,
            observed.last(),
            "once the budget is spent the client must make one attempt and not even sleep; a " +
                "second attempt here means the breaker never opened",
        )
        assertEquals(
            calls + capacity / transientCost,
            observed.sumOf { it.first },
            "one unconditional attempt per call, plus exactly ${capacity / transientCost} " +
                "retries funded by the budget",
        )
        assertEquals(
            capacity % transientCost,
            client.tokenBucket.available,
            "nothing succeeded, so nothing was returned: only the unspendable remainder is left",
        )
    }

    /**
     * ...and the recovery side of it, which is the reason the breaker is not simply a kill switch.
     *
     * The same client, after the same outage, is fed successes. Each *clean first-attempt* success
     * credits one token — the only thing that refills the bucket past what it lent out — so the
     * breaker closes again gradually and in proportion to evidence that the dependency works. The
     * threshold is asserted from both sides: three successes are not enough to fund a retry, eight
     * are.
     */
    @Test
    fun capacityRecoversFromCleanSuccessesAndTheBreakerCloses() = runTest {
        var failing = true
        var attemptsInCall = 0

        val client = client(
            MockEngine {
                attemptsInCall++
                if (failing) respond("", HttpStatusCode.ServiceUnavailable)
                else respond("""{"ok":true}""", HttpStatusCode.OK)
            },
        )

        repeat(20) { assertFailsWith<AwsServiceException> { client.callRaw("POST", operation = "GetItem") } }
        val remainder = capacity % transientCost
        assertEquals(remainder, client.tokenBucket.available, "precondition: the breaker is open")

        failing = false
        repeat(3) { assertTrue(client.callRaw("POST", operation = "GetItem").isSuccess) }
        assertEquals(
            remainder + 3,
            client.tokenBucket.available,
            "a clean success credits exactly one token, no more",
        )

        // Still short of a single retry's cost, so the breaker stays open.
        failing = true
        attemptsInCall = 0
        assertFailsWith<AwsServiceException> { client.callRaw("POST", operation = "GetItem") }
        assertEquals(1, attemptsInCall, "13 tokens do not buy a 14-token retry")

        failing = false
        repeat(5) { assertTrue(client.callRaw("POST", operation = "GetItem").isSuccess) }
        assertTrue(client.tokenBucket.available >= transientCost, "precondition: one retry is affordable")

        failing = true
        attemptsInCall = 0
        assertFailsWith<AwsServiceException> { client.callRaw("POST", operation = "GetItem") }
        assertEquals(
            2,
            attemptsInCall,
            "the recovered capacity must actually fund a retry — one attempt here means the " +
                "breaker latched open and clean successes bought nothing",
        )
    }

    /**
     * "Returned by succeeding", stated as an exact number.
     *
     * A call that retries twice and then works gets back precisely what it spent — not less, which
     * would bleed a working client's budget away one call at a time, and not more.
     *
     * The measurement is taken against a *partly drained* bucket rather than a full one, because a
     * full bucket saturates: at 500 of 500, an over-refund and a correct refund are the same number
     * and the test would assert nothing. The `+1` clean-success credit is checked in the same way,
     * and separately — it belongs only to calls that needed no retry at all, and a retried success
     * that also collected it would show up here as 459.
     */
    @Test
    fun aRetriedSuccessRefundsExactlyWhatItSpentAndNothingMore() = runTest {
        var succeedFromAttempt = Int.MAX_VALUE

        val client = client(
            MockEngine { request ->
                val attempt = request.headers["amz-sdk-request"]
                    ?.substringAfter("attempt=")?.substringBefore(';')?.toInt() ?: 1
                if (attempt >= succeedFromAttempt) respond("""{"ok":true}""", HttpStatusCode.OK)
                else respond("", HttpStatusCode.ServiceUnavailable)
            },
        )

        // Drain a known amount: one call that fails on all four attempts, spending three retries.
        assertFailsWith<AwsServiceException> { client.callRaw("POST", operation = "GetItem") }
        val afterOutage = capacity - 3 * transientCost
        assertEquals(afterOutage, client.tokenBucket.available, "precondition: 42 tokens spent")

        succeedFromAttempt = 3
        assertTrue(client.callRaw("POST", operation = "GetItem").isSuccess)
        assertEquals(
            afterOutage,
            client.tokenBucket.available,
            "the two retries this call bought cost 28 and returned 28; anything else is a leak, " +
                "and $afterOutage + 1 would mean a retried success also took the clean-success credit",
        )

        succeedFromAttempt = 1
        assertTrue(client.callRaw("POST", operation = "GetItem").isSuccess)
        assertEquals(
            afterOutage + 1,
            client.tokenBucket.available,
            "and a success that needed no retry at all credits exactly one token",
        )
    }

    /**
     * The one abort that still hands capacity straight back.
     *
     * `prepareRetry` acquires before it knows whether the backoff fits inside
     * [RetryConfig.maxTotalRetryDuration]. When it does not, the retry never happens — no request is
     * sent, no evidence about the dependency is gathered — so charging the budget for it would open
     * the breaker on a local deadline rather than on a failing service. The refund on that path is
     * kept, and this is what keeps it: a throttle whose one-second backoff cannot fit in a
     * 100 ms window leaves the budget untouched.
     */
    @Test
    fun aRetryAbandonedAtTheDeadlineGivesItsCapacityBack() = runTest {
        var attempts = 0
        var sleeps = 0

        val client = client(
            MockEngine {
                attempts++
                respond(
                    """{"__type":"com.amazon.coral.service#ThrottlingException","Message":"slow down"}""",
                    HttpStatusCode.BadRequest,
                )
            },
            retryConfig = RetryConfig(maxAttempts = 4, maxTotalRetryDuration = 100.milliseconds),
            // No jitter discount: the full one-second throttling backoff, which cannot fit.
            jitter = 1.0,
            sleep = { sleeps++ },
        )

        assertFailsWith<AwsServiceException> { client.callRaw("POST", operation = "GetItem") }

        assertEquals(1, attempts, "the retry was abandoned before it was sent")
        assertEquals(0, sleeps, "and before it slept")
        assertEquals(
            capacity,
            client.tokenBucket.available,
            "a retry that never happened must cost nothing",
        )
    }
}
