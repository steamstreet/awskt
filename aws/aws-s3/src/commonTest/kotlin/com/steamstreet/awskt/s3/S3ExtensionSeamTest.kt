package com.steamstreet.awskt.s3

import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsHttpResponse
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.signing.AwsCredentials
import com.steamstreet.awskt.signing.PayloadHash
import com.steamstreet.awskt.signing.SigV4
import com.steamstreet.awskt.signing.SigV4Config
import com.steamstreet.awskt.signing.SignedBodyHeader
import com.steamstreet.awskt.signing.SigningRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The extension seam: [S3.clientFor].
 *
 * S3 addresses the bucket in the authority, so there is no single transport an extension author
 * could reasonably be handed — the bucket is an *input* to which client you need, which is why this
 * interface exposes `clientFor(bucket)` and no bucket-less `client`. The property that used to sit
 * there answered with whichever bucket's client had been built first, so an extension written to
 * the documented pattern signed a host it was not sending to and earned `SignatureDoesNotMatch` —
 * intermittently, depending on what the process had done beforehand.
 *
 * These tests are written from *outside* the operations, in the shape a downstream author would
 * write, so the seam is proven rather than merely documented.
 */
class S3ExtensionSeamTest {

    /** Fixed so a signature computed here can be compared with the one the client produced. */
    private val signingMillis = 1_700_000_000_000L
    private val region = "us-west-2"
    private val credentials = AwsCredentials("AKID", "SECRET")

    private val requests = mutableListOf<HttpRequestData>()

    /** Echoes the host it was sent to, so a caller can prove where its own call actually went. */
    private fun s3(endpoint: String? = null, insecure: Boolean = false): S3 {
        val engine = MockEngine { request ->
            requests += request
            val host = request.url.host
            respond(
                content = host,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Length", listOf(host.length.toString())),
            )
        }
        return S3 {
            this.region = this@S3ExtensionSeamTest.region
            credentialsProvider = AwsCredentialsProvider { credentials }
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false }
            retryConfig = RetryConfig(maxAttempts = 3)
            endpointUrl = endpoint
            allowInsecureEndpoint = insecure
            clock = { signingMillis }
            random = { 1.0 }
            sleep = { }
        }
    }

    /**
     * Two buckets, two clients — and the same bucket, the same client.
     *
     * Distinctness is the whole reason the seam takes a bucket: each instance carries its own
     * endpoint, and sharing one across buckets is how a call reaches the wrong authority. Identity
     * on a repeat is what makes calling this per request cheap, and it is also what keeps one retry
     * token bucket per bucket rather than one per call.
     */
    @Test
    fun clientForReturnsOneCachedClientPerBucket() {
        s3().use { s3 ->
            val a = s3.clientFor("bucket-a")
            val b = s3.clientFor("bucket-b")

            assertNotSame(a, b, "two buckets must not share one transport")
            assertSame(a, s3.clientFor("bucket-a"), "bucket-a was rebuilt instead of cached")
            assertSame(b, s3.clientFor("bucket-b"), "bucket-b was rebuilt instead of cached")
        }
    }

    /**
     * And each of those clients is pointed at the endpoint [resolveS3Endpoint] resolves for *its*
     * bucket — asserted through a call, because that is the only thing production cares about.
     *
     * The order matters here: `bucket-a`'s client is built first, which under the old bucket-less
     * `client` property was precisely the one every extension got handed regardless of bucket.
     */
    @Test
    fun eachBucketsClientReachesThatBucketsResolvedEndpoint() = runTest {
        s3().use { s3 ->
            for (bucket in listOf("bucket-a", "bucket-b", "bucket-c")) {
                val response = s3.listObjectsV2(bucket, region, prefix = "p/")
                val expected = resolveS3Endpoint(bucket, region, getEnv = { null })

                assertEquals(
                    expected.authority,
                    requests.last().url.hostWithPortIfNotDefault(),
                    "an extension through clientFor(\"$bucket\") was sent somewhere else",
                )
                assertEquals(
                    expected.authority,
                    response.body.decodeToString(),
                    "the responding endpoint disagrees with the resolved one",
                )
            }
        }
    }

    /**
     * The signature, not merely the routing.
     *
     * `host` is always a signed header, so the authority the client signed is recoverable: re-sign
     * the identical request against the authority this bucket resolves to and the signatures must
     * be equal. The negative control below is the point — signing the same request against another
     * bucket's authority gives a different signature, which is exactly the `SignatureDoesNotMatch`
     * an extension used to get when it was handed a client belonging to whatever bucket had been
     * touched first.
     */
    @Test
    fun anExtensionThroughClientForSignsItsOwnBucketsHost() = runTest {
        s3().use { s3 ->
            // Warm the cache with a *different* bucket first: under the old seam this is the client
            // the next call would have been given.
            s3.listObjectsV2("bucket-a", region, prefix = "p/")
            s3.listObjectsV2("bucket-b", region, prefix = "p/")

            val sent = requests.last()
            val authority = resolveS3Endpoint("bucket-b", region, getEnv = { null }).authority
            assertEquals("bucket-b.s3.$region.amazonaws.com", authority)

            val actual = sent.headers["Authorization"]
            assertNotNull(actual, "the request was not signed at all")

            assertTrue(
                actual.endsWith("Signature=" + expectedSignature(sent, authority)),
                "the request was not signed for $authority: $actual",
            )
            assertTrue(
                !actual.endsWith("Signature=" + expectedSignature(sent, "bucket-a.s3.$region.amazonaws.com")),
                "signing against another bucket's host produced the same signature — this test " +
                    "cannot tell the two apart and proves nothing",
            )
        }
    }

    /**
     * A bracketed IPv6 endpoint override, which the authority parse used to shear in half.
     *
     * `authority.substringBefore(':')` answers `[` for `[::1]:4566`, so the client was aimed at the
     * host `[` on the scheme's default port: a connection failure against a LocalStack that is
     * listening, and — because the *signed* authority stayed correct — one that looks like a DNS
     * problem rather than a parse bug. The brackets are kept on the host deliberately; Ktor
     * re-emits `host` verbatim, so a bare `::1` builds `http://::1:4566`.
     */
    @Test
    fun bracketedIpv6EndpointOverrideIsAddressedAndSignedIntact() = runTest {
        s3(endpoint = "http://[::1]:4566", insecure = true).use { s3 ->
            val response = s3.listObjectsV2(
                "bucket-a",
                region,
                prefix = "p/",
                endpointOverride = "http://[::1]:4566",
            )

            val sent = requests.last()
            assertEquals("[::1]", sent.url.host, "the bracketed IPv6 host was taken apart")
            assertEquals(4566, sent.url.port, "the port after the closing bracket was lost")
            assertEquals("[::1]", response.body.decodeToString())

            // An override is always path-style, so the bucket is in the path rather than the host.
            assertEquals("/bucket-a", sent.url.encodedPath)

            val actual = sent.headers["Authorization"]
            assertNotNull(actual)
            assertTrue(
                actual.endsWith("Signature=" + expectedSignature(sent, "[::1]:4566", "/bucket-a")),
                "the IPv6 endpoint was signed as something other than [::1]:4566: $actual",
            )
        }
    }

    /**
     * Re-signs the request the client just sent, against [authority].
     *
     * The header set is reconstructed rather than read back off the request, because Ktor adds
     * headers of its own after signing (`Accept`, `Accept-Charset`) and feeding those in would make
     * every signature differ for a reason that has nothing to do with the host. What `aws-core`
     * signs for a bodyless S3 GET is these three plus the `host`, `x-amz-date` and
     * `x-amz-content-sha256` that the signer adds itself.
     */
    private fun expectedSignature(
        sent: HttpRequestData,
        authority: String,
        path: String = "/",
    ): String = SigV4.sign(
        request = SigningRequest(
            method = "GET",
            path = path,
            host = authority,
            queryParameters = listOf("list-type" to "2", "prefix" to "p/"),
            headers = listOf(
                "amz-sdk-invocation-id" to sent.headers["amz-sdk-invocation-id"].orEmpty(),
                "amz-sdk-request" to sent.headers["amz-sdk-request"].orEmpty(),
                "accept-encoding" to "identity",
            ),
            body = ByteArray(0),
        ),
        credentials = credentials,
        config = SigV4Config(
            region = region,
            service = "s3",
            payloadHash = PayloadHash.EmptyBody,
            signedBodyHeader = SignedBodyHeader.X_AMZ_CONTENT_SHA256,
            doubleUriEncode = false,
            normalizeUriPath = false,
        ),
        signingInstantMillis = signingMillis,
    ).signature
}

/** The authority as it is signed and sent: the port appears only when it is not the default. */
private fun io.ktor.http.Url.hostWithPortIfNotDefault(): String =
    if (port == protocol.defaultPort) host else "$host:$port"

/**
 * `ListObjectsV2` — an operation this library does not ship — written the way the interface KDoc
 * says to write one, from outside the client's own file and with no privileged access to anything.
 *
 * The last four arguments are S3's dialect rather than `callRaw`'s defaults, and the path has to
 * agree with the addressing decision the client was built from, which is why it comes from
 * `resolveS3Endpoint` too.
 */
private suspend fun S3.listObjectsV2(
    bucket: String,
    region: String,
    prefix: String,
    endpointOverride: String? = null,
): AwsHttpResponse = clientFor(bucket).callRaw(
    method = "GET",
    path = resolveS3Endpoint(
        bucket,
        region,
        endpointOverride = endpointOverride,
        allowInsecureEndpoint = endpointOverride != null,
        getEnv = { null },
    ).basePath.ifEmpty { "/" },
    query = listOf("list-type" to "2", "prefix" to prefix),
    operation = "ListObjectsV2",
    payloadHash = PayloadHash.EmptyBody,
    signedBodyHeader = SignedBodyHeader.X_AMZ_CONTENT_SHA256,
    doubleUriEncode = false,
    normalizeUriPath = false,
)
