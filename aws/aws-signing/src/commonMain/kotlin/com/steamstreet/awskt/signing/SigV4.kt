package com.steamstreet.awskt.signing

/**
 * Headers excluded from the signature.
 *
 * The first group is AWS's own list (hop-by-hop headers plus a few that proxies rewrite). The last
 * entry is a deliberate divergence — see below.
 */
private val SKIPPED_HEADERS = setOf(
    "expect",
    "sec-websocket-key",
    "sec-websocket-protocol",
    "sec-websocket-version",
    "user-agent",
    "x-amzn-trace-id",
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailers",
    "transfer-encoding",
    "upgrade",

    // DIVERGENCE: AWS signs content-length; we do not.
    //
    // The reason is self-consistency, not convenience. Ktor sets Content-Length *after* we hand it
    // the request, so signing a value we did not write is latent breakage on any engine swap or
    // version bump — exactly the argument that already excludes user-agent above.
    //
    // It costs us two fixtures: post-x-www-form-urlencoded and post-x-www-form-urlencoded-parameters
    // are documented skips in both modes. That is a known, priced trade.
    //
    // (It is *also* a no-op when presigning, since there is no body at signing time. Do not record
    // that as the justification — a wrong reason invites a future "simplification" that re-enables
    // the header for header-mode signing and breaks on the next Ktor upgrade.)
    "content-length",
)

/**
 * AWS Signature Version 4.
 *
 * Supports both header-based signing (an `Authorization` header) and query-string signing
 * (presigned URLs). The two share everything except where the credential material is carried:
 * canonical URI, canonical query, header canonicalization and key derivation are identical.
 *
 * This object has no HTTP client, no I/O, and no dependency on the rest of awskt. That constraint
 * is what allows AWS's own fixture corpus to drive it directly.
 */
public object SigV4 {

    /**
     * Signs [request] and returns the headers and query parameters to send.
     *
     * @param signingInstantMillis the signing time, in epoch milliseconds. Passed in rather than
     *   read from the clock so that signing is deterministic and testable against fixed vectors.
     */
    public fun sign(
        request: SigningRequest,
        credentials: AwsCredentials,
        config: SigV4Config,
        signingInstantMillis: Long,
    ): SignedRequest {
        val time = Sigv4Time(signingInstantMillis)
        val scope = "${time.dateStamp}/${config.region}/${config.service}/$TERMINATOR"
        val credentialValue = "${credentials.accessKeyId}/$scope"
        val presigning = config.location == SignatureLocation.QUERY_STRING
        val sessionToken = credentials.sessionToken
        val signToken = sessionToken != null && !config.omitSessionToken

        val payloadHash = when (val spec = config.payloadHash) {
            PayloadHash.Compute -> sha256(request.body).toHexLower()
            PayloadHash.EmptyBody -> EMPTY_BODY_SHA256
            PayloadHash.Unsigned -> UNSIGNED_PAYLOAD
            is PayloadHash.Precomputed -> spec.hex
        }

        val queryToSign = ArrayList<Pair<String, String>>(request.queryParameters)
        val headersToSign = ArrayList<Pair<String, String>>()

        // `host` is signed in BOTH modes, and we supply it rather than trusting the caller's header
        // set. smithy-kotlin adds it only when signing via headers and otherwise relies on the
        // request already carrying one; when it does not, the result is an empty X-Amz-SignedHeaders
        // and a presigned URL that AWS rejects.
        headersToSign += "host" to request.host
        for ((name, value) in request.headers) {
            val lower = name.lowercase()
            if (lower == "host" || lower in SKIPPED_HEADERS) continue
            headersToSign += lower to value
        }

        if (presigning) {
            queryToSign += "X-Amz-Algorithm" to ALGORITHM
            queryToSign += "X-Amz-Credential" to credentialValue
            queryToSign += "X-Amz-Date" to time.amzDate
            queryToSign += "X-Amz-Expires" to config.expiresInSeconds.toString()
            if (signToken) queryToSign += "X-Amz-Security-Token" to sessionToken!!
        } else {
            headersToSign += "x-amz-date" to time.amzDate
            if (config.signedBodyHeader == SignedBodyHeader.X_AMZ_CONTENT_SHA256) {
                headersToSign += "x-amz-content-sha256" to payloadHash
            }
            if (signToken) headersToSign += "x-amz-security-token" to sessionToken!!
        }

        // Group duplicates by name, preserving encounter order of the values — AWS joins them in
        // the order received, not sorted (see the get-header-value-order fixture).
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for ((name, value) in headersToSign) {
            grouped.getOrPut(name) { ArrayList(1) }.add(value)
        }
        val signedHeaderNames = grouped.keys.sorted()
        val signedHeaders = signedHeaderNames.joinToString(";")

        // Must be injected after the signed-header list is known but before canonicalization.
        if (presigning) queryToSign += "X-Amz-SignedHeaders" to signedHeaders

        val canonicalHeaders = buildString {
            for (name in signedHeaderNames) {
                append(name)
                append(':')
                append(grouped.getValue(name).joinToString(",") { canonicalHeaderValue(it) })
                append('\n')
            }
        }

        val canonicalRequest = buildString {
            append(request.method).append('\n')
            append(canonicalUri(request.path, config.doubleUriEncode, config.normalizeUriPath))
                .append('\n')
            append(canonicalQuery(queryToSign)).append('\n')
            append(canonicalHeaders).append('\n') // the extra blank line is required
            append(signedHeaders).append('\n')
            append(payloadHash)
        }

        val stringToSign = buildString {
            append(ALGORITHM).append('\n')
            append(time.amzDate).append('\n')
            append(scope).append('\n')
            append(sha256(canonicalRequest.encodeToByteArray()).toHexLower())
        }

        val key = signingKey(credentials, time.dateStamp, config.region, config.service)
        val signature = hmacSha256(key, stringToSign.encodeToByteArray()).toHexLower()

        val outHeaders = ArrayList<Pair<String, String>>()
        val outQuery = ArrayList(queryToSign)

        if (presigning) {
            // Appended after signing, and never canonicalized.
            outQuery += "X-Amz-Signature" to signature
            if (sessionToken != null && config.omitSessionToken) {
                outQuery += "X-Amz-Security-Token" to sessionToken
            }
        } else {
            outHeaders += "Host" to request.host
            for ((name, value) in request.headers) {
                if (name.lowercase() == "host") continue
                outHeaders += name to value
            }
            outHeaders += "X-Amz-Date" to time.amzDate
            if (config.signedBodyHeader == SignedBodyHeader.X_AMZ_CONTENT_SHA256) {
                outHeaders += "X-Amz-Content-Sha256" to payloadHash
            }
            if (sessionToken != null) outHeaders += "X-Amz-Security-Token" to sessionToken
            outHeaders += "Authorization" to
                "$ALGORITHM Credential=$credentialValue, " +
                "SignedHeaders=$signedHeaders, Signature=$signature"
        }

        return SignedRequest(
            headers = outHeaders,
            queryParameters = outQuery,
            signedHeaderNames = signedHeaderNames,
            canonicalRequest = canonicalRequest,
            stringToSign = stringToSign,
            signature = signature,
        )
    }
}
