package com.steamstreet.dynamokt.exposed

/**
 * Provides a scoped context for database operations.
 * Similar to Exposed's transaction block.
 *
 * Example:
 * ```
 * database.withTables {
 *     Users.insert {
 *         this[Users.id] = "user#123"
 *         this[Users.name] = "John"
 *     }
 *
 *     Orders.update("order#456") {
 *         this[Orders.status] = "SHIPPED"
 *     }
 * }
 * ```
 */
public class DatabaseScope(public val database: Database) {
    /**
     * Bind a table to this database scope for cleaner API usage
     */
    public fun <T : Table> T.inScope(): TableInScope<T> = TableInScope(this, database)
}

/**
 * A table bound to a specific database, allowing operations without passing database explicitly.
 */
public class TableInScope<T : Table>(
    public val table: T,
    public val database: Database
) {
    /**
     * Insert a new item
     */
    public suspend fun insert(block: InsertStatement.() -> Unit): ResultRow {
        return table.insert(database, block)
    }

    /**
     * Get an item using a where clause
     */
    public suspend fun get(where: SqlExpressionBuilder.() -> Op<Boolean>): ResultRow? {
        return table.get(database, where)
    }

    /**
     * Update an item using a where clause
     */
    public suspend fun update(where: SqlExpressionBuilder.() -> Op<Boolean>, block: UpdateStatement.() -> Unit): ResultRow {
        return table.update(database, where, block)
    }

    /**
     * Delete an item using a where clause
     */
    public suspend fun delete(where: SqlExpressionBuilder.() -> Op<Boolean>): Boolean {
        return table.delete(database, where)
    }
}

/**
 * Execute database operations within a scoped context.
 * Similar to Exposed's transaction block.
 *
 * Example:
 * ```
 * database.withTables {
 *     val usersInScope = Users.inScope()
 *     usersInScope.insert {
 *         this[Users.id] = "user#123"
 *     }
 * }
 * ```
 */
public suspend fun <T> Database.withTables(block: suspend DatabaseScope.() -> T): T {
    return DatabaseScope(this).block()
}

/**
 * Bind a table to a database for scoped operations.
 *
 * Example:
 * ```
 * val usersDb = Users.inDatabase(database)
 * usersDb.insert {
 *     this[Users.id] = "user#123"
 * }
 * usersDb.update("user#123") {
 *     this[Users.age] = 31
 * }
 * ```
 */
public fun <T : Table> T.inDatabase(database: Database): TableInScope<T> {
    return TableInScope(this, database)
}

/**
 * Extension functions to allow table operations directly within DatabaseScope
 * without explicit inScope() call.
 */
public suspend fun <T : Table> DatabaseScope.insert(
    table: T,
    block: InsertStatement.() -> Unit
): ResultRow {
    return table.insert(database, block)
}

public suspend fun <T : Table> DatabaseScope.get(
    table: T,
    where: SqlExpressionBuilder.() -> Op<Boolean>
): ResultRow? {
    return table.get(database, where)
}

public suspend fun <T : Table> DatabaseScope.update(
    table: T,
    where: SqlExpressionBuilder.() -> Op<Boolean>,
    block: UpdateStatement.() -> Unit
): ResultRow {
    return table.update(database, where, block)
}

public suspend fun <T : Table> DatabaseScope.delete(
    table: T,
    where: SqlExpressionBuilder.() -> Op<Boolean>
): Boolean {
    return table.delete(database, where)
}
