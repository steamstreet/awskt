package com.steamstreet.awskt.s3

import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.awsEnv
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.awskt.core.parseAwsCredentialExpirationOrNull
import com.steamstreet.awskt.core.resolveRegion
import com.steamstreet.awskt.signing.PayloadHash
import com.steamstreet.awskt.signing.SigV4
import com.steamstreet.awskt.signing.SigV4Config
import com.steamstreet.awskt.signing.SignatureLocation
import com.steamstreet.awskt.signing.SignedBodyHeader
import com.steamstreet.awskt.signing.sigV4UriEncode
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * A request to presign. Every field that changes what the URL authorizes is explicit.
 *
 * @param method never inferred. A URL signed for GET is rejected for PUT, and guessing the method
 *   from surrounding context is exactly how a read URL becomes a write URL.
 * @param expiresIn required — there is no default and no zero-argument overload. Capped at seven
 *   days, which is the longest lifetime AWS will honour because the signing key is date-scoped.
 * @param contentType signed as a `content-type` header when set. Signing it trades flexibility for
 *   enforcement: the uploader must then reproduce it **byte for byte**, and a browser `fetch` with
 *   a `Blob` commonly appends or normalises a charset and earns a 403 naming a header rather than a
 *   rule. Leave it null unless you control the uploader.
 * @param signedHeaders additional headers the fetcher will send, declared here so they are covered
 *   by the signature. There is no free-form bag applied afterwards: an `x-amz-*` header sent but
 *   not signed is a hard 403. One non-obvious non-`x-amz-` trap — **if `Range` is signed and the
 *   fetcher also sends `If-Range`, `If-Range` must be signed too.**
 */
public class PresignRequest(
    public val bucket: String,
    public val key: String,
    public val method: PresignMethod,
    public val expiresIn: Duration,
    public val versionId: String? = null,
    public val contentType: String? = null,
    /**
     * Signed response-header overrides. These are query parameters in S3's model, so they are
     * canonicalized and covered by the signature — which means **a caller cannot append them to
     * the returned URL afterwards**; doing so yields `SignatureDoesNotMatch`. They are first-class
     * here because a browser download with a controlled filename and content type is the single
     * most common presigned-GET use case, and without them it is impossible rather than merely
     * inconvenient.
     */
    public val responseContentType: String? = null,
    public val responseContentDisposition: String? = null,
    public val responseCacheControl: String? = null,
    public val responseContentEncoding: String? = null,
    public val responseContentLanguage: String? = null,
    public val responseExpires: String? = null,
    public val signedHeaders: List<Pair<String, String>> = emptyList(),
) {
    /** Reveals nothing that is not already in the caller's hands. */
    override fun toString(): String = "PresignRequest($method s3://$bucket/$key, expiresIn=$expiresIn)"
}

/**
 * Configuration for [S3Presigner].
 *
 * **Read once, at construction.** [S3Presigner] snapshots every value it needs, so mutating this
 * object afterwards has no effect on a presigner already built from it — and cannot race with a
 * presign in flight. [clockSkewOffsetMillis] is the deliberate exception in spirit though not in
 * mechanism: the *function reference* is snapshotted, so replacing the property has no effect while
 * the function it held keeps reporting live skew, which is exactly its purpose.
 */
public class S3PresignerConfig {
    public var region: String? = null
    public var endpointUrl: String? = null
    public var credentialsProvider: AwsCredentialsProvider? = null
    public var forcePathStyle: Boolean = false

    /** Permit a plaintext endpoint override. Loopback only, for LocalStack and MinIO. */
    public var allowInsecureEndpoint: Boolean = false

    /**
     * Permit an `expiresIn` longer than one hour when signing with a session token whose expiry is
     * unknown.
     *
     * Off by default, and the default is the point. In Lambda the credential expiry is *always*
     * unknown, so a 7-day URL minted there is really a URL that dies whenever the execution role's
     * session does — typically within the hour. Capping it makes the hazard something you opt into
     * at the call site rather than something you discover from a link that died mid-life.
     */
    public var allowPresignBeyondUnknownSessionExpiry: Boolean = false

    /**
     * Signing-clock offset in milliseconds, normally supplied by a live [com.steamstreet.awskt.core.AwsServiceClient]
     * that has already learned the server's clock skew from a response.
     *
     * Presigning performs no round trip, so it gets no skew signal of its own — and AWS names clock
     * drift as the first cause of presigned-URL `SignatureDoesNotMatch`. Wire this to a client that
     * has made at least one call and the correction carries over.
     */
    public var clockSkewOffsetMillis: () -> Long = { 0L }

    internal var clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }
    internal var getEnv: (String) -> String? = ::awsEnv
}

/** The cap applied when a session token is present but its expiry is unknown. */
private val UNKNOWN_SESSION_CAP = 1.hours

/**
 * Presigns S3 URLs.
 *
 * ### It performs no network I/O
 *
 * Beyond resolving credentials, presigning is pure computation. That is a security property and not
 * merely a performance one: there is no transport, no proxy and no log sink between the signer and
 * the returned string, so the only way a presigned URL leaks is if the caller logs it.
 *
 * ### `presignPutObject` is not the sibling of `presignGetObject`
 *
 * Because presigning must use `UNSIGNED-PAYLOAD` — at signing time the body does not exist, so
 * there is nothing to hash — a presigned PUT URL accepts **any body, of any content, up to S3's
 * 5 GiB single-PUT limit, from anyone holding the link**. The URL constrains bucket, key, method,
 * expiry and the signed headers, and nothing else. Content or size constraints have to come from a
 * bucket policy (`s3:content-length-range` via a POST policy) or a post-upload check; they cannot
 * come from the URL.
 */
public class S3Presigner(configure: S3PresignerConfig.() -> Unit = {}) {
    private val region: String
    private val endpointUrl: String?
    private val forcePathStyle: Boolean
    private val allowInsecureEndpoint: Boolean
    private val allowPresignBeyondUnknownSessionExpiry: Boolean

    /**
     * The **function**, not a value read from it. Snapshotting the reference is the correct move
     * here and not an oversight: this hook exists to be wired to a live `AwsServiceClient` that
     * keeps learning the server's clock skew from responses, so the presigner must keep calling it
     * on every presign. What is frozen is *which* function is consulted, which is configuration;
     * what stays live is the number it reports, which is the point.
     */
    private val clockSkewOffsetMillis: () -> Long
    private val clock: () -> Long
    private val getEnv: (String) -> String?
    private val credentialsProvider: AwsCredentialsProvider

    init {
        // Every read of the builder happens here, and none after. `S3PresignerConfig` is a bag of
        // `var`s the caller still holds a reference to; re-reading it per presign would let a
        // mutation change the addressing style or the expiry policy of a URL being signed
        // concurrently, unsynchronised.
        val config = S3PresignerConfig().apply(configure)
        region = resolveRegion(config.region)
        endpointUrl = config.endpointUrl
        forcePathStyle = config.forcePathStyle
        allowInsecureEndpoint = config.allowInsecureEndpoint
        allowPresignBeyondUnknownSessionExpiry = config.allowPresignBeyondUnknownSessionExpiry
        clockSkewOffsetMillis = config.clockSkewOffsetMillis
        clock = config.clock
        getEnv = config.getEnv
        credentialsProvider = config.credentialsProvider ?: defaultCredentialsProvider()
    }

    /** Presigns a GET. */
    public suspend fun presignGetObject(
        bucket: String,
        key: String,
        expiresIn: Duration,
        responseContentType: String? = null,
        responseContentDisposition: String? = null,
    ): PresignedUrl = presign(
        PresignRequest(
            bucket = bucket,
            key = key,
            method = PresignMethod.GET,
            expiresIn = expiresIn,
            responseContentType = responseContentType,
            responseContentDisposition = responseContentDisposition,
        ),
    )

    /**
     * Presigns a PUT. Read the class KDoc before using this — the resulting URL is a write
     * capability with no content or size constraint.
     */
    public suspend fun presignPutObject(
        bucket: String,
        key: String,
        expiresIn: Duration,
        contentType: String? = null,
    ): PresignedUrl = presign(
        PresignRequest(
            bucket = bucket,
            key = key,
            method = PresignMethod.PUT,
            expiresIn = expiresIn,
            contentType = contentType,
        ),
    )

    public suspend fun presign(request: PresignRequest): PresignedUrl {
        require(request.expiresIn > Duration.ZERO) {
            "expiresIn must be positive, was ${request.expiresIn}"
        }
        require(request.expiresIn <= MAX_PRESIGN_EXPIRY) {
            "expiresIn must be at most 7 days (604800 seconds), was ${request.expiresIn}. " +
                "The SigV4 signing key is date-scoped and cannot be valid for longer."
        }

        val credentials = credentialsProvider.resolve()
        val now = clock() + clockSkewOffsetMillis()

        // A bonus, not the fix: ECS and `credential_process` publish this, Lambda does not. Parsed
        // by aws-core's one parser for the variable, so a presign and a credential resolution can
        // never disagree about when the same string says the credential dies.
        val credentialExpiry = credentials.expiresAtEpochMillis
            ?: getEnv("AWS_CREDENTIAL_EXPIRATION")?.let(::parseAwsCredentialExpirationOrNull)

        val sessionExpiryUnknown = credentials.sessionToken != null && credentialExpiry == null
        if (sessionExpiryUnknown &&
            request.expiresIn > UNKNOWN_SESSION_CAP &&
            !allowPresignBeyondUnknownSessionExpiry
        ) {
            throw PresignExpiryException(
                "Refusing to presign for ${request.expiresIn} with session credentials whose " +
                    "expiry is unknown. The URL would stop working when the role session ends — " +
                    "typically within the hour — not when X-Amz-Expires says. Shorten expiresIn to " +
                    "at most $UNKNOWN_SESSION_CAP, or set " +
                    "allowPresignBeyondUnknownSessionExpiry = true to accept that.",
            )
        }

        val requestedExpiry = now + request.expiresIn.inWholeMilliseconds
        val expiry = when {
            credentialExpiry != null ->
                PresignExpiry.Known(minOf(requestedExpiry, credentialExpiry))

            sessionExpiryUnknown -> PresignExpiry.BoundedByUnknownSession(requestedExpiry)
            else -> PresignExpiry.Known(requestedExpiry)
        }

        val endpoint = resolveS3Endpoint(
            bucket = request.bucket,
            region = region,
            endpointOverride = endpointUrl,
            forcePathStyle = forcePathStyle,
            allowInsecureEndpoint = allowInsecureEndpoint,
            getEnv = getEnv,
        )

        // Encoded ONCE, with no normalization, and the *same string* builds both the URL and the
        // canonical request. That identity is the whole reason S3 signing works on keys containing
        // `..`, `//` and `%`.
        val encodedPath = endpoint.basePath + "/" + sigV4UriEncode(request.key, encodeSlash = false)

        val signed = SigV4.sign(
            request = com.steamstreet.awskt.signing.SigningRequest(
                method = request.method.httpMethod,
                path = encodedPath,
                host = endpoint.authority,
                queryParameters = queryParametersFor(request),
                headers = headersFor(request),
            ),
            credentials = credentials,
            config = SigV4Config(
                region = region,
                service = "s3",
                location = SignatureLocation.QUERY_STRING,
                expiresInSeconds = request.expiresIn.inWholeSeconds,
                payloadHash = PayloadHash.Unsigned,
                signedBodyHeader = SignedBodyHeader.NONE,
                doubleUriEncode = false,
                normalizeUriPath = false,
            ),
            signingInstantMillis = now,
        )

        return PresignedUrl(
            url = "${endpoint.origin}$encodedPath?${signed.encodedQueryString()}",
            method = request.method,
            signedHeaderNames = signed.signedHeaderNames,
            expiry = expiry,
        )
    }

    /**
     * Query parameters in **decoded** form; the signer encodes them.
     *
     * `x-id` is emitted for GET, PUT and DELETE but **not for HEAD**, matching the AWS SDKs. It
     * lives in the outbound query, therefore in the canonical query, therefore under the signature
     * — which is precisely what lets the differential harness compare our URL to the SDK's
     * including `X-Amz-Signature`.
     */
    private fun queryParametersFor(request: PresignRequest): List<Pair<String, String>> = buildList {
        request.versionId?.let { add("versionId" to it) }
        request.responseCacheControl?.let { add("response-cache-control" to it) }
        request.responseContentDisposition?.let { add("response-content-disposition" to it) }
        request.responseContentEncoding?.let { add("response-content-encoding" to it) }
        request.responseContentLanguage?.let { add("response-content-language" to it) }
        request.responseContentType?.let { add("response-content-type" to it) }
        request.responseExpires?.let { add("response-expires" to it) }
        when (request.method) {
            PresignMethod.GET -> add("x-id" to "GetObject")
            PresignMethod.PUT -> add("x-id" to "PutObject")
            PresignMethod.DELETE -> add("x-id" to "DeleteObject")
            PresignMethod.HEAD -> Unit
        }
    }

    private fun headersFor(request: PresignRequest): List<Pair<String, String>> = buildList {
        request.contentType?.let { add("content-type" to it) }
        addAll(request.signedHeaders)
    }

    public companion object {
        /** Seven days — AWS will not honour longer, because the signing key is date-scoped. */
        public val MAX_PRESIGN_EXPIRY: Duration = 604_800.seconds
    }
}

/** A presign was refused because the URL's advertised lifetime would have been a lie. */
public class PresignExpiryException(message: String) : Exception(message)
