package com.steamstreet.dynamokt.exposed

import kotlinx.coroutines.flow.Flow

/**
 * A table bound to a specific database instance.
 * Allows calling table operations without passing the database each time.
 *
 * This is the binding API: `TableInScope` (`inDatabase`/`inScope`) and the `withTables` scope are
 * deprecated in its favour, because three ways to say the same thing is two too many and only this
 * one covers the whole surface - queries, scans, batches and transactions included.
 *
 * Example:
 * ```
 * val users = database.bind(Users)
 * users.selectAll().where { id eq "123" }
 * users.select(Users.id, Users.name).where { id eq "123" }
 * users.insert { it[name] = "John" }
 * users.get { Users.id eq "123" }
 * ```
 *
 * A `BoundTable` is also accepted directly inside `database.transaction { }` and
 * `database.transactionGet { }`.
 */
public class BoundTable<T : Table>(
    public val table: T,
    public val database: Database
)

/**
 * Bind a table to this database, returning a BoundTable that can be used
 * without passing the database to each operation.
 */
public fun <T : Table> Database.bind(table: T): BoundTable<T> = BoundTable(table, this)

/**
 * Select specific columns from the bound table.
 * Returns a Query that can be further configured with where(), etc.
 *
 * Example:
 * ```
 * val users = database.bind(Users)
 * users.select(Users.id, Users.name).where { Users.id eq "123" }
 * ```
 */
public fun <T : Table> BoundTable<T>.select(
    column: Column<*>,
    vararg columns: Column<*>
): Query {
    return table.select(database, column, *columns)
}

/**
 * Select all columns from the bound table.
 * Returns a Query that can be further configured with where(), etc.
 *
 * Example:
 * ```
 * val users = database.bind(Users)
 * users.selectAll().where { Users.id eq "123" }
 * ```
 */
public fun <T : Table> BoundTable<T>.selectAll(): Query {
    return table.selectAll(database)
}

/**
 * Perform a full table scan on the bound table.
 * Returns a Query that can be further configured with where(), etc.
 *
 * Example:
 * ```
 * val users = database.bind(Users)
 * users.scan().where { Users.active eq true }
 * ```
 */
public fun <T : Table> BoundTable<T>.scan(): Query {
    return table.scan(database)
}

/**
 * Batch get multiple items from the bound table by their keys.
 *
 * Example:
 * ```
 * val users = database.bind(Users)
 * users.batchGet(listOf("user#1" to null, "user#2" to null))
 * ```
 * @deprecated Use selectAll().where { keys(...) } instead for a unified API
 */
@Deprecated(
    "Use selectAll().where { keys(keys) } instead",
    ReplaceWith("selectAll().where { keys(keys) }.asFlow()")
)
@Suppress("DEPRECATION")
public fun <T : Table> BoundTable<T>.batchGet(
    keys: List<Pair<Any, Any?>>
): Flow<ResultRow> {
    return table.batchGet(database, keys)
}

/**
 * Insert a new item into the bound table.
 *
 * Example:
 * ```
 * val users = database.bind(Users)
 * users.insert {
 *     it[Users.name] = "John"
 *     it[Users.age] = 30
 * }
 * ```
 */
public suspend fun <T : Table> BoundTable<T>.insert(
    block: T.(InsertStatement) -> Unit
): ResultRow {
    return table.insert(database, block)
}

/**
 * Get a single item from the bound table using a where clause.
 *
 * Delegates to `selectAll().where(...).firstOrNull()`, so the same index selection applies: a full
 * primary key becomes a GetItem, a partition key match becomes a Query, and anything the chosen
 * index cannot express as a key condition becomes a server-side filter.
 *
 * Example:
 * ```
 * val users = database.bind(Users)
 * val user = users.get { Users.id eq "user#123" }
 * ```
 */
public suspend fun <T : Table> BoundTable<T>.get(
    where: SqlExpressionBuilder.() -> Op<Boolean>
): ResultRow? {
    return table.get(database, where)
}

/**
 * Update an item in the bound table.
 *
 * Example:
 * ```
 * val users = database.bind(Users)
 * users.update({ Users.id eq "user#123" }) {
 *     it[Users.age] = 31
 * }
 * ```
 */
public suspend fun <T : Table> BoundTable<T>.update(
    where: SqlExpressionBuilder.() -> Op<Boolean>,
    block: T.(UpdateStatement) -> Unit
): ResultRow {
    return table.update(database, where, block)
}

/**
 * Delete an item from the bound table.
 *
 * Example:
 * ```
 * val users = database.bind(Users)
 * users.delete { Users.id eq "user#123" }
 * ```
 */
public suspend fun <T : Table> BoundTable<T>.delete(
    where: SqlExpressionBuilder.() -> Op<Boolean>
): Boolean {
    return table.delete(database, where)
}

/**
 * Delete an item from the bound table with optional condition.
 *
 * Example:
 * ```
 * val users = database.bind(Users)
 * users.delete({ Users.id eq "user#123" }) {
 *     condition { Users.status eq "inactive" }
 * }
 * ```
 */
public suspend fun <T : Table> BoundTable<T>.delete(
    where: SqlExpressionBuilder.() -> Op<Boolean>,
    block: T.(DeleteStatement) -> Unit
): Boolean {
    return table.delete(database, where, block)
}

/**
 * Write many items into the bound table in one go. See [Table.batchInsert] for the semantics:
 * these are puts, conditions are not available, and a shortfall throws
 * `BatchWriteIncompleteException` naming the writes that did not land.
 *
 * Example:
 * ```
 * val users = database.bind(Users)
 * users.batchInsert(newUsers) { statement, user ->
 *     statement[Users.id] = user.id
 *     statement[Users.name] = user.name
 * }
 * ```
 */
public suspend fun <T : Table, E> BoundTable<T>.batchInsert(
    items: Iterable<E>,
    concurrency: Int = 1,
    block: T.(InsertStatement, E) -> Unit
): List<ResultRow> {
    return table.batchInsert(database, items, concurrency, block)
}

/**
 * Delete many items from the bound table by their full primary keys. See [Table.batchDelete].
 *
 * Example:
 * ```
 * val users = database.bind(Users)
 * users.batchDelete(listOf("user#1" to null, "user#2" to null))
 * ```
 */
public suspend fun <T : Table> BoundTable<T>.batchDelete(
    keys: List<Pair<Any, Any?>>,
    concurrency: Int = 1
) {
    table.batchDelete(database, keys, concurrency)
}
