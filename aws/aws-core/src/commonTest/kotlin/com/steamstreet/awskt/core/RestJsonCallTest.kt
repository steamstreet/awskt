package com.steamstreet.awskt.core

import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The restJson1 half of `TypedCalls.kt`.
 *
 * Exercised here rather than only through `aws-scheduler` because these are shared infrastructure:
 * the next REST-shaped service to arrive inherits this behaviour, and a regression would surface as
 * that service misbehaving rather than as a failure here.
 */

@Serializable
private data class Body(@SerialName("Value") val value: String)

@Serializable
private data class Answer(
    @SerialName("Result") val result: String? = null,
    @SerialName("Count") val count: Int = 0,
)

private class RestHarness {
    val requests = mutableListOf<HttpRequestData>()
    val bodies = mutableListOf<String>()
}

private fun restClient(
    harness: RestHarness,
    respondWith: Pair<String, HttpStatusCode> = "{}" to HttpStatusCode.OK,
): AwsServiceClient {
    val engine = MockEngine { request ->
        harness.requests += request
        harness.bodies += (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
        respond(respondWith.first, respondWith.second)
    }
    return AwsServiceClient(
        httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
        credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET", "TOKEN")),
        endpoint = resolveEndpoint("example", "us-west-2", "https://example.us-west-2.amazonaws.com"),
        region = "us-west-2",
        protocol = AwsProtocol.restJson1("example"),
        clock = { 1_700_000_000_000 },
        random = { 1.0 },
        sleep = {},
    )
}

class RestJsonProtocolTest {

    /** The null target prefix is the whole difference from awsJson, and it reaches the wire. */
    @Test
    fun restJson1SendsNoTargetHeaderAndAPlainJsonContentType() = runTest {
        val h = RestHarness()
        restClient(h).callRestJson(
            method = "POST",
            path = "/things/one",
            request = Body("x"),
            requestSerializer = Body.serializer(),
            responseSerializer = Answer.serializer(),
        )

        val request = h.requests.single()
        assertNull(request.headers["X-Amz-Target"])
        assertEquals("application/json", request.body.contentType?.toString()?.substringBefore(";"))
        assertEquals("/things/one", request.url.encodedPath)
        assertEquals("""{"Value":"x"}""", h.bodies.single())
    }

    @Test
    fun sendsTheMethodPathAndQueryItWasGiven() = runTest {
        val h = RestHarness()
        restClient(h).callRestJsonNoBody(
            method = "DELETE",
            path = "/things/one",
            responseSerializer = Answer.serializer(),
            query = listOf("group" to "g1", "token" to "t1"),
        )

        val request = h.requests.single()
        assertEquals("DELETE", request.method.value)
        assertEquals("g1", request.url.parameters["group"])
        assertEquals("t1", request.url.parameters["token"])
    }

    /**
     * A signed zero-length payload hashes to AWS's documented empty-body SHA-256; `{}` hashes to
     * something else and arrives with a content type the service did not expect on a GET.
     */
    @Test
    fun theNoBodyVariantSendsNothingRatherThanAnEmptyObject() = runTest {
        val h = RestHarness()
        restClient(h).callRestJsonNoBody(
            method = "GET",
            path = "/things",
            responseSerializer = Answer.serializer(),
        )
        assertEquals("", h.bodies.single())
    }

    /**
     * A 204, or a 200 with an empty body, is a legitimate answer to a DELETE. Decoding "" throws a
     * SerializationException naming a JSON parse position, which describes nothing a caller can act
     * on; "{}" deserializes to every field at its default, which is what an empty answer means.
     */
    @Test
    fun anEmptyResponseBodyDeserializesToDefaultsRatherThanThrowing() = runTest {
        val h = RestHarness()
        val answer = restClient(h, "" to HttpStatusCode.NoContent).callRestJsonNoBody(
            method = "DELETE",
            path = "/things/one",
            responseSerializer = Answer.serializer(),
        )
        assertNull(answer.result)
        assertEquals(0, answer.count)
    }

    @Test
    fun aWhitespaceOnlyBodyIsTreatedTheSameWay() = runTest {
        val h = RestHarness()
        val answer = restClient(h, "   \n" to HttpStatusCode.OK).callRestJsonNoBody(
            method = "GET",
            path = "/things",
            responseSerializer = Answer.serializer(),
        )
        assertEquals(0, answer.count)
    }

    @Test
    fun deserializesAPopulatedResponse() = runTest {
        val h = RestHarness()
        val answer = restClient(h, """{"Result":"ok","Count":3}""" to HttpStatusCode.OK).callRestJson(
            method = "PUT",
            path = "/things/one",
            request = Body("x"),
            requestSerializer = Body.serializer(),
            responseSerializer = Answer.serializer(),
        )
        assertEquals("ok", answer.result)
        assertEquals(3, answer.count)
    }

    /** The path is signed and sent byte-for-byte; encoding is the service module's job. */
    @Test
    fun passesThePathThroughWithoutReEncodingIt() = runTest {
        val h = RestHarness()
        restClient(h).callRestJsonNoBody(
            method = "GET",
            path = "/things/a%2Fb",
            responseSerializer = Answer.serializer(),
        )
        assertEquals("/things/a%2Fb", h.requests.single().url.encodedPath)
    }

    @Test
    fun errorsStillArriveAsAwsServiceException() = runTest {
        val h = RestHarness()
        val failure = runCatching {
            restClient(h, """{"__type":"ValidationException","message":"no"}""" to HttpStatusCode.BadRequest)
                .callRestJsonNoBody("GET", "/things", Answer.serializer())
        }.exceptionOrNull()

        assertTrue(failure is AwsServiceException, "expected an AwsServiceException, got $failure")
        assertEquals("ValidationException", failure.code)
    }
}

class AwsQueryProtocolTest {

    /** SNS's protocol: form content type, no target header, and XML error parsing. */
    @Test
    fun awsQueryDeclaresTheFormContentTypeAndNoTarget() {
        val protocol = AwsProtocol.awsQuery("sns")
        assertEquals("application/x-www-form-urlencoded", protocol.contentType)
        assertNull(protocol.targetPrefix)
        assertEquals("sns", protocol.endpointPrefix)
        assertEquals("sns", protocol.signingName)
        // Named for restXml, but a query-protocol error is <Error><Code>…</Code></Error>, which is
        // exactly what that parser scans for — and it is what the AWS SDK uses here too.
        assertTrue(protocol.errorParser === RestXmlErrorParser)
    }

    @Test
    fun awsQueryErrorsParseOutOfTheQueryProtocolsErrorEnvelope() {
        val details = RestXmlErrorParser.parse(
            400,
            emptyMap(),
            """<ErrorResponse><Error><Type>Sender</Type><Code>NotFound</Code>
               <Message>Topic does not exist</Message></Error></ErrorResponse>""".encodeToByteArray(),
        )
        assertEquals("NotFound", details.code)
        assertEquals("Topic does not exist", details.message)
    }

    @Test
    fun restJson1DeclaresPlainJsonAndReusesTheAwsJsonErrorParser() {
        val protocol = AwsProtocol.restJson1("scheduler")
        assertEquals("application/json", protocol.contentType)
        assertNull(protocol.targetPrefix)
        assertTrue(protocol.errorParser === AwsJsonErrorParser)
    }
}
