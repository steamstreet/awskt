package com.steamstreet.awskt.s3

import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.signing.AwsCredentials
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Presign behaviour that does not need the AWS SDK, and therefore runs on **every** target
 * including `macosArm64` and `linuxX64`.
 *
 * The split matters: the differential harness is JVM-only because the SDK is, so without these the
 * native targets would compile the presigner and execute none of it — which is exactly the vacuous
 * pass 2.3.x shipped.
 */
class PresignTest {

    private val now = 1_700_000_000_000L

    private fun presigner(
        sessionToken: String? = null,
        credentialExpiry: Long? = null,
        allowBeyond: Boolean = false,
        env: (String) -> String? = { null },
    ) = S3Presigner {
        region = "us-west-2"
        credentialsProvider = AwsCredentialsProvider {
            AwsCredentials("AKID", "SECRET", sessionToken, credentialExpiry)
        }
        clock = { now }
        allowPresignBeyondUnknownSessionExpiry = allowBeyond
        getEnv = env
    }

    private fun queryOf(url: String): Map<String, String> =
        url.substringAfter('?').split("&").associate {
            it.substringBefore('=') to it.substringAfter('=')
        }

    // -- x-id (Decision 17) ------------------------------------------------------------------

    @Test
    fun getCarriesGetObjectXId() = runTest {
        val url = presigner().presignGetObject("b", "k", 15.minutes).url
        assertEquals("GetObject", queryOf(url)["x-id"])
    }

    @Test
    fun putCarriesPutObjectXId() = runTest {
        val url = presigner().presignPutObject("b", "k", 15.minutes).url
        assertEquals("PutObject", queryOf(url)["x-id"])
    }

    @Test
    fun deleteCarriesDeleteObjectXId() = runTest {
        val url = presigner().presign(
            PresignRequest("b", "k", PresignMethod.DELETE, 15.minutes),
        ).url
        assertEquals("DeleteObject", queryOf(url)["x-id"])
    }

    /** HEAD gets none — matching the AWS SDKs, and it is signed either way, so a stray one is a 403. */
    @Test
    fun headCarriesNoXId() = runTest {
        val url = presigner().presign(PresignRequest("b", "k", PresignMethod.HEAD, 15.minutes)).url
        assertTrue("x-id" !in queryOf(url), "HEAD must not carry x-id, got $url")
    }

    // -- Signed headers ----------------------------------------------------------------------

    @Test
    fun hostIsAlwaysSigned() = runTest {
        val presigned = presigner().presignGetObject("b", "k", 15.minutes)
        assertEquals(listOf("host"), presigned.signedHeaderNames)
        assertEquals("host", queryOf(presigned.url)["X-Amz-SignedHeaders"])
    }

    @Test
    fun contentTypeIsSignedWhenSuppliedToPut() = runTest {
        val presigned = presigner().presignPutObject("b", "k", 15.minutes, contentType = "text/plain")
        assertEquals(listOf("content-type", "host"), presigned.signedHeaderNames)
    }

    @Test
    fun declaredSignedHeadersAppearInTheSignature() = runTest {
        val presigned = presigner().presign(
            PresignRequest(
                "b", "k", PresignMethod.GET, 15.minutes,
                signedHeaders = listOf("range" to "bytes=0-9"),
            ),
        )
        assertEquals(listOf("host", "range"), presigned.signedHeaderNames)
    }

    // -- Expiry model ------------------------------------------------------------------------

    @Test
    fun longLivedCredentialsGiveAKnownExpiry() = runTest {
        val presigned = presigner().presignGetObject("b", "k", 1.hours)
        val expiry = assertIs<PresignExpiry.Known>(presigned.expiry)
        assertEquals(now + 3_600_000, expiry.epochMillis)
    }

    /** The credential dies first, so the URL does too — and the number reported is the true one. */
    @Test
    fun knownCredentialExpiryClampsTheReportedExpiry() = runTest {
        val credentialExpiry = now + 60_000
        val presigned = presigner(sessionToken = "T", credentialExpiry = credentialExpiry)
            .presignGetObject("b", "k", 1.hours)
        val expiry = assertIs<PresignExpiry.Known>(presigned.expiry)
        assertEquals(credentialExpiry, expiry.epochMillis)
    }

    /**
     * The Lambda case. There is no way to learn the session expiry from inside the process, so the
     * type refuses to pretend — a bare `Long` here would read as a promise the library cannot keep.
     */
    @Test
    fun unknownSessionExpiryIsNotReportedAsKnown() = runTest {
        val presigned = presigner(sessionToken = "T").presignGetObject("b", "k", 30.minutes)
        val expiry = assertIs<PresignExpiry.BoundedByUnknownSession>(presigned.expiry)
        assertEquals(now + 1_800_000, expiry.requestedEpochMillis)
    }

    @Test
    fun longExpiryWithUnknownSessionIsRefusedByDefault() = runTest {
        val e = assertFailsWith<PresignExpiryException> {
            presigner(sessionToken = "T").presignGetObject("b", "k", 6.hours)
        }
        assertContains(e.message!!, "allowPresignBeyondUnknownSessionExpiry")
    }

    @Test
    fun longExpiryWithUnknownSessionIsAllowedWhenOptedIn() = runTest {
        val presigned = presigner(sessionToken = "T", allowBeyond = true).presignGetObject("b", "k", 6.hours)
        assertIs<PresignExpiry.BoundedByUnknownSession>(presigned.expiry)
    }

    /** A bonus signal only: ECS and `credential_process` set it, Lambda does not. */
    @Test
    fun awsCredentialExpirationEnvironmentVariableIsUsedWhenPresent() = runTest {
        val presigned = presigner(
            sessionToken = "T",
            env = { if (it == "AWS_CREDENTIAL_EXPIRATION") "2023-11-14T22:20:00Z" else null },
        ).presignGetObject("b", "k", 1.hours)
        assertIs<PresignExpiry.Known>(presigned.expiry)
    }

    /** A malformed value must never fail a presign that would otherwise succeed. */
    @Test
    fun malformedCredentialExpirationIsIgnored() = runTest {
        val presigned = presigner(
            sessionToken = "T",
            env = { if (it == "AWS_CREDENTIAL_EXPIRATION") "not-a-timestamp" else null },
        ).presignGetObject("b", "k", 30.minutes)
        assertIs<PresignExpiry.BoundedByUnknownSession>(presigned.expiry)
    }

    @Test
    fun expiryBeyondSevenDaysIsRefused() = runTest {
        val e = assertFailsWith<IllegalArgumentException> {
            presigner(allowBeyond = true).presignGetObject("b", "k", 8.days)
        }
        assertContains(e.message!!, "7 days")
    }

    @Test
    fun sevenDaysExactlyIsAccepted() = runTest {
        presigner(allowBeyond = true).presignGetObject("b", "k", 7.days)
    }

    @Test
    fun zeroExpiryIsRefused() = runTest {
        assertFailsWith<IllegalArgumentException> { presigner().presignGetObject("b", "k", 0.seconds) }
    }

    // -- Redaction ---------------------------------------------------------------------------

    /**
     * Runs on native as well as the JVM **by design**. The redaction is deliberately not a regex:
     * a lookbehind would behave differently on Kotlin/Native, and a silent non-match is
     * indistinguishable from a successful redaction — so the security property would fail open on
     * the only target that ships.
     */
    @Test
    fun toStringRedactsSignatureAndSessionToken() = runTest {
        val presigned = presigner(sessionToken = "SUPERSECRETTOKEN").presignGetObject("b", "k", 15.minutes)
        val signature = queryOf(presigned.url)["X-Amz-Signature"]!!
        val rendered = presigned.toString()

        assertTrue(signature !in rendered, "signature leaked into toString(): $rendered")
        assertTrue("SUPERSECRETTOKEN" !in rendered, "session token leaked into toString(): $rendered")
        assertContains(rendered, "X-Amz-Signature=REDACTED")
        assertContains(rendered, "X-Amz-Security-Token=REDACTED")
    }

    @Test
    fun redactionLeavesEverythingElseIntact() {
        val redacted = redactPresignedUrl(
            "https://b.s3.us-west-2.amazonaws.com/k?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
                "&X-Amz-Signature=deadbeef&x-id=GetObject&X-Amz-Security-Token=abc",
        )
        assertEquals(
            "https://b.s3.us-west-2.amazonaws.com/k?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
                "&X-Amz-Signature=REDACTED&x-id=GetObject&X-Amz-Security-Token=REDACTED",
            redacted,
        )
    }

    @Test
    fun redactionHandlesAUrlWithNoQuery() {
        assertEquals("https://example.com/k", redactPresignedUrl("https://example.com/k"))
    }

    @Test
    fun theSecretNeverAppearsInAnyRendering() = runTest {
        val presigned = presigner(sessionToken = "SUPERSECRETTOKEN").presignGetObject("b", "k", 15.minutes)
        val request = PresignRequest("b", "k", PresignMethod.GET, 15.minutes)
        for (rendering in listOf(presigned.toString(), request.toString())) {
            assertTrue("SECRET" !in rendering, "secret key leaked: $rendering")
            assertTrue("SUPERSECRETTOKEN" !in rendering, "session token leaked: $rendering")
        }
    }

    // -- Key encoding ------------------------------------------------------------------------

    /**
     * The same encoded string builds the URL path and the canonical request. If they ever diverge
     * the result is a signature that is perfect for a path S3 never sees.
     */
    @Test
    fun keyIsEncodedOnceWithSlashesPreserved() = runTest {
        // A virtual-hosted-compatible bucket, so the path is the key and nothing else.
        val url = presigner().presignGetObject("my-bucket", "a b/c..d/e+f", 15.minutes).url
        val path = url.substringBefore('?').substringAfter(".amazonaws.com")
        assertEquals("/a%20b/c..d/e%2Bf", path)
    }

    @Test
    fun pathStyleBucketAppearsInThePath() = runTest {
        val presigner = S3Presigner {
            region = "us-west-2"
            credentialsProvider = AwsCredentialsProvider { AwsCredentials("AKID", "SECRET") }
            clock = { now }
            forcePathStyle = true
            getEnv = { null }
        }
        val url = presigner.presignGetObject("my-bucket", "k", 15.minutes).url
        assertTrue(url.startsWith("https://s3.us-west-2.amazonaws.com/my-bucket/k?"), url)
    }
}
