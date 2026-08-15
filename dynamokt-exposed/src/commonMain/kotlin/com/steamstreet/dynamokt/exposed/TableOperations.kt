package com.steamstreet.dynamokt.exposed

import com.steamstreet.awskt.dynamodb.DeleteItemRequest
import com.steamstreet.awskt.dynamodb.PutItemRequest
import com.steamstreet.awskt.dynamodb.ReturnValue
import com.steamstreet.awskt.dynamodb.UpdateItemRequest
import com.steamstreet.awskt.dynamodb.orNullIfEmpty
import com.steamstreet.dynamokt.AttributeValue

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
 * A where clause for a single-item operation (update, delete, condition check, transactional
 * get), split into the primary key that identifies the item and everything else.
 *
 * @property pk the partition key value
 * @property sk the sort key value, or `null` for a table without a sort key
 * @property residual every top-level conjunct that was not a primary key equality, re-ANDed.
 *   For writes this is folded into the operation's ConditionExpression so it actually constrains
 *   the write; for reads there is nowhere to put it and it is an error.
 */
internal class KeyWhere(
    val pk: Any,
    val sk: Any?,
    val residual: Op<Boolean>?
)

/**
 * Combine two optional conditions with AND, dropping nulls.
 */
internal fun combineConditions(first: Op<Boolean>?, second: Op<Boolean>?): Op<Boolean>? = when {
    first == null -> second
    second == null -> first
    else -> AndOp(first, second)
}

/**
 * True when a `keys(...)` operation appears anywhere in a single-item where clause. `when` is
 * deliberately non-exhaustive so that new [Op] subclasses do not break this walk.
 */
private fun whereHasKeysOp(op: Op<Boolean>): Boolean = when (op) {
    is KeysOp -> true
    is AndOp -> whereHasKeysOp(op.left) || whereHasKeysOp(op.right)
    is OrOp -> whereHasKeysOp(op.left) || whereHasKeysOp(op.right)
    else -> false
}

/**
 * Split a single-item where clause into its primary key and a residual condition.
 *
 * Walks the top-level AND spine only: an `eq` on the partition key (and, for a composite-key
 * table, an `eq` on the sort key) identifies the item, and *everything else* - including
 * conditions on key columns beyond the first equality, and anything under an `or` - becomes the
 * residual. Callers must either fold the residual into a ConditionExpression or reject it; it
 * must never be silently dropped, because `{ id eq x and version eq 1 }` reads as optimistic
 * locking and has to behave that way.
 *
 * @param operation the caller's name, used in error messages ("update", "delete", ...)
 */
internal fun Table.decomposeItemWhere(
    where: SqlExpressionBuilder.() -> Op<Boolean>,
    operation: String
): KeyWhere = decomposeItemWhere(SqlExpressionBuilder().where(), operation)

/**
 * Split an already-built single-item where clause. See the lambda overload for the rules.
 */
internal fun Table.decomposeItemWhere(op: Op<Boolean>, operation: String): KeyWhere {
    val pkColumn = partitionKey
        ?: throw IllegalArgumentException(
            "Table '$tableName' has no partition key, so $operation cannot identify an item."
        )
    val skColumn = sortKey

    if (whereHasKeysOp(op)) {
        throw IllegalArgumentException(
            "keys() is not valid in $operation on table '$tableName'. It selects a set of items, " +
                "while $operation targets exactly one - issue one $operation per key instead."
        )
    }

    var pk: Any? = null
    var pkFound = false
    var sk: Any? = null
    var skFound = false
    var residual: Op<Boolean>? = null

    fun walk(node: Op<Boolean>) {
        when {
            node is AndOp -> {
                walk(node.left)
                walk(node.right)
            }

            node is EqOp<*> && !pkFound && node.column.name == pkColumn.name -> {
                pk = node.value
                pkFound = true
            }

            node is EqOp<*> && skColumn != null && !skFound && node.column.name == skColumn.name -> {
                sk = node.value
                skFound = true
            }

            else -> residual = combineConditions(residual, node)
        }
    }
    walk(op)

    if (!pkFound) {
        throw IllegalArgumentException(
            "$operation on table '$tableName' requires the partition key '${pkColumn.name}' to be " +
                "matched with eq in the where clause (for example { ${pkColumn.name} eq value })."
        )
    }
    if (skColumn != null && !skFound) {
        throw IllegalArgumentException(
            "$operation on table '$tableName' requires the full primary key; sort key " +
                "'${skColumn.name}' must use eq. A range condition such as beginsWith, gt or " +
                "between cannot identify a single item - query for the items first, then " +
                "$operation each one."
        )
    }
    val pkValue = pk
        ?: throw IllegalArgumentException(
            "$operation on table '$tableName' was given a null partition key '${pkColumn.name}'."
        )

    return KeyWhere(pkValue, sk, residual)
}

/**
 * Build the error message for an insert that is missing its partition key. Shared by the direct
 * and transactional paths so both report the same thing.
 */
internal fun missingPartitionKeyMessage(table: Table, pkColumn: Column<*>): String =
    "Insert into table '${table.tableName}' is missing a value for partition key column " +
        "'${pkColumn.name}'. Set it (for example it[${pkColumn.name}] = ...) before executing."

/**
 * Insert statement builder for type-safe DynamoDB put operations.
 *
 * ### This is a put, not a SQL INSERT
 *
 * DynamoDB has no "insert" - this renders a `PutItem`, which **replaces any existing item with
 * the same primary key wholesale**. Attributes present on the old item but absent from this
 * statement are gone, and no error is raised. That is the native DynamoDB behaviour and it is
 * kept deliberately, because it is what "write this item" usually means here.
 *
 * Call [ifNotExists] for Exposed-style insert semantics: the write then fails with
 * `ConditionalCheckFailedException` if the item already exists.
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
     * Only insert if the item doesn't already exist (`attribute_not_exists` on the partition
     * key). This gives the statement Exposed-style insert semantics: an existing item is not
     * replaced, the write fails with `ConditionalCheckFailedException` instead.
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

        val pkColumn = table.partitionKey
            ?: error("Table ${table.tableName} has no partition key")
        require(item.containsKey(pkColumn.name)) { missingPartitionKeyMessage(table, pkColumn) }

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

        database.client.putItem(
            PutItemRequest(
                tableName = database.resolveTableName(table),
                item = built.item,
                conditionExpression = built.conditionExpression,
                expressionAttributeNames = built.attributeNames.orNullIfEmpty()
                    ?.takeIf { built.conditionExpression != null },
                expressionAttributeValues = built.attributeValues.orNullIfEmpty()
                    ?.takeIf { built.conditionExpression != null },
            ),
        )

        return ResultRow(table, built.item)
    }
}

/**
 * Insert a new item into the table.
 * Example: Users.insert(db) { it[name] = "John"; it[age] = 30 }
 *
 * **This is a put, not a SQL INSERT.** An existing item with the same primary key is replaced
 * wholesale, losing any attributes this statement does not set. Call
 * [InsertStatement.ifNotExists] inside the block for Exposed-style insert semantics.
 *
 * @throws IllegalArgumentException if the partition key column was not set
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
 * Update statement builder for type-safe DynamoDB update operations.
 *
 * ### An update creates the item if it is missing
 *
 * DynamoDB's `UpdateItem` is an upsert: updating a key that does not exist **creates** an item
 * holding the key plus whatever this statement sets, rather than being the no-op an Exposed user
 * expects from `UPDATE ... WHERE`. That native behaviour is kept; call [ifExists] to require the
 * item to already exist, which turns a miss into a `ConditionalCheckFailedException`.
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
     * Conditions from the where clause that were not part of the primary key. Folded into the
     * ConditionExpression alongside any explicit [condition] so that both the direct-execute and
     * the transactional build paths apply them.
     */
    internal var whereResidual: Op<Boolean>? = null

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
     * Increment a `Long` column. Pass a negative [amount] to decrement.
     */
    public fun increment(column: Column<Long>, amount: Long = 1L) {
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
     * Require that the item already exists (`attribute_exists` on the partition key), turning
     * this upsert into a true update: a missing item fails with
     * `ConditionalCheckFailedException` instead of being created.
     *
     * This ANDs into any condition set by [condition], in either order.
     */
    public fun ifExists() {
        val pkColumn = table.partitionKey ?: error("Table ${table.tableName} has no partition key")
        conditionOp = combineConditions(conditionOp, AttributeExistsOp(pkColumn))
    }

    /**
     * Render the statement into the key, update expression and condition DynamoDB needs.
     */
    internal fun build(): BuiltUpdate {
        val key = table.buildKey(pk, sk)

        val overlapping = (sets.keys + adds.keys + removes).filter { column ->
            listOf(sets.containsKey(column), adds.containsKey(column), removes.contains(column))
                .count { it } > 1
        }
        require(overlapping.isEmpty()) {
            "Update on table '${table.tableName}' touches ${overlapping.joinToString { "'${it.name}'" }} " +
                "in more than one of set / increment / remove. DynamoDB rejects an update expression " +
                "that mentions the same attribute twice - pick one clause per column."
        }

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

        // Build condition expression from the where residual and any explicit condition. Both are
        // rendered in one call: buildConditionExpression restarts its placeholder counter per call,
        // so two calls sharing these maps would collide.
        val conditionExpression = combineConditions(whereResidual, conditionOp)?.let { op ->
            buildConditionExpression(op, nameIndex, valueIndex)
        }

        return BuiltUpdate(key, updateExpression, conditionExpression, nameIndex, valueIndex)
    }

    /**
     * Build and execute the update operation
     */
    public suspend fun execute(): ResultRow {
        val built = build()

        val result = database.client.updateItem(
            UpdateItemRequest(
                tableName = database.resolveTableName(table),
                key = built.key,
                updateExpression = built.updateExpression,
                conditionExpression = built.conditionExpression,
                expressionAttributeNames = built.attributeNames.orNullIfEmpty(),
                expressionAttributeValues = built.attributeValues.orNullIfEmpty(),
                returnValues = ReturnValue.AllNew,
            ),
        )

        return ResultRow(table, result.attributes ?: emptyMap())
    }
}

/**
 * Update an item in the table using a where clause.
 * Example: Users.update(database, { Users.id eq "user#123" }) { it[age] = 31 }
 *
 * The where clause must match the full primary key with `eq`. Any *further* conditions in it -
 * `{ id eq "user#123" and version eq 1 }` - are applied as a DynamoDB ConditionExpression, so the
 * update fails with `ConditionalCheckFailedException` when they do not hold. They are never
 * silently ignored.
 *
 * **An update creates the item if it is missing** (DynamoDB `UpdateItem` is an upsert). Call
 * [UpdateStatement.ifExists] inside the block to require the item to already exist.
 *
 * @throws IllegalArgumentException if the where clause does not pin the full primary key
 */
public suspend fun <T : Table> T.update(
    database: Database,
    where: SqlExpressionBuilder.() -> Op<Boolean>,
    block: T.(UpdateStatement) -> Unit
): ResultRow {
    val keyWhere = decomposeItemWhere(where, "update")
    return UpdateStatement(this, database, keyWhere.pk, keyWhere.sk)
        .also { it.whereResidual = keyWhere.residual }
        .also { block(it) }
        .execute()
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
     * Conditions from the where clause that were not part of the primary key. Folded into the
     * ConditionExpression alongside any explicit [condition].
     */
    internal var whereResidual: Op<Boolean>? = null

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

        // Build condition expression if present. One call, so the placeholder counter inside
        // buildConditionExpression cannot produce colliding names.
        val nameIndex = mutableMapOf<String, String>()
        val valueIndex = mutableMapOf<String, AttributeValue>()
        val conditionExpression = combineConditions(whereResidual, conditionOp)?.let { op ->
            buildConditionExpression(op, nameIndex, valueIndex)
        }

        return BuiltDelete(key, conditionExpression, nameIndex, valueIndex)
    }

    /**
     * Execute the delete operation.
     *
     * @return true if an item was actually deleted, false if nothing existed at that key.
     *   DynamoDB's delete is idempotent, so deleting a missing item is a success, not an error;
     *   the returned old attributes are what distinguishes the two.
     */
    public suspend fun execute(): Boolean {
        val built = build()

        val result = database.client.deleteItem(
            DeleteItemRequest(
                tableName = database.resolveTableName(table),
                key = built.key,
                conditionExpression = built.conditionExpression,
                expressionAttributeNames = built.attributeNames.orNullIfEmpty(),
                expressionAttributeValues = built.attributeValues.orNullIfEmpty(),
                returnValues = ReturnValue.AllOld,
            ),
        )

        return !result.attributes.isNullOrEmpty()
    }
}

/**
 * Delete an item from the table using a where clause.
 * Example: Users.delete(database) { Users.id eq "user#123" }
 *
 * The where clause must match the full primary key with `eq`; any further conditions become a
 * ConditionExpression, so a delete whose extra conditions do not hold fails with
 * `ConditionalCheckFailedException` rather than deleting.
 *
 * @return true if an item existed at that key and was deleted, false if there was nothing there
 * @throws IllegalArgumentException if the where clause does not pin the full primary key
 */
public suspend fun Table.delete(
    database: Database,
    where: SqlExpressionBuilder.() -> Op<Boolean>
): Boolean {
    val keyWhere = decomposeItemWhere(where, "delete")
    return DeleteStatement(this, database, keyWhere.pk, keyWhere.sk)
        .also { it.whereResidual = keyWhere.residual }
        .execute()
}

/**
 * Delete an item from the table using a where clause with optional condition.
 * Example: Users.delete(database, { Users.id eq "user#123" }) { it.condition { status eq "inactive" } }
 *
 * The where clause must match the full primary key with `eq`; any further conditions are ANDed
 * with the block's `condition { }` into a single ConditionExpression.
 *
 * @return true if an item existed at that key and was deleted, false if there was nothing there
 * @throws IllegalArgumentException if the where clause does not pin the full primary key
 */
public suspend fun <T : Table> T.delete(
    database: Database,
    where: SqlExpressionBuilder.() -> Op<Boolean>,
    block: T.(DeleteStatement) -> Unit
): Boolean {
    val keyWhere = decomposeItemWhere(where, "delete")
    return DeleteStatement(this, database, keyWhere.pk, keyWhere.sk)
        .also { it.whereResidual = keyWhere.residual }
        .also { block(it) }
        .execute()
}
