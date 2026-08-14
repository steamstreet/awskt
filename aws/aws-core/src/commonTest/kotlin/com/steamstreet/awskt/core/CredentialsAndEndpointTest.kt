package com.steamstreet.awskt.core

import com.steamstreet.awskt.signing.AwsCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
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

        assertEquals(
            parseAwsCredentialExpirationOrNull("2026-08-10T06:30:00Z"),
            credentials.expiresAtEpochMillis,
        )
    }

    /**
     * `credential_process` implementations are free to publish an offset form, and the digits of
     * `12:34:56-07:00` are not the digits of a UTC time. Reading them as UTC used to hand the
     * provider — and therefore [CachedCredentialsProvider]'s effective expiry — a value seven hours
     * early with no error anywhere. Asserted end to end, and against a literal instant, because the
     * failure mode is a wrong number rather than a thrown one.
     */
    @Test
    fun environmentProviderHonoursAnOffsetInTheExpiry() = runTest {
        val credentials = EnvironmentCredentialsProvider(
            env(
                "AWS_ACCESS_KEY_ID" to "AKID",
                "AWS_SECRET_ACCESS_KEY" to SECRET,
                "AWS_CREDENTIAL_EXPIRATION" to "2026-08-14T12:34:56-07:00",
            ),
        ).resolve()

        // 2026-08-14T19:34:56Z — the same instant, seven hours after the digits shown.
        assertEquals(1_786_736_096_000L, credentials.expiresAtEpochMillis)
    }

    /** A malformed expiry is "unknown", not a failed resolution. */
    @Test
    fun environmentProviderKeepsCredentialsWhenTheExpiryIsMalformed() = runTest {
        val credentials = EnvironmentCredentialsProvider(
            env(
                "AWS_ACCESS_KEY_ID" to "AKID",
                "AWS_SECRET_ACCESS_KEY" to SECRET,
                "AWS_CREDENTIAL_EXPIRATION" to "yesterday",
            ),
        ).resolve()

        assertEquals("AKID", credentials.accessKeyId)
        assertNull(credentials.expiresAtEpochMillis)
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

    // -- Concurrency: single-flight refresh, lock-free hits ----------------------------------

    /**
     * A stampede of concurrent resolves onto a stale cache must produce **one** call to the
     * delegate. That is the property the mutex exists for, and making the hit path lock-free must
     * not cost it: without single-flight, a Lambda fanning out sixteen requests answers a cold
     * cache with sixteen IMDS round trips, which is both slow and rate-limited.
     *
     * The assertion that matters is made while every racer is parked — one inside the delegate, the
     * rest on the mutex — because after the gate opens the count is indistinguishable from a
     * cache-hit-driven one.
     */
    @Test
    fun concurrentResolvesOnAStaleCacheCallTheDelegateExactlyOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val provider = CachedCredentialsProvider(
            delegate = {
                calls++
                gate.await()
                AwsCredentials("AKID$calls", SECRET)
            },
            clock = { 0L },
        )

        val racers = List(16) { async { provider.resolve() } }
        advanceUntilIdle()
        assertEquals(1, calls, "only the coroutine holding the lock may consult the delegate")

        gate.complete(Unit)
        val resolved = racers.awaitAll()
        assertEquals(1, calls, "the waiters must take the refreshed entry, not resolve again")
        assertTrue(resolved.all { it.accessKeyId == "AKID1" }, "every racer must get the one result")
    }

    /**
     * The hit path takes no lock.
     *
     * One coroutine is parked *inside* the delegate, so it holds the refresh mutex. A second
     * resolve, for which the cached entry is fresh, must complete anyway. Under the previous
     * implementation — `mutex.withLock` wrapped around the cache read as well as the refresh — that
     * second call suspends until the first finishes, and this test hangs rather than fails.
     *
     * The injected clock is moved *backwards* deliberately. The lock is only ever taken on a miss,
     * so "mutex held and cache fresh" cannot arise from time moving forwards alone; rewinding the
     * test clock is the only way to hold both conditions at the same instant.
     */
    @Test
    fun aCacheHitCompletesWhileAnotherCoroutineHoldsTheRefreshLock() = runTest {
        var now = 0L
        var calls = 0
        val insideDelegate = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val provider = CachedCredentialsProvider(
            delegate = {
                calls++
                if (calls > 1) {
                    insideDelegate.complete(Unit)
                    release.await()
                }
                AwsCredentials("AKID$calls", SECRET)
            },
            expireAfter = 15.minutes,
            refreshBuffer = 10.seconds,
            clock = { now },
        )

        assertEquals("AKID1", provider.resolve().accessKeyId, "warm the cache; entry is good to 15m")

        now = 20.minutes.inWholeMilliseconds
        val refreshing = async { provider.resolve() }
        insideDelegate.await()

        // Back inside the cached entry's window: the cache is fresh, and the mutex is held.
        now = 5.minutes.inWholeMilliseconds
        assertEquals("AKID1", provider.resolve().accessKeyId, "a fresh entry must be served unlocked")
        assertTrue(refreshing.isActive, "the refresh must still be the one holding the lock")

        release.complete(Unit)
        assertEquals("AKID2", refreshing.await().accessKeyId)
    }

    // -- Stale-on-error ----------------------------------------------------------------------

    /**
     * A refresh failure serves the previous credential rather than failing every caller.
     *
     * The cache ceiling being past is this library's re-resolution cadence expiring, not AWS's
     * verdict on the credential — so a briefly unreachable IMDS/STS must not convert into a total
     * outage of everything the process is doing. The refreshed value replaces the stale one as soon
     * as the delegate recovers, and the stale entry is *not* re-stamped in the meantime, so the
     * next call tries again instead of pinning it for another window.
     */
    @Test
    fun aFailedRefreshServesTheStaleCredentialAndTheNextSuccessReplacesIt() = runTest {
        var now = 0L
        var calls = 0
        var failing = false
        val provider = CachedCredentialsProvider(
            delegate = {
                calls++
                if (failing) throw IllegalStateException("IMDS unreachable")
                AwsCredentials("AKID$calls", SECRET)
            },
            expireAfter = 15.minutes,
            refreshBuffer = 10.seconds,
            clock = { now },
        )

        assertEquals("AKID1", provider.resolve().accessKeyId)

        now += 20.minutes.inWholeMilliseconds
        failing = true
        assertEquals("AKID1", provider.resolve().accessKeyId, "a transient failure must not fail the call")
        assertEquals("AKID1", provider.resolve().accessKeyId, "and must keep serving until it recovers")
        assertEquals(3, calls, "a served stale entry is not re-stamped; the delegate is retried")

        failing = false
        assertEquals("AKID4", provider.resolve().accessKeyId, "recovery replaces the stale credential")
        assertEquals("AKID4", provider.resolve().accessKeyId, "and the replacement is then cached")
        assertEquals(4, calls)
    }

    /** Nothing has ever been cached, so there is nothing plausible to serve: the failure is the answer. */
    @Test
    fun aFailedRefreshWithNothingCachedPropagates() = runTest {
        val provider = CachedCredentialsProvider(
            delegate = { throw IllegalStateException("IMDS unreachable") },
            clock = { 0L },
        )
        assertFailsWith<IllegalStateException> { provider.resolve() }
    }

    /**
     * A cached credential that has passed **its own** stated expiry is dead, not merely stale.
     * Serving it would sign with a credential AWS will reject, turning a clear failure into a
     * `403 InvalidClientTokenId` a caller has to reverse-engineer.
     */
    @Test
    fun aStaleEntryWhoseCredentialHasActuallyExpiredPropagatesInstead() = runTest {
        var now = 0L
        var failing = false
        val provider = CachedCredentialsProvider(
            delegate = {
                if (failing) throw IllegalStateException("STS unreachable")
                AwsCredentials("AKID", SECRET, expiresAtEpochMillis = 60_000)
            },
            expireAfter = 15.minutes,
            refreshBuffer = 10.seconds,
            clock = { now },
        )

        assertEquals("AKID", provider.resolve().accessKeyId)

        // Past the credential's own 60-second expiry, not merely past the cache ceiling.
        now = 120_000
        failing = true
        assertFailsWith<IllegalStateException> { provider.resolve() }
    }

    /**
     * Cancellation is not a credential-source outage. Answering a cancelled caller with a stale
     * credential — or worse, swallowing the cancellation — would let work continue after the scope
     * that asked for it has gone away, which is the same reason [CredentialsProviderChain] carves
     * `CancellationException` out of its own catch-all.
     */
    @Test
    fun cancellationPropagatesRatherThanBeingAnsweredWithAStaleCredential() = runTest {
        var now = 0L
        var cancelling = false
        val provider = CachedCredentialsProvider(
            delegate = {
                if (cancelling) throw CancellationException("scope cancelled")
                AwsCredentials("AKID", SECRET)
            },
            expireAfter = 15.minutes,
            refreshBuffer = 10.seconds,
            clock = { now },
        )

        assertEquals("AKID", provider.resolve().accessKeyId)
        now += 20.minutes.inWholeMilliseconds
        cancelling = true
        assertFailsWith<CancellationException> { provider.resolve() }
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
        assertNull(parseAwsCredentialExpirationOrNull("not a date"))
        assertNull(parseAwsCredentialExpirationOrNull(""))
        // Well-formed shape, impossible date: still null rather than a silently rolled-over instant.
        assertNull(parseAwsCredentialExpirationOrNull("2026-13-45T99:99:99Z"))
        assertEquals(0L, parseAwsCredentialExpirationOrNull("1970-01-01T00:00:00Z"))
    }

    /**
     * The three forms `AWS_CREDENTIAL_EXPIRATION` is published in. The offset case is the
     * regression: the previous parser read fixed substring positions and discarded everything after
     * the seconds, so this input came back as the UTC reading of its digits — wrong by the offset,
     * with nothing to indicate it.
     */
    @Test
    fun expiryAcceptsZOffsetAndFractionalForms() {
        assertEquals(1_786_710_896_000L, parseAwsCredentialExpirationOrNull("2026-08-14T12:34:56Z"))
        assertEquals(
            1_786_736_096_000L,
            parseAwsCredentialExpirationOrNull("2026-08-14T12:34:56-07:00"),
            "an offset must move the instant, not be ignored",
        )
        assertEquals(
            1_786_710_896_000L,
            parseAwsCredentialExpirationOrNull("2026-08-14T12:34:56+00:00"),
            "+00:00 is the same instant as Z",
        )
        assertEquals(
            1_786_710_896_123L,
            parseAwsCredentialExpirationOrNull("2026-08-14T12:34:56.123Z"),
            "fractional seconds are kept to millisecond precision",
        )
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
