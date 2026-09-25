package com.steamstreet.awskt.jwt

import kotlin.time.Clock

/**
 * A verifier for access tokens from the Cognito user pool [userPoolId] in [region], issued to any of
 * the app clients [clientIds].
 *
 * Cognito access tokens have no `aud` claim. They name the app client in `client_id`, and mark
 * themselves with `token_use` of `access`. This verifier checks both, so a Cognito ID token, which
 * is signed by the same keys, is refused.
 *
 * ```kotlin
 * val cognito = JwtVerifier.cognitoAccessToken("us-east-1", "us-east-1_AbCdEf123", setOf(appClientId))
 * val userId = cognito.verify(accessToken).claims.subject
 * ```
 *
 * The keys are fetched from the pool's JWKS endpoint and cached, so build the verifier once and
 * share it.
 *
 * To supply your own HTTP client or clock skew, build the verifier directly, starting from
 * [cognitoIssuer]:
 *
 * ```kotlin
 * val issuer = cognitoIssuer(region, userPoolId)
 * val verifier = JwtVerifier(
 *     keys = JwksKeySource("$issuer/.well-known/jwks.json", httpClient),
 *     issuers = setOf(issuer),
 *     audience = JwtAudience.Claim("client_id", clientIds),
 *     requiredClaims = mapOf("token_use" to "access"),
 *     clockSkew = 5.seconds,
 * )
 * ```
 */
public fun JwtVerifier.Companion.cognitoAccessToken(
    region: String,
    userPoolId: String,
    clientIds: Set<String>,
): JwtVerifier {
    val issuer = cognitoIssuer(region, userPoolId)
    return cognitoAccessToken(issuer, JwksKeySource("$issuer/.well-known/jwks.json"), clientIds)
}

/**
 * The issuer of tokens from the Cognito user pool [userPoolId] in [region]: the `iss` claim of both
 * its ID and access tokens. The pool's signing keys are at `$issuer/.well-known/jwks.json`.
 */
public fun cognitoIssuer(region: String, userPoolId: String): String =
    "https://cognito-idp.$region.amazonaws.com/$userPoolId"

internal fun cognitoAccessToken(
    issuer: String,
    keys: JwtKeySource,
    clientIds: Set<String>,
    clock: Clock = Clock.System,
): JwtVerifier = JwtVerifier(
    keys = keys,
    issuers = setOf(issuer),
    audience = JwtAudience.Claim("client_id", clientIds),
    algorithms = setOf(JwtAlgorithm.RS256),
    requiredClaims = mapOf("token_use" to "access"),
    clock = clock,
)
