package com.steamstreet.awskt.credentials.sdk

import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProviderException
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.time.Instant
import com.steamstreet.awskt.core.AwsCredentialsDefaults
import com.steamstreet.awskt.core.AwsCredentialsNotFoundException
import com.steamstreet.awskt.core.CachedCredentialsProvider
import com.steamstreet.awskt.core.defaultCredentialsProvider
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
private const val TOKEN = "FQoGZXIvYXdzEExampleSessionToken=="

/** smithy-kotlin's Instant has no millisecond factory. */
private fun instantAt(epochMillis: Long): Instant =
    Instant.fromEpochSeconds(epochMillis / 1_000, ((epochMillis % 1_000) * 1_000_000).toInt())

/**
 * A scripted SDK provider: each resolve takes the next answer, which is either credentials or a
 * failure to throw. Counts calls so caching can be asserted.
 */
private class FakeSdkProvider(vararg answers: Any) : CredentialsProvider {
    private val queue = ArrayDeque(answers.toList())
    var calls = 0
        private set

    override suspend fun resolve(attributes: Attributes): Credentials {
        calls++
        val next = if (queue.size > 1) queue.removeFirst() else queue.first()
        if (next is Throwable) throw next
        return next as Credentials
    }

    override fun toString(): String = "FakeSdkProvider"
}

class SdkCredentialsProviderTest {

    @AfterTest
    fun restore() {
        AwsCredentialsDefaults.provider = null
        listOf("aws.accessKeyId", "aws.secretAccessKey", "aws.sessionToken", "aws.profile",
            "aws.sharedCredentialsFile", "aws.configFile").forEach(System::clearProperty)
    }

    @Test
    fun mapsEveryFieldIncludingSubSecondExpiry() = runTest {
        val expiry = instantAt(1_790_000_000_123L)
        val bridge = SdkCredentialsProvider(
            FakeSdkProvider(Credentials("AKID", SECRET, TOKEN, expiration = expiry)),
        )

        val resolved = bridge.resolve()

        assertEquals("AKID", resolved.accessKeyId)
        assertEquals(SECRET, resolved.secretAccessKey)
        assertEquals(TOKEN, resolved.sessionToken)
        assertEquals(1_790_000_000_123L, resolved.expiresAtEpochMillis)
    }

    @Test
    fun anAbsentExpiryStaysUnknown() = runTest {
        val resolved = SdkCredentialsProvider(FakeSdkProvider(Credentials("AKID", SECRET))).resolve()

        assertNull(resolved.sessionToken)
        assertNull(resolved.expiresAtEpochMillis)
    }

    @Test
    fun theSdkFindingNothingIsReportedAsCredentialsNotFound() = runTest {
        val sdkFailure = CredentialsProviderException("No credentials could be loaded from the chain")
        val bridge = SdkCredentialsProvider(FakeSdkProvider(sdkFailure))

        val thrown = assertFailsWith<AwsCredentialsNotFoundException> { bridge.resolve() }

        assertSame(sdkFailure, thrown.cause)
    }

    @Test
    fun otherFailuresPropagateUnchanged() = runTest {
        val bridge = SdkCredentialsProvider(FakeSdkProvider(IllegalStateException("boom")))

        assertFailsWith<IllegalStateException> { bridge.resolve() }
    }

    @Test
    fun toStringDoesNotRenderTheSecret() {
        val rendered = SdkCredentialsProvider(FakeSdkProvider(Credentials("AKID", SECRET))).toString()

        assertFalse(SECRET in rendered, rendered)
    }

    /**
     * The expiry the bridge maps is what drives awskt's cache: a credential close to expiring must
     * be re-resolved from the SDK, and one comfortably valid must not be.
     */
    @Test
    fun theMappedExpiryDrivesRefreshThroughTheCache() = runTest {
        var now = 1_000_000_000_000L
        val first = Credentials("AKID-1", SECRET, TOKEN, expiration = instantAt(now + 60_000))
        val second = Credentials("AKID-2", SECRET, TOKEN, expiration = instantAt(now + 3_600_000))
        val sdk = FakeSdkProvider(first, second)
        val cached = CachedCredentialsProvider(
            SdkCredentialsProvider(sdk),
            expireAfter = 15.minutes,
            refreshBuffer = 10.seconds,
            clock = { now },
        )

        assertEquals("AKID-1", cached.resolve().accessKeyId)
        now += 30_000
        assertEquals("AKID-1", cached.resolve().accessKeyId, "still well inside its expiry")
        assertEquals(1, sdk.calls)

        now += 25_000 // 5 seconds before expiry, inside the 10-second refresh buffer
        assertEquals("AKID-2", cached.resolve().accessKeyId)
        assertEquals(2, sdk.calls)
    }

    @Test
    fun aFailedRefreshServesTheStillValidCredential() = runTest {
        var now = 1_000_000_000_000L
        val valid = Credentials("AKID-1", SECRET, expiration = instantAt(now + 3_600_000))
        val sdk = FakeSdkProvider(valid, CredentialsProviderException("IMDS unreachable"))
        val cached = CachedCredentialsProvider(SdkCredentialsProvider(sdk), expireAfter = 1.minutes, clock = { now })

        assertEquals("AKID-1", cached.resolve().accessKeyId)
        now += 2.minutes.inWholeMilliseconds // past the cache ceiling, not past the credential's expiry

        assertEquals("AKID-1", cached.resolve().accessKeyId)
        assertEquals(2, sdk.calls)
    }

    /** End to end through the real SDK chain, via the source it consults first. */
    @Test
    fun theDefaultChainResolvesFromSystemProperties() = runTest {
        System.setProperty("aws.accessKeyId", "AKID-SYSPROP")
        System.setProperty("aws.secretAccessKey", SECRET)
        System.setProperty("aws.sessionToken", TOKEN)

        val resolved = sdkDefaultChainCredentialsProvider().resolve()

        assertEquals("AKID-SYSPROP", resolved.accessKeyId)
        assertEquals(TOKEN, resolved.sessionToken)
    }

    /**
     * The case the module exists for: a named profile, which `aws-core`'s own chain cannot read.
     * Environment credentials outrank a profile in the SDK's chain, so on a machine that exports
     * them the profile is never reached and there is nothing to assert.
     */
    @Test
    fun theDefaultChainResolvesANamedProfile() = runTest {
        if (System.getenv("AWS_ACCESS_KEY_ID") != null) return@runTest

        val credentials = File.createTempFile("credentials", "").apply {
            deleteOnExit()
            writeText("[awskt-test]\naws_access_key_id = AKID-PROFILE\naws_secret_access_key = $SECRET\n")
        }
        val config = File.createTempFile("config", "").apply { deleteOnExit() }
        System.setProperty("aws.sharedCredentialsFile", credentials.absolutePath)
        System.setProperty("aws.configFile", config.absolutePath)

        val resolved = sdkDefaultChainCredentialsProvider(profileName = "awskt-test").resolve()

        assertEquals("AKID-PROFILE", resolved.accessKeyId)
    }

    @Test
    fun installedAsTheProcessDefaultItReachesEveryClient() = runTest {
        val builtEarly = defaultCredentialsProvider()

        AwsCredentialsDefaults.provider =
            SdkCredentialsProvider(FakeSdkProvider(Credentials("AKID-SDK", SECRET)))

        assertEquals("AKID-SDK", builtEarly.resolve().accessKeyId)
    }
}
