package com.steamstreet.dynamokt.exposed

/**
 * The message shared by everything deprecated in this file. Binding a table to a database has three
 * spellings - [BoundTable], [TableInScope] and the [DatabaseScope] free functions - which differ
 * only in surface: `BoundTable` is the one that also covers queries, scans, batch operations and
 * transactions, so it is the one that stays.
 */
private const val USE_BOUND_TABLE: String =
    "Use database.bind(table), which covers queries, scans and batch operations too."

/**
 * Provides a scoped context for database operations.
 *
 * @deprecated in effect: everything reachable through this scope is deprecated in favour of
 * [BoundTable]. The class itself is not marked deprecated only because it still appears in the
 * signatures of the deprecated functions.
 */
public class DatabaseScope(public val database: Database) {
    /**
     * Bind a table to this database scope for cleaner API usage
     */
    @Deprecated(
        USE_BOUND_TABLE,
        ReplaceWith("database.bind(this)", "com.steamstreet.dynamokt.exposed.bind"),
        DeprecationLevel.WARNING
    )
    @Suppress("DEPRECATION")
    public fun <T : Table> T.inScope(): TableInScope<T> = TableInScope(this, database)
}

/**
 * A table bound to a specific database, allowing operations without passing database explicitly.
 *
 * @deprecated Use [BoundTable] via `database.bind(table)`. It supports the same insert / get /
 * update / delete, and additionally `selectAll()`, `select(...)`, `scan()`, `batchInsert` and
 * `batchDelete`, and can be used inside `database.transaction { }`.
 */
@Deprecated(
    USE_BOUND_TABLE,
    ReplaceWith("BoundTable", "com.steamstreet.dynamokt.exposed.BoundTable"),
    DeprecationLevel.WARNING
)
public class TableInScope<T : Table>(
    public val table: T,
    public val database: Database
) {
    /**
     * Insert a new item
     */
    public suspend fun insert(block: T.(InsertStatement) -> Unit): ResultRow {
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
    public suspend fun update(where: SqlExpressionBuilder.() -> Op<Boolean>, block: T.(UpdateStatement) -> Unit): ResultRow {
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
 *
 * @deprecated The scope buys nothing a bound table does not: bind each table once with
 * `database.bind(table)` and call it directly. Note that this was never a transaction - for atomic
 * writes use `database.transaction { }`.
 */
@Deprecated(
    "Bind each table with database.bind(table) instead; this scope is not a transaction. " +
        "Use database.transaction { } for atomic writes.",
    level = DeprecationLevel.WARNING
)
public suspend fun <T> Database.withTables(block: suspend DatabaseScope.() -> T): T {
    return DatabaseScope(this).block()
}

/**
 * Bind a table to a database for scoped operations.
 *
 * @deprecated Use `database.bind(table)`, which returns a [BoundTable] covering the full operation
 * surface.
 */
@Deprecated(
    USE_BOUND_TABLE,
    ReplaceWith("database.bind(this)", "com.steamstreet.dynamokt.exposed.bind"),
    DeprecationLevel.WARNING
)
@Suppress("DEPRECATION")
public fun <T : Table> T.inDatabase(database: Database): TableInScope<T> {
    return TableInScope(this, database)
}

/**
 * Insert into a table from within a [DatabaseScope].
 *
 * @deprecated Use `database.bind(table).insert(block)`.
 */
@Deprecated(
    USE_BOUND_TABLE,
    ReplaceWith("database.bind(table).insert(block)", "com.steamstreet.dynamokt.exposed.bind"),
    DeprecationLevel.WARNING
)
public suspend fun <T : Table> DatabaseScope.insert(
    table: T,
    block: T.(InsertStatement) -> Unit
): ResultRow {
    return table.insert(database, block)
}

/**
 * Read a single item from within a [DatabaseScope].
 *
 * @deprecated Use `database.bind(table).get(where)`.
 */
@Deprecated(
    USE_BOUND_TABLE,
    ReplaceWith("database.bind(table).get(where)", "com.steamstreet.dynamokt.exposed.bind"),
    DeprecationLevel.WARNING
)
public suspend fun <T : Table> DatabaseScope.get(
    table: T,
    where: SqlExpressionBuilder.() -> Op<Boolean>
): ResultRow? {
    return table.get(database, where)
}

/**
 * Update an item from within a [DatabaseScope].
 *
 * @deprecated Use `database.bind(table).update(where, block)`.
 */
@Deprecated(
    USE_BOUND_TABLE,
    ReplaceWith("database.bind(table).update(where, block)", "com.steamstreet.dynamokt.exposed.bind"),
    DeprecationLevel.WARNING
)
public suspend fun <T : Table> DatabaseScope.update(
    table: T,
    where: SqlExpressionBuilder.() -> Op<Boolean>,
    block: T.(UpdateStatement) -> Unit
): ResultRow {
    return table.update(database, where, block)
}

/**
 * Delete an item from within a [DatabaseScope].
 *
 * @deprecated Use `database.bind(table).delete(where)`.
 */
@Deprecated(
    USE_BOUND_TABLE,
    ReplaceWith("database.bind(table).delete(where)", "com.steamstreet.dynamokt.exposed.bind"),
    DeprecationLevel.WARNING
)
public suspend fun <T : Table> DatabaseScope.delete(
    table: T,
    where: SqlExpressionBuilder.() -> Op<Boolean>
): Boolean {
    return table.delete(database, where)
}
