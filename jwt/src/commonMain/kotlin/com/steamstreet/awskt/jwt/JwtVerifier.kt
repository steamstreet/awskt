package com.steamstreet.awskt.jwt

import com.steamstreet.awskt.jwt.JwtVerificationException.Reason
import kotlinx.serialization.json.JsonPrimitive
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
 * 8. The token names an accepted recipient, as [audience] describes: by default `aud` names at
 *    least one accepted value, and for Cognito access tokens `client_id` is one of them.
 * 9. Every entry of [requiredClaims] is present as a string equal to the given value.
 *
 * Both [issuers] and [audience] are required. A verifier that skips the recipient check accepts
 * any token the issuer ever signed for any application, which is the most common JWT mistake.
 * Google, for example, has two spellings of its issuer, so list both:
 * `setOf("https://accounts.google.com", "accounts.google.com")`.
 *
 * For Cognito access tokens, which carry no `aud`, use [cognitoAccessToken].
 *
 * @param requiredClaims claims that must be present with exactly these string values. For
 *   example, `token_use` of `access` keeps a Cognito ID token from being accepted where an access
 *   token is expected.
 */
public class JwtVerifier(
    private val keys: JwtKeySource,
    private val issuers: Set<String>,
    private val audience: JwtAudience,
    private val algorithms: Set<JwtAlgorithm> = setOf(JwtAlgorithm.RS256),
    private val requiredClaims: Map<String, String> = emptyMap(),
    private val clockSkew: Duration = 60.seconds,
    private val clock: Clock = Clock.System,
) {
    /** A verifier with the standard `aud` check: shorthand for `JwtAudience.Aud(audiences)`. */
    public constructor(
        keys: JwtKeySource,
        issuers: Set<String>,
        audiences: Set<String>,
        algorithms: Set<JwtAlgorithm> = setOf(JwtAlgorithm.RS256),
        requiredClaims: Map<String, String> = emptyMap(),
        clockSkew: Duration = 60.seconds,
        clock: Clock = Clock.System,
    ) : this(keys, issuers, JwtAudience.Aud(audiences), algorithms, requiredClaims, clockSkew, clock)

    init {
        require(issuers.isNotEmpty()) { "at least one issuer is required" }
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

        when (audience) {
            is JwtAudience.Aud -> if (claims.audience.none { it in audience.values }) {
                throw JwtVerificationException(Reason.AUDIENCE, "Audience ${claims.audience} is not accepted")
            }

            is JwtAudience.Claim -> {
                val value = claims.stringOnly(audience.name)
                    ?: throw JwtVerificationException(
                        Reason.AUDIENCE,
                        "The token has no ${audience.name} claim that is a string",
                    )
                if (value !in audience.values) {
                    throw JwtVerificationException(Reason.AUDIENCE, "${audience.name} $value is not accepted")
                }
            }
        }

        for ((name, required) in requiredClaims) {
            if (claims.stringOnly(name) != required) {
                throw JwtVerificationException(
                    Reason.CLAIM,
                    "Claim $name is missing or does not have the required value",
                )
            }
        }
    }

    override fun toString(): String =
        "JwtVerifier(keys=$keys, issuers=$issuers, audience=$audience, requiredClaims=$requiredClaims)"

    /** Holds factories for common issuers, such as [cognitoAccessToken]. */
    public companion object
}

/**
 * Which claim names a token's intended recipient, and the values [JwtVerifier] accepts in it.
 *
 * There is deliberately no way to skip the check: a verifier without one accepts any token the
 * issuer ever signed for any application.
 */
public sealed class JwtAudience {
    /** Standard `aud` check: the token's `aud` (string or array) must name at least one of [values]. */
    public class Aud(public val values: Set<String>) : JwtAudience() {
        init {
            require(values.isNotEmpty()) { "at least one audience is required" }
        }

        override fun toString(): String = "Aud($values)"
    }

    /**
     * A single-valued claim that must equal one of [values]. For Cognito access tokens this is
     * `client_id`, which carries the app client the token was issued to.
     *
     * A token without the claim, or with a value that is not a string, is rejected.
     */
    public class Claim(public val name: String, public val values: Set<String>) : JwtAudience() {
        init {
            require(name.isNotEmpty()) { "the claim name must not be empty" }
            require(values.isNotEmpty()) { "at least one accepted $name value is required" }
        }

        override fun toString(): String = "Claim($name, $values)"
    }
}

/**
 * A claim's value when it is a JSON string. [JwtClaims.string] also returns numbers and booleans as
 * text, which an exact match must not accept: `"1"` and `1` are different claims.
 */
private fun JwtClaims.stringOnly(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
