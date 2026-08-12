package com.steamstreet.awskt.s3

import com.steamstreet.awskt.core.AwsConfigurationException
import com.steamstreet.awskt.core.awsEnv

/** How a bucket is addressed in the URL. */
public enum class S3AddressingStyle {
    /** `https://{bucket}.s3.{region}.amazonaws.com/{key}` — the default. */
    VIRTUAL_HOSTED,

    /** `https://s3.{region}.amazonaws.com/{bucket}/{key}` — legacy names, and every local stack. */
    PATH,
}

/**
 * A resolved S3 endpoint.
 *
 * @param authority signed as `host` and sent as `Host`, **including a non-default port**.
 * @param basePath `""` for virtual-hosted, `/{bucket}` for path-style. The object key is appended
 *   to this, already encoded.
 */
public class ResolvedS3Endpoint(
    public val scheme: String,
    public val authority: String,
    public val basePath: String,
    public val style: S3AddressingStyle,
) {
    /** The origin, with no trailing slash. */
    public val origin: String get() = "$scheme://$authority"

    override fun toString(): String = "ResolvedS3Endpoint($origin$basePath, $style)"
}

/** A bucket name that cannot be expressed in a URI at all. */
public class InvalidBucketNameException(message: String) : Exception(message)

/** An endpoint override that would send credentials over plaintext HTTP. */
public class InsecureEndpointException(message: String) : Exception(message)

/**
 * True when [bucket] can be the leftmost label of an HTTPS virtual-hosted authority.
 *
 * Stricter than "is a legal bucket name" on purpose, and the extra rule is the dots: AWS's
 * certificate for `*.s3.{region}.amazonaws.com` is a single-label wildcard, so `my.bucket` presents
 * as `my.bucket.s3...` and fails hostname verification. Dotted buckets are legal, common, and must
 * be addressed path-style.
 */
internal fun isVirtualHostCompatible(bucket: String): Boolean {
    if (bucket.length !in 3..63) return false
    if (bucket.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' }) return false
    if (bucket.first() == '-' || bucket.last() == '-') return false
    return true
}

/** Rejects only what cannot appear in a URI authority or path segment at all. */
private fun requireExpressibleBucket(bucket: String) {
    if (bucket.isBlank()) {
        throw InvalidBucketNameException("Bucket name must not be blank")
    }
    val illegal = bucket.firstOrNull { it.isWhitespace() || it.isISOControl() || it in "/\\?#[]@" }
    if (illegal != null) {
        throw InvalidBucketNameException(
            "Bucket name '$bucket' contains a character that cannot appear in a URI: '$illegal'",
        )
    }
}

/**
 * Resolves the endpoint and the addressing style for a bucket.
 *
 * ### Addressing is a decision, not a validation gate
 *
 * A name that is not virtual-host-compatible is **not an error**. `us-east-1` buckets created under
 * the legacy rules may contain uppercase letters and underscores and run to 255 characters; they
 * still exist, they are still reachable path-style, and AWS's own documented path-style example
 * uses the dotted bucket `example.com`. So the modern naming rule decides *how* we address the
 * bucket, never *whether* we will call it. A 400 from S3 is strictly more informative than a client
 * that refuses to try.
 *
 * Path-style is forced when any of these hold:
 *  - [forcePathStyle] is set;
 *  - an endpoint override is in play — LocalStack and MinIO are path-style only;
 *  - the bucket is not virtual-host-compatible over HTTPS.
 *
 * ### `us-east-1`
 *
 * Resolves to `s3.us-east-1.amazonaws.com`, **never** the legacy global `s3.amazonaws.com`. Buckets
 * in regions launched after 2019-03-20 return HTTP 400 from the global endpoint, so the "friendly"
 * legacy alias is a latent, region-dependent failure.
 *
 * @param allowInsecureEndpoint permit an `http://` override. Loopback only — see [InsecureEndpointException].
 */
public fun resolveS3Endpoint(
    bucket: String,
    region: String,
    endpointOverride: String? = null,
    forcePathStyle: Boolean = false,
    allowInsecureEndpoint: Boolean = false,
    getEnv: (String) -> String? = ::awsEnv,
): ResolvedS3Endpoint {
    requireExpressibleBucket(bucket)

    val override = endpointOverride?.takeIf { it.isNotBlank() }
        ?: getEnv("AWS_ENDPOINT_URL_S3")?.takeIf { it.isNotBlank() }
        ?: getEnv("AWS_ENDPOINT_URL")?.takeIf { it.isNotBlank() }

    if (override != null) {
        val (scheme, authority) = splitOrigin(override)
        requireSecure(scheme, authority, allowInsecureEndpoint)
        // Always path-style against an override. Every local S3 implementation is path-style, and
        // prefixing a bucket onto an arbitrary override host would be a DNS lookup nobody asked for.
        return ResolvedS3Endpoint(scheme, authority, "/$bucket", S3AddressingStyle.PATH)
    }

    val pathStyle = forcePathStyle || !isVirtualHostCompatible(bucket)
    return if (pathStyle) {
        ResolvedS3Endpoint("https", "s3.$region.amazonaws.com", "/$bucket", S3AddressingStyle.PATH)
    } else {
        ResolvedS3Endpoint("https", "$bucket.s3.$region.amazonaws.com", "", S3AddressingStyle.VIRTUAL_HOSTED)
    }
}

/**
 * `UNSIGNED-PAYLOAD` — which presigning has no alternative to — puts body integrity entirely on
 * TLS. Plaintext is refused outside loopback rather than merely warned about.
 */
private fun requireSecure(scheme: String, authority: String, allowInsecure: Boolean) {
    if (scheme == "https") return
    val host = authority.substringBefore(':')
    val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]" ||
        host.endsWith(".localhost")
    if (!allowInsecure) {
        throw InsecureEndpointException(
            "Refusing a plaintext '$scheme://' S3 endpoint. Presigned URLs rely on TLS for body " +
                "integrity because the payload is signed as UNSIGNED-PAYLOAD. Set " +
                "allowInsecureEndpoint = true for a loopback endpoint such as LocalStack.",
        )
    }
    if (!loopback) {
        throw InsecureEndpointException(
            "allowInsecureEndpoint permits plaintext for loopback only, but the host was '$host'.",
        )
    }
}

/** Splits `scheme://authority[/...]`, keeping any explicit port in the authority. */
private fun splitOrigin(url: String): Pair<String, String> {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) {
        throw AwsConfigurationException("S3 endpoint override must include a scheme: '$url'")
    }
    val scheme = url.substring(0, schemeEnd).lowercase()
    val rest = url.substring(schemeEnd + 3)
    val authority = rest.substringBefore('/').substringBefore('?').ifBlank {
        throw AwsConfigurationException("S3 endpoint override has no host: '$url'")
    }
    return scheme to authority
}
