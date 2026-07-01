package com.steamstreet.dynamokt.exposed

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.getItem
import aws.sdk.kotlin.services.dynamodb.query
import aws.sdk.kotlin.services.dynamodb.scan as dynamoScan
import aws.sdk.kotlin.services.dynamodb.batchGetItem
import aws.sdk.kotlin.services.dynamodb.model.KeysAndAttributes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Result of index matching - determines which DynamoDB operation to use
 */
internal sealed class IndexMatch {
    /**
     * Full key provided - use GetItem
     */
    data class GetItem(val pkColumn: Column<*>, val skColumn: Column<*>?) : IndexMatch()

    /**
     * Partition key matches - use Query on table or index
     */
    data class Query(
        val index: Index?,
        val pkColumn: Column<*>,
        val skColumn: Column<*>?,
        val sortKeyCondition: ColumnCondition?
    ) : IndexMatch()

    /**
     * No index matches - would require a Scan (not allowed in select)
     */
    data object NoMatch : IndexMatch()
}

/**
 * Exception thrown when select() cannot find an appropriate index
 */
public class NoIndexMatchException(message: String) : IllegalArgumentException(message)

/**
 * Finds the best index match for the given operation.
 * Returns GetItem if full key is specified, Query if partition key matches, or NoMatch.
 */
internal fun Table.findIndexMatch(op: Op<Boolean>): IndexMatch {
    val conditions = extractConditions(op)
    val eqColumns = conditions.filterIsInstance<ColumnCondition.Eq>().map { it.column }.toSet()

    // Find sort key condition (non-eq condition on a sort key column)
    fun findSortKeyCondition(skColumn: Column<*>?): ColumnCondition? {
        if (skColumn == null) return null
        return conditions.find { condition ->
            val col = when (condition) {
                is ColumnCondition.Eq -> condition.column
                is ColumnCondition.Gt -> condition.column
                is ColumnCondition.Lt -> condition.column
                is ColumnCondition.Ge -> condition.column
                is ColumnCondition.Le -> condition.column
                is ColumnCondition.Between -> condition.column
                is ColumnCondition.BeginsWith -> condition.column
            }
            col == skColumn && condition !is ColumnCondition.Eq
        }
    }

    // 1. Check table's primary key first
    val pk = partitionKey
    if (pk != null && pk in eqColumns) {
        val sk = sortKey
        val skCondition = findSortKeyCondition(sk)

        // If SK exists and is specified with eq, it's a GetItem
        if (sk == null || sk in eqColumns) {
            return IndexMatch.GetItem(pk, sk)
        }

        // Otherwise it's a Query (with optional SK condition)
        return IndexMatch.Query(
            index = null,
            pkColumn = pk,
            skColumn = sk,
            sortKeyCondition = skCondition
        )
    }

    // 2. Check GSIs
    for (index in indices) {
        if (index.partitionKey in eqColumns) {
            val skCondition = findSortKeyCondition(index.sortKey)

            // GSIs always use Query (even with full key, since GetItem doesn't work on GSIs)
            return IndexMatch.Query(
                index = index,
                pkColumn = index.partitionKey,
                skColumn = index.sortKey,
                sortKeyCondition = skCondition
            )
        }
    }

    // 3. No match found
    return IndexMatch.NoMatch
}

/**
 * Builds a DynamoDB key condition expression from the operation
 */
internal fun buildKeyConditionExpression(
    pkColumn: Column<*>,
    skColumn: Column<*>?,
    conditions: List<ColumnCondition>,
    nameIndex: MutableMap<String, String>,
    valueIndex: MutableMap<String, AttributeValue>
): String {
    var attrCounter = 0

    fun nextNameKey() = "#attr${attrCounter++}"
    fun nextValueKey() = ":attr${attrCounter++}"

    val parts = mutableListOf<String>()

    // Partition key (always eq)
    val pkEq = conditions.filterIsInstance<ColumnCondition.Eq>().find { it.column == pkColumn }
    if (pkEq != null) {
        val nameKey = nextNameKey()
        val valueKey = nextValueKey()
        nameIndex[nameKey] = pkColumn.name
        @Suppress("UNCHECKED_CAST")
        valueIndex[valueKey] = (pkColumn as Column<Any?>).toAttributeValue(pkEq.value)
        parts.add("$nameKey = $valueKey")
    }

    // Sort key condition (if any)
    if (skColumn != null) {
        val skConditions = conditions.filter {
            when (it) {
                is ColumnCondition.Eq -> it.column == skColumn
                is ColumnCondition.Gt -> it.column == skColumn
                is ColumnCondition.Lt -> it.column == skColumn
                is ColumnCondition.Ge -> it.column == skColumn
                is ColumnCondition.Le -> it.column == skColumn
                is ColumnCondition.Between -> it.column == skColumn
                is ColumnCondition.BeginsWith -> it.column == skColumn
            }
        }

        for (condition in skConditions) {
            val nameKey = nextNameKey()
            nameIndex[nameKey] = skColumn.name

            @Suppress("UNCHECKED_CAST")
            val col = skColumn as Column<Any?>

            when (condition) {
                is ColumnCondition.Eq -> {
                    val valueKey = nextValueKey()
                    valueIndex[valueKey] = col.toAttributeValue(condition.value)
                    parts.add("$nameKey = $valueKey")
                }
                is ColumnCondition.Gt -> {
                    val valueKey = nextValueKey()
                    valueIndex[valueKey] = col.toAttributeValue(condition.value)
                    parts.add("$nameKey > $valueKey")
                }
                is ColumnCondition.Lt -> {
                    val valueKey = nextValueKey()
                    valueIndex[valueKey] = col.toAttributeValue(condition.value)
                    parts.add("$nameKey < $valueKey")
                }
                is ColumnCondition.Ge -> {
                    val valueKey = nextValueKey()
                    valueIndex[valueKey] = col.toAttributeValue(condition.value)
                    parts.add("$nameKey >= $valueKey")
                }
                is ColumnCondition.Le -> {
                    val valueKey = nextValueKey()
                    valueIndex[valueKey] = col.toAttributeValue(condition.value)
                    parts.add("$nameKey <= $valueKey")
                }
                is ColumnCondition.Between -> {
                    val valueKey1 = nextValueKey()
                    val valueKey2 = nextValueKey()
                    valueIndex[valueKey1] = col.toAttributeValue(condition.from)
                    valueIndex[valueKey2] = col.toAttributeValue(condition.to)
                    parts.add("$nameKey BETWEEN $valueKey1 AND $valueKey2")
                }
                is ColumnCondition.BeginsWith -> {
                    val valueKey = nextValueKey()
                    valueIndex[valueKey] = AttributeValue.S(condition.prefix)
                    parts.add("begins_with($nameKey, $valueKey)")
                }
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

    /**
     * Add a where clause to the query.
     */
    public fun where(op: SqlExpressionBuilder.() -> Op<Boolean>): Query {
        whereOp = SqlExpressionBuilder().op()
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
     * Execute the query and return results as a Flow.
     */
    public fun asFlow(): Flow<ResultRow> {
        val op = whereOp

        // Build projection expression if specific columns selected
        val projectionExpression = selectedColumns?.let { cols ->
            cols.mapIndexed { index, _ -> "#proj$index" }.joinToString(", ")
        }
        val projectionNames = selectedColumns?.let { cols ->
            cols.mapIndexed { index, col -> "#proj$index" to col.name }.toMap()
        }

        // Check for batch get (KeysOp)
        if (op is KeysOp) {
            return executeBatchGet(op.keys, projectionExpression, projectionNames)
        }

        // If no where clause or marked as scan, do a scan
        if (op == null || isScan) {
            return executeScan(op, projectionExpression, projectionNames)
        }

        val match = table.findIndexMatch(op)

        return when (match) {
            is IndexMatch.NoMatch -> {
                val conditionColumns = extractAllConditionColumns(op).map { it.name }
                throw NoIndexMatchException(
                    "No index found for query on table '${table.tableName}'. " +
                    "Columns in condition: $conditionColumns. " +
                    "Use selectAll() without where for full table scans."
                )
            }
            is IndexMatch.GetItem -> executeGetItem(op, match, projectionExpression, projectionNames)
            is IndexMatch.Query -> executeQuery(op, match, projectionExpression, projectionNames)
        }
    }

    /**
     * Execute the query and collect all results into a list.
     */
    public suspend fun toList(): List<ResultRow> {
        val results = mutableListOf<ResultRow>()
        asFlow().collect { results.add(it) }
        return results
    }

    /**
     * Execute the query and return the first result, or null if none.
     */
    public suspend fun firstOrNull(): ResultRow? {
        var result: ResultRow? = null
        asFlow().collect {
            if (result == null) result = it
        }
        return result
    }

    /**
     * Execute the query and return exactly one result.
     * @throws NoSuchElementException if no results
     * @throws IllegalArgumentException if more than one result
     */
    public suspend fun single(): ResultRow {
        var result: ResultRow? = null
        var count = 0
        asFlow().collect {
            count++
            if (count == 1) result = it
        }
        if (count == 0) throw NoSuchElementException("Query returned no results")
        if (count > 1) throw IllegalArgumentException("Query returned more than one result")
        return result!!
    }

    /**
     * Execute the query and return exactly one result, or null if none.
     * @throws IllegalArgumentException if more than one result
     */
    public suspend fun singleOrNull(): ResultRow? {
        var result: ResultRow? = null
        var count = 0
        asFlow().collect {
            count++
            if (count == 1) result = it
        }
        if (count > 1) throw IllegalArgumentException("Query returned more than one result")
        return result
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

        val result = database.client.getItem {
            tableName = database.resolveTableName(table)
            this.key = key
            consistentRead = database.defaultConsistentRead
            if (projectionExpression != null) {
                this.projectionExpression = projectionExpression
                expressionAttributeNames = projectionNames
            }
        }

        result.item?.let { emit(ResultRow(table, it)) }
    }

    private fun executeQuery(
        op: Op<Boolean>,
        match: IndexMatch.Query,
        projectionExpression: String?,
        projectionNames: Map<String, String>?
    ): Flow<ResultRow> = flow {
        val conditions = extractConditions(op)
        val nameIndex = mutableMapOf<String, String>()
        val valueIndex = mutableMapOf<String, AttributeValue>()

        val keyConditionExpression = buildKeyConditionExpression(
            match.pkColumn,
            match.skColumn,
            conditions,
            nameIndex,
            valueIndex
        )

        // Merge projection names into nameIndex
        projectionNames?.let { nameIndex.putAll(it) }

        val result = database.client.query {
            tableName = database.resolveTableName(table)
            match.index?.let { indexName = it.name }
            this.keyConditionExpression = keyConditionExpression
            if (projectionExpression != null) {
                this.projectionExpression = projectionExpression
            }
            if (nameIndex.isNotEmpty()) {
                expressionAttributeNames = nameIndex
            }
            if (valueIndex.isNotEmpty()) {
                expressionAttributeValues = valueIndex
            }
            consistentRead = if (match.index == null) database.defaultConsistentRead else false
        }

        result.items?.forEach { item ->
            emit(ResultRow(table, item))
        }
    }

    private fun executeScan(
        filterOp: Op<Boolean>?,
        projectionExpression: String?,
        projectionNames: Map<String, String>?
    ): Flow<ResultRow> = flow {
        val nameIndex = mutableMapOf<String, String>()
        val valueIndex = mutableMapOf<String, AttributeValue>()
        var filterExpression: String? = null

        if (filterOp != null) {
            filterExpression = buildFilterExpression(filterOp, nameIndex, valueIndex)
        }

        // Merge projection names into nameIndex
        projectionNames?.let { nameIndex.putAll(it) }

        val result = database.client.dynamoScan {
            tableName = database.resolveTableName(table)
            if (projectionExpression != null) {
                this.projectionExpression = projectionExpression
            }
            if (filterExpression != null) {
                this.filterExpression = filterExpression
            }
            if (nameIndex.isNotEmpty()) {
                expressionAttributeNames = nameIndex
            }
            if (valueIndex.isNotEmpty()) {
                expressionAttributeValues = valueIndex
            }
        }

        result.items?.forEach { item ->
            emit(ResultRow(table, item))
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
            val keysAndAttributes = KeysAndAttributes {
                this.keys = chunk.map { (pk, sk) ->
                    buildMap {
                        @Suppress("UNCHECKED_CAST")
                        put(pkColumn.name, (pkColumn as Column<Any?>).toAttributeValue(pk))

                        if (skColumn != null && sk != null) {
                            @Suppress("UNCHECKED_CAST")
                            put(skColumn.name, (skColumn as Column<Any?>).toAttributeValue(sk))
                        }
                    }
                }
                consistentRead = database.defaultConsistentRead
                if (projectionExpression != null) {
                    this.projectionExpression = projectionExpression
                    expressionAttributeNames = projectionNames
                }
            }

            val resolvedTableName = database.resolveTableName(table)
            val result = database.client.batchGetItem {
                requestItems = mapOf(resolvedTableName to keysAndAttributes)
            }

            result.responses?.get(resolvedTableName)?.forEach { item ->
                emit(ResultRow(table, item))
            }
        }
    }
}

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
        val keysAndAttributes = KeysAndAttributes {
            this.keys = chunk.map { (pk, sk) ->
                buildMap {
                    @Suppress("UNCHECKED_CAST")
                    put(pkColumn.name, (pkColumn as Column<Any?>).toAttributeValue(pk))

                    if (skColumn != null && sk != null) {
                        @Suppress("UNCHECKED_CAST")
                        put(skColumn.name, (skColumn as Column<Any?>).toAttributeValue(sk))
                    }
                }
            }
            consistentRead = database.defaultConsistentRead
        }

        val resolvedTableName = database.resolveTableName(this@batchGet)
        val result = database.client.batchGetItem {
            requestItems = mapOf(resolvedTableName to keysAndAttributes)
        }

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
 * Automatically chooses between GetItem and Query based on the where clause.
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