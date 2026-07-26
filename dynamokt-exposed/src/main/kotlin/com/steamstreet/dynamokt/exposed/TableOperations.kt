package com.steamstreet.dynamokt.exposed

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.putItem
import aws.sdk.kotlin.services.dynamodb.getItem
import aws.sdk.kotlin.services.dynamodb.updateItem
import aws.sdk.kotlin.services.dynamodb.deleteItem
import aws.sdk.kotlin.services.dynamodb.query as dynamoQuery

/**
 * A put operation that has been rendered into the pieces DynamoDB needs.
 * Shared by [InsertStatement.execute] and the transactional put builder.
 */
internal class BuiltPut(
    val item: Map<String, AttributeValue>,
    val conditionExpression: String?,
    val attributeNames: Map<String, String>,
    val attributeValues: Map<String, AttributeValue>
)

/**
 * An update operation that has been rendered into the pieces DynamoDB needs.
 */
internal class BuiltUpdate(
    val key: Map<String, AttributeValue>,
    val updateExpression: String,
    val conditionExpression: String?,
    val attributeNames: Map<String, String>,
    val attributeValues: Map<String, AttributeValue>
)

/**
 * A delete operation that has been rendered into the pieces DynamoDB needs.
 */
internal class BuiltDelete(
    val key: Map<String, AttributeValue>,
    val conditionExpression: String?,
    val attributeNames: Map<String, String>,
    val attributeValues: Map<String, AttributeValue>
)

/**
 * Build the primary key map for this table from partition/sort key values.
 */
internal fun Table.buildKey(pk: Any?, sk: Any?): Map<String, AttributeValue> {
    val pkColumn = partitionKey ?: error("Table $tableName has no partition key")
    val skColumn = sortKey

    return buildMap {
        @Suppress("UNCHECKED_CAST")
        put(pkColumn.name, (pkColumn as Column<Any?>).toAttributeValue(pk))

        if (sk != null && skColumn != null) {
            @Suppress("UNCHECKED_CAST")
            put(skColumn.name, (skColumn as Column<Any?>).toAttributeValue(sk))
        }
    }
}

/**
 * Extract the partition and sort key values from a where clause.
 * Throws if the partition key isn't specified with an equality condition.
 */
internal fun Table.extractKeyValues(where: SqlExpressionBuilder.() -> Op<Boolean>): Pair<Any, Any?> {
    val op = SqlExpressionBuilder().where()
    val keyValues = extractKeyValues(op)

    val pkColumn = partitionKey ?: error("Table $tableName has no partition key")
    val skColumn = sortKey

    val pk = keyValues[pkColumn] ?: error("Partition key ${pkColumn.name} not specified in where clause")
    val sk = skColumn?.let { keyValues[it] }

    return pk to sk
}

/**
 * Insert statement builder for type-safe DynamoDB put operations.
 * Similar to Exposed's insert.
 */
public class InsertStatement(
    public val table: Table,
    public val database: Database
) {
    private val values = mutableMapOf<Column<*>, Any?>()
    private var conditionOp: Op<Boolean>? = null

    /**
     * Set a column value using indexed access (Exposed style)
     * Example: it[Users.id] = "user#123"
     */
    public operator fun <T> set(column: Column<T>, value: T) {
        values[column] = value
    }

    /**
     * Add a condition expression for conditional insert.
     * The insert will only succeed if the condition is met.
     *
     * Example:
     * ```
     * Users.insert(database) {
     *     Users.id to "user#123"
     *     Users.name to "John"
     *     condition { Users.id.notExists() }
     * }
     * ```
     */
    public fun condition(block: SqlExpressionBuilder.() -> Op<Boolean>) {
        conditionOp = SqlExpressionBuilder().block()
    }

    /**
     * Shorthand to only insert if the item doesn't exist (attribute_not_exists on partition key)
     */
    public fun ifNotExists() {
        val pk = table.partitionKey ?: error("Table ${table.tableName} has no partition key")
        conditionOp = AttributeNotExistsOp(pk)
    }

    /**
     * Only insert if the specified column doesn't exist
     */
    public fun ifNotExists(column: Column<*>) {
        conditionOp = AttributeNotExistsOp(column)
    }

    /**
     * Render the statement into the item and expressions DynamoDB needs.
     */
    internal fun build(): BuiltPut {
        val item = values.entries.associate { (column, value) ->
            @Suppress("UNCHECKED_CAST")
            column.name to (column as Column<Any?>).toAttributeValue(value)
        }

        // Build condition expression if present
        val nameIndex = mutableMapOf<String, String>()
        val valueIndex = mutableMapOf<String, AttributeValue>()
        val conditionExpression = conditionOp?.let { op ->
            buildConditionExpression(op, nameIndex, valueIndex)
        }

        return BuiltPut(item, conditionExpression, nameIndex, valueIndex)
    }

    /**
     * Build the item and execute the put operation
     */
    public suspend fun execute(): ResultRow {
        val built = build()

        database.client.putItem {
            tableName = database.resolveTableName(table)
            this.item = built.item

            built.conditionExpression?.let { expr ->
                this.conditionExpression = expr
                if (built.attributeNames.isNotEmpty()) {
                    expressionAttributeNames = built.attributeNames
                }
                if (built.attributeValues.isNotEmpty()) {
                    expressionAttributeValues = built.attributeValues
                }
            }
        }

        return ResultRow(table, built.item)
    }
}

/**
 * Insert a new item into the table.
 * Example: Users.insert(db) { it[name] = "John"; it[age] = 30 }
 */
public suspend fun <T : Table> T.insert(
    database: Database,
    block: T.(InsertStatement) -> Unit
): ResultRow {
    return InsertStatement(this, database).also { block(it) }.execute()
}

/**
 * Get a single item using a where clause.
 * Delegates to selectAll() which automatically chooses GetItem or Query.
 * Example: Users.get(database) { Users.id eq "user#123" }
 */
public suspend fun Table.get(
    database: Database,
    where: SqlExpressionBuilder.() -> Op<Boolean>
): ResultRow? {
    return selectAll(database).where(where).firstOrNull()
}

/**
 * Update statement builder for type-safe DynamoDB update operations
 */
public class UpdateStatement(
    public val table: Table,
    public val database: Database,
    public val pk: Any,
    public val sk: Any? = null
) {
    private val sets = mutableMapOf<Column<*>, Any?>()
    private val removes = mutableSetOf<Column<*>>()
    private val adds = mutableMapOf<Column<*>, Number>()

    private var conditionOp: Op<Boolean>? = null

    /**
     * Set a column value
     */
    public operator fun <T> set(column: Column<T>, value: T) {
        sets[column] = value
    }

    /**
     * Set a column to an increment expression.
     * Enables Exposed-style syntax: it[count] = count + 1
     */
    public operator fun <T : Number> set(column: Column<T>, expr: IncrementExpr<T>) {
        require(column == expr.column) { "Increment expression column must match the target column" }
        adds[column] = expr.amount
    }

    /**
     * Remove a column
     */
    public fun <T> remove(column: Column<T>) {
        removes.add(column)
    }

    /**
     * Increment a numeric column
     */
    public fun increment(column: Column<Int>, amount: Int = 1) {
        adds[column] = amount
    }

    /**
     * Add a condition expression for conditional update.
     * The update will only succeed if the condition is met.
     *
     * Example:
     * ```
     * Users.update(database, { Users.id eq "user#123" }) {
     *     this[Users.version] = 2
     *     condition { Users.version eq 1 }
     * }
     * ```
     */
    public fun condition(block: SqlExpressionBuilder.() -> Op<Boolean>) {
        conditionOp = SqlExpressionBuilder().block()
    }

    /**
     * Render the statement into the key, update expression and condition DynamoDB needs.
     */
    internal fun build(): BuiltUpdate {
        val key = table.buildKey(pk, sk)

        val nameIndex = mutableMapOf<String, String>()
        val valueIndex = mutableMapOf<String, AttributeValue>()
        var attrCounter = 0

        // Build update expression
        val updateParts = mutableListOf<String>()

        if (sets.isNotEmpty()) {
            val setParts = sets.map { (column, value) ->
                val nameKey = "#attr${attrCounter++}"
                val valueKey = ":attr${attrCounter++}"
                nameIndex[nameKey] = column.name
                @Suppress("UNCHECKED_CAST")
                valueIndex[valueKey] = (column as Column<Any?>).toAttributeValue(value)
                "$nameKey = $valueKey"
            }
            updateParts.add("SET ${setParts.joinToString(", ")}")
        }

        if (adds.isNotEmpty()) {
            val addParts = adds.map { (column, amount) ->
                val nameKey = "#attr${attrCounter++}"
                val valueKey = ":attr${attrCounter++}"
                nameIndex[nameKey] = column.name
                valueIndex[valueKey] = AttributeValue.N(amount.toString())
                "$nameKey $valueKey"
            }
            updateParts.add("ADD ${addParts.joinToString(", ")}")
        }

        if (removes.isNotEmpty()) {
            val removeParts = removes.map { column ->
                val nameKey = "#attr${attrCounter++}"
                nameIndex[nameKey] = column.name
                nameKey
            }
            updateParts.add("REMOVE ${removeParts.joinToString(", ")}")
        }

        require(updateParts.isNotEmpty()) {
            "Update on table ${table.tableName} has no changes. " +
                "Set, increment or remove at least one column, or use conditionCheck for a condition-only operation."
        }

        val updateExpression = updateParts.joinToString(" ")

        // Build condition expression if present
        val conditionExpression = conditionOp?.let { op ->
            buildConditionExpression(op, nameIndex, valueIndex)
        }

        return BuiltUpdate(key, updateExpression, conditionExpression, nameIndex, valueIndex)
    }

    /**
     * Build and execute the update operation
     */
    public suspend fun execute(): ResultRow {
        val built = build()

        val result = database.client.updateItem {
            tableName = database.resolveTableName(table)
            this.key = built.key
            this.updateExpression = built.updateExpression
            built.conditionExpression?.let { this.conditionExpression = it }
            if (built.attributeNames.isNotEmpty()) {
                expressionAttributeNames = built.attributeNames
            }
            if (built.attributeValues.isNotEmpty()) {
                expressionAttributeValues = built.attributeValues
            }
            returnValues = aws.sdk.kotlin.services.dynamodb.model.ReturnValue.AllNew
        }

        return ResultRow(table, result.attributes ?: emptyMap())
    }
}

/**
 * Update an item in the table using a where clause.
 * Example: Users.update(database, { Users.id eq "user#123" }) { it[age] = 31 }
 */
public suspend fun <T : Table> T.update(
    database: Database,
    where: SqlExpressionBuilder.() -> Op<Boolean>,
    block: T.(UpdateStatement) -> Unit
): ResultRow {
    val (pk, sk) = extractKeyValues(where)
    return UpdateStatement(this, database, pk, sk).also { block(it) }.execute()
}

/**
 * Delete statement builder for type-safe DynamoDB delete operations with conditional support.
 */
public class DeleteStatement(
    public val table: Table,
    public val database: Database,
    public val pk: Any,
    public val sk: Any? = null
) {
    private var conditionOp: Op<Boolean>? = null

    /**
     * Add a condition expression for conditional delete.
     * The delete will only succeed if the condition is met.
     *
     * Example:
     * ```
     * Users.delete(database, { Users.id eq "user#123" }) {
     *     condition { Users.status eq "inactive" }
     * }
     * ```
     */
    public fun condition(block: SqlExpressionBuilder.() -> Op<Boolean>) {
        conditionOp = SqlExpressionBuilder().block()
    }

    /**
     * Render the statement into the key and condition DynamoDB needs.
     */
    internal fun build(): BuiltDelete {
        val key = table.buildKey(pk, sk)

        // Build condition expression if present
        val nameIndex = mutableMapOf<String, String>()
        val valueIndex = mutableMapOf<String, AttributeValue>()
        val conditionExpression = conditionOp?.let { op ->
            buildConditionExpression(op, nameIndex, valueIndex)
        }

        return BuiltDelete(key, conditionExpression, nameIndex, valueIndex)
    }

    /**
     * Execute the delete operation
     */
    public suspend fun execute(): Boolean {
        val built = build()

        database.client.deleteItem {
            tableName = database.resolveTableName(table)
            this.key = built.key
            built.conditionExpression?.let { this.conditionExpression = it }
            if (built.attributeNames.isNotEmpty()) {
                expressionAttributeNames = built.attributeNames
            }
            if (built.attributeValues.isNotEmpty()) {
                expressionAttributeValues = built.attributeValues
            }
        }

        return true
    }
}

/**
 * Delete an item from the table using a where clause.
 * Example: Users.delete(database) { Users.id eq "user#123" }
 */
public suspend fun Table.delete(
    database: Database,
    where: SqlExpressionBuilder.() -> Op<Boolean>
): Boolean {
    val (pk, sk) = extractKeyValues(where)
    return DeleteStatement(this, database, pk, sk).execute()
}

/**
 * Delete an item from the table using a where clause with optional condition.
 * Example: Users.delete(database, { Users.id eq "user#123" }) { it.condition { status eq "inactive" } }
 */
public suspend fun <T : Table> T.delete(
    database: Database,
    where: SqlExpressionBuilder.() -> Op<Boolean>,
    block: T.(DeleteStatement) -> Unit
): Boolean {
    val (pk, sk) = extractKeyValues(where)
    return DeleteStatement(this, database, pk, sk).also { block(it) }.execute()
}
