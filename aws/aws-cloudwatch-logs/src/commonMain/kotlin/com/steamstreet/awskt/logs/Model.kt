package com.steamstreet.awskt.logs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request and response types for CloudWatch Logs **Insights**.
 *
 * ### Insights is asynchronous, and that shapes everything here
 *
 * `StartQuery` does not return results. It returns a **query id**, and the caller polls
 * `GetQueryResults` until the query reaches a terminal status. Every awkward property of this
 * module follows from that, including the two traps below.
 *
 * ### `GetQueryResults` returns rows before the query has finished
 *
 * A `Running` query answers **HTTP 200 with a populated `results` list** that is not the answer —
 * it is however much has been matched so far. A caller that reads `results` without checking
 * [GetQueryResultsResponse.status] gets a plausible, incomplete, non-deterministic answer, and gets
 * a *different* one on the next run. This is the single most likely way to misuse Insights, and
 * [Logs.query] exists so it does not have to be remembered.
 *
 * ### Times are epoch **seconds**, not milliseconds
 *
 * [StartQueryRequest.startTime] and [StartQueryRequest.endTime] are seconds since the epoch. That
 * is not the convention elsewhere in CloudWatch Logs — `FilterLogEvents` and `GetLogEvents` on the
 * *same service* take milliseconds — so a value carried between the two APIs is wrong by a factor
 * of a thousand. Passing milliseconds here asks for a window starting in the year 56000 and returns
 * an empty result rather than an error, which is why it survives code review. See
 * [StartQueryRequest] for the helpers that avoid writing the number by hand.
 */

/** Terminal and non-terminal query statuses. Strings, not an enum — AWS extends the set. */
public object QueryStatus {
    /** Accepted, not started. Not terminal. */
    public const val SCHEDULED: String = "Scheduled"

    /** In progress. **Results are partial** — not terminal. */
    public const val RUNNING: String = "Running"

    /** Finished. The only status on which results are the whole answer. */
    public const val COMPLETE: String = "Complete"

    public const val FAILED: String = "Failed"
    public const val CANCELLED: String = "Cancelled"

    /** The query exceeded the service's own time limit. Terminal, and usually means "narrow the range". */
    public const val TIMEOUT: String = "Timeout"
    public const val UNKNOWN: String = "Unknown"

    /**
     * Statuses after which polling is pointless.
     *
     * Used by [Logs.query] to decide when to stop. `Complete` is the only one of them that means
     * the results are trustworthy — the rest are terminal *failures*, which is why the helper
     * raises on them rather than returning whatever partial rows arrived.
     */
    public val TERMINAL: Set<String> = setOf(COMPLETE, FAILED, CANCELLED, TIMEOUT, UNKNOWN)
}

/**
 * `StartQuery`.
 *
 * @param queryString the Insights query. Note that a query with no `| limit` and no `stats` is
 *   bounded by [limit] rather than being unbounded.
 * @param startTime epoch **seconds** — see the file KDoc. [inLastHour] and [between] build these.
 * @param endTime epoch **seconds**, inclusive.
 * @param logGroupNames the groups to search. Exactly one of this and [logGroupIdentifiers] should
 *   be set.
 * @param logGroupIdentifiers the newer form: accepts names *or* ARNs, and is the only one that can
 *   name a log group in **another account** through a monitoring account link. Prefer it for
 *   anything cross-account; prefer [logGroupNames] for its brevity otherwise.
 * @param limit rows returned, at most **10,000**. This is a cap on the *result*, not on the data
 *   scanned — an unselective query over a large group is billed for everything it read whatever
 *   this says.
 */
@Serializable
public data class StartQueryRequest(
    @SerialName("queryString") public val queryString: String,
    @SerialName("startTime") public val startTime: Long,
    @SerialName("endTime") public val endTime: Long,
    @SerialName("logGroupNames") public val logGroupNames: List<String>? = null,
    @SerialName("logGroupIdentifiers") public val logGroupIdentifiers: List<String>? = null,
    @SerialName("limit") public val limit: Int? = null,
) {
    public companion object {
        /**
         * A query over the last [seconds], ending now.
         *
         * @param nowEpochSeconds the current time in **seconds**. Passed in rather than read from a
         *   clock because this module has no clock: `aws-core`'s is internal, and a multiplatform
         *   `commonMain` has no portable one that does not drag in a date-time dependency for two
         *   arithmetic operations. Callers on the JVM have `System.currentTimeMillis() / 1000`; a
         *   Lambda has the invocation deadline; a test has whatever it wants.
         */
        public fun inLastSeconds(
            queryString: String,
            seconds: Long,
            nowEpochSeconds: Long,
            logGroupNames: List<String>? = null,
            logGroupIdentifiers: List<String>? = null,
            limit: Int? = null,
        ): StartQueryRequest = StartQueryRequest(
            queryString = queryString,
            startTime = nowEpochSeconds - seconds,
            endTime = nowEpochSeconds,
            logGroupNames = logGroupNames,
            logGroupIdentifiers = logGroupIdentifiers,
            limit = limit,
        )

        /**
         * A query over an explicit window given in **milliseconds**, converted here.
         *
         * The one place this module accepts millisecond input, and it exists precisely because
         * everything else a caller holds is in milliseconds — `System.currentTimeMillis()`, a
         * `Message`'s `SentTimestamp`, `FilterLogEvents`' own bounds. Doing the division at the
         * call site is where the factor-of-1000 bug gets written; doing it here is where it does
         * not.
         */
        public fun betweenMillis(
            queryString: String,
            startEpochMillis: Long,
            endEpochMillis: Long,
            logGroupNames: List<String>? = null,
            logGroupIdentifiers: List<String>? = null,
            limit: Int? = null,
        ): StartQueryRequest = StartQueryRequest(
            queryString = queryString,
            startTime = startEpochMillis / 1000,
            endTime = endEpochMillis / 1000,
            logGroupNames = logGroupNames,
            logGroupIdentifiers = logGroupIdentifiers,
            limit = limit,
        )
    }
}

@Serializable
public data class StartQueryResponse(
    @SerialName("queryId") public val queryId: String? = null,
)

@Serializable
public data class GetQueryResultsRequest(
    @SerialName("queryId") public val queryId: String,
)

/**
 * One field of one result row.
 *
 * Insights returns rows as **lists of name/value pairs** rather than as objects, because a query's
 * output columns are decided by the query text and can differ per row. [ResultRow] is the sugar
 * over that.
 */
@Serializable
public data class ResultField(
    @SerialName("field") public val field: String? = null,
    @SerialName("value") public val value: String? = null,
)

/**
 * What the query cost.
 *
 * [bytesScanned] is the billed quantity, and it is a `Double` on the wire rather than an integer —
 * AWS's own shape, kept as sent. It bears no relation to how many rows came back: a query that
 * matched nothing still scanned, and still billed for, everything in the time range.
 */
@Serializable
public data class QueryStatistics(
    @SerialName("recordsMatched") public val recordsMatched: Double = 0.0,
    @SerialName("recordsScanned") public val recordsScanned: Double = 0.0,
    @SerialName("bytesScanned") public val bytesScanned: Double = 0.0,
)

/**
 * `GetQueryResults`' result.
 *
 * **[results] is partial unless [status] is [QueryStatus.COMPLETE].** See the file KDoc — this is
 * the module's headline trap, and [isComplete] / [Logs.query] are the two ways not to fall into it.
 */
@Serializable
public data class GetQueryResultsResponse(
    @SerialName("status") public val status: String? = null,
    @SerialName("results") public val results: List<List<ResultField>> = emptyList(),
    @SerialName("statistics") public val statistics: QueryStatistics? = null,
) {
    /** Whether [results] is the whole answer rather than however much has matched so far. */
    public val isComplete: Boolean get() = status == QueryStatus.COMPLETE

    /** Whether polling this query again could change anything. */
    public val isTerminal: Boolean get() = status in QueryStatus.TERMINAL

    /** [results] as rows keyed by field name. See [ResultRow]. */
    public val rows: List<ResultRow> get() = results.map(::ResultRow)
}

/**
 * One result row, addressable by field name.
 *
 * Insights hands back a list of name/value pairs per row; this is the map view of it, which is what
 * essentially every caller wants and which is fiddly enough to get wrong once per project.
 *
 * ### `@ptr` is present and is not a field you selected
 *
 * Unless the query uses `stats`, every row carries a `@ptr` — an opaque pointer to the underlying
 * log event, usable with `GetLogRecord` to fetch the whole unparsed event. It appears in [fields]
 * alongside the columns actually selected, so code that iterates fields to build output should
 * expect it. [selected] omits it and the other `@`-prefixed system fields.
 */
public class ResultRow(
    /** The row exactly as Insights sent it, in column order. */
    public val fields: List<ResultField>,
) {
    private val byName: Map<String, String> =
        fields.mapNotNull { field -> field.field?.let { it to field.value.orEmpty() } }.toMap()

    /** A field's value, or null when the query did not produce that column for this row. */
    public operator fun get(field: String): String? = byName[field]

    /** Every field, keyed by name — `@ptr` included. */
    public val values: Map<String, String> get() = byName

    /**
     * Every field the query actually selected: [values] without the `@`-prefixed system columns.
     *
     * `@ptr` is always there and `@timestamp`, `@message` and `@log` are there whenever the query
     * asks for them by those names — so this drops genuinely-requested columns too, which is why it
     * sits alongside [values] rather than replacing it. Use it for "turn this row into a record",
     * and [values] when a specific `@` column matters.
     */
    public val selected: Map<String, String> get() = byName.filterKeys { !it.startsWith("@") }

    /** The pointer to the underlying log event, for `GetLogRecord`. Absent on a `stats` query. */
    public val pointer: String? get() = byName["@ptr"]

    override fun toString(): String = "ResultRow(${byName.keys.joinToString()})"
}

@Serializable
public data class StopQueryRequest(
    @SerialName("queryId") public val queryId: String,
)

/**
 * `StopQuery`'s result.
 *
 * [success] is false when the query had **already finished**, which is not an error and is the
 * common case when stopping defensively — see [Logs.stopQuery].
 */
@Serializable
public data class StopQueryResponse(
    @SerialName("success") public val success: Boolean = false,
)
