package com.steamstreet.awskt.ses

import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.StaticCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class SesHarness {
    val requests = mutableListOf<HttpRequestData>()
    val bodies = mutableListOf<String>()
}

internal fun harnessSes(
    harness: SesHarness,
    retryConfig: RetryConfig = RetryConfig(),
    responder: (Int) -> Pair<String, HttpStatusCode>,
): Ses {
    var call = 0
    val engine = MockEngine { request ->
        harness.requests += request
        harness.bodies += (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
        val (body, status) = responder(call++)
        respond(body, status)
    }
    return DefaultSes(
        AwsServiceClient(
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET", "TOKEN")),
            endpoint = resolveEndpoint("email", "us-west-2", "https://email.us-west-2.amazonaws.com"),
            region = "us-west-2",
            protocol = SES_PROTOCOL,
            retryConfig = retryConfig,
            clock = { 1_700_000_000_000 },
            random = { 1.0 },
            sleep = {},
        ),
    )
}

/**
 * A transport that dies **after** the request went out, which is the ambiguous failure the whole
 * `NOT_IDEMPOTENT` decision is about: SES may or may not have accepted the message, and nobody can
 * tell. `classifyTransportFailure` answers AMBIGUOUS for an unrecognised throwable, which is what
 * this raises.
 */
internal fun ambiguouslyFailingSes(harness: SesHarness): Ses {
    val engine = MockEngine { request ->
        harness.requests += request
        throw IllegalStateException("connection reset")
    }
    return DefaultSes(
        AwsServiceClient(
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET", "TOKEN")),
            endpoint = resolveEndpoint("email", "us-west-2", "https://email.us-west-2.amazonaws.com"),
            region = "us-west-2",
            protocol = SES_PROTOCOL,
            clock = { 1_700_000_000_000 },
            random = { 1.0 },
            sleep = {},
        ),
    )
}

private fun bodyJson(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

private const val SEND_OK = """{"MessageId":"0100018f-1a2b-3c4d-5e6f-000000000000-000000"}"""

private val SIGN_IN_CODE = SendEmailRequest(
    content = EmailContent(
        simple = Message(
            subject = Content("123456 is your sign-in code"),
            body = Body(text = Content("Your code is 123456"), html = Content("<p>123456</p>")),
        ),
    ),
    fromEmailAddress = "WrestleVRS <no-reply@example.com>",
    destination = Destination(toAddresses = listOf("player@example.com")),
)

class SesProtocolTest {

    /**
     * restJson1 addresses by method and path, not by `X-Amz-Target`. A target header here would be
     * ignored by the service and would mean `AwsProtocol.restJson1` had been given a target prefix.
     */
    @Test
    fun addressesByPathWithNoTargetHeader() = runTest {
        val h = SesHarness()
        harnessSes(h) { SEND_OK to HttpStatusCode.OK }.sendEmail(SIGN_IN_CODE)

        val request = h.requests.single()
        assertNull(request.headers["X-Amz-Target"])
        assertEquals("POST", request.method.value)
        assertEquals("/v2/email/outbound-emails", request.url.encodedPath)
        assertEquals("application/json", request.body.contentType?.toString()?.substringBefore(";"))
    }

    /**
     * The host comes from the endpoint prefix `email` and the signature from the signing name `ses`.
     * They differ, and signing against the host name is a `SignatureDoesNotMatch` on every call that
     * says nothing about which of the two is wrong.
     */
    @Test
    fun signsAsSesWhileAddressingEmail() = runTest {
        val h = SesHarness()
        harnessSes(h) { SEND_OK to HttpStatusCode.OK }.sendEmail(SIGN_IN_CODE)

        val request = h.requests.single()
        assertEquals("email.us-west-2.amazonaws.com", request.url.host)
        val authorization = request.headers["Authorization"]!!
        assertContains(authorization, "/us-west-2/ses/aws4_request")
    }

    /** `SendEmail` has no path or query parameters at all; everything rides in the body. */
    @Test
    fun sendsNoQueryParameters() = runTest {
        val h = SesHarness()
        harnessSes(h) { SEND_OK to HttpStatusCode.OK }.sendEmail(SIGN_IN_CODE)

        assertTrue(h.requests.single().url.parameters.isEmpty(), "SendEmail takes no query parameters")
    }

    @Test
    fun serializesTheBodyInPascalCaseAndOmitsNulls() = runTest {
        val h = SesHarness()
        harnessSes(h) { SEND_OK to HttpStatusCode.OK }.sendEmail(SIGN_IN_CODE)

        val body = bodyJson(h.bodies.single())
        assertEquals("WrestleVRS <no-reply@example.com>", body["FromEmailAddress"]?.jsonPrimitive?.content)
        assertEquals(
            "player@example.com",
            body["Destination"]!!.jsonObject["ToAddresses"]!!.jsonArray.single().jsonPrimitive.content,
        )

        val simple = body["Content"]!!.jsonObject["Simple"]!!.jsonObject
        assertEquals(
            "123456 is your sign-in code",
            simple["Subject"]!!.jsonObject["Data"]?.jsonPrimitive?.content,
        )
        val messageBody = simple["Body"]!!.jsonObject
        assertEquals("Your code is 123456", messageBody["Text"]!!.jsonObject["Data"]?.jsonPrimitive?.content)
        assertEquals("<p>123456</p>", messageBody["Html"]!!.jsonObject["Data"]?.jsonPrimitive?.content)

        // Absent optionals must be absent, not null — `encodeDefaults = false` and
        // `explicitNulls = false` in `awsJson` are what make that true, and SES rejects some
        // explicit nulls it accepts as missing.
        assertNull(body["ConfigurationSetName"])
        assertNull(body["EmailTags"])
        assertNull(simple["Subject"]!!.jsonObject["Charset"])
        assertNull(body["Content"]!!.jsonObject["Raw"])
    }

    @Test
    fun parsesTheMessageId() = runTest {
        val response = harnessSes(SesHarness()) { SEND_OK to HttpStatusCode.OK }.sendEmail(SIGN_IN_CODE)

        assertEquals("0100018f-1a2b-3c4d-5e6f-000000000000-000000", response.messageId)
    }

    /** AWS adding a response field must be a non-event, not a deserialization failure in production. */
    @Test
    fun ignoresUnknownResponseFields(): Unit = runTest {
        val response = harnessSes(SesHarness()) {
            """{"MessageId":"abc","SomethingAwsAddedLater":{"nested":true}}""" to HttpStatusCode.OK
        }.sendEmail(SIGN_IN_CODE)

        assertEquals("abc", response.messageId)
    }

    @Test
    fun carriesTheOptionalTopLevelFieldsWhenSet() = runTest {
        val h = SesHarness()
        harnessSes(h) { SEND_OK to HttpStatusCode.OK }.sendEmail(
            SIGN_IN_CODE.copy(
                configurationSetName = "transactional",
                replyToAddresses = listOf("support@example.com"),
                emailTags = listOf(MessageTag("purpose", "sign-in")),
                listManagementOptions = ListManagementOptions("players", "announcements"),
            ),
        )

        val body = bodyJson(h.bodies.single())
        assertEquals("transactional", body["ConfigurationSetName"]?.jsonPrimitive?.content)
        assertEquals(
            "support@example.com",
            body["ReplyToAddresses"]!!.jsonArray.single().jsonPrimitive.content,
        )
        val tag = body["EmailTags"]!!.jsonArray.single().jsonObject
        assertEquals("purpose", tag["Name"]?.jsonPrimitive?.content)
        assertEquals("sign-in", tag["Value"]?.jsonPrimitive?.content)
        assertEquals(
            "players",
            body["ListManagementOptions"]!!.jsonObject["ContactListName"]?.jsonPrimitive?.content,
        )
    }
}

/**
 * The module's one real design decision: SES has no client token, so a replayed send is a second
 * email in somebody's inbox.
 */
class SendEmailIsNotIdempotentTest {

    /**
     * The case the decision exists for. The request went out and the socket died before an answer
     * came back — nobody can tell whether SES accepted the message — and under `NOT_IDEMPOTENT`
     * `aws-core` surfaces that rather than replaying it.
     *
     * If this ever asserts 2 attempts, someone has changed `safety` back to the defaulted
     * `IDEMPOTENT` and the symptom in production is duplicate mail, which nothing else here catches.
     */
    @Test
    fun doesNotReplayAnAmbiguousTransportFailure() = runTest {
        val h = SesHarness()
        assertFailsWith<IllegalStateException> {
            ambiguouslyFailingSes(h).sendEmail(SIGN_IN_CODE)
        }

        assertEquals(
            1,
            h.requests.size,
            "an ambiguous send was replayed; that is a duplicate email, not a retry",
        )
    }

    /**
     * What `NOT_IDEMPOTENT` deliberately does *not* stop, asserted so the guarantee is not read more
     * broadly than it is. A 500 is an answer rather than an ambiguity, and by SES's contract an
     * answer that is not a 200 did not accept the message — so it is still retried.
     */
    @Test
    fun stillRetriesAServiceError() = runTest {
        val h = SesHarness()
        val response = harnessSes(h) { call ->
            if (call == 0) """{"__type":"InternalServerError"}""" to HttpStatusCode.InternalServerError
            else SEND_OK to HttpStatusCode.OK
        }.sendEmail(SIGN_IN_CODE)

        assertEquals(2, h.requests.size, "a 500 is an answer, and answers are retried")
        assertEquals("0100018f-1a2b-3c4d-5e6f-000000000000-000000", response.messageId)
    }

    /** A throttle is likewise an answer: SES rejected the request, so nothing was sent to duplicate. */
    @Test
    fun stillRetriesAThrottle() = runTest {
        val h = SesHarness()
        harnessSes(h) { call ->
            if (call == 0) {
                """{"__type":"TooManyRequestsException","message":"slow down"}""" to
                    HttpStatusCode.TooManyRequests
            } else {
                SEND_OK to HttpStatusCode.OK
            }
        }.sendEmail(SIGN_IN_CODE)

        assertEquals(2, h.requests.size, "a 429 was not retried")
    }
}

class SesErrorMappingTest {

    private fun errorBody(code: String) = """{"__type":"$code","message":"nope"}"""

    private suspend fun failingWith(code: String, status: HttpStatusCode): Throwable = runCatching {
        harnessSes(SesHarness(), RetryConfig(maxAttempts = 1)) { errorBody(code) to status }
            .sendEmail(SIGN_IN_CODE)
    }.exceptionOrNull()!!

    @Test
    fun mapsKnownCodesOntoTypedExceptions() = runTest {
        assertTrue(failingWith("MessageRejected", HttpStatusCode.BadRequest) is MessageRejected)
        assertTrue(failingWith("BadRequestException", HttpStatusCode.BadRequest) is BadRequestException)
        assertTrue(
            failingWith("AccountSuspendedException", HttpStatusCode.BadRequest) is AccountSuspendedException,
        )
        assertTrue(
            failingWith("SendingPausedException", HttpStatusCode.BadRequest) is SendingPausedException,
        )
        assertTrue(
            failingWith("MailFromDomainNotVerifiedException", HttpStatusCode.BadRequest)
                is MailFromDomainNotVerifiedException,
        )
        assertTrue(failingWith("NotFoundException", HttpStatusCode.NotFound) is NotFoundException)
        assertTrue(
            failingWith("TooManyRequestsException", HttpStatusCode.TooManyRequests)
                is TooManyRequestsException,
        )
    }

    /**
     * `MessageRejected` is the one SES shape here with **no `Exception` suffix**. A map keyed on
     * `MessageRejectedException` compiles and silently downgrades the most common send failure to
     * the base type, so the exact string is pinned rather than only the type.
     */
    @Test
    fun mapsMessageRejectedWithoutAnExceptionSuffix() = runTest {
        val failure = failingWith("MessageRejected", HttpStatusCode.BadRequest)

        assertTrue(failure is MessageRejected)
        assertEquals("MessageRejected", failure.code)
    }

    /**
     * A restJson1 service normally reports its code in the `x-amzn-errortype` **header** rather than
     * the body, and `AwsJsonErrorParser` reads that first. Asserted because this module relies on it
     * and adds no parser of its own.
     */
    @Test
    fun readsTheCodeFromTheErrorTypeHeader() = runTest {
        val engine = MockEngine {
            respond("{}", HttpStatusCode.BadRequest, headersOf("x-amzn-errortype", "MessageRejected"))
        }
        val ses = DefaultSes(
            AwsServiceClient(
                httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
                credentialsProvider = StaticCredentialsProvider(AwsCredentials("A", "S", "T")),
                endpoint = resolveEndpoint("email", "us-west-2", "https://email.us-west-2.amazonaws.com"),
                region = "us-west-2",
                protocol = SES_PROTOCOL,
                clock = { 1_700_000_000_000 },
                random = { 1.0 },
                sleep = {},
            ),
        )

        assertFailsWith<MessageRejected> { ses.sendEmail(SIGN_IN_CODE) }
    }

    @Test
    fun unknownCodesFallThroughToTheBaseType() = runTest {
        val failure = assertFailsWith<SesException> {
            harnessSes(SesHarness()) {
                errorBody("AccessDeniedException") to HttpStatusCode.Forbidden
            }.sendEmail(SIGN_IN_CODE)
        }

        assertEquals("AccessDeniedException", failure.code)
        assertTrue(failure.cause != null, "the transport exception was discarded")
    }
}

class SendSimpleEmailTest {

    @Test
    fun buildsTheNestedRequestAndDefaultsToUtf8() = runTest {
        val h = SesHarness()
        harnessSes(h) { SEND_OK to HttpStatusCode.OK }.sendSimpleEmail(
            from = "WrestleVRS <no-reply@example.com>",
            to = listOf("player@example.com"),
            subject = "123456 is your sign-in code",
            text = "Your code is 123456",
            html = "<p>123456</p>",
        )

        val body = bodyJson(h.bodies.single())
        assertEquals("WrestleVRS <no-reply@example.com>", body["FromEmailAddress"]?.jsonPrimitive?.content)
        val simple = body["Content"]!!.jsonObject["Simple"]!!.jsonObject
        val subject = simple["Subject"]!!.jsonObject
        assertEquals("123456 is your sign-in code", subject["Data"]?.jsonPrimitive?.content)
        // Without this, SES falls back to 7-bit ASCII and a subject with a curly quote arrives mangled.
        assertEquals("UTF-8", subject["Charset"]?.jsonPrimitive?.content)
        assertEquals(
            "UTF-8",
            simple["Body"]!!.jsonObject["Html"]!!.jsonObject["Charset"]?.jsonPrimitive?.content,
        )
    }

    /** Passing null charset gets the API's own default back, rather than being overridden. */
    @Test
    fun omitsTheCharsetWhenAskedTo() = runTest {
        val h = SesHarness()
        harnessSes(h) { SEND_OK to HttpStatusCode.OK }.sendSimpleEmail(
            from = "no-reply@example.com",
            to = listOf("player@example.com"),
            subject = "plain",
            text = "plain",
            charset = null,
        )

        val simple = bodyJson(h.bodies.single())["Content"]!!.jsonObject["Simple"]!!.jsonObject
        assertNull(simple["Subject"]!!.jsonObject["Charset"])
    }

    @Test
    fun sendsOnlyTheBodyPartsGiven() = runTest {
        val h = SesHarness()
        harnessSes(h) { SEND_OK to HttpStatusCode.OK }.sendSimpleEmail(
            from = "no-reply@example.com",
            to = listOf("player@example.com"),
            subject = "text only",
            text = "text only",
        )

        val messageBody = bodyJson(h.bodies.single())["Content"]!!
            .jsonObject["Simple"]!!.jsonObject["Body"]!!.jsonObject
        assertEquals("text only", messageBody["Text"]!!.jsonObject["Data"]?.jsonPrimitive?.content)
        assertNull(messageBody["Html"])
    }

    /** Both of these are a `BadRequestException` from SES; failing here names the problem for free. */
    @Test
    fun refusesARequestSesWouldRejectAnyway() = runTest {
        val ses = harnessSes(SesHarness()) { SEND_OK to HttpStatusCode.OK }

        assertContains(
            assertFailsWith<IllegalArgumentException> {
                ses.sendSimpleEmail("a@example.com", emptyList(), "s", text = "t")
            }.message!!,
            "recipient",
        )
        assertContains(
            assertFailsWith<IllegalArgumentException> {
                ses.sendSimpleEmail("a@example.com", listOf("b@example.com"), "s")
            }.message!!,
            "neither",
        )
    }
}
