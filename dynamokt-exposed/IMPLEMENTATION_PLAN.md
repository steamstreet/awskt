# DynamoKt Exposed - Implementation Plan

## Module Overview

**Module Name**: `dynamoKtExposed`

**Dependencies**:
- AWS SDK Kotlin DynamoDB client
- `standards` module (for common utilities)
- Kotlin Coroutines
- NO dependency on existing `dynamokt` module

**Goal**: Create an Exposed-style type-safe ORM for DynamoDB that matches the spirit and API patterns of Kotlin Exposed.

## Architecture Overview

**Exposed API Patterns to Follow**:
- Tables as `object` declarations extending base table class
- Columns declared as properties using type-specific functions
- DSL API: Operations directly on table objects (e.g., `Users.insert { }`)
- Property delegation for entity fields (e.g., `var name by Users.name`)
- Query chains with `.where { }` syntax

```
dynamoKtExposed/
├── src/main/kotlin/com/steamstreet/dynamokt/exposed/
│   ├── Table.kt                  # Base table class (like Exposed's Table)
│   ├── Column.kt                 # Column/attribute definitions
│   ├── Database.kt               # Database connection & transactions
│   ├── Op.kt                     # Query operators and expressions
│   ├── Query.kt                  # Query result handling
│   ├── statements/
│   │   ├── InsertStatement.kt    # Insert operations
│   │   ├── UpdateStatement.kt    # Update operations
│   │   ├── DeleteStatement.kt    # Delete operations
│   │   └── SelectStatement.kt    # Query/select operations
│   ├── dao/
│   │   ├── Entity.kt             # DAO base entity
│   │   ├── EntityClass.kt        # Entity factory
│   │   └── EntityID.kt           # Entity ID wrapper
│   └── schema/
│       ├── Index.kt              # GSI/LSI support
│       └── SchemaUtils.kt        # Table creation
└── src/test/kotlin/
    └── ... (test files)
```

## Phase 1: Core Foundation (Table & Column Definitions)

### 1.1 Column Type System

**File**: `core/Column.kt`

```kotlin
// Base column interface
interface Column<T> {
    val name: String
    val table: Table

    fun toAttributeValue(value: T): AttributeValue
    fun fromAttributeValue(value: AttributeValue): T
}

// Concrete column types (following Exposed naming conventions)
class VarCharColumn(override val table: Table, override val name: String) : Column<String>
class TextColumn(override val table: Table, override val name: String) : Column<String>
class IntegerColumn(override val table: Table, override val name: String) : Column<Int>
class LongColumn(override val table: Table, override val name: String) : Column<Long>
class BoolColumn(override val table: Table, override val name: String) : Column<Boolean>
class EnumerationColumn<T : Enum<T>>(override val table: Table, override val name: String, val enumClass: KClass<T>) : Column<T>
class ListColumn<T>(override val table: Table, override val name: String, val elementColumn: Column<T>) : Column<List<T>>
class MapColumn(override val table: Table, override val name: String) : Column<Map<String, Any?>>

// Nullable column wrapper
class NullableColumn<T : Any>(val wrapped: Column<T>) : Column<T?>
```

**Tasks**:
- [ ] Create `Column` interface with type parameter
- [ ] Implement `toAttributeValue` and `fromAttributeValue` for each type
- [ ] Add `nullable()` extension to make columns optional
- [ ] Add support for custom serializers/deserializers
- [ ] Implement `ListColumn` for list attributes
- [ ] Implement `MapColumn` for map attributes

### 1.2 Table Definition

**File**: `core/Table.kt`

```kotlin
abstract class Table(val tableName: String) {
    // Columns registry
    private val _columns = mutableListOf<Column<*>>()
    val columns: List<Column<*>> get() = _columns.toList()

    // Key definitions
    var partitionKey: Column<*>? = null
        private set
    var sortKey: Column<*>? = null
        private set

    // Column factory methods (following Exposed naming)
    fun varchar(name: String): VarCharColumn = VarCharColumn(this, name).also { _columns.add(it) }
    fun text(name: String): TextColumn = TextColumn(this, name).also { _columns.add(it) }
    fun integer(name: String): IntegerColumn = IntegerColumn(this, name).also { _columns.add(it) }
    fun long(name: String): LongColumn = LongColumn(this, name).also { _columns.add(it) }
    fun bool(name: String): BoolColumn = BoolColumn(this, name).also { _columns.add(it) }
    inline fun <reified T : Enum<T>> enumeration(name: String): EnumerationColumn<T> =
        EnumerationColumn(this, name, T::class).also { _columns.add(it) }
    fun <T> list(name: String, elementColumn: Column<T>): ListColumn<T> =
        ListColumn(this, name, elementColumn).also { _columns.add(it) }
    fun map(name: String): MapColumn = MapColumn(this, name).also { _columns.add(it) }

    // Key designation
    fun <T> Column<T>.partitionKey(): Column<T> = apply { this@Table.partitionKey = this }
    fun <T> Column<T>.sortKey(): Column<T> = apply { this@Table.sortKey = this }

    // GSI/LSI support
    private val _indices = mutableListOf<Index>()
    val indices: List<Index> get() = _indices.toList()

    fun gsi(name: String, block: GlobalSecondaryIndex.() -> Unit): GlobalSecondaryIndex {
        return GlobalSecondaryIndex(name, this).apply(block).also { _indices.add(it) }
    }

    fun lsi(name: String, block: LocalSecondaryIndex.() -> Unit): LocalSecondaryIndex {
        return LocalSecondaryIndex(name, this).apply(block).also { _indices.add(it) }
    }
}
```

**Tasks**:
- [ ] Create abstract `Table` class with table name
- [ ] Implement column registration system
- [ ] Add factory methods for all column types
- [ ] Implement partition key and sort key designation
- [ ] Add column auto-registration when created
- [ ] Support for nullable columns with `.nullable()`

### 1.3 Entity/Result Wrapper

**File**: `core/Entity.kt`

```kotlin
// Wraps DynamoDB item with type-safe access
class ResultRow(
    val table: Table,
    private val attributes: Map<String, AttributeValue>
) {
    operator fun <T> get(column: Column<T>): T {
        val value = attributes[column.name]
            ?: throw IllegalStateException("Column ${column.name} not found")
        return column.fromAttributeValue(value)
    }

    operator fun <T : Any> get(column: Column<T?>): T? {
        return attributes[column.name]?.let { column.fromAttributeValue(it) }
    }

    fun <T> getOrNull(column: Column<T>): T? {
        return attributes[column.name]?.let { column.fromAttributeValue(it) }
    }

    // Access underlying attributes
    val raw: Map<String, AttributeValue> get() = attributes
}
```

**Tasks**:
- [ ] Create `ResultRow` class to wrap DynamoDB items
- [ ] Implement type-safe `get` operator for columns
- [ ] Support nullable column access
- [ ] Add `getOrNull` for optional access
- [ ] Provide access to raw attribute map

### 1.4 Database Connection

**File**: `core/Database.kt`

```kotlin
class Database(
    val client: DynamoDbClient,
    val defaultConsistentRead: Boolean = false
) {
    companion object {
        // Similar to Exposed's Database.connect()
        fun connect(
            region: String = "us-east-1",
            endpoint: String? = null,
            configure: DynamoDbClient.Config.() -> Unit = {}
        ): Database {
            val client = DynamoDbClient {
                this.region = region
                endpoint?.let { endpointUrl = Url.parse(it) }
                configure()
            }
            return Database(client)
        }
    }
}

// Transaction context
suspend fun <T> Database.transaction(block: suspend Transaction.() -> T): T {
    return Transaction(this).block()
}
```

**Tasks**:
- [ ] Create `Database` class wrapping DynamoDB client
- [ ] Implement `connect()` factory method
- [ ] Add configuration options
- [ ] Support for custom client builders
- [ ] Transaction context management

## Phase 2: CRUD Operations

### 2.1 Insert/Put Operations

**File**: `dsl/Insert.kt`

```kotlin
// Insert builder
class InsertStatement(val table: Table, val database: Database) {
    private val values = mutableMapOf<Column<*>, Any?>()

    operator fun <T> set(column: Column<T>, value: T) {
        values[column] = value
    }

    infix fun <T> Column<T>.to(value: T) {
        values[this] = value
    }

    suspend fun execute(): ResultRow {
        // Build and execute putItem request
        val item = values.entries.associate { (column, value) ->
            column.name to (column as Column<Any?>).toAttributeValue(value)
        }

        database.client.putItem {
            tableName = table.tableName
            this.item = item
        }

        return ResultRow(table, item)
    }
}

// Extension function on Table
suspend fun Table.insert(database: Database, block: InsertStatement.() -> Unit): ResultRow {
    return InsertStatement(this, database).apply(block).execute()
}
```

**Tasks**:
- [ ] Create `InsertStatement` builder class
- [ ] Implement `set` operator for columns
- [ ] Support infix `to` syntax (like Exposed)
- [ ] Add conditional insert (putItem with condition)
- [ ] Implement `execute()` to perform the insert
- [ ] Return `ResultRow` with inserted data

### 2.2 Update Operations

**File**: `dsl/Update.kt`

```kotlin
class UpdateStatement(
    val table: Table,
    val database: Database,
    val pk: Any,
    val sk: Any? = null
) {
    private val updates = mutableMapOf<Column<*>, Any?>()
    private val removes = mutableSetOf<Column<*>>()
    private val increments = mutableMapOf<Column<*>, Number>()
    private val listAppends = mutableMapOf<Column<*>, Any>()

    operator fun <T> set(column: Column<T>, value: T) {
        updates[column] = value
    }

    fun <T> remove(column: Column<T>) {
        removes.add(column)
    }

    fun <T : Number> increment(column: Column<T>, amount: T) {
        increments[column] = amount
    }

    fun <T> appendToList(column: ListColumn<T>, value: T) {
        listAppends[column] = value
    }

    // List item updates
    fun <T> updateListItem(column: ListColumn<T>, index: Int, value: T) {
        // Use array index syntax
    }

    suspend fun execute(): ResultRow {
        // Build updateItem request with expressions
        // ...
    }
}

suspend fun Table.update(
    database: Database,
    pk: Any,
    sk: Any? = null,
    block: UpdateStatement.() -> Unit
): ResultRow {
    return UpdateStatement(this, database, pk, sk).apply(block).execute()
}
```

**Tasks**:
- [ ] Create `UpdateStatement` builder class
- [ ] Support `set`, `remove`, `increment` operations
- [ ] Build DynamoDB update expressions
- [ ] Handle attribute name and value placeholders
- [ ] Support list operations (append, update by index)
- [ ] Add conditional updates
- [ ] Return updated `ResultRow`

### 2.3 Query Operations

**File**: `dsl/Query.kt`

```kotlin
class Query(val table: Table, val database: Database) {
    private var pkCondition: KeyCondition? = null
    private var skCondition: KeyCondition? = null
    private var filterExpression: FilterExpression? = null
    private var index: Index? = null
    private var limitValue: Int? = null
    private var scanForward: Boolean = true
    private var consistentRead: Boolean = false

    // Partition key condition
    fun partitionKey(block: KeyConditionBuilder.() -> Unit) {
        pkCondition = KeyConditionBuilder().apply(block).build()
    }

    // Sort key condition
    fun sortKey(block: KeyConditionBuilder.() -> Unit) {
        skCondition = KeyConditionBuilder().apply(block).build()
    }

    // Filter
    fun filter(block: FilterBuilder.() -> Unit) {
        filterExpression = FilterBuilder(table).apply(block).build()
    }

    fun limit(count: Int) { limitValue = count }
    fun scanIndexForward(forward: Boolean) { scanForward = forward }
    fun withConsistentRead(consistent: Boolean) { consistentRead = consistent }

    // Use index
    fun useIndex(idx: Index) { index = idx }

    suspend fun execute(): List<ResultRow> {
        // Execute query and return results
        // ...
    }
}

// Extension
suspend fun Table.query(database: Database, block: Query.() -> Unit): List<ResultRow> {
    return Query(this, database).apply(block).execute()
}

// Simpler get by key
suspend fun Table.get(database: Database, pk: Any, sk: Any? = null): ResultRow? {
    val key = buildMap {
        partitionKey?.let { put(it.name, (it as Column<Any>).toAttributeValue(pk)) }
        sortKey?.let { put(it.name, (it as Column<Any>).toAttributeValue(sk!!)) }
    }

    val result = database.client.getItem {
        tableName = this@get.tableName
        this.key = key
    }

    return result.item?.let { ResultRow(this, it) }
}
```

**Tasks**:
- [ ] Create `Query` class for query operations
- [ ] Implement partition key condition builder
- [ ] Implement sort key condition builder (begins_with, between, comparison)
- [ ] Add filter expression support
- [ ] Support GSI/LSI queries
- [ ] Implement limit, scanIndexForward options
- [ ] Add consistent read option
- [ ] Implement simple `get()` for single item retrieval
- [ ] Handle pagination with continuation tokens

### 2.4 Delete Operations

**File**: `dsl/Delete.kt`

```kotlin
suspend fun Table.delete(database: Database, pk: Any, sk: Any? = null): Boolean {
    val key = buildMap {
        partitionKey?.let { put(it.name, (it as Column<Any>).toAttributeValue(pk)) }
        sortKey?.let { put(it.name, (it as Column<Any>).toAttributeValue(sk!!)) }
    }

    database.client.deleteItem {
        tableName = this@delete.tableName
        this.key = key
    }

    return true
}

// Conditional delete
class DeleteStatement(val table: Table, val database: Database, val pk: Any, val sk: Any? = null) {
    private var condition: FilterExpression? = null

    fun condition(block: FilterBuilder.() -> Unit) {
        condition = FilterBuilder(table).apply(block).build()
    }

    suspend fun execute(): Boolean {
        // Execute with condition
    }
}
```

**Tasks**:
- [ ] Implement simple delete by key
- [ ] Add conditional delete support
- [ ] Return success/failure status

## Phase 3: Query Builders & Filters

### 3.1 Filter Expression Builder

**File**: `dsl/Filter.kt`

```kotlin
class FilterBuilder(val table: Table) {
    private val expressions = mutableListOf<String>()
    private val names = mutableMapOf<String, String>()
    private val values = mutableMapOf<String, AttributeValue>()

    // Comparison operations
    infix fun <T> Column<T>.eq(value: T): FilterExpression {
        return buildExpression(this, "=", value)
    }

    infix fun <T> Column<T>.neq(value: T): FilterExpression {
        return buildExpression(this, "<>", value)
    }

    infix fun <T : Comparable<T>> Column<T>.gt(value: T): FilterExpression {
        return buildExpression(this, ">", value)
    }

    infix fun <T : Comparable<T>> Column<T>.lt(value: T): FilterExpression {
        return buildExpression(this, "<", value)
    }

    infix fun <T : Comparable<T>> Column<T>.gte(value: T): FilterExpression {
        return buildExpression(this, ">=", value)
    }

    infix fun <T : Comparable<T>> Column<T>.lte(value: T): FilterExpression {
        return buildExpression(this, "<=", value)
    }

    // Function-based operations
    fun <T> Column<T>.isNull(): FilterExpression { /* ... */ }
    fun <T> Column<T>.isNotNull(): FilterExpression { /* ... */ }

    infix fun Column<String>.contains(value: String): FilterExpression { /* ... */ }
    infix fun Column<String>.beginsWith(value: String): FilterExpression { /* ... */ }

    fun <T : Comparable<T>> Column<T>.between(from: T, to: T): FilterExpression { /* ... */ }

    infix fun <T> Column<T>.inList(values: List<T>): FilterExpression { /* ... */ }

    // List operations
    fun <T> ListColumn<T>.size(): SizeExpression { /* ... */ }

    // Logical operations
    infix fun FilterExpression.and(other: FilterExpression): FilterExpression { /* ... */ }
    infix fun FilterExpression.or(other: FilterExpression): FilterExpression { /* ... */ }
    fun not(expression: FilterExpression): FilterExpression { /* ... */ }

    fun build(): FilterExpression {
        return FilterExpression(expressions, names, values)
    }
}

data class FilterExpression(
    val expressions: List<String>,
    val names: Map<String, String>,
    val values: Map<String, AttributeValue>
)
```

**Tasks**:
- [ ] Create `FilterBuilder` class
- [ ] Implement comparison operators (eq, neq, gt, lt, gte, lte)
- [ ] Add function-based operations (contains, beginsWith, between, in)
- [ ] Support null checks
- [ ] Implement list operations
- [ ] Add logical operators (and, or, not)
- [ ] Generate expression attribute names and values
- [ ] Combine multiple expressions properly

### 3.2 Key Condition Builder

**File**: `dsl/KeyCondition.kt`

```kotlin
class KeyConditionBuilder {
    private var expression: String? = null
    private val names = mutableMapOf<String, String>()
    private val values = mutableMapOf<String, AttributeValue>()

    fun <T> Column<T>.eq(value: T): KeyCondition {
        // Build key condition for equality
    }

    fun <T : Comparable<T>> Column<T>.gt(value: T): KeyCondition { /* ... */ }
    fun <T : Comparable<T>> Column<T>.lt(value: T): KeyCondition { /* ... */ }
    fun <T : Comparable<T>> Column<T>.gte(value: T): KeyCondition { /* ... */ }
    fun <T : Comparable<T>> Column<T>.lte(value: T): KeyCondition { /* ... */ }

    fun Column<String>.beginsWith(value: String): KeyCondition { /* ... */ }

    fun <T : Comparable<T>> Column<T>.between(from: T, to: T): KeyCondition { /* ... */ }

    fun build(): KeyCondition {
        return KeyCondition(expression!!, names, values)
    }
}
```

**Tasks**:
- [ ] Create `KeyConditionBuilder` for partition/sort key conditions
- [ ] Support equality for partition key
- [ ] Support comparison operators for sort key
- [ ] Add `beginsWith` for sort key strings
- [ ] Add `between` for sort key ranges

## Phase 4: Schema Management

### 4.1 Index Definitions

**File**: `schema/Index.kt`

```kotlin
sealed class Index(val name: String, val table: Table) {
    abstract val partitionKey: Column<*>
    abstract val sortKey: Column<*>?
}

class GlobalSecondaryIndex(name: String, table: Table) : Index(name, table) {
    override lateinit var partitionKey: Column<*>
        private set
    override var sortKey: Column<*>? = null
        private set

    fun <T> partitionKey(column: Column<T>) {
        partitionKey = column
    }

    fun <T> sortKey(column: Column<T>) {
        sortKey = column
    }

    var projectionType: ProjectionType = ProjectionType.All
    var nonKeyAttributes: List<String>? = null
}

class LocalSecondaryIndex(name: String, table: Table) : Index(name, table) {
    override val partitionKey: Column<*>
        get() = table.partitionKey ?: error("Table has no partition key")

    override lateinit var sortKey: Column<*>
        private set

    fun <T> sortKey(column: Column<T>) {
        sortKey = column
    }

    var projectionType: ProjectionType = ProjectionType.All
    var nonKeyAttributes: List<String>? = null
}
```

**Tasks**:
- [ ] Create `Index` sealed class hierarchy
- [ ] Implement `GlobalSecondaryIndex` with configurable keys
- [ ] Implement `LocalSecondaryIndex` (uses table's PK)
- [ ] Add projection configuration
- [ ] Support key-only, all, and include projections

### 4.2 Schema Utilities

**File**: `schema/SchemaUtils.kt`

```kotlin
object SchemaUtils {
    suspend fun createTable(database: Database, table: Table, block: CreateTableBuilder.() -> Unit = {}) {
        val builder = CreateTableBuilder(table).apply(block)

        database.client.createTable {
            tableName = table.tableName

            // Build key schema
            keySchema = buildList {
                table.partitionKey?.let {
                    add(KeySchemaElement {
                        attributeName = it.name
                        keyType = KeyType.Hash
                    })
                }
                table.sortKey?.let {
                    add(KeySchemaElement {
                        attributeName = it.name
                        keyType = KeyType.Range
                    })
                }
            }

            // Build attribute definitions
            attributeDefinitions = buildAttributeDefinitions(table)

            // GSIs
            if (table.indices.isNotEmpty()) {
                globalSecondaryIndexes = table.indices.filterIsInstance<GlobalSecondaryIndex>()
                    .map { buildGsiDefinition(it) }
                localSecondaryIndexes = table.indices.filterIsInstance<LocalSecondaryIndex>()
                    .map { buildLsiDefinition(it) }
            }

            billingMode = builder.billingMode
        }
    }

    suspend fun dropTable(database: Database, table: Table) {
        database.client.deleteTable {
            tableName = table.tableName
        }
    }

    suspend fun tableExists(database: Database, table: Table): Boolean {
        return try {
            database.client.describeTable { tableName = table.tableName }
            true
        } catch (e: ResourceNotFoundException) {
            false
        }
    }
}

class CreateTableBuilder(val table: Table) {
    var billingMode: BillingMode = BillingMode.PayPerRequest
    var readCapacity: Long? = null
    var writeCapacity: Long? = null
}
```

**Tasks**:
- [ ] Create `SchemaUtils` object
- [ ] Implement `createTable()` to create DynamoDB tables from schema
- [ ] Build key schema from partition/sort key columns
- [ ] Build attribute definitions for all keys
- [ ] Handle GSI and LSI creation
- [ ] Support billing mode configuration
- [ ] Add `dropTable()` utility
- [ ] Add `tableExists()` check

## Phase 5: Transactions

### 5.1 Transaction Support

**File**: `dsl/Transaction.kt`

```kotlin
class Transaction(val database: Database) {
    private val operations = mutableListOf<TransactWriteItem>()

    fun <T> Table.insert(block: InsertStatement.() -> Unit) {
        val stmt = InsertStatement(this, database).apply(block)
        operations.add(stmt.toTransactWriteItem())
    }

    fun Table.update(pk: Any, sk: Any? = null, block: UpdateStatement.() -> Unit) {
        val stmt = UpdateStatement(this, database, pk, sk).apply(block)
        operations.add(stmt.toTransactWriteItem())
    }

    fun Table.delete(pk: Any, sk: Any? = null) {
        // Add delete operation
    }

    fun conditionCheck(table: Table, pk: Any, sk: Any? = null, block: FilterBuilder.() -> Unit) {
        // Add condition check
    }

    suspend fun execute() {
        database.client.transactWriteItems {
            transactItems = operations
        }
    }
}

suspend fun <T> Database.transaction(block: suspend Transaction.() -> T): T {
    val txn = Transaction(this)
    val result = txn.block()
    txn.execute()
    return result
}
```

**Tasks**:
- [ ] Create `Transaction` class
- [ ] Support transactional insert, update, delete
- [ ] Add condition check operations
- [ ] Build `TransactWriteItems` request
- [ ] Implement transaction execution
- [ ] Handle transaction errors and rollback

## Phase 6: DAO Pattern Support

### 6.1 Entity Classes

**File**: `dao/EntityClass.kt`

```kotlin
// Entity ID wrapper
sealed class EntityID<T : Comparable<T>> {
    abstract val value: T
}

data class StringEntityID(override val value: String) : EntityID<String>()
data class IntEntityID(override val value: Int) : EntityID<Int>()
data class LongEntityID(override val value: Long) : EntityID<Long>()

// Base entity class
abstract class Entity<ID : Comparable<ID>>(val id: EntityID<ID>) {
    protected lateinit var _resultRow: ResultRow

    val resultRow: ResultRow get() = _resultRow

    internal fun hydrate(row: ResultRow) {
        _resultRow = row
    }
}

// Entity class factory
abstract class EntityClass<ID : Comparable<ID>, E : Entity<ID>>(
    val table: Table,
    val database: Database,
    val entityConstructor: (EntityID<ID>) -> E
) {
    private val idColumn: Column<ID> get() = table.partitionKey as Column<ID>

    suspend fun findById(id: ID): E? {
        val row = table.get(database, id) ?: return null
        return wrapRow(row)
    }

    suspend fun new(id: ID, init: E.() -> Unit): E {
        val entity = entityConstructor(id.toEntityID())
        entity.init()
        // Insert entity
        return entity
    }

    protected fun wrapRow(row: ResultRow): E {
        val id = row[idColumn]
        return entityConstructor(id.toEntityID()).apply {
            hydrate(row)
        }
    }

    private fun ID.toEntityID(): EntityID<ID> = when (this) {
        is String -> StringEntityID(this) as EntityID<ID>
        is Int -> IntEntityID(this) as EntityID<ID>
        is Long -> LongEntityID(this) as EntityID<ID>
        else -> error("Unsupported ID type")
    }
}
```

**Tasks**:
- [ ] Create `Entity` base class
- [ ] Implement `EntityID` wrapper types
- [ ] Create `EntityClass` factory
- [ ] Support `findById()` for entity retrieval
- [ ] Support `new()` for entity creation
- [ ] Add `delete()` method
- [ ] Support lazy loading of relationships

### 6.2 DAO Example Pattern

```kotlin
// Example usage:
object Users : Table("users") {
    val id = varchar("id").partitionKey()
    val name = varchar("name")
    val email = varchar("email")
    val age = integer("age")
}

class User(id: EntityID<String>) : Entity<String>(id) {
    var name by Users.name
    var email by Users.email
    var age by Users.age

    suspend fun save() {
        Users.update(database, id.value) {
            it[Users.name] = name
            it[Users.email] = email
            it[Users.age] = age
        }
    }
}

object UserClass : EntityClass<String, User>(Users, database, ::User)
```

**Tasks**:
- [ ] Support property delegates for entity fields
- [ ] Implement save/update pattern
- [ ] Add lazy loading support
- [ ] Support entity relationships

## Phase 7: Testing & Documentation

### 7.1 Unit Tests

**Test Files**:
- `TableDefinitionTest.kt` - Test table and column definitions
- `InsertTest.kt` - Test insert operations
- `UpdateTest.kt` - Test update operations
- `QueryTest.kt` - Test query operations
- `FilterTest.kt` - Test filter builders
- `TransactionTest.kt` - Test transactions
- `EntityClassTest.kt` - Test DAO pattern
- `SchemaUtilsTest.kt` - Test schema creation

**Tasks**:
- [ ] Set up test infrastructure with LocalStack/DynamoDB Local
- [ ] Write comprehensive tests for each component
- [ ] Add integration tests for complex scenarios
- [ ] Test error handling and edge cases

### 7.2 Documentation

**Tasks**:
- [ ] Write comprehensive README with examples
- [ ] Document migration from current dynamoKt
- [ ] Create API reference documentation
- [ ] Add KDoc comments to all public APIs
- [ ] Write usage guides for common patterns
- [ ] Document differences from Kotlin Exposed

## Example API Usage

```kotlin
// Table definition following Exposed conventions
object Users : Table("users") {
    val id = varchar("id").partitionKey()
    val name = varchar("name")
    val email = varchar("email")
    val age = integer("age")
    val active = bool("active")
    val tags = list("tags", varchar(""))
}

// DSL API
val db = Database.connect()

// Insert
Users.insert(db) {
    it[id] = "user#123"
    it[name] = "John Doe"
    it[email] = "john@example.com"
    it[age] = 30
    it[active] = true
}

// Get
val user = Users.get(db, "user#123")
println(user[Users.name])  // Type-safe access

// Update
Users.update(db, "user#123") {
    it[age] = 31
    it.increment(loginCount)
}

// Query
val activeUsers = Users.query(db) {
    partitionKey { Users.id eq "user#123" }
    filter { Users.active eq true }
}

// DAO API
class User(id: EntityID<String>) : Entity<String>(id) {
    var name by Users.name
    var email by Users.email
    var age by Users.age
}

val user = UserClass.findById("user#123")
user.age = 31
user.save()
```

## Implementation Priority

### Milestone 1: Core Foundation (Weeks 1-2)
- Table and Column definitions
- ResultRow wrapper
- Database connection

### Milestone 2: Basic CRUD (Weeks 3-4)
- Insert operations
- Get by key
- Update operations
- Delete operations

### Milestone 3: Queries (Weeks 5-6)
- Query builder
- Key conditions
- Filter expressions
- Index queries

### Milestone 4: Advanced Features (Weeks 7-8)
- Transactions
- Schema utilities
- DAO pattern
- List/Map operations

### Milestone 5: Polish & Testing (Weeks 9-10)
- Comprehensive tests
- Documentation
- Performance optimization
- Migration guides

## Key Design Decisions

### 1. No Dependency on dynamoKt
- Clean slate implementation
- Direct AWS SDK usage
- Can be used alongside or instead of dynamoKt

### 2. Exposed-Like API
- Match Exposed's patterns where applicable
- Column-based type safety
- DSL builders for operations
- DAO pattern support

### 3. DynamoDB-Specific Features
- GSI/LSI support as first-class citizens
- Proper handling of DynamoDB's key-value model
- Support for list and map attributes
- Transaction support with conditions

### 4. Kotlin-First Design
- Coroutines for async operations
- Extension functions for clean API
- Property delegates for DAO pattern
- Inline reified for type safety

## Success Criteria

- [ ] Type-safe table definitions
- [ ] Complete CRUD operations
- [ ] Query with filters and conditions
- [ ] Transaction support
- [ ] GSI/LSI queries
- [ ] DAO pattern support
- [ ] 80%+ test coverage
- [ ] Comprehensive documentation
- [ ] Performance comparable to raw SDK usage
- [ ] Easy migration from current dynamoKt