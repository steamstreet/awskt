package com.steamstreet.awskt.opensearch

import com.steamstreet.awskt.core.AwsConfigurationException
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.StaticCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val DOMAIN = "https://search-vf-abc123.us-west-2.es.amazonaws.com"

internal class OpenSearchHarness {
    val requests = mutableListOf<HttpRequestData>()
    val bodies = mutableListOf<String>()
}

/**
 * The module's client over a [MockEngine], built the way `OpenSearch()` builds it.
 *
 * [DefaultOpenSearch] directly rather than through the factory: the factory resolves credentials
 * and a region from the environment, and a test that depends on either is a test that passes on one
 * machine.
 */
internal fun harnessOpenSearch(
    harness: OpenSearchHarness,
    responder: (Int) -> Pair<String, HttpStatusCode>,
): OpenSearch {
    var call = 0
    val engine = MockEngine { request ->
        harness.requests += request
        harness.bodies += (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
        val (body, status) = responder(call++)
        respond(body, status)
    }
    return DefaultOpenSearch(
        AwsServiceClient(
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET", "TOKEN")),
            endpoint = resolveEndpoint("es", "us-west-2", DOMAIN),
            region = "us-west-2",
            protocol = OPENSEARCH_PROTOCOL,
            clock = { 1_700_000_000_000 },
            random = { 1.0 },
            sleep = {},
        ),
    )
}

private val MATCH_QUERY = buildJsonObject {
    put("size", 20)
    putJsonObject("query") { putJsonObject("match") { put("name", "aria") } }
}

private const val HITS = """{"took":4,"timed_out":false,"hits":{"total":{"value":1},"hits":[]}}"""

class OpenSearchProtocolTest {

    /** The four fields the module exists to supply, exactly as `AwsProtocol` takes them. */
    @Test
    fun theProtocolIsEsRestAddressedWithOpenSearchsErrorParser() {
        assertEquals("es", OPENSEARCH_PROTOCOL.endpointPrefix)
        assertEquals("es", OPENSEARCH_PROTOCOL.signingName)
        assertNull(OPENSEARCH_PROTOCOL.targetPrefix)
        assertEquals(OpenSearchErrorParser, OPENSEARCH_PROTOCOL.errorParser)
    }

    /**
     * Null, and the KDoc on [AwsProtocol.openSearch] says why: `_bulk` needs a different one, and a
     * protocol-supplied content type cannot be overridden per call without breaking the signature.
     */
    @Test
    fun theProtocolSuppliesNoContentTypeSoThatBulkCanSupplyItsOwn() {
        assertNull(OPENSEARCH_PROTOCOL.contentType)
    }

    /** Serverless changes the signing name. See `OpenSearchConfig.serverless` for what else it needs. */
    @Test
    fun serverlessSignsAsAoss() {
        assertEquals("aoss", OPENSEARCH_SERVERLESS_PROTOCOL.signingName)
        assertEquals("aoss", OPENSEARCH_SERVERLESS_PROTOCOL.endpointPrefix)
    }

    /**
     * REST-addressed means no `X-Amz-Target`. A target header here would mean the protocol had been
     * given a prefix, and the service would ignore a header it has never heard of.
     */
    @Test
    fun addressesByPathWithNoTargetHeader() = runTest {
        val h = OpenSearchHarness()
        harnessOpenSearch(h) { HITS to HttpStatusCode.OK }.search("venues", MATCH_QUERY)

        val request = h.requests.single()
        assertNull(request.headers["X-Amz-Target"])
        assertEquals("POST", request.method.value)
        assertEquals("/venues/_search", request.url.encodedPath)
    }

    /** `es`, in the credential scope. The signing name is not derivable from anything else on the wire. */
    @Test
    fun signsUnderTheEsServiceName() = runTest {
        val h = OpenSearchHarness()
        harnessOpenSearch(h) { HITS to HttpStatusCode.OK }.search("venues", MATCH_QUERY)

        assertContains(h.requests.single().headers["Authorization"]!!, "/us-west-2/es/aws4_request")
    }

    /** The domain host is signed and sent, not a regional endpoint derived from `es` + the region. */
    @Test
    fun addressesTheDomainHost() = runTest {
        val h = OpenSearchHarness()
        harnessOpenSearch(h) { HITS to HttpStatusCode.OK }.search("venues", MATCH_QUERY)

        assertEquals("search-vf-abc123.us-west-2.es.amazonaws.com", h.requests.single().url.host)
    }
}

class OpenSearchRequestShapeTest {

    /** The query body reaches the wire verbatim. Nothing is added to it and nothing is reordered. */
    @Test
    fun sendsTheQueryBodyUnchanged() = runTest {
        val h = OpenSearchHarness()
        harnessOpenSearch(h) { HITS to HttpStatusCode.OK }.search("venues", MATCH_QUERY)

        assertEquals(MATCH_QUERY.toString(), h.bodies.single())
        assertEquals(JSON_CONTENT_TYPE, h.requests.single().body.contentType?.toString())
    }

    /** The response comes back parsed and otherwise untouched — no model, no field renaming. */
    @Test
    fun returnsTheResponseUnmodelled() = runTest {
        val h = OpenSearchHarness()
        val response = harnessOpenSearch(h) { HITS to HttpStatusCode.OK }.search("venues", MATCH_QUERY)

        assertEquals(4, response["took"]!!.jsonPrimitive.content.toInt())
        assertNotNull(response["hits"])
    }

    /**
     * A body-less GET sends no content type at all. `AwsProtocol.openSearch` carries none, so this
     * is the property that arrangement buys and the one a regression would silently undo.
     */
    @Test
    fun aDocumentGetCarriesNoBodyAndNoContentType() = runTest {
        val h = OpenSearchHarness()
        harnessOpenSearch(h) { """{"_id":"7","found":true}""" to HttpStatusCode.OK }
            .getDocument("venues", "7")

        val request = h.requests.single()
        assertEquals("GET", request.method.value)
        assertEquals("/venues/_doc/7", request.url.encodedPath)
        assertEquals("", h.bodies.single())
        assertNull(request.body.contentType)
    }

    /**
     * A document id is percent-encoded before it is signed. Interpolated raw, `../_search` would
     * address a different endpoint under the caller's own credentials.
     */
    @Test
    fun percentEncodesTheDocumentId() = runTest {
        val h = OpenSearchHarness()
        harnessOpenSearch(h) { """{"found":false}""" to HttpStatusCode.NotFound }
            .getDocument("venues", "../../_search?q=*")

        assertEquals("/venues/_doc/..%2F..%2F_search%3Fq%3D%2A", h.requests.single().url.encodedPath)
    }

    /**
     * A multi-index expression survives the encoding. The comma is signed as `%2C`, and OpenSearch
     * decodes the path before it resolves index names — asserted here on the wire, and proved
     * against a real engine in `LocalOpenSearchTest`.
     */
    @Test
    fun encodesAMultiIndexExpression() = runTest {
        val h = OpenSearchHarness()
        harnessOpenSearch(h) { HITS to HttpStatusCode.OK }.search("venues,events", MATCH_QUERY)

        assertEquals("/venues%2Cevents/_search", h.requests.single().url.encodedPath)
    }

    /** Query-string parameters are the only way to reach the search options that are not body fields. */
    @Test
    fun passesQueryStringParameters() = runTest {
        val h = OpenSearchHarness()
        harnessOpenSearch(h) { HITS to HttpStatusCode.OK }
            .search("venues", MATCH_QUERY, listOf("routing" to "west", "preference" to "_local"))

        val url = h.requests.single().url
        assertEquals("west", url.parameters["routing"])
        assertEquals("_local", url.parameters["preference"])
    }
}

private const val BULK_OK = """{"took":9,"errors":false,"items":[]}"""

class OpenSearchBulkTest {

    private val bulkBody = """{"index":{"_index":"venues","_id":"7"}}
{"name":"Aria"}"""

    /** `_bulk` is the one request here that must not claim to be `application/json`: that is a 406. */
    @Test
    fun sendsNdjson() = runTest {
        val h = OpenSearchHarness()
        harnessOpenSearch(h) { BULK_OK to HttpStatusCode.OK }.bulk(bulkBody)

        val request = h.requests.single()
        assertEquals("/_bulk", request.url.encodedPath)
        assertEquals(NDJSON_CONTENT_TYPE, request.body.contentType?.toString())
    }

    /** OpenSearch rejects a bulk body with no trailing newline, naming a parse position. */
    @Test
    fun terminatesTheBodyWithANewline() = runTest {
        val h = OpenSearchHarness()
        harnessOpenSearch(h) { BULK_OK to HttpStatusCode.OK }.bulk(bulkBody)

        assertTrue(h.bodies.single().endsWith("\n"))
        assertEquals(bulkBody + "\n", h.bodies.single())
    }

    /** One already terminated is not terminated twice — a blank line is a parse error of its own. */
    @Test
    fun doesNotDoubleTerminateABodyThatAlreadyEndsInANewline() = runTest {
        val h = OpenSearchHarness()
        harnessOpenSearch(h) { BULK_OK to HttpStatusCode.OK }.bulk("$bulkBody\n")

        assertEquals(bulkBody + "\n", h.bodies.single())
    }

    /** A default index addresses `POST /{index}/_bulk`, and is encoded like any other segment. */
    @Test
    fun addressesADefaultIndexWhenGivenOne() = runTest {
        val h = OpenSearchHarness()
        harnessOpenSearch(h) { BULK_OK to HttpStatusCode.OK }.bulk(bulkBody, index = "venues")

        assertEquals("/venues/_bulk", h.requests.single().url.encodedPath)
    }

    /**
     * **The assertion the module is built around.** An ambiguous transport failure — the request may
     * have reached OpenSearch, the response did not come back — is surfaced after one attempt rather
     * than replayed. A replay of a body carrying `index` actions without explicit ids is a set of
     * duplicate documents.
     *
     * `RuntimeException("read timed out")` matches none of `aws-core`'s provably-not-sent markers,
     * so it lands in the AMBIGUOUS bucket, which is the one `OperationSafety` governs.
     */
    @Test
    fun neverReplaysAnAmbiguouslyFailedBulk() = runTest {
        val h = OpenSearchHarness()
        val engine = MockEngine {
            h.requests += it
            throw RuntimeException("read timed out")
        }
        val openSearch = DefaultOpenSearch(
            AwsServiceClient(
                httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
                credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET")),
                endpoint = resolveEndpoint("es", "us-west-2", DOMAIN),
                region = "us-west-2",
                protocol = OPENSEARCH_PROTOCOL,
                clock = { 1_700_000_000_000 },
                random = { 1.0 },
                sleep = {},
            ),
        )

        assertFailsWith<RuntimeException> { openSearch.bulk(bulkBody) }
        assertEquals(1, h.requests.size, "an ambiguously failed bulk was replayed")

        // The contrast that makes the number above mean something: the same transport failure on a
        // search is retried, so `1` is a property of the operation and not of the harness.
        h.requests.clear()
        assertFailsWith<RuntimeException> { openSearch.search("venues", MATCH_QUERY) }
        assertTrue(h.requests.size > 1, "an ambiguously failed search should be retried")
    }

    /** Per-item failures arrive inside a 200 and are the caller's to inspect, not this module's. */
    @Test
    fun returnsAPartiallyFailedBulkAsASuccess() = runTest {
        val h = OpenSearchHarness()
        val partial = """{"took":9,"errors":true,"items":[{"index":{"status":429,"error":""" +
            """{"type":"es_rejected_execution_exception"}}}]}"""

        val response = harnessOpenSearch(h) { partial to HttpStatusCode.OK }.bulk(bulkBody)

        assertTrue(response["errors"]!!.jsonPrimitive.content.toBoolean())
    }
}

class OpenSearchErrorTest {

    /** A missing document is null; the caller is not asked to distinguish a 404 from a failure. */
    @Test
    fun aMissingDocumentIsNull() = runTest {
        val h = OpenSearchHarness()
        val response = harnessOpenSearch(h) {
            """{"_index":"venues","_id":"7","found":false}""" to HttpStatusCode.NotFound
        }.getDocument("venues", "7")

        assertNull(response)
    }

    /**
     * A missing *index* is the same status and a completely different answer. Flattening it into
     * null would report "no such venue" for a query path whose index had been deleted.
     */
    @Test
    fun aMissingIndexThrowsRatherThanReturningNull() = runTest {
        val h = OpenSearchHarness()
        val failure = assertFailsWith<OpenSearchException> {
            harnessOpenSearch(h) {
                """{"error":{"type":"index_not_found_exception","reason":"no such index [venues]"},""" +
                    """"status":404}""" to HttpStatusCode.NotFound
            }.getDocument("venues", "7")
        }

        assertEquals("index_not_found_exception", failure.type)
        assertEquals(404, failure.statusCode)
    }

    /**
     * A 404 that is neither a missing document nor a missing index — a mistyped path answered by
     * the AWS front end — **throws**. It is recognised by what the body says rather than by "a 404
     * with no type", so an unexpected one cannot become a silent empty answer.
     */
    @Test
    fun anUnexpected404IsNotFlattenedIntoNull() = runTest {
        val h = OpenSearchHarness()
        val failure = assertFailsWith<OpenSearchException> {
            harnessOpenSearch(h) {
                """{"Message":"Not Found"}""" to HttpStatusCode.NotFound
            }.getDocument("venues", "7")
        }

        assertEquals(404, failure.statusCode)
        assertNull(failure.type)
    }

    /** The typed exception carries OpenSearch's own name for the failure. */
    @Test
    fun surfacesTheOpenSearchTypeOnTheException() = runTest {
        val h = OpenSearchHarness()
        val failure = assertFailsWith<OpenSearchException> {
            harnessOpenSearch(h) {
                """{"error":{"type":"parsing_exception","reason":"unknown query [mtch]"},"status":400}""" to
                    HttpStatusCode.BadRequest
            }.search("venues", MATCH_QUERY)
        }

        assertEquals("parsing_exception", failure.type)
        assertEquals("parsing_exception", failure.code)
        assertEquals("unknown query [mtch]", failure.message)
    }

    /**
     * The substitution paying off end to end: a 429 is retried, and the exception that would have
     * been thrown still names the engine's own type.
     */
    @Test
    fun retriesAThrottledSearchAndKeepsTheRealTypeIfItGivesUp() = runTest {
        val throttled = """{"error":{"type":"es_rejected_execution_exception","reason":"rejected"},""" +
            """"status":429}"""

        val recovered = OpenSearchHarness()
        val response = harnessOpenSearch(recovered) { call ->
            if (call == 0) throttled to HttpStatusCode.TooManyRequests else HITS to HttpStatusCode.OK
        }.search("venues", MATCH_QUERY)

        assertEquals(2, recovered.requests.size, "a 429 was not retried")
        assertNotNull(response["hits"])

        val exhausted = OpenSearchHarness()
        val failure = assertFailsWith<OpenSearchException> {
            harnessOpenSearch(exhausted) { throttled to HttpStatusCode.TooManyRequests }
                .search("venues", MATCH_QUERY)
        }

        assertEquals(THROTTLING_CODE, failure.code)
        assertEquals("es_rejected_execution_exception", failure.type)
    }

    /** An IAM refusal never reaches OpenSearch, so there is no type — but there is still a message. */
    @Test
    fun surfacesAnAccessPolicyRefusal() = runTest {
        val h = OpenSearchHarness()
        val failure = assertFailsWith<OpenSearchException> {
            harnessOpenSearch(h) {
                """{"Message":"User: arn:aws:sts::1:assumed-role/web/x is not authorized to """ +
                    """perform: es:ESHttpPost"}""" to HttpStatusCode.Forbidden
            }.search("venues", MATCH_QUERY)
        }

        assertNull(failure.type)
        assertEquals(403, failure.statusCode)
        assertContains(failure.message!!, "not authorized to perform: es:ESHttpPost")
    }
}

class OpenSearchConfigTest {

    /**
     * No endpoint is a configuration error, **not** a fall back to `es.<region>.amazonaws.com`. That
     * host is the OpenSearch configuration API and would answer a search with a 404 from a service
     * the caller never meant to address.
     */
    @Test
    fun refusesToGuessAnEndpoint() {
        val failure = assertFailsWith<AwsConfigurationException> {
            OpenSearch { region = "us-west-2" }
        }

        assertContains(failure.message!!, "No OpenSearch endpoint configured")
    }

    /** CDK hands out a bare host. Requiring the scheme would make that a footgun for no reason. */
    @Test
    fun acceptsABareDomainHost() = runTest {
        val h = OpenSearchHarness()
        val engine = MockEngine {
            h.requests += it
            respond(HITS, HttpStatusCode.OK)
        }
        OpenSearch {
            region = "us-west-2"
            endpointUrl = "search-vf-abc123.us-west-2.es.amazonaws.com"
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET"))
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false }
        }.use { it.search("venues", MATCH_QUERY) }

        val url = h.requests.single().url
        assertEquals("https", url.protocol.name)
        assertEquals("search-vf-abc123.us-west-2.es.amazonaws.com", url.host)
    }

    /**
     * `request` is the seam for everything the module does not ship, and its `safety` parameter has
     * no default — an extension author writing a write cannot get replay safety by omission.
     */
    @Test
    fun theExtensionSeamTakesAnExplicitSafety() = runTest {
        val h = OpenSearchHarness()
        val response: JsonObject = harnessOpenSearch(h) { """{"count":3}""" to HttpStatusCode.OK }
            .request(
                method = "POST",
                path = "/venues/_count",
                safety = OperationSafety.IDEMPOTENT,
                body = MATCH_QUERY,
                operation = "Count",
            )

        assertEquals("/venues/_count", h.requests.single().url.encodedPath)
        assertEquals(3, response["count"]!!.jsonPrimitive.content.toInt())
    }
}
