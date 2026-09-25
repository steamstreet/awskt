package com.steamstreet.awskt.jwt

import com.steamstreet.awskt.jwt.JwtVerificationException.Reason
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val NOW = Instant.fromEpochSeconds(1_800_000_000)

private class TestClock(var now: Instant = NOW) : Clock {
    override fun now(): Instant = now
}

private fun jwk(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
private fun jwks(vararg keys: String): String = """{"keys":[${keys.joinToString(",")}]}"""

/** A JWKS endpoint that counts fetches and can change its answer between them. */
private class FakeJwks(var body: String = jwks(Vectors.RSA_JWK, Vectors.EC_JWK)) {
    var fetches = 0
    var failing = false
    val client = HttpClient(MockEngine {
        fetches++
        if (failing) respondError(HttpStatusCode.ServiceUnavailable)
        else respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
    })
}

/** Signs arbitrary claims with the vector RSA key, so each claim rule can be tested in isolation. */
private suspend fun rs256(
    claims: JsonObject,
    kid: String? = "rsa-1",
    alg: String = "RS256",
    extraHeader: Map<String, String> = emptyMap(),
): String {
    val header = buildJsonObject {
        put("alg", alg)
        kid?.let { put("kid", it) }
        extraHeader.forEach { (k, v) -> put(k, v) }
    }
    val input = base64UrlEncode(header.toString().encodeToByteArray()) + "." +
        base64UrlEncode(claims.toString().encodeToByteArray())
    val signature = cryptoProvider.get(RSA.PKCS1).privateKeyDecoder(SHA256)
        .decodeFromByteArray(RSA.PrivateKey.Format.PEM, Vectors.RSA_PKCS8_PEM.encodeToByteArray())
        .signatureGenerator()
        .generateSignature(input.encodeToByteArray())
    return input + "." + base64UrlEncode(signature)
}

private fun claims(
    iss: String? = "https://issuer.example",
    aud: Any? = "client-1",
    exp: Instant? = NOW + 1.hours,
    nbf: Instant? = null,
) = buildJsonObject {
    iss?.let { put("iss", it) }
    when (aud) {
        is String -> put("aud", aud)
        is List<*> -> putJsonArray("aud") { aud.forEach { add(kotlinx.serialization.json.JsonPrimitive(it as String)) } }
    }
    exp?.let { put("exp", it.epochSeconds) }
    nbf?.let { put("nbf", it.epochSeconds) }
    put("sub", "user-1")
}

private fun verifier(
    jwks: FakeJwks = FakeJwks(),
    algorithms: Set<JwtAlgorithm> = setOf(JwtAlgorithm.RS256),
    clock: Clock = TestClock(),
    audiences: Set<String> = setOf("client-1"),
) = JwtVerifier(
    keys = JwksKeySource("https://issuer.example/keys", jwks.client, clock = clock),
    issuers = setOf("https://issuer.example"),
    audiences = audiences,
    algorithms = algorithms,
    clock = clock,
)

private suspend fun assertRejected(reason: Reason, block: suspend () -> Unit) {
    val e = assertFailsWith<JwtVerificationException> { block() }
    assertEquals(reason, e.reason, e.message)
}

class VectorTest {
    /** OpenSSL signed it; this verifies it. Runs on the JDK provider and on linked OpenSSL. */
    @Test
    fun verifiesAnRs256TokenSignedByOpenssl() = runTest {
        val jwt = verifier().verify(Vectors.RS256_TOKEN)

        assertEquals("user-1", jwt.claims.subject)
        assertEquals(true, jwt.claims.boolean("email_verified"))
    }

    @Test
    fun verifiesAnEs256TokenSignedByOpenssl() = runTest {
        val jwt = verifier(algorithms = setOf(JwtAlgorithm.ES256)).verify(Vectors.ES256_TOKEN)

        assertEquals("ec-1", jwt.header.keyId)
    }

    @Test
    fun oneFlippedBitInTheSignatureFails() = runTest {
        val (h, p, s) = Vectors.RS256_TOKEN.split('.')
        val bytes = base64UrlDecode(s, "signature").also { it[10] = (it[10].toInt() xor 1).toByte() }

        assertRejected(Reason.INVALID_SIGNATURE) { verifier().verify("$h.$p.${base64UrlEncode(bytes)}") }
    }

    @Test
    fun aChangedPayloadFails() = runTest {
        val (h, _, s) = Vectors.RS256_TOKEN.split('.')
        val forged = base64UrlEncode(claims().toString().encodeToByteArray())

        assertRejected(Reason.INVALID_SIGNATURE) { verifier().verify("$h.$forged.$s") }
    }
}

class VerifierRulesTest {
    @Test
    fun acceptsAValidToken() = runTest {
        assertEquals("user-1", verifier().verify(rs256(claims())).claims.subject)
    }

    @Test
    fun refusesAlgNoneWithoutLookingUpAKey() = runTest {
        val jwks = FakeJwks()
        val (_, p, _) = rs256(claims()).split('.')
        val header = base64UrlEncode("""{"alg":"none"}""".encodeToByteArray())

        assertRejected(Reason.ALGORITHM_NOT_ALLOWED) { verifier(jwks).verify("$header.$p.") }
        assertEquals(0, jwks.fetches)
    }

    @Test
    fun refusesHs256() = runTest {
        assertRejected(Reason.ALGORITHM_NOT_ALLOWED) { verifier().verify(rs256(claims(), alg = "HS256")) }
    }

    /** The algorithm is the verifier's choice, not the token's. */
    @Test
    fun refusesAnAlgorithmTheVerifierWasNotConfiguredFor() = runTest {
        assertRejected(Reason.ALGORITHM_NOT_ALLOWED) { verifier().verify(Vectors.ES256_TOKEN) }
    }

    @Test
    fun refusesACritHeader() = runTest {
        assertRejected(Reason.UNSUPPORTED_HEADER) {
            verifier().verify(rs256(claims(), extraHeader = mapOf("crit" to "b64")))
        }
    }

    @Test
    fun anUnknownKeyIdIsKeyNotFound() = runTest {
        assertRejected(Reason.KEY_NOT_FOUND) { verifier().verify(rs256(claims(), kid = "nope")) }
    }

    @Test
    fun malformedTokensAreMalformed() = runTest {
        for (token in listOf("", "a.b", "a.b.c.d", "!!.@@.##", "e30.bm90IGpzb24.AA")) {
            assertRejected(Reason.MALFORMED) { verifier().verify(token) }
        }
    }

    @Test
    fun expiryHonoursTheClockSkew() = runTest {
        val clock = TestClock()
        val token = rs256(claims(exp = NOW))

        clock.now = NOW + 59.seconds
        verifier(clock = clock).verify(token)

        clock.now = NOW + 60.seconds
        assertRejected(Reason.EXPIRED) { verifier(clock = clock).verify(token) }
    }

    @Test
    fun aTokenWithoutExpIsRefused() = runTest {
        assertRejected(Reason.EXPIRED) { verifier().verify(rs256(claims(exp = null))) }
    }

    @Test
    fun notBeforeHonoursTheClockSkew() = runTest {
        verifier().verify(rs256(claims(nbf = NOW + 60.seconds)))
        assertRejected(Reason.NOT_YET_VALID) { verifier().verify(rs256(claims(nbf = NOW + 61.seconds))) }
    }

    @Test
    fun theIssuerMustMatch() = runTest {
        assertRejected(Reason.ISSUER) { verifier().verify(rs256(claims(iss = "https://evil.example"))) }
        assertRejected(Reason.ISSUER) { verifier().verify(rs256(claims(iss = null))) }
    }

    @Test
    fun theAudienceMustMatch() = runTest {
        assertRejected(Reason.AUDIENCE) { verifier().verify(rs256(claims(aud = "someone-else"))) }
        assertRejected(Reason.AUDIENCE) { verifier().verify(rs256(claims(aud = null))) }
    }

    /** Apple's case: several client ids, any of which may be the token's audience. */
    @Test
    fun anyAcceptedAudienceInAnArrayIsEnough() = runTest {
        val v = verifier(audiences = setOf("ios-app", "web-app"))

        v.verify(rs256(claims(aud = "web-app")))
        v.verify(rs256(claims(aud = listOf("other", "ios-app"))))
    }

    @Test
    fun issuersAndAudiencesAreRequired() {
        val keys = JwtKeySource { _, _ -> null }
        assertFailsWith<IllegalArgumentException> { JwtVerifier(keys, emptySet(), setOf("a")) }
        assertFailsWith<IllegalArgumentException> { JwtVerifier(keys, setOf("i"), emptySet()) }
    }
}

class RecipientAndRequiredClaimsTest {
    private val keys = JwtKeySource { _, algorithm -> JwtVerificationKey.fromJwk(jwk(Vectors.RSA_JWK), algorithm) }

    private fun verifier(
        audience: JwtAudience = JwtAudience.Claim("client_id", setOf("client-1")),
        requiredClaims: Map<String, String> = emptyMap(),
    ) = JwtVerifier(
        keys = keys,
        issuers = setOf("https://issuer.example"),
        audience = audience,
        requiredClaims = requiredClaims,
        clock = TestClock(),
    )

    private fun claims(vararg extra: Pair<String, Any>) = buildJsonObject {
        put("iss", "https://issuer.example")
        put("exp", (NOW + 1.hours).epochSeconds)
        put("sub", "user-1")
        for ((name, value) in extra) when (value) {
            is String -> put(name, value)
            is Number -> put(name, value)
            is Boolean -> put(name, value)
        }
    }

    @Test
    fun aClaimAudienceAcceptsAnyListedValue() = runTest {
        val v = verifier(JwtAudience.Claim("client_id", setOf("client-1", "client-2")))

        v.verify(rs256(claims("client_id" to "client-1")))
        v.verify(rs256(claims("client_id" to "client-2")))
    }

    @Test
    fun aClaimAudienceRejectsAnotherValueAndNamesTheClaim() = runTest {
        val e = assertFailsWith<JwtVerificationException> {
            verifier().verify(rs256(claims("client_id" to "someone-else")))
        }
        assertEquals(Reason.AUDIENCE, e.reason)
        assertEquals("client_id someone-else is not accepted", e.message)
    }

    @Test
    fun aClaimAudienceRejectsAMissingOrNonStringClaim() = runTest {
        assertRejected(Reason.AUDIENCE) { verifier().verify(rs256(claims())) }
        assertRejected(Reason.AUDIENCE) { verifier().verify(rs256(claims("client_id" to 1))) }
        assertRejected(Reason.AUDIENCE) {
            verifier(JwtAudience.Claim("client_id", setOf("true"))).verify(rs256(claims("client_id" to true)))
        }
    }

    /** A token whose `aud` would match is still refused when the verifier checks another claim. */
    @Test
    fun aClaimAudienceIgnoresAud() = runTest {
        assertRejected(Reason.AUDIENCE) { verifier().verify(rs256(claims("aud" to "client-1"))) }
    }

    @Test
    fun requiredClaimsMustMatchExactlyAsStrings() = runTest {
        val v = verifier(requiredClaims = mapOf("token_use" to "access"))

        v.verify(rs256(claims("client_id" to "client-1", "token_use" to "access")))
        assertRejected(Reason.CLAIM) { v.verify(rs256(claims("client_id" to "client-1"))) }
        assertRejected(Reason.CLAIM) { v.verify(rs256(claims("client_id" to "client-1", "token_use" to "id"))) }

        val numeric = verifier(requiredClaims = mapOf("version" to "2"))
        assertRejected(Reason.CLAIM) { numeric.verify(rs256(claims("client_id" to "client-1", "version" to 2))) }
    }

    @Test
    fun aRequiredClaimFailureNamesTheClaimButNotItsValue() = runTest {
        val e = assertFailsWith<JwtVerificationException> {
            verifier(requiredClaims = mapOf("token_use" to "access"))
                .verify(rs256(claims("client_id" to "client-1", "token_use" to "secret-looking-value")))
        }
        assertEquals(Reason.CLAIM, e.reason)
        assertTrue("token_use" in e.message!!)
        assertFalse("secret-looking-value" in e.message!!)
        assertFalse("access" in e.message!!)
    }

    @Test
    fun theAudienceIsCheckedBeforeRequiredClaims() = runTest {
        assertRejected(Reason.AUDIENCE) {
            verifier(requiredClaims = mapOf("token_use" to "access")).verify(rs256(claims("token_use" to "id")))
        }
    }

    @Test
    fun requiredClaimsAlsoWorkWithTheAudCheck() = runTest {
        val v = verifier(JwtAudience.Aud(setOf("client-1")), mapOf("token_use" to "id"))

        v.verify(rs256(claims("aud" to "client-1", "token_use" to "id")))
        assertRejected(Reason.CLAIM) { v.verify(rs256(claims("aud" to "client-1", "token_use" to "access"))) }
    }

    @Test
    fun theAudiencesShorthandTakesRequiredClaims() = runTest {
        val v = JwtVerifier(
            keys = keys,
            issuers = setOf("https://issuer.example"),
            audiences = setOf("client-1"),
            requiredClaims = mapOf("token_use" to "id"),
            clock = TestClock(),
        )

        v.verify(rs256(claims("aud" to "client-1", "token_use" to "id")))
        assertRejected(Reason.CLAIM) { v.verify(rs256(claims("aud" to "client-1"))) }
    }

    @Test
    fun audienceValuesAreRequired() {
        assertFailsWith<IllegalArgumentException> { JwtAudience.Aud(emptySet()) }
        assertFailsWith<IllegalArgumentException> { JwtAudience.Claim("client_id", emptySet()) }
        assertFailsWith<IllegalArgumentException> { JwtAudience.Claim("", setOf("a")) }
    }
}

class CognitoAccessTokenTest {
    private val issuer = cognitoIssuer("us-east-1", "us-east-1_AbCdEf123")
    private val verifier = cognitoAccessToken(
        issuer = issuer,
        keys = { _, algorithm -> JwtVerificationKey.fromJwk(jwk(Vectors.RSA_JWK), algorithm) },
        clientIds = setOf("app-client"),
        clock = TestClock(),
    )

    /** The shape of a real Cognito access token: no `aud`, the client in `client_id`. */
    private fun accessToken(
        tokenUse: String? = "access",
        clientId: String = "app-client",
        iss: String = issuer,
    ) = buildJsonObject {
        put("sub", "user-1")
        put("iss", iss)
        put("client_id", clientId)
        put("origin_jti", "jti-1")
        put("event_id", "event-1")
        tokenUse?.let { put("token_use", it) }
        put("scope", "aws.cognito.signin.user.admin")
        put("auth_time", NOW.epochSeconds)
        put("exp", (NOW + 1.hours).epochSeconds)
        put("iat", NOW.epochSeconds)
        put("jti", "jti-2")
        put("username", "user-1")
    }

    @Test
    fun theIssuerIsThePoolUrl() {
        assertEquals("https://cognito-idp.us-east-1.amazonaws.com/us-east-1_AbCdEf123", issuer)
    }

    @Test
    fun theKeysComeFromThePoolsJwksEndpoint() {
        val v = JwtVerifier.cognitoAccessToken("us-east-1", "us-east-1_AbCdEf123", setOf("app-client"))

        assertTrue("JwksKeySource($issuer/.well-known/jwks.json)" in v.toString(), v.toString())
    }

    @Test
    fun acceptsAnAccessTokenForTheClient() = runTest {
        assertEquals("user-1", verifier.verify(rs256(accessToken())).claims.subject)
    }

    /**
     * The shape of a real Cognito ID token for the same client: the client is in `aud`, and there is
     * no `client_id`. It fails the recipient check, so it never reaches the `token_use` check.
     */
    @Test
    fun refusesAnIdTokenForTheSameClient() = runTest {
        val idToken = buildJsonObject {
            put("sub", "user-1")
            put("aud", "app-client")
            put("email_verified", true)
            put("event_id", "event-1")
            put("token_use", "id")
            put("auth_time", NOW.epochSeconds)
            put("iss", issuer)
            put("cognito:username", "user-1")
            put("exp", (NOW + 1.hours).epochSeconds)
            put("iat", NOW.epochSeconds)
            put("email", "person@example.com")
        }

        assertRejected(Reason.AUDIENCE) { verifier.verify(rs256(idToken)) }
    }

    /** A token that names the client but does not say it is an access token. */
    @Test
    fun requiresTokenUseAccess() = runTest {
        assertRejected(Reason.CLAIM) { verifier.verify(rs256(accessToken(tokenUse = null))) }
        assertRejected(Reason.CLAIM) { verifier.verify(rs256(accessToken(tokenUse = "id"))) }
    }

    @Test
    fun refusesAnotherClient() = runTest {
        assertRejected(Reason.AUDIENCE) { verifier.verify(rs256(accessToken(clientId = "other-client"))) }
    }

    @Test
    fun refusesAnotherPool() = runTest {
        val otherPool = cognitoIssuer("us-east-1", "us-east-1_Other")
        assertRejected(Reason.ISSUER) { verifier.verify(rs256(accessToken(iss = otherPool))) }
    }

    @Test
    fun refusesEs256() = runTest {
        assertRejected(Reason.ALGORITHM_NOT_ALLOWED) { verifier.verify(Vectors.ES256_TOKEN) }
    }
}

class JwksKeySourceTest {
    @Test
    fun fetchesOnceAndCaches() = runTest {
        val jwks = FakeJwks()
        val v = verifier(jwks)

        repeat(5) { v.verify(rs256(claims())) }

        assertEquals(1, jwks.fetches)
    }

    /** An issuer publishes a new key, then starts signing with it. */
    @Test
    fun anUnknownKidRefetchesAndFindsARotatedKey() = runTest {
        val clock = TestClock()
        val jwks = FakeJwks(body = jwks(Vectors.EC_JWK))
        val v = verifier(jwks, clock = clock)
        assertRejected(Reason.KEY_NOT_FOUND) { v.verify(rs256(claims())) }

        jwks.body = jwks(Vectors.RSA_JWK, Vectors.EC_JWK)
        clock.now += 61.seconds
        v.verify(rs256(claims()))

        assertEquals(2, jwks.fetches)
    }

    @Test
    fun unknownKidRefetchesAreRateLimited() = runTest {
        val clock = TestClock()
        val jwks = FakeJwks()
        val v = verifier(jwks, clock = clock)
        v.verify(rs256(claims()))

        repeat(10) { assertRejected(Reason.KEY_NOT_FOUND) { v.verify(rs256(claims(), kid = "made-up-$it")) } }
        assertEquals(1, jwks.fetches, "the refetch window had not opened")

        clock.now += 1.minutes
        assertRejected(Reason.KEY_NOT_FOUND) { v.verify(rs256(claims(), kid = "made-up")) }
        assertEquals(2, jwks.fetches)
    }

    @Test
    fun anExpiredSetIsRefetched() = runTest {
        val clock = TestClock()
        val jwks = FakeJwks()
        val v = verifier(jwks, clock = clock)
        v.verify(rs256(claims()))

        clock.now += 25.hours
        v.verify(rs256(claims(exp = clock.now + 1.hours)))

        assertEquals(2, jwks.fetches)
    }

    @Test
    fun aFailedRefreshKeepsServingTheCachedSet() = runTest {
        val clock = TestClock()
        val jwks = FakeJwks()
        val v = verifier(jwks, clock = clock)
        v.verify(rs256(claims()))

        jwks.failing = true
        clock.now += 25.hours
        repeat(3) { v.verify(rs256(claims(exp = clock.now + 1.hours))) }

        assertEquals(2, jwks.fetches, "one failed refresh, then the stale set until the window reopens")
    }

    @Test
    fun aFailureWithNothingCachedPropagates() = runTest {
        val jwks = FakeJwks().apply { failing = true }

        val e = assertFailsWith<JwtException> { verifier(jwks).verify(rs256(claims())) }
        assertFalse(e is JwtVerificationException)
    }

    @Test
    fun encryptionKeysAreIgnored() = runTest {
        val encKey = Vectors.RSA_JWK.replace("\"use\":\"sig\"", "\"use\":\"enc\"")
        assertRejected(Reason.KEY_NOT_FOUND) { verifier(FakeJwks(jwks(encKey))).verify(rs256(claims())) }
    }

    @Test
    fun withoutAKidOnlyAnUnambiguousKeyIsUsed() = runTest {
        verifier(FakeJwks(jwks(Vectors.RSA_JWK))).verify(rs256(claims(), kid = null))

        val second = Vectors.RSA_JWK.replace("rsa-1", "rsa-2")
        assertRejected(Reason.KEY_NOT_FOUND) {
            verifier(FakeJwks(jwks(Vectors.RSA_JWK, second))).verify(rs256(claims(), kid = null))
        }
    }

    @Test
    fun aJwkMustSuitTheAlgorithm() = runTest {
        assertFailsWith<JwtException> { JwtVerificationKey.fromJwk(jwk(Vectors.EC_JWK), JwtAlgorithm.RS256) }
        assertFailsWith<JwtException> { JwtVerificationKey.fromJwk(jwk(Vectors.RSA_JWK), JwtAlgorithm.ES256) }
    }
}

class Es256SignerTest {
    private fun ecVerifier(clock: Clock = TestClock()) = JwtVerifier(
        keys = { _, algorithm -> JwtVerificationKey.fromJwk(jwk(Vectors.EC_JWK), algorithm) },
        issuers = setOf("TEAMID"),
        audiences = setOf("https://appleid.apple.com"),
        algorithms = setOf(JwtAlgorithm.ES256),
        clock = clock,
    )

    /** The Apple client secret, round-tripped through the verifier. */
    @Test
    fun signsAnAppleClientSecret() = runTest {
        val signer = Es256Signer.fromPrivateKey(Vectors.EC_PKCS8_PEM, keyId = "ABC123")

        val token = signer.sign {
            issuer = "TEAMID"
            subject = "com.example.service"
            audience("https://appleid.apple.com")
            issuedAt = NOW
            expiresAt = NOW + 180.days
        }
        val jwt = ecVerifier().verify(token)

        assertEquals("ES256", jwt.header.algorithm)
        assertEquals("ABC123", jwt.header.keyId)
        assertEquals("com.example.service", jwt.claims.subject)
        assertEquals(NOW + 180.days, jwt.claims.expiresAt)
        // Apple expects a string, not a one-element array.
        assertEquals("\"https://appleid.apple.com\"", jwt.claims["aud"].toString())
    }

    @Test
    fun acceptsEveryKeyEncodingTheP8EndsUpIn() = runTest {
        for (key in listOf(Vectors.EC_PKCS8_PEM, Vectors.EC_SEC1_PEM, Vectors.EC_PKCS8_BASE64)) {
            val token = Es256Signer.fromPrivateKey(key).sign {
                issuer = "TEAMID"; audience("https://appleid.apple.com"); expiresAt = NOW + 1.hours
            }
            ecVerifier().verify(token)
        }
    }

    @Test
    fun anUnreadableKeyFailsWithoutEchoingIt() = runTest {
        val secret = Base64.Default.encode("definitely not a key but secret-looking".encodeToByteArray())

        val e = assertFailsWith<JwtException> { Es256Signer.fromPrivateKey(secret) }
        assertFalse(secret in (e.message ?: ""))
    }

    @Test
    fun customClaimsAreWritten() = runTest {
        val token = Es256Signer.fromPrivateKey(Vectors.EC_PKCS8_PEM).sign {
            issuer = "TEAMID"; audience("https://appleid.apple.com"); expiresAt = NOW + 1.hours
            claim("scope", "email")
            claim("n", 3)
        }
        val claims = ecVerifier().verify(token).claims

        assertEquals("email", claims.string("scope"))
        assertEquals(3L, claims.long("n"))
    }
}

class ClaimsTest {
    @Test
    fun readsRegisteredAndCustomClaims() {
        val claims = JwtClaims(
            Json.parseToJsonElement(
                """{"iss":"i","sub":"s","aud":["a","b"],"exp":10,"email_verified":true,"flag":"false"}""",
            ).jsonObject,
        )

        assertEquals(listOf("a", "b"), claims.audience)
        assertEquals(Instant.fromEpochSeconds(10), claims.expiresAt)
        assertEquals(true, claims.boolean("email_verified"))
        assertEquals(false, claims.boolean("flag"))
        assertNull(claims.string("missing"))
    }

    @Test
    fun toStringDoesNotIncludeValues() {
        val claims = JwtClaims(Json.parseToJsonElement("""{"email":"person@example.com"}""").jsonObject)

        assertTrue("email" in claims.toString())
        assertFalse("person@example.com" in claims.toString())
    }

    @Test
    fun decodeReadsWithoutVerifying() {
        val jwt = Jwt.decode(Vectors.RS256_TOKEN)

        assertEquals("rsa-1", jwt.header.keyId)
        assertEquals("https://issuer.example", jwt.claims.issuer)
    }
}
