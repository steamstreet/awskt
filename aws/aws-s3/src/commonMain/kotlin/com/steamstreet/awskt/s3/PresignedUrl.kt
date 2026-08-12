package com.steamstreet.awskt.s3

/** The HTTP method a presigned URL is valid for. Signed, and therefore not interchangeable. */
public enum class PresignMethod(public val httpMethod: String) {
    GET("GET"),
    PUT("PUT"),
    HEAD("HEAD"),
    DELETE("DELETE"),
}

/**
 * When a presigned URL stops working.
 *
 * **Deliberately not a `Long`.** A presigned URL dies at the earlier of `X-Amz-Expires` and the
 * expiry of the credentials that signed it, and in Lambda the second of those is *unknowable* —
 * the runtime publishes `AWS_SESSION_TOKEN` with no expiry alongside it. A bare timestamp would
 * therefore read as a promise the library cannot keep, so the unknown case is given its own type
 * and callers have to look at it.
 */
public sealed class PresignExpiry {
    /**
     * The URL expires at [epochMillis], and that is the truth: either the credentials are long-lived
     * or their expiry was known and has been taken into account.
     */
    public class Known(public val epochMillis: Long) : PresignExpiry() {
        override fun toString(): String = "Known($epochMillis)"
    }

    /**
     * The URL expires **no later than** [requestedEpochMillis], and possibly much sooner: it was
     * signed with session credentials whose own expiry could not be determined.
     *
     * This is the normal case inside Lambda. There is no way to narrow it from within the process.
     */
    public class BoundedByUnknownSession(public val requestedEpochMillis: Long) : PresignExpiry() {
        override fun toString(): String = "BoundedByUnknownSession(<=$requestedEpochMillis)"
    }
}

/**
 * A presigned S3 URL.
 *
 * ### This object is a credential
 *
 * The URL grants **the signing role's permissions, not the end user's**, to anyone holding it. It
 * is reusable until it expires and **it cannot be revoked** — the only controls are IAM-side
 * (`s3:signatureAge` and network-path conditions in a bucket policy), not in this library. Treat
 * the string exactly as you would treat a secret key.
 *
 * [toString] redacts, but the redaction protects log lines, not the value itself.
 */
public class PresignedUrl(
    public val url: String,
    public val method: PresignMethod,
    /**
     * The lowercase header names covered by the signature. The fetcher must send **exactly** these
     * and no other `x-amz-*` header — an unsigned one is a 403, not a warning.
     */
    public val signedHeaderNames: List<String>,
    public val expiry: PresignExpiry,
) {
    /** Redacted. See [redactPresignedUrl]. */
    override fun toString(): String = "PresignedUrl(${redactPresignedUrl(url)})"
}

/** Query parameters whose values are credential material and must never be logged. */
private val REDACTED_PARAMETERS = setOf("X-Amz-Signature", "X-Amz-Security-Token")

/**
 * Blanks the signature and the session token in a presigned URL.
 *
 * **Plain string work, not a regex, and specifically not a lookbehind.** Kotlin/Native ships a
 * different regex implementation from the JVM's, lookbehind is the least portable construct across
 * KMP backends, and a silent non-match is indistinguishable from a successful redaction — so a
 * regex here would fail *open* on the native targets while passing every JVM test. The security
 * property has to hold on the platform that actually ships.
 *
 * Both parameters matter. The signature is the obvious one; the URL also carries the **session
 * token in full**, which is the same secret `AwsCredentials.toString()` already refuses to print.
 */
public fun redactPresignedUrl(url: String): String {
    val queryStart = url.indexOf('?')
    if (queryStart < 0) return url

    val prefix = url.substring(0, queryStart)
    val query = url.substring(queryStart + 1)

    val redacted = query.split("&").joinToString("&") { pair ->
        val eq = pair.indexOf('=')
        if (eq < 0) {
            pair
        } else {
            val name = pair.substring(0, eq)
            if (name in REDACTED_PARAMETERS) "$name=REDACTED" else pair
        }
    }
    return "$prefix?$redacted"
}
