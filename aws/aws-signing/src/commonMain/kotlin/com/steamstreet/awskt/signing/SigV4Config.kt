package com.steamstreet.awskt.signing

/** Where the signature is carried. */
public enum class SignatureLocation {
    /** An `Authorization` header. The normal case. */
    HEADERS,

    /** Query-string parameters — a presigned URL. */
    QUERY_STRING,
}

/** Whether to emit `x-amz-content-sha256` alongside the signature. Required by S3. */
public enum class SignedBodyHeader {
    NONE,
    X_AMZ_CONTENT_SHA256,
}

/** How the payload hash that goes into the canonical request is determined. */
public sealed class PayloadHash {
    /** SHA-256 the body. Correct default; requires the whole body in memory. */
    public data object Compute : PayloadHash()

    /** The well-known hash of a zero-length body. */
    public data object EmptyBody : PayloadHash()

    /**
     * The literal `UNSIGNED-PAYLOAD`. S3 over HTTPS only, and **mandatory for presigning** — at
     * presign time the body does not exist yet, so there is nothing to hash.
     */
    public data object Unsigned : PayloadHash()

    /** A hash already computed elsewhere, as lowercase hex. */
    public class Precomputed(public val hex: String) : PayloadHash()
}

/**
 * Signing parameters. One instance describes how to sign a single request.
 *
 * @param region the AWS region, e.g. `us-east-1`.
 * @param service the signing name, which is not always the service name — see
 *   [signingNameFor].
 * @param location whether to sign into headers or into the query string.
 * @param expiresInSeconds presigned-URL lifetime. Required when [location] is
 *   [SignatureLocation.QUERY_STRING], rejected otherwise.
 * @param payloadHash how to derive the payload hash.
 * @param signedBodyHeader whether to emit `x-amz-content-sha256`.
 * @param doubleUriEncode encode path segments a second time. **False for S3 only.**
 * @param normalizeUriPath apply RFC 3986 dot-segment removal. **False for S3 only.**
 * @param omitSessionToken exclude `X-Amz-Security-Token` from the signature, adding it to the
 *   request only afterwards. Almost always false.
 */
public class SigV4Config(
    public val region: String,
    public val service: String,
    public val location: SignatureLocation = SignatureLocation.HEADERS,
    public val expiresInSeconds: Long? = null,
    public val payloadHash: PayloadHash = PayloadHash.Compute,
    public val signedBodyHeader: SignedBodyHeader = SignedBodyHeader.NONE,
    public val doubleUriEncode: Boolean = true,
    public val normalizeUriPath: Boolean = true,
    public val omitSessionToken: Boolean = false,
) {
    init {
        when (location) {
            SignatureLocation.QUERY_STRING -> {
                requireNotNull(expiresInSeconds) {
                    "expiresInSeconds is required when signing via QUERY_STRING"
                }
                require(expiresInSeconds > 0) {
                    "expiresInSeconds must be positive, was $expiresInSeconds"
                }
                require(expiresInSeconds <= MAX_PRESIGN_EXPIRY_SECONDS) {
                    "expiresInSeconds must be at most $MAX_PRESIGN_EXPIRY_SECONDS (7 days), was " +
                        "$expiresInSeconds. The SigV4 signing key is date-scoped and cannot be " +
                        "valid for longer."
                }
            }

            SignatureLocation.HEADERS -> require(expiresInSeconds == null) {
                "expiresInSeconds applies only to QUERY_STRING signing"
            }
        }
    }

    /** Deliberately reveals no credential material; there is none here, and it must stay that way. */
    override fun toString(): String =
        "SigV4Config(region=$region, service=$service, location=$location)"

    public companion object {
        /** Seven days, the maximum lifetime AWS will honour on a presigned URL. */
        public const val MAX_PRESIGN_EXPIRY_SECONDS: Long = 604_800
    }
}

/**
 * The name a service signs as, which occasionally differs from the name in its hostname. Getting
 * this wrong produces `SignatureDoesNotMatch`, which reads exactly like a signer bug.
 */
public fun signingNameFor(serviceId: String): String = when (serviceId) {
    "dynamodb-streams" -> "dynamodb" // host is streams.dynamodb.*
    "appconfigdata" -> "appconfig"
    else -> serviceId
}
