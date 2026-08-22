package com.steamstreet.awskt.opensearch

import com.steamstreet.awskt.core.awsEnv
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * End-to-end proof against a **real managed OpenSearch domain**.
 *
 * See `aws-kms`'s `LiveKmsTest` for why these exist. This one closes the two gaps
 * `LocalOpenSearchTest` structurally cannot:
 *
 * - **SigV4 under the `es` signing name.** The container runs with its security plugin disabled, so
 *   it ignores the `Authorization` header entirely; a signer that is wrong for `es` passes every
 *   test there and fails every request here.
 * - **The AWS front end in front of the engine**, which the container does not have.
 *
 * Self-skips without credentials and without an endpoint:
 *
 * ```
 * eval "$(aws configure export-credentials --profile <profile> --format env)" \
 *   && AWS_REGION=us-west-2 \
 *      SMOKE_OPENSEARCH_ENDPOINT=search-my-domain-abc123.us-west-2.es.amazonaws.com \
 *      ./gradlew :aws:aws-opensearch:jvmTest
 * ```
 *
 * The domain's access policy has to name the calling principal, and the domain has to be
 * **public-access**: a VPC-only domain is unreachable from a laptop, which presents as a connection
 * timeout rather than as an authorization failure.
 *
 * ### Nothing here writes
 *
 * Every call below is a search, and no index is created, indexed into, or deleted. That is
 * deliberate rather than incidental: unlike a queue or a schedule, a search domain in an account is
 * usually somebody's *data*, and a live suite that could leave an index behind is one nobody will
 * point at a domain that matters. `LocalOpenSearchTest` is where writes and `_bulk` are exercised.
 */
class LiveOpenSearchTest {

    private fun endpoint(): String? {
        if (awsEnv("AWS_ACCESS_KEY_ID").isNullOrEmpty()) return null
        return awsEnv("SMOKE_OPENSEARCH_ENDPOINT")?.takeIf { it.isNotBlank() }
    }

    private fun openSearch(endpoint: String) = OpenSearch {
        endpointUrl = endpoint
        caInfo = awsEnv("SMOKE_CA_BUNDLE")
    }

    /**
     * A signed search against `_all`, which needs no fixture: it answers on a domain with no
     * indexes at all.
     *
     * The assertion is deliberately weak — the point is that the request was **accepted**, and a
     * signature that is wrong for `es` never gets this far.
     */
    @Test
    fun searchesAllIndexes() = runTest {
        val endpoint = endpoint() ?: return@runTest
        openSearch(endpoint).use { openSearch ->
            val response = openSearch.search(
                "_all",
                buildJsonObject {
                    put("size", 0)
                    putJsonObject("query") { putJsonObject("match_all") {} }
                },
            )

            assertNotNull(response["hits"], "a signed search answered without a hits object")
            assertNotNull(response["took"])
        }
    }

    /**
     * The error envelope, read off the real managed service rather than off a fixture or a
     * container. This is the assertion the whole module turns on.
     */
    @Test
    fun aMissingIndexReportsItsOpenSearchType() = runTest {
        val endpoint = endpoint() ?: return@runTest
        openSearch(endpoint).use { openSearch ->
            val failure = runCatching {
                openSearch.search("awskt-live-no-such-index", buildJsonObject { })
            }.exceptionOrNull()

            assertNotNull(failure)
            val typed = failure as? OpenSearchException
            assertNotNull(typed, "a missing index surfaced as ${failure::class.simpleName}")
            assertEquals(404, typed.statusCode)
            assertEquals(
                "index_not_found_exception",
                typed.type,
                "the managed service's error envelope no longer matches the parser's fixtures",
            )
        }
    }

    /**
     * A document GET against an index that does not exist throws rather than answering null.
     *
     * The distinction `getDocument` is built around, verified where it matters — the two 404s are
     * genuinely different documents on the wire, and only a real service proves this module reads
     * them apart.
     */
    @Test
    fun aDocumentGetInAMissingIndexThrowsRatherThanReturningNull() = runTest {
        val endpoint = endpoint() ?: return@runTest
        openSearch(endpoint).use { openSearch ->
            val failure = runCatching {
                openSearch.getDocument("awskt-live-no-such-index", "1")
            }.exceptionOrNull()

            assertNotNull(failure, "a missing index returned null instead of failing")
            assertEquals("index_not_found_exception", (failure as OpenSearchException).type)
        }
    }

    /** The response body arrives unmodelled, which is the module's central promise. */
    @Test
    fun returnsTheResponseUnmodelled() = runTest {
        val endpoint = endpoint() ?: return@runTest
        openSearch(endpoint).use { openSearch ->
            val response = openSearch.search("_all", buildJsonObject { put("size", 0) })

            // `hits.total.value` is a nested field no type in this module knows about.
            assertNotNull(response["hits"]!!.jsonObject["total"])
        }
    }
}
