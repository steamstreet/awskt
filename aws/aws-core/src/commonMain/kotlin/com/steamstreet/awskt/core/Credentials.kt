package com.steamstreet.awskt.core

import com.steamstreet.awskt.signing.AwsCredentials
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 */
public class CachedCredentialsProvider(
    private val delegate: AwsCredentialsProvider,
    private val expireAfter: Duration = 15.minutes,
    private val refreshBuffer: Duration = 10.seconds,
    private val clock: () -> Long = ::currentEpochMillis,
) : AwsCredentialsProvider {
    private val mutex = Mutex()
    private var cached: AwsCredentials? = null
    private var effectiveExpiryMillis: Long = Long.MIN_VALUE

    override suspend fun resolve(): AwsCredentials = mutex.withLock {
        val now = clock()
        cached?.let { current ->
            if (now + refreshBuffer.inWholeMilliseconds < effectiveExpiryMillis) return current
        }

        val fresh = delegate.resolve()
        val ceiling = now + expireAfter.inWholeMilliseconds
        cached = fresh
        effectiveExpiryMillis = fresh.expiresAtEpochMillis?.let { minOf(it, ceiling) } ?: ceiling
        fresh
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
