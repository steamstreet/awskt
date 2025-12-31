package com.steamstreet.dynamokt.exposed

import kotlin.time.ExperimentalTime

/**
 * Base class for defining DynamoDB tables in an Exposed-style API.
 * Tables should be declared as objects extending this class.
 *
 * Example:
 * ```
 * object Users : Table("users") {
 *     val id = varchar("id").partitionKey()
 *     val name = varchar("name")
 *     val age = integer("age")
 * }
 * ```
 */
public abstract class Table(public val tableName: String) {
    @PublishedApi
    internal val _columns: MutableList<Column<*>> = mutableListOf<Column<*>>()

    /**
     * All columns defined in this table
     */
    public val columns: List<Column<*>> get() = _columns.toList()

    /**
     * The partition key column for this table
     */
    public var partitionKey: Column<*>? = null
        private set

    /**
     * The sort key column for this table (optional)
     */
    public var sortKey: Column<*>? = null
        private set

    // Column factory methods following Exposed naming conventions

    /**
     * Define a varchar (String) column
     */
    public fun varchar(name: String): VarCharColumn =
        VarCharColumn(this, name).also { _columns.add(it) }

    /**
     * Define a text (String) column - alias for varchar in DynamoDB
     */
    public fun text(name: String): TextColumn =
        TextColumn(this, name).also { _columns.add(it) }

    /**
     * Define an integer (Int) column
     */
    public fun integer(name: String): IntegerColumn =
        IntegerColumn(this, name).also { _columns.add(it) }

    /**
     * Define a long (Long) column
     */
    public fun long(name: String): LongColumn =
        LongColumn(this, name).also { _columns.add(it) }

    /**
     * Define a boolean column
     */
    public fun bool(name: String): BoolColumn =
        BoolColumn(this, name).also { _columns.add(it) }

    /**
     * Define an enumeration column
     */
    public inline fun <reified T : Enum<T>> enumeration(name: String): EnumerationColumn<T> =
        EnumerationColumn(this, name, T::class).also { _columns.add(it) }

    /**
     * Define a list column
     */
    public fun <T> list(name: String, elementColumn: Column<T>): ListColumn<T> =
        ListColumn(this, name, elementColumn).also { _columns.add(it) }

    /**
     * Define a map column
     */
    public fun map(name: String): MapColumn =
        MapColumn(this, name).also { _columns.add(it) }

    /**
     * Define a timestamp column storing Instant values as ISO-8601 strings
     */
    @OptIn(ExperimentalTime::class)
    public fun timestamp(name: String): TimestampColumn =
        TimestampColumn(this, name).also { _columns.add(it) }

    // Key designation methods

    /**
     * Mark this column as the partition key
     */
    public fun <T> Column<T>.partitionKey(): Column<T> = apply {
        this@Table.partitionKey = this
    }

    /**
     * Mark this column as the sort key
     */
    public fun <T> Column<T>.sortKey(): Column<T> = apply {
        this@Table.sortKey = this
    }

    // Index support
    private val _indices = mutableListOf<Index>()

    /**
     * All indices defined for this table
     */
    public val indices: List<Index> get() = _indices.toList()

    /**
     * Define a Global Secondary Index
     */
    public fun gsi(name: String, block: GlobalSecondaryIndex.() -> Unit): GlobalSecondaryIndex {
        return GlobalSecondaryIndex(name, this).apply(block).also { _indices.add(it) }
    }

    /**
     * Define a Local Secondary Index
     */
    public fun lsi(name: String, block: LocalSecondaryIndex.() -> Unit): LocalSecondaryIndex {
        return LocalSecondaryIndex(name, this).apply(block).also { _indices.add(it) }
    }
}

/**
 * Base class for index definitions
 */
public sealed class Index(public val name: String, public val table: Table) {
    public abstract val partitionKey: Column<*>
    public abstract val sortKey: Column<*>?
}

/**
 * Global Secondary Index definition
 */
public class GlobalSecondaryIndex(name: String, table: Table) : Index(name, table) {
    override lateinit var partitionKey: Column<*>
        private set

    override var sortKey: Column<*>? = null
        private set

    /**
     * Set the partition key for this GSI
     */
    public fun <T> partitionKey(column: Column<T>) {
        partitionKey = column
    }

    /**
     * Set the sort key for this GSI
     */
    public fun <T> sortKey(column: Column<T>) {
        sortKey = column
    }

    public var projectionType: ProjectionType = ProjectionType.ALL
    public var nonKeyAttributes: List<String>? = null
}

/**
 * Local Secondary Index definition
 */
public class LocalSecondaryIndex(name: String, table: Table) : Index(name, table) {
    override val partitionKey: Column<*>
        get() = table.partitionKey ?: error("Table ${table.tableName} has no partition key defined")

    override lateinit var sortKey: Column<*>
        private set

    /**
     * Set the sort key for this LSI
     */
    public fun <T> sortKey(column: Column<T>) {
        sortKey = column
    }

    public var projectionType: ProjectionType = ProjectionType.ALL
    public var nonKeyAttributes: List<String>? = null
}

/**
 * Projection type for secondary indices
 */
public enum class ProjectionType {
    ALL,
    KEYS_ONLY,
    INCLUDE
}
