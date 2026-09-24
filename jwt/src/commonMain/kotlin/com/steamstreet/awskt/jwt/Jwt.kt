package com.steamstreet.awskt.jwt

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/** The signature algorithms this module verifies. */
public enum class JwtAlgorithm(public val headerName: String) {
    /** RSASSA-PKCS1-v1_5 with SHA-256. What Apple, Google and Cognito sign ID tokens with. */
    RS256("RS256"),

    /** ECDSA on P-256 with SHA-256. What Apple requires for a Sign in with Apple client secret. */
    ES256("ES256"),
    ;

    internal companion object {
        fun fromHeader(name: String?): JwtAlgorithm? = entries.firstOrNull { it.headerName == name }
    }
}

/** Something about a token, or a key, could not be used. The base of this module's exceptions. */
public open class JwtException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The token is not a well-formed compact JWS. */
public class JwtFormatException(message: String, cause: Throwable? = null) : JwtException(message, cause)

/**
 * A token was rejected by [JwtVerifier.verify]. [reason] says which check failed; the message says
 * how, and never includes the token itself.
 */
public class JwtVerificationException(
    public val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : JwtException(message, cause) {
    public enum class Reason {
        /** Not a well-formed token. */
        MALFORMED,

        /** The header names an algorithm the verifier does not accept, including `none`. */
        ALGORITHM_NOT_ALLOWED,

        /** No key matches the token's `kid` and algorithm, even after refreshing the key set. */
        KEY_NOT_FOUND,

        /** The signature does not verify. */
        INVALID_SIGNATURE,

        /** `exp` is missing, or has passed by more than the allowed clock skew. */
        EXPIRED,

        /** `nbf` is still in the future by more than the allowed clock skew. */
        NOT_YET_VALID,

        /** `iss` is missing or not one of the accepted issuers. */
        ISSUER,

        /** `aud` is missing or names none of the accepted audiences. */
        AUDIENCE,

        /** The header carries a `crit` extension this verifier does not implement. */
        UNSUPPORTED_HEADER,
    }
}

/** A token's header. [json] carries every member, including ones not modelled here. */
public class JwtHeader(public val json: JsonObject) {
    /** `alg`, as written. */
    public val algorithm: String? get() = json.string("alg")

    /** `kid`, which names the key in the issuer's key set. */
    public val keyId: String? get() = json.string("kid")

    /** `typ`. */
    public val type: String? get() = json.string("typ")

    override fun toString(): String = "JwtHeader($json)"
}

/**
 * A token's claims. The registered claims have properties; anything else is read with [string],
 * [boolean], [long] or [get].
 *
 * [toString] lists the claim names only, because claims routinely carry personal data such as an
 * email address.
 */
public class JwtClaims(public val json: JsonObject) {
    public val issuer: String? get() = string("iss")
    public val subject: String? get() = string("sub")

    /** `aud`, which may be written as one string or as an array; both read as a list. */
    public val audience: List<String>
        get() = when (val aud = json["aud"]) {
            is JsonPrimitive -> listOfNotNull(aud.contentOrNull)
            is JsonArray -> aud.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }

    public val expiresAt: Instant? get() = instant("exp")
    public val notBefore: Instant? get() = instant("nbf")
    public val issuedAt: Instant? get() = instant("iat")
    public val jwtId: String? get() = string("jti")

    /** A claim as a string, or null when it is absent or not a JSON primitive. */
    public fun string(name: String): String? = json.string(name)

    /**
     * A claim as a boolean. Also accepts the strings `"true"` and `"false"`, because Apple writes
     * `email_verified` as a string in some tokens and as a boolean in others.
     */
    public fun boolean(name: String): Boolean? {
        val primitive = json[name] as? JsonPrimitive ?: return null
        return primitive.booleanOrNull ?: primitive.contentOrNull?.toBooleanStrictOrNull()
    }

    /** A claim as a whole number. */
    public fun long(name: String): Long? = (json[name] as? JsonPrimitive)?.longOrNull

    /** A claim as raw JSON. */
    public operator fun get(name: String): JsonElement? = json[name]

    private fun instant(name: String): Instant? = long(name)?.let(Instant::fromEpochSeconds)

    override fun toString(): String = "JwtClaims(${json.keys})"
}

/**
 * A decoded compact JWS: `header.payload.signature`.
 *
 * [decode] only parses. It checks nothing about the signature or the claims, so a [Jwt] obtained
 * that way must not be trusted. Use [JwtVerifier.verify] for that.
 */
public class Jwt private constructor(
    public val header: JwtHeader,
    public val claims: JwtClaims,
    /** The bytes the signature covers: the first two segments, as sent. */
    internal val signingInput: ByteArray,
    internal val signature: ByteArray,
) {
    override fun toString(): String = "Jwt(header=$header, claims=$claims)"

    public companion object {
        /**
         * Parses [token] without verifying it.
         *
         * @throws JwtFormatException when it is not three base64url segments whose first two are
         *   JSON objects.
         */
        public fun decode(token: String): Jwt {
            val parts = token.split('.')
            if (parts.size != 3) {
                throw JwtFormatException("A JWS has three segments; this has ${parts.size}")
            }
            val (headerPart, payloadPart, signaturePart) = parts
            return Jwt(
                header = JwtHeader(decodeJsonObject(headerPart, "header")),
                claims = JwtClaims(decodeJsonObject(payloadPart, "payload")),
                signingInput = "$headerPart.$payloadPart".encodeToByteArray(),
                signature = base64UrlDecode(signaturePart, "signature"),
            )
        }

        private fun decodeJsonObject(segment: String, name: String): JsonObject {
            val text = base64UrlDecode(segment, name).decodeToString()
            return try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: SerializationException) {
                throw JwtFormatException("The $name is not JSON", e)
            } catch (e: IllegalArgumentException) {
                throw JwtFormatException("The $name is not a JSON object", e)
            }
        }
    }
}

private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
private val base64UrlUnpadded = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

internal fun base64UrlDecode(segment: String, name: String): ByteArray = try {
    base64Url.decode(segment)
} catch (e: IllegalArgumentException) {
    throw JwtFormatException("The $name is not base64url", e)
}

internal fun base64UrlEncode(bytes: ByteArray): String = base64UrlUnpadded.encode(bytes)

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
