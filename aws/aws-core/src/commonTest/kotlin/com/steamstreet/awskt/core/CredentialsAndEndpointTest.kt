package com.steamstreet.awskt.core

import com.steamstreet.awskt.signing.AwsCredentials
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
private const val TOKEN = "FQoGZXIvYXdzEExampleSessionToken=="

class CredentialsTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    @Test
    fun environmentProviderReadsTheStandardVariables() = runTest {
        val credentials = EnvironmentCredentialsProvider(
            env(
                "AWS_ACCESS_KEY_ID" to "AKID",
                "AWS_SECRET_ACCESS_KEY" to SECRET,
                "AWS_SESSION_TOKEN" to TOKEN,
            ),
        ).resolve()

        assertEquals("AKID", credentials.accessKeyId)
        assertEquals(TOKEN, credentials.sessionToken)
        assertNull(credentials.expiresAtEpochMillis, "Lambda publishes no expiry; null must stay null")
    }

    @Test
    fun environmentProviderReadsAnExpiryWhenOneIsPublished() = runTest {
        val credentials = EnvironmentCredentialsProvider(
            env(
                "AWS_ACCESS_KEY_ID" to "AKID",
                "AWS_SECRET_ACCESS_KEY" to SECRET,
                "AWS_CREDENTIAL_EXPIRATION" to "2026-08-10T06:30:00Z",
            ),
        ).resolve()

        assertEquals(parseIso8601UtcOrNull("2026-08-10T06:30:00Z"), credentials.expiresAtEpochMillis)
    }

    @Test
    fun environmentProviderFailsClearlyWhenIncomplete() = runTest {
        assertFailsWith<AwsCredentialsNotFoundException> {
            EnvironmentCredentialsProvider(env("AWS_ACCESS_KEY_ID" to "AKID")).resolve()
        }
        assertFailsWith<AwsCredentialsNotFoundException> {
            EnvironmentCredentialsProvider(env()).resolve()
        }
        // An empty string is not a credential.
        assertFailsWith<AwsCredentialsNotFoundException> {
            EnvironmentCredentialsProvider(
                env("AWS_ACCESS_KEY_ID" to "", "AWS_SECRET_ACCESS_KEY" to SECRET),
            ).resolve()
        }
    }

    @Test
    fun chainFallsThroughAndReportsEveryProviderItTried() = runTest {
        val failing = AwsCredentialsProvider { throw AwsCredentialsNotFoundException("nope") }
        val working = StaticCredentialsProvider(AwsCredentials("AKID", SECRET))

        assertEquals("AKID", CredentialsProviderChain(failing, working).resolve().accessKeyId)

        val error = assertFailsWith<AwsCredentialsNotFoundException> {
            CredentialsProviderChain(failing, failing).resolve()
        }
        assertTrue(error.message!!.contains("Tried:"))
        assertFalse(SECRET in error.stackTraceToString())
    }

    /**
     * The chain catches `Throwable` per provider so that one broken provider cannot sink the rest.
     * A cancelled scope is not a broken provider: falling through would do the remaining providers'
     * I/O after the caller has gone away, and would then report `AwsCredentialsNotFoundException` —
     * blaming the environment for a caller that was cancelled. Remove the `CancellationException`
     * carve-out in `CredentialsProviderChain.resolve` and this test fails on both counts.
     */
    @Test
    fun cancellationStopsTheChainInsteadOfFallingThrough() = runTest {
        var laterProvidersConsulted = 0
        val cancelling = AwsCredentialsProvider { throw CancellationException("scope cancelled") }
        val working = AwsCredentialsProvider {
            laterProvidersConsulted++
            AwsCredentials("AKID", SECRET)
        }

        assertFailsWith<CancellationException> {
            CredentialsProviderChain(cancelling, working).resolve()
        }
        assertEquals(0, laterProvidersConsulted, "a cancelled scope must not walk the chain")
    }

    @Test
    fun cacheReusesCredentialsUntilTheRefreshBuffer() = runTest {
        var now = 0L
        var resolutions = 0
        val provider = CachedCredentialsProvider(
            delegate = { resolutions++; AwsCredentials("AKID$resolutions", SECRET) },
            expireAfter = 15.minutes,
            refreshBuffer = 10.seconds,
            clock = { now },
        )

        assertEquals("AKID1", provider.resolve().accessKeyId)
        now += 10.minutes.inWholeMilliseconds
        assertEquals("AKID1", provider.resolve().accessKeyId, "still inside the window")
        assertEquals(1, resolutions)

        // Cross into the refresh buffer: 15m window, so re-resolve just before 15m.
        now += 5.minutes.inWholeMilliseconds
        assertEquals("AKID2", provider.resolve().accessKeyId)
        assertEquals(2, resolutions)
    }

    /**
     * A credential that reports its own expiry must not be held for the full window, and one that
     * reports none must not be pinned forever — hence `min(expiry, now + expireAfter)`.
     */
    @Test
    fun effectiveExpiryIsTheEarlierOfTheTwo() = runTest {
        var now = 0L
        var resolutions = 0
        val provider = CachedCredentialsProvider(
            delegate = {
                resolutions++
                AwsCredentials("AKID$resolutions", SECRET, expiresAtEpochMillis = now + 60_000)
            },
            expireAfter = 15.minutes,
            refreshBuffer = 10.seconds,
            clock = { now },
        )

        assertEquals("AKID1", provider.resolve().accessKeyId)
        now += 30_000
        assertEquals("AKID1", provider.resolve().accessKeyId)
        // Past the credential's own 60s expiry, well inside the 15m ceiling.
        now += 25_000
        assertEquals("AKID2", provider.resolve().accessKeyId)
    }

    @Test
    fun providersNeverRenderCredentialMaterial() = runTest {
        val static = StaticCredentialsProvider(AwsCredentials("AKID", SECRET, TOKEN))
        assertFalse(SECRET in static.toString())
        assertFalse(TOKEN in static.toString())

        val cached = CachedCredentialsProvider(static)
        assertFalse(SECRET in cached.toString())

        val chain = CredentialsProviderChain(static, EnvironmentCredentialsProvider())
        assertFalse(SECRET in chain.toString())
    }

    @Test
    fun malformedExpiryDegradesToUnknownRatherThanThrowing() {
        assertNull(parseIso8601UtcOrNull("not a date"))
        assertNull(parseIso8601UtcOrNull(""))
        assertEquals(0L, parseIso8601UtcOrNull("1970-01-01T00:00:00Z"))
    }
}

class EndpointResolutionTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    @Test
    fun regionResolutionOrder() {
        assertEquals("explicit", resolveRegion("explicit", env("AWS_REGION" to "env"), { null }))
        assertEquals("env", resolveRegion(null, env("AWS_REGION" to "env"), { null }))
        assertEquals("default", resolveRegion(null, env("AWS_DEFAULT_REGION" to "default"), { null }))
        assertEquals("prop", resolveRegion(null, env()) { if (it == "aws.region") "prop" else null })
    }

    @Test
    fun missingRegionNamesEverySourceItTried() {
        val error = assertFailsWith<AwsConfigurationException> { resolveRegion(null, env(), { null }) }
        assertTrue(error.message!!.contains("AWS_REGION"))
        assertTrue(error.message!!.contains("AWS_DEFAULT_REGION"))
        assertTrue(error.message!!.contains("aws.region"))
    }

    /** Several existing tests point at LocalStack in code; an env var must never override that. */
    @Test
    fun explicitEndpointBeatsEveryEnvironmentVariable() {
        val endpoint = resolveEndpoint(
            "dynamodb",
            "us-west-2",
            explicit = "http://localhost:4566",
            getEnv = env("AWS_ENDPOINT_URL" to "http://wrong:1", "AWS_ENDPOINT_URL_DYNAMODB" to "http://wrong:2"),
        )
        assertEquals("localhost:4566", endpoint.authority)
    }

    @Test
    fun serviceSpecificEnvironmentVariableBeatsTheGeneralOne() {
        val endpoint = resolveEndpoint(
            "dynamodb",
            "us-west-2",
            getEnv = env(
                "AWS_ENDPOINT_URL" to "http://general:1",
                "AWS_ENDPOINT_URL_DYNAMODB" to "http://specific:2",
            ),
        )
        assertEquals("specific:2", endpoint.authority)
    }

    @Test
    fun defaultsToTheRegionalAmazonawsEndpoint() {
        val endpoint = resolveEndpoint("dynamodb", "us-west-2", getEnv = env())
        assertEquals("https://dynamodb.us-west-2.amazonaws.com", endpoint.url)
        assertEquals("dynamodb.us-west-2.amazonaws.com", endpoint.authority)
    }

    /**
     * The whole AWS fixture corpus uses default ports and every integration test here uses an
     * ephemeral one, so this is the case that passes CI and fails production if it regresses.
     */
    @Test
    fun authorityIncludesANonDefaultPortAndOmitsADefaultOne() {
        assertEquals("localhost:4566", parseEndpoint("http://localhost:4566").authority)
        assertEquals("example.com", parseEndpoint("https://example.com").authority)
        assertEquals("example.com", parseEndpoint("https://example.com:443").authority)
        assertEquals("example.com", parseEndpoint("http://example.com:80").authority)
        assertEquals("example.com:8443", parseEndpoint("https://example.com:8443").authority)
    }
}
