package com.steamstreet.awskt.signing

internal const val ALGORITHM = "AWS4-HMAC-SHA256"
internal const val TERMINATOR = "aws4_request"

/** The four chained HMACs that turn a secret access key into a date/region/service-scoped key. */
internal fun deriveSigningKey(
    secretAccessKey: String,
    dateStamp: String,
    region: String,
    service: String,
): ByteArray {
    val kDate = hmacSha256("AWS4$secretAccessKey".encodeToByteArray(), dateStamp.encodeToByteArray())
    val kRegion = hmacSha256(kDate, region.encodeToByteArray())
    val kService = hmacSha256(kRegion, service.encodeToByteArray())
    return hmacSha256(kService, TERMINATOR.encodeToByteArray())
}

private class CachedSigningKey(val id: String, val key: ByteArray)

/**
 * Single-slot cache for the derived signing key.
 *
 * Four HMACs per request is not free, and a warm Lambda signs the same
 * `(credential, date, region, service)` tuple thousands of times. A single slot is enough because a
 * given container almost always talks to one service in one region.
 *
 * The cache key includes a **digest of the secret**, never the secret itself — cache keys have a
 * way of ending up in diagnostics. The digest is not paranoia: keying on the access key id alone is
 * wrong, because two different secrets can be presented under one id, and the second then signs
 * with the first one's key. A live test caught exactly that, having quietly signed a request with a
 * deliberately-wrong secret and had AWS accept it.
 *
 * The date stamp is included so a container alive across midnight, or one handed rotated
 * credentials, re-derives rather than signing with a stale key.
 *
 * Reads and writes are unsynchronized. A reference assignment is atomic on both JVM and Native, and
 * the value is a pure function of the key, so the worst a race can do is recompute.
 */
private var cachedSigningKey: CachedSigningKey? = null

internal fun signingKey(
    credentials: AwsCredentials,
    dateStamp: String,
    region: String,
    service: String,
): ByteArray {
    val secretDigest = sha256(credentials.secretAccessKey.encodeToByteArray()).toHexLower().substring(0, 16)
    val id = "${credentials.accessKeyId}|$secretDigest|$dateStamp|$region|$service"
    cachedSigningKey?.let { if (it.id == id) return it.key }

    val key = deriveSigningKey(credentials.secretAccessKey, dateStamp, region, service)
    cachedSigningKey = CachedSigningKey(id, key)
    return key
}
