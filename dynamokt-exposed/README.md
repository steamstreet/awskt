# DynamoKt Exposed

An Exposed-style type-safe DSL for Amazon DynamoDB in Kotlin. This library provides a familiar API for developers who have used [Kotlin Exposed](https://github.com/JetBrains/Exposed) with SQL databases, adapted for DynamoDB's document model.

## Overview

DynamoKt Exposed provides:
- **Type-safe table definitions** using Kotlin objects
- **Exposed-style DSL** for CRUD operations
- **Automatic index selection** for queries
- **Conditional writes** with version checking support
- **GSI/LSI support** as first-class citizens
- **Coroutine-based** async operations

## Quick Start

### Define a Table

Tables are defined as Kotlin objects extending `Table`:

```kotlin
object Users : Table("users") {
    val id = varchar("id").partitionKey()
    val name = varchar("name")
    val email = varchar("email")
    val age = integer("age")
    val active = bool("active")
}
```

### Connect to DynamoDB

```kotlin
val database = Database.connect(
    region = "us-east-1",
    endpoint = "http://localhost:8000"  // Optional: for local development
)

// Or wrap an existing client
val database = Database(existingDynamoDbClient)
```

### Basic CRUD Operations

```kotlin
// Insert
Users.insert(database) {
    it[id] = "user#123"
    it[name] = "John Doe"
    it[email] = "john@example.com"
    it[age] = 30
    it[active] = true
}

// Get by key
val user = Users.get(database) { Users.id eq "user#123" }
println(user?.get(Users.name))  // "John Doe"

// Update
Users.update(database, { Users.id eq "user#123" }) {
    it[name] = "John Smith"
    it[age] = 31
}

// Increment (Exposed-style)
Users.update(database, { Users.id eq "user#123" }) {
    it[age] = age + 1  // Atomic increment
}

// Delete
Users.delete(database) { Users.id eq "user#123" }
```

## Column Types

| Method | Kotlin Type | DynamoDB Type |
|--------|-------------|---------------|
| `varchar(name)` | `String` | S (String) |
| `text(name)` | `String` | S (String) |
| `integer(name)` | `Int` | N (Number) |
| `long(name)` | `Long` | N (Number) |
| `bool(name)` | `Boolean` | BOOL |
| `enumeration<T>(name)` | `Enum<T>` | S (String) |
| `list(name, elementColumn)` | `List<T>` | L (List) |
| `map(name)` | `Map<String, Any?>` | M (Map) |

### Nullable Columns

```kotlin
object Products : Table("products") {
    val id = varchar("id").partitionKey()
    val description = varchar("description").nullable()
}

// Access nullable values
val desc: String? = product.getOrNull(Products.description)
```

## Composite Keys

DynamoDB tables can have both partition and sort keys:

```kotlin
object Orders : Table("orders") {
    val customerId = varchar("customerId").partitionKey()
    val orderId = varchar("orderId").sortKey()
    val amount = integer("amount")
}

// Get with composite key
val order = Orders.get(database) {
    (Orders.customerId eq "cust#1") and (Orders.orderId eq "order#001")
}

// Query by partition key only (returns multiple items)
val customerOrders = Orders.select(database) {
    Orders.customerId eq "cust#1"
}.toList()
```

## Queries and Filters

### Select with Automatic Index Selection

The `select` function automatically chooses the best operation:
- **GetItem**: When full primary key is specified with equality
- **Query**: When partition key is specified (with optional sort key conditions)
- **Throws `NoIndexMatchException`**: When no index can satisfy the query

```kotlin
// Uses GetItem (full key specified)
Orders.select(database) {
    (Orders.customerId eq "cust#1") and (Orders.orderId eq "order#001")
}

// Uses Query (partition key only)
Orders.select(database) {
    Orders.customerId eq "cust#1"
}

// Uses Query with sort key condition
Orders.select(database) {
    (Orders.customerId eq "cust#1") and (Orders.orderId beginsWith "2024#")
}
```

### Sort Key Operators

| Operator | Description |
|----------|-------------|
| `eq` | Equals |
| `gt` | Greater than |
| `lt` | Less than |
| `ge` | Greater than or equal |
| `le` | Less than or equal |
| `between(from, to)` | Between (inclusive) |
| `beginsWith` | String prefix match |

```kotlin
// Range query
Events.select(database) {
    (Events.pk eq "stream#1") and (Events.sk gt 1000L)
}

// Between query
Events.select(database) {
    (Events.pk eq "stream#1") and Events.sk.between(1000L, 2000L)
}
```

### Scan (Full Table)

Use `scan` when you need to read the entire table:

```kotlin
// Scan all items
val allOrders = Orders.scan(database).toList()

// Scan with filter
val pendingOrders = Orders.scan(database) {
    Orders.status eq "pending"
}.toList()
```

### Batch Get

Retrieve multiple items by key:

```kotlin
val orders = Orders.selectAll(
    database,
    listOf(
        "cust#1" to "order#001",
        "cust#2" to "order#003"
    )
).toList()
```

## Global Secondary Indexes (GSI)

Define GSIs on your table:

```kotlin
object Users : Table("users") {
    val id = varchar("id").partitionKey()
    val email = varchar("email")
    val name = varchar("name")

    val byEmail = gsi("byEmail") {
        partitionKey(email)
    }
}
```

Queries automatically use the appropriate GSI:

```kotlin
// Automatically uses byEmail GSI
val user = Users.select(database) {
    Users.email eq "john@example.com"
}.firstOrNull()
```

## Conditional Writes

Conditional writes allow you to specify conditions that must be met for the operation to succeed. This is essential for implementing optimistic locking and preventing race conditions.

### Condition Operators

| Operator | Description |
|----------|-------------|
| `eq` | Equals |
| `neq` | Not equals |
| `exists()` | Attribute exists |
| `notExists()` | Attribute does not exist |
| `and` | Combine conditions |

### Version-Based Updates (Optimistic Locking)

```kotlin
object Users : Table("users") {
    val id = varchar("id").partitionKey()
    val name = varchar("name")
    val version = integer("version")
}

// Update only if version matches
try {
    Users.update(database, { Users.id eq "user#123" }) {
        it[name] = "Updated Name"
        it[version] = 2
        it.condition { version eq 1 }  // Only update if current version is 1
    }
} catch (e: ConditionalCheckFailedException) {
    // Another process modified the item
}
```

### Insert If Not Exists

```kotlin
// Using condition DSL
Users.insert(database) {
    it[id] = "user#123"
    it[name] = "John"
    it.condition { id.notExists() }
}

// Using convenience method
Users.insert(database) {
    it[id] = "user#123"
    it[name] = "John"
    it.ifNotExists()
}
```

### Conditional Delete

```kotlin
Users.delete(database, { Users.id eq "user#123" }) {
    it.condition { status eq "inactive" }
}
```

### Compound Conditions

```kotlin
Users.update(database, { Users.id eq "user#123" }) {
    it[status] = "archived"
    it.condition { (version eq 1) and (status eq "active") }
}
```

## Database Scope

For cleaner code when performing multiple operations on the same table:

### Table Binding

```kotlin
val usersDb = Users.inDatabase(database)

// No need to pass database each time
usersDb.insert {
    it[id] = "user#123"
    it[name] = "John"
}

val user = usersDb.get { Users.id eq "user#123" }

usersDb.update({ Users.id eq "user#123" }) {
    it[age] = 31
}

usersDb.delete { Users.id eq "user#123" }
```

### Multi-Table Scope

```kotlin
database.withTables {
    insert(Users) {
        it[id] = "user#456"
        it[name] = "Jane"
    }

    insert(Orders) {
        it[orderId] = "order#789"
        it[userId] = "user#456"
        it[total] = 15000
    }

    val user = get(Users) { Users.id eq "user#456" }
}
```

## Update Operations

### Set Values

```kotlin
Users.update(database, { Users.id eq "user#123" }) {
    it[name] = "New Name"
    it[age] = 25
}
```

### Increment (Atomic)

```kotlin
// Exposed-style syntax
Users.update(database, { Users.id eq "user#123" }) {
    it[age] = age + 1       // Increment by 1
    it[score] = score + 10  // Increment by 10
}

// Legacy method (still supported)
Users.update(database, { Users.id eq "user#123" }) {
    it.increment(age, 5)
}
```

### Remove Attributes

```kotlin
Users.update(database, { Users.id eq "user#123" }) {
    it.remove(temporaryField)
}
```

## Limitations

### DynamoDB vs SQL Differences

1. **No JOINs**: DynamoDB doesn't support joins. Design your data model accordingly.

2. **Query requires partition key**: You cannot query without specifying the partition key with equality. Use `scan` for full table operations.

3. **Limited filter expressions**: Filters are applied after data is read from DynamoDB, so they don't reduce read capacity consumption.

4. **No transactions in this version**: TransactWriteItems/TransactGetItems are not yet implemented.

5. **No automatic pagination**: Large result sets need manual pagination handling.

### Current Implementation Gaps

1. **No DAO/Entity pattern**: Unlike Exposed, there's no entity class with property delegates for automatic persistence.

2. **No schema creation utilities**: Table creation must be done separately (e.g., via AWS Console, CloudFormation, or direct SDK calls).

3. **Limited projection support**: Cannot specify which attributes to return.

4. **No OR conditions**: Only AND conditions are supported in where clauses.

5. **No update expressions for lists**: Cannot append to lists or update list items by index.

6. **Single-page results**: Query and scan operations return only the first page of results.

### Type Limitations

1. **MapColumn has limited type support**: Nested maps support String, Number, Boolean, List, and nested Map types only.

2. **No binary (B) type support**: Binary data types are not implemented.

3. **No set types (SS, NS, BS)**: String sets, number sets, and binary sets are not implemented.

## Error Handling

### Common Exceptions

```kotlin
import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException
import com.steamstreet.dynamokt.exposed.NoIndexMatchException

try {
    Users.update(database, { Users.id eq "user#123" }) {
        it[version] = 2
        it.condition { version eq 1 }
    }
} catch (e: ConditionalCheckFailedException) {
    // Condition was not met
}

try {
    Users.select(database) { Users.name eq "John" }  // No index on name
} catch (e: NoIndexMatchException) {
    // Use scan instead or add a GSI
}
```

## Best Practices

1. **Always define partition keys**: Every table must have a partition key.

2. **Use composite keys for hierarchical data**: Combine entity type and ID in partition keys (e.g., `"USER#123"`).

3. **Design for access patterns**: Create GSIs for each access pattern you need.

4. **Use conditional writes for consistency**: Implement optimistic locking with version fields.

5. **Prefer select over scan**: Scans read the entire table and are expensive.

6. **Use batch operations for multiple items**: `selectAll` is more efficient than multiple `get` calls.

## Dependencies

```kotlin
dependencies {
    implementation("com.steamstreet:awskt-dynamokt-exposed:VERSION")
}
```

Required transitive dependencies:
- AWS SDK for Kotlin (DynamoDB)
- Kotlin Coroutines
