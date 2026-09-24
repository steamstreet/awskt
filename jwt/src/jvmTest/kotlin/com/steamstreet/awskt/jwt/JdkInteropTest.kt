package com.steamstreet.awskt.jwt

import kotlinx.coroutines.test.runTest
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * The JDK's own `Signature` implementations, used directly, as a second independent oracle: the
 * signer's output must verify under `SHA256withECDSAinP1363Format` (the raw r||s form JWS uses),
 * and a token the JDK signs must verify here.
 */
class JdkInteropTest {
    @Test
    fun theSignersOutputVerifiesWithTheJdk() = runTest {
        val token = Es256Signer.fromPrivateKey(Vectors.EC_PKCS8_BASE64).sign {
            issuer = "TEAMID"
            expiresAt = Clock.System.now() + 1.hours
        }
        val (h, p, s) = token.split('.')

        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(vectorPublicKeyDer()))
        val verifier = Signature.getInstance("SHA256withECDSAinP1363Format").apply {
            initVerify(publicKey)
            update("$h.$p".toByteArray())
        }

        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(s)))
    }

    @Test
    fun aTokenSignedByTheJdkVerifies() = runTest {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val header = base64UrlEncode("""{"alg":"ES256","kid":"jdk"}""".toByteArray())
        val exp = (Clock.System.now() + 1.hours).epochSeconds
        val payload = base64UrlEncode("""{"iss":"i","aud":"a","exp":$exp}""".toByteArray())
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").apply {
            initSign(pair.private)
            update("$header.$payload".toByteArray())
        }.sign()

        val point = (pair.public as java.security.interfaces.ECPublicKey).w
        fun coordinate(v: java.math.BigInteger) =
            base64UrlEncode(v.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it })
        val jwk = """{"kty":"EC","crv":"P-256","kid":"jdk","x":"${coordinate(point.affineX)}","y":"${coordinate(point.affineY)}"}"""

        val jwt = JwtVerifier(
            keys = { _, algorithm ->
                JwtVerificationKey.fromJwk(kotlinx.serialization.json.Json.parseToJsonElement(jwk)
                    .let { it as kotlinx.serialization.json.JsonObject }, algorithm)
            },
            issuers = setOf("i"),
            audiences = setOf("a"),
            algorithms = setOf(JwtAlgorithm.ES256),
        ).verify("$header.$payload.${base64UrlEncode(signature)}")

        assertEquals("jdk", jwt.header.keyId)
    }

    /** The public half of the vector key as X.509 DER, built from the JWK the vectors carry. */
    private fun vectorPublicKeyDer(): ByteArray {
        val jwk = kotlinx.serialization.json.Json.parseToJsonElement(Vectors.EC_JWK) as kotlinx.serialization.json.JsonObject
        fun c(name: String) = Base64.getUrlDecoder().decode((jwk[name] as kotlinx.serialization.json.JsonPrimitive).content)
        // SubjectPublicKeyInfo for a P-256 uncompressed point: fixed 26-byte prefix, then 04||x||y.
        val prefix = Base64.getDecoder().decode("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE")
        return prefix + c("x") + c("y")
    }
}
