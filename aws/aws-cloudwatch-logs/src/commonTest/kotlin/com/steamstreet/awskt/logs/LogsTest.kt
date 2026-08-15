package com.steamstreet.awskt.logs

import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.StaticCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

internal class LogsHarness {
    val requests = mutableListOf<HttpRequestData>()
    val bodies = mutableListOf<String>()
    val targets: List<String> get() = requests.map { it.headers["X-Amz-Target"].orEmpty() }
}

internal fun harnessLogs(
    harness: LogsHarness,
    responder: (Int) -> Pair<String, HttpStatusCode>,
): Logs {
    var call = 0
    val engine = MockEngine { request ->
        harness.requests += request
        harness.bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        val (body, status) = responder(call++)
        respond(body, status)
    }
    return DefaultLogs(
        AwsServiceClient(
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET", "TOKEN")),
            endpoint = resolveEndpoint("logs", "us-west-2", "https://logs.us-west-2.amazonaws.com"),
            region = "us-west-2",
            protocol = LOGS_PROTOCOL,
            clock = { 1_700_000_000_000 },
            random = { 1.0 },
            sleep = {},
        ),
    )
}

private fun bodyJson(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

private const val QUERY_ID = "b1e2c3d4-0000-1111-2222-333344445555"
private const val STARTED = """{"queryId":"$QUERY_ID"}"""

private fun results(status: String, vararg rows: String) =
    """{"status":"$status","results":[${rows.joinToString(",")}],
        "statistics":{"recordsMatched":2.0,"recordsScanned":1000.0,"bytesScanned":54321.0}}"""

private const val ROW_ONE =
    """[{"field":"@timestamp","value":"2026-08-14 10:00:00.000"},
        {"field":"@message","value":"hello"},
        {"field":"@ptr","value":"CnQKKQ=="}]"""

private const val ROW_TWO =
    """[{"field":"@timestamp","value":"2026-08-14 10:00:01.000"},
        {"field":"@message","value":"world"},
        {"field":"@ptr","value":"CnQKKh=="}]"""

private val request = StartQueryRequest(
    queryString = "fields @timestamp, @message | sort @timestamp desc | limit 20",
    startTime = 1_700_000_000,
    endTime = 1_700_003_600,
    logGroupNames = listOf("/aws/lambda/my-fn"),
)

class LogsProtocolTest {

    @Test
    fun targetsTheDatedLogsPrefixOverAwsJson1_1() = runTest {
        val h = LogsHarness()
        harnessLogs(h) { STARTED to HttpStatusCode.OK }.startQuery(request)

        val sent = h.requests.single()
        // Logs_20140328 — a dated service name, not derivable from anything.
        assertEquals("Logs_20140328.StartQuery", sent.headers["X-Amz-Target"])
        assertEquals("application/x-amz-json-1.1", sent.body.contentType?.toString())
    }

    @Test
    fun eachOperationSendsItsOwnTarget() = runTest {
        val h = LogsHarness()
        val logs = harnessLogs(h) { call ->
            when (call) {
                0 -> STARTED to HttpStatusCode.OK
                1 -> results(QueryStatus.COMPLETE) to HttpStatusCode.OK
                else -> """{"success":true}""" to HttpStatusCode.OK
            }
        }
        logs.startQuery(request)
        logs.getQueryResults(GetQueryResultsRequest(QUERY_ID))
        logs.stopQuery(StopQueryRequest(QUERY_ID))

        assertEquals(
            listOf(
                "Logs_20140328.StartQuery",
                "Logs_20140328.GetQueryResults",
                "Logs_20140328.StopQuery",
            ),
            h.targets,
        )
    }

    @Test
    fun serializesTheQueryAndItsWindowInLowerCamelCase() = runTest {
        val h = LogsHarness()
        harnessLogs(h) { STARTED to HttpStatusCode.OK }.startQuery(request)

        val body = bodyJson(h.bodies.single())
        // Lower camel case, unlike the PascalCase of DynamoDB, EventBridge, KMS and SQS. The
        // services genuinely differ and each module matches its own.
        assertEquals(request.queryString, body["queryString"]?.jsonPrimitive?.content)
        assertEquals(1_700_000_000, body["startTime"]?.jsonPrimitive?.content?.toLong())
        assertEquals(1_700_003_600, body["endTime"]?.jsonPrimitive?.content?.toLong())
        assertEquals("/aws/lambda/my-fn", body["logGroupNames"]!!.jsonArray.single().jsonPrimitive.content)
        assertNull(body["limit"])
    }

    @Test
    fun parsesResultsStatusAndStatistics() = runTest {
        val response = harnessLogs(LogsHarness()) {
            results(QueryStatus.COMPLETE, ROW_ONE, ROW_TWO) to HttpStatusCode.OK
        }.getQueryResults(GetQueryResultsRequest(QUERY_ID))

        assertTrue(response.isComplete)
        assertEquals(2, response.results.size)
        assertEquals(54321.0, response.statistics?.bytesScanned)
        assertEquals("hello", response.rows[0]["@message"])
    }
}

/**
 * The epoch-seconds trap. `FilterLogEvents` on the *same service* takes milliseconds, so a value
 * carried between the two is wrong by a factor of a thousand — and asks for a window in the year
 * 56000, which returns an empty result rather than an error.
 */
class TimeWindowTest {

    @Test
    fun betweenMillisConvertsToSeconds() {
        val built = StartQueryRequest.betweenMillis(
            queryString = "fields @message",
            startEpochMillis = 1_700_000_000_000,
            endEpochMillis = 1_700_003_600_000,
        )
        assertEquals(1_700_000_000, built.startTime)
        assertEquals(1_700_003_600, built.endTime)
    }

    @Test
    fun inLastSecondsWindowsBackwardsFromTheGivenNow() {
        val built = StartQueryRequest.inLastSeconds(
            queryString = "fields @message",
            seconds = 3600,
            nowEpochSeconds = 1_700_003_600,
        )
        assertEquals(1_700_000_000, built.startTime)
        assertEquals(1_700_003_600, built.endTime)
    }
}

class ResultRowTest {

    private val row = ResultRow(
        listOf(
            ResultField("@timestamp", "2026-08-14 10:00:00.000"),
            ResultField("@message", "hello"),
            ResultField("@ptr", "CnQKKQ=="),
            ResultField("level", "ERROR"),
        ),
    )

    @Test
    fun addressesFieldsByName() {
        assertEquals("hello", row["@message"])
        assertEquals("ERROR", row["level"])
        assertNull(row["absent"])
    }

    /** `@ptr` is always in a non-`stats` result whether or not the query asked for it. */
    @Test
    fun selectedDropsTheSystemColumnsAndValuesKeepsThem() {
        assertEquals(mapOf("level" to "ERROR"), row.selected)
        assertTrue(row.values.containsKey("@ptr"))
        assertEquals("CnQKKQ==", row.pointer)
    }

    @Test
    fun aStatsRowHasNoPointer() {
        val stats = ResultRow(listOf(ResultField("count", "42")))
        assertNull(stats.pointer)
        assertEquals(mapOf("count" to "42"), stats.selected)
    }

    @Test
    fun aFieldWithNoValueReadsAsEmptyRatherThanBeingDropped() {
        val sparse = ResultRow(listOf(ResultField("maybe", null)))
        assertEquals("", sparse["maybe"])
    }
}

/**
 * The polling helper, which is the reason this module is more than three thin wrappers.
 */
class QueryTest {

    @Test
    fun pollsUntilCompleteAndReturnsOnlyTheFinishedResult() = runTest {
        val h = LogsHarness()
        val response = harnessLogs(h) { call ->
            when (call) {
                0 -> STARTED to HttpStatusCode.OK
                1 -> results(QueryStatus.SCHEDULED) to HttpStatusCode.OK
                // A Running query returns rows. They are NOT the answer, and must not be returned.
                2 -> results(QueryStatus.RUNNING, ROW_ONE) to HttpStatusCode.OK
                else -> results(QueryStatus.COMPLETE, ROW_ONE, ROW_TWO) to HttpStatusCode.OK
            }
        }.query(request, initialPollInterval = 1.milliseconds, maxPollInterval = 1.milliseconds)

        assertTrue(response.isComplete)
        assertEquals(2, response.rows.size, "the partial Running result must not be returned")
        assertEquals(listOf("hello", "world"), response.rows.map { it["@message"] })
        // start + three polls.
        assertEquals(4, h.requests.size)
    }

    @Test
    fun passesTheQueryIdFromStartToEveryPoll() = runTest {
        val h = LogsHarness()
        harnessLogs(h) { call ->
            if (call == 0) STARTED to HttpStatusCode.OK
            else results(QueryStatus.COMPLETE) to HttpStatusCode.OK
        }.query(request, initialPollInterval = 1.milliseconds)

        assertEquals(QUERY_ID, bodyJson(h.bodies[1])["queryId"]?.jsonPrimitive?.content)
    }

    /**
     * A `Timeout` is the *service* giving up, and it carries rows. Returning them as an answer is
     * the failure this guards.
     */
    @Test
    fun raisesOnATerminalFailureRatherThanReturningItsPartialRows() = runTest {
        val failure = assertFailsWith<QueryFailedException> {
            harnessLogs(LogsHarness()) { call ->
                if (call == 0) STARTED to HttpStatusCode.OK
                else results(QueryStatus.TIMEOUT, ROW_ONE) to HttpStatusCode.OK
            }.query(request, initialPollInterval = 1.milliseconds)
        }

        assertEquals(QueryStatus.TIMEOUT, failure.status)
        assertEquals(QUERY_ID, failure.queryId)
        // Carried for inspection, deliberately not returned as success.
        assertEquals(1, failure.partialResults.size)
        assertEquals(200, failure.statusCode, "the failure arrived inside a successful HTTP call")
        assertContains(failure.message!!, "narrow the time range")
    }

    @Test
    fun raisesOnAFailedQuery() = runTest {
        val failure = assertFailsWith<QueryFailedException> {
            harnessLogs(LogsHarness()) { call ->
                if (call == 0) STARTED to HttpStatusCode.OK
                else results(QueryStatus.FAILED) to HttpStatusCode.OK
            }.query(request, initialPollInterval = 1.milliseconds)
        }
        assertEquals(QueryStatus.FAILED, failure.status)
    }

    /**
     * An abandoned query holds one of the account's concurrent-query slots until it times out on
     * its own — see [LimitExceededException]. The stop is what stops a caller starving itself.
     */
    @Test
    fun stopsTheQueryBeforeRaisingOnTimeout() = runTest {
        val h = LogsHarness()
        // A TestTimeSource advanced once per response, so the budget is reached deterministically
        // rather than by racing the wall clock against virtual `delay`s.
        val time = TestTimeSource()
        val failure = assertFailsWith<QueryTimedOutException> {
            harnessLogs(h) { call ->
                time += 10.milliseconds
                when (call) {
                    0 -> STARTED to HttpStatusCode.OK
                    else -> results(QueryStatus.RUNNING, ROW_ONE) to HttpStatusCode.OK
                }
            }.query(
                request,
                timeout = 30.milliseconds,
                initialPollInterval = 5.milliseconds,
                maxPollInterval = 5.milliseconds,
                timeSource = time,
            )
        }

        assertEquals(QUERY_ID, failure.queryId)
        assertEquals(QueryStatus.RUNNING, failure.lastStatus)
        assertEquals(
            "Logs_20140328.StopQuery",
            h.targets.last(),
            "an abandoned query must be stopped, or it holds a concurrency slot",
        )
        assertContains(failure.message!!, QUERY_ID)
    }

    /**
     * Backoff, asserted on the arithmetic rather than by counting requests against a clock.
     *
     * The first version of this test counted polls inside a time budget and failed — not because
     * the backoff was wrong, but because `runTest` makes `delay` virtual while
     * `TimeSource.Monotonic` keeps real time, so the loop spun. That is what the injectable
     * `timeSource` on [query] is for, and it is why this assertion moved to the function that
     * actually does the arithmetic.
     */
    @Test
    fun pollIntervalGrowsGeometricallyToItsCeiling() {
        assertEquals(15.milliseconds, nextPollInterval(10.milliseconds, 100.milliseconds))
        assertEquals(22.5.milliseconds, nextPollInterval(15.milliseconds, 100.milliseconds))
        // Clamped, and stays clamped.
        assertEquals(100.milliseconds, nextPollInterval(80.milliseconds, 100.milliseconds))
        assertEquals(100.milliseconds, nextPollInterval(100.milliseconds, 100.milliseconds))

        // From the production default, a minute of polling costs tens of calls rather than hundreds.
        var interval = 250.milliseconds
        var total = kotlin.time.Duration.ZERO
        var polls = 0
        while (total < 60.seconds) {
            total += interval
            interval = nextPollInterval(interval, 2.seconds)
            polls++
        }
        assertTrue(polls < 40, "60s of polling should cost well under 40 calls, was $polls")
    }

    @Test
    fun aMalformedQueryFailsAtStartWithoutPolling() = runTest {
        val h = LogsHarness()
        val failure = assertFailsWith<MalformedQueryException> {
            harnessLogs(h) {
                """{"__type":"MalformedQueryException","message":"Unknown function at 1:8"}""" to
                    HttpStatusCode.BadRequest
            }.query(request, initialPollInterval = 1.milliseconds)
        }

        assertContains(failure.message!!, "1:8")
        assertEquals(1, h.requests.size, "a query that does not compile must not be polled")
    }

    @Test
    fun queryRowsReturnsJustTheRows() = runTest {
        val rows = harnessLogs(LogsHarness()) { call ->
            if (call == 0) STARTED to HttpStatusCode.OK
            else results(QueryStatus.COMPLETE, ROW_ONE) to HttpStatusCode.OK
        }.queryRows(request)

        assertEquals(listOf("hello"), rows.map { it["@message"] })
    }
}

class StopQueryTest {

    /** "It had already finished" is what the caller wanted, not an error. */
    @Test
    fun treatsAnAlreadyFinishedQueryAsANonEvent() = runTest {
        val stopped = harnessLogs(LogsHarness()) { """{"success":false}""" to HttpStatusCode.OK }
            .stopQuery(QUERY_ID)
        assertEquals(false, stopped)
    }

    /**
     * The shape real AWS actually sends for a query in a terminal status — captured on the wire on
     * 2026-08-15 — is a 400 `InvalidParameterException`, not `success = false`.
     */
    @Test
    fun treatsTheServicesAlreadyEndedErrorAsANonEvent() = runTest {
        val stopped = harnessLogs(LogsHarness()) {
            """{"__type":"InvalidParameterException","message":"Query is already ended with Complete (Service: AWSLogs; Status Code: 400; Error Code: InvalidParameterException; Request ID: 2f2932c0-bc7e-4214-bd04-aaf18d69bbf3; Proxy: null)"}""" to HttpStatusCode.BadRequest
        }.stopQuery(QUERY_ID)
        assertEquals(false, stopped)
    }

    /** The same code for a genuinely bad parameter is not "already ended" and must not be hidden. */
    @Test
    fun doesNotSwallowAnUnrelatedInvalidParameter() = runTest {
        assertFailsWith<InvalidParameterException> {
            harnessLogs(LogsHarness()) {
                """{"__type":"InvalidParameterException","message":"1 validation error detected: Value 'x' at 'queryId' failed to satisfy constraint"}""" to HttpStatusCode.BadRequest
            }.stopQuery(QUERY_ID)
        }
    }

    @Test
    fun swallowsAnAgedOutQueryId() = runTest {
        val stopped = harnessLogs(LogsHarness()) {
            """{"__type":"ResourceNotFoundException","message":"gone"}""" to HttpStatusCode.BadRequest
        }.stopQuery(QUERY_ID)
        assertEquals(false, stopped)
    }

    /** A genuine failure still propagates — swallowing everything would hide a permissions problem. */
    @Test
    fun doesNotSwallowAnUnrelatedFailure() = runTest {
        assertFailsWith<AccessDeniedException> {
            harnessLogs(LogsHarness()) {
                """{"__type":"AccessDeniedException","message":"no"}""" to HttpStatusCode.Forbidden
            }.stopQuery(QUERY_ID)
        }
    }
}

class LogsErrorMappingTest {

    private fun errorBody(code: String) = """{"__type":"$code","message":"nope"}"""

    @Test
    fun mapsKnownCodesOntoTypedExceptions() = runTest {
        suspend fun failingWith(code: String): Throwable = runCatching {
            harnessLogs(LogsHarness()) { errorBody(code) to HttpStatusCode.BadRequest }
                .startQuery(request)
        }.exceptionOrNull()!!

        assertTrue(failingWith("MalformedQueryException") is MalformedQueryException)
        assertTrue(failingWith("ResourceNotFoundException") is ResourceNotFoundException)
        assertTrue(failingWith("InvalidParameterException") is InvalidParameterException)
        assertTrue(failingWith("LimitExceededException") is LimitExceededException)
        assertTrue(failingWith("ServiceUnavailableException") is ServiceUnavailableException)
    }

    @Test
    fun unknownCodesFallThroughToTheBaseType() = runTest {
        val failure = assertFailsWith<LogsException> {
            harnessLogs(LogsHarness()) { errorBody("SomethingAwsAddedLater") to HttpStatusCode.BadRequest }
                .startQuery(request)
        }
        assertEquals("SomethingAwsAddedLater", failure.code)
        assertTrue(failure.cause != null, "the transport exception was discarded")
    }
}
