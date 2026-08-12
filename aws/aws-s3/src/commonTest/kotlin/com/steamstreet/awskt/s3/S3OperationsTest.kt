package com.steamstreet.awskt.s3

import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsRedirectException
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The four operations, driven by canned responses.
 *
 * Runs on **every** target, native included — these assert wire construction, error classification
 * and the memory/completeness guards, none of which need the AWS SDK.
 */
class S3OperationsTest {

    class Harness {
        val requests = mutableListOf<HttpRequestData>()
        val sleeps = mutableListOf<Long>()
    }

    private fun s3(
        harness: Harness,
        maxDownload: Long = 64L * 1024 * 1024,
        maxUpload: Long = 64L * 1024 * 1024,
        responder: (Int) -> Triple<String, HttpStatusCode, List<Pair<String, String>>>,
    ): S3 {
        var call = 0
        val engine = MockEngine { request ->
            harness.requests += request
            val (body, status, hdrs) = responder(call++)
            respond(
                content = body,
                status = status,
                headers = headersOf(*hdrs.map { it.first to listOf(it.second) }.toTypedArray()),
            )
        }
        return S3 {
            region = "us-west-2"
            credentialsProvider = AwsCredentialsProvider { AwsCredentials("AKID", "SECRET") }
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false }
            retryConfig = RetryConfig(maxAttempts = 3)
            maxBufferedDownloadBytes = maxDownload
            maxBufferedUploadBytes = maxUpload
            clock = { 1_700_000_000_000 }
            random = { 1.0 }
            sleep = { harness.sleeps += it }
        }
    }

    /** Throws mid-flight, simulating a socket death after the bytes went out. */
    private fun failing(harness: Harness, failures: Int, then: () -> Triple<String, HttpStatusCode, List<Pair<String, String>>>): S3 {
        var call = 0
        val engine = MockEngine { request ->
            harness.requests += request
            if (call++ < failures) throw IOException("connection reset")
            val (body, status, hdrs) = then()
            respond(body, status, headersOf(*hdrs.map { it.first to listOf(it.second) }.toTypedArray()))
        }
        return S3 {
            region = "us-west-2"
            credentialsProvider = AwsCredentialsProvider { AwsCredentials("AKID", "SECRET") }
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false }
            retryConfig = RetryConfig(maxAttempts = 3)
            clock = { 1_700_000_000_000 }
            random = { 1.0 }
            sleep = { harness.sleeps += it }
        }
    }

    private fun ok(body: String, vararg extra: Pair<String, String>) =
        Triple(body, HttpStatusCode.OK, listOf("Content-Length" to body.length.toString()) + extra.toList())

    private fun err(code: String, status: HttpStatusCode) = Triple(
        """<?xml version="1.0"?><Error><Code>$code</Code><Message>boom</Message></Error>""",
        status,
        listOf("x-amz-request-id" to "REQ123", "x-amz-id-2" to "EXT456"),
    )

    // -- Request construction -------------------------------------------------------------

    @Test
    fun getObjectBuildsTheExpectedRequestLine() = runTest {
        val h = Harness()
        s3(h) { ok("hello") }.getObject(GetObjectRequest("my-bucket", "a b/c..d"))

        val url = h.requests.single().url.toString()
        assertContains(url, "https://my-bucket.s3.us-west-2.amazonaws.com/a%20b/c..d")
        assertContains(url, "x-id=GetObject")
    }

    /** HEAD carries no `x-id` — it is signed, so a stray one is a 403 rather than a nuisance. */
    @Test
    fun headObjectCarriesNoXId() = runTest {
        val h = Harness()
        s3(h) { Triple("", HttpStatusCode.OK, listOf("Content-Length" to "5")) }
            .headObject(HeadObjectRequest("my-bucket", "k"))

        assertTrue("x-id" !in h.requests.single().url.toString())
    }

    @Test
    fun deleteObjectCarriesDeleteObjectXId() = runTest {
        val h = Harness()
        s3(h) { Triple("", HttpStatusCode.NoContent, emptyList()) }
            .deleteObject(DeleteObjectRequest("my-bucket", "k"))

        assertContains(h.requests.single().url.toString(), "x-id=DeleteObject")
    }

    @Test
    fun putObjectSignsTheRealPayloadHash() = runTest {
        val h = Harness()
        s3(h) { Triple("", HttpStatusCode.OK, listOf("ETag" to "\"abc\"")) }
            .putObject(PutObjectRequest("my-bucket", "k", "hello".encodeToByteArray()))

        val sha = h.requests.single().headers["x-amz-content-sha256"]
        // Never UNSIGNED-PAYLOAD outside presigning: forking the security class on payload size
        // would make a bucket policy conditioning on s3:x-amz-content-sha256 pass small objects
        // and 403 large ones.
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            sha,
            "PutObject must sign the real SHA-256 of the body",
        )
    }

    @Test
    fun getObjectHarvestsHeadersAndUserMetadata() = runTest {
        val h = Harness()
        val response = s3(h) {
            ok(
                "hello",
                "ETag" to "\"abc123\"",
                "Content-Type" to "text/plain",
                "Last-Modified" to "Wed, 21 Oct 2015 07:28:00 GMT",
                "x-amz-meta-Owner" to "jon",
            )
        }.getObject(GetObjectRequest("my-bucket", "k"))

        assertEquals("hello", response.body.decodeToString())
        assertEquals("abc123", response.eTag, "ETag quotes must be stripped")
        assertEquals("text/plain", response.contentType)
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", response.lastModified)
        assertEquals(mapOf("owner" to "jon"), response.metadata)
    }

    // -- Retry classification --------------------------------------------------------------

    @Test
    fun slowDownIsRetriedOnTheThrottlingBackoffBase() = runTest {
        val h = Harness()
        assertFailsWith<SlowDownException> {
            s3(h) { err("SlowDown", HttpStatusCode.ServiceUnavailable) }
                .getObject(GetObjectRequest("my-bucket", "k"))
        }
        assertEquals(3, h.requests.size, "SlowDown is retryable")
        // Throttling backs off on the 1000 ms base, not the 25 ms transient one. With random()
        // pinned to 1.0 the first delay is exactly the base.
        assertEquals(1_000L, h.sleeps.first(), "SlowDown must use the throttling base, not transient")
    }

    @Test
    fun noSuchKeyIsNotRetried() = runTest {
        val h = Harness()
        val e = assertFailsWith<NoSuchKeyException> {
            s3(h) { err("NoSuchKey", HttpStatusCode.NotFound) }
                .getObject(GetObjectRequest("my-bucket", "k"))
        }
        assertEquals(1, h.requests.size)
        assertEquals("REQ123", e.requestId)
        assertEquals("EXT456", e.extendedRequestId, "x-amz-id-2 must reach the exception")
    }

    @Test
    fun conditionalRequestConflictIsRetried() = runTest {
        val h = Harness()
        assertFailsWith<ConditionalRequestConflictException> {
            s3(h) { err("ConditionalRequestConflict", HttpStatusCode.Conflict) }
                .getObject(GetObjectRequest("my-bucket", "k"))
        }
        assertEquals(3, h.requests.size)
    }

    /** A 301 means the bucket is elsewhere; replaying goes to the same wrong endpoint. */
    @Test
    fun permanentRedirectIsSurfacedAndNotFollowed() = runTest {
        val h = Harness()
        assertFailsWith<Throwable> {
            s3(h) {
                Triple(
                    """<?xml version="1.0"?><Error><Code>PermanentRedirect</Code><Message>wrong region</Message></Error>""",
                    HttpStatusCode.MovedPermanently,
                    listOf("x-amz-request-id" to "REQ123"),
                )
            }.getObject(GetObjectRequest("my-bucket", "k"))
        }
        assertEquals(1, h.requests.size, "a 301 must not be retried")
    }

    @Test
    fun malformedErrorBodyDegradesRatherThanThrowingOutOfTheErrorPath() = runTest {
        val h = Harness()
        val e = assertFailsWith<S3Exception> {
            s3(h) { Triple("<<<not xml at all", HttpStatusCode.Forbidden, emptyList()) }
                .getObject(GetObjectRequest("my-bucket", "k"))
        }
        assertEquals(403, e.statusCode)
    }

    /** HEAD has no response body by protocol, so its errors classify from status alone. */
    @Test
    fun headObject404WithNoBodyStillProducesATypedException() = runTest {
        val h = Harness()
        assertFailsWith<NoSuchKeyException> {
            s3(h) { Triple("", HttpStatusCode.NotFound, emptyList()) }
                .headObject(HeadObjectRequest("my-bucket", "k"))
        }
    }

    // -- Idempotency ------------------------------------------------------------------------

    @Test
    fun ambiguousFailureOnAPlainPutIsRetried() = runTest {
        val h = Harness()
        failing(h, failures = 1) { Triple("", HttpStatusCode.OK, listOf("ETag" to "\"abc\"")) }
            .putObject(PutObjectRequest("my-bucket", "k", "x".encodeToByteArray()))

        assertEquals(2, h.requests.size, "an unconditional overwrite is safe to replay")
    }

    /**
     * With `ifNoneMatch` set the PUT is a conditional create, and a replay would see its own
     * successful write and fail 412 — reporting a conflict that never happened.
     */
    @Test
    fun ambiguousFailureOnAConditionalPutIsNotRetried() = runTest {
        val h = Harness()
        assertFailsWith<Throwable> {
            failing(h, failures = 1) { Triple("", HttpStatusCode.OK, emptyList()) }
                .putObject(
                    PutObjectRequest("my-bucket", "k", "x".encodeToByteArray(), ifNoneMatch = "*"),
                )
        }
        assertEquals(1, h.requests.size, "a conditional create must not be replayed")
    }

    // -- Completeness and the memory ceiling -------------------------------------------------

    /**
     * TLS gives per-record integrity, not stream completeness: a connection dying mid-body yields
     * a well-formed short array. Whether the engine notices is engine-dependent, and this library
     * runs on Curl for native — the engine whose body handling forced the Ktor bump.
     */
    @Test
    fun shortBodyWithAnHonestContentLengthIsDetected() {
        // Asserted against the check directly rather than through the transport: Ktor's MockEngine
        // validates Content-Length itself and raises before a client-level check could see the
        // body, so the truncation cannot be staged through the mock. See `checkDownloadComplete`.
        val e = assertFailsWith<S3IncompleteDownloadException> {
            checkDownloadComplete(declared = 100L, actual = 5L, requestId = "REQ", extendedRequestId = "EXT")
        }
        assertEquals(100L, e.expectedBytes)
        assertEquals(5L, e.actualBytes)
        assertEquals("REQ", e.requestId)
        assertEquals("EXT", e.extendedRequestId)
    }

    @Test
    fun matchingContentLengthPasses() {
        checkDownloadComplete(declared = 5L, actual = 5L, requestId = null, extendedRequestId = null)
    }

    @Test
    fun absentContentLengthCannotFailTheCheck() {
        checkDownloadComplete(declared = null, actual = 5L, requestId = null, extendedRequestId = null)
    }

    @Test
    fun missingContentLengthDoesNotFailACompleteDownload() = runTest {
        val h = Harness()
        val response = s3(h) { Triple("hello", HttpStatusCode.OK, emptyList()) }
            .getObject(GetObjectRequest("my-bucket", "k"))
        assertEquals("hello", response.body.decodeToString())
        assertEquals(5L, response.contentLength)
    }

    /**
     * The declared length is refused **before** the body is read. This is the check that actually
     * prevents the OOM; a check on the returned response would already have allocated the array.
     */
    @Test
    fun oversizedDeclaredLengthIsRefusedBeforeTheBodyIsRead() = runTest {
        val h = Harness()
        val e = assertFailsWith<S3PayloadTooLargeException> {
            s3(h, maxDownload = 10) {
                Triple("x".repeat(50), HttpStatusCode.OK, listOf("Content-Length" to "50"))
            }.getObject(GetObjectRequest("my-bucket", "k"))
        }
        assertContains(e.message!!, "range")
    }

    /** An absent or understated Content-Length must not be a way past the ceiling. */
    @Test
    fun oversizedActualBodyIsRefusedEvenWithoutContentLength() = runTest {
        val h = Harness()
        assertFailsWith<S3PayloadTooLargeException> {
            s3(h, maxDownload = 10) { Triple("x".repeat(50), HttpStatusCode.OK, emptyList()) }
                .getObject(GetObjectRequest("my-bucket", "k"))
        }
    }

    @Test
    fun oversizedUploadIsRefusedBeforeAnyRequest() = runTest {
        val h = Harness()
        assertFailsWith<S3PayloadTooLargeException> {
            s3(h, maxUpload = 10) { Triple("", HttpStatusCode.OK, emptyList()) }
                .putObject(PutObjectRequest("my-bucket", "k", ByteArray(50)))
        }
        assertEquals(0, h.requests.size, "the request must never be sent")
    }
}
