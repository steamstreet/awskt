package com.steamstreet.awskt.eventbridge

import com.steamstreet.awskt.core.BatchRetry
import com.steamstreet.awskt.core.RetryConfig
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

// ---------------------------------------------------------------------------------------------
// Helpers. The responder passed to `harnessEventBridge` runs *after* the harness has recorded the
// request body, so it can answer the request it was actually given — which is what lets these
// tests assert "only the failed entries were resubmitted" positionally rather than by count.
// ---------------------------------------------------------------------------------------------

private fun entriesOf(body: String): JsonArray =
    (Json.parseToJsonElement(body) as JsonObject)["Entries"]!!.jsonArray

private fun sourcesOf(body: String): List<String> =
    entriesOf(body).map { it.jsonObject["Source"]!!.jsonPrimitive.content }

/** An `EventId` derived from the entry's `Source`, so result *ordering* is assertable. */
private fun published(source: String) = """{"EventId":"id-$source"}"""

private fun rejected(code: String) = """{"ErrorCode":"$code","ErrorMessage":"$code happened"}"""

private fun putEventsResponse(vararg results: String): String {
    val failed = results.count { "ErrorCode" in it }
    return """{"FailedEntryCount":$failed,"Entries":[${results.joinToString(",")}]}"""
}

/** Every entry in the request it is answering succeeds. */
private fun allPublished(body: String): String =
    putEventsResponse(*sourcesOf(body).map(::published).toTypedArray())

/**
 * Answers the request it was given, rejecting the entries whose `Source` is in [failing].
 *
 * Built from the request rather than hardcoded because the resubmission rounds send a *shorter*
 * request: a canned two-entry response would be read positionally against a one-entry resubmission
 * and hand the resent entry the first entry's `EventId`.
 */
private fun publishedExcept(body: String, code: String, vararg failing: String): String =
    putEventsResponse(
        *sourcesOf(body).map { if (it in failing) rejected(code) else published(it) }.toTypedArray(),
    )

private fun entries(count: Int): List<PutEventsEntry> =
    (1..count).map { PutEventsEntry(source = "s$it", detailType = "T", detail = "{}") }

/**
 * A [BatchRetry] that records what it was asked to wait instead of waiting — the same seam
 * `aws-dynamodb`'s batch tests use, for the same reason.
 *
 * `random = { 0.5 }` rather than 1.0 so the assertions distinguish full jitter from a bare
 * exponential; the 100ms base keeps the whole sequence inside the default 25-second budget.
 */
private fun recordingBackoff(into: MutableList<Long>): BatchRetry = BatchRetry(
    config = RetryConfig(throttlingBaseDelayMillis = 100),
    random = { 0.5 },
    sleep = { into += it },
)

// ---------------------------------------------------------------------------------------------

class PutEventsAllChunkingTest {

    /**
     * The headline bug: `PutEvents` rejects the whole request above 10 entries, so an unchunked
     * 23-entry batch is a `ValidationException` at runtime rather than 23 published events.
     */
    @Test
    fun chunksIntoRequestsOfTen() = runTest {
        val h = EventBridgeHarness()
        val results = harnessEventBridge(h) { allPublished(h.bodies.last()) to HttpStatusCode.OK }
            .putEventsAll(entries(23))

        assertEquals(listOf(10, 10, 3), h.bodies.map { entriesOf(it).size })
        assertEquals(23, results.size)
    }

    /** Exactly 10 is one request, not two — an off-by-one here doubles the request count. */
    @Test
    fun exactlyTenIsASingleRequest() = runTest {
        val h = EventBridgeHarness()
        harnessEventBridge(h) { allPublished(h.bodies.last()) to HttpStatusCode.OK }
            .putEventsAll(entries(10))

        assertEquals(1, h.requests.size)
    }

    /** The result list is positional against the request, across chunk boundaries. */
    @Test
    fun returnsResultsInRequestOrder() = runTest {
        val h = EventBridgeHarness()
        val results = harnessEventBridge(h) { allPublished(h.bodies.last()) to HttpStatusCode.OK }
            .putEventsAll(entries(23))

        assertEquals((1..23).map { "id-s$it" }, results.map { it.eventId })
    }

    /** An empty batch is a no-op, not an empty request AWS would reject. */
    @Test
    fun emptyBatchSendsNothing() = runTest {
        val h = EventBridgeHarness()
        val results = harnessEventBridge(h) { allPublished(h.bodies.last()) to HttpStatusCode.OK }
            .putEventsAll(emptyList())

        assertTrue(h.requests.isEmpty())
        assertTrue(results.isEmpty())
    }
}

class PutEventsAllResubmissionTest {

    /**
     * **The case this function exists for.** EventBridge throttles *entries*, inside an HTTP 200,
     * so the transport's retry loop never sees it. Only the throttled entry may be resubmitted:
     * resending the whole chunk would publish the two that succeeded a second time.
     */
    @Test
    fun resubmitsOnlyTheThrottledEntries() = runTest {
        val h = EventBridgeHarness()
        val slept = mutableListOf<Long>()
        val results = harnessEventBridge(h) { call ->
            when (call) {
                0 -> publishedExcept(h.bodies.last(), "ThrottlingException", "s2")
                else -> allPublished(h.bodies.last())
            } to HttpStatusCode.OK
        }.putEventsAll(entries(3), backoff = recordingBackoff(slept))

        assertEquals(2, h.requests.size)
        assertEquals(listOf("s2"), sourcesOf(h.bodies[1]), "only the failed entry may be resent")
        assertEquals(listOf("id-s1", "id-s2", "id-s3"), results.map { it.eventId })
    }

    /** `InternalFailure` is the other code AWS documents as retryable, and is treated identically. */
    @Test
    fun resubmitsInternalFailure() = runTest {
        val h = EventBridgeHarness()
        val results = harnessEventBridge(h) { call ->
            when (call) {
                0 -> publishedExcept(h.bodies.last(), "InternalFailure", "s1")
                else -> allPublished(h.bodies.last())
            } to HttpStatusCode.OK
        }.putEventsAll(entries(2), backoff = recordingBackoff(mutableListOf()))

        assertEquals(listOf("s1"), sourcesOf(h.bodies[1]))
        assertEquals(listOf("id-s1", "id-s2"), results.map { it.eventId })
    }

    /**
     * Resubmitting a throttled entry is a *throttling* retry and must be paced like one.
     *
     * 50/100/200 is exactly full jitter — `random * base * 2^retry` with `random` pinned to 0.5 —
     * so this also pins down that the jitter is applied rather than the raw exponential. Asserting
     * `currentTime` stayed at zero is what proves the wait went through [BatchRetry.sleep] and not
     * through `delay`, which `runTest`'s virtual clock would otherwise hide.
     */
    @Test
    fun backsOffExponentiallyBetweenRounds() = runTest {
        val h = EventBridgeHarness()
        val slept = mutableListOf<Long>()
        harnessEventBridge(h) { call ->
            when {
                call < 3 -> putEventsResponse(rejected("ThrottlingException"))
                else -> allPublished(h.bodies.last())
            } to HttpStatusCode.OK
        }.putEventsAll(entries(1), backoff = recordingBackoff(slept))

        assertEquals(4, h.requests.size, "three throttled rounds, then the successful one")
        assertEquals(listOf(50L, 100L, 200L), slept)
        assertEquals(0L, testScheduler.currentTime, "backoff must go through the injected sleep")
    }

    /** The budget is per chunk: a second chunk starts its backoff accounting from zero. */
    @Test
    fun theBackoffBudgetIsPerChunk() = runTest {
        val h = EventBridgeHarness()
        val slept = mutableListOf<Long>()
        // Chunk 1 (call 0) throttles one entry, chunk 2 (call 2) throttles one entry.
        harnessEventBridge(h) { call ->
            when (call) {
                0, 2 -> publishedExcept(
                    h.bodies.last(), "ThrottlingException", sourcesOf(h.bodies.last()).first(),
                )

                else -> allPublished(h.bodies.last())
            } to HttpStatusCode.OK
        }.putEventsAll(entries(12), backoff = recordingBackoff(slept))

        // 50 for the first chunk's single resubmission, then 50 again — not 100 — for the second.
        assertEquals(listOf(50L, 50L), slept, "each chunk gets its own budget and round counter")
    }
}

class PutEventsAllFailureTest {

    /**
     * A non-retryable per-entry code stops the batch, and the exception carries both halves.
     *
     * The entry that was merely throttled is reported as failed rather than resubmitted: once the
     * chunk is doomed, spending the backoff budget on it only delays the caller's discovery and
     * publishes more events it will have to reconcile.
     */
    @Test
    fun terminalCodeStopsTheBatchAndCarriesBothHalves() = runTest {
        val h = EventBridgeHarness()
        val slept = mutableListOf<Long>()
        val failure = assertFailsWith<PutEventsPartialFailureException> {
            harnessEventBridge(h) {
                putEventsResponse(
                    published("s1"),
                    rejected("MalformedDetail"),
                    rejected("ThrottlingException"),
                ) to HttpStatusCode.OK
            }.putEventsAll(entries(3), backoff = recordingBackoff(slept))
        }

        assertEquals("PutEventsPartialFailure", failure.code)
        assertEquals(200, failure.statusCode, "EventBridge answered 200; saying otherwise misdescribes it")
        assertEquals(listOf("id-s1"), failure.succeeded.map { it.eventId })
        assertEquals(listOf("s2", "s3"), failure.failed.map { it.first.source }, "in request order")
        assertEquals(listOf("MalformedDetail", "ThrottlingException"), failure.failed.map { it.second.errorCode })
        assertEquals(1, h.requests.size, "the throttled entry must not be resubmitted into a doomed batch")
        assertTrue(slept.isEmpty())
        assertTrue("MalformedDetail" in (failure.message ?: ""), "the message should name the code")
    }

    /** A terminal failure in the second chunk stops the third from being sent at all. */
    @Test
    fun aTerminalFailureStopsLaterChunks() = runTest {
        val h = EventBridgeHarness()
        val failure = assertFailsWith<PutEventsPartialFailureException> {
            harnessEventBridge(h) { call ->
                when (call) {
                    1 -> publishedExcept(h.bodies.last(), "InvalidArgument", "s11")
                    else -> allPublished(h.bodies.last())
                } to HttpStatusCode.OK
            }.putEventsAll(entries(23))
        }

        assertEquals(2, h.requests.size, "the third chunk must never be sent")
        assertEquals(19, failure.succeeded.size, "chunk 1 in full, plus nine of chunk 2")
        assertEquals(listOf("s11"), failure.failed.map { it.first.source })
    }

    /**
     * The budget stops the loop before [maxRounds], and the call still throws rather than reporting
     * a partially published batch as a success.
     */
    @Test
    fun exhaustingTheBackoffBudgetThrows() = runTest {
        val h = EventBridgeHarness()
        val slept = mutableListOf<Long>()
        val backoff = BatchRetry(
            // 100 + 200 + 400 fits in 750ms; the fourth wait of 800ms does not.
            config = RetryConfig(throttlingBaseDelayMillis = 100, maxTotalRetryDuration = 750.milliseconds),
            random = { 1.0 },
            sleep = { slept += it },
        )

        val failure = assertFailsWith<PutEventsPartialFailureException> {
            harnessEventBridge(h) {
                publishedExcept(h.bodies.last(), "ThrottlingException", "s2") to HttpStatusCode.OK
            }.putEventsAll(entries(2), backoff = backoff)
        }

        assertEquals(listOf(100L, 200L, 400L), slept, "the budget must stop the loop before maxRounds")
        assertEquals(4, h.requests.size, "three resubmissions, then give up")
        assertTrue("750ms" in (failure.message ?: ""), "the message should name the budget it hit")
        assertEquals(listOf("s2"), failure.failed.map { it.first.source })
        // Every round republished the entry that succeeded? No — only the failed one is resent, so
        // the success is reported exactly once even after three resubmissions.
        assertEquals(listOf("id-s1"), failure.succeeded.map { it.eventId })
    }

    /** Running out of rounds throws the same exception, carrying the same state. */
    @Test
    fun exhaustingMaxRoundsThrows() = runTest {
        val h = EventBridgeHarness()
        val failure = assertFailsWith<PutEventsPartialFailureException> {
            harnessEventBridge(h) {
                putEventsResponse(rejected("ThrottlingException")) to HttpStatusCode.OK
            }.putEventsAll(entries(1), maxRounds = 2, backoff = recordingBackoff(mutableListOf()))
        }

        assertEquals(2, h.requests.size)
        assertTrue("2 rounds" in (failure.message ?: ""))
        assertTrue(failure.succeeded.isEmpty())
        assertEquals(1, failure.failed.size)
    }

    /**
     * The failed entries are the caller's own objects, so the documented recovery — republish only
     * what failed — is a single expression and cannot silently double-publish the successes.
     */
    @Test
    fun theFailedEntriesCanBeResubmittedDirectly() = runTest {
        val h = EventBridgeHarness()
        val failure = assertFailsWith<PutEventsPartialFailureException> {
            harnessEventBridge(h) {
                putEventsResponse(published("s1"), rejected("MalformedDetail")) to HttpStatusCode.OK
            }.putEventsAll(entries(2))
        }

        assertEquals(entries(2).drop(1), failure.failed.map { it.first })
    }

    /** A whole-request error is still the transport's to map — this path must not swallow it. */
    @Test
    fun wholeRequestErrorsStillSurfaceTyped() = runTest {
        assertFailsWith<ResourceNotFoundException> {
            harnessEventBridge(EventBridgeHarness()) {
                """{"__type":"ResourceNotFoundException","message":"bus not found"}""" to
                    HttpStatusCode.BadRequest
            }.putEventsAll(entries(1))
        }
    }
}

class PutEventsAllValidationTest {

    /**
     * One oversized entry fails the *entire* request server-side, with a message that does not say
     * which entry was at fault. Checking client-side names it, and costs no round trip.
     */
    @Test
    fun rejectsAnOversizedEntryBeforeSending() = runTest {
        val h = EventBridgeHarness()
        val oversized = PutEventsEntry(source = "s", detailType = "T", detail = "a".repeat(256 * 1024 + 1))

        val failure = assertFailsWith<ValidationException> {
            harnessEventBridge(h) { allPublished(h.bodies.last()) to HttpStatusCode.OK }
                .putEventsAll(entries(2) + oversized)
        }

        assertTrue("entry 2" in (failure.message ?: ""), "the message must name the offending index")
        assertTrue(h.requests.isEmpty(), "nothing may be published when the batch cannot succeed")
    }

    /**
     * The limit is in **bytes**, not characters. A detail of 200,000 three-byte characters is
     * comfortably under any character-count check and 600 KB on the wire.
     */
    @Test
    fun sizeIsMeasuredInUtf8Bytes() = runTest {
        val h = EventBridgeHarness()
        assertFailsWith<ValidationException> {
            harnessEventBridge(h) { allPublished(h.bodies.last()) to HttpStatusCode.OK }
                .putEventsAll(listOf(PutEventsEntry(source = "s", detail = "€".repeat(200_000))))
        }
        assertTrue(h.requests.isEmpty())
    }

    /** An entry just under the limit is sent, not rejected — the check must not be off by a field. */
    @Test
    fun acceptsAnEntryJustUnderTheLimit() = runTest {
        val h = EventBridgeHarness()
        // 256 KB total across Source + DetailType + Detail.
        val detail = "a".repeat(256 * 1024 - "s".length - "T".length)

        harnessEventBridge(h) { allPublished(h.bodies.last()) to HttpStatusCode.OK }
            .putEventsAll(listOf(PutEventsEntry(source = "s", detailType = "T", detail = detail)))

        assertEquals(1, h.requests.size)
    }
}
