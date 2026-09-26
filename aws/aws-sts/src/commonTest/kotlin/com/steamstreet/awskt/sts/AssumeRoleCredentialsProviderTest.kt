package com.steamstreet.awskt.sts

import com.steamstreet.awskt.core.AwsCredentialsNotFoundException
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.StaticCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val ROLE = "arn:aws:iam::123456789012:role/demo"
private const val T0 = 1_700_000_000_000

/** An [Sts] that answers `AssumeRole` from a function and counts the calls. */
private class FakeSts(
    private val expiresIn: Duration? = 60.minutes,
    private val clock: () -> Long,
) : Sts {
    var calls = 0
    var closed = false
    val requests = mutableListOf<AssumeRoleRequest>()

    override val client: AwsServiceClient get() = error("not used")

    override suspend fun assumeRole(request: AssumeRoleRequest): AssumeRoleResponse {
        requests += request
        calls++
        return AssumeRoleResponse(
            credentials = AwsCredentials(
                accessKeyId = "ASIA$calls",
                secretAccessKey = "secret-$calls",
                sessionToken = "token-$calls",
                expiresAtEpochMillis = expiresIn?.let { clock() + it.inWholeMilliseconds },
            ),
            assumedRoleUser = null,
            packedPolicySize = null,
            sourceIdentity = null,
        )
    }

    override suspend fun getCallerIdentity(): CallerIdentity = error("not used")

    override fun close() {
        closed = true
    }
}

class AssumeRoleCredentialsProviderTest {

    private var now = T0

    private fun provider(
        sts: Sts,
        request: AssumeRoleRequest = AssumeRoleRequest(ROLE, "reports"),
        ownsSts: Boolean = false,
    ) = AssumeRoleCredentialsProvider(request, sts, ownsSts, refreshBuffer = 5.minutes, clock = { now })

    @Test
    fun assumesOnceAndServesFromCache() = runTest {
        val sts = FakeSts(clock = { now })
        val provider = provider(sts)

        assertEquals("ASIA1", provider.resolve().accessKeyId)
        now += 30.minutes.inWholeMilliseconds
        assertEquals("ASIA1", provider.resolve().accessKeyId)
        assertEquals(1, sts.calls)
        assertEquals(AssumeRoleRequest(ROLE, "reports"), sts.requests.single())
    }

    /**
     * Five minutes ahead of expiry, not `CachedCredentialsProvider`'s ten seconds: the refresh is a
     * network call that can itself spend most of a minute retrying.
     */
    @Test
    fun refreshesFiveMinutesBeforeExpiry() = runTest {
        val sts = FakeSts(clock = { now })
        val provider = provider(sts)

        provider.resolve()
        now = T0 + 54.minutes.inWholeMilliseconds
        assertEquals("ASIA1", provider.resolve().accessKeyId)
        now = T0 + 56.minutes.inWholeMilliseconds
        assertEquals("ASIA2", provider.resolve().accessKeyId)
        assertEquals(2, sts.calls)
    }

    /** A response with no readable expiry is still re-assumed once the requested session is up. */
    @Test
    fun anUnknownExpiryIsBoundedByTheRequestedDuration() = runTest {
        val sts = FakeSts(expiresIn = null, clock = { now })
        val provider = provider(sts, AssumeRoleRequest(ROLE, "reports", duration = 15.minutes))

        provider.resolve()
        now = T0 + 9.minutes.inWholeMilliseconds
        provider.resolve()
        assertEquals(1, sts.calls)
        now = T0 + 11.minutes.inWholeMilliseconds
        provider.resolve()
        assertEquals(2, sts.calls)
    }

    @Test
    fun concurrentMissesMakeOneCall() = runTest {
        val sts = FakeSts(clock = { now })
        val provider = provider(sts)

        (1..50).map { async { provider.resolve() } }.awaitAll()
        assertEquals(1, sts.calls)
    }

    @Test
    fun closesOnlyAnStsItOwns() {
        val borrowed = FakeSts(clock = { now })
        provider(borrowed).close()
        assertFalse(borrowed.closed)

        val owned = FakeSts(clock = { now })
        provider(owned, ownsSts = true).close()
        assertTrue(owned.closed)
    }

    @Test
    fun toStringNamesTheRoleNotACredential() = runTest {
        val provider = provider(FakeSts(clock = { now }))
        provider.resolve()
        assertEquals("AssumeRoleCredentialsProvider($ROLE)", provider.toString())
    }

    /**
     * The deadlock this guards against: the provider's STS client resolves its own credentials
     * through the provider — what happens when it is installed as `AwsCredentialsDefaults.provider`
     * and handed an `Sts()` built with the default chain. Without the guard the inner resolve waits
     * on the refresh lock the outer one holds, forever.
     */
    @Test
    fun aProviderThatIsItsOwnSourceFailsInsteadOfDeadlocking() = runTest(timeout = 10.seconds) {
        lateinit var provider: AssumeRoleCredentialsProvider
        val sts = realSts(AwsCredentialsProvider { provider.resolve() })
        provider = provider(sts)

        val e = assertFailsWith<AwsCredentialsNotFoundException> { provider.resolve() }
        assertContains(e.message!!, "is its own source credentials")
    }

    /** The same cycle through a second provider: A assumes from B, and B from A. */
    @Test
    fun aCycleThroughTwoProvidersIsCaughtToo() = runTest(timeout = 10.seconds) {
        lateinit var a: AssumeRoleCredentialsProvider
        val b = provider(realSts(AwsCredentialsProvider { a.resolve() }))
        a = provider(realSts(b))

        assertFailsWith<AwsCredentialsNotFoundException> { a.resolve() }
    }

    /** A two-hop chain that is *not* a cycle must still work: the guard is per provider. */
    @Test
    fun aTwoHopChainResolves() = runTest {
        val inner = provider(realSts(StaticCredentialsProvider(AwsCredentials("AKID", "SECRET"))))
        val outer = provider(realSts(inner))

        assertEquals("ASIAIOSFODNN7EXAMPLE", outer.resolve().accessKeyId)
    }

    /** A real [DefaultSts] over a mock engine, signing with [credentials]. */
    private fun realSts(credentials: AwsCredentialsProvider): Sts = DefaultSts(
        AwsServiceClient(
            httpClient = HttpClient(MockEngine { respond(ASSUME_ROLE_OK, HttpStatusCode.OK) }) {
                expectSuccess = false
            },
            credentialsProvider = credentials,
            endpoint = resolveEndpoint("sts", "us-west-2", "https://sts.us-west-2.amazonaws.com"),
            region = "us-west-2",
            protocol = STS_PROTOCOL,
            clock = { now },
            random = { 1.0 },
            sleep = {},
        ),
    )
}
