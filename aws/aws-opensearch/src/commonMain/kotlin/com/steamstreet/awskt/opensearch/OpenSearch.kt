package com.steamstreet.awskt.opensearch

import com.steamstreet.awskt.core.AwsCallObserver
import com.steamstreet.awskt.core.AwsConfigurationException
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsHttpTimeouts
import com.steamstreet.awskt.core.AwsProtocol
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.awsHttpClient
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.awskt.core.awsEnv
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.core.resolveRegion
import com.steamstreet.awskt.signing.SignedBodyHeader
import com.steamstreet.awskt.signing.sigV4UriEncode
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The JSON parser used for OpenSearch responses. Lenient about fields this library does not model. */
private val openSearchJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** `application/json` — every request here but `_bulk`. */
internal const val JSON_CONTENT_TYPE: String = "application/json"

/**
 * `application/x-ndjson`, which `_bulk` **requires**. `application/json` is a 406, not a warning.
 */
internal const val NDJSON_CONTENT_TYPE: String = "application/x-ndjson"

/**
 * A managed OpenSearch domain's wire dialect: SigV4 under the `es` signing name, REST-addressed.
 *
 * ### Why `contentType` is null here when every request still sends one
 *
 * `AwsProtocol` contributes its content type to *every* request the client signs, and `_bulk` needs
 * a different one — `application/x-ndjson`, which OpenSearch enforces with a 406. A per-call
 * override cannot work against a protocol that already supplies one: `AwsServiceClient` appends the
 * caller's headers *after* the protocol's, so both would be signed as one grouped `content-type`
 * value and only one would be sent, and the request would fail as `SignatureDoesNotMatch` — a
 * failure that names nothing about content types.
 *
 * So the header moves one layer out, exactly as `AwsProtocol.restXml` does it for S3. On the wire
 * nothing changes: [OpenSearch] sends `application/json` on every request that carries a body and
 * `application/x-ndjson` on `_bulk`. A request with no body sends no content type at all, which is
 * both correct HTTP and what a `GET /{index}/_doc/{id}` should look like.
 *
 * ### `targetPrefix` is null, and that is the REST-addressing part
 *
 * OpenSearch has no `X-Amz-Target`: the operation is the method and the path (`POST
 * /{index}/_search`). `AwsServiceClient` only emits the header when a prefix is present, so a null
 * here means no target header at all — the same arrangement as `AwsProtocol.restJson1`.
 *
 * ### Ordinary payload signing
 *
 * None of S3's special-casing applies. `doubleUriEncode` and `normalizeUriPath` stay at their
 * defaults, which is what a domain endpoint expects; the S3 flags exist for object keys that may
 * contain `..` and are wrong everywhere else.
 *
 * @param endpointPrefix `es` for a managed domain, `aoss` for Serverless. It does **not** derive the
 *   endpoint here — an OpenSearch endpoint is a domain host, not `<prefix>.<region>.amazonaws.com` —
 *   and is used only to name the `AWS_ENDPOINT_URL_<PREFIX>` environment variable.
 * @param signingName the SigV4 service name. `es` for a managed domain; see
 *   [OpenSearchConfig.serverless] before reaching for `aoss`.
 */
public fun AwsProtocol.Companion.openSearch(
    endpointPrefix: String = "es",
    signingName: String = endpointPrefix,
): AwsProtocol = AwsProtocol(endpointPrefix, signingName, null, null, OpenSearchErrorParser)

/** A managed OpenSearch domain — `es`, ordinary payload signing. See [AwsProtocol.openSearch]. */
public val OPENSEARCH_PROTOCOL: AwsProtocol = AwsProtocol.openSearch()

/**
 * OpenSearch Serverless — `aoss`. See [OpenSearchConfig.serverless] for what else it needs and for
 * what has and has not been verified.
 */
public val OPENSEARCH_SERVERLESS_PROTOCOL: AwsProtocol = AwsProtocol.openSearch("aoss")

/**
 * A signed transport to an OpenSearch domain. **Not a client for the OpenSearch API.**
 *
 * The whole point of the module is what it does *not* do. Callers already build their queries with
 * `buildJsonObject` and decode their answers with kotlinx.serialization; what they cannot do
 * without a JVM is sign the request. So this exposes the shapes a query path actually issues —
 * [search], [getDocument], [bulk] — as raw JSON in and raw JSON out, and models nothing about
 * either direction.
 *
 * ```kotlin
 * val openSearch = OpenSearch { endpointUrl = Env["SEARCH_DOMAIN_ENDPOINT"] }
 *
 * val hits = openSearch.search("venues", buildJsonObject {
 *     put("size", 20)
 *     putJsonObject("query") { putJsonObject("match") { put("name", term) } }
 * })["hits"]!!.jsonObject["hits"]!!.jsonArray
 * ```
 *
 * What it does own is the three things a caller building this by hand gets wrong: the endpoint
 * (a domain host, not a regional one), path encoding (an index or document id interpolated raw is a
 * signature mismatch at best), and retry safety — see [bulk].
 *
 * ### Index administration is out of scope
 *
 * No `createIndex`, no mappings, no aliases, no `listIndexes`. Not an oversight: index
 * administration is provisioning, it runs once per deploy rather than once per request, and it is
 * the part of the OpenSearch API with the largest and most version-dependent schema. A JVM indexer
 * with the official client remains the right place for it. Anything absent is still reachable
 * through [request] or through [client].
 *
 * ### Extending it
 *
 * [client] is public and no operation below has privileged access to it. An operation this module
 * does not ship is an extension function:
 *
 * ```kotlin
 * suspend fun OpenSearch.count(index: String, query: JsonObject): Long =
 *     request("POST", "/${sigV4UriEncode(index)}/_count", body = query,
 *         safety = OperationSafety.IDEMPOTENT, operation = "Count")["count"]!!.jsonPrimitive.long
 * ```
 *
 * **Encode interpolated path segments with `sigV4UriEncode`.** `callRaw` signs and sends the path
 * byte-for-byte as given.
 */
public interface OpenSearch : AutoCloseable {
    /** The signed transport. Public because it is the extension seam — see the interface KDoc. */
    public val client: AwsServiceClient

    /**
     * `POST /{index}/_search`, with [query] as the request body and the answer returned unparsed.
     *
     * `IDEMPOTENT`: a search applies nothing, so a replay after an ambiguous transport failure
     * costs a second query and cannot corrupt anything.
     *
     * @param index one index, or a multi-index expression — `venues,events`, `venues-*`, `_all`.
     *   Percent-encoded before it is signed, which does not break those forms: OpenSearch decodes
     *   the path before it resolves index names, so a signed `venues%2Cevents` still addresses two
     *   indexes. `LocalOpenSearchTest` proves that against a real engine rather than assuming it.
     * @param query the search body, verbatim. Nothing is added to it — no default `size`, no
     *   `track_total_hits`.
     * @param params query-string parameters, for the search options that are not body fields:
     *   `routing`, `preference`, `search_type`, `scroll`.
     */
    public suspend fun search(
        index: String,
        query: JsonObject,
        params: List<Pair<String, String>> = emptyList(),
    ): JsonObject

    /**
     * `GET /{index}/_doc/{id}` — one document by id, or **null when there is no such document**.
     *
     * `IDEMPOTENT`, for the same reason [search] is.
     *
     * ### Null and `index_not_found_exception` are different answers, and both are 404
     *
     * A missing *document* in an existing index is a 404 whose body is an ordinary document
     * (`{"_index":"venues","_id":"7","found":false}`) with no error envelope in it, so
     * [OpenSearchErrorParser] reads no code and this returns null. A missing *index* is a 404
     * carrying `index_not_found_exception`, and that throws — a query path that silently answers
     * "no such document" when its index has been deleted is a far worse failure than one that
     * reports it.
     *
     * @param id percent-encoded before signing, so an id that came from user input cannot inject a
     *   `/` and address a different endpoint.
     * @param params query-string parameters — `_source`, `_source_includes`, `routing`, `realtime`.
     */
    public suspend fun getDocument(
        index: String,
        id: String,
        params: List<Pair<String, String>> = emptyList(),
    ): JsonObject?

    /**
     * `POST /_bulk` — **[OperationSafety.NOT_IDEMPOTENT], and that is the reason this method exists
     * rather than being left to [request].**
     *
     * A bulk body is a list of writes. If the request reaches OpenSearch and the connection dies
     * before the response arrives, the transport cannot tell whether the writes landed, and a
     * replay applies every `index` action in the body a second time. For actions that carry an
     * explicit `_id` that is idempotent by construction; for the `index` and `create` actions that
     * let OpenSearch generate one, it is a duplicate document. So an ambiguous failure is surfaced,
     * not retried. `RetryConfig.retryAmbiguousWrites` remains the caller's opt-out.
     *
     * ### A 200 does not mean every action succeeded
     *
     * `_bulk` reports per-action failures **inside a successful response** — `"errors": true` at the
     * top level and a per-item `error` object below it — which never reaches the transport's error
     * path and is never retried by it. That includes per-document 429s, which is the shape a
     * throttled bulk usually takes. **Check `errors` on the returned object.** This method does not
     * check it for you: deciding what to do about a partial failure needs the body that produced
     * it, which is the caller's.
     *
     * @param body newline-delimited JSON, **terminated by a newline** — OpenSearch rejects a body
     *   that is not. Appended here if it is missing, because the resulting error names a parse
     *   position rather than the missing byte.
     * @param index a default index for actions that name none, addressing `POST /{index}/_bulk`.
     * @param params query-string parameters — `refresh`, `routing`, `pipeline`, `timeout`.
     */
    public suspend fun bulk(
        body: String,
        index: String? = null,
        params: List<Pair<String, String>> = emptyList(),
    ): JsonObject

    /**
     * Any request, signed and retried, answered as parsed JSON.
     *
     * The general form of the three above and the seam for everything this module does not ship.
     *
     * @param path **already percent-encoded**, and signed and sent byte-for-byte as given.
     *   Interpolate segments with `sigV4UriEncode`.
     * @param safety deliberately **has no default**. Every other `safety` parameter in this library
     *   defaults to [OperationSafety.IDEMPOTENT] because it sits on a typed operation whose author
     *   knew which it was; this one does not know what it is about to send, and a write that
     *   defaulted to idempotent would be silently replayable.
     */
    public suspend fun request(
        method: String,
        path: String,
        safety: OperationSafety,
        query: List<Pair<String, String>> = emptyList(),
        body: JsonObject? = null,
        operation: String? = null,
    ): JsonObject
}

/** Configuration for [OpenSearch]. */
public class OpenSearchConfig {
    /**
     * The domain endpoint. **Required** — there is no default worth guessing.
     *
     * A managed domain answers on a host of its own, `search-<name>-<hash>.<region>.es.amazonaws.com`,
     * which is what CDK's `Domain.domainEndpoint` returns. Accepted with or without a scheme;
     * `https` is assumed. Falls back to `AWS_ENDPOINT_URL_ES` (or `AWS_ENDPOINT_URL_AOSS` when
     * [serverless] is set) and then throws.
     *
     * It deliberately does **not** fall back to `es.<region>.amazonaws.com` the way every other
     * module in this library falls back to its regional endpoint. That host exists — it is the
     * OpenSearch *configuration* API, where `CreateDomain` and `DescribeDomain` live — so the
     * fallback would resolve, sign, connect, and answer a search with a 404 from a completely
     * different service.
     */
    public var endpointUrl: String? = null

    public var region: String? = null
    public var credentialsProvider: AwsCredentialsProvider? = null

    /**
     * Address **OpenSearch Serverless** (`aoss`) rather than a managed domain (`es`).
     *
     * Two differences, both of which have to be right or every request is a signature failure: the
     * SigV4 service name becomes `aoss`, and Serverless requires the payload hash to be sent as an
     * `x-amz-content-sha256` header rather than only folded into the signature.
     *
     * **Shaped from AWS's documentation and not exercised against a live collection.** The rest of
     * this module is verified against a real engine and against real AWS; this flag is not. It is
     * here because leaving it out would have left `AwsProtocol.openSearch("aoss")` reachable and
     * quietly broken — the signing name alone is not enough — not because Serverless is a supported
     * configuration.
     */
    public var serverless: Boolean = false

    /**
     * A client to send on, instead of one built here.
     *
     * Supplying one **bypasses [caInfo] and [httpTimeouts]**: those are arguments to the client this
     * factory would have built, and a client the caller already owns is configured by the caller.
     */
    public var httpClient: HttpClient? = null
    public var retryConfig: RetryConfig = RetryConfig()

    /** CA bundle for the native Curl engine. Null uses the system trust store. */
    public var caInfo: String? = null

    /**
     * Per-attempt time limits for the client this factory builds. Ignored when [httpClient] is set.
     *
     * Worth a second look for this service where it is not for the others in this library. A search
     * is the one call here whose duration is a function of *the caller's own query* — an aggregation
     * over a large index, or a `scroll`, can outrun a default that suits a `GetItem` — and the
     * per-attempt bound is what turns that into a retry storm rather than a slow answer.
     */
    public var httpTimeouts: AwsHttpTimeouts = AwsHttpTimeouts()

    /** Notified of every attempt, retry decision and give-up. Null means no instrumentation. */
    public var observer: AwsCallObserver? = null
}

/**
 * Builds an OpenSearch transport.
 *
 * @throws AwsConfigurationException if no endpoint is configured. See [OpenSearchConfig.endpointUrl]
 *   for why this is not defaulted.
 */
public fun OpenSearch(configure: OpenSearchConfig.() -> Unit = {}): OpenSearch {
    val config = OpenSearchConfig().apply(configure)
    val protocol = if (config.serverless) OPENSEARCH_SERVERLESS_PROTOCOL else OPENSEARCH_PROTOCOL
    val region = resolveRegion(config.region)
    val envKey = "AWS_ENDPOINT_URL_" + protocol.endpointPrefix.uppercase()

    val url = config.endpointUrl?.takeIf { it.isNotBlank() }
        ?: awsEnv(envKey)?.takeIf { it.isNotBlank() }
        ?: throw AwsConfigurationException(
            "No OpenSearch endpoint configured. Set `endpointUrl` on the client config to the " +
                "domain endpoint (CDK's Domain.domainEndpoint, e.g. " +
                "search-my-domain-abc123.$region.es.amazonaws.com), or set the $envKey environment " +
                "variable. There is no regional default: es.$region.amazonaws.com is the OpenSearch " +
                "configuration API, not a domain, and would answer searches with a 404.",
        )

    // One binding for the effective client, handed to both the transport and the close path — see
    // the same note in `Scheduler()`, where splitting the two leaked the client we had just built.
    val httpClient = config.httpClient ?: awsHttpClient(config.caInfo, config.httpTimeouts)
    return DefaultOpenSearch(
        client = AwsServiceClient(
            httpClient = httpClient,
            credentialsProvider = config.credentialsProvider ?: defaultCredentialsProvider(),
            endpoint = resolveEndpoint(
                protocol.endpointPrefix,
                region,
                // Already resolved above, so `resolveEndpoint` treats it as explicit and consults
                // no environment variable of its own — including the generic `AWS_ENDPOINT_URL`,
                // which on a machine pointed at LocalStack is emphatically not a search domain.
                explicit = if ("://" in url) url else "https://$url",
            ),
            region = region,
            protocol = protocol,
            retryConfig = config.retryConfig,
            observer = config.observer,
        ),
        // Serverless folds the payload hash into a header as well as into the signature.
        signedBodyHeader = if (config.serverless) {
            SignedBodyHeader.X_AMZ_CONTENT_SHA256
        } else {
            SignedBodyHeader.NONE
        },
        ownsHttpClient = config.httpClient == null,
        httpClient = httpClient,
    )
}

internal class DefaultOpenSearch(
    override val client: AwsServiceClient,
    private val signedBodyHeader: SignedBodyHeader = SignedBodyHeader.NONE,
    private val ownsHttpClient: Boolean = false,
    private val httpClient: HttpClient? = null,
) : OpenSearch {

    override suspend fun search(
        index: String,
        query: JsonObject,
        params: List<Pair<String, String>>,
    ): JsonObject = request(
        method = "POST",
        path = "/${sigV4UriEncode(index)}/_search",
        safety = OperationSafety.IDEMPOTENT,
        query = params,
        body = query,
        operation = "Search",
    )

    override suspend fun getDocument(
        index: String,
        id: String,
        params: List<Pair<String, String>>,
    ): JsonObject? = try {
        request(
            method = "GET",
            path = "/${sigV4UriEncode(index)}/_doc/${sigV4UriEncode(id)}",
            safety = OperationSafety.IDEMPOTENT,
            query = params,
            operation = "GetDocument",
        )
    } catch (e: OpenSearchException) {
        // Recognised by what the body *says* — `"found": false` — not by "a 404 with no type we
        // could read". A missing index carries `index_not_found_exception` and an AWS front-end 404
        // carries nothing at all; neither is a missing document and neither may become null here.
        if (e.statusCode == 404 && isDocumentNotFound(e.rawErrorBody)) null else throw e
    }

    override suspend fun bulk(
        body: String,
        index: String?,
        params: List<Pair<String, String>>,
    ): JsonObject = mapErrors {
        val path = if (index == null) "/_bulk" else "/${sigV4UriEncode(index)}/_bulk"
        val response = client.callRaw(
            method = "POST",
            path = path,
            query = params,
            headers = listOf("Content-Type" to NDJSON_CONTENT_TYPE),
            body = (if (body.endsWith("\n")) body else "$body\n").encodeToByteArray(),
            operation = "Bulk",
            // The one operation here that must never be replayed on an ambiguous failure.
            safety = OperationSafety.NOT_IDEMPOTENT,
            signedBodyHeader = signedBodyHeader,
        )
        response.body.asJson()
    }

    override suspend fun request(
        method: String,
        path: String,
        safety: OperationSafety,
        query: List<Pair<String, String>>,
        body: JsonObject?,
        operation: String?,
    ): JsonObject = mapErrors {
        val encoded = body?.toString()?.encodeToByteArray()
        val response = client.callRaw(
            method = method,
            path = path,
            query = query,
            // No body, no content type: `AwsProtocol.openSearch` carries none, so this is the only
            // place one is added, and a bare GET should not claim to be sending JSON.
            headers = if (encoded == null) emptyList() else listOf("Content-Type" to JSON_CONTENT_TYPE),
            body = encoded ?: ByteArray(0),
            operation = operation,
            safety = safety,
            signedBodyHeader = signedBodyHeader,
        )
        response.body.asJson()
    }

    override fun close() {
        if (ownsHttpClient) httpClient?.close()
    }
}

/**
 * Parses a successful response body, treating an empty one as `{}`.
 *
 * The same accommodation `aws-core`'s `decodeResponseBody` makes, and for the same reason: decoding
 * `""` throws naming a JSON parse position, which describes nothing a caller can act on.
 */
private fun ByteArray.asJson(): JsonObject {
    val element = openSearchJson.parseToJsonElement(decodeToString().ifBlank { "{}" })
    // The `_cat` APIs answer with a top-level array. Nothing this module ships calls one, but
    // `request` is the seam for what it does not ship, and a bare ClassCastException names neither
    // the endpoint nor the remedy.
    return element as? JsonObject ?: throw OpenSearchException(
        code = null,
        message = "OpenSearch answered with a top-level ${element::class.simpleName} rather than an " +
            "object. `request` returns a JsonObject; use `client.callRaw` for an endpoint that " +
            "answers with an array, such as the `_cat` APIs.",
        statusCode = 200,
    )
}
