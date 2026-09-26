package com.steamstreet.awskt.sts

import com.steamstreet.awskt.core.AwsCredentialsDefaults
import com.steamstreet.awskt.core.AwsCredentialsNotFoundException
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.CachedCredentialsProvider
import com.steamstreet.awskt.core.CredentialsProviderChain
import com.steamstreet.awskt.core.EnvironmentCredentialsProvider
import com.steamstreet.awskt.signing.AwsCredentials
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Credentials for an assumed role, obtained from STS and refreshed before they expire.
 *
 * ```
 * // One client
 * val s3 = S3 { credentialsProvider = AssumeRoleCredentialsProvider(AssumeRoleRequest(roleArn, "reports")) }
 *
 * // Every client in the process
 * AwsCredentialsDefaults.provider = AssumeRoleCredentialsProvider(AssumeRoleRequest(roleArn, "reports"))
 * ```
 *
 * ### Which credentials do the assuming
 *
 * Built without an [Sts], the provider makes its own, and that client's source credentials default
 * to **the environment** — the same chain `aws-core` uses by default — and deliberately *not* to
 * [AwsCredentialsDefaults.provider]. Installing this provider there is the second example above, and
 * if its STS client consulted the same setting the provider would be its own source. Pass
 * `credentialsProvider` in the `configure` block to assume from something else, such as another
 * `AssumeRoleCredentialsProvider` for a two-hop chain.
 *
 * Handed an [Sts] instead, the provider uses it as it is, and does not close it.
 *
 * ### A provider that is its own source fails rather than hangs
 *
 * An [Sts] built with `Sts()` defaults to [AwsCredentialsDefaults.provider]. Hand one to this
 * provider, install the provider there, and resolving would re-enter the provider's own refresh,
 * which waits on a lock the outer refresh holds — a deadlock, not an error. The provider marks the
 * coroutine context while it calls STS and throws [AwsCredentialsNotFoundException] on re-entry,
 * naming the cycle.
 *
 * ### Caching
 *
 * [CachedCredentialsProvider] does the caching, so resolution is lock-free on a hit, single-flight
 * on a refresh, and serves the previous credential if STS is briefly unreachable while that
 * credential is still valid. Two of its defaults are wrong for STS and are set here instead:
 *
 * - the refresh buffer is [refreshBuffer], five minutes by default rather than ten seconds — the
 *   margin the AWS SDKs use, because an STS call is a network round trip that can itself be retried
 *   for the better part of a minute; and
 * - the cache ceiling is the requested session length rather than fifteen minutes, so a one-hour
 *   session is assumed once an hour, not four times.
 */
public class AssumeRoleCredentialsProvider internal constructor(
    private val request: AssumeRoleRequest,
    private val sts: Sts,
    private val ownsSts: Boolean,
    refreshBuffer: Duration,
    clock: () -> Long,
) : AwsCredentialsProvider, AutoCloseable {

    /**
     * Assumes [request]'s role through [sts], which the caller owns: [close] leaves it open.
     *
     * @param refreshBuffer how long before expiry to fetch the next credentials.
     */
    public constructor(
        request: AssumeRoleRequest,
        sts: Sts,
        refreshBuffer: Duration = DEFAULT_REFRESH_BUFFER,
    ) : this(request, sts, ownsSts = false, refreshBuffer, ::epochMillis)

    /**
     * Assumes [request]'s role through an STS client built from [configure], which this provider
     * owns and [close] closes.
     *
     * An unset `credentialsProvider` means the environment, not [AwsCredentialsDefaults.provider] —
     * see the class KDoc.
     *
     * @param refreshBuffer how long before expiry to fetch the next credentials.
     */
    public constructor(
        request: AssumeRoleRequest,
        refreshBuffer: Duration = DEFAULT_REFRESH_BUFFER,
        configure: StsConfig.() -> Unit = {},
    ) : this(
        request,
        buildSts(StsConfig().apply(configure), fallbackCredentials = ::environmentCredentials),
        ownsSts = true,
        refreshBuffer,
        ::epochMillis,
    )

    private val cache = CachedCredentialsProvider(
        delegate = { assume() },
        expireAfter = request.duration ?: DEFAULT_SESSION_DURATION,
        refreshBuffer = refreshBuffer,
        clock = clock,
    )

    override suspend fun resolve(): AwsCredentials {
        val resolving = currentCoroutineContext()[Resolving]
        if (resolving != null && this in resolving.providers) {
            throw AwsCredentialsNotFoundException(
                "$this is its own source credentials: its STS client resolves credentials through " +
                    "this provider, typically because the provider is installed as " +
                    "AwsCredentialsDefaults.provider and its Sts was built with Sts(). Build the " +
                    "provider without an Sts, or give that Sts an explicit credentialsProvider.",
            )
        }
        return cache.resolve()
    }

    private suspend fun assume(): AwsCredentials {
        val outer = currentCoroutineContext()[Resolving]?.providers.orEmpty()
        return withContext(Resolving(outer + this)) { sts.assumeRole(request).credentials }
    }

    override fun close() {
        if (ownsSts) sts.close()
    }

    /** Names the role, never a credential. */
    override fun toString(): String = "AssumeRoleCredentialsProvider(${request.roleArn})"

    /**
     * The providers currently calling STS on this coroutine, outermost first. A set rather than one
     * provider so a cycle through a second provider — A assumes from B, B from A — is caught too.
     */
    private class Resolving(val providers: Set<AssumeRoleCredentialsProvider>) :
        AbstractCoroutineContextElement(Resolving) {
        companion object Key : CoroutineContext.Key<Resolving>
    }

    private companion object {
        /** The margin the AWS SDKs refresh STS credentials by. See the class KDoc. */
        val DEFAULT_REFRESH_BUFFER: Duration = 5.minutes

        /** STS's own default when `DurationSeconds` is omitted. */
        val DEFAULT_SESSION_DURATION: Duration = 1.hours

        fun environmentCredentials(): AwsCredentialsProvider =
            CachedCredentialsProvider(CredentialsProviderChain(EnvironmentCredentialsProvider()))

        fun epochMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
    }
}
