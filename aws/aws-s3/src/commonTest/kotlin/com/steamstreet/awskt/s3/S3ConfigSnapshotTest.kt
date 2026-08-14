package com.steamstreet.awskt.s3

import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * The configuration builders are read **once**, at construction.
 *
 * `S3Config` and `S3PresignerConfig` are bags of `var`s handed to a caller-supplied lambda, and the
 * caller keeps the reference — `S3 { }` is an expression, not a scope that ends. A client that
 * re-read those fields per request would let a mutation on one thread change the size ceiling, the
 * addressing style or the credentials of a request already in flight on another, with no
 * synchronisation and, on the JVM, no visibility guarantee that the change would be seen at all.
 *
 * These tests reach back through the captured builder and change it, then assert the client behaves
 * as it was built. Each one fails loudly against a client that holds the config: the ceilings raise
 * `S3PayloadTooLargeException`, and the addressing change reroutes to a different host.
 */
class S3ConfigSnapshotTest {

    /** Echoes the request host, so a caller can prove where its own call actually went. */
    private fun echoEngine() = MockEngine { request ->
        respond(
            content = request.url.host,
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Length", listOf(request.url.host.length.toString())),
        )
    }

    private class Built(val s3: S3, val config: S3Config)

    private fun build(engine: MockEngine = echoEngine()): Built {
        var captured: S3Config? = null
        val s3 = S3 {
            captured = this
            region = "us-west-2"
            credentialsProvider = AwsCredentialsProvider { AwsCredentials("AKID", "SECRET") }
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false }
            retryConfig = RetryConfig(maxAttempts = 3)
            clock = { 1_700_000_000_000 }
            random = { 1.0 }
            sleep = { }
        }
        return Built(s3, captured!!)
    }

    /**
     * The download ceiling is decided at construction. Dropping it to a single byte afterwards must
     * not start refusing bodies that were within the limit the client was built with — that refusal
     * would arrive as a typed failure on a call that had already been admitted.
     */
    @Test
    fun loweringTheDownloadCeilingAfterConstructionDoesNotAffectTheClient() = runTest {
        val built = build()
        built.s3.use { s3 ->
            built.config.maxBufferedDownloadBytes = 1L

            val response = s3.getObject(GetObjectRequest(bucket = "snapshot-bucket", key = "k"))
            assertEquals("snapshot-bucket.s3.us-west-2.amazonaws.com", response.body.decodeToString())
        }
    }

    @Test
    fun loweringTheUploadCeilingAfterConstructionDoesNotAffectTheClient() = runTest {
        val built = build()
        built.s3.use { s3 ->
            built.config.maxBufferedUploadBytes = 1L

            // Would be refused outright by a client that re-read the ceiling.
            s3.putObject(
                PutObjectRequest(
                    bucket = "snapshot-bucket",
                    key = "k",
                    body = ByteArray(4096) { 7 },
                ),
            )
        }
    }

    /**
     * Addressing is the sharpest case, because it changes the signed `Host`. The assertion uses a
     * bucket the client has never seen, so the per-bucket client cache cannot be what preserves the
     * behaviour — `buildClientFor` really is reading the snapshot.
     */
    @Test
    fun flippingForcePathStyleAfterConstructionDoesNotRerouteNewBuckets() = runTest {
        val built = build()
        built.s3.use { s3 ->
            built.config.forcePathStyle = true
            built.config.endpointUrl = "https://somewhere-else.example.com"

            val response = s3.getObject(GetObjectRequest(bucket = "never-seen-bucket", key = "k"))
            assertEquals(
                "never-seen-bucket.s3.us-west-2.amazonaws.com",
                response.body.decodeToString(),
                "a post-construction config change must not move where a request is sent or signed",
            )
        }
    }

    /**
     * Swapping the credentials provider afterwards must not take effect either. Asserted on the
     * `Authorization` header, which is where the access key is actually observable — and on a bucket
     * the client has never seen, so the per-bucket client cache is not what is doing the work.
     */
    @Test
    fun swappingTheCredentialsProviderAfterConstructionDoesNotAffectTheClient() = runTest {
        val seen = mutableListOf<String?>()
        val engine = MockEngine { request ->
            seen += request.headers["Authorization"]
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Length", listOf("0")),
            )
        }
        val built = build(engine)
        built.s3.use { s3 ->
            built.config.credentialsProvider =
                AwsCredentialsProvider { AwsCredentials("REPLACED", "SECRET") }

            s3.headObject(HeadObjectRequest(bucket = "never-seen-bucket", key = "k"))

            val authorization = seen.single() ?: error("the request was not signed")
            assertTrue(
                "Credential=AKID/" in authorization,
                "the provider bound at construction must be the one that signs, got $authorization",
            )
        }
    }

    // -- S3Presigner -------------------------------------------------------------------------

    private class BuiltPresigner(val presigner: S3Presigner, val config: S3PresignerConfig)

    private fun buildPresigner(sessionToken: String? = null): BuiltPresigner {
        var captured: S3PresignerConfig? = null
        val presigner = S3Presigner {
            captured = this
            region = "us-west-2"
            credentialsProvider =
                AwsCredentialsProvider { AwsCredentials("AKID", "SECRET", sessionToken) }
            clock = { 1_700_000_000_000 }
            getEnv = { null }
        }
        return BuiltPresigner(presigner, captured!!)
    }

    @Test
    fun flippingPresignerForcePathStyleAfterConstructionDoesNotChangeTheUrl() = runTest {
        val built = buildPresigner()
        val before = built.presigner.presignGetObject("presign-bucket", "k", 15.minutes).url

        built.config.forcePathStyle = true
        built.config.endpointUrl = "https://somewhere-else.example.com"

        val after = built.presigner.presignGetObject("presign-bucket", "k", 15.minutes).url
        assertTrue(
            after.startsWith("https://presign-bucket.s3.us-west-2.amazonaws.com/"),
            "addressing is decided at construction, got $after",
        )
        assertEquals(before, after, "the same request must presign identically before and after")
    }

    /**
     * The unknown-session-expiry cap is a safety default, and the opt-out has to be an explicit
     * choice made when the presigner is built — not something that can be switched on later by a
     * mutation the presigner never agreed to.
     */
    @Test
    fun enablingTheUnknownSessionOptOutAfterConstructionDoesNotLoosenTheCap() = runTest {
        val built = buildPresigner(sessionToken = "FQoGZXIvYXdzEExampleSessionToken==")

        built.config.allowPresignBeyondUnknownSessionExpiry = true

        assertFailsWith<PresignExpiryException> {
            built.presigner.presignGetObject("presign-bucket", "k", 7.days)
        }
    }

    /**
     * The clock-skew hook is the deliberate exception: the *function reference* is snapshotted, so
     * the presigner keeps calling it and keeps seeing whatever skew it reports. Replacing the
     * property is a configuration change and has no effect; changing what the captured function
     * returns is live wiring and does.
     */
    @Test
    fun theClockSkewHookStaysLiveWhileTheFieldItselfIsSnapshotted() = runTest {
        var skew = 0L
        var captured: S3PresignerConfig? = null
        val presigner = S3Presigner {
            captured = this
            region = "us-west-2"
            credentialsProvider = AwsCredentialsProvider { AwsCredentials("AKID", "SECRET") }
            clock = { 1_700_000_000_000 }
            clockSkewOffsetMillis = { skew }
            getEnv = { null }
        }

        val baseline = presigner.presignGetObject("presign-bucket", "k", 15.minutes).url

        // Replacing the property does nothing: the reference captured at construction is the one
        // consulted.
        captured!!.clockSkewOffsetMillis = { 86_400_000L }
        assertEquals(
            baseline,
            presigner.presignGetObject("presign-bucket", "k", 15.minutes).url,
            "the hook that was captured is the hook that is called",
        )

        // Moving the value the captured function reports does change the signing time, which is the
        // whole reason the reference rather than a value is held.
        skew = 86_400_000L
        assertTrue(
            presigner.presignGetObject("presign-bucket", "k", 15.minutes).url != baseline,
            "the captured function must still be consulted on every presign",
        )
    }
}
