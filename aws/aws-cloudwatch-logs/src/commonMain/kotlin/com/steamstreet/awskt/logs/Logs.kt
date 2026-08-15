package com.steamstreet.awskt.logs

import com.steamstreet.awskt.core.AwsCallObserver
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsHttpTimeouts
import com.steamstreet.awskt.core.AwsProtocol
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.awsHttpClient
import com.steamstreet.awskt.core.callJson
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.core.resolveRegion
import io.ktor.client.HttpClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * CloudWatch Logs' AWS-JSON 1.1 dialect.
 *
 * The target prefix is `Logs_20140328` — a dated service name rather than a product name, which is
 * not derivable from anything and is the sort of value that has to be read off the wire. The
 * endpoint and signing prefixes are the ordinary `logs`.
 */
public val LOGS_PROTOCOL: AwsProtocol =
    AwsProtocol.awsJson1_1(endpointPrefix = "logs", targetPrefix = "Logs_20140328")

/**
 * A CloudWatch Logs client covering **Insights queries**.
 *
 * `StartQuery`, `GetQueryResults` and `StopQuery` — the three operations that make up an Insights
 * query — plus [query], which runs the whole cycle.
 *
 * ### What is not here
 *
 * `PutLogEvents` is out, and deliberately: a Lambda's stdout already reaches CloudWatch, so a
 * function calling `PutLogEvents` to log is paying for an API call to do what a `println` does for
 * free — and `awskt-logging` is this repository's answer to structured logging. `FilterLogEvents`
 * and `GetLogEvents` are also out; they are a different, non-Insights way to read logs, and a caller
 * that wants them can add them through the extension seam. Log group and retention management is
 * control plane.
 *
 * ### Two things worth knowing before you use it
 *
 * 1. **`GetQueryResults` returns rows before the query is finished.** A `Running` query answers 200
 *    with a partial `results` list. Check [GetQueryResultsResponse.isComplete], or use [query],
 *    which will not return anything else.
 * 2. **`startTime` and `endTime` are epoch seconds**, unlike `FilterLogEvents` on the same service.
 *    See [StartQueryRequest].
 *
 * ### Extending it
 *
 * [client] is public and no operation below has privileged access to it:
 *
 * ```kotlin
 * @Serializable
 * data class GetLogRecordRequest(@SerialName("logRecordPointer") val logRecordPointer: String)
 *
 * @Serializable
 * data class GetLogRecordResponse(
 *     @SerialName("logRecord") val logRecord: Map<String, String> = emptyMap(),
 * )
 *
 * // Fetches the whole unparsed event behind a row's `@ptr`.
 * suspend fun Logs.getLogRecord(pointer: String): GetLogRecordResponse =
 *     client.callJson(
 *         "GetLogRecord",
 *         GetLogRecordRequest(pointer),
 *         GetLogRecordRequest.serializer(),
 *         GetLogRecordResponse.serializer(),
 *     )
 * ```
 */
public interface Logs : AutoCloseable {
    /** The signed transport. Public because it is the extension seam — see the interface KDoc. */
    public val client: AwsServiceClient

    /**
     * Starts a query and returns its id. **Does not return results.**
     *
     * `IDEMPOTENT` in the transport's sense — a replayed `StartQuery` starts a *second* query rather
     * than double-applying anything, and since a query has no side effect beyond consuming one of
     * the account's concurrent-query slots, that is safe. It is not free: see
     * [LimitExceededException]. [query] avoids the question by never leaving a query running.
     */
    public suspend fun startQuery(request: StartQueryRequest): StartQueryResponse

    /**
     * Fetches a query's current state.
     *
     * **Read [GetQueryResultsResponse] before using this directly** — the results it returns are
     * partial until the status says otherwise, and nothing about a partial result looks partial.
     */
    public suspend fun getQueryResults(request: GetQueryResultsRequest): GetQueryResultsResponse

    /**
     * Stops a running query.
     *
     * [StopQueryResponse.success] is false when the query had already finished, which is not an
     * error — see [stopQuery], the convenience that treats it as the non-event it is.
     */
    public suspend fun stopQuery(request: StopQueryRequest): StopQueryResponse
}

/** Configuration for [Logs]. */
public class LogsConfig {
    public var region: String? = null
    public var endpointUrl: String? = null
    public var credentialsProvider: AwsCredentialsProvider? = null

    /**
     * A client to send on, instead of one built here.
     *
     * Supplying one **bypasses [caInfo] and [httpTimeouts]**: those are arguments to the client this
     * factory would have built, and a client the caller already owns is configured by the caller.
     */
    public var httpClient: HttpClient? = null
    public var retryConfig: RetryConfig = RetryConfig()

    /** CA bundle for the native Curl engine. Null uses the system trust store. */
    public var caInfo: String? = null

    /**
     * Per-attempt time limits. The library defaults suit this module.
     *
     * Worth being explicit about why, because Insights *queries* are slow: the slowness is on AWS's
     * side, spread across many short `GetQueryResults` polls, and no single HTTP request here waits
     * for a query to finish. The bound that matters for a slow query is [query]'s `timeout`, which
     * is a different thing entirely.
     */
    public var httpTimeouts: AwsHttpTimeouts = AwsHttpTimeouts()

    /** Notified of every attempt, retry decision and give-up. Null means no instrumentation. */
    public var observer: AwsCallObserver? = null
}

/** Builds a CloudWatch Logs client. */
public fun Logs(configure: LogsConfig.() -> Unit = {}): Logs {
    val config = LogsConfig().apply(configure)
    val region = resolveRegion(config.region)
    // One binding for the effective client, handed to both the transport and the close path — see
    // the same note in `DynamoDb()`, where splitting the two leaked the client we had just built.
    val httpClient = config.httpClient ?: awsHttpClient(config.caInfo, config.httpTimeouts)
    return DefaultLogs(
        client = AwsServiceClient(
            httpClient = httpClient,
            credentialsProvider = config.credentialsProvider ?: defaultCredentialsProvider(),
            endpoint = resolveEndpoint("logs", region, config.endpointUrl),
            region = region,
            protocol = LOGS_PROTOCOL,
            retryConfig = config.retryConfig,
            observer = config.observer,
        ),
        ownsHttpClient = config.httpClient == null,
        httpClient = httpClient,
    )
}

internal class DefaultLogs(
    override val client: AwsServiceClient,
    private val ownsHttpClient: Boolean = false,
    private val httpClient: HttpClient? = null,
) : Logs {

    override suspend fun startQuery(request: StartQueryRequest): StartQueryResponse = mapErrors {
        client.callJson(
            "StartQuery", request, StartQueryRequest.serializer(), StartQueryResponse.serializer(),
            safety = OperationSafety.IDEMPOTENT,
        )
    }

    override suspend fun getQueryResults(
        request: GetQueryResultsRequest,
    ): GetQueryResultsResponse = mapErrors {
        client.callJson(
            "GetQueryResults", request, GetQueryResultsRequest.serializer(),
            GetQueryResultsResponse.serializer(), safety = OperationSafety.IDEMPOTENT,
        )
    }

    override suspend fun stopQuery(request: StopQueryRequest): StopQueryResponse = mapErrors {
        client.callJson(
            "StopQuery", request, StopQueryRequest.serializer(), StopQueryResponse.serializer(),
            safety = OperationSafety.IDEMPOTENT,
        )
    }

    override fun close() {
        if (ownsHttpClient) httpClient?.close()
    }
}

// -- Running a query -----------------------------------------------------------------------------

/**
 * Starts a query, polls it to completion, and returns the finished result.
 *
 * ### This is the operation Insights actually has; the three below it are its parts
 *
 * `StartQuery` + poll `GetQueryResults` + `StopQuery` on the way out is not a convenience wrapper,
 * it is the shape every Insights caller has to write. Leaving it to callers means each one
 * re-derives four things, and the first two are silent when wrong:
 *
 * 1. **`Running` returns rows.** A caller that polls once and reads `results` gets a partial answer
 *    that looks exactly like a complete one, and a different partial answer next time. This returns
 *    only on [QueryStatus.COMPLETE].
 * 2. **`Timeout`, `Failed` and `Cancelled` also carry rows.** Treating "the response parsed" as
 *    success turns a query the *service* gave up on into a short result. Those raise
 *    [QueryFailedException], which carries the partial rows for inspection without pretending they
 *    are the answer.
 * 3. **An abandoned query keeps running** and holds one of the account's concurrent-query slots
 *    until it times out on its own — see [LimitExceededException]. This stops the query on the
 *    timeout path *and on cancellation*, which is the path that actually leaks in production: a
 *    Lambda hitting its own deadline cancels the coroutine and, without this, leaves a query
 *    running behind it on every invocation.
 * 4. **Polling at a fixed interval is either slow or wasteful.** Most Insights queries finish in a
 *    second or two; some take a minute. A fixed 1-second poll adds up to a second of latency to
 *    every fast query, and a fixed 100 ms poll spends 600 API calls on a slow one. This starts
 *    tight and backs off.
 *
 * ### Cancellation
 *
 * If the calling coroutine is cancelled, the query is stopped with [NonCancellable] before the
 * `CancellationException` propagates. `NonCancellable` is load-bearing rather than defensive: the
 * scope is already cancelled at that point, so a plain suspending call would itself be cancelled
 * immediately and the stop would never be sent — which is precisely the leak this is preventing.
 *
 * @param request what to run. See [StartQueryRequest] for the epoch-**seconds** trap and the
 *   helpers that avoid it.
 * @param timeout how long to wait for the query, **not** an HTTP timeout — it bounds the whole
 *   start-and-poll cycle. Exceeding it stops the query and raises [QueryTimedOutException].
 * @param initialPollInterval the first gap between polls. Short, so a fast query is not delayed.
 * @param maxPollInterval the ceiling the interval backs off to.
 * @param timeSource where [timeout] is measured against. A seam for tests, and the same one
 *   `AwsServiceClient` exposes as `clock` for the same reason: under `runTest` the coroutine
 *   scheduler makes `delay` virtual while `TimeSource.Monotonic` keeps ticking in real time, so a
 *   test that does not inject this measures the wall clock while its sleeps cost nothing — and
 *   spins. Production callers should leave it alone.
 * @return the completed result, including [GetQueryResultsResponse.statistics] — worth reading,
 *   since `bytesScanned` is what the query is billed on and bears no relation to how many rows came
 *   back.
 * @throws QueryFailedException if the query reached a terminal status other than `Complete`.
 * @throws QueryTimedOutException if [timeout] elapsed first. The query is stopped before this is
 *   raised.
 * @throws MalformedQueryException if the query does not compile — from `StartQuery`, before any
 *   polling.
 */
public suspend fun Logs.query(
    request: StartQueryRequest,
    timeout: Duration = 60.seconds,
    initialPollInterval: Duration = 250.milliseconds,
    maxPollInterval: Duration = 2.seconds,
    timeSource: TimeSource = TimeSource.Monotonic,
): GetQueryResultsResponse {
    val queryId = startQuery(request).queryId
        ?: throw LogsException("MissingQueryId", "StartQuery returned no queryId.", 200)

    val started = timeSource.markNow()
    var interval = initialPollInterval

    try {
        while (true) {
            val results = getQueryResults(GetQueryResultsRequest(queryId))

            if (results.isComplete) return results
            if (results.isTerminal) {
                throw QueryFailedException(
                    queryId = queryId,
                    status = results.status,
                    partialResults = results.rows,
                    message = "Insights query $queryId ended with status '${results.status}' after " +
                        "${started.elapsedNow()}. ${results.results.size} partial row(s) are carried " +
                        "on this exception and are NOT a complete answer." +
                        if (results.status == QueryStatus.TIMEOUT) {
                            " A Timeout is the service giving up, not this client — narrow the time " +
                                "range or make the query more selective."
                        } else {
                            ""
                        },
                )
            }

            if (started.elapsedNow() + interval > timeout) {
                // Stopped before raising, so the abandoned query does not hold a concurrency slot.
                stopQuery(queryId)
                throw QueryTimedOutException(
                    queryId = queryId,
                    lastStatus = results.status,
                    message = "Insights query $queryId was still '${results.status}' after " +
                        "${started.elapsedNow()} (budget $timeout). It has been stopped. Poll " +
                        "getQueryResults(\"$queryId\") directly to keep waiting instead.",
                )
            }

            delay(interval)
            interval = nextPollInterval(interval, maxPollInterval)
        }
    } catch (cancelled: CancellationException) {
        // NonCancellable because the scope is already cancelled — a plain call here would be
        // cancelled before it was sent, which is exactly the leak this exists to prevent.
        withContext(NonCancellable) { stopQuery(queryId) }
        throw cancelled
    }
}

/**
 * The next gap between polls: geometric growth to a ceiling.
 *
 * Extracted so the arithmetic can be asserted directly. Testing it through the poll loop instead
 * would mean counting requests against a clock, and the count depends on how the test's scheduler
 * treats `delay` — which measures the harness rather than the backoff.
 *
 * 1.5 rather than 2: a query that finishes in 300 ms costs two polls either way, and one that takes
 * a minute costs about thirty rather than two hundred and forty — while doubling would overshoot
 * the ceiling in four steps and lose most of the resolution in between.
 */
internal fun nextPollInterval(current: Duration, max: Duration): Duration =
    minOf(current * 1.5, max)

/**
 * [query], returning just the rows.
 *
 * Discards [GetQueryResultsResponse.statistics], which is the only reason not to use it: `bytesScanned`
 * is what an Insights query is billed on, and it is invisible from the row count.
 */
public suspend fun Logs.queryRows(
    request: StartQueryRequest,
    timeout: Duration = 60.seconds,
): List<ResultRow> = query(request, timeout).rows

/**
 * Stops a query, treating "it had already finished" as the non-event it is.
 *
 * `StopQuery` answers `success = false` — not an error — for a query that has already reached a
 * terminal status, and raises [ResourceNotFoundException] for one that has aged out entirely. Both
 * mean "there is nothing running", which is what the caller wanted, so both are swallowed. A
 * genuine failure — no permission, service unavailable — still propagates.
 *
 * @return whether a running query was actually stopped.
 */
public suspend fun Logs.stopQuery(queryId: String): Boolean = try {
    stopQuery(StopQueryRequest(queryId)).success
} catch (alreadyGone: ResourceNotFoundException) {
    false
}
