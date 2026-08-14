package com.steamstreet.awskt.s3

import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.RetryConfig
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * One [S3] instance addressing many buckets from many coroutines at once.
 *
 * S3 puts the bucket in the *authority*, so the endpoint — and therefore the signed host — differs
 * per bucket, and `DefaultS3` keeps one `AwsServiceClient` per bucket. That cache is shared state on
 * the path of every operation, and one `S3` instance serving concurrent handler coroutines is the
 * intended usage, not an exotic one. `Dispatchers.Default` is multi-threaded on the JVM and on
 * Kotlin/Native alike, so those coroutines really are on different threads.
 *
 * A plain `mutableMapOf` here is worse than a lost update: concurrent writers corrupt the
 * `LinkedHashMap` itself, and a read racing a resize can miss an entry that is present or fail to
 * terminate.
 *
 * These tests call [DefaultS3.clientFor] directly rather than going through `getObject`. That is
 * deliberate — an HTTP round trip between the cache read and the cache write serialises the callers
 * and hides the interleaving, which is exactly why an earlier version of this test caught the bug
 * only about one run in three. [operationsAcrossBucketsAllReachTheirOwnEndpoint] keeps the
 * end-to-end path covered.
 */
class S3ConcurrencyTest {

    private fun s3(engine: MockEngine = okEngine()): DefaultS3 = S3 {
        region = "us-west-2"
        credentialsProvider = AwsCredentialsProvider { AwsCredentials("AKID", "SECRET") }
        httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false }
        retryConfig = RetryConfig(maxAttempts = 3)
        clock = { 1_700_000_000_000 }
        random = { 1.0 }
        sleep = { }
    } as DefaultS3

    /** Echoes the request host back, so a caller can prove where its own call actually went. */
    private fun okEngine() = MockEngine { request ->
        respond(
            content = request.url.host,
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Length", listOf(request.url.host.length.toString())),
        )
    }

    /**
     * Many coroutines racing to create the client for the *same* bucket must all end up holding the
     * identical instance.
     *
     * The cache starts cold on every round, which is when the race is available at all: every racer
     * reads a miss, every racer builds, and only one may win. Under the unsynchronised version each
     * racer keeps the client it built itself, so callers walk away with different instances — and
     * since each `AwsServiceClient` carries its own retry token bucket, the circuit breaker would be
     * measuring a fraction of the traffic it is supposed to be protecting. A breaker that silently
     * under-counts reads as working, which is what makes this worth an assertion.
     */
    @Test
    fun parallelCallersRacingForOneBucketAllGetTheSameClient() = runTest {
        repeat(ROUNDS) { round ->
            val s3 = s3()
            val clients = withContext(Dispatchers.Default) {
                List(RACERS) { async { s3.clientFor("one-bucket") } }.awaitAll()
            }

            val first = clients.first()
            val distinct = clients.count { it !== first }
            assertEquals(
                0,
                distinct,
                "round $round: $distinct of ${clients.size} racers kept a client of their own " +
                    "instead of the cached one",
            )
            assertSame(first, s3.clientFor("one-bucket"), "round $round: the cache kept a third instance")
            assertEquals(setOf("one-bucket"), s3.cachedBuckets, "round $round")
        }
    }

    /**
     * The same pressure spread across distinct buckets, so every call is an insert.
     *
     * This is the shape that corrupts the map structurally rather than merely losing an update: a
     * read walking a bucket chain while a concurrent insert triggers a resize. Every bucket must be
     * present afterwards, and every caller's client must be the one the cache kept.
     */
    @Test
    fun parallelInsertsAcrossBucketsAllLandInTheCache() = runTest {
        repeat(ROUNDS) { round ->
            val s3 = s3()
            val buckets = List(RACERS) { "bucket-$it" }

            val built = withContext(Dispatchers.Default) {
                buckets.map { bucket -> async { bucket to s3.clientFor(bucket) } }.awaitAll()
            }

            assertEquals(
                buckets.toSet(),
                s3.cachedBuckets,
                "round $round: the cache lost entries under concurrent insertion",
            )
            for ((bucket, client) in built) {
                assertSame(client, s3.clientFor(bucket), "round $round: $bucket resolved to two clients")
            }
        }
    }

    /**
     * Hits and misses interleaved: repeated passes over a small bucket set, so reads of already
     * cached entries run concurrently with the inserts of new ones.
     */
    @Test
    fun interleavedCacheHitsAndMissesStayConsistent() = runTest {
        repeat(ROUNDS) { round ->
            val s3 = s3()
            val buckets = List(16) { "mixed-$it" }

            val resolved = withContext(Dispatchers.Default) {
                List(RACERS) {
                    async { buckets.map { bucket -> bucket to s3.clientFor(bucket) } }
                }.awaitAll()
            }.flatten()

            assertEquals(buckets.toSet(), s3.cachedBuckets, "round $round")
            // Every caller, whether it hit or missed, must have been handed the cached instance.
            val canonical = buckets.associateWith { s3.clientFor(it) }
            for ((bucket, client) in resolved) {
                assertSame(canonical[bucket], client, "round $round: $bucket resolved inconsistently")
            }
        }
    }

    /**
     * The end-to-end path, kept because the tests above bypass it.
     *
     * Each response echoes the host it was sent to, so this asserts the property that actually
     * matters in production: a call for one bucket is never routed to another bucket's endpoint,
     * which would mean a 403 from a bucket policy or — far worse — a successful read of the wrong
     * bucket.
     */
    @Test
    fun operationsAcrossBucketsAllReachTheirOwnEndpoint() = runTest {
        val buckets = List(64) { "bucket-$it" }

        s3().use { s3 ->
            val results = withContext(Dispatchers.Default) {
                buckets.map { bucket ->
                    async {
                        val response = s3.getObject(GetObjectRequest(bucket = bucket, key = "k"))
                        bucket to response.body.decodeToString()
                    }
                }.awaitAll()
            }

            assertEquals(buckets.size, results.size, "every call must complete")
            val misrouted = results.filter { (bucket, host) ->
                host != "$bucket.s3.us-west-2.amazonaws.com"
            }
            assertTrue(misrouted.isEmpty(), "calls routed to the wrong bucket's endpoint: $misrouted")
        }
    }

    private companion object {
        /** A race is probabilistic; one round can get lucky. */
        const val ROUNDS = 40
        const val RACERS = 32
    }
}
