package com.steamstreet.awskt.opensearch

import com.steamstreet.awskt.core.StaticCredentialsProvider
import com.steamstreet.awskt.signing.AwsCredentials
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A real OpenSearch engine, started once per JVM.
 *
 * ### What this oracle is, and what it is not
 *
 * It **is** the only thing that can confirm the error envelope [OpenSearchErrorParser] is written
 * against is the envelope OpenSearch actually emits. A canned `MockEngine` fixture answers whatever
 * it was told to answer, so a parser written against a misremembered shape passes every unit test in
 * this module and returns nulls in production. Every error assertion below reads a body the engine
 * produced.
 *
 * It is **not** a signature oracle, and not in the mild way LocalStack is not: the security plugin
 * is switched off entirely, so the `Authorization` header this client works hard to compute is
 * *ignored*, not merely accepted. A run here is fully compatible with a signer that fails every
 * request to a real domain. `LiveOpenSearchTest` is where that is closed.
 *
 * It also runs against **OpenSearch, not AWS's managed OpenSearch**, so nothing in front of the
 * engine exists here — the IAM refusal shape in [OpenSearchErrorParserTest] has no counterpart
 * below and cannot have one.
 *
 * Started lazily so a developer without Docker still gets a green offline build: every test calls
 * [LocalOpenSearch.available] and skips itself. Ryuk reaps the container when the JVM exits.
 */
internal object LocalOpenSearch {

    /** False when Docker is not reachable, in which case every test here self-skips. */
    val available: Boolean by lazy {
        runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    }

    /**
     * Pinned to a 2.x minor rather than floating on `latest`. The error envelope is the thing under
     * test, and it is the engine's, so an unpinned image would let an upstream release change the
     * fixtures this module is verified against without a commit here.
     *
     * `DISABLE_SECURITY_PLUGIN` is what makes a single-node container usable without provisioning
     * a certificate and an admin password. It is also what makes this not a signature oracle.
     */
    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse("opensearchproject/opensearch:2.17.1"))
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            // A default heap sized for a production node will not start under a CI memory cap.
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .waitingFor(Wait.forHttp("/_cluster/health").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3))
            .also { it.start() }
    }

    val endpoint: String get() = "http://${container.host}:${container.getMappedPort(9200)}"

    /** Our transport, pointed at the container. Credentials are fabricated; nothing checks them. */
    fun openSearch(): OpenSearch = OpenSearch {
        region = "us-east-1"
        endpointUrl = endpoint
        credentialsProvider = StaticCredentialsProvider(AwsCredentials("DummyKey", "DummySecret"))
    }
}

/**
 * The envelope fixtures in [OpenSearchErrorParserTest], re-derived from a live engine.
 *
 * Each test here provokes a real failure and asserts that this module reads the same thing out of
 * it that the unit test asserts against a hand-written body. If OpenSearch ever changes a shape,
 * this suite fails and the fixture that has quietly become fiction is named.
 */
class LocalOpenSearchTest {

    private fun skipUnlessDocker(): Boolean {
        if (!LocalOpenSearch.available) {
            println("Docker unavailable; skipping LocalOpenSearchTest")
            return true
        }
        return false
    }

    private suspend fun OpenSearch.indexDocument(index: String, id: String, document: JsonObject) {
        request(
            method = "PUT",
            path = "/$index/_doc/$id",
            safety = com.steamstreet.awskt.core.OperationSafety.IDEMPOTENT,
            query = listOf("refresh" to "true"),
            body = document,
            operation = "Index",
        )
    }

    private fun matchAll() = buildJsonObject { putJsonObject("query") { putJsonObject("match_all") {} } }

    /** The headline: a search against a real engine, answered and parsed. */
    @Test
    fun searchesARealIndex() = runTest {
        if (skipUnlessDocker()) return@runTest
        LocalOpenSearch.openSearch().use { openSearch ->
            openSearch.indexDocument("venues-search", "1", buildJsonObject { put("name", "Aria") })

            val response = openSearch.search(
                "venues-search",
                buildJsonObject { putJsonObject("query") { putJsonObject("match") { put("name", "Aria") } } },
            )

            val hits = response["hits"]!!.jsonObject["hits"]!!.jsonArray
            assertEquals(1, hits.size)
            assertEquals("Aria", hits[0].jsonObject["_source"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        }
    }

    /**
     * The multi-index claim in [OpenSearch.search]'s KDoc, proved rather than assumed.
     *
     * `venues-a,venues-b` is signed and sent as `venues-a%2Cvenues-b`. Whether OpenSearch decodes
     * the path before it resolves index names is a property of the engine, not something the wire
     * assertion in `OpenSearchRequestShapeTest` can establish — if it did not, this returns hits
     * from one index or none.
     */
    @Test
    fun aPercentEncodedCommaStillAddressesTwoIndexes() = runTest {
        if (skipUnlessDocker()) return@runTest
        LocalOpenSearch.openSearch().use { openSearch ->
            openSearch.indexDocument("venues-a", "1", buildJsonObject { put("name", "Aria") })
            openSearch.indexDocument("venues-b", "1", buildJsonObject { put("name", "Bellagio") })

            val hits = openSearch.search("venues-a,venues-b", matchAll())["hits"]!!
                .jsonObject["hits"]!!.jsonArray

            assertEquals(2, hits.size)
            assertEquals(setOf("venues-a", "venues-b"), hits.map { it.jsonObject["_index"]!!.jsonPrimitive.content }.toSet())
        }
    }

    /** Fixture check: `{"error":{"type":"index_not_found_exception",…},"status":404}`. */
    @Test
    fun aMissingIndexReportsIndexNotFoundException() = runTest {
        if (skipUnlessDocker()) return@runTest
        LocalOpenSearch.openSearch().use { openSearch ->
            val failure = assertFailsWith<OpenSearchException> {
                openSearch.search("no-such-index-anywhere", matchAll())
            }

            assertEquals(404, failure.statusCode)
            assertEquals("index_not_found_exception", failure.type)
            assertEquals("index_not_found_exception", failure.code)
            assertNotNull(failure.message)
        }
    }

    /** Fixture check: a missing document is a 404 with no error envelope, so `getDocument` is null. */
    @Test
    fun aMissingDocumentInARealIndexIsNull() = runTest {
        if (skipUnlessDocker()) return@runTest
        LocalOpenSearch.openSearch().use { openSearch ->
            openSearch.indexDocument("venues-get", "1", buildJsonObject { put("name", "Aria") })

            assertNotNull(openSearch.getDocument("venues-get", "1"))
            assertNull(openSearch.getDocument("venues-get", "does-not-exist"))
        }
    }

    /**
     * Fixture check for the `root_cause` fallback: a malformed query names its type in the root
     * cause, and this is the path that reads it.
     */
    @Test
    fun aMalformedQueryReportsAParsingType() = runTest {
        if (skipUnlessDocker()) return@runTest
        LocalOpenSearch.openSearch().use { openSearch ->
            openSearch.indexDocument("venues-bad", "1", buildJsonObject { put("name", "Aria") })

            val failure = assertFailsWith<OpenSearchException> {
                openSearch.search(
                    "venues-bad",
                    buildJsonObject { putJsonObject("query") { putJsonObject("not_a_query_type") {} } },
                )
            }

            assertEquals(400, failure.statusCode)
            assertNotNull(failure.type, "no type read from a real 400 — the envelope has changed")
            assertNotNull(failure.message)
        }
    }

    /**
     * `_bulk` end to end, including the two things about it this module handles: the
     * `application/x-ndjson` content type — which a real engine rejects with a 406 rather than
     * tolerating — and the trailing newline.
     */
    @Test
    fun bulkIndexesAndReportsPerItemStatus() = runTest {
        if (skipUnlessDocker()) return@runTest
        LocalOpenSearch.openSearch().use { openSearch ->
            // Deliberately without a trailing newline: the module appends it.
            val body = """
                {"index":{"_index":"venues-bulk","_id":"1"}}
                {"name":"Aria"}
                {"index":{"_index":"venues-bulk","_id":"2"}}
                {"name":"Bellagio"}
            """.trimIndent()

            val response = openSearch.bulk(body, params = listOf("refresh" to "true"))

            assertEquals(false, response["errors"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(2, response["items"]!!.jsonArray.size)

            val hits = openSearch.search("venues-bulk", matchAll())["hits"]!!.jsonObject["hits"]!!.jsonArray
            assertEquals(2, hits.size)
        }
    }

    /**
     * The per-item-failure shape [OpenSearch.bulk]'s KDoc warns about: a **200** carrying
     * `"errors": true`. Nothing in the transport sees this, which is exactly why the KDoc tells
     * callers to check it.
     */
    @Test
    fun aPartiallyFailedBulkArrivesAsASuccessfulResponse() = runTest {
        if (skipUnlessDocker()) return@runTest
        LocalOpenSearch.openSearch().use { openSearch ->
            openSearch.indexDocument("venues-partial", "1", buildJsonObject { put("name", "Aria") })

            // `create` on an id that already exists fails; the `index` beside it succeeds.
            val response = openSearch.bulk(
                """
                {"create":{"_index":"venues-partial","_id":"1"}}
                {"name":"Duplicate"}
                {"index":{"_index":"venues-partial","_id":"2"}}
                {"name":"Bellagio"}
                """.trimIndent(),
                params = listOf("refresh" to "true"),
            )

            assertTrue(response["errors"]!!.jsonPrimitive.content.toBoolean())
            val items = response["items"]!!.jsonArray
            assertEquals(409, items[0].jsonObject["create"]!!.jsonObject["status"]!!.jsonPrimitive.content.toInt())
            assertEquals(201, items[1].jsonObject["index"]!!.jsonObject["status"]!!.jsonPrimitive.content.toInt())
        }
    }
}
