package com.steamstreet.awskt.logs

import com.steamstreet.awskt.core.awsEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end proof against real CloudWatch Logs Insights.
 *
 * See `aws-kms`'s `LiveKmsTest` for why these exist alongside the MockEngine suite. Self-skips
 * without credentials:
 *
 * ```
 * eval "$(aws configure export-credentials --profile <profile> --format env)" \
 *   && AWS_REGION=us-west-2 SMOKE_LOG_GROUP=/aws/lambda/awskt-native-smoke \
 *      ./gradlew :aws:aws-cloudwatch-logs:jvmTest
 * ```
 *
 * ### These are the tests the mock suite structurally cannot write
 *
 * Every interesting property of Insights is a property of the *service's* asynchrony: that a
 * `Running` query returns partial rows, that a query takes a variable number of polls to finish,
 * that a stopped query stays stopped. A MockEngine proves this client handles a **scripted**
 * sequence of statuses; only a real query proves the sequence it scripted is the one AWS produces.
 *
 * The log group needs to exist. It does **not** need to contain anything — a query over an empty
 * group completes with zero rows, which exercises the whole cycle and is a perfectly good assertion.
 *
 * `runTest` is used with **real** delays here, unlike the hermetic suite: `query` polls with
 * `delay`, and a virtual clock would spin through the polls without giving AWS time to finish. The
 * `timeout` values below are therefore real seconds.
 */
class LiveLogsTest {

    private fun logGroup(): String? {
        if (awsEnv("AWS_ACCESS_KEY_ID").isNullOrEmpty()) return null
        return awsEnv("SMOKE_LOG_GROUP")?.takeIf { it.isNotBlank() }
    }

    private fun logs() = Logs { caInfo = awsEnv("SMOKE_CA_BUNDLE") }

    /**
     * Epoch **seconds**, which is what Insights takes — see [StartQueryRequest].
     *
     * `kotlin.time.Clock` rather than `kotlinx-datetime`: this module has no date-time dependency
     * and does not need one for a division, and the convention plugin already opts into
     * `ExperimentalTime` for the whole repository.
     */
    private fun nowSeconds(): Long = kotlin.time.Clock.System.now().epochSeconds

    /**
     * The whole cycle — start, poll to completion, read statistics — against a real query.
     *
     * A query over a historical window on a possibly-empty group is deliberate: it completes fast,
     * costs almost nothing, and proves everything this module is responsible for. Matching rows
     * would prove something about the log group instead.
     */
    @Test
    fun runsAQueryToCompletion() = runTest {
        val group = logGroup() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_LOG_GROUP")
            return@runTest
        }

        logs().use { logs ->
            val response = logs.query(
                StartQueryRequest(
                    queryString = "fields @timestamp, @message | sort @timestamp desc | limit 5",
                    startTime = nowSeconds() - 3600,
                    endTime = nowSeconds(),
                    logGroupNames = listOf(group),
                ),
                timeout = 60.seconds,
            )

            // The contract of `query`: it returns only on Complete, never on Running.
            assertTrue(response.isComplete, "query returned with status '${response.status}'")
            assertTrue(response.rows.size <= 5, "the limit was not respected")
            // Statistics come back even for a zero-row result, and bytesScanned is the billed number.
            assertTrue(response.statistics != null, "no statistics came back")
            println(
                "[live] Insights query completed: ${response.rows.size} row(s), " +
                    "${response.statistics?.bytesScanned} bytes scanned",
            )
        }
    }

    /**
     * A `stats` query, which is the shape whose rows carry no `@ptr`.
     *
     * Worth a live run of its own because the row shape genuinely differs: `ResultRow.pointer` is
     * null and `selected` is the whole row, and the hermetic test for that asserts against a
     * hand-written fixture rather than against what Insights emits.
     */
    @Test
    fun runsAStatsQueryWhoseRowsHaveNoPointer() = runTest {
        val group = logGroup() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_LOG_GROUP")
            return@runTest
        }

        logs().use { logs ->
            val response = logs.query(
                StartQueryRequest(
                    queryString = "stats count(*) as events",
                    startTime = nowSeconds() - 3600,
                    endTime = nowSeconds(),
                    logGroupNames = listOf(group),
                ),
                timeout = 60.seconds,
            )

            assertTrue(response.isComplete)
            response.rows.forEach { row ->
                assertEquals(null, row.pointer, "a stats row should carry no @ptr")
                assertEquals(row.values, row.selected, "a stats row has no system columns to drop")
            }
            println("[live] Insights stats query returned ${response.rows.size} row(s)")
        }
    }

    /**
     * A query that does not compile must fail at `StartQuery`, before any polling.
     *
     * The message carries the compile error with its position, which is the whole reason
     * [MalformedQueryException]'s KDoc says to surface it — and the only way to see the real
     * message is to ask the real service.
     */
    @Test
    fun reportsACompileErrorFromTheService() = runTest {
        val group = logGroup() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_LOG_GROUP")
            return@runTest
        }

        logs().use { logs ->
            var reported: String? = null
            try {
                logs.query(
                    StartQueryRequest(
                        queryString = "fields @timestamp | nonsenseCommand foo bar",
                        startTime = nowSeconds() - 3600,
                        endTime = nowSeconds(),
                        logGroupNames = listOf(group),
                    ),
                    timeout = 30.seconds,
                )
            } catch (malformed: MalformedQueryException) {
                reported = malformed.message
            }

            assertTrue(reported != null, "an invalid query should raise MalformedQueryException")
            println("[live] Insights rejected an invalid query: $reported")
        }
    }

    /** Starting a query and stopping it immediately — the path that keeps concurrency slots free. */
    @Test
    fun startsAndStopsAQuery() = runTest {
        val group = logGroup() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_LOG_GROUP")
            return@runTest
        }

        logs().use { logs ->
            val queryId = logs.startQuery(
                StartQueryRequest(
                    queryString = "fields @timestamp, @message | limit 10000",
                    startTime = nowSeconds() - 86_400,
                    endTime = nowSeconds(),
                    logGroupNames = listOf(group),
                ),
            ).queryId

            assertTrue(!queryId.isNullOrBlank(), "StartQuery returned no queryId")
            // Either outcome is correct: true if it was still running, false if it had already
            // finished — which on a small group it very well may have.
            val stopped = logs.stopQuery(queryId)
            println("[live] Insights query $queryId stopped=$stopped")

            // Wait for the query to actually reach a terminal status. Cancellation is asynchronous
            // and bounded: a query on a small window ends within a second or two either way.
            var status = logs.getQueryResults(GetQueryResultsRequest(queryId)).status
            var polls = 0
            while (status !in QueryStatus.TERMINAL && polls++ < 15) {
                // A real second, not a virtual one — see the class KDoc.
                withContext(Dispatchers.Default) { delay(1.seconds) }
                status = logs.getQueryResults(GetQueryResultsRequest(queryId)).status
            }
            println("[live] Insights query $queryId settled at $status after $polls poll(s)")
            assertTrue(status in QueryStatus.TERMINAL, "query never reached a terminal status: $status")

            // Stopping is idempotent from the service's side: a query that settled at `Cancelled`
            // answers `success = true` again, and one that got to `Complete` first is the
            // "already ended" error, which the convenience swallows. Neither is an error here.
            val again = logs.stopQuery(queryId)
            println("[live] second stop of a $status query returned $again")
            assertEquals(status == QueryStatus.CANCELLED, again, "second stop of a $status query")
        }
    }

    /**
     * Stopping a query that ended **on its own** — the case that, on the wire, is not `success =
     * false` but `InvalidParameterException: Query is already ended with Complete`. This is what
     * `query`'s timeout path hits when the query completes between its last poll and the stop, and
     * the reason the convenience swallows that specific message.
     */
    @Test
    fun stoppingACompletedQueryIsANonEvent() = runTest {
        val group = logGroup() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_LOG_GROUP")
            return@runTest
        }

        logs().use { logs ->
            val queryId = logs.startQuery(
                StartQueryRequest(
                    queryString = "fields @timestamp | limit 1",
                    startTime = nowSeconds() - 3600,
                    endTime = nowSeconds(),
                    logGroupNames = listOf(group),
                ),
            ).queryId!!

            var status = logs.getQueryResults(GetQueryResultsRequest(queryId)).status
            var polls = 0
            while (status != QueryStatus.COMPLETE && polls++ < 30) {
                withContext(Dispatchers.Default) { delay(1.seconds) }
                status = logs.getQueryResults(GetQueryResultsRequest(queryId)).status
            }
            assertEquals(QueryStatus.COMPLETE, status, "query did not complete in time")

            assertEquals(false, logs.stopQuery(queryId), "stopping a Complete query")
            // And the raw operation is what the convenience is protecting callers from.
            val raw = runCatching { logs.stopQuery(StopQueryRequest(queryId)) }.exceptionOrNull()
            assertTrue(raw is InvalidParameterException, "raw StopQuery on a Complete query threw $raw")
            assertContains(raw.message!!, "already ended")
            println("[live] raw StopQuery on a Complete query: ${raw.message}")
        }
    }
}
