package com.steamstreet.awskt.core

import com.steamstreet.awskt.signing.AwsCredentials
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Supplies credentials for signing. Resolution may do I/O, so it is suspending. */
public fun interface AwsCredentialsProvider {
    public suspend fun resolve(): AwsCredentials
}

/** No provider in the chain could supply credentials. */
public class AwsCredentialsNotFoundException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Fixed credentials. Chiefly for tests and for LocalStack. */
public class StaticCredentialsProvider(
    private val credentials: AwsCredentials,
) : AwsCredentialsProvider {
    override suspend fun resolve(): AwsCredentials = credentials

    /** Never render the wrapped secret. */
    override fun toString(): String = "StaticCredentialsProvider(${credentials.accessKeyId})"
}

/**
 * Reads the standard environment variables. This is the only provider a Lambda, an ECS task or a
 * CodeBuild job needs, which is why the chain's default is so short.
 *
 * `AWS_CREDENTIAL_EXPIRATION` is read when present, but **it is absent in Lambda** — see
 * [AwsCredentials.expiresAtEpochMillis]. Nothing may treat a null expiry as "never expires".
 */
public class EnvironmentCredentialsProvider(
    private val getEnv: (String) -> String? = ::platformGetEnv,
) : AwsCredentialsProvider {
    override suspend fun resolve(): AwsCredentials {
        val accessKeyId = getEnv("AWS_ACCESS_KEY_ID")
        val secretAccessKey = getEnv("AWS_SECRET_ACCESS_KEY")
        if (accessKeyId.isNullOrEmpty() || secretAccessKey.isNullOrEmpty()) {
            throw AwsCredentialsNotFoundException(
                "AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY must both be set in the environment",
            )
        }
        return AwsCredentials(
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            sessionToken = getEnv("AWS_SESSION_TOKEN")?.takeIf { it.isNotEmpty() },
            expiresAtEpochMillis =
                getEnv("AWS_CREDENTIAL_EXPIRATION")?.let(::parseAwsCredentialExpirationOrNull),
        )
    }

    override fun toString(): String = "EnvironmentCredentialsProvider"
}

/** Tries each provider in order; throws only when every one has failed. */
public class CredentialsProviderChain(
    private val providers: List<AwsCredentialsProvider>,
) : AwsCredentialsProvider {
    public constructor(vararg providers: AwsCredentialsProvider) : this(providers.toList())

    init {
        require(providers.isNotEmpty()) { "a credentials chain needs at least one provider" }
    }

    override suspend fun resolve(): AwsCredentials {
        var firstFailure: Throwable? = null
        val tried = StringBuilder()
        for (provider in providers) {
            try {
                return provider.resolve()
            } catch (cancellation: CancellationException) {
                // A cancelled scope is not "this provider had nothing to offer". Treating it as a
                // per-provider failure walks the rest of the chain — doing more I/O on the way —
                // and then reports AwsCredentialsNotFoundException, which blames the environment
                // for what was actually a caller that went away.
                throw cancellation
            } catch (e: Throwable) {
                if (firstFailure == null) firstFailure = e else firstFailure.addSuppressed(e)
                if (tried.isNotEmpty()) tried.append(", ")
                tried.append(provider.toString())
            }
        }
        throw AwsCredentialsNotFoundException("No provider supplied credentials. Tried: $tried", firstFailure)
    }

    override fun toString(): String = "CredentialsProviderChain(${providers.joinToString()})"
}

/**
 * Caches resolved credentials until shortly before they expire.
 *
 * The effective expiry is `min(credential expiry, now + expireAfter)`, so a credential that reports
 * no expiry — every Lambda credential — is still re-resolved periodically rather than pinned for
 * the life of the container.
 *
 * [refreshBuffer] exists because credentials are resolved at the top of *each* retry attempt: with
 * four attempts and a 20-second backoff cap, one call can span a minute, and a credential that was
 * valid when the call started may not be when the last attempt signs.
 *
 * ### The hit path takes no lock
 *
 * Credentials are resolved at the top of every retry attempt of every call, so on a fan-out of
 * `async {}` requests this is one of the hottest suspend functions in the library. The cached state
 * is therefore one immutable [CacheEntry] behind an atomic reference: a hit is a single load and a
 * comparison, with no `Mutex` acquisition and so no queueing of concurrent readers behind each
 * other. The mutex still exists and still guards *refresh* — a hundred coroutines noticing a stale
 * entry at once must produce one call to the delegate, not a hundred — and the holder re-checks
 * freshness after acquiring, because someone else may have refreshed while it waited.
 *
 * ### A failed refresh serves the stale credential rather than failing every caller
 *
 * When [delegate] throws and the previously cached credential is **not itself expired** — its
 * `expiresAtEpochMillis` is null or still in the future — that credential is returned instead of
 * the failure. This is a deliberate behaviour choice and worth stating plainly:
 *
 *  - The cache ceiling being past is not the same as the credential being dead. `expireAfter` is
 *    this library's re-resolution cadence, not AWS's opinion; a 15-minute-old Lambda credential is
 *    ordinarily still perfectly valid.
 *  - A refresh failure is usually transient — IMDS or STS being briefly unreachable — and
 *    propagating it fails *every* in-flight call at once, which converts a blip in the credential
 *    source into a total outage of the caller.
 *
 * The failure is propagated when nothing plausibly valid can be served: no entry has ever been
 * cached, or the cached credential has passed its own stated expiry. A served stale entry is not
 * re-stamped, so the next call tries the delegate again rather than pinning a stale credential for
 * another window.
 *
 * [CancellationException] is never answered with a stale credential and never swallowed — see
 * [resolveLocked].
 */
@OptIn(ExperimentalAtomicApi::class)
public class CachedCredentialsProvider(
    private val delegate: AwsCredentialsProvider,
    private val expireAfter: Duration = 15.minutes,
    private val refreshBuffer: Duration = 10.seconds,
    private val clock: () -> Long = ::currentEpochMillis,
) : AwsCredentialsProvider {

    /**
     * The credential and the moment it stops being served, as one immutable unit.
     *
     * Two fields published together. Held as two independent `var`s they could be read torn — the
     * new credential paired with the old expiry, or vice versa — which is a signing failure that
     * only appears under concurrency.
     */
    private class CacheEntry(
        val credentials: AwsCredentials,
        val effectiveExpiryMillis: Long,
    ) {
        fun isFreshAt(nowMillis: Long, refreshBufferMillis: Long): Boolean =
            nowMillis + refreshBufferMillis < effectiveExpiryMillis

        /**
         * Whether the *credential itself* is still plausibly usable, which is a strictly weaker
         * question than [isFreshAt]. A null expiry means "unknown", and unknown is not "expired" —
         * see [AwsCredentials.expiresAtEpochMillis].
         */
        fun credentialsNotDefinitelyExpiredAt(nowMillis: Long): Boolean =
            credentials.expiresAtEpochMillis?.let { it > nowMillis } ?: true
    }

    private val mutex = Mutex()
    private val cache = AtomicReference<CacheEntry?>(null)

    override suspend fun resolve(): AwsCredentials {
        // The hit path: one load, one comparison, no lock. This is the common case on every retry
        // attempt of every call, and it must not queue behind an unrelated coroutine's refresh.
        cache.load()?.let { entry ->
            if (entry.isFreshAt(clock(), refreshBuffer.inWholeMilliseconds)) return entry.credentials
        }
        return mutex.withLock { resolveLocked() }
    }

    /** Single-flight refresh. Called only with [mutex] held. */
    private suspend fun resolveLocked(): AwsCredentials {
        val now = clock()

        // Double-check. Every coroutine that queued behind the winner arrives here with the work
        // already done; without this they would each go on to call the delegate in turn, which is
        // the stampede the mutex exists to prevent.
        val previous = cache.load()
        if (previous != null && previous.isFreshAt(now, refreshBuffer.inWholeMilliseconds)) {
            return previous.credentials
        }

        val fresh = try {
            delegate.resolve()
        } catch (cancellation: CancellationException) {
            // A cancelled scope is not "the credential source is having a moment". Serving a stale
            // credential here would answer a caller that has gone away, and swallowing it would
            // report success for work that was cancelled — so it goes straight back out, ahead of
            // the stale-serving logic below.
            throw cancellation
        } catch (failure: Throwable) {
            val stale = previous?.takeIf { it.credentialsNotDefinitelyExpiredAt(now) }
                ?: throw failure
            return stale.credentials
        }

        val ceiling = now + expireAfter.inWholeMilliseconds
        val effectiveExpiry = fresh.expiresAtEpochMillis?.let { minOf(it, ceiling) } ?: ceiling
        cache.store(CacheEntry(fresh, effectiveExpiry))
        return fresh
    }

    override fun toString(): String = "CachedCredentialsProvider($delegate)"
}

/** The default chain. Deliberately short — see the plan's out-of-scope list for what is missing. */
public fun defaultCredentialsProvider(): AwsCredentialsProvider =
    CachedCredentialsProvider(CredentialsProviderChain(EnvironmentCredentialsProvider()))

/**
 * Parses the `AWS_CREDENTIAL_EXPIRATION` convention to epoch millis — the one parser for that
 * variable, shared by every service module rather than re-implemented per module.
 *
 * Accepts what the convention actually emits: `2026-08-14T12:34:56Z`, a numeric offset such as
 * `2026-08-14T12:34:56-07:00`, and fractional seconds (`...T12:34:56.123Z`).
 *
 * **The offset is honoured, not ignored.** Some `credential_process` implementations publish an
 * offset form, and reading its digits as if they were UTC yields a value wrong by the offset — up
 * to fourteen hours, silently. Too late and a dead credential is cached and signed with; too early
 * and every call re-resolves. Neither surfaces as a parse error, so it must be right here.
 *
 * Returns null on anything unexpected: a malformed expiry degrades to "unknown", which callers
 * already handle, and must never throw out of credential resolution.
 */
public fun parseAwsCredentialExpirationOrNull(value: String): Long? = runCatching {
    kotlin.time.Instant.parse(value).toEpochMilliseconds()
}.getOrNull()
