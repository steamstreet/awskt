package com.steamstreet.dynamokt.exposed

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.TransactGetItem
import aws.sdk.kotlin.services.dynamodb.model.TransactWriteItem
import aws.sdk.kotlin.services.dynamodb.transactGetItems
import aws.sdk.kotlin.services.dynamodb.transactWriteItems

/**
 * The maximum number of operations DynamoDB accepts in a single transaction.
 */
internal const val MAX_TRANSACTION_ITEMS: Int = 100

/**
 * Collects write operations to be committed atomically via DynamoDB's
 * TransactWriteItems. All operations succeed or none of them do.
 *
 * Operations may span multiple tables, but DynamoDB does not allow more than one
 * operation against the same item in a single transaction.
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
     * [InsertStatement.ifNotExists] and [InsertStatement.condition].
     */
    public fun <T : Table> T.insert(block: T.(InsertStatement) -> Unit) {
        val built = InsertStatement(this, database).also { block(it) }.build()
        val key = keyOf(built.item)
        val resolvedName = database.resolveTableName(this)

        register(this, key)
        items.add(TransactWriteItem {
            put {
                tableName = resolvedName
                item = built.item
                built.conditionExpression?.let { expr ->
                    conditionExpression = expr
                    if (built.attributeNames.isNotEmpty()) {
                        expressionAttributeNames = built.attributeNames
                    }
                    if (built.attributeValues.isNotEmpty()) {
                        expressionAttributeValues = built.attributeValues
                    }
                }
            }
        })
    }

    /**
     * Update an item as part of this transaction.
     *
     * The where clause must specify the full primary key with equality conditions.
     * Unlike a non-transactional update, no values are returned - DynamoDB does not
     * return item attributes from a transaction.
     */
    public fun <T : Table> T.update(
        where: SqlExpressionBuilder.() -> Op<Boolean>,
        block: T.(UpdateStatement) -> Unit
    ) {
        val (pk, sk) = extractKeyValues(where)
        val built = UpdateStatement(this, database, pk, sk).also { block(it) }.build()
        val resolvedName = database.resolveTableName(this)

        register(this, built.key)
        items.add(TransactWriteItem {
            update {
                tableName = resolvedName
                key = built.key
                updateExpression = built.updateExpression
                built.conditionExpression?.let { conditionExpression = it }
                if (built.attributeNames.isNotEmpty()) {
                    expressionAttributeNames = built.attributeNames
                }
                if (built.attributeValues.isNotEmpty()) {
                    expressionAttributeValues = built.attributeValues
                }
            }
        })
    }

    /**
     * Delete an item as part of this transaction.
     */
    public fun <T : Table> T.delete(where: SqlExpressionBuilder.() -> Op<Boolean>) {
        delete(where) {}
    }

    /**
     * Delete an item as part of this transaction, with an optional condition.
     */
    public fun <T : Table> T.delete(
        where: SqlExpressionBuilder.() -> Op<Boolean>,
        block: T.(DeleteStatement) -> Unit
    ) {
        val (pk, sk) = extractKeyValues(where)
        val built = DeleteStatement(this, database, pk, sk).also { block(it) }.build()
        val resolvedName = database.resolveTableName(this)

        register(this, built.key)
        items.add(TransactWriteItem {
            delete {
                tableName = resolvedName
                key = built.key
                built.conditionExpression?.let { conditionExpression = it }
                if (built.attributeNames.isNotEmpty()) {
                    expressionAttributeNames = built.attributeNames
                }
                if (built.attributeValues.isNotEmpty()) {
                    expressionAttributeValues = built.attributeValues
                }
            }
        })
    }

    /**
     * Assert something about an item without writing to it. If the condition fails,
     * the entire transaction is cancelled.
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
        val (pk, sk) = extractKeyValues(where)
        val itemKey = buildKey(pk, sk)

        val nameIndex = mutableMapOf<String, String>()
        val valueIndex = mutableMapOf<String, AttributeValue>()
        val expression = buildConditionExpression(SqlExpressionBuilder().condition(), nameIndex, valueIndex)
        val resolvedName = database.resolveTableName(this)

        register(this, itemKey)
        items.add(TransactWriteItem {
            conditionCheck {
                tableName = resolvedName
                key = itemKey
                conditionExpression = expression
                if (nameIndex.isNotEmpty()) {
                    expressionAttributeNames = nameIndex
                }
                if (valueIndex.isNotEmpty()) {
                    expressionAttributeValues = valueIndex
                }
            }
        })
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

    /**
     * Extract the primary key attributes from a fully built item.
     */
    private fun Table.keyOf(item: Map<String, AttributeValue>): Map<String, AttributeValue> {
        val pkColumn = partitionKey ?: error("Table $tableName has no partition key")
        val pkValue = item[pkColumn.name]
            ?: error("Partition key ${pkColumn.name} not set on insert into $tableName")

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
     * without a round trip.
     */
    private fun register(table: Table, key: Map<String, AttributeValue>) {
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
        database.client.transactWriteItems {
            transactItems = items
            token?.let { this.clientRequestToken = it }
        }
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
 */
public class TransactionGet internal constructor(public val database: Database) {
    internal val tables: MutableList<Table> = mutableListOf()
    internal val items: MutableList<TransactGetItem> = mutableListOf()

    /**
     * Read an item as part of this transaction. The where clause must specify the
     * full primary key with equality conditions.
     */
    public fun <T : Table> T.get(where: SqlExpressionBuilder.() -> Op<Boolean>) {
        val (pk, sk) = extractKeyValues(where)
        val itemKey = buildKey(pk, sk)
        val resolvedName = database.resolveTableName(this)

        tables.add(this)
        items.add(TransactGetItem {
            get {
                tableName = resolvedName
                key = itemKey
            }
        })
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

    val response = client.transactGetItems {
        transactItems = request.items
    }

    return request.tables.mapIndexed { index, table ->
        response.responses?.getOrNull(index)?.item?.takeIf { it.isNotEmpty() }?.let {
            ResultRow(table, it)
        }
    }
}
