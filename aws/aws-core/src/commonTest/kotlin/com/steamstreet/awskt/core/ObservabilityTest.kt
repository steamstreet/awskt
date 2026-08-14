package com.steamstreet.awskt.core

import com.steamstreet.awskt.core.AwsCallEvent.Outcome
import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// -------------------------------------------------------------------------------------------------
// Harness
// -------------------------------------------------------------------------------------------------

private class ObserverHarness {
    val requests = mutableListOf<HttpRequestData>()
    val sleeps = mutableListOf<Long>()
    val events = mutableListOf<AwsCallEvent>()

    var now: Long = 1_700_000_000_000

    /** Milliseconds the clock jumps while a request is in the engine, so durations are non-zero. */
    var millisPerRequest: Long = 0

    val outcomes: List<Outcome> get() = events.map { it.outcome }

    /** The one event with this outcome, failing loudly when the stream carries a different number. */
    fun single(outcome: Outcome): AwsCallEvent = events.single { it.outcome == outcome }
}

private fun observedClient(
    harness: ObserverHarness,
    retryConfig: RetryConfig = RetryConfig(),
    observer: AwsCallObserver? = AwsCallObserver { harness.events += it },
    protocol: AwsProtocol = AwsProtocol.awsJson1_0("dynamodb", "DynamoDB_20120810"),
    handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) ->
    io.ktor.client.request.HttpResponseData,
): AwsServiceClient {
    val engine = MockEngine { request ->
        harness.requests += request
        harness.now += harness.millisPerRequest
        handler(request)
    }
    return AwsServiceClient(
        httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
        credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKIDEXAMPLE", "SECRET", "TOKEN")),
        endpoint = parseEndpoint("https://dynamodb.us-west-2.amazonaws.com"),
        region = "us-west-2",
        protocol = protocol,
        retryConfig = retryConfig,
        clock = { harness.now },
        random = { 1.0 },
        sleep = { harness.sleeps += it },
        observer = observer,
    )
}

private fun jsonError(code: String, message: String = "boom") =
    """{"__type":"com.amazon.coral.service#$code","Message":"$message"}"""

private fun responder(vararg responses: Pair<String, HttpStatusCode>): (Int) -> Pair<String, HttpStatusCode> {
    return { call -> responses[minOf(call, responses.size - 1)] }
}

// -------------------------------------------------------------------------------------------------
// The event stream
// -------------------------------------------------------------------------------------------------

/**
 * The shape decision these tests pin down: **one terminal event per attempt**, with the retry
 * decision as a *separate* follow-on event rather than a `willRetryAfterMillis` folded into the
 * terminal one.
 *
 * Folding it in would have made "was this attempt retried?" and "what did this attempt do?" the same
 * event, and there is no honest way to fold [Outcome.CLOCK_SKEW_CORRECTED] — a retry with no
 * backoff — into that. Keeping the decision separate also keeps [Outcome.SERVICE_ERROR] meaning
 * exactly "AWS returned an error", retryable or not, which is the count a dashboard actually wants.
 */
class AwsCallEventStreamTest {

    @Test
    fun aThrottledCallEmitsOneTerminalEventPerAttemptAndOneDecisionBetweenThem() = runTest {
        val harness = ObserverHarness()
        val responses = responder(
            jsonError("ThrottlingException") to HttpStatusCode.BadRequest,
            jsonError("ThrottlingException") to HttpStatusCode.BadRequest,
            """{"ok":true}""" to HttpStatusCode.OK,
        )
        var call = 0
        val client = observedClient(harness) {
            val (body, status) = responses(call++)
            respond(body, status)
        }

        client.callRaw("POST", operation = "GetItem")

        assertEquals(
            listOf(
                Outcome.SERVICE_ERROR, Outcome.RETRY_SCHEDULED,
                Outcome.SERVICE_ERROR, Outcome.RETRY_SCHEDULED,
                Outcome.SUCCESS,
            ),
            harness.outcomes,
        )
        // Three terminal events, so three attempts — reconstructed without counting requests.
        assertEquals(
            3,
            harness.events.count {
                it.outcome in setOf(Outcome.SUCCESS, Outcome.SERVICE_ERROR, Outcome.TRANSPORT_FAILURE)
            },
        )
        assertEquals(listOf(1, 1, 2, 2, 3), harness.events.map { it.attempt }, "1-based, decisions repeat")
        assertEquals(
            listOf("GetItem"),
            harness.events.map { it.operation }.distinct(),
        )

        // The error dimensions ride on the terminal event...
        val firstError = harness.events.first()
        assertEquals(400, firstError.statusCode)
        assertEquals("ThrottlingException", firstError.errorCode)
        assertEquals(200, harness.single(Outcome.SUCCESS).statusCode)
        assertNull(harness.single(Outcome.SUCCESS).errorCode)

        // ...and the chosen delay rides on the decision, exactly there and nowhere else. Throttling
        // base 1s, doubling, with jitter pinned to 1.0.
        assertEquals(
            listOf(1_000L, 2_000L),
            harness.events.filter { it.outcome == Outcome.RETRY_SCHEDULED }.map { it.willRetryAfterMillis },
        )
        assertEquals(harness.sleeps, listOf(1_000L, 2_000L), "announced before it is slept, and equal")
        assertTrue(
            harness.events.filter { it.outcome != Outcome.RETRY_SCHEDULED }
                .all { it.willRetryAfterMillis == null },
        )
    }

    /**
     * A retryable failure that runs out of attempts ends in exactly one [Outcome.GAVE_UP] — the
     * signal a call-failure metric is keyed on — *after* the final attempt's own terminal event.
     */
    @Test
    fun givingUpEmitsOneFinalDecisionAfterTheLastAttemptsTerminalEvent() = runTest {
        val harness = ObserverHarness()
        val client = observedClient(harness, RetryConfig(maxAttempts = 3)) {
            respond(jsonError("ThrottlingException"), HttpStatusCode.BadRequest)
        }

        assertFailsWith<AwsServiceException> { client.callRaw("POST", operation = "GetItem") }

        assertEquals(
            listOf(
                Outcome.SERVICE_ERROR, Outcome.RETRY_SCHEDULED,
                Outcome.SERVICE_ERROR, Outcome.RETRY_SCHEDULED,
                Outcome.SERVICE_ERROR, Outcome.GAVE_UP,
            ),
            harness.outcomes,
        )
        val gaveUp = harness.single(Outcome.GAVE_UP)
        assertEquals(3, gaveUp.attempt, "the attempt it gave up on")
        assertEquals(400, gaveUp.statusCode)
        assertEquals("ThrottlingException", gaveUp.errorCode)
        assertNull(gaveUp.willRetryAfterMillis)
    }

    /**
     * A never-retryable error still reports SERVICE_ERROR. Reporting only GAVE_UP would leave a
     * "service errors" counter blind to every `ValidationException` the client ever received.
     */
    @Test
    fun aNonRetryableErrorEmitsBothTheServiceErrorAndTheGiveUp() = runTest {
        val harness = ObserverHarness()
        val client = observedClient(harness) {
            respond(jsonError("ValidationException"), HttpStatusCode.BadRequest)
        }

        assertFailsWith<AwsServiceException> { client.callRaw("POST", operation = "GetItem") }

        assertEquals(listOf(Outcome.SERVICE_ERROR, Outcome.GAVE_UP), harness.outcomes)
        assertEquals(1, harness.requests.size)
        assertEquals("ValidationException", harness.single(Outcome.GAVE_UP).errorCode)
    }

    /** A failure with no response reports a null status; that is how an observer tells them apart. */
    @Test
    fun aTransportFailureReportsNoStatusAndIsFollowedByItsRetryDecision() = runTest {
        val harness = ObserverHarness()
        var call = 0
        val client = observedClient(harness) {
            if (call++ == 0) throw RuntimeException("Connection refused")
            respond("""{"ok":true}""", HttpStatusCode.OK)
        }

        client.callRaw("POST", operation = "GetItem")

        assertEquals(
            listOf(Outcome.TRANSPORT_FAILURE, Outcome.RETRY_SCHEDULED, Outcome.SUCCESS),
            harness.outcomes,
        )
        val failure = harness.single(Outcome.TRANSPORT_FAILURE)
        assertNull(failure.statusCode, "no response ever arrived")
        assertNull(failure.errorCode)
        // Transient base of 25ms, not the throttling second.
        assertEquals(25L, harness.single(Outcome.RETRY_SCHEDULED).willRetryAfterMillis)
    }

    /**
     * A body the caller's `validateBody` rejects is a TRANSPORT_FAILURE *with* a status: the bytes
     * arrived and were defective. It is retried like a dead socket, and it is reported like one.
     */
    @Test
    fun aRejectedBodyIsATransportFailureThatStillCarriesItsStatus() = runTest {
        val harness = ObserverHarness()
        var validations = 0
        val client = observedClient(harness, RetryConfig(maxAttempts = 2)) {
            respond("""{"ok":true}""", HttpStatusCode.OK)
        }

        assertFailsWith<IllegalStateException> {
            client.callRaw("POST", operation = "GetItem", validateBody = {
                validations++
                error("truncated")
            })
        }

        assertEquals(2, validations)
        assertEquals(
            listOf(
                Outcome.TRANSPORT_FAILURE, Outcome.RETRY_SCHEDULED,
                Outcome.TRANSPORT_FAILURE, Outcome.GAVE_UP,
            ),
            harness.outcomes,
        )
        assertTrue(
            harness.events.filter { it.outcome == Outcome.TRANSPORT_FAILURE }.all { it.statusCode == 200 },
        )
    }

    /**
     * ...and on a write it is **not** replayed, which is the one place `validateBody`'s retry
     * semantics bend to [OperationSafety].
     *
     * The 2xx arrived, so unlike an ambiguous transport failure there is nothing to wonder about:
     * the request reached AWS and was applied. A second attempt to obtain a better copy of the
     * answer applies the write twice. The event stream still reports the attempt's own terminal
     * outcome before the give-up, exactly as every other non-retried failure does.
     */
    @Test
    fun aRejectedBodyOnAWriteIsSurfacedWithoutReplayingTheWrite() = runTest {
        val harness = ObserverHarness()
        var validations = 0
        val client = observedClient(harness, RetryConfig(maxAttempts = 4)) {
            respond("""{"ok":true}""", HttpStatusCode.OK)
        }

        assertFailsWith<IllegalStateException> {
            client.callRaw(
                "POST",
                operation = "PutEvents",
                safety = OperationSafety.NOT_IDEMPOTENT,
                validateBody = {
                    validations++
                    error("truncated")
                },
            )
        }

        assertEquals(1, validations, "the write must not be replayed to re-validate it")
        assertEquals(1, harness.requests.size)
        assertEquals(emptyList(), harness.sleeps, "and must not pay retry backoff either")
        assertEquals(listOf(Outcome.TRANSPORT_FAILURE, Outcome.GAVE_UP), harness.outcomes)
        assertEquals(200, harness.single(Outcome.GAVE_UP).statusCode, "which response was rejected")
    }

    /** Opting in to replaying ambiguous writes does not opt in to replaying this one. */
    @Test
    fun retryAmbiguousWritesDoesNotUnlockAWriteWhoseResponseArrived() = runTest {
        val harness = ObserverHarness()
        val client = observedClient(
            harness,
            RetryConfig(maxAttempts = 4, retryAmbiguousWrites = true),
        ) { respond("""{"ok":true}""", HttpStatusCode.OK) }

        assertFailsWith<IllegalStateException> {
            client.callRaw(
                "POST",
                operation = "PutEvents",
                safety = OperationSafety.NOT_IDEMPOTENT,
                validateBody = { error("truncated") },
            )
        }

        assertEquals(1, harness.requests.size)
    }

    /**
     * The skew retry takes no backoff, so it reports itself rather than arriving as a
     * RETRY_SCHEDULED with a zero delay that an observer could not tell from a genuine one.
     */
    @Test
    fun aClockSkewCorrectionIsVisibleAndSleepsNothing() = runTest {
        val harness = ObserverHarness()
        var call = 0
        val client = observedClient(harness) {
            if (call++ == 0) {
                respond(
                    jsonError("RequestTimeTooSkewed", "skewed"),
                    HttpStatusCode.Forbidden,
                    Headers.build { append("Date", "Wed, 15 Nov 2023 06:00:00 GMT") },
                )
            } else {
                respond("""{"ok":true}""", HttpStatusCode.OK)
            }
        }

        client.callRaw("POST", operation = "GetItem")

        assertEquals(
            listOf(Outcome.SERVICE_ERROR, Outcome.CLOCK_SKEW_CORRECTED, Outcome.SUCCESS),
            harness.outcomes,
        )
        val corrected = harness.single(Outcome.CLOCK_SKEW_CORRECTED)
        assertEquals(1, corrected.attempt)
        assertEquals("RequestTimeTooSkewed", corrected.errorCode)
        assertNull(corrected.willRetryAfterMillis, "the skew retry is immediate")
        assertEquals(emptyList(), harness.sleeps)
    }

    /**
     * A refusal from `inspectBeforeBody` is the caller's own policy decision about a response that
     * arrived intact, so it is filed as a give-up and **not** as a service or transport outcome —
     * which would put the caller's ceiling in AWS's error rate.
     */
    @Test
    fun anInspectionRefusalGivesUpWithoutBlamingAws() = runTest {
        val harness = ObserverHarness()
        val client = observedClient(harness) { respond("""{"ok":true}""", HttpStatusCode.OK) }

        assertFailsWith<IllegalStateException> {
            client.callRaw("GET", path = "/key", inspectBeforeBody = { _, _ -> error("too big") })
        }

        assertEquals(listOf(Outcome.GAVE_UP), harness.outcomes)
        assertEquals(200, harness.single(Outcome.GAVE_UP).statusCode, "which response was refused")
    }

    /** A cancelled call has no outcome: the caller stopped asking. */
    @Test
    fun aCancelledCallEmitsNothing() = runTest {
        val harness = ObserverHarness()
        val client = observedClient(harness) { throw CancellationException("scope cancelled") }

        assertFailsWith<CancellationException> { client.callRaw("POST", operation = "GetItem") }
        assertEquals(emptyList(), harness.outcomes)
    }

    /** Durations come from the injected clock, so a test can assert on them exactly. */
    @Test
    fun durationsAreMeasuredWithTheInjectedClock() = runTest {
        val harness = ObserverHarness()
        harness.millisPerRequest = 7
        var call = 0
        val client = observedClient(harness) {
            if (call++ == 0) respond(jsonError("ThrottlingException"), HttpStatusCode.BadRequest)
            else respond("""{"ok":true}""", HttpStatusCode.OK)
        }

        client.callRaw("POST", operation = "GetItem")

        // Every event is 7ms after its own attempt's start; the injected `sleep` does not move the
        // clock, so nothing else can contribute.
        assertTrue(harness.events.all { it.durationMillis == 7L }, harness.events.toString())
        val terminals = harness.events.filter {
            it.outcome in setOf(Outcome.SUCCESS, Outcome.SERVICE_ERROR, Outcome.TRANSPORT_FAILURE)
        }
        assertEquals(14L, terminals.sumOf { it.durationMillis }, "total time on the wire")
    }

    /** Nothing is reported when nothing is listening, and the call is unaffected. */
    @Test
    fun noObserverIsTheDefaultAndChangesNothing() = runTest {
        val harness = ObserverHarness()
        val client = observedClient(harness, observer = null) {
            respond("""{"ok":true}""", HttpStatusCode.OK)
        }

        assertEquals(200, client.callRaw("POST", operation = "GetItem").status)
        assertEquals(emptyList(), harness.outcomes)
    }
}

// -------------------------------------------------------------------------------------------------
// A throwing observer
// -------------------------------------------------------------------------------------------------

class AwsCallObserverIsolationTest {

    /**
     * Instrumentation does not get to fail the thing it instruments. Without the swallow, a
     * null-pointer in a metrics tag surfaces as an AWS call that failed — and the outage looks like
     * it is in DynamoDB.
     */
    @Test
    fun aThrowingObserverAffectsNeitherTheResultNorTheRetries() = runTest {
        val harness = ObserverHarness()
        var notified = 0
        var call = 0
        val client = observedClient(
            harness,
            observer = AwsCallObserver {
                notified++
                throw IllegalStateException("observer is broken")
            },
        ) {
            if (call++ < 2) respond(jsonError("ThrottlingException"), HttpStatusCode.BadRequest)
            else respond("""{"ok":true}""", HttpStatusCode.OK)
        }

        val response = client.callRaw("POST", operation = "GetItem")

        assertEquals(200, response.status)
        assertEquals(3, harness.requests.size, "the retries still happened")
        assertEquals(listOf(1_000L, 2_000L), harness.sleeps)
        // Still called on every event it would otherwise have received — one throw does not
        // detach it.
        assertEquals(5, notified)
    }

    /** And a throwing observer cannot turn a failing call into a differently-failing one. */
    @Test
    fun aThrowingObserverDoesNotReplaceTheCallsOwnException() = runTest {
        val harness = ObserverHarness()
        val client = observedClient(
            harness,
            observer = AwsCallObserver { throw IllegalStateException("observer is broken") },
        ) {
            respond(jsonError("ValidationException"), HttpStatusCode.BadRequest)
        }

        val error = assertFailsWith<AwsServiceException> { client.callRaw("POST", operation = "GetItem") }
        assertEquals("ValidationException", error.code)
    }

    /**
     * The single exception to the swallow. A cancellation raised inside the observer is structured
     * concurrency tearing the call down, not a fault in the observer; absorbing it would leave a
     * cancelled coroutine issuing AWS requests.
     */
    @Test
    fun aCancellationFromTheObserverIsRethrown() = runTest {
        val harness = ObserverHarness()
        val client = observedClient(
            harness,
            observer = AwsCallObserver { throw CancellationException("scope cancelled") },
        ) {
            respond("""{"ok":true}""", HttpStatusCode.OK)
        }

        assertFailsWith<CancellationException> { client.callRaw("POST", operation = "GetItem") }
    }
}

// -------------------------------------------------------------------------------------------------
// User-Agent
// -------------------------------------------------------------------------------------------------

/**
 * Without one of these, requests reach AWS labelled `ktor-client` (CIO) or unlabelled (Curl), and
 * CloudTrail's `userAgent`, S3 server access logs and AWS Support triage all key on that field.
 */
class UserAgentTest {

    @Test
    fun exactlyOneUserAgentGoesOutAndItIsOurs() = runTest {
        val harness = ObserverHarness()
        val client = observedClient(harness) { respond("""{"ok":true}""", HttpStatusCode.OK) }

        client.callRaw("POST", operation = "GetItem")

        val sent = harness.requests.single().headers.getAll("User-Agent").orEmpty()
        assertEquals(1, sent.size, "Ktor appends its own default when none is set: $sent")
        assertEquals(AWSKT_USER_AGENT, sent.single())
        assertTrue(sent.single().startsWith("awskt/"), sent.single())
    }

    /** The caller's own identity wins, and does not get a second header stapled next to it. */
    @Test
    fun aCallerSuppliedUserAgentIsNotDuplicated() = runTest {
        val harness = ObserverHarness()
        val client = observedClient(harness) { respond("""{"ok":true}""", HttpStatusCode.OK) }

        client.callRaw(
            "POST",
            operation = "GetItem",
            // Deliberately cased differently from the header this client would add: HTTP header
            // names are case-insensitive, and a case-sensitive check here means two User-Agents.
            headers = listOf("User-Agent" to "my-service/4.2"),
        )

        assertEquals(
            listOf("my-service/4.2"),
            harness.requests.single().headers.getAll("User-Agent"),
        )
    }

    /**
     * It is deliberately **not** signed — `user-agent` is in the signer's skipped set — so a
     * version bump here can never break a signature.
     */
    @Test
    fun theUserAgentIsNotInTheSignedHeaderList() = runTest {
        val harness = ObserverHarness()
        val client = observedClient(harness) { respond("""{"ok":true}""", HttpStatusCode.OK) }

        client.callRaw("POST", operation = "GetItem")

        val authorization = harness.requests.single().headers["Authorization"]
        assertNotNull(authorization)
        assertTrue("user-agent" !in authorization, authorization)
    }
}
