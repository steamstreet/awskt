package com.steamstreet.awskt.lambda

import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.StaticCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class LambdaHarness {
    val requests = mutableListOf<HttpRequestData>()
    val bodies = mutableListOf<ByteArray>()
}

/** One canned answer: body, status, and the headers Lambda carries the interesting things in. */
internal class Answer(
    val body: ByteArray = ByteArray(0),
    val status: HttpStatusCode = HttpStatusCode.OK,
    val headers: Headers = Headers.Empty,
) {
    constructor(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        headers: Headers = Headers.Empty,
    ) : this(body.encodeToByteArray(), status, headers)
}

internal fun harnessLambda(
    harness: LambdaHarness,
    retryConfig: RetryConfig = RetryConfig(),
    responder: (Int) -> Answer,
): Lambda {
    var call = 0
    val engine = MockEngine { request ->
        harness.requests += request
        harness.bodies += (request.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)
        val answer = responder(call++)
        respond(ByteReadChannel(answer.body), answer.status, answer.headers)
    }
    return DefaultLambda(
        AwsServiceClient(
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET", "TOKEN")),
            endpoint = resolveEndpoint("lambda", "us-west-2", "https://lambda.us-west-2.amazonaws.com"),
            region = "us-west-2",
            protocol = LAMBDA_PROTOCOL,
            retryConfig = retryConfig,
            clock = { 1_700_000_000_000 },
            random = { 1.0 },
            sleep = {},
        ),
    )
}

/**
 * A transport that dies **after** the request went out — the ambiguous failure that
 * [InvokeRequest.safety] is entirely about. `classifyTransportFailure` answers AMBIGUOUS for an
 * unrecognised throwable, which is what this raises.
 */
internal fun ambiguouslyFailingLambda(harness: LambdaHarness): Lambda {
    val engine = MockEngine { request ->
        harness.requests += request
        throw IllegalStateException("connection reset")
    }
    return DefaultLambda(
        AwsServiceClient(
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET", "TOKEN")),
            endpoint = resolveEndpoint("lambda", "us-west-2", "https://lambda.us-west-2.amazonaws.com"),
            region = "us-west-2",
            protocol = LAMBDA_PROTOCOL,
            clock = { 1_700_000_000_000 },
            random = { 1.0 },
            sleep = {},
        ),
    )
}

private const val RESULT = """{"ok":true}"""

class InvokeProtocolTest {

    /** restJson1 addresses by method and path. A target header would mean the protocol is wrong. */
    @Test
    fun addressesTheInvocationPathWithNoTargetHeader() = runTest {
        val h = LambdaHarness()
        harnessLambda(h) { Answer(RESULT) }.invoke(InvokeRequest("worker"))

        val request = h.requests.single()
        assertNull(request.headers["X-Amz-Target"])
        assertEquals("POST", request.method.value)
        assertEquals("/2015-03-31/functions/worker/invocations", request.url.encodedPath)
        assertEquals("application/json", request.body.contentType?.toString()?.substringBefore(";"))
    }

    /**
     * The payload is the body, verbatim — not wrapped in a JSON envelope, not base64'd.
     *
     * `Payload` is an `@httpPayload` blob, so anything else here would reach the handler as
     * something it did not send.
     */
    @Test
    fun sendsThePayloadAsTheBodyVerbatim() = runTest {
        val h = LambdaHarness()
        harnessLambda(h) { Answer(RESULT) }
            .invoke(InvokeRequest("worker", """{"job":"nightly"}""".encodeToByteArray()))

        assertEquals("""{"job":"nightly"}""", h.bodies.single().decodeToString())
    }

    /** A null payload sends an empty body, which a function receives as `{}`. */
    @Test
    fun sendsAnEmptyBodyForANullPayload() = runTest {
        val h = LambdaHarness()
        harnessLambda(h) { Answer(RESULT) }.invoke(InvokeRequest("worker"))

        assertEquals(0, h.bodies.single().size)
    }

    @Test
    fun sendsTheInvocationTypeAndOmitsLogTypeWhenNone() = runTest {
        val h = LambdaHarness()
        harnessLambda(h) { Answer(RESULT) }.invoke(InvokeRequest("worker"))

        val request = h.requests.single()
        assertEquals("RequestResponse", request.headers["X-Amz-Invocation-Type"])
        assertNull(request.headers["X-Amz-Log-Type"])
        assertNull(request.headers["X-Amz-Client-Context"])
    }

    @Test
    fun sendsTheLogTypeWhenTailIsRequested() = runTest {
        val h = LambdaHarness()
        harnessLambda(h) { Answer(RESULT) }
            .invoke(InvokeRequest("worker", logType = LogType.TAIL))

        assertEquals("Tail", h.requests.single().headers["X-Amz-Log-Type"])
    }

    @Test
    fun sendsTheQualifierAsAQueryParameter() = runTest {
        val h = LambdaHarness()
        harnessLambda(h) { Answer(RESULT) }.invoke(InvokeRequest("worker", qualifier = "PROD"))

        assertEquals("PROD", h.requests.single().url.parameters["Qualifier"])
    }

    /**
     * A function addressed by ARN or by `name:alias` carries `:`, which occupies one path segment
     * and must reach Lambda encoded — the same reasoning as `aws-bedrock-runtime`'s model ids.
     */
    @Test
    fun percentEncodesTheFunctionName() = runTest {
        val h = LambdaHarness()
        val lambda = harnessLambda(h) { Answer(RESULT) }
        lambda.invoke(InvokeRequest("worker:PROD"))
        lambda.invoke(InvokeRequest("arn:aws:lambda:us-west-2:1:function:worker"))

        assertEquals(
            listOf(
                "/2015-03-31/functions/worker%3APROD/invocations",
                "/2015-03-31/functions/arn%3Aaws%3Alambda%3Aus-west-2%3A1%3Afunction%3Aworker/invocations",
            ),
            h.requests.map { it.url.encodedPath },
        )
    }

    /** Raw JSON in, base64 on the wire — see `InvokeRequest.clientContextJson`. */
    @Test
    fun base64EncodesTheClientContext() = runTest {
        val h = LambdaHarness()
        harnessLambda(h) { Answer(RESULT) }
            .invoke(InvokeRequest("worker", clientContextJson = """{"custom":{"trace":"abc"}}"""))

        val header = h.requests.single().headers["X-Amz-Client-Context"]!!
        assertEquals("""{"custom":{"trace":"abc"}}""", Base64.Default.decode(header).decodeToString())
    }
}

class InvokeLocalLimitsTest {

    @Test
    fun refusesAnOversizedSynchronousPayloadBeforeSending() = runTest {
        val h = LambdaHarness()
        val failure = assertFailsWith<LambdaPayloadTooLargeException> {
            harnessLambda(h) { Answer(RESULT) }
                .invoke(InvokeRequest("worker", ByteArray(MAX_SYNC_PAYLOAD_BYTES + 1)))
        }

        assertTrue(h.requests.isEmpty(), "nothing should have been uploaded")
        assertContains(failure.message!!, "REQUEST_RESPONSE")
    }

    /** The asynchronous ceiling is 24× smaller, so the same payload passes one check and fails the other. */
    @Test
    fun appliesTheSmallerCeilingToAnEventInvocation() = runTest {
        val h = LambdaHarness()
        val payload = ByteArray(MAX_EVENT_PAYLOAD_BYTES + 1)

        harnessLambda(h) { Answer(status = HttpStatusCode.Accepted) }
            .invoke(InvokeRequest("worker", payload))
        assertEquals(1, h.requests.size)

        assertFailsWith<LambdaPayloadTooLargeException> {
            harnessLambda(h) { Answer(status = HttpStatusCode.Accepted) }
                .invoke(InvokeRequest("worker", payload, invocationType = InvocationType.EVENT))
        }
        assertEquals(1, h.requests.size)
    }

    /** The limit Lambda applies is on the *encoded* context, so that is what is measured. */
    @Test
    fun refusesAnOversizedClientContext() = runTest {
        val h = LambdaHarness()
        val failure = assertFailsWith<LambdaPayloadTooLargeException> {
            harnessLambda(h) { Answer(RESULT) }.invoke(
                InvokeRequest("worker", clientContextJson = "x".repeat(MAX_CLIENT_CONTEXT_BYTES)),
            )
        }

        assertTrue(h.requests.isEmpty())
        assertContains(failure.message!!, "X-Amz-Client-Context")
    }
}

class InvokeResponseTest {

    @Test
    fun readsThePayloadAndTheExecutedVersion() = runTest {
        val h = LambdaHarness()
        val response = harnessLambda(h) {
            Answer(RESULT, headers = headersOf("X-Amz-Executed-Version", "7"))
        }.invoke(InvokeRequest("worker"))

        assertEquals(200, response.statusCode)
        assertEquals(RESULT, response.payloadText)
        assertEquals("7", response.executedVersion)
        assertNull(response.functionError)
        assertTrue(!response.isFunctionError)
    }

    @Test
    fun anEventInvocationAnswers202WithNoPayload() = runTest {
        val h = LambdaHarness()
        val response = harnessLambda(h) { Answer(status = HttpStatusCode.Accepted) }
            .invoke(InvokeRequest("worker", invocationType = InvocationType.EVENT))

        assertEquals(202, response.statusCode)
        assertEquals("", response.payloadText)
    }

    @Test
    fun decodesTheLogTail() = runTest {
        val h = LambdaHarness()
        val logs = "START RequestId: 1\nhello\nEND RequestId: 1\n"
        val response = harnessLambda(h) {
            Answer(RESULT, headers = headersOf("X-Amz-Log-Result", Base64.Default.encode(logs.encodeToByteArray())))
        }.invoke(InvokeRequest("worker", logType = LogType.TAIL))

        assertEquals(logs, response.logTail)
    }

    /** A malformed log header must not fail an invocation that otherwise worked. */
    @Test
    fun answersNullForAMalformedLogTail() = runTest {
        val h = LambdaHarness()
        val response = harnessLambda(h) {
            Answer(RESULT, headers = headersOf("X-Amz-Log-Result", "not base64 !!!"))
        }.invoke(InvokeRequest("worker", logType = LogType.TAIL))

        assertNull(response.logTail)
    }

    @Test
    fun logTailIsNullWhenNoneWasRequested() = runTest {
        val h = LambdaHarness()
        assertNull(harnessLambda(h) { Answer(RESULT) }.invoke(InvokeRequest("worker")).logTail)
    }
}

/**
 * The distinction the whole module is organised around: a function that failed is a **successful
 * invocation**, answered `200`, and nothing in the transport treats it as an error.
 */
class FunctionErrorTest {

    private val errorPayload =
        """{"errorType":"TypeError","errorMessage":"x is not a function","stackTrace":["at h"]}"""

    private fun failing(h: LambdaHarness, error: String = "Unhandled", body: String = errorPayload) =
        harnessLambda(h) { Answer(body, headers = headersOf("X-Amz-Function-Error", error)) }

    @Test
    fun aFailedFunctionStillAnswers200AndDoesNotThrow() = runTest {
        val h = LambdaHarness()
        val response = failing(h).invoke(InvokeRequest("worker"))

        assertEquals(200, response.statusCode)
        assertTrue(response.isFunctionError)
        assertEquals("Unhandled", response.functionError)
        assertEquals(errorPayload, response.payloadText)
    }

    @Test
    fun orThrowRaisesTheParsedError() = runTest {
        val h = LambdaHarness()
        val response = failing(h, error = "Handled").invoke(InvokeRequest("worker"))

        val failure = assertFailsWith<FunctionErrorException> { response.orThrow() }
        assertEquals("Handled", failure.functionError)
        assertEquals("TypeError", failure.errorType)
        assertEquals("x is not a function", failure.errorMessage)
        assertEquals(errorPayload, failure.payload.decodeToString())
        assertContains(failure.message!!, "x is not a function")
    }

    /** A custom runtime answers with whatever it likes. The bytes survive; the parsed fields do not. */
    @Test
    fun orThrowKeepsThePayloadWhenItIsNotTheManagedRuntimeShape() = runTest {
        val h = LambdaHarness()
        val response = failing(h, body = "kaboom").invoke(InvokeRequest("worker"))

        val failure = assertFailsWith<FunctionErrorException> { response.orThrow() }
        assertNull(failure.errorType)
        assertNull(failure.errorMessage)
        assertEquals("kaboom", failure.payload.decodeToString())
        assertContains(failure.message!!, "kaboom")
    }

    @Test
    fun orThrowIsAPassThroughOnSuccess() = runTest {
        val h = LambdaHarness()
        val response = harnessLambda(h) { Answer(RESULT) }.invoke(InvokeRequest("worker"))
        assertEquals(response, response.orThrow())
    }
}

class ConvenienceTest {

    @Test
    fun invokeFunctionRoundTripsText() = runTest {
        val h = LambdaHarness()
        val answer = harnessLambda(h) { Answer(RESULT) }.invokeFunction("worker", """{"job":"n"}""")

        assertEquals(RESULT, answer)
        assertEquals("""{"job":"n"}""", h.bodies.single().decodeToString())
        assertEquals("RequestResponse", h.requests.single().headers["X-Amz-Invocation-Type"])
    }

    /** The whole reason the convenience exists: it cannot silently return an error payload. */
    @Test
    fun invokeFunctionThrowsWhenTheFunctionFailed() = runTest {
        val h = LambdaHarness()
        val lambda = harnessLambda(h) {
            Answer("""{"errorMessage":"boom"}""", headers = headersOf("X-Amz-Function-Error", "Unhandled"))
        }

        val failure = assertFailsWith<FunctionErrorException> { lambda.invokeFunction("worker") }
        assertEquals("boom", failure.errorMessage)
    }

    @Test
    fun invokeEventQueuesAsynchronously() = runTest {
        val h = LambdaHarness()
        harnessLambda(h) { Answer(status = HttpStatusCode.Accepted) }.invokeEvent("worker", "{}")

        assertEquals("Event", h.requests.single().headers["X-Amz-Invocation-Type"])
    }

    @Test
    fun dryRunValidatesWithoutRunning() = runTest {
        val h = LambdaHarness()
        harnessLambda(h) { Answer(status = HttpStatusCode.NoContent) }.dryRun("worker", "PROD")

        val request = h.requests.single()
        assertEquals("DryRun", request.headers["X-Amz-Invocation-Type"])
        assertEquals("PROD", request.url.parameters["Qualifier"])
        assertEquals(0, h.bodies.single().size)
    }
}

class InvokeErrorMappingTest {

    private suspend fun failWith(code: String, status: HttpStatusCode, body: String = "{}"): LambdaException {
        val h = LambdaHarness()
        val lambda = harnessLambda(h, RetryConfig(maxAttempts = 1)) {
            Answer(body, status, headersOf("x-amzn-errortype", code))
        }
        return assertFailsWith<LambdaException> { lambda.invoke(InvokeRequest("worker")) }
    }

    @Test
    fun mapsAMissingFunction() = runTest {
        val failure = failWith("ResourceNotFoundException", HttpStatusCode.NotFound)
        assertTrue(failure is ResourceNotFoundException)
        assertEquals(404, failure.statusCode)
    }

    @Test
    fun mapsAnOversizedRequest() = runTest {
        assertTrue(failWith("RequestTooLargeException", HttpStatusCode.PayloadTooLarge) is RequestTooLargeException)
    }

    @Test
    fun mapsARecursionStop() = runTest {
        assertTrue(
            failWith("RecursiveInvocationException", HttpStatusCode.BadRequest) is RecursiveInvocationException,
        )
    }

    @Test
    fun mapsAKmsFailureOntoOneTypeThatKeepsTheCode() = runTest {
        val failure = failWith("KMSDisabledException", HttpStatusCode.BadRequest)
        assertTrue(failure is KmsException)
        assertEquals("KMSDisabledException", failure.code)
    }

    /** `Reason` is what tells an account ceiling apart from a per-function one. */
    @Test
    fun keepsTheThrottlingReason() = runTest {
        val failure = failWith(
            "TooManyRequestsException",
            HttpStatusCode.TooManyRequests,
            """{"Type":"User","message":"Rate Exceeded.","Reason":"ConcurrentInvocationLimitExceeded"}""",
        )
        assertEquals("ConcurrentInvocationLimitExceeded", (failure as TooManyRequestsException).reason)
    }

    /** An unparseable throttle body must not become a deserialization failure. */
    @Test
    fun toleratesAThrottleBodyWithNoReason() = runTest {
        val failure = failWith("TooManyRequestsException", HttpStatusCode.TooManyRequests, "not json")
        assertNull((failure as TooManyRequestsException).reason)
    }

    /** An unmodelled code still arrives typed, rather than as a bare transport failure. */
    @Test
    fun fallsThroughForAnUnknownCode() = runTest {
        val failure = failWith("AccessDeniedException", HttpStatusCode.Forbidden)
        assertEquals(LambdaException::class, failure::class)
        assertEquals("AccessDeniedException", failure.code)
    }
}

class InvokeRetryTest {

    /** A throttle is `aws-core`'s THROTTLING class, so it is replayed within the budget. */
    @Test
    fun retriesAThrottleAndSucceeds() = runTest {
        val h = LambdaHarness()
        val lambda = harnessLambda(h) { call ->
            if (call < 2) {
                Answer("{}", HttpStatusCode.TooManyRequests, headersOf("x-amzn-errortype", "TooManyRequestsException"))
            } else {
                Answer(RESULT)
            }
        }

        assertEquals(RESULT, lambda.invoke(InvokeRequest("worker")).payloadText)
        assertEquals(3, h.requests.size)
    }

    /**
     * The module's one real design decision. An invocation that may or may not have run is **not**
     * replayed, because replaying it runs the handler a second time.
     */
    @Test
    fun doesNotReplayAnAmbiguousTransportFailure() = runTest {
        val h = LambdaHarness()
        assertFailsWith<IllegalStateException> {
            ambiguouslyFailingLambda(h).invoke(InvokeRequest("worker"))
        }

        assertEquals(1, h.requests.size, "an ambiguous failure must not be retried")
    }

    /** …and a caller who knows their handler is idempotent can opt back in, per request. */
    @Test
    fun replaysWhenTheCallerDeclaresTheHandlerIdempotent() = runTest {
        val h = LambdaHarness()
        assertFailsWith<IllegalStateException> {
            ambiguouslyFailingLambda(h)
                .invoke(InvokeRequest("worker", safety = OperationSafety.IDEMPOTENT))
        }

        assertTrue(h.requests.size > 1, "an idempotent invocation should have been retried")
    }

    /** A `DryRun` runs nothing, so its replay is free — and the convenience says so. */
    @Test
    fun replaysADryRun() = runTest {
        val h = LambdaHarness()
        assertFailsWith<IllegalStateException> { ambiguouslyFailingLambda(h).dryRun("worker") }

        assertTrue(h.requests.size > 1)
    }
}
