package com.steamstreet.dynamokt.exposed

import com.steamstreet.dynamokt.AttributeValue
import com.steamstreet.awskt.dynamodb.TransactGetItem
import com.steamstreet.awskt.dynamodb.TransactGetItemsRequest
import com.steamstreet.awskt.dynamodb.ConditionCheck
import com.steamstreet.awskt.dynamodb.Get
import com.steamstreet.awskt.dynamodb.TransactDelete
import com.steamstreet.awskt.dynamodb.TransactPut
import com.steamstreet.awskt.dynamodb.TransactUpdate
import com.steamstreet.awskt.dynamodb.TransactWriteItem
import com.steamstreet.awskt.dynamodb.TransactWriteItemsRequest
import com.steamstreet.awskt.dynamodb.orNullIfEmpty

/**
 * The maximum number of operations DynamoDB accepts in a single transaction.
 */
internal const val MAX_TRANSACTION_ITEMS: Int = 100

/**
 * The message attached to the deprecated shadows below. Calling a top-level `insert`/`update`/
 * `delete`/`get` with an explicit `database` inside a transaction block would execute it right
 * away, outside the transaction - a one-argument difference silently changing atomicity.
 */
private const val OUTSIDE_TRANSACTION: String =
    "This runs immediately, outside the transaction. Drop the 'database' argument to enlist the " +
        "operation in the transaction, or move the call outside the transaction block."

/**
 * Collects write operations to be committed atomically via DynamoDB's
 * TransactWriteItems. All operations succeed or none of them do.
 *
 * Operations may span multiple tables, but DynamoDB does not allow more than one
 * operation against the same item in a single transaction.
 *
 * ### Not thread-safe
 *
 * The builder keeps mutable, unsynchronized state. Add every operation from a single coroutine:
 * do not `launch` (or otherwise fan out) inside the transaction block and add operations
 * concurrently, or operations can be lost or interleaved unpredictably. Gather your data first,
 * then add the writes sequentially.
 *
 * Example:
 * ```
 * database.transaction {
 *     Users.insert {
 *         it[Users.id] = "user#123"
 *         it[Users.name] = "John"
 *         it.ifNotExists()
 *     }
 *     Orders.update({ Orders.id eq "order#456" }) {
 *         it[Orders.status] = "SHIPPED"
 *         it.condition { Orders.status eq "PENDING" }
 *     }
 *     Inventory.conditionCheck({ Inventory.sku eq "sku#1" }) {
 *         Inventory.available gt 0
 *     }
 * }
 * ```
 */
public class Transaction internal constructor(public val database: Database) {
    private val items = mutableListOf<TransactWriteItem>()
    private val targets = mutableSetOf<Pair<String, Map<String, AttributeValue>>>()

    /**
     * Optional idempotency token. DynamoDB treats two calls with the same token
     * within its (roughly 10 minute) window as the same transaction, so a retry
     * after an ambiguous failure does not apply the writes twice.
     */
    public var clientRequestToken: String? = null

    /**
     * The number of operations currently in the transaction.
     */
    public val size: Int get() = items.size

    /**
     * True when no operations have been added. Committing an empty transaction is a no-op.
     */
    public fun isEmpty(): Boolean = items.isEmpty()

    /**
     * Insert an item as part of this transaction.
     *
     * Supports the same conditions as a non-transactional insert, including
     * [InsertStatement.ifNotExists] and [InsertStatement.condition]. Like the non-transactional
     * form this is a put: it replaces an existing item at the same key unless `ifNotExists()`
     * (or another condition) says otherwise.
     */
    public fun <T : Table> T.insert(block: T.(InsertStatement) -> Unit) {
        val built = InsertStatement(this, database).also { block(it) }.build()
        val key = keyOf(built.item)
        val resolvedName = database.resolveTableName(this)

        register(this, key)
        items.add(
            TransactWriteItem(
                put = TransactPut(
                    tableName = resolvedName,
                    item = built.item,
                    conditionExpression = built.conditionExpression,
                    expressionAttributeNames = built.attributeNames.orNullIfEmpty()
                        ?.takeIf { built.conditionExpression != null },
                    expressionAttributeValues = built.attributeValues.orNullIfEmpty()
                        ?.takeIf { built.conditionExpression != null },
                ),
            ),
        )
    }

    /**
     * Update an item as part of this transaction.
     *
     * The where clause must specify the full primary key with equality conditions. Any further
     * conditions in it are folded into the operation's ConditionExpression, so
     * `{ id eq "x" and version eq 1 }` cancels the transaction when the version does not match.
     *
     * Unlike a non-transactional update, no values are returned - DynamoDB does not
     * return item attributes from a transaction.
     */
    public fun <T : Table> T.update(
        where: SqlExpressionBuilder.() -> Op<Boolean>,
        block: T.(UpdateStatement) -> Unit
    ) {
        val keyWhere = decomposeItemWhere(where, "update")
        val built = UpdateStatement(this, database, keyWhere.pk, keyWhere.sk)
            .also { it.whereResidual = keyWhere.residual }
            .also { block(it) }
            .build()
        val resolvedName = database.resolveTableName(this)

        register(this, built.key)
        items.add(
            TransactWriteItem(
                update = TransactUpdate(
                    tableName = resolvedName,
                    key = built.key,
                    updateExpression = built.updateExpression,
                    conditionExpression = built.conditionExpression,
                    expressionAttributeNames = built.attributeNames.orNullIfEmpty(),
                    expressionAttributeValues = built.attributeValues.orNullIfEmpty(),
                ),
            ),
        )
    }

    /**
     * Delete an item as part of this transaction.
     */
    public fun <T : Table> T.delete(where: SqlExpressionBuilder.() -> Op<Boolean>) {
        delete(where) {}
    }

    /**
     * Delete an item as part of this transaction, with an optional condition.
     *
     * As with [update], conditions in the where clause beyond the primary key are folded into the
     * operation's ConditionExpression rather than discarded.
     */
    public fun <T : Table> T.delete(
        where: SqlExpressionBuilder.() -> Op<Boolean>,
        block: T.(DeleteStatement) -> Unit
    ) {
        val keyWhere = decomposeItemWhere(where, "delete")
        val built = DeleteStatement(this, database, keyWhere.pk, keyWhere.sk)
            .also { it.whereResidual = keyWhere.residual }
            .also { block(it) }
            .build()
        val resolvedName = database.resolveTableName(this)

        register(this, built.key)
        items.add(
            TransactWriteItem(
                delete = TransactDelete(
                    tableName = resolvedName,
                    key = built.key,
                    conditionExpression = built.conditionExpression,
                    expressionAttributeNames = built.attributeNames.orNullIfEmpty(),
                    expressionAttributeValues = built.attributeValues.orNullIfEmpty(),
                ),
            ),
        )
    }

    /**
     * Assert something about an item without writing to it. If the condition fails,
     * the entire transaction is cancelled.
     *
     * Conditions in the where clause beyond the primary key are ANDed into the check.
     *
     * Example:
     * ```
     * Accounts.conditionCheck({ Accounts.id eq "account#1" }) {
     *     Accounts.status eq "ACTIVE"
     * }
     * ```
     */
    public fun <T : Table> T.conditionCheck(
        where: SqlExpressionBuilder.() -> Op<Boolean>,
        condition: SqlExpressionBuilder.() -> Op<Boolean>
    ) {
        val keyWhere = decomposeItemWhere(where, "conditionCheck")
        val itemKey = buildKey(keyWhere.pk, keyWhere.sk)

        val explicit = SqlExpressionBuilder().condition()
        val checkOp = keyWhere.residual?.let { AndOp(it, explicit) } ?: explicit

        val nameIndex = mutableMapOf<String, String>()
        val valueIndex = mutableMapOf<String, AttributeValue>()
        val expression = buildConditionExpression(checkOp, nameIndex, valueIndex)
        val resolvedName = database.resolveTableName(this)

        register(this, itemKey)
        items.add(
            TransactWriteItem(
                conditionCheck = ConditionCheck(
                    tableName = resolvedName,
                    key = itemKey,
                    conditionExpression = expression,
                    expressionAttributeNames = nameIndex.orNullIfEmpty(),
                    expressionAttributeValues = valueIndex.orNullIfEmpty(),
                ),
            ),
        )
    }

    /**
     * Insert into a bound table as part of this transaction.
     */
    public fun <T : Table> BoundTable<T>.insert(block: T.(InsertStatement) -> Unit): Unit =
        table.insert(block)

    /**
     * Update an item in a bound table as part of this transaction.
     */
    public fun <T : Table> BoundTable<T>.update(
        where: SqlExpressionBuilder.() -> Op<Boolean>,
        block: T.(UpdateStatement) -> Unit
    ): Unit = table.update(where, block)

    /**
     * Delete an item from a bound table as part of this transaction.
     */
    public fun <T : Table> BoundTable<T>.delete(where: SqlExpressionBuilder.() -> Op<Boolean>): Unit =
        table.delete(where)

    /**
     * Delete an item from a bound table as part of this transaction, with a condition.
     */
    public fun <T : Table> BoundTable<T>.delete(
        where: SqlExpressionBuilder.() -> Op<Boolean>,
        block: T.(DeleteStatement) -> Unit
    ): Unit = table.delete(where, block)

    /**
     * Assert something about an item in a bound table without writing to it.
     */
    public fun <T : Table> BoundTable<T>.conditionCheck(
        where: SqlExpressionBuilder.() -> Op<Boolean>,
        condition: SqlExpressionBuilder.() -> Op<Boolean>
    ): Unit = table.conditionCheck(where, condition)

    // -- Shadows of the immediate, non-transactional operations ----------------------------------
    //
    // The top-level `Table.insert(database) { }`, `Table.update(database, where) { }`,
    // `Table.delete(database, where)` and `Table.get(database, where)` are perfectly callable
    // inside a transaction block, where they execute at once against the database instead of
    // joining the transaction. These member extensions shadow them (a member extension on an
    // implicit receiver wins over a top-level extension) and fail the build with an explanation.

    /** @suppress */
    @Deprecated(OUTSIDE_TRANSACTION, level = DeprecationLevel.ERROR)
    public suspend fun <T : Table> T.insert(
        database: Database,
        block: T.(InsertStatement) -> Unit
    ): ResultRow = error(OUTSIDE_TRANSACTION)

    /** @suppress */
    @Deprecated(OUTSIDE_TRANSACTION, level = DeprecationLevel.ERROR)
    public suspend fun <T : Table> T.update(
        database: Database,
        where: SqlExpressionBuilder.() -> Op<Boolean>,
        block: T.(UpdateStatement) -> Unit
    ): ResultRow = error(OUTSIDE_TRANSACTION)

    /** @suppress */
    @Deprecated(OUTSIDE_TRANSACTION, level = DeprecationLevel.ERROR)
    public suspend fun Table.delete(
        database: Database,
        where: SqlExpressionBuilder.() -> Op<Boolean>
    ): Boolean = error(OUTSIDE_TRANSACTION)

    /** @suppress */
    @Deprecated(OUTSIDE_TRANSACTION, level = DeprecationLevel.ERROR)
    public suspend fun <T : Table> T.delete(
        database: Database,
        where: SqlExpressionBuilder.() -> Op<Boolean>,
        block: T.(DeleteStatement) -> Unit
    ): Boolean = error(OUTSIDE_TRANSACTION)

    /** @suppress */
    @Deprecated(OUTSIDE_TRANSACTION, level = DeprecationLevel.ERROR)
    public suspend fun Table.get(
        database: Database,
        where: SqlExpressionBuilder.() -> Op<Boolean>
    ): ResultRow? = error(OUTSIDE_TRANSACTION)

    /**
     * Extract the primary key attributes from a fully built item.
     */
    private fun Table.keyOf(item: Map<String, AttributeValue>): Map<String, AttributeValue> {
        val pkColumn = partitionKey ?: error("Table $tableName has no partition key")
        val pkValue = item[pkColumn.name]
            ?: error(missingPartitionKeyMessage(this, pkColumn))

        return buildMap {
            put(pkColumn.name, pkValue)
            sortKey?.let { skColumn ->
                item[skColumn.name]?.let { put(skColumn.name, it) }
            }
        }
    }

    /**
     * Record the item an operation targets, rejecting a second operation on the same
     * item. DynamoDB cancels such transactions, so failing here gives a clearer error
     * without a round trip. Also enforces the transaction size limit at add time, where the
     * offending call is still on the stack, rather than at commit.
     */
    private fun register(table: Table, key: Map<String, AttributeValue>) {
        require(items.size < MAX_TRANSACTION_ITEMS) {
            "Transaction already contains $MAX_TRANSACTION_ITEMS operations, the most DynamoDB " +
                "allows in one TransactWriteItems call. Split the work into several transactions."
        }

        val target = database.resolveTableName(table) to key
        require(targets.add(target)) {
            "Transaction already contains an operation on item $key in table " +
                "'${database.resolveTableName(table)}'. DynamoDB allows at most one operation per item."
        }
    }

    /**
     * Commit the accumulated operations. Does nothing if the transaction is empty.
     */
    internal suspend fun commit() {
        if (items.isEmpty()) return

        require(items.size <= MAX_TRANSACTION_ITEMS) {
            "Transaction contains ${items.size} operations, but DynamoDB allows at most $MAX_TRANSACTION_ITEMS."
        }

        val token = clientRequestToken
        database.client.transactWriteItems(
            TransactWriteItemsRequest(transactItems = items, clientRequestToken = token),
        )
    }
}

/**
 * Execute a set of writes atomically.
 *
 * The block accumulates operations, which are committed as a single
 * TransactWriteItems call when the block completes. If the block throws, nothing
 * is written. If the transaction is cancelled - typically because a condition
 * failed - DynamoDB throws `TransactionCanceledException`, whose
 * `cancellationReasons` identify which operation failed.
 *
 * Operations must be added from a single coroutine; see [Transaction] for why.
 *
 * Example:
 * ```
 * database.transaction {
 *     Accounts.update({ Accounts.id eq "account#1" }) {
 *         it.increment(Accounts.balance, -100)
 *         it.condition { Accounts.balance ge 100 }
 *     }
 *     Accounts.update({ Accounts.id eq "account#2" }) {
 *         it.increment(Accounts.balance, 100)
 *     }
 * }
 * ```
 *
 * @param clientRequestToken optional idempotency token for safe retries
 */
public suspend fun <T> Database.transaction(
    clientRequestToken: String? = null,
    block: suspend Transaction.() -> T
): T {
    val transaction = Transaction(this)
    transaction.clientRequestToken = clientRequestToken
    val result = transaction.block()
    transaction.commit()
    return result
}

/**
 * Collects reads to be performed as a single consistent snapshot via DynamoDB's
 * TransactGetItems.
 *
 * Like [Transaction], the builder is not thread-safe: add every read from a single coroutine.
 */
public class TransactionGet internal constructor(public val database: Database) {
    internal val tables: MutableList<Table> = mutableListOf()
    internal val items: MutableList<TransactGetItem> = mutableListOf()

    /**
     * Read an item as part of this transaction. The where clause must specify the
     * full primary key with equality conditions, and nothing else: a read has no
     * ConditionExpression to carry extra conditions, so they would silently not apply.
     */
    public fun <T : Table> T.get(where: SqlExpressionBuilder.() -> Op<Boolean>) {
        val keyWhere = decomposeItemWhere(where, "a transactional get")
        require(keyWhere.residual == null) {
            "A transactional get on table '$tableName' cannot apply conditions - a read has no " +
                "ConditionExpression. Restrict the where clause to the primary key and check the " +
                "returned row, or use conditionCheck in a write transaction."
        }
        val itemKey = buildKey(keyWhere.pk, keyWhere.sk)
        val resolvedName = database.resolveTableName(this)

        require(items.size < MAX_TRANSACTION_ITEMS) {
            "Transaction already contains $MAX_TRANSACTION_ITEMS reads, the most DynamoDB allows " +
                "in one TransactGetItems call."
        }

        tables.add(this)
        items.add(TransactGetItem(Get(tableName = resolvedName, key = itemKey)))
    }

    /**
     * Read an item from a bound table as part of this transaction.
     */
    public fun <T : Table> BoundTable<T>.get(where: SqlExpressionBuilder.() -> Op<Boolean>): Unit =
        table.get(where)
}

/**
 * Read several items as a single atomic, consistent snapshot.
 *
 * Returns one entry per requested item, in the order the reads were added, with
 * `null` for items that do not exist.
 *
 * Example:
 * ```
 * val (user, order) = database.transactionGet {
 *     Users.get { Users.id eq "user#123" }
 *     Orders.get { Orders.id eq "order#456" }
 * }
 * ```
 */
public suspend fun Database.transactionGet(
    block: TransactionGet.() -> Unit
): List<ResultRow?> {
    val request = TransactionGet(this).apply(block)
    if (request.items.isEmpty()) return emptyList()

    require(request.items.size <= MAX_TRANSACTION_ITEMS) {
        "Transaction contains ${request.items.size} reads, but DynamoDB allows at most $MAX_TRANSACTION_ITEMS."
    }

    val response = client.transactGetItems(TransactGetItemsRequest(request.items))

    return request.tables.mapIndexed { index, table ->
        response.responses?.getOrNull(index)?.item?.takeIf { it.isNotEmpty() }?.let {
            ResultRow(table, it)
        }
    }
}
