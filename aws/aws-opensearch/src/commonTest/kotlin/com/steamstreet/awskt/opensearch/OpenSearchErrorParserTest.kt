package com.steamstreet.awskt.opensearch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The envelope, shape by shape.
 *
 * These are unit tests on the parser rather than on the transport because the parser is the whole
 * reason the module exists — `AwsJsonErrorParser` reads *nothing* out of any of these bodies. The
 * fixtures are the real thing: `LocalOpenSearchTest` asserts that a live engine still produces the
 * shapes assumed here, so a version bump that changed one would fail there rather than silently
 * making every fixture below a description of history.
 */
class OpenSearchErrorParserTest {

    private fun parse(status: Int, body: String?, headers: Map<String, String> = emptyMap()) =
        OpenSearchErrorParser.parse(status, headers, body?.encodeToByteArray())

    /** The shape every OpenSearch rejection takes. */
    @Test
    fun readsTypeAndReasonFromTheErrorObject() {
        val details = parse(
            404,
            """{"error":{"type":"index_not_found_exception","reason":"no such index [venues]",""" +
                """"index":"venues"},"status":404}""",
        )

        assertEquals("index_not_found_exception", details.code)
        assertEquals("no such index [venues]", details.message)
    }

    /**
     * `error` as a bare string, which is what a rejected HTTP method answers with. There is no type
     * in it to report, and inventing one would be worse than a null.
     */
    @Test
    fun readsTheBareStringErrorAsAMessageWithNoCode() {
        val details = parse(
            405,
            """{"error":"Incorrect HTTP method for uri [/venues/_search] and method [PUT], """ +
                """allowed: [POST, GET]","status":405}""",
        )

        assertNull(details.code)
        assertEquals(
            "Incorrect HTTP method for uri [/venues/_search] and method [PUT], allowed: [POST, GET]",
            details.message,
        )
    }

    /** A shard-level failure names its type only in the root cause. */
    @Test
    fun fallsBackToTheFirstRootCauseType() {
        val details = parse(
            400,
            """{"error":{"root_cause":[{"type":"query_shard_exception","reason":"failed to create """ +
                """query"}],"reason":"all shards failed"},"status":400}""",
        )

        assertEquals("query_shard_exception", details.code)
        assertEquals("all shards failed", details.message)
    }

    /**
     * The AWS front end refusing before OpenSearch is reached — an access policy that does not
     * name the caller's role. The single most common first failure against a new domain, and the
     * one that would report as an entirely empty error without the fallback.
     */
    @Test
    fun readsTheAwsFrontEndsMessageWhenOpenSearchNeverSawTheRequest() {
        val details = parse(
            403,
            """{"Message":"User: arn:aws:sts::1:assumed-role/web/abc is not authorized to """ +
                """perform: es:ESHttpPost"}""",
        )

        assertNull(details.code)
        assertEquals(
            "User: arn:aws:sts::1:assumed-role/web/abc is not authorized to perform: es:ESHttpPost",
            details.message,
        )
    }

    /** A signature failure is an AWS-shaped error, header and all. */
    @Test
    fun readsTheAwsErrorTypeHeader() {
        val details = parse(
            403,
            """{"message":"The request signature we calculated does not match"}""",
            mapOf("x-amzn-errortype" to "InvalidSignatureException:http://internal"),
        )

        assertEquals("InvalidSignatureException", details.code)
        assertEquals("The request signature we calculated does not match", details.message)
    }

    /**
     * The one deliberate substitution. `es_rejected_execution_exception` is in none of `aws-core`'s
     * tables and neither is the status, so reporting it honestly would mean a throttled search
     * failed on its first attempt.
     */
    @Test
    fun reportsA429AsThrottlingSoThatAwsCoreRetriesIt() {
        val body = """{"error":{"type":"es_rejected_execution_exception","reason":"rejected """ +
            """execution of coordinating operation"},"status":429}"""

        assertEquals(THROTTLING_CODE, parse(429, body).code)
        assertEquals("rejected execution of coordinating operation", parse(429, body).message)
    }

    /** The substitution is lossless: the engine's own name is still in the body. */
    @Test
    fun theRealTypeSurvivesTheThrottlingSubstitution() {
        val body = """{"error":{"type":"circuit_breaking_exception","reason":"[parent] Data too """ +
            """large"},"status":429}"""

        assertEquals(THROTTLING_CODE, parse(429, body).code)
        assertEquals("circuit_breaking_exception", openSearchErrorType(body.encodeToByteArray()))
    }

    /**
     * A missing *document* is a 404 with no error envelope at all. Reading no code out of it is
     * what lets `getDocument` tell it apart from a missing index.
     */
    @Test
    fun readsNothingFromANotFoundDocument() {
        val details = parse(404, """{"_index":"venues","_id":"7","found":false}""")

        assertNull(details.code)
        assertNull(details.message)
    }

    /** Degrades rather than throwing — an error path that throws destroys the error. */
    @Test
    fun survivesAMalformedOrEmptyBody() {
        for (body in listOf(null, "", "   ", "<html>502 Bad Gateway</html>", """{"error":""")) {
            val details = parse(502, body)
            assertNull(details.code, "code for body=$body")
            assertNull(details.message, "message for body=$body")
        }
    }

    /** Null in, null out, on every accessor the module publishes. */
    @Test
    fun errorTypeOfANonErrorBodyIsNull() {
        assertNull(openSearchErrorType(null))
        assertNull(openSearchErrorType("""{"hits":{"total":{"value":0}}}""".encodeToByteArray()))
        assertNull(openSearchErrorType("""{"error":"a bare string"}""".encodeToByteArray()))
    }
}
