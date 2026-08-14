package com.steamstreet.awskt.s3

import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsEndpoint
import com.steamstreet.awskt.core.AwsHttpResponse
import com.steamstreet.awskt.core.AwsHttpTimeouts
import com.steamstreet.awskt.core.AwsProtocol
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.awsHttpClient
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.awskt.core.resolveRegion
import com.steamstreet.awskt.signing.PayloadHash
import com.steamstreet.awskt.signing.SignedBodyHeader
import com.steamstreet.awskt.signing.sigV4UriEncode
import io.ktor.client.HttpClient
import io.ktor.http.URLProtocol
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** S3's REST-XML dialect. */
public val S3_PROTOCOL: AwsProtocol = AwsProtocol.restXml(endpointPrefix = "s3")

/**
 * An S3 client.
 *
 * ### Object size is bounded by memory, deliberately
 *
 * Every operation here materializes the whole body as a `ByteArray`. That is the right trade for
 * the Lambda-shaped workloads this library targets, but it is a real ceiling and it is stated
 * rather than discovered:
 *
 * | Lambda memory | Practical object size |
 * |---------------|-----------------------|
 * | 128 MB        | ~30 MB                |
 * | 256 MB        | ~70 MB                |
 * | 512 MB        | ~150 MB               |
 * | 1024 MB       | ~350 MB               |
 *
 * `S3Config.maxBufferedDownloadBytes` and `maxBufferedUploadBytes` enforce the ceiling as a typed
 * [S3PayloadTooLargeException] instead of an OOM kill — which in Lambda produces no stack trace, no
 * typed exception and no CloudWatch error entry, only a truncated invocation.
 *
 * A `ByteReadChannel` overload is a purely **additive** v2 addition; nothing in this API forecloses it.
 *
 * ### Extending it
 *
 * As with `DynamoDb` (Decision 18), [client] is public and the four operations have no privileged
 * access to it.
 */
public interface S3 : AutoCloseable {
    /** The signed transport. Public because it is the extension seam. */
    public val client: AwsServiceClient

    public suspend fun getObject(request: GetObjectRequest): GetObjectResponse
    public suspend fun putObject(request: PutObjectRequest): PutObjectResponse
    public suspend fun headObject(request: HeadObjectRequest): HeadObjectResponse
    public suspend fun deleteObject(request: DeleteObjectRequest): DeleteObjectResponse
}

/** Configuration for [S3]. */
public class S3Config {
    public var region: String? = null
    public var endpointUrl: String? = null
    public var credentialsProvider: AwsCredentialsProvider? = null

    /**
     * A client to send on, instead of one built here.
     *
     * Supplying one **bypasses [caInfo] and [httpTimeouts]**: those are arguments to the client this
     * factory would have built, and a client the caller already owns is configured by the caller. A
     * caller-supplied client with no `HttpTimeout` plugin has no attempt bound at all, which for S3
     * is the difference between a slow download and a hung Lambda.
     */
    public var httpClient: HttpClient? = null
    public var retryConfig: RetryConfig = RetryConfig(maxAttempts = 3)
    public var caInfo: String? = null

    /**
     * Per-attempt time limits for the client this factory builds. Ignored when [httpClient] is set.
     *
     * **This is the module where the default request timeout can bite.** It is a whole-attempt
     * budget covering the body transfer, and [maxBufferedDownloadBytes] permits 64 MB by default —
     * which inside the default 30 s needs a sustained ~2.2 MB/s. In-Region that is comfortable;
     * over the public internet, or with the ceiling raised, it is not. Raise the two together, or a
     * download that would have completed is turned into a timeout that is then retried from zero.
     */
    public var httpTimeouts: AwsHttpTimeouts = AwsHttpTimeouts()

    /** Every local S3 implementation is path-style. Set with [endpointUrl]. */
    public var forcePathStyle: Boolean = false

    /** Loopback only. See [InsecureEndpointException]. */
    public var allowInsecureEndpoint: Boolean = false

    /**
     * The ceiling on a **downloaded** body. Default 64 MB.
     *
     * Split from the upload ceiling on purpose. The download is the one whose size the caller does
     * *not* control — an attacker or a colleague can put a 5 GB object where a 5 KB one was
     * expected — so a single shared setting would keep re-introducing the asymmetry the split
     * exists to prevent.
     */
    public var maxBufferedDownloadBytes: Long = 64L * 1024 * 1024

    /** The ceiling on an **uploaded** body. Default 64 MB. */
    public var maxBufferedUploadBytes: Long = 64L * 1024 * 1024

    // Injected by tests so the retry loop is deterministic and does not really sleep. Internal:
    // these are not a supported way to configure a client.
    internal var clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    internal var random: () -> Double = { kotlin.random.Random.nextDouble() }
    internal var sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
}

/** Builds an S3 client. */
public fun S3(configure: S3Config.() -> Unit = {}): S3 {
    val config = S3Config().apply(configure)
    val region = resolveRegion(config.region)
    val httpClient = config.httpClient ?: awsHttpClient(config.caInfo, config.httpTimeouts)
    return DefaultS3(
        region = region,
        config = config,
        httpClient = httpClient,
        ownsHttpClient = config.httpClient == null,
    )
}

@OptIn(ExperimentalAtomicApi::class)
internal class DefaultS3(
    private val region: String,
    private val config: S3Config,
    private val httpClient: HttpClient,
    private val ownsHttpClient: Boolean,
) : S3 {

    /**
     * S3 addresses the bucket in the *authority*, so the endpoint — and therefore the signed host —
     * differs per bucket. One `AwsServiceClient` per bucket is built on demand and reused.
     *
     * An immutable map behind an atomic reference rather than a `mutableMapOf`. One `S3` instance is
     * shared across concurrent coroutines by design, and `Dispatchers.Default` is multi-threaded on
     * the JVM and on Kotlin/Native alike — so two handlers touching two buckets at once were
     * mutating one plain `LinkedHashMap` from two threads, which corrupts the map itself rather
     * than merely losing an entry. Reads are a single volatile load and take no lock.
     */
    private val clientsByBucket = AtomicReference<Map<String, AwsServiceClient>>(emptyMap())

    override val client: AwsServiceClient
        get() = clientsByBucket.load().values.firstOrNull() ?: clientFor("")

    /** The buckets currently cached. Internal so a concurrency test can assert on the cache. */
    internal val cachedBuckets: Set<String> get() = clientsByBucket.load().keys

    // Internal, not private, so a concurrency test can hammer it directly. Driving it through
    // `getObject` instead would put an HTTP round trip between the cache read and the cache write,
    // which serialises the callers and hides the very interleaving the test exists to catch.
    internal fun clientFor(bucket: String): AwsServiceClient {
        clientsByBucket.load()[bucket]?.let { return it }
        val built = buildClientFor(bucket)

        // Publish, unless someone else got there first — in which case theirs is the one everybody
        // uses and `built` is dropped. Losing this race costs a discarded object and nothing else:
        // an AwsServiceClient owns no connection (the HttpClient is shared and closed by `close`)
        // and its credentials provider only caches. What must not happen is two callers *keeping*
        // different clients for one bucket, because each carries its own retry token bucket and the
        // circuit breaker would then be measuring half the traffic.
        while (true) {
            val current = clientsByBucket.load()
            current[bucket]?.let { return it }
            if (clientsByBucket.compareAndSet(current, current + (bucket to built))) return built
        }
    }

    private fun buildClientFor(bucket: String): AwsServiceClient {
        val endpoint = resolveS3Endpoint(
            bucket = bucket.ifBlank { "placeholder-bucket" },
            region = region,
            endpointOverride = config.endpointUrl,
            forcePathStyle = config.forcePathStyle,
            allowInsecureEndpoint = config.allowInsecureEndpoint,
        )
        return AwsServiceClient(
            httpClient = httpClient,
            credentialsProvider = config.credentialsProvider ?: defaultCredentialsProvider(),
            endpoint = AwsEndpoint(
                url = endpoint.origin,
                authority = endpoint.authority,
                protocol = if (endpoint.scheme == "https") URLProtocol.HTTPS else URLProtocol.HTTP,
                host = endpoint.authority.substringBefore(':'),
                port = endpoint.authority.substringAfter(':', "").toIntOrNull()
                    ?: if (endpoint.scheme == "https") 443 else 80,
            ),
            region = region,
            protocol = S3_PROTOCOL,
            retryConfig = config.retryConfig,
            clock = config.clock,
            random = config.random,
            sleep = config.sleep,
        )
    }

    /** The base path (`""` or `/bucket`) for a bucket under the resolved addressing style. */
    private fun basePathFor(bucket: String): String = resolveS3Endpoint(
        bucket = bucket,
        region = region,
        endpointOverride = config.endpointUrl,
        forcePathStyle = config.forcePathStyle,
        allowInsecureEndpoint = config.allowInsecureEndpoint,
    ).basePath

    /**
     * Encoded **once**, with slashes preserved and no normalization. The same string is signed and
     * sent — `callRaw` documents that it never re-encodes the path.
     */
    private fun pathFor(bucket: String, key: String): String =
        basePathFor(bucket) + "/" + sigV4UriEncode(key, encodeSlash = false)

    private suspend fun call(
        bucket: String,
        method: String,
        key: String,
        query: List<Pair<String, String>>,
        headers: List<Pair<String, String>>,
        body: ByteArray = ByteArray(0),
        operation: String,
        safety: OperationSafety,
        inspectBeforeBody: ((status: Int, headers: Map<String, String>) -> Unit)? = null,
        validateBody: ((response: AwsHttpResponse) -> Unit)? = null,
    ): AwsHttpResponse = mapS3Errors {
        clientFor(bucket).callRaw(
            inspectBeforeBody = inspectBeforeBody,
            validateBody = validateBody,
            method = method,
            path = pathFor(bucket, key),
            query = query,
            headers = headers,
            body = body,
            operation = operation,
            safety = safety,
            // ALWAYS the real computed hash. There is deliberately no size-triggered switch to
            // UNSIGNED-PAYLOAD: that would fork the request's *security class* on payload size, so
            // a bucket policy conditioning on s3:x-amz-content-sha256 would pass small objects and
            // 403 large ones — a production failure that scales with payload size and that no
            // fixture, MockEngine test or LocalStack run can reproduce.
            payloadHash = if (body.isEmpty()) PayloadHash.EmptyBody else PayloadHash.Compute,
            signedBodyHeader = SignedBodyHeader.X_AMZ_CONTENT_SHA256,
            doubleUriEncode = false,
            normalizeUriPath = false,
        )
    }

    override suspend fun getObject(request: GetObjectRequest): GetObjectResponse {
        val query = buildList {
            request.versionId?.let { add("versionId" to it) }
            request.responseCacheControl?.let { add("response-cache-control" to it) }
            request.responseContentDisposition?.let { add("response-content-disposition" to it) }
            request.responseContentEncoding?.let { add("response-content-encoding" to it) }
            request.responseContentLanguage?.let { add("response-content-language" to it) }
            request.responseContentType?.let { add("response-content-type" to it) }
            add("x-id" to "GetObject")
        }
        val headers = buildList {
            request.range?.let { add("Range" to it) }
            request.ifMatch?.let { add("If-Match" to it) }
            request.ifNoneMatch?.let { add("If-None-Match" to it) }
            request.ifModifiedSince?.let { add("If-Modified-Since" to it) }
            request.ifUnmodifiedSince?.let { add("If-Unmodified-Since" to it) }
        }

        val response = call(
            request.bucket, "GET", request.key, query, headers,
            operation = "GetObject", safety = OperationSafety.IDEMPOTENT,
            // Runs on **every attempt**, and — critically — *before* `aws-core` reads the body.
            // Performing this check on the returned response instead would allocate the very array
            // it exists to refuse, which in Lambda is an OOM kill: no stack trace, no typed
            // exception, no CloudWatch error entry, only a truncated invocation.
            //
            // A refusal here is never retried: the object really is that big, and it will still be
            // that big on the next attempt. `callRaw` guarantees that — see `validateBody` below,
            // whose retry semantics are deliberately the opposite.
            inspectBeforeBody = { status, responseHeaders ->
                if (status in 200..299) {
                    val declared = responseHeaders.header("content-length")?.toLongOrNull()
                    if (declared != null && declared > config.maxBufferedDownloadBytes) {
                        throw S3PayloadTooLargeException(
                            "s3://${request.bucket}/${request.key} is $declared bytes, over the " +
                                "${config.maxBufferedDownloadBytes}-byte maxBufferedDownloadBytes " +
                                "ceiling. Raise the limit, or fetch it in pieces with " +
                                "GetObjectRequest.range.",
                        )
                    }
                }
            },
            // Checked *inside* the retry loop, where a replay can still fix it. A truncated body is
            // a dead connection, not a verdict about the object — which is the one case in this
            // method where fetching it again is exactly the right answer.
            //
            // Skipped for a body that is over the ceiling, so the two guards cannot fight: that
            // refusal is a policy decision made below, and raising it as truncation here would put
            // it on the retryable side of the loop, which is the bug this arrangement fixes.
            validateBody = { validated ->
                if (validated.body.size.toLong() <= config.maxBufferedDownloadBytes) {
                    checkDownloadComplete(
                        declared = validated.headers.header("content-length")?.toLongOrNull(),
                        actual = validated.body.size.toLong(),
                        requestId = validated.headers.header("x-amz-request-id"),
                        extendedRequestId = validated.headers.header("x-amz-id-2"),
                    )
                }
            },
        )

        val declared = response.headers.header("content-length")?.toLongOrNull()

        // Separately bound what actually arrived, so an absent or understated Content-Length
        // cannot walk past the ceiling.
        if (response.body.size.toLong() > config.maxBufferedDownloadBytes) {
            throw S3PayloadTooLargeException(
                "s3://${request.bucket}/${request.key} returned ${response.body.size} bytes, over " +
                    "the ${config.maxBufferedDownloadBytes}-byte maxBufferedDownloadBytes ceiling " +
                    "(the declared Content-Length was $declared). Use GetObjectRequest.range.",
            )
        }

        return GetObjectResponse(
            body = response.body,
            contentLength = declared ?: response.body.size.toLong(),
            contentType = response.headers.header("content-type"),
            eTag = response.headers.header("etag")?.trim('"'),
            lastModified = response.headers.header("last-modified"),
            versionId = response.headers.header("x-amz-version-id"),
            contentRange = response.headers.header("content-range"),
            cacheControl = response.headers.header("cache-control"),
            contentEncoding = response.headers.header("content-encoding"),
            contentDisposition = response.headers.header("content-disposition"),
            metadata = response.headers.userMetadata(),
        )
    }

    override suspend fun putObject(request: PutObjectRequest): PutObjectResponse {
        if (request.body.size.toLong() > config.maxBufferedUploadBytes) {
            throw S3PayloadTooLargeException(
                "Refusing to upload ${request.body.size} bytes to s3://${request.bucket}/${request.key}: " +
                    "over the ${config.maxBufferedUploadBytes}-byte maxBufferedUploadBytes ceiling.",
            )
        }

        val headers = buildList {
            request.contentType?.let { add("Content-Type" to it) }
            request.cacheControl?.let { add("Cache-Control" to it) }
            request.contentEncoding?.let { add("Content-Encoding" to it) }
            request.contentDisposition?.let { add("Content-Disposition" to it) }
            request.ifNoneMatch?.let { add("If-None-Match" to it) }
            // AWS tells REST callers not to send x-amz-sdk-checksum-algorithm, and we do not.
            add("accept-encoding" to "identity")
            for ((name, value) in request.metadata) add("x-amz-meta-$name" to value)
        }

        val response = call(
            request.bucket, "PUT", request.key, listOf("x-id" to "PutObject"), headers, request.body,
            operation = "PutObject",
            // A conditional create must not be replayed on an ambiguous failure: the retry sees its
            // own successful write and fails with 412, reporting a conflict that never happened.
            safety = if (request.ifNoneMatch != null) {
                OperationSafety.NOT_IDEMPOTENT
            } else {
                OperationSafety.IDEMPOTENT
            },
        )

        return PutObjectResponse(
            eTag = response.headers.header("etag")?.trim('"'),
            versionId = response.headers.header("x-amz-version-id"),
        )
    }

    override suspend fun headObject(request: HeadObjectRequest): HeadObjectResponse {
        // No `x-id` on HEAD, matching the AWS SDKs.
        val query = buildList { request.versionId?.let { add("versionId" to it) } }
        val response = call(
            request.bucket, "HEAD", request.key, query, emptyList(),
            operation = "HeadObject", safety = OperationSafety.IDEMPOTENT,
        )
        return HeadObjectResponse(
            contentLength = response.headers.header("content-length")?.toLongOrNull() ?: 0L,
            contentType = response.headers.header("content-type"),
            eTag = response.headers.header("etag")?.trim('"'),
            lastModified = response.headers.header("last-modified"),
            versionId = response.headers.header("x-amz-version-id"),
            metadata = response.headers.userMetadata(),
        )
    }

    override suspend fun deleteObject(request: DeleteObjectRequest): DeleteObjectResponse {
        val query = buildList {
            request.versionId?.let { add("versionId" to it) }
            add("x-id" to "DeleteObject")
        }
        val response = call(
            request.bucket, "DELETE", request.key, query, emptyList(),
            operation = "DeleteObject", safety = OperationSafety.IDEMPOTENT,
        )
        return DeleteObjectResponse(
            versionId = response.headers.header("x-amz-version-id"),
            deleteMarker = response.headers.header("x-amz-delete-marker")?.toBoolean() ?: false,
        )
    }

    override fun close() {
        if (ownsHttpClient) httpClient.close()
    }
}

/**
 * A short body with an honest `Content-Length` is a truncated download, not a small object.
 *
 * Called from `getObject`'s `validateBody`, which runs **inside** `callRaw`'s retry loop, so a
 * truncation is replayed rather than reported. That placement is load-bearing: the same check made
 * on the response `callRaw` returned would sit past the last point a retry is possible, and a
 * failure this library documents as retryable would in fact be permanent.
 *
 * **That is defence in depth rather than redundancy.** Ktor's client core does compare
 * `Content-Length` against a `ByteArray` body and raise before this check is reached — but whether
 * a *real* engine notices a stream that died mid-body is engine-dependent, and this library runs
 * CIO on the JVM and Curl on native, whose response-body handling is the reason this project pinned
 * a newer Ktor in the first place. On `linuxArm64`, the one target whose tests cannot run locally,
 * "something upstream will throw" is an assumption rather than an observation.
 */
internal fun checkDownloadComplete(
    declared: Long?,
    actual: Long,
    requestId: String?,
    extendedRequestId: String?,
) {
    if (declared != null && declared != actual) {
        throw S3IncompleteDownloadException(declared, actual, requestId, extendedRequestId)
    }
}

/** Header lookup that does not care about case, because HTTP does not. */
internal fun Map<String, String>.header(name: String): String? =
    this[name] ?: entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

/** `x-amz-meta-*` with the prefix stripped and the name lowercased. */
internal fun Map<String, String>.userMetadata(): Map<String, String> =
    entries.filter { it.key.startsWith("x-amz-meta-", ignoreCase = true) }
        .associate { it.key.substring("x-amz-meta-".length).lowercase() to it.value }
