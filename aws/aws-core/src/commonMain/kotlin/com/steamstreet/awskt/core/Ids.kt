package com.steamstreet.awskt.core

import kotlin.random.Random

/**
 * A UUID-shaped identifier: 32 lowercase hex digits with the 8-4-4-4-12 hyphens, 36 characters.
 *
 * One generator rather than one per module. AWS's own headers and request tokens are UUID-shaped,
 * two callers already need one — `amz-sdk-invocation-id` here and `ClientRequestToken` in
 * `aws-dynamodb`, whose maximum length is exactly 36 — and two hand-rolled generators drift: this
 * library shipped a 16-hex-digit invocation id next to a 36-character token for precisely that
 * reason.
 *
 * Public for the same reason [awsEnv] is, and with the same caveat: a sibling service module cannot
 * see an `internal` declaration across a module boundary, so the alternative is each module
 * carrying its own copy. It is not part of the client API and nothing here promises stability
 * beyond the shape.
 *
 * **Not cryptographically secure, and not a conforming UUID.** [Random] is the platform's default
 * RNG rather than a CSPRNG, and no version or variant bits are set. That is deliberate and adequate
 * for both callers — these identifiers exist so AWS can correlate the attempts of one call, and so
 * DynamoDB can de-duplicate a resumed transaction, neither of which is a security decision. Anything
 * that must be unguessable needs a different function, not this one with a comment removed.
 */
public fun randomUuidString(): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder(36)
    for (i in 0 until 32) {
        if (i == 8 || i == 12 || i == 16 || i == 20) sb.append('-')
        sb.append(hex[Random.nextInt(16)])
    }
    return sb.toString()
}
