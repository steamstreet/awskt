package com.steamstreet.awskt.jwt

import com.steamstreet.awskt.jwt.JwtVerificationException.Reason
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies signed JWTs, such as the ID tokens Apple and Google issue.
 *
 * ```kotlin
 * val apple = JwtVerifier(
 *     keys = JwksKeySource("https://appleid.apple.com/auth/keys"),
 *     issuers = setOf("https://appleid.apple.com"),
 *     audiences = setOf("com.example.app", "com.example.web"),
 * )
 * val jwt = apple.verify(idToken)
 * val userId = jwt.claims.subject
 * ```
 *
 * [verify] accepts a token only when all of these hold:
 *
 * 1. It is a well-formed compact JWS.
 * 2. Its `alg` is one of [algorithms]. `none`, and anything else not listed, is refused before any
 *    key is looked up. The algorithm comes from this configuration, never from the token, which is
 *    what prevents algorithm-substitution attacks.
 * 3. It carries no `crit` header, since this verifier implements no extensions.
 * 4. [keys] has a key for its `kid` and algorithm, and the signature verifies with it.
 * 5. `exp` is present and has not passed by more than [clockSkew].
 * 6. `nbf`, when present, has been reached, allowing [clockSkew].
 * 7. `iss` is one of [issuers].
 * 8. `aud` names at least one of [audiences].
 *
 * Both [issuers] and [audiences] are required. A verifier that skips the audience check accepts
 * any token the issuer ever signed for any application, which is the most common JWT mistake.
 * Google, for example, has two spellings of its issuer, so list both:
 * `setOf("https://accounts.google.com", "accounts.google.com")`.
 */
public class JwtVerifier(
    private val keys: JwtKeySource,
    private val issuers: Set<String>,
    private val audiences: Set<String>,
    private val algorithms: Set<JwtAlgorithm> = setOf(JwtAlgorithm.RS256),
    private val clockSkew: Duration = 60.seconds,
    private val clock: Clock = Clock.System,
) {
    init {
        require(issuers.isNotEmpty()) { "at least one issuer is required" }
        require(audiences.isNotEmpty()) { "at least one audience is required" }
        require(algorithms.isNotEmpty()) { "at least one algorithm is required" }
        require(!clockSkew.isNegative()) { "clockSkew must not be negative" }
    }

    /**
     * Verifies [token] and returns it decoded.
     *
     * @throws JwtVerificationException when any check fails; [JwtVerificationException.reason]
     *   says which.
     * @throws JwtException when the key set could not be fetched at all.
     */
    public suspend fun verify(token: String): Jwt {
        val jwt = try {
            Jwt.decode(token)
        } catch (e: JwtFormatException) {
            throw JwtVerificationException(Reason.MALFORMED, e.message ?: "malformed token", e)
        }

        val algorithm = JwtAlgorithm.fromHeader(jwt.header.algorithm)
            ?.takeIf { it in algorithms }
            ?: throw JwtVerificationException(
                Reason.ALGORITHM_NOT_ALLOWED,
                "Algorithm ${jwt.header.algorithm} is not accepted; expected one of ${algorithms.map { it.headerName }}",
            )

        if (jwt.header.json.containsKey("crit")) {
            throw JwtVerificationException(Reason.UNSUPPORTED_HEADER, "The token has a crit header")
        }

        val key = keys.key(jwt.header.keyId, algorithm)
            ?: throw JwtVerificationException(
                Reason.KEY_NOT_FOUND,
                "No ${algorithm.headerName} key for kid ${jwt.header.keyId}",
            )
        if (!key.verifies(jwt.signingInput, jwt.signature)) {
            throw JwtVerificationException(Reason.INVALID_SIGNATURE, "The signature does not verify")
        }

        checkClaims(jwt.claims)
        return jwt
    }

    private fun checkClaims(claims: JwtClaims) {
        val now = clock.now()

        val expiresAt = claims.expiresAt
            ?: throw JwtVerificationException(Reason.EXPIRED, "The token has no exp claim")
        if (now - clockSkew >= expiresAt) {
            throw JwtVerificationException(Reason.EXPIRED, "The token expired at $expiresAt")
        }

        claims.notBefore?.let { notBefore ->
            if (now + clockSkew < notBefore) {
                throw JwtVerificationException(Reason.NOT_YET_VALID, "The token is not valid before $notBefore")
            }
        }

        val issuer = claims.issuer
        if (issuer == null || issuer !in issuers) {
            throw JwtVerificationException(Reason.ISSUER, "Issuer $issuer is not accepted")
        }

        if (claims.audience.none { it in audiences }) {
            throw JwtVerificationException(Reason.AUDIENCE, "Audience ${claims.audience} is not accepted")
        }
    }

    override fun toString(): String = "JwtVerifier(keys=$keys, issuers=$issuers, audiences=$audiences)"
}
