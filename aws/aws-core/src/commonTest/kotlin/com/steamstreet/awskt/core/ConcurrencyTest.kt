package com.steamstreet.awskt.core

import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One [AwsServiceClient] shared across parallel coroutines.
 *
 * This is not a hypothetical arrangement — it is the intended one. A handler builds a client once,
 * then fans out with `async { }`, and those coroutines run on `Dispatchers.Default`, which is
 * multi-threaded on the JVM *and* on Kotlin/Native. Kotlin/Native's current memory model has no
 * freeze to make unsynchronised shared state fail loudly, so the only thing standing between that
 * arrangement and a silent race is a test that actually runs the calls in parallel.
 *
 * `runTest` alone would not: its scheduler is single-threaded and would serialise every coroutine
 * below, which is exactly the interleaving that cannot expose the bug. Every test here therefore
 * steps out to `Dispatchers.Default` explicitly.
 */

/** Enough concurrency to interleave, small enough to stay fast on a CI box with few cores. */
private const val WORKERS = 8
private const val PER_WORKER = 2_000

/** [RetryErrorType.THROTTLING]'s cost, restated so the arithmetic below is readable. */
private const val THROTTLE_COST = 5

/** [RetryTokenBucket]'s default capacity, which is what an [AwsServiceClient] builds itself with. */
private const val DEFAULT_BUCKET_CAPACITY = 500

@OptIn(ExperimentalAtomicApi::class)
class RetryTokenBucketConcurrencyTest {

    /**
     * The lost-update test, and the sharpest of the three.
     *
     * Capacity is sized so that every acquire *must* succeed and the bucket *must* land on exactly
     * zero. A read-modify-write on a plain `var` fails this loudly: two coroutines read the same
     * count, both subtract from that same stale value, and one of the two decrements evaporates —
     * so the bucket finishes above zero, having handed out more capacity than it spent.
     */
    @Test
    fun parallelAcquiresSpendExactlyWhatTheyTake() = runTest {
        val acquires = WORKERS * PER_WORKER
        val bucket = RetryTokenBucket(capacity = acquires * THROTTLE_COST)

        withContext(Dispatchers.Default) {
            List(WORKERS) {
                async {
                    repeat(PER_WORKER) {
                        assertTrue(
                            bucket.tryAcquire(RetryErrorType.THROTTLING),
                            "capacity was sized for every acquire to succeed",
                        )
                    }
                }
            }.awaitAll()
        }

        assertEquals(
            0,
            bucket.available,
            "$acquires acquires at $THROTTLE_COST each must drain the bucket exactly; " +
                "a surplus means decrements were lost to a read-modify-write race",
        )
    }

    /**
     * The same race stated as the behaviour that actually matters.
     *
     * The bucket is a circuit breaker. Over-admitting is not a counting curiosity: it means a
     * dependency that is already failing broadly gets *more* retry traffic than the breaker was
     * built to allow, which is the amplification the breaker exists to prevent. Capacity here is
     * sized for exactly [permitted] grants against eight times that many contenders.
     */
    @Test
    fun theBreakerNeverAdmitsMoreThanItsCapacity() = runTest {
        val permitted = 50
        // Several rounds: a race is probabilistic, and one round can get lucky.
        repeat(20) { round ->
            val bucket = RetryTokenBucket(capacity = permitted * THROTTLE_COST)
            val granted = AtomicInt(0)

            withContext(Dispatchers.Default) {
                List(permitted * 8) {
                    async {
                        if (bucket.tryAcquire(RetryErrorType.THROTTLING)) granted.incrementAndFetch()
                    }
                }.awaitAll()
            }

            assertEquals(permitted, granted.load(), "round $round admitted the wrong number of retries")
            assertTrue(bucket.available >= 0, "round $round drove the bucket negative: ${bucket.available}")
        }
    }

    /**
     * The credit side of the same state.
     *
     * [RetryTokenBucket.refund] and [RetryTokenBucket.onCleanSuccess] are also read-modify-writes,
     * and they run on the *success* path — the common one. Losing credits is the quieter failure:
     * the breaker never fully recovers, so a client that saw one bad minute keeps refusing retries
     * long after the dependency came back.
     */
    @Test
    fun parallelRefundsRestoreExactlyCapacityAndNeverOvershoot() = runTest {
        val acquires = WORKERS * PER_WORKER
        val capacity = acquires * THROTTLE_COST
        val bucket = RetryTokenBucket(capacity = capacity)

        repeat(acquires) { bucket.tryAcquire(RetryErrorType.THROTTLING) }
        assertEquals(0, bucket.available, "precondition: drained")

        withContext(Dispatchers.Default) {
            List(WORKERS) {
                async { repeat(PER_WORKER) { bucket.refund(RetryErrorType.THROTTLING) } }
            }.awaitAll()
        }

        assertEquals(capacity, bucket.available, "every refund must be credited exactly once")
    }

    /**
     * [RetryTokenBucket.onCleanSuccess] is the other credit path, and the most frequently executed
     * line in the class — it runs on every first-attempt success, which on a healthy client is every
     * call.
     *
     * Capacity is sized to exactly the number of increments so that the run must land on it dead on.
     * Sizing it *below* the increment count would let the saturation cap paper over every lost
     * update and the test would pass against a plain `var`.
     */
    @Test
    fun parallelCleanSuccessesCreditExactlyOnceEach() = runTest {
        val credits = WORKERS * PER_WORKER
        val bucket = RetryTokenBucket(capacity = credits)

        repeat(credits / THROTTLE_COST) { bucket.tryAcquire(RetryErrorType.THROTTLING) }
        assertEquals(0, bucket.available, "precondition: drained")

        withContext(Dispatchers.Default) {
            List(WORKERS) { async { repeat(PER_WORKER) { bucket.onCleanSuccess() } } }.awaitAll()
        }

        assertEquals(credits, bucket.available, "must refill to capacity exactly, and never past it")
    }
}

@OptIn(ExperimentalAtomicApi::class)
class AwsServiceClientConcurrencyTest {

    private val credentials = AwsCredentials(
        "AKIDEXAMPLE",
        "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
        "FQoGZXIvYXdzEExampleSessionToken==",
    )

    /**
     * Many coroutines, one client, one shared retry token bucket.
     *
     * Every call is throttled once and then succeeds, so each takes a token, sleeps, and hands it
     * straight back. The run is therefore *balanced*, and the client must finish at exactly full
     * capacity — which is the assertion that bites: interleaved acquires and refunds on a plain
     * `var` lose updates in both directions and the budget drifts off 500. The drift is the bug that
     * matters in production, because it is cumulative: a long-lived client in a warm Lambda leaks a
     * little capacity per call until the breaker refuses retries it should be allowing.
     */
    @Test
    fun oneClientServesManyParallelCallsWithoutLeakingRetryCapacity() = runTest {
        val calls = 200
        val attempts = AtomicInt(0)

        val engine = MockEngine { request ->
            attempts.incrementAndFetch()
            // Decided from the request alone. MockEngine runs its handler on the calling coroutine,
            // so it is invoked concurrently here — any shared mutable bookkeeping inside it would be
            // a second race sitting on top of the one under test. The client stamps each attempt
            // with `amz-sdk-request: attempt=N`, which is all this needs.
            val firstAttempt = request.headers["amz-sdk-request"]?.startsWith("attempt=1;") == true
            if (firstAttempt) {
                respond(
                    """{"__type":"com.amazon.coral.service#ThrottlingException","Message":"slow down"}""",
                    HttpStatusCode.BadRequest,
                )
            } else {
                respond("""{"ok":true}""", HttpStatusCode.OK)
            }
        }

        val client = AwsServiceClient(
            httpClient = HttpClient(engine) {
                followRedirects = false
                expectSuccess = false
            },
            credentialsProvider = StaticCredentialsProvider(credentials),
            endpoint = parseEndpoint("https://dynamodb.us-west-2.amazonaws.com"),
            region = "us-west-2",
            protocol = AwsProtocol.awsJson1_0("dynamodb", "DynamoDB_20120810"),
            retryConfig = RetryConfig(maxAttempts = 3),
            clock = { 1_700_000_000_000 },
            random = { 0.0 },
            // Not a real sleep: the retry pacing is not what is under test, and a real backoff would
            // make this the slowest test in the module for no added coverage.
            sleep = { },
        )

        val responses = withContext(Dispatchers.Default) {
            List(calls) { async { client.callRaw("POST", operation = "GetItem") } }.awaitAll()
        }

        assertEquals(calls, responses.size)
        assertTrue(responses.all { it.isSuccess }, "every call had capacity for its one retry")
        assertEquals(
            calls * 2,
            attempts.load(),
            "each call is exactly one throttled attempt plus one successful retry",
        )
        assertEquals(
            DEFAULT_BUCKET_CAPACITY,
            client.tokenBucket.available,
            "$calls balanced acquire/refund pairs must leave the retry budget exactly where it " +
                "started; any other number is capacity lost or invented by a race",
        )
    }

    /**
     * The same invariant with far more churn per call.
     *
     * Every attempt is throttled, so each call runs the full `maxAttempts` and takes and returns a
     * token three times over instead of once — three times the acquire/refund traffic through the
     * one shared bucket, and a correspondingly wider window for two coroutines to read the same
     * count. The budget must still come back to exactly full.
     */
    @Test
    fun aParallelRunThatExhaustsEveryRetryStillBalancesTheBudget() = runTest {
        val calls = 100
        val maxAttempts = 4
        val attempts = AtomicInt(0)

        val engine = MockEngine {
            attempts.incrementAndFetch()
            respond(
                """{"__type":"com.amazon.coral.service#ThrottlingException","Message":"slow down"}""",
                HttpStatusCode.BadRequest,
            )
        }

        val client = AwsServiceClient(
            httpClient = HttpClient(engine) {
                followRedirects = false
                expectSuccess = false
            },
            credentialsProvider = StaticCredentialsProvider(credentials),
            endpoint = parseEndpoint("https://dynamodb.us-west-2.amazonaws.com"),
            region = "us-west-2",
            protocol = AwsProtocol.awsJson1_0("dynamodb", "DynamoDB_20120810"),
            retryConfig = RetryConfig(maxAttempts = maxAttempts),
            clock = { 1_700_000_000_000 },
            random = { 0.0 },
            sleep = { },
        )

        val outcomes = withContext(Dispatchers.Default) {
            List(calls) {
                async {
                    try {
                        client.callRaw("POST", operation = "GetItem")
                        null
                    } catch (e: AwsServiceException) {
                        e
                    }
                }
            }.awaitAll()
        }

        assertTrue(outcomes.all { it != null }, "every call is throttled on every attempt")
        assertEquals(
            calls * maxAttempts,
            attempts.load(),
            "no call may be denied a retry: the budget is far larger than this run consumes at once",
        )
        assertEquals(
            DEFAULT_BUCKET_CAPACITY,
            client.tokenBucket.available,
            "every one of the ${calls * (maxAttempts - 1)} acquire/refund pairs must net to zero",
        )
    }

    /**
     * The learned clock-skew offset is written by one call's response path and read by the signing
     * path of every other in-flight call.
     *
     * The skew is learned first, on its own, and only then read in parallel — so this asserts the
     * property that a plain `var` cannot promise across threads: that the correction, once learned,
     * is visible to coroutines signing on other threads. Without it those coroutines keep signing
     * with an uncorrected clock and AWS keeps rejecting them, which presents as a client that works
     * on a machine with a good clock and fails on one without.
     */
    @Test
    fun theLearnedClockSkewIsVisibleToParallelSigners() = runTest {
        val now = 1_700_000_000_000L
        val skewMillis = 15 * 60 * 1_000L
        // What the corrected signing time should produce, in SigV4's basic-format date-time.
        val correctedStamp = amzDateStamp(now + skewMillis)

        val sawUncorrected = AtomicInt(0)
        val engine = MockEngine { request ->
            if (request.headers["x-amz-date"] == correctedStamp) {
                respond("""{"ok":true}""", HttpStatusCode.OK)
            } else {
                sawUncorrected.incrementAndFetch()
                respond(
                    """{"__type":"com.amazon.coral.service#RequestTimeTooSkewed","Message":"skewed"}""",
                    HttpStatusCode.Forbidden,
                    // The client learns the offset from this header, not from the code.
                    headersOf("Date", httpDate(now + skewMillis)),
                )
            }
        }

        val client = AwsServiceClient(
            httpClient = HttpClient(engine) {
                followRedirects = false
                expectSuccess = false
            },
            credentialsProvider = StaticCredentialsProvider(credentials),
            endpoint = parseEndpoint("https://s3.us-west-2.amazonaws.com"),
            region = "us-west-2",
            protocol = AwsProtocol.awsJson1_0("dynamodb", "DynamoDB_20120810"),
            retryConfig = RetryConfig(maxAttempts = 3),
            clock = { now },
            random = { 0.0 },
            sleep = { },
        )

        // One call, alone, teaches the client the offset.
        assertTrue(client.callRaw("POST", operation = "GetItem").isSuccess)
        val afterLearning = sawUncorrected.load()
        assertEquals(1, afterLearning, "exactly one rejection was needed to learn the offset")

        val responses = withContext(Dispatchers.Default) {
            List(64) { async { client.callRaw("POST", operation = "GetItem") } }.awaitAll()
        }

        assertTrue(responses.all { it.isSuccess })
        assertEquals(
            afterLearning,
            sawUncorrected.load(),
            "every parallel signer must see the already-learned offset; a rejection here means one " +
                "of them signed with an uncorrected clock",
        )
    }
}

/** `yyyyMMdd'T'HHmmss'Z'`, matching what the signer puts in `x-amz-date`. */
private fun amzDateStamp(epochMillis: Long): String {
    val (y, mo, d, h, mi, s) = civilFromEpochMillis(epochMillis)
    fun p(v: Int, w: Int) = v.toString().padStart(w, '0')
    return "${p(y, 4)}${p(mo, 2)}${p(d, 2)}T${p(h, 2)}${p(mi, 2)}${p(s, 2)}Z"
}

/** An RFC 7231 IMF-fixdate, which is what the `Date` response header carries. */
private fun httpDate(epochMillis: Long): String {
    val (y, mo, d, h, mi, s) = civilFromEpochMillis(epochMillis)
    val days = epochMillis.floorDiv(86_400_000L)
    val dow = listOf("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed")[(days.mod(7L)).toInt()]
    val month = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )[mo - 1]
    fun p(v: Int, w: Int) = v.toString().padStart(w, '0')
    return "$dow, ${p(d, 2)} $month $y ${p(h, 2)}:${p(mi, 2)}:${p(s, 2)} GMT"
}

private data class Civil(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

/** Howard Hinnant's civil-from-days, the same algorithm `parseHttpDateOrNull` inverts. */
private fun civilFromEpochMillis(epochMillis: Long): Civil {
    val totalSeconds = epochMillis.floorDiv(1_000L)
    val days = totalSeconds.floorDiv(86_400L)
    val secondOfDay = totalSeconds.mod(86_400L)

    val z = days + 719_468L
    val era = (if (z >= 0) z else z - 146_096L) / 146_097L
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
    val y = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val d = doy - (153L * mp + 2L) / 5L + 1L
    val m = if (mp < 10L) mp + 3L else mp - 9L

    return Civil(
        year = (if (m <= 2L) y + 1L else y).toInt(),
        month = m.toInt(),
        day = d.toInt(),
        hour = (secondOfDay / 3_600L).toInt(),
        minute = ((secondOfDay % 3_600L) / 60L).toInt(),
        second = (secondOfDay % 60L).toInt(),
    )
}
