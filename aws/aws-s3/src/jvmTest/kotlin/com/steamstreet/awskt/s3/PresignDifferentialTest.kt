package com.steamstreet.awskt.s3

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignDeleteObject
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.signing.AwsCredentials
import kotlinx.coroutines.runBlocking
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * Compares our presigned URLs against the real `aws.sdk.kotlin` S3 presigner — **including
 * `X-Amz-Signature`**.
 *
 * This is the cheapest oracle in the project and the reason M3.5a exists as its own milestone. S3
 * signing differs from every other service in two flags (`doubleUriEncode = false`,
 * `normalizeUriPath = false`), and getting either wrong produces a URL that is structurally perfect
 * and yields an opaque 403. Comparing the signature hex against an independent implementation turns
 * that into a diff on day two.
 *
 * ### Why the M3 interceptor trick cannot be reused
 *
 * Presigning never transmits: the SDK sets the body to `HttpBody.Empty` and *returns* the signed
 * `HttpRequest`, so `readBeforeTransmit` never fires. The replacement is simpler rather than harder
 * — the SDK hands back the signed request as an ordinary value.
 *
 * ### The clock
 *
 * The SDK presigner takes no injectable signing clock, so the only way to compare signatures is to
 * parse `X-Amz-Date` back out of **its** URL and sign ours at that exact instant. Without this the
 * comparison either flakes at second boundaries or has to exclude the signature — which is the only
 * field worth comparing.
 *
 * ### Order, and what "byte-identical" means here
 *
 * Origin and encoded path are compared as exact strings. Query parameters are compared as a sorted
 * multiset of exact `name=value` pairs, signature included. Emission *order* within the query is
 * not a wire-correctness property — the canonical request sorts, so two URLs differing only in
 * parameter order carry the same signature and behave identically — and pinning it would be
 * asserting on an SDK implementation detail.
 */
class PresignDifferentialTest {

    private val accessKey = "AKIDEXAMPLE"
    private val secret = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
    private val region = "us-west-2"
    private val bucket = "example-bucket"

    private fun parseAmzDate(value: String): Long =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .parse(value, java.time.Instant::from)
            .toEpochMilli()

    private fun sdkClient(sessionToken: String?) = S3Client {
        region = this@PresignDifferentialTest.region
        credentialsProvider = StaticCredentialsProvider {
            accessKeyId = accessKey
            secretAccessKey = secret
            this.sessionToken = sessionToken
        }
    }

    private fun ourPresigner(sessionToken: String?, signingInstantMillis: Long) = S3Presigner {
        region = this@PresignDifferentialTest.region
        credentialsProvider = AwsCredentialsProvider {
            AwsCredentials(accessKey, secret, sessionToken)
        }
        clock = { signingInstantMillis }
        // The 7-day cases carry a session token whose expiry is unknown, which is exactly the case
        // the guard refuses by default. Opting in here is the guard working, not a workaround.
        allowPresignBeyondUnknownSessionExpiry = true
        getEnv = { null }
    }

    /** Splits a URL into (origin + path) and its query pairs, both left percent-encoded. */
    private fun split(url: String): Pair<String, List<String>> {
        val q = url.indexOf('?')
        if (q < 0) return url to emptyList()
        return url.substring(0, q) to url.substring(q + 1).split("&").sorted()
    }

    private fun assertSameUrl(label: String, sdkUrl: String, ours: String) {
        val (sdkPrefix, sdkQuery) = split(sdkUrl)
        val (ourPrefix, ourQuery) = split(ours)
        assertEquals(sdkPrefix, ourPrefix, "$label: origin and encoded path\nSDK: $sdkUrl\nOURS: $ours")
        assertEquals(
            sdkQuery.joinToString("\n"),
            ourQuery.joinToString("\n"),
            "$label: query parameters\nSDK: $sdkUrl\nOURS: $ours",
        )
    }

    // -- GET ---------------------------------------------------------------------------------

    private fun checkGet(
        label: String,
        key: String,
        expiresIn: Duration,
        sessionToken: String? = null,
        responseContentType: String? = null,
        responseContentDisposition: String? = null,
    ) = runBlocking {
        val sdkUrl = sdkClient(sessionToken).use { client ->
            client.presignGetObject(
                GetObjectRequest {
                    this.bucket = this@PresignDifferentialTest.bucket
                    this.key = key
                    responseContentType?.let { this.responseContentType = it }
                    responseContentDisposition?.let { this.responseContentDisposition = it }
                },
                expiresIn,
            ).url.toString()
        }

        val signedAt = parseAmzDate(queryValue(sdkUrl, "X-Amz-Date"))
        val ours = ourPresigner(sessionToken, signedAt).presign(
            PresignRequest(
                bucket = bucket,
                key = key,
                method = PresignMethod.GET,
                expiresIn = expiresIn,
                responseContentType = responseContentType,
                responseContentDisposition = responseContentDisposition,
            ),
        )

        assertSameUrl(label, sdkUrl, ours.url)
    }

    @Test
    fun getVanilla() = checkGet("GET vanilla", "hello.txt", 900.seconds)

    @Test
    fun getKeyWithSpace() = checkGet("GET space", "a b/c.txt", 900.seconds)

    @Test
    fun getKeyWithPlus() = checkGet("GET plus", "a+b/c.txt", 900.seconds)

    /** `//` and `..` are the inputs `normalizeUriPath = false` exists for. */
    @Test
    fun getKeyWithDoubleSlashAndDots() = checkGet("GET //..", "a//b/../c.txt", 900.seconds)

    /** A literal `%` must be encoded once, not twice — `doubleUriEncode = false`. */
    @Test
    fun getKeyWithPercent() = checkGet("GET percent", "a%20b/c.txt", 900.seconds)

    @Test
    fun getKeyWithColon() = checkGet("GET colon", "a:b/c.txt", 900.seconds)

    @Test
    fun getKeyWithNonAscii() = checkGet("GET non-ascii", "日本語/ファイル.txt", 900.seconds)

    @Test
    fun getWithSessionToken() = checkGet("GET session token", "hello.txt", 900.seconds, sessionToken = "SESSIONTOKEN/+=")

    @Test
    fun getExpiryOneSecond() = checkGet("GET 1s", "hello.txt", 1.seconds)

    @Test
    fun getExpirySevenDays() =
        checkGet("GET 7d", "hello.txt", 7.days, sessionToken = "SESSIONTOKEN")

    /**
     * The thinnest-covered path in the milestone: a response-header override whose value contains a
     * space and non-ASCII text, which exercises the S3-mode *query* encoder rather than the path one.
     */
    @Test
    fun getWithResponseOverrides() = checkGet(
        "GET response overrides",
        "hello.txt",
        900.seconds,
        responseContentType = "text/plain; charset=utf-8",
        responseContentDisposition = "attachment; filename=\"日本語 report.txt\"",
    )

    // -- PUT ---------------------------------------------------------------------------------

    private fun checkPut(
        label: String,
        key: String,
        expiresIn: Duration,
        sessionToken: String? = null,
        contentType: String? = null,
    ) = runBlocking {
        val sdkUrl = sdkClient(sessionToken).use { client ->
            client.presignPutObject(
                PutObjectRequest {
                    this.bucket = this@PresignDifferentialTest.bucket
                    this.key = key
                    contentType?.let { this.contentType = it }
                },
                expiresIn,
            ).url.toString()
        }

        val signedAt = parseAmzDate(queryValue(sdkUrl, "X-Amz-Date"))
        val ours = ourPresigner(sessionToken, signedAt).presign(
            PresignRequest(
                bucket = bucket,
                key = key,
                method = PresignMethod.PUT,
                expiresIn = expiresIn,
                contentType = contentType,
            ),
        )

        assertSameUrl(label, sdkUrl, ours.url)
    }

    @Test
    fun putVanilla() = checkPut("PUT vanilla", "upload.bin", 900.seconds)

    @Test
    fun putAwkwardKey() = checkPut("PUT awkward key", "a b/c..d/e+f/日本語", 900.seconds)

    @Test
    fun putWithSessionToken() = checkPut("PUT session token", "upload.bin", 900.seconds, sessionToken = "SESSIONTOKEN")

    // -- DELETE ------------------------------------------------------------------------------

    @Test
    fun deleteVanilla() = runBlocking {
        val sdkUrl = sdkClient(null).use { client ->
            client.presignDeleteObject(
                DeleteObjectRequest {
                    this.bucket = this@PresignDifferentialTest.bucket
                    this.key = "gone.txt"
                },
                900.seconds,
            ).url.toString()
        }

        val signedAt = parseAmzDate(queryValue(sdkUrl, "X-Amz-Date"))
        val ours = ourPresigner(null, signedAt).presign(
            PresignRequest(bucket = bucket, key = "gone.txt", method = PresignMethod.DELETE, expiresIn = 900.seconds),
        )

        assertSameUrl("DELETE vanilla", sdkUrl, ours.url)
    }

    private fun queryValue(url: String, name: String): String =
        url.substringAfter('?').split("&").first { it.startsWith("$name=") }.substringAfter('=')
}
