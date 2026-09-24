package com.steamstreet.awskt.jwt

import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.operations.SignatureGenerator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/**
 * Signs JWTs with ES256: ECDSA on P-256 with SHA-256.
 *
 * Built for the Sign in with Apple client secret, which Apple requires to be an ES256 JWT signed
 * with the `.p8` key from the developer account:
 *
 * ```kotlin
 * val signer = Es256Signer.fromPrivateKey(p8, keyId = keyId)
 * val clientSecret = signer.sign {
 *     issuer = teamId
 *     subject = clientId
 *     audience("https://appleid.apple.com")
 *     issuedAt = now
 *     expiresAt = now + 180.days
 * }
 * ```
 *
 * Build one per key and reuse it; parsing the key is the expensive part.
 */
public class Es256Signer private constructor(
    private val keyId: String?,
    private val generator: SignatureGenerator,
) {
    /** Signs the claims [build] describes and returns the compact token. */
    public suspend fun sign(build: JwtClaimsBuilder.() -> Unit): String =
        sign(JwtClaimsBuilder().apply(build).build())

    /** Signs [claims] as they are and returns the compact token. */
    public suspend fun sign(claims: JsonObject): String {
        val header = buildJsonObject {
            put("alg", JwtAlgorithm.ES256.headerName)
            put("typ", "JWT")
            keyId?.let { put("kid", it) }
        }
        val signingInput = base64UrlEncode(header.toString().encodeToByteArray()) + "." +
            base64UrlEncode(claims.toString().encodeToByteArray())
        val signature = generator.generateSignature(signingInput.encodeToByteArray())
        return signingInput + "." + base64UrlEncode(signature)
    }

    override fun toString(): String = "Es256Signer(kid=$keyId)"

    public companion object {
        /**
         * Reads a P-256 private key.
         *
         * Accepts a PEM block (`BEGIN PRIVATE KEY` for PKCS#8, or `BEGIN EC PRIVATE KEY` for SEC1),
         * or the base64 of a PKCS#8 DER key with no PEM armour, which is how an Apple `.p8` key
         * often ends up once its header lines are stripped for storage.
         *
         * @param keyId written as the token header's `kid`.
         * @throws JwtException when the key cannot be read as a P-256 private key. The message never
         *   includes the key.
         */
        public suspend fun fromPrivateKey(privateKey: String, keyId: String? = null): Es256Signer {
            val decoder = cryptoProvider.get(ECDSA).privateKeyDecoder(EC.Curve.P256)
            val text = privateKey.trim()
            val key = try {
                when {
                    "BEGIN EC PRIVATE KEY" in text ->
                        decoder.decodeFromByteArray(EC.PrivateKey.Format.PEM.SEC1, text.encodeToByteArray())

                    "BEGIN" in text ->
                        decoder.decodeFromByteArray(EC.PrivateKey.Format.PEM, text.encodeToByteArray())

                    else -> decoder.decodeFromByteArray(
                        EC.PrivateKey.Format.DER,
                        Base64.Mime.decode(text),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The cause is kept for diagnosis; neither message includes key material.
                throw JwtException("The private key could not be read as a P-256 key", e)
            }
            return Es256Signer(keyId, key.signatureGenerator(SHA256, ECDSA.SignatureFormat.RAW))
        }
    }
}

/** The claims of a token being signed. Registered claims have properties; add others with [claim]. */
public class JwtClaimsBuilder {
    public var issuer: String? = null
    public var subject: String? = null
    public var issuedAt: Instant? = null
    public var expiresAt: Instant? = null
    public var notBefore: Instant? = null
    public var jwtId: String? = null

    private val audiences = mutableListOf<String>()
    private val extra = linkedMapOf<String, JsonElement>()

    /**
     * Adds audiences. One audience is written as a string, several as an array; Apple's token
     * endpoint expects the single-string form.
     */
    public fun audience(vararg values: String) {
        audiences += values
    }

    public fun claim(name: String, value: String?): Unit = claim(name, JsonPrimitive(value))
    public fun claim(name: String, value: Number?): Unit = claim(name, JsonPrimitive(value))
    public fun claim(name: String, value: Boolean?): Unit = claim(name, JsonPrimitive(value))

    public fun claim(name: String, value: JsonElement) {
        extra[name] = value
    }

    internal fun build(): JsonObject = buildJsonObject {
        issuer?.let { put("iss", it) }
        subject?.let { put("sub", it) }
        when (audiences.size) {
            0 -> Unit
            1 -> put("aud", audiences.single())
            else -> put("aud", JsonArray(audiences.map(::JsonPrimitive)))
        }
        issuedAt?.let { put("iat", it.epochSeconds) }
        expiresAt?.let { put("exp", it.epochSeconds) }
        notBefore?.let { put("nbf", it.epochSeconds) }
        jwtId?.let { put("jti", it) }
        extra.forEach { (name, value) -> put(name, value) }
    }
}
