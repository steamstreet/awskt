package com.steamstreet.dynamokt.exposed

import com.steamstreet.awskt.dynamodb.BatchGetItemRequest
import com.steamstreet.awskt.dynamodb.GetItemRequest
import com.steamstreet.awskt.dynamodb.QueryRequest
import com.steamstreet.awskt.dynamodb.QueryResponse
import com.steamstreet.awskt.dynamodb.ScanRequest
import com.steamstreet.awskt.dynamodb.ScanResponse
import com.steamstreet.awskt.dynamodb.Select
import com.steamstreet.awskt.dynamodb.orNullIfEmpty
import com.steamstreet.dynamokt.AttributeValue
import com.steamstreet.awskt.dynamodb.KeysAndAttributes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

/**
 * Result of index matching - determines which DynamoDB operation to use
 */
internal sealed class IndexMatch {
    /**
     * Full primary key equality with nothing left to filter - use GetItem
     */
    data class GetItem(val pkColumn: Column<*>, val skColumn: Column<*>?) : IndexMatch()

    /**
     * Partition key matches - use Query on the table or one of its indices.
     *
     * @property residual the conjuncts that are not key conditions of this index.
     *   They are rendered as a `FilterExpression` so they are applied server-side
     *   rather than silently dropped.
     */
    data class Query(
        val index: Index?,
        val pkColumn: Column<*>,
        val skColumn: Column<*>?,
        val pkCondition: ColumnCondition.Eq,
        val sortKeyCondition: ColumnCondition?,
        val residual: Op<Boolean>?
    ) : IndexMatch()

    /**
     * No index matches - would require a Scan (not allowed in select)
     */
    data object NoMatch : IndexMatch()
}

/**
 * Thrown when no table or index partition key is pinned with `eq`, so the query
 * cannot be served as a GetItem or Query. Use `scan()` instead.
 */
public class NoIndexMatchException(message: String) : IllegalArgumentException(message)

/**
 * Thrown by [Query.startAfter] when a pagination token cannot be decoded, so a
 * corrupt or foreign cursor surfaces as a caller error rather than a raw
 * serialization failure.
 */
public class InvalidPageTokenException(
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

/**
 * A table or index that could serve a query: its partition key must carry a
 * top-level equality condition, and its sort key may carry one range condition.
 */
private class IndexCandidate(
    val index: Index?,
    val pkColumn: Column<*>,
    val skColumn: Column<*>?
)

/** A candidate plus the way this particular where-tree splits across it. */
private class CandidatePlan(
    val candidate: IndexCandidate,
    val pkCondition: ColumnCondition.Eq,
    val sortKeyConditions: List<ColumnCondition>,
    val residual: List<Op<Boolean>>
) {
    /**
     * 1 = base-table full primary key equality, 2 = sort key constrained,
     * 3 = partition key only. Lower wins; ties break on candidate order.
     */
    val tier: Int
        get() = when {
            candidate.index == null &&
                (candidate.skColumn == null ||
                    (sortKeyConditions.size == 1 && sortKeyConditions[0] is ColumnCondition.Eq)) -> 1
            sortKeyConditions.isNotEmpty() -> 2
            else -> 3
        }
}

/**
 * Finds the best index match for the given operation.
 *
 * Every table, GSI and LSI whose partition key carries a top-level equality is a
 * candidate; the one whose sort key is also constrained wins over a bare
 * partition-key match, and the base table wins ties. Conditions the chosen index
 * cannot express as key conditions become the match's `residual`.
 */
internal fun Table.findIndexMatch(op: Op<Boolean>): IndexMatch {
    val conjuncts = flattenAnd(op)
    val parsed = conjuncts.map { keyConditionOrNull(it) }

    val tablePk = partitionKey
    val tableSk = sortKey
    // Bound outside buildList: inside it, `indices` would resolve to the list's own index range.
    val secondaryIndices = indices

    val candidates = buildList {
        if (tablePk != null) add(IndexCandidate(null, tablePk, tableSk))
        secondaryIndices.forEach { index ->
            when (index) {
                is GlobalSecondaryIndex -> add(IndexCandidate(index, index.partitionKey, index.sortKey))
                // An LSI shares the table's partition key, so it is only reachable when there is one.
                is LocalSecondaryIndex -> if (tablePk != null) add(IndexCandidate(index, tablePk, index.sortKey))
            }
        }
    }

    val plans = candidates.mapNotNull { candidate ->
        var pkCondition: ColumnCondition.Eq? = null
        val sortKeyConditions = mutableListOf<ColumnCondition>()
        val residual = mutableListOf<Op<Boolean>>()

        conjuncts.forEachIndexed { i, conjunct ->
            val condition = parsed[i]
            val column = condition?.conditionColumn
            when {
                condition is ColumnCondition.Eq && column == candidate.pkColumn && pkCondition == null ->
                    pkCondition = condition
                column != null && candidate.skColumn != null && column == candidate.skColumn ->
                    sortKeyConditions.add(condition)
                else -> residual.add(conjunct)
            }
        }

        pkCondition?.let { CandidatePlan(candidate, it, sortKeyConditions, residual) }
    }

    val chosen = plans.minByOrNull { it.tier } ?: return IndexMatch.NoMatch

    val sortKeyCondition = chosen.candidate.skColumn?.let {
        coalesceSortKeyConditions(it, chosen.sortKeyConditions)
    }

    // DynamoDB rejects a FilterExpression that mentions the queried index's own key attributes,
    // so anything left over on those columns has nowhere to go.
    val keyNames = setOfNotNull(chosen.candidate.pkColumn.name, chosen.candidate.skColumn?.name)
    val strandedKeyColumns = chosen.residual
        .flatMap { referencedColumns(it) }
        .filter { it.name in keyNames }
        .map { it.name }
        .distinct()
    require(strandedKeyColumns.isEmpty()) {
        "Condition on key attribute(s) ${strandedKeyColumns.joinToString()} cannot be expressed: " +
            "DynamoDB allows one condition per key attribute in a KeyConditionExpression and forbids " +
            "key attributes of the queried index in a FilterExpression."
    }

    val residual = chosen.residual.reduceOrNull { left, right -> AndOp(left, right) }

    if (chosen.tier == 1 && residual == null) {
        return IndexMatch.GetItem(chosen.candidate.pkColumn, chosen.candidate.skColumn)
    }

    return IndexMatch.Query(
        index = chosen.candidate.index,
        pkColumn = chosen.candidate.pkColumn,
        skColumn = chosen.candidate.skColumn,
        pkCondition = chosen.pkCondition,
        sortKeyCondition = sortKeyCondition,
        residual = residual
    )
}

/**
 * Reduce the conditions on a sort key to the single condition DynamoDB allows.
 * `ge` + `le` is the one combination that folds cleanly, into an (inclusive)
 * BETWEEN; anything else is rejected rather than silently narrowed.
 */
private fun coalesceSortKeyConditions(
    skColumn: Column<*>,
    conditions: List<ColumnCondition>
): ColumnCondition? {
    if (conditions.size <= 1) return conditions.firstOrNull()

    if (conditions.size == 2) {
        val ge = conditions.filterIsInstance<ColumnCondition.Ge>().singleOrNull()
        val le = conditions.filterIsInstance<ColumnCondition.Le>().singleOrNull()
        if (ge != null && le != null) {
            return ColumnCondition.Between(skColumn, ge.value, le.value)
        }
    }

    throw IllegalArgumentException(
        "Sort key '${skColumn.name}' has ${conditions.size} conditions. DynamoDB accepts one " +
            "condition per key attribute in a KeyConditionExpression, and key attributes cannot be " +
            "moved into a FilterExpression. Only 'ge' combined with 'le' folds into a BETWEEN."
    )
}

/**
 * Builds a DynamoDB key condition expression for the chosen index's keys.
 * Placeholders use the `attr` prefix so they never collide with the `fattr`
 * placeholders of a FilterExpression built into the same request.
 */
internal fun buildKeyConditionExpression(
    pkColumn: Column<*>,
    pkCondition: ColumnCondition.Eq,
    skColumn: Column<*>?,
    skCondition: ColumnCondition?,
    nameIndex: MutableMap<String, String>,
    valueIndex: MutableMap<String, AttributeValue>
): String {
    var attrCounter = 0

    fun nextNameKey() = "#attr${attrCounter++}"
    fun nextValueKey() = ":attr${attrCounter++}"

    val parts = mutableListOf<String>()

    run {
        val nameKey = nextNameKey()
        val valueKey = nextValueKey()
        nameIndex[nameKey] = pkColumn.name
        @Suppress("UNCHECKED_CAST")
        valueIndex[valueKey] = (pkColumn as Column<Any?>).toAttributeValue(pkCondition.value)
        parts.add("$nameKey = $valueKey")
    }

    if (skColumn != null && skCondition != null) {
        val nameKey = nextNameKey()
        nameIndex[nameKey] = skColumn.name

        @Suppress("UNCHECKED_CAST")
        val col = skColumn as Column<Any?>

        when (skCondition) {
            is ColumnCondition.Eq -> {
                val valueKey = nextValueKey()
                valueIndex[valueKey] = col.toAttributeValue(skCondition.value)
                parts.add("$nameKey = $valueKey")
            }
            is ColumnCondition.Gt -> {
                val valueKey = nextValueKey()
                valueIndex[valueKey] = col.toAttributeValue(skCondition.value)
                parts.add("$nameKey > $valueKey")
            }
            is ColumnCondition.Lt -> {
                val valueKey = nextValueKey()
                valueIndex[valueKey] = col.toAttributeValue(skCondition.value)
                parts.add("$nameKey < $valueKey")
            }
            is ColumnCondition.Ge -> {
                val valueKey = nextValueKey()
                valueIndex[valueKey] = col.toAttributeValue(skCondition.value)
                parts.add("$nameKey >= $valueKey")
            }
            is ColumnCondition.Le -> {
                val valueKey = nextValueKey()
                valueIndex[valueKey] = col.toAttributeValue(skCondition.value)
                parts.add("$nameKey <= $valueKey")
            }
            is ColumnCondition.Between -> {
                val valueKey1 = nextValueKey()
                val valueKey2 = nextValueKey()
                valueIndex[valueKey1] = col.toAttributeValue(skCondition.from)
                valueIndex[valueKey2] = col.toAttributeValue(skCondition.to)
                parts.add("$nameKey BETWEEN $valueKey1 AND $valueKey2")
            }
            is ColumnCondition.BeginsWith -> {
                val valueKey = nextValueKey()
                valueIndex[valueKey] = AttributeValue.S(skCondition.prefix)
                parts.add("begins_with($nameKey, $valueKey)")
            }
        }
    }

    return parts.joinToString(" AND ")
}

/**
 * A query builder for DynamoDB operations, similar to Exposed's Query class.
 * Supports method chaining for building queries.
 *
 * Example:
 * ```
 * Users.select(Users.id, Users.name).where { Users.id eq "123" }
 * Users.selectAll().where { Users.id eq "123" }
 * ```
 */
public class Query(
    public val table: Table,
    public val database: Database,
    private val selectedColumns: List<Column<*>>? = null // null means all columns
) {
    private var whereOp: Op<Boolean>? = null
    private var isScan: Boolean = false
    private var limitValue: Int? = null
    private var scanForward: Boolean = true
    private var startKey: Map<String, AttributeValue>? = null

    /**
     * Add a where clause to the query.
     *
     * @throws IllegalStateException if a where clause was already specified; a
     *   second call would silently discard the first, so use [andWhere] instead.
     */
    public fun where(op: SqlExpressionBuilder.() -> Op<Boolean>): Query {
        check(whereOp == null) { "where clause already specified; use andWhere" }
        whereOp = SqlExpressionBuilder().op()
        return this
    }

    /**
     * AND an additional condition into the existing where clause, or set it when
     * there is none yet.
     */
    public fun andWhere(op: SqlExpressionBuilder.() -> Op<Boolean>): Query {
        val addition = SqlExpressionBuilder().op()
        whereOp = whereOp?.let { AndOp(it, addition) } ?: addition
        return this
    }

    /**
     * Limit the maximum number of rows returned.
     *
     * For [page] this bounds the single page. For [asFlow] (and the terminal
     * operators built on it) this caps the total number of rows emitted across
     * all pages.
     */
    public fun limit(n: Int): Query {
        limitValue = n
        return this
    }

    /**
     * Control sort order for query (and index query) operations, mapping to
     * DynamoDB's `ScanIndexForward`. Defaults to ascending.
     *
     * Has no effect on scans or [IndexMatch.GetItem]-routed lookups.
     */
    public fun orderBy(descending: Boolean = false): Query {
        scanForward = !descending
        return this
    }

    /**
     * Resume a query after a previous page by supplying the opaque token
     * returned as [PageResult.nextToken]. Passing `null` is a no-op, so a
     * nullable request cursor can be forwarded directly.
     *
     * The token encoding matches the base `dynamokt` library
     * (`toJsonItemString()` / `fromJsonToItem()`), so tokens interoperate
     * between the two layers.
     */
    public fun startAfter(token: String?): Query {
        startKey = token?.let { raw ->
            try {
                raw.decodePageToken()
            } catch (e: IllegalArgumentException) {
                // kotlinx.serialization's SerializationException is itself an IllegalArgumentException.
                throw InvalidPageTokenException("Invalid page token: ${e.message}", e)
            }
        }
        return this
    }

    /**
     * Mark this as a scan operation (for when no index matches)
     */
    internal fun asScan(): Query {
        isScan = true
        return this
    }

    /**
     * Build the projection expression and attribute-name map for the selected
     * columns, or `null`/`null` when all columns are selected.
     */
    private fun buildProjection(): Pair<String?, Map<String, String>?> {
        val projectionExpression = selectedColumns?.let { cols ->
            cols.mapIndexed { index, _ -> "#proj$index" }.joinToString(", ")
        }
        val projectionNames = selectedColumns?.let { cols ->
            cols.mapIndexed { index, col -> "#proj$index" to col.name }.toMap()
        }
        return projectionExpression to projectionNames
    }

    private fun noIndexMatch(op: Op<Boolean>): Nothing {
        val conditionColumns = referencedColumns(op).map { it.name }
        throw NoIndexMatchException(
            "No index found for query on table '${table.tableName}'. " +
            "Columns in condition: $conditionColumns. " +
            "A query needs an equality condition on the partition key of the table or one of its " +
            "indices; use scan() to evaluate this condition as a filter instead."
        )
    }

    /**
     * The DynamoDB operation this query resolves to. Resolved once per terminal
     * so that routing, and any routing error, is identical across [asFlow],
     * [page] and [count].
     */
    private sealed class Route {
        class BatchGet(val keys: List<Pair<Any, Any?>>) : Route()
        class Scan(val filter: Op<Boolean>?) : Route()
        class Get(val op: Op<Boolean>, val match: IndexMatch.GetItem) : Route()
        class Index(val match: IndexMatch.Query) : Route()
    }

    private fun route(): Route {
        val op = whereOp

        // keys() maps to BatchGetItem, which takes no expressions at all.
        require(op == null || op is KeysOp || !containsKeysOp(op)) {
            "keys() cannot be combined with other conditions"
        }

        if (op is KeysOp) return Route.BatchGet(op.keys)
        if (op == null || isScan) return Route.Scan(op)

        return when (val match = table.findIndexMatch(op)) {
            is IndexMatch.NoMatch -> noIndexMatch(op)
            is IndexMatch.GetItem -> Route.Get(op, match)
            is IndexMatch.Query -> Route.Index(match)
        }
    }

    /**
     * Execute the query and return results as a Flow.
     *
     * Query and scan operations automatically follow `LastEvaluatedKey` across
     * every page, so unbounded reads return all matching rows without silent
     * first-page truncation. When [limit] is set it caps the total number of
     * rows emitted across all pages.
     *
     * Conditions the chosen index cannot express as key conditions are applied
     * server-side as a `FilterExpression`, so the emitted rows always satisfy the
     * full where clause.
     */
    public fun asFlow(): Flow<ResultRow> = buildFlow(limitValue)

    /**
     * [asFlow] with the request limit a terminal wants when the caller set none.
     * A page limit only bounds how much DynamoDB evaluates per request; the
     * loops still follow `LastEvaluatedKey`, so a filtered first page that comes
     * back empty does not end the flow early.
     */
    private fun buildFlow(effectiveLimit: Int?): Flow<ResultRow> {
        val (projectionExpression, projectionNames) = buildProjection()

        return when (val route = route()) {
            is Route.BatchGet -> executeBatchGet(route.keys, projectionExpression, projectionNames)
            is Route.Scan -> executeScan(route.filter, projectionExpression, projectionNames, effectiveLimit)
            is Route.Get -> executeGetItem(route.op, route.match, projectionExpression, projectionNames)
            is Route.Index -> executeQuery(route.match, projectionExpression, projectionNames, effectiveLimit)
        }
    }

    /**
     * Execute a single bounded page of this query and return the rows along
     * with an opaque [PageResult.nextToken] for resuming.
     *
     * Exactly one DynamoDB request is issued. The `nextToken` is surfaced
     * faithfully from `LastEvaluatedKey`: it may be non-null even on a short or
     * empty page (DynamoDB's 1 MB cap, or a `FilterExpression` that rejected the
     * whole page), and `null` only when the underlying request reports no more
     * pages.
     *
     * For [IndexMatch.GetItem]-routed lookups (full key supplied, nothing left to
     * filter) and batch-get (`keys(...)`) reads there is no cursor concept, so
     * `nextToken` is always `null`; [limit] and ordering are ignored on those paths.
     */
    public suspend fun page(): PageResult {
        val (projectionExpression, projectionNames) = buildProjection()

        return when (val route = route()) {
            is Route.BatchGet -> {
                val rows = executeBatchGet(route.keys, projectionExpression, projectionNames).toList()
                PageResult(rows, null)
            }
            is Route.Scan -> {
                val response = scanPage(route.filter, projectionExpression, projectionNames, startKey, limitValue)
                PageResult(
                    (response.items ?: emptyList()).map { ResultRow(table, it) },
                    response.lastEvaluatedKey?.encodePageToken()
                )
            }
            is Route.Get -> {
                val rows = executeGetItem(route.op, route.match, projectionExpression, projectionNames).toList()
                PageResult(rows, null)
            }
            is Route.Index -> {
                val response = queryPage(route.match, projectionExpression, projectionNames, startKey, limitValue)
                PageResult(
                    (response.items ?: emptyList()).map { ResultRow(table, it) },
                    response.lastEvaluatedKey?.encodePageToken()
                )
            }
        }
    }

    /**
     * Execute the query and collect all results into a list.
     */
    public suspend fun toList(): List<ResultRow> = asFlow().toList()

    /**
     * Execute the query and return the first result, or null if none.
     *
     * Collection is cancelled after the first row, and when no [limit] was set
     * the underlying request asks for a single row, so this does not paginate
     * the whole partition.
     */
    public suspend fun firstOrNull(): ResultRow? = buildFlow(limitValue ?: 1).firstOrNull()

    /**
     * Execute the query and return exactly one result.
     *
     * Reads at most two rows; the request limit is 2 when no [limit] was set.
     *
     * @throws NoSuchElementException if no results
     * @throws IllegalArgumentException if more than one result
     */
    public suspend fun single(): ResultRow {
        val rows = buildFlow(limitValue ?: 2).take(2).toList()
        if (rows.isEmpty()) throw NoSuchElementException("Query returned no results")
        require(rows.size == 1) { "Query returned more than one result" }
        return rows[0]
    }

    /**
     * Execute the query and return exactly one result, or null if none.
     *
     * Reads at most two rows; the request limit is 2 when no [limit] was set.
     *
     * @throws IllegalArgumentException if more than one result
     */
    public suspend fun singleOrNull(): ResultRow? {
        val rows = buildFlow(limitValue ?: 2).take(2).toList()
        require(rows.size <= 1) { "Query returned more than one result" }
        return rows.firstOrNull()
    }

    /**
     * Count the matching rows without transferring them.
     *
     * Queries use `Select=COUNT`; scans sum the `Count` DynamoDB reports per page
     * while projecting only the partition key. Both follow `LastEvaluatedKey` to
     * the end, so the result covers every page rather than the first one. When
     * [limit] is set it caps the returned count.
     *
     * @throws UnsupportedOperationException for `keys()` (batch-get) queries,
     *   which have no server-side count.
     */
    public suspend fun count(): Long {
        return when (val route = route()) {
            is Route.BatchGet -> throw UnsupportedOperationException(
                "count() is not supported for keys() queries"
            )
            is Route.Get -> {
                val keyProjection = "#cntpk"
                val keyNames = mapOf(keyProjection to route.match.pkColumn.name)
                if (executeGetItem(route.op, route.match, keyProjection, keyNames).firstOrNull() != null) 1L else 0L
            }
            is Route.Index -> countPages { start, pageLimit ->
                val response = queryPage(route.match, null, null, start, pageLimit, Select.Count)
                (response.count ?: response.items?.size ?: 0) to response.lastEvaluatedKey
            }
            is Route.Scan -> {
                // ScanRequest has no Select field, so the payload is trimmed with a
                // partition-key-only projection instead; Count is reported either way.
                val pkName = table.partitionKey?.name
                val projection = if (pkName != null) "#cntpk" else null
                val names = if (pkName != null) mapOf("#cntpk" to pkName) else null
                countPages { start, pageLimit ->
                    val response = scanPage(route.filter, projection, names, start, pageLimit)
                    (response.count ?: response.items?.size ?: 0) to response.lastEvaluatedKey
                }
            }
        }
    }

    /**
     * Walk every page of a counting request, summing the per-page counts and
     * stopping at [limit] when one is set.
     */
    private suspend inline fun countPages(
        page: (Map<String, AttributeValue>?, Int?) -> Pair<Int, Map<String, AttributeValue>?>
    ): Long {
        val cap = limitValue?.toLong()
        var total = 0L
        var exclusiveStart = startKey
        while (true) {
            val (count, last) = page(exclusiveStart, cap?.let { (it - total).toInt() })
            total += count
            if (cap != null && total >= cap) return cap
            exclusiveStart = last?.takeIf { it.isNotEmpty() } ?: break
        }
        return total
    }

    private fun executeGetItem(
        op: Op<Boolean>,
        match: IndexMatch.GetItem,
        projectionExpression: String?,
        projectionNames: Map<String, String>?
    ): Flow<ResultRow> = flow {
        val keyValues = extractKeyValues(op)

        val key = buildMap {
            @Suppress("UNCHECKED_CAST")
            val pkCol = match.pkColumn as Column<Any?>
            put(pkCol.name, pkCol.toAttributeValue(keyValues[match.pkColumn]))

            if (match.skColumn != null) {
                @Suppress("UNCHECKED_CAST")
                val skCol = match.skColumn as Column<Any?>
                put(skCol.name, skCol.toAttributeValue(keyValues[match.skColumn]))
            }
        }

        val result = database.client.getItem(
            GetItemRequest(
                tableName = database.resolveTableName(table),
                key = key,
                consistentRead = database.defaultConsistentRead,
                projectionExpression = projectionExpression,
                expressionAttributeNames = projectionNames?.takeIf { projectionExpression != null },
            ),
        )

        result.item?.let { emit(ResultRow(table, it)) }
    }

    /**
     * Issue a single query (or index query) request.
     *
     * The key conditions and the residual filter are built into the same
     * placeholder maps: key placeholders use the `attr` prefix and filter
     * placeholders the `fattr` prefix, so the two never collide.
     */
    private suspend fun queryPage(
        match: IndexMatch.Query,
        projectionExpression: String?,
        projectionNames: Map<String, String>?,
        exclusiveStart: Map<String, AttributeValue>?,
        pageLimit: Int?,
        select: Select? = null
    ): QueryResponse {
        val nameIndex = mutableMapOf<String, String>()
        val valueIndex = mutableMapOf<String, AttributeValue>()

        val keyConditionExpression = buildKeyConditionExpression(
            match.pkColumn,
            match.pkCondition,
            match.skColumn,
            match.sortKeyCondition,
            nameIndex,
            valueIndex
        )

        // Conditions the index cannot express as key conditions are applied server-side.
        val filterExpression = match.residual?.let { buildFilterExpression(it, nameIndex, valueIndex) }

        // Merge projection names into nameIndex
        projectionNames?.let { nameIndex.putAll(it) }

        return database.client.query(
            QueryRequest(
                tableName = database.resolveTableName(table),
                indexName = match.index?.name,
                keyConditionExpression = keyConditionExpression,
                filterExpression = filterExpression,
                projectionExpression = projectionExpression,
                expressionAttributeNames = nameIndex.orNullIfEmpty(),
                expressionAttributeValues = valueIndex.orNullIfEmpty(),
                // Only a GSI is eventually consistent; the base table and its LSIs honour the flag.
                consistentRead = if (match.index is GlobalSecondaryIndex) false else database.defaultConsistentRead,
                scanIndexForward = false.takeIf { !scanForward },
                exclusiveStartKey = exclusiveStart,
                limit = pageLimit,
                select = select,
            ),
        )
    }

    private fun executeQuery(
        match: IndexMatch.Query,
        projectionExpression: String?,
        projectionNames: Map<String, String>?,
        totalLimit: Int?
    ): Flow<ResultRow> = flow {
        var exclusiveStart = startKey
        var remaining = totalLimit
        while (true) {
            val response = queryPage(match, projectionExpression, projectionNames, exclusiveStart, remaining)
            for (item in response.items ?: emptyList()) {
                emit(ResultRow(table, item))
                if (remaining != null) {
                    val next = remaining - 1
                    remaining = next
                    if (next <= 0) return@flow
                }
            }
            // Emptiness, not nullity: DynamoDB can return `"LastEvaluatedKey": {}`, and a `== null`
            // check treats that as "there is another page", re-issuing the identical request
            // forever. Same rule as the client's own paginators.
            exclusiveStart = response.lastEvaluatedKey?.takeIf { it.isNotEmpty() } ?: break
        }
    }

    /**
     * Issue a single scan request.
     */
    private suspend fun scanPage(
        filterOp: Op<Boolean>?,
        projectionExpression: String?,
        projectionNames: Map<String, String>?,
        exclusiveStart: Map<String, AttributeValue>?,
        pageLimit: Int?
    ): ScanResponse {
        val nameIndex = mutableMapOf<String, String>()
        val valueIndex = mutableMapOf<String, AttributeValue>()
        var filterExpression: String? = null

        if (filterOp != null) {
            filterExpression = buildFilterExpression(filterOp, nameIndex, valueIndex)
        }

        // Merge projection names into nameIndex
        projectionNames?.let { nameIndex.putAll(it) }

        return database.client.scan(
            ScanRequest(
                tableName = database.resolveTableName(table),
                filterExpression = filterExpression,
                projectionExpression = projectionExpression,
                expressionAttributeNames = nameIndex.orNullIfEmpty(),
                expressionAttributeValues = valueIndex.orNullIfEmpty(),
                exclusiveStartKey = exclusiveStart,
                limit = pageLimit,
            ),
        )
    }

    private fun executeScan(
        filterOp: Op<Boolean>?,
        projectionExpression: String?,
        projectionNames: Map<String, String>?,
        totalLimit: Int?
    ): Flow<ResultRow> = flow {
        var exclusiveStart = startKey
        var remaining = totalLimit
        while (true) {
            val response = scanPage(filterOp, projectionExpression, projectionNames, exclusiveStart, remaining)
            for (item in response.items ?: emptyList()) {
                emit(ResultRow(table, item))
                if (remaining != null) {
                    val next = remaining - 1
                    remaining = next
                    if (next <= 0) return@flow
                }
            }
            // Emptiness, not nullity: DynamoDB can return `"LastEvaluatedKey": {}`, and a `== null`
            // check treats that as "there is another page", re-issuing the identical request
            // forever. Same rule as the client's own paginators.
            exclusiveStart = response.lastEvaluatedKey?.takeIf { it.isNotEmpty() } ?: break
        }
    }

    private fun executeBatchGet(
        keys: List<Pair<Any, Any?>>,
        projectionExpression: String?,
        projectionNames: Map<String, String>?
    ): Flow<ResultRow> = flow {
        val pkColumn = table.partitionKey ?: error("Table ${table.tableName} has no partition key defined")
        val skColumn = table.sortKey

        // DynamoDB BatchGetItem has a limit of 100 items per request
        keys.chunked(100).forEach { chunk ->
            val keysAndAttributes = KeysAndAttributes(
                keys = chunk.map { (pk, sk) ->
                    buildMap {
                        @Suppress("UNCHECKED_CAST")
                        put(pkColumn.name, (pkColumn as Column<Any?>).toAttributeValue(pk))

                        if (skColumn != null && sk != null) {
                            @Suppress("UNCHECKED_CAST")
                            put(skColumn.name, (skColumn as Column<Any?>).toAttributeValue(sk))
                        }
                    }
                },
                consistentRead = database.defaultConsistentRead,
                projectionExpression = projectionExpression,
                expressionAttributeNames = projectionNames?.takeIf { projectionExpression != null },
            )

            val resolvedTableName = database.resolveTableName(table)
            val result = database.client.batchGetItem(
                BatchGetItemRequest(mapOf(resolvedTableName to keysAndAttributes)),
            )

            result.responses?.get(resolvedTableName)?.forEach { item ->
                emit(ResultRow(table, item))
            }
        }
    }
}

/**
 * The result of a single bounded [Query.page] read.
 *
 * @property rows the rows in this page
 * @property nextToken an opaque, serializable cursor for fetching the next page
 *   via [Query.startAfter], or `null` when there are no more pages. The encoding
 *   is compatible with the base `dynamokt` library's pagination tokens.
 */
public class PageResult(
    public val rows: List<ResultRow>,
    public val nextToken: String?
)

/**
 * Select specific columns from the table.
 * Returns a Query that can be further configured with where(), etc.
 *
 * Example:
 * ```
 * Users.select(database, Users.id, Users.name).where { Users.id eq "123" }
 * ```
 */
public fun Table.select(
    database: Database,
    column: Column<*>,
    vararg columns: Column<*>
): Query {
    return Query(this, database, listOf(column) + columns.toList())
}

/**
 * Select all columns from the table.
 * Returns a Query that can be further configured with where(), etc.
 *
 * The where clause must pin the partition key of the table or one of its
 * indices with `eq`; the best-matching index is chosen automatically and any
 * remaining conditions become a server-side filter. Use [scan] when there is no
 * such key.
 *
 * Example:
 * ```
 * Users.selectAll(database).where { Users.id eq "123" }
 * ```
 */
public fun Table.selectAll(database: Database): Query {
    return Query(this, database, null)
}

/**
 * Perform a full table scan.
 * Use this when you need to retrieve all items or filter without using an index.
 *
 * Example:
 * ```
 * Users.scan(database).where { Users.active eq true }
 * ```
 */
public fun Table.scan(database: Database): Query {
    return Query(this, database, null).asScan()
}

/**
 * Batch get multiple items by their full keys.
 *
 * @param keys List of partition key / sort key pairs (sort key is null for tables without one)
 * @deprecated Use selectAll(database).where { keys(...) } instead for a unified API
 */
@Deprecated(
    "Use selectAll(database).where { keys(keys) } instead",
    ReplaceWith("selectAll(database).where { keys(keys) }.asFlow()")
)
public fun Table.batchGet(
    database: Database,
    keys: List<Pair<Any, Any?>>
): Flow<ResultRow> = flow {
    val pkColumn = partitionKey ?: error("Table $tableName has no partition key defined")
    val skColumn = sortKey

    // DynamoDB BatchGetItem has a limit of 100 items per request
    keys.chunked(100).forEach { chunk ->
        val keysAndAttributes = KeysAndAttributes(
            keys = chunk.map { (pk, sk) ->
                buildMap {
                    @Suppress("UNCHECKED_CAST")
                    put(pkColumn.name, (pkColumn as Column<Any?>).toAttributeValue(pk))

                    if (skColumn != null && sk != null) {
                        @Suppress("UNCHECKED_CAST")
                        put(skColumn.name, (skColumn as Column<Any?>).toAttributeValue(sk))
                    }
                }
            },
            consistentRead = database.defaultConsistentRead,
        )

        val resolvedTableName = database.resolveTableName(this@batchGet)
        val result = database.client.batchGetItem(
            BatchGetItemRequest(mapOf(resolvedTableName to keysAndAttributes)),
        )

        result.responses?.get(resolvedTableName)?.forEach { item ->
            emit(ResultRow(this@batchGet, item))
        }
    }
}

// ============================================================================
// Legacy API - kept for backwards compatibility
// ============================================================================

/**
 * Select items from the table using the best available index.
 * Automatically chooses between GetItem and Query based on the where clause;
 * conditions the chosen index cannot express become a filter.
 *
 * @throws NoIndexMatchException if no index can satisfy the query (would require a Scan)
 * @deprecated Use selectAll(database).where { } instead
 */
@Deprecated(
    "Use selectAll(database).where { } instead",
    ReplaceWith("selectAll(database).where(where).asFlow()")
)
public fun Table.select(
    database: Database,
    where: SqlExpressionBuilder.() -> Op<Boolean>
): Flow<ResultRow> {
    return selectAll(database).where(where).asFlow()
}

/**
 * Batch get multiple items by their full keys.
 *
 * @param keys List of partition key / sort key pairs (sort key is null for tables without one)
 * @deprecated Use selectAll(database).where { keys(keys) } instead
 */
@Deprecated(
    "Use selectAll(database).where { keys(keys) } instead",
    ReplaceWith("selectAll(database).where { keys(keys) }.asFlow()")
)
@Suppress("DEPRECATION")
public fun Table.selectAll(
    database: Database,
    keys: List<Pair<Any, Any?>>
): Flow<ResultRow> = batchGet(database, keys)

/**
 * Perform a full table scan with optional filter.
 * Use this explicitly when you need to scan the entire table.
 *
 * @deprecated Use scan(database).where { } instead
 */
@Deprecated(
    "Use scan(database).where { } instead",
    ReplaceWith("scan(database).let { if (filter != null) it.where(filter) else it }.asFlow()")
)
public fun Table.scan(
    database: Database,
    filter: (SqlExpressionBuilder.() -> Op<Boolean>)? = null
): Flow<ResultRow> {
    val query = scan(database)
    return if (filter != null) {
        query.where(filter).asFlow()
    } else {
        query.asFlow()
    }
}

/**
 * Builds a filter expression from an operation tree
 */
internal fun buildFilterExpression(
    op: Op<Boolean>,
    nameIndex: MutableMap<String, String>,
    valueIndex: MutableMap<String, AttributeValue>
): String {
    return buildExpressionInternal(op, nameIndex, valueIndex, "fattr")
}

/**
 * Builds a condition expression from an operation tree for conditional writes.
 * This is used by insert, update, and delete operations.
 */
internal fun buildConditionExpression(
    op: Op<Boolean>,
    nameIndex: MutableMap<String, String>,
    valueIndex: MutableMap<String, AttributeValue>
): String {
    return buildExpressionInternal(op, nameIndex, valueIndex, "cattr")
}

/**
 * Internal implementation for building DynamoDB expressions from Op trees
 */
private fun buildExpressionInternal(
    op: Op<Boolean>,
    nameIndex: MutableMap<String, String>,
    valueIndex: MutableMap<String, AttributeValue>,
    prefix: String
): String {
    var attrCounter = 0

    fun nextNameKey() = "#${prefix}${attrCounter++}"
    fun nextValueKey() = ":${prefix}${attrCounter++}"

    fun build(operation: Op<Boolean>): String {
        return when (operation) {
            is EqOp<*> -> {
                val nameKey = nextNameKey()
                val valueKey = nextValueKey()
                nameIndex[nameKey] = operation.column.name
                @Suppress("UNCHECKED_CAST")
                valueIndex[valueKey] = (operation.column as Column<Any?>).toAttributeValue(operation.value)
                "$nameKey = $valueKey"
            }
            is NeOp<*> -> {
                val nameKey = nextNameKey()
                val valueKey = nextValueKey()
                nameIndex[nameKey] = operation.column.name
                @Suppress("UNCHECKED_CAST")
                valueIndex[valueKey] = (operation.column as Column<Any?>).toAttributeValue(operation.value)
                "$nameKey <> $valueKey"
            }
            is GtOp<*> -> {
                val nameKey = nextNameKey()
                val valueKey = nextValueKey()
                nameIndex[nameKey] = operation.column.name
                @Suppress("UNCHECKED_CAST")
                valueIndex[valueKey] = (operation.column as Column<Any?>).toAttributeValue(operation.value)
                "$nameKey > $valueKey"
            }
            is LtOp<*> -> {
                val nameKey = nextNameKey()
                val valueKey = nextValueKey()
                nameIndex[nameKey] = operation.column.name
                @Suppress("UNCHECKED_CAST")
                valueIndex[valueKey] = (operation.column as Column<Any?>).toAttributeValue(operation.value)
                "$nameKey < $valueKey"
            }
            is GeOp<*> -> {
                val nameKey = nextNameKey()
                val valueKey = nextValueKey()
                nameIndex[nameKey] = operation.column.name
                @Suppress("UNCHECKED_CAST")
                valueIndex[valueKey] = (operation.column as Column<Any?>).toAttributeValue(operation.value)
                "$nameKey >= $valueKey"
            }
            is LeOp<*> -> {
                val nameKey = nextNameKey()
                val valueKey = nextValueKey()
                nameIndex[nameKey] = operation.column.name
                @Suppress("UNCHECKED_CAST")
                valueIndex[valueKey] = (operation.column as Column<Any?>).toAttributeValue(operation.value)
                "$nameKey <= $valueKey"
            }
            is BetweenOp<*> -> {
                val nameKey = nextNameKey()
                val valueKey1 = nextValueKey()
                val valueKey2 = nextValueKey()
                nameIndex[nameKey] = operation.column.name
                @Suppress("UNCHECKED_CAST")
                val col = operation.column as Column<Any?>
                valueIndex[valueKey1] = col.toAttributeValue(operation.from)
                valueIndex[valueKey2] = col.toAttributeValue(operation.to)
                "$nameKey BETWEEN $valueKey1 AND $valueKey2"
            }
            is BeginsWithOp -> {
                val nameKey = nextNameKey()
                val valueKey = nextValueKey()
                nameIndex[nameKey] = operation.column.name
                valueIndex[valueKey] = AttributeValue.S(operation.prefix)
                "begins_with($nameKey, $valueKey)"
            }
            is AttributeExistsOp -> {
                val nameKey = nextNameKey()
                nameIndex[nameKey] = operation.column.name
                "attribute_exists($nameKey)"
            }
            is AttributeNotExistsOp -> {
                val nameKey = nextNameKey()
                nameIndex[nameKey] = operation.column.name
                "attribute_not_exists($nameKey)"
            }
            is InListOp<*> -> {
                val nameKey = nextNameKey()
                nameIndex[nameKey] = operation.column.name
                @Suppress("UNCHECKED_CAST")
                val col = operation.column as Column<Any?>
                val valueKeys = operation.values.map { value ->
                    nextValueKey().also { valueIndex[it] = col.toAttributeValue(value) }
                }
                "$nameKey IN (${valueKeys.joinToString(", ")})"
            }
            is ContainsOp -> {
                val nameKey = nextNameKey()
                val valueKey = nextValueKey()
                nameIndex[nameKey] = operation.column.name
                valueIndex[valueKey] = operation.value
                "contains($nameKey, $valueKey)"
            }
            is NotOp -> {
                "NOT (${build(operation.op)})"
            }
            is AndOp -> {
                "(${build(operation.left)}) AND (${build(operation.right)})"
            }
            is OrOp -> {
                "(${build(operation.left)}) OR (${build(operation.right)})"
            }
            is KeysOp -> {
                error("KeysOp cannot be used in condition expressions")
            }
        }
    }

    return build(op)
}