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

/**
 * One cached derivation.
 *
 * [credentials] is held for its **identity**, not its contents: `AwsCredentials` is immutable, so
 * the same reference is necessarily the same secret, and a reference comparison settles the common
 * case without hashing anything. [credentialId] is the contents-based identity used when the
 * reference does not match — a digest of the secret, never the secret itself.
 */
private class CachedSigningKey(
    val credentials: AwsCredentials,
    val credentialId: String,
    val scope: String,
    val key: ByteArray,
)

/**
 * How many `(credential, date, region, service)` tuples stay cached at once.
 *
 * Four rather than one because a single slot **thrashes on a mixed-service workload**: a Lambda that
 * alternates DynamoDB and S3 calls evicted the other service's key on every sign and re-derived four
 * HMACs each time, so the cache had a 0% hit rate on precisely the container it exists to speed up.
 * Four covers the realistic shapes — two or three services in one region, or one service either side
 * of a midnight rollover — and is small enough that a linear scan is cheaper than any map.
 */
private const val SIGNING_KEY_SLOTS = 4

private val signingKeySlots = arrayOfNulls<CachedSigningKey>(SIGNING_KEY_SLOTS)

/** Round-robin replacement. Deliberately not an LRU: a counter would cost more than a re-derivation. */
private var nextSigningKeySlot = 0

/**
 * Test-only: how many times [deriveSigningKey] has been reached through [signingKey].
 *
 * Not part of any published API — the whole file is `internal`. It exists because "the cache still
 * produces correct signatures" is a much weaker claim than "the cache still *avoids the work*", and
 * only the second one catches a regression that quietly turns every sign back into four HMACs.
 */
internal var signingKeyDerivations: Int = 0
    private set

/** Test-only: empties the cache so a derivation-count assertion starts from a known state. */
internal fun resetSigningKeyCacheForTest() {
    for (i in signingKeySlots.indices) signingKeySlots[i] = null
    nextSigningKeySlot = 0
    signingKeyDerivations = 0
}

/**
 * Small fixed-size cache for the derived signing key.
 *
 * Four HMACs per request is not free, and a warm Lambda signs the same
 * `(credential, date, region, service)` tuple thousands of times. [SIGNING_KEY_SLOTS] slots are
 * scanned linearly, which at this size is a handful of reference comparisons.
 *
 * ### Two lookups, and why the first one hashes nothing
 *
 * The **fast path** compares the caller's [AwsCredentials] by reference. `AwsCredentials` is
 * immutable, so the same object is the same secret, and there is nothing to prove by hashing it —
 * which matters because the digest below used to be computed on *every* call, hits included, so the
 * cache paid a SHA-256 to avoid four HMACs. A warm container whose provider hands back one cached
 * credentials object now hashes nothing at all.
 *
 * The **slow path** — a different object, which is what a credentials refresh produces — falls back
 * to the contents-based identity and, on a match, re-publishes the slot under the new reference so
 * the next call is fast again. A newly allocated `AwsCredentials` carrying the *same* values
 * therefore costs one SHA-256 and no re-derivation. A provider that allocates a fresh object per
 * call pays that digest per call; that is the deliberate trade for never signing with the wrong key.
 *
 * The contents-based identity is `accessKeyId` plus a **digest of the secret**, never the secret
 * itself — cache keys have a way of ending up in diagnostics. The digest is not paranoia: keying on
 * the access key id alone is wrong, because two different secrets can be presented under one id, and
 * the second then signs with the first one's key. A live test caught exactly that, having quietly
 * signed a request with a deliberately-wrong secret and had AWS accept it.
 *
 * The scope includes the date stamp so a container alive across midnight, or one handed rotated
 * credentials, re-derives rather than signing with a stale key.
 *
 * Reads and writes are unsynchronized, exactly as when this was a single slot. A reference
 * assignment — to a `var` or to an array element — is atomic on both JVM and Native, every slot is
 * immutable once published, and the value is a pure function of the key, so the worst a race can do
 * is recompute or drop an entry.
 */
internal fun signingKey(
    credentials: AwsCredentials,
    dateStamp: String,
    region: String,
    service: String,
): ByteArray {
    val scope = "$dateStamp|$region|$service"

    for (slot in signingKeySlots) {
        val cached = slot ?: continue
        if (cached.credentials === credentials && cached.scope == scope) return cached.key
    }

    val secretDigest = sha256(credentials.secretAccessKey.encodeToByteArray()).toHexLower().substring(0, 16)
    val credentialId = "${credentials.accessKeyId}|$secretDigest"

    for (i in signingKeySlots.indices) {
        val cached = signingKeySlots[i] ?: continue
        if (cached.credentialId == credentialId && cached.scope == scope) {
            // Same values under a new object: keep the derivation, adopt the new reference.
            signingKeySlots[i] = CachedSigningKey(credentials, credentialId, scope, cached.key)
            return cached.key
        }
    }

    signingKeyDerivations++
    val key = deriveSigningKey(credentials.secretAccessKey, dateStamp, region, service)
    val slot = nextSigningKeySlot
    nextSigningKeySlot = (slot + 1) % SIGNING_KEY_SLOTS
    signingKeySlots[slot] = CachedSigningKey(credentials, credentialId, scope, key)
    return key
}
