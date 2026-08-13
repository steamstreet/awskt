package com.steamstreet.awskt.core

import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
private const val TOKEN = "FQoGZXIvYXdzEExampleSessionToken=="

/** A recorder so tests can assert on how the transport retried, not merely that it did. */
private class Harness(
    val requests: MutableList<HttpRequestData> = mutableListOf(),
    val sleeps: MutableList<Long> = mutableListOf(),
) {
    var now: Long = 1_700_000_000_000
}

private fun harnessClient(
    harness: Harness,
    retryConfig: RetryConfig = RetryConfig(),
    protocol: AwsProtocol = AwsProtocol.awsJson1_0("dynamodb", "DynamoDB_20120810"),
    endpointUrl: String = "https://dynamodb.us-west-2.amazonaws.com",
    handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
): AwsServiceClient {
    val engine = MockEngine { request ->
        harness.requests += request
        handler(request)
    }
    return AwsServiceClient(
        httpClient = HttpClient(engine) {
            followRedirects = false
            expectSuccess = false
        },
        credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKIDEXAMPLE", SECRET, TOKEN)),
        endpoint = parseEndpoint(endpointUrl),
        region = "us-west-2",
        protocol = protocol,
        retryConfig = retryConfig,
        clock = { harness.now },
        random = { 1.0 },
        sleep = { harness.sleeps += it },
    )
}

private fun jsonError(code: String, message: String = "boom") =
    """{"__type":"com.amazon.coral.service#$code","Message":"$message"}"""

class TransportRetryTest {

    @Test
    fun throttlingIsRetriedUpToMaxAttempts() = runTest {
        val harness = Harness()
        val client = harnessClient(harness) {
            respond(jsonError("ProvisionedThroughputExceededException"), HttpStatusCode.BadRequest)
        }

        assertFailsWith<AwsServiceException> { client.callRaw("POST", operation = "GetItem") }
        assertEquals(4, harness.requests.size, "4 attempts = 1 initial + 3 retries")
        assertEquals(3, harness.sleeps.size)
    }

    @Test
    fun validationErrorsAreNotRetried() = runTest {
        val harness = Harness()
        val client = harnessClient(harness) {
            respond(jsonError("ValidationException"), HttpStatusCode.BadRequest)
        }

        val error = assertFailsWith<AwsServiceException> { client.callRaw("POST", operation = "GetItem") }
        assertEquals(1, harness.requests.size)
        assertEquals("ValidationException", error.code)
    }

    /**
     * The whole point of the depth-1 `__type` rule: this body contains `ThrottlingError` inside
     * `CancellationReasons`, and must still produce zero retries.
     */
    @Test
    fun cancelledTransactionsAreNeverRetriedDespiteThrottlingReasons() = runTest {
        val harness = Harness()
        val body = """
            {"__type":"com.amazonaws.dynamodb.v20120810#TransactionCanceledException",
             "Message":"Transaction cancelled",
             "CancellationReasons":[{"Code":"ThrottlingError"},{"Code":"None"}]}
        """.trimIndent()
        val client = harnessClient(harness) { respond(body, HttpStatusCode.BadRequest) }

        val error = assertFailsWith<AwsServiceException> {
            client.callRaw("POST", operation = "TransactWriteItems")
        }
        assertEquals(1, harness.requests.size, "an atomic write must never be replayed")
        assertEquals("TransactionCanceledException", error.code)
    }

    @Test
    fun successAfterARetryReturnsTheBody() = runTest {
        val harness = Harness()
        var call = 0
        val client = harnessClient(harness) {
            if (call++ == 0) respond(jsonError("ThrottlingException"), HttpStatusCode.BadRequest)
            else respond("""{"ok":true}""", HttpStatusCode.OK)
        }

        val response = client.callRaw("POST", operation = "GetItem")
        assertEquals(200, response.status)
        assertEquals("""{"ok":true}""", response.body.decodeToString())
        assertEquals(2, harness.requests.size)
    }

    @Test
    fun retryAfterHeaderIsTreatedAsMilliseconds() = runTest {
        val harness = Harness()
        val client = harnessClient(harness, RetryConfig(maxAttempts = 2)) {
            respond(
                jsonError("ThrottlingException"),
                HttpStatusCode.BadRequest,
                headersOf("x-amz-retry-after", "3000"),
            )
        }

        assertFailsWith<AwsServiceException> { client.callRaw("POST", operation = "GetItem") }
        assertEquals(listOf(3_000L), harness.sleeps, "3000 means 3s, not 3000s")
    }

    @Test
    fun throttlingBacksOffHarderThanTransientFailures() = runTest {
        val throttled = Harness()
        harnessClient(throttled, RetryConfig(maxAttempts = 2)) {
            respond(jsonError("SlowDown"), HttpStatusCode.ServiceUnavailable)
        }.let { runCatching { it.callRaw("POST", operation = "GetItem") } }

        val transient = Harness()
        harnessClient(transient, RetryConfig(maxAttempts = 2)) {
            respond("", HttpStatusCode.ServiceUnavailable)
        }.let { runCatching { it.callRaw("POST", operation = "GetItem") } }

        assertEquals(listOf(1_000L), throttled.sleeps)
        assertEquals(listOf(25L), transient.sleeps)
    }

    @Test
    fun theAggregateDeadlineStopsRetriesBeforeALambdaTimesOut() = runTest {
        val harness = Harness()
        val client = harnessClient(
            harness,
            RetryConfig(maxAttempts = 10, throttlingBaseDelayMillis = 10_000, maxTotalRetryDuration = kotlin.time.Duration.parse("5s")),
        ) { respond(jsonError("ThrottlingException"), HttpStatusCode.BadRequest) }

        assertFailsWith<AwsServiceException> { client.callRaw("POST", operation = "GetItem") }
        assertTrue(harness.sleeps.isEmpty(), "a 10s backoff must not be taken under a 5s deadline")
        assertEquals(1, harness.requests.size)
    }
}

class TransportIdempotencyTest {

    @Test
    fun ambiguousFailuresAreRetriedForIdempotentOperations() = runTest {
        val harness = Harness()
        var call = 0
        val client = harnessClient(harness) {
            if (call++ == 0) throw RuntimeException("read timed out")
            respond("""{"ok":true}""", HttpStatusCode.OK)
        }

        val response = client.callRaw("POST", operation = "Query", safety = OperationSafety.IDEMPOTENT)
        assertEquals(200, response.status)
        assertEquals(2, call)
    }

    /**
     * `UpdateItem` emits unconditional `ADD` and `list_append` expressions. If the bytes reached
     * DynamoDB before the socket died, replaying increments twice — so an ambiguous failure must
     * surface rather than retry.
     */
    @Test
    fun ambiguousFailuresAreNotRetriedForNonIdempotentOperations() = runTest {
        val harness = Harness()
        var call = 0
        val client = harnessClient(harness) {
            call++
            throw RuntimeException("read timed out")
        }

        assertFailsWith<RuntimeException> {
            client.callRaw("POST", operation = "UpdateItem", safety = OperationSafety.NOT_IDEMPOTENT)
        }
        assertEquals(1, call, "an UpdateItem that may have landed must not be replayed")
    }

    @Test
    fun provablyUnsentFailuresAreRetriedEvenForNonIdempotentOperations() = runTest {
        val harness = Harness()
        var call = 0
        val client = harnessClient(harness) {
            if (call++ == 0) throw RuntimeException("Connection refused")
            respond("""{"ok":true}""", HttpStatusCode.OK)
        }

        val response = client.callRaw("POST", operation = "UpdateItem", safety = OperationSafety.NOT_IDEMPOTENT)
        assertEquals(200, response.status)
        assertEquals(2, call, "a request that never left is safe to retry")
    }

    @Test
    fun theEscapeHatchAllowsRetryingAmbiguousWrites() = runTest {
        val harness = Harness()
        var call = 0
        val client = harnessClient(harness, RetryConfig(retryAmbiguousWrites = true)) {
            if (call++ == 0) throw RuntimeException("read timed out")
            respond("""{"ok":true}""", HttpStatusCode.OK)
        }

        client.callRaw("POST", operation = "UpdateItem", safety = OperationSafety.NOT_IDEMPOTENT)
        assertEquals(2, call)
    }
}

/**
 * Cancellation is not a transport failure. `classifyTransportFailure` answers AMBIGUOUS for
 * anything it does not recognise, and AMBIGUOUS on an IDEMPOTENT operation retries — so without
 * the `CancellationException` carve-out in the send catch, cancelling a scope makes the client
 * answer by issuing the request again. Remove that carve-out and both tests here fail.
 */
class TransportCancellationTest {

    @Test
    fun cancellingTheScopeStopsTheClientRatherThanRetrying() = runTest {
        val harness = Harness()
        val inFlight = CompletableDeferred<Unit>()
        val client = harnessClient(harness) {
            inFlight.complete(Unit)
            awaitCancellation()
        }

        val call = launch { client.callRaw("POST", operation = "GetItem") }
        inFlight.await()
        call.cancelAndJoin()

        assertEquals(1, harness.requests.size, "a cancelled scope must not produce a second request")
        // The sleep hook records without suspending, so it runs even when every other suspension
        // point is already cancelled: it registers a retry the request count could miss if the
        // Ktor pipeline short-circuits before reaching the engine.
        assertTrue(harness.sleeps.isEmpty(), "cancellation must not schedule a retry backoff")
    }

    /**
     * The same rule at the exact catch site: a cancellation surfacing out of `send` — which is what
     * a cancelled scope produces at the suspension point inside it — must propagate untouched.
     */
    @Test
    fun aCancellationOutOfSendIsNeitherClassifiedNorRetried() = runTest {
        val harness = Harness()
        var sends = 0
        val client = harnessClient(harness) {
            sends++
            throw CancellationException("scope cancelled")
        }

        assertFailsWith<CancellationException> {
            client.callRaw("POST", operation = "GetItem", safety = OperationSafety.IDEMPOTENT)
        }
        assertEquals(1, sends, "an idempotent operation must still not retry a cancellation")
        assertTrue(harness.sleeps.isEmpty())
    }
}

class TransportRequestShapeTest {

    @Test
    fun signsAndSendsTheExpectedHeaders() = runTest {
        val harness = Harness()
        val client = harnessClient(harness) { respond("{}", HttpStatusCode.OK) }
        client.callRaw("POST", operation = "GetItem")

        val request = harness.requests.single()
        val authorization = request.headers["Authorization"]
        assertNotNull(authorization)
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"))
        assertEquals("DynamoDB_20120810.GetItem", request.headers["X-Amz-Target"])
        // Ktor carries Content-Type on the body rather than in the header map. It still reaches the
        // wire — and it must equal the value that was signed, which is why it is set explicitly.
        assertEquals("application/x-amz-json-1.0", request.body.contentType?.toString())
        assertEquals(TOKEN, request.headers["X-Amz-Security-Token"])
        assertEquals("identity", request.headers["accept-encoding"])
    }

    @Test
    fun retriesCarryAStableInvocationIdAndAnIncrementingAttempt() = runTest {
        val harness = Harness()
        val client = harnessClient(harness, RetryConfig(maxAttempts = 3)) {
            respond(jsonError("ThrottlingException"), HttpStatusCode.BadRequest)
        }
        runCatching { client.callRaw("POST", operation = "GetItem") }

        val invocationIds = harness.requests.map { it.headers["amz-sdk-invocation-id"] }.toSet()
        assertEquals(1, invocationIds.size, "the invocation id identifies the call, not the attempt")
        assertEquals(
            listOf("attempt=1; max=3", "attempt=2; max=3", "attempt=3; max=3"),
            harness.requests.map { it.headers["amz-sdk-request"] },
        )
    }

    @Test
    fun eachAttemptIsSignedAfresh() = runTest {
        val harness = Harness()
        val client = harnessClient(harness, RetryConfig(maxAttempts = 2)) {
            // Enough to cross a second (X-Amz-Date has second resolution) without eating the
            // aggregate retry deadline, which is measured from the same clock.
            harness.now += 2_000
            respond(jsonError("ThrottlingException"), HttpStatusCode.BadRequest)
        }
        runCatching { client.callRaw("POST", operation = "GetItem") }

        val dates = harness.requests.map { it.headers["X-Amz-Date"] }
        assertEquals(2, dates.size)
        assertTrue(dates[0] != dates[1], "a replayed signature is a stale signature")
    }

    @Test
    fun theSignedPathIsSentVerbatim() = runTest {
        val harness = Harness()
        // An S3-shaped key: dot segments and an encoded space that must survive untouched.
        val path = "/bucket/a%20b/c..d/e"
        val client = harnessClient(
            harness,
            protocol = AwsProtocol.restXml("s3"),
            endpointUrl = "https://bucket.s3.us-west-2.amazonaws.com",
        ) { respond("", HttpStatusCode.OK) }

        client.callRaw("GET", path = path, doubleUriEncode = false, normalizeUriPath = false)

        assertEquals(path, harness.requests.single().url.encodedPath)
    }

    @Test
    fun queryParametersAreEncodedForTheWire() = runTest {
        val harness = Harness()
        val client = harnessClient(
            harness,
            protocol = AwsProtocol.restXml("s3"),
            endpointUrl = "https://bucket.s3.us-west-2.amazonaws.com",
        ) { respond("", HttpStatusCode.OK) }

        client.callRaw("GET", path = "/", query = listOf("prefix" to "a b/c"))

        assertContains(harness.requests.single().url.encodedQuery, "prefix=a%20b%2Fc")
    }
}

class TransportErrorSurfacingTest {

    @Test
    fun requestIdentifiersReachTheException() = runTest {
        val harness = Harness()
        val client = harnessClient(harness) {
            respond(
                jsonError("ValidationException"),
                HttpStatusCode.BadRequest,
                headersOf(
                    "x-amz-request-id" to listOf("REQ-123"),
                    "x-amz-id-2" to listOf("EXT-456"),
                ).let { Headers.build { appendAll(it) } },
            )
        }

        val error = assertFailsWith<AwsServiceException> { client.callRaw("POST", operation = "GetItem") }
        assertEquals("REQ-123", error.requestId)
        assertEquals("EXT-456", error.extendedRequestId)
        // AWS Support will not act on an S3 report without both, so they must survive toString().
        assertContains(error.toString(), "REQ-123")
    }

    @Test
    fun aHeadResponseWithNoBodyStillProducesATypedException() = runTest {
        val harness = Harness()
        val client = harnessClient(harness, protocol = AwsProtocol.restXml("s3")) {
            respond("", HttpStatusCode.NotFound)
        }

        val error = assertFailsWith<AwsServiceException> { client.callRaw("HEAD", path = "/key") }
        assertEquals(404, error.statusCode)
        assertEquals(1, harness.requests.size, "404 is not retryable")
    }

    /**
     * Following the redirect would replay an `Authorization` header computed over one authority to
     * a different host: a broken signature *and* a credential sent off-origin.
     */
    @Test
    fun redirectsAreSurfacedNotFollowed() = runTest {
        val harness = Harness()
        val client = harnessClient(harness, protocol = AwsProtocol.restXml("s3")) {
            respond(
                "<Error><Code>PermanentRedirect</Code><Message>wrong region</Message></Error>",
                HttpStatusCode.MovedPermanently,
                Headers.build {
                    append("location", "https://evil.example.com/")
                    append("x-amz-bucket-region", "eu-west-1")
                },
            )
        }

        val error = assertFailsWith<AwsRedirectException> { client.callRaw("GET", path = "/key") }
        assertEquals(301, error.statusCode)
        assertEquals("eu-west-1", error.bucketRegion)
        assertEquals(1, harness.requests.size, "exactly one request; the redirect was not followed")

        // Nothing credential-bearing may reach the redirect target.
        val hosts = harness.requests.map { it.url.host }.toSet()
        assertEquals(setOf("dynamodb.us-west-2.amazonaws.com"), hosts)
        assertFalse(hosts.contains("evil.example.com"))
    }

    @Test
    fun xmlSlowDownIsRetriedOnTheThrottlingSchedule() = runTest {
        val harness = Harness()
        val client = harnessClient(
            harness,
            RetryConfig(maxAttempts = 2),
            protocol = AwsProtocol.restXml("s3"),
        ) {
            respond("<Error><Code>SlowDown</Code></Error>", HttpStatusCode.ServiceUnavailable)
        }

        assertFailsWith<AwsServiceException> { client.callRaw("GET", path = "/key") }
        assertEquals(2, harness.requests.size)
        assertEquals(listOf(1_000L), harness.sleeps, "throttling base, not the 25ms transient one")
    }

    @Test
    fun xmlNoSuchKeyIsNotRetried() = runTest {
        val harness = Harness()
        val client = harnessClient(harness, protocol = AwsProtocol.restXml("s3")) {
            respond("<Error><Code>NoSuchKey</Code><Message>gone</Message></Error>", HttpStatusCode.NotFound)
        }

        val error = assertFailsWith<AwsServiceException> { client.callRaw("GET", path = "/key") }
        assertEquals("NoSuchKey", error.code)
        assertEquals("gone", error.message)
        assertEquals(1, harness.requests.size)
    }

    @Test
    fun xml409ConditionalRequestConflictIsRetried() = runTest {
        val harness = Harness()
        val client = harnessClient(
            harness,
            RetryConfig(maxAttempts = 2),
            protocol = AwsProtocol.restXml("s3"),
        ) {
            respond("<Error><Code>ConditionalRequestConflict</Code></Error>", HttpStatusCode.Conflict)
        }

        assertFailsWith<AwsServiceException> { client.callRaw("PUT", path = "/key") }
        assertEquals(2, harness.requests.size)
    }

    @Test
    fun malformedXmlDegradesToStatusBasedClassification() = runTest {
        val harness = Harness()
        val client = harnessClient(
            harness,
            RetryConfig(maxAttempts = 2),
            protocol = AwsProtocol.restXml("s3"),
        ) {
            respond("<Error><Code>trunc", HttpStatusCode.ServiceUnavailable)
        }

        assertFailsWith<AwsServiceException> { client.callRaw("GET", path = "/key") }
        assertEquals(2, harness.requests.size, "503 still retries on status when the body is unusable")
    }

    @Test
    fun exceptionsCarryNoCredentialMaterial() = runTest {
        val harness = Harness()
        val client = harnessClient(harness) {
            respond(jsonError("ValidationException"), HttpStatusCode.BadRequest)
        }

        val error = assertFailsWith<AwsServiceException> { client.callRaw("POST", operation = "GetItem") }
        val rendered = error.toString() + error.stackTraceToString()
        assertFalse(SECRET in rendered)
        assertFalse(TOKEN in rendered)
    }
}
