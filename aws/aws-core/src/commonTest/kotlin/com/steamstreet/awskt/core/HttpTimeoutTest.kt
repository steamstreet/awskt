package com.steamstreet.awskt.core

import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
private const val ENDPOINT = "https://dynamodb.us-west-2.amazonaws.com"

/** Ktor's wording, reproduced so the classifier is tested against the strings it will really see. */
private fun connectTimeout() = ConnectTimeoutException(
    "Connect timeout has expired [url=$ENDPOINT, connect_timeout=3000 ms]",
)

private fun requestTimeout() = HttpRequestTimeoutException(ENDPOINT, 30_000L)

private fun socketTimeout() = SocketTimeoutException(
    "Socket timeout has expired [url=$ENDPOINT, socket_timeout=30000] ms",
)

/** A caller's own `withTimeout`, which is a [CancellationException] and must stay one. */
private class TimeoutCancellationException(message: String) : CancellationException(message)

class AwsHttpTimeoutsTest {

    @Test
    fun defaultsAreAwsSdkShaped() {
        val timeouts = AwsHttpTimeouts()
        // Not arbitrary: a connect to a regional endpoint either completes in tens of milliseconds
        // or never, and the two 30s budgets sit under a typical Lambda timeout with room for the
        // retry loop's own sleeps.
        assertEquals(3_000, timeouts.connectTimeoutMillis)
        assertEquals(30_000, timeouts.socketTimeoutMillis)
        assertEquals(30_000, timeouts.requestTimeoutMillis)
    }

    @Test
    fun nullIsAcceptedAsAnExplicitlyUnboundedTimeout() {
        val timeouts = AwsHttpTimeouts(null, null, null)
        assertNull(timeouts.connectTimeoutMillis)
        assertNull(timeouts.socketTimeoutMillis)
        assertNull(timeouts.requestTimeoutMillis)
    }

    /** Ktor rejects these too, but only at the first request — a long way from the mistake. */
    @Test
    fun nonPositiveValuesAreRejectedWhereTheyAreSet() {
        assertFailsWith<IllegalArgumentException> { AwsHttpTimeouts(connectTimeoutMillis = 0) }
        assertFailsWith<IllegalArgumentException> { AwsHttpTimeouts(socketTimeoutMillis = -1) }
        assertFailsWith<IllegalArgumentException> { AwsHttpTimeouts(requestTimeoutMillis = 0) }
    }
}

/**
 * The client configuration `awsHttpClient` applies, exercised through `MockEngine`.
 *
 * `awsHttpClient` itself hardcodes CIO/Curl, so the shared [configureAwsClient] is what is tested
 * here — it is the whole of what those two functions install, so a regression in it is a regression
 * in both engines.
 *
 * **What MockEngine can and cannot prove.** `requestTimeoutMillis` is enforced by the `HttpTimeout`
 * plugin itself — a coroutine that cancels the call once the budget is spent — so it is real here
 * exactly as it is in production. `connectTimeoutMillis` and `socketTimeoutMillis` are enforced by
 * the *engine*, and MockEngine has neither a socket nor a connect phase to enforce them on; it only
 * declares the capability. Those two are covered at the classification level below instead, and
 * their end-to-end behaviour is the engine's, not this library's.
 */
class AwsHttpClientConfigurationTest {

    @Test
    fun aStalledResponseFailsWithinTheBudgetInsteadOfHanging() = runTest {
        val client = HttpClient(MockEngine { delay(4.seconds); respond("late", HttpStatusCode.OK) }) {
            configureAwsClient(AwsHttpTimeouts(requestTimeoutMillis = 250))
        }

        // Real dispatchers, not the test scheduler: the point of the assertion is wall-clock, and
        // virtual time would skip the very stall being measured.
        withContext(Dispatchers.Default) {
            val mark = TimeSource.Monotonic.markNow()
            assertFailsWith<HttpRequestTimeoutException> { client.get(ENDPOINT) }
            assertTrue(
                mark.elapsedNow() < 3.seconds,
                "the 250ms budget must end the call long before the 4s stall does",
            )
        }
        client.close()
    }

    /** The two settings that were non-negotiable before timeouts existed, still applied. */
    @Test
    fun redirectsAreNotFollowedAndErrorStatusesAreNotThrown() = runTest {
        val engine = MockEngine {
            respond(
                "<Error><Code>PermanentRedirect</Code></Error>",
                HttpStatusCode.MovedPermanently,
                Headers.build { append("location", "https://evil.example.com/") },
            )
        }
        val client = HttpClient(engine) { configureAwsClient(AwsHttpTimeouts()) }

        val response = client.get(ENDPOINT)

        assertEquals(301, response.status.value, "expectSuccess=false: a 301 is data, not an exception")
        assertEquals(1, engine.requestHistory.size, "followRedirects=false: the credential stays on-origin")
        assertEquals(setOf("dynamodb.us-west-2.amazonaws.com"), engine.requestHistory.map { it.url.host }.toSet())
        client.close()
    }
}

/**
 * Timeout classification, which is where a timeout stops being a hang and becomes a *decision*.
 *
 * A connect timeout is provably NOT_SENT and may be replayed even for a write; a request or socket
 * timeout may already have landed and must not be. Getting this wrong is silent in both directions:
 * too permissive duplicates writes, too strict turns every idempotent blip into a hard failure.
 */
class TimeoutClassificationTest {

    @Test
    fun aConnectTimeoutIsProvablyNotSent() {
        assertEquals(TransportFailure.NOT_SENT, classifyTransportFailure(connectTimeout()))
        // ...and by message alone, for an engine that reports it with a class of another name.
        assertEquals(
            TransportFailure.NOT_SENT,
            classifyTransportFailure(Exception("Connect timeout has expired [url=x, connect_timeout=3000 ms]")),
        )
        assertEquals(TransportFailure.NOT_SENT, classifyTransportFailure(Exception("connect timed out")))
    }

    /**
     * Both of these fire with the request already on the wire, so AWS may have applied it. AMBIGUOUS
     * is the *default* answer rather than a matched one — this test exists so that a later addition
     * to the NOT_SENT patterns cannot quietly capture them.
     */
    @Test
    fun requestAndSocketTimeoutsAreAmbiguous() {
        assertEquals(TransportFailure.AMBIGUOUS, classifyTransportFailure(requestTimeout()))
        assertEquals(TransportFailure.AMBIGUOUS, classifyTransportFailure(socketTimeout()))
    }

    @Test
    fun aTimeoutWrappedInACauseChainIsStillClassified() {
        assertEquals(
            TransportFailure.NOT_SENT,
            classifyTransportFailure(Exception("call failed", connectTimeout())),
        )
    }

    /**
     * Ktor enforces `requestTimeoutMillis` by cancelling the call's job with the timeout as the
     * cancellation *cause*. Read as a caller's cancellation, that timeout would be neither retried
     * nor reported as a timeout.
     */
    @Test
    fun aTimeoutDressedAsACancellationIsUnwrapped() {
        val timeout = requestTimeout()
        val cancellation = CancellationException("Request timeout has expired", timeout)

        assertSame(timeout, transportFailureOrNull(cancellation))
        assertEquals(TransportFailure.AMBIGUOUS, classifyTransportFailure(transportFailureOrNull(cancellation)!!))
    }

    @Test
    fun anOrdinaryCancellationIsNotATransportFailure() {
        assertNull(transportFailureOrNull(CancellationException("scope cancelled")))
        assertNull(transportFailureOrNull(CancellationException("parent died", CancellationException("child"))))
    }

    /**
     * The exclusion that makes the unwrapping safe. A caller's `withTimeout` cancels with a
     * `TimeoutCancellationException`, whose name matches the same pattern — unwrap that and the
     * client answers "stop now" by sending the request again.
     */
    @Test
    fun aCallersOwnWithTimeoutIsNotUnwrapped() {
        val cancellation = CancellationException("cancelled", TimeoutCancellationException("Timed out after 100 ms"))
        assertNull(transportFailureOrNull(cancellation))
    }

    @Test
    fun anOrdinaryFailureIsItsOwnTransportFailure() {
        val failure = Exception("connection reset")
        assertSame(failure, transportFailureOrNull(failure))
    }
}

/**
 * The retry loop's end of the same rule, asserted on the **request count**: the exception a caller
 * sees is identical whether the transport replayed the call once or not at all.
 */
class TimeoutRetryTest {

    private class Fixture {
        var requests: Int = 0
        val sleeps: MutableList<Long> = mutableListOf()
    }

    private fun clientOver(
        fixture: Fixture,
        timeouts: AwsHttpTimeouts = AwsHttpTimeouts(requestTimeoutMillis = 250),
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
            io.ktor.client.request.HttpRequestData,
        ) -> io.ktor.client.request.HttpResponseData,
    ) = AwsServiceClient(
        httpClient = HttpClient(
            MockEngine { request ->
                fixture.requests++
                handler(request)
            },
        ) { configureAwsClient(timeouts) },
        credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKIDEXAMPLE", SECRET, null)),
        endpoint = parseEndpoint(ENDPOINT),
        region = "us-west-2",
        protocol = AwsProtocol.awsJson1_0("dynamodb", "DynamoDB_20120810"),
        retryConfig = RetryConfig(maxAttempts = 3),
        clock = { 1_700_000_000_000 },
        random = { 1.0 },
        // Recorded rather than taken: the wall-clock assertions below are about the stall, not
        // about backoff, and a real sleep would put the two in the same measurement.
        sleep = { fixture.sleeps += it },
    )

    @Test
    fun aStalledIdempotentCallIsRetriedAndCanSucceed() = runTest {
        val fixture = Fixture()
        var attempt = 0
        val client = clientOver(fixture) {
            if (attempt++ == 0) delay(4.seconds)
            respond("""{"ok":true}""", HttpStatusCode.OK)
        }

        withContext(Dispatchers.Default) {
            val response = client.callRaw("POST", operation = "Query", safety = OperationSafety.IDEMPOTENT)
            assertEquals(200, response.status)
        }
        assertEquals(2, fixture.requests, "a timed-out read of an idempotent call is replayable")
    }

    /**
     * The request was on the wire when the budget expired, so `PutEvents`-shaped work must surface
     * rather than publish twice — and it must surface as the *timeout*, not as a cancelled scope.
     */
    @Test
    fun aStalledNonIdempotentCallSurfacesTheTimeoutWithoutReplaying() = runTest {
        val fixture = Fixture()
        val client = clientOver(fixture) {
            delay(4.seconds)
            respond("""{"ok":true}""", HttpStatusCode.OK)
        }

        withContext(Dispatchers.Default) {
            assertFailsWith<HttpRequestTimeoutException> {
                client.callRaw("POST", operation = "PutEvents", safety = OperationSafety.NOT_IDEMPOTENT)
            }
        }
        assertEquals(1, fixture.requests, "a write that may have landed must not be replayed")
        assertTrue(fixture.sleeps.isEmpty(), "and must not pay retry backoff either")
    }

    /** The other side of the split: a connect timeout never left, so even a write may replay it. */
    @Test
    fun aConnectTimeoutIsRetriedEvenForANonIdempotentCall() = runTest {
        val fixture = Fixture()
        var attempt = 0
        val client = clientOver(fixture) {
            if (attempt++ == 0) throw connectTimeout()
            respond("""{"ok":true}""", HttpStatusCode.OK)
        }

        val response = client.callRaw("POST", operation = "PutItem", safety = OperationSafety.NOT_IDEMPOTENT)

        assertEquals(200, response.status)
        assertEquals(2, fixture.requests, "a request that never left is safe to retry")
    }

    /** The budget must bound the *call*, not merely each attempt: three stalls, then it gives up. */
    @Test
    fun aPermanentlyStalledIdempotentCallStopsAtTheAttemptLimit() = runTest {
        val fixture = Fixture()
        val client = clientOver(fixture) {
            delay(4.seconds)
            respond("""{"ok":true}""", HttpStatusCode.OK)
        }

        withContext(Dispatchers.Default) {
            val mark = TimeSource.Monotonic.markNow()
            assertFailsWith<HttpRequestTimeoutException> {
                client.callRaw("GET", path = "/", safety = OperationSafety.IDEMPOTENT)
            }
            assertTrue(
                mark.elapsedNow() < 6.seconds,
                "three 250ms attempts, not three 4s stalls — the whole point of the plugin",
            )
        }
        assertEquals(3, fixture.requests, "maxAttempts, then the timeout surfaces")
    }
}
