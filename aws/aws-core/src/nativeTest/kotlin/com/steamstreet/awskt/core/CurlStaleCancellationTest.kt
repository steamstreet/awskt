package com.steamstreet.awskt.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The curl engine defect [retiringOnCancellation] guards against, against a real server. A request
 * is cancelled while the server holds it; once the server has answered it and the engine has gone
 * idle, the next request is sent. Through the plain curl engine that request can fail at once with
 * the first request's cancellation; through [awsHttpClient] it is answered.
 */
class CurlStaleCancellationTest {
    @Test
    fun theRequestAfterACancelledOneIsAnswered() = runBlocking {
        val server = StallingServer(stallFirstMillis = 1_500)
        val client = awsHttpClient(timeouts = AwsHttpTimeouts(requestTimeoutMillis = 10_000))

        val outcome = sendAfterCancellation(server, client)

        assertEquals(404, outcome.status, "the second request is answered: $outcome")
        assertEquals(2, server.requests)
    }

    /**
     * The defect itself, through the plain curl engine: the second request fails at once with the
     * first one's cancellation. Run three times on macOS, it failed three times, in under a
     * millisecond each. Whether the request reaches the server first depends on the platform: on
     * macOS it does not, and in the Lambda runtime's image it did, and its answer was discarded.
     *
     * **If this test fails, Ktor's curl engine no longer has the defect**, and
     * [retiringOnCancellation] can be removed from [awsHttpClient] along with this test.
     */
    @Test
    fun thePlainCurlEngineStillHasTheDefect() = runBlocking {
        val server = StallingServer(stallFirstMillis = 1_500)
        val client = HttpClient(Curl) { expectSuccess = false }

        val outcome = sendAfterCancellation(server, client)

        assertIs<CancellationException>(outcome.failure, "the second request was answered: $outcome")
        assertTrue(outcome.millis < 100, "the failure was not immediate: $outcome")
    }

    /** What became of the request sent after a cancelled one. */
    data class Outcome(val status: Int?, val failure: Throwable?, val millis: Long)

    private suspend fun sendAfterCancellation(server: StallingServer, client: HttpClient): Outcome {
        val url = "http://127.0.0.1:${server.port}/semantic-index/_search"

        // The first request outlives its caller's bound, as a search did the resolver's.
        assertNull(withTimeoutOrNull(300.milliseconds) { client.post(url) { setBody("{}") } })

        // The server answers it, the engine finishes the transfer and goes idle; only then does the
        // engine record the cancellation it was given.
        while (server.answered < 1) delay(50)
        delay(500)

        val start = TimeSource.Monotonic.markNow()
        return try {
            val response: HttpResponse = client.post(url) { setBody("{}") }
            Outcome(response.status.value, null, start.elapsedNow().inWholeMilliseconds)
        } catch (t: Throwable) {
            Outcome(null, t, start.elapsedNow().inWholeMilliseconds)
        }
    }
}
