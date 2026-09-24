package com.steamstreet.awskt.jwt

import com.steamstreet.awskt.core.awsHttpClient
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * A public key that verifies signatures for one [algorithm]. Built from a JWK with [fromJwk].
 */
public class JwtVerificationKey private constructor(
    public val algorithm: JwtAlgorithm,
    public val keyId: String?,
    private val verifier: dev.whyoleg.cryptography.operations.SignatureVerifier,
) {
    internal suspend fun verifies(data: ByteArray, signature: ByteArray): Boolean =
        try {
            verifier.tryVerifySignature(data, signature)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A signature of the wrong length or shape is a bad signature, not an error in the key.
            false
        }

    override fun toString(): String = "JwtVerificationKey($algorithm, kid=$keyId)"

    public companion object {
        /**
         * Builds a key from one JWK.
         *
         * [algorithm] is checked against the JWK: `kty` must be `RSA` for [JwtAlgorithm.RS256] and
         * `EC` on `P-256` for [JwtAlgorithm.ES256], and an `alg` member, when present, must match.
         *
         * @throws JwtException when the JWK does not describe a key for [algorithm].
         */
        public suspend fun fromJwk(jwk: JsonObject, algorithm: JwtAlgorithm): JwtVerificationKey {
            if (!jwk.isUsableFor(algorithm)) {
                throw JwtException("The JWK is not a ${algorithm.headerName} signing key")
            }
            val bytes = jwk.toString().encodeToByteArray()
            val verifier = try {
                when (algorithm) {
                    JwtAlgorithm.RS256 -> cryptoProvider.get(RSA.PKCS1)
                        .publicKeyDecoder(SHA256)
                        .decodeFromByteArray(RSA.PublicKey.Format.JWK, bytes)
                        .signatureVerifier()

                    JwtAlgorithm.ES256 -> cryptoProvider.get(ECDSA)
                        .publicKeyDecoder(EC.Curve.P256)
                        .decodeFromByteArray(EC.PublicKey.Format.JWK, bytes)
                        .signatureVerifier(SHA256, ECDSA.SignatureFormat.RAW)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw JwtException("The JWK could not be read as a ${algorithm.headerName} key", e)
            }
            return JwtVerificationKey(algorithm, jwk.member("kid"), verifier)
        }
    }
}

/** Finds the key that should verify a token. */
public fun interface JwtKeySource {
    /**
     * The key for [keyId] and [algorithm], or null when there is none.
     *
     * [keyId] is the token's `kid`, which may be absent.
     */
    public suspend fun key(keyId: String?, algorithm: JwtAlgorithm): JwtVerificationKey?
}

/**
 * Keys from an issuer's JSON Web Key Set, such as `https://appleid.apple.com/auth/keys` or
 * `https://www.googleapis.com/oauth2/v3/certs`.
 *
 * ### Caching
 *
 * The set is fetched on first use and kept for [cacheFor]. Issuers rotate keys by publishing the
 * new one before signing with it, so a token whose `kid` is not in the cached set triggers a
 * refetch straight away rather than waiting for the cache to expire. Those refetches are limited to
 * one per [minRefetchInterval], so a stream of tokens with made-up key ids cannot turn this into a
 * request amplifier against the issuer.
 *
 * When a refetch fails and a previously fetched set exists, the previous set keeps being used and
 * the next lookup tries again. A failure with nothing cached propagates.
 *
 * Build one per issuer and share it: the cache lives in the instance.
 *
 * ### Which key
 *
 * Keys marked `"use": "enc"` are ignored. With a `kid` on the token, the key with that `kid` is
 * used. Without one, a key is used only when exactly one key in the set suits the algorithm.
 *
 * @param httpClient the client to fetch with. By default one is built with the same timeouts and
 *   native TLS setup as the awskt AWS clients, and closed with [close].
 */
public class JwksKeySource(
    public val url: String,
    httpClient: HttpClient? = null,
    private val cacheFor: Duration = 24.hours,
    private val minRefetchInterval: Duration = 1.minutes,
    private val clock: Clock = Clock.System,
) : JwtKeySource, AutoCloseable {

    private val ownsClient = httpClient == null
    private val lazyHttp = lazy { httpClient ?: awsHttpClient() }
    private val http: HttpClient by lazyHttp

    private class KeySet(val keys: List<JsonObject>, val fetchedAt: kotlin.time.Instant)

    private val mutex = Mutex()
    private var keySet: KeySet? = null
    private var lastFetchAttempt: kotlin.time.Instant? = null
    private val decoded = mutableMapOf<Pair<String?, JwtAlgorithm>, JwtVerificationKey>()

    override suspend fun key(keyId: String?, algorithm: JwtAlgorithm): JwtVerificationKey? = mutex.withLock {
        val now = clock.now()
        val current = keySet
        // An expired set is refreshed, but no more often than a missing key would be: while the
        // issuer is unreachable the stale set is served, and hammering the endpoint helps nobody.
        val set = when {
            current == null -> refresh(now)
            now - current.fetchedAt >= cacheFor && mayRefetch(now) -> refresh(now)
            else -> current
        }

        select(set, keyId, algorithm)?.let { return@withLock decode(it, keyId, algorithm) }

        // Not in the set: the issuer may have rotated since it was fetched.
        if (!mayRefetch(now)) return@withLock null
        val refreshed = refresh(now)
        select(refreshed, keyId, algorithm)?.let { decode(it, keyId, algorithm) }
    }

    private fun mayRefetch(now: kotlin.time.Instant): Boolean =
        lastFetchAttempt?.let { now - it >= minRefetchInterval } ?: true

    /** Fetches the set, falling back to the cached one when the fetch fails. Called under [mutex]. */
    private suspend fun refresh(now: kotlin.time.Instant): KeySet {
        lastFetchAttempt = now
        return try {
            fetch(now).also {
                keySet = it
                decoded.clear()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            keySet ?: throw JwtException("Could not fetch the key set from $url", e)
        }
    }

    private suspend fun fetch(now: kotlin.time.Instant): KeySet {
        val response = http.get(url)
        if (!response.status.isSuccess()) {
            throw JwtException("$url answered ${response.status.value}")
        }
        val keys = Json.parseToJsonElement(response.bodyAsText()).jsonObject["keys"] as? JsonArray
            ?: throw JwtException("$url did not return a key set")
        return KeySet(keys.mapNotNull { it as? JsonObject }, now)
    }

    private fun select(set: KeySet, keyId: String?, algorithm: JwtAlgorithm): JsonObject? {
        val candidates = set.keys.filter { it.isUsableFor(algorithm) }
        return if (keyId != null) {
            candidates.firstOrNull { it.member("kid") == keyId }
        } else {
            candidates.singleOrNull()
        }
    }

    private suspend fun decode(jwk: JsonObject, keyId: String?, algorithm: JwtAlgorithm): JwtVerificationKey =
        decoded.getOrPut(keyId to algorithm) { JwtVerificationKey.fromJwk(jwk, algorithm) }

    override fun close() {
        if (ownsClient && lazyHttp.isInitialized()) http.close()
    }

    override fun toString(): String = "JwksKeySource($url)"
}

private fun JsonObject.member(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

/** Whether this JWK can verify signatures made with [algorithm]. */
private fun JsonObject.isUsableFor(algorithm: JwtAlgorithm): Boolean {
    val use = member("use")
    if (use != null && use != "sig") return false
    val alg = member("alg")
    if (alg != null && alg != algorithm.headerName) return false
    return when (algorithm) {
        JwtAlgorithm.RS256 -> member("kty") == "RSA"
        JwtAlgorithm.ES256 -> member("kty") == "EC" && member("crv") == "P-256"
    }
}
