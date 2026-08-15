# DynamoKt Exposed

An Exposed-style type-safe DSL for Amazon DynamoDB in Kotlin. This library provides a familiar API for developers who have used [Kotlin Exposed](https://github.com/JetBrains/Exposed) with SQL databases, adapted for DynamoDB's document model.

## Overview

DynamoKt Exposed provides:
- **Type-safe table definitions** using Kotlin objects
- **Exposed-style DSL** for CRUD operations
- **Automatic index selection** for queries, across the table, its GSIs and its LSIs
- **Server-side filters** for conditions no index can express as a key condition
- **Automatic pagination**, plus explicit page-at-a-time reads with resumable tokens
- **Conditional writes**, transactions and batch operations
- **Schema creation** from the same table definitions
- **Coroutine-based** async operations, multiplatform (JVM, Linux, macOS)

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

`Database.connect` takes a configuration block. It is not a suspending function: the underlying
client resolves region, endpoint and credentials lazily on the first call, so there is nothing to
await.

```kotlin
// Region, endpoint and credentials come from the standard AWS environment.
val database = Database.connect()

// Or configure the underlying client explicitly.
val database = Database.connect {
    client {
        region = "us-east-1"
        endpointUrl = "http://localhost:8000"   // DynamoDB Local / LocalStack
    }
    defaultConsistentRead = true
    tableNameMapper = { name -> "test_$name" }  // handy for tests and per-stage tables
}

// Or wrap a client you already have.
val database = Database(existingDynamoDbClient)
```

`tableNameMapper` is applied to every request, including schema creation, so a mapped database
reads, writes and creates the same physical tables.

### Basic CRUD Operations

```kotlin
// Insert (a DynamoDB put - see "Differences from SQL Exposed")
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

// Update (a DynamoDB update - an upsert)
Users.update(database, { Users.id eq "user#123" }) {
    it[name] = "John Smith"
    it[age] = 31
}

// Increment (Exposed-style, atomic)
Users.update(database, { Users.id eq "user#123" }) {
    it[age] = age + 1
}

// Delete; true when an item was actually there
val existed: Boolean = Users.delete(database) { Users.id eq "user#123" }
```

## Differences from SQL Exposed

Three of DynamoDB's native behaviours differ from what the same Exposed call means against SQL.
They are kept rather than hidden, because they are what "write this item" usually means here - but
each has an opt-out.

### `insert` is a put: it replaces

`insert` renders a `PutItem`, which **replaces any existing item with the same primary key
wholesale**. Attributes on the old item that the statement does not set are gone, and no error is
raised. For Exposed-style insert semantics call `ifNotExists()`, which adds
`attribute_not_exists` on the partition key so an existing item fails with
`ConditionalCheckFailedException` instead:

```kotlin
Users.insert(database) {
    it[id] = "user#123"
    it[name] = "John"
    it.ifNotExists()
}
```

### `update` is an upsert: it creates

`UpdateItem` on a key that does not exist **creates** an item holding that key plus whatever the
statement sets, rather than being the no-op `UPDATE ... WHERE` would be. Call `ifExists()` to
require the item to already exist; a miss then fails with `ConditionalCheckFailedException`.
`ifExists()` ANDs into any `condition { }`, in either order.

```kotlin
Users.update(database, { Users.id eq "user#123" }) {
    it[name] = "John Smith"
    it.ifExists()
}
```

### `delete` succeeds on a missing item

DynamoDB's delete is idempotent, so deleting nothing is not an error. The returned `Boolean`
distinguishes the two cases: `true` when an item existed at that key and was deleted, `false` when
there was nothing there.

## Column Types

| Method | Kotlin Type | DynamoDB Type |
|--------|-------------|---------------|
| `varchar(name)` | `String` | S |
| `text(name)` | `String` | S |
| `integer(name)` | `Int` | N |
| `long(name)` | `Long` | N |
| `double(name)` | `Double` | N |
| `bool(name)` | `Boolean` | BOOL |
| `binary(name)` | `ByteArray` | B |
| `timestamp(name)` | `kotlin.time.Instant` | S (ISO-8601) |
| `stringSet(name)` | `Set<String>` | SS |
| `numberSet(name)` | `Set<Long>` | NS |
| `enumeration<T>(name)` | `Enum<T>` | N (the ordinal) |
| `enumerationByName<T>(name)` | `Enum<T>` | S (the name) |
| `customEnumeration(name, fromDb, toDb)` | `Enum<T>` | S |
| `list(name, elementColumn)` | `List<T>` | L |
| `map(name)` | `Map<String, Any?>` | M |

A few of these have caveats worth knowing before they are keys or filters:

- **`enumeration<T>()` stores the ordinal**, matching Exposed. That makes it compact and orderable
  but couples stored data to declaration order: reordering the enum reinterprets existing rows.
  `enumerationByName<T>()` stores the name and does not.
- **`double()`** goes through DynamoDB's `N`, which carries more significant digits than a `Double`
  holds, so values written elsewhere may round on read. `NaN` and the infinities have no DynamoDB
  representation and are rejected by the service.
- **`stringSet()` / `numberSet()`** cannot be empty - DynamoDB has no empty set. Writing one throws
  `IllegalArgumentException`; remove the attribute instead.
- **`timestamp()`** writes fixed-width ISO-8601 UTC (`YYYY-MM-DDTHH:MM:SS.nnnnnnnnnZ`, always nine
  fractional digits and a `Z`). DynamoDB compares string sort keys byte-wise, and variable-width
  fractions sort wrongly, so the padding is what makes `between`/`gt`/`lt` on a timestamp sort key
  mean what they read as. Reading is lenient and accepts any ISO-8601 form, so older rows still
  decode - but a table with mixed-width history will not order correctly until it is rewritten.
- **`map()`** is schemaless and recursive to any depth. Values may be `null`, `String`, `Boolean`,
  any `Number`, `List<*>`, or `Map<*, *>` with `String` keys; anything else throws
  `IllegalArgumentException` naming the path and type. Round-trips are lossless **except for
  numeric widening**: `N` carries no Kotlin type tag, so integral values read back as `Long` and
  fractional ones as `Double`.

### Nullable Columns

```kotlin
object Products : Table("products") {
    val id = varchar("id").partitionKey()
    val description = varchar("description").nullable()
}

val desc: String? = product[Products.description]
```

`row[column]` throws `IllegalStateException` for a non-nullable column that is absent - which is
what a projection that did not include it looks like. Use `row.getOrNull(column)` or
`row.hasValue(column)` when absence is expected, and `row.raw` for the underlying attribute map.

## Composite Keys

```kotlin
object Orders : Table("orders") {
    val customerId = varchar("customerId").partitionKey()
    val orderId = varchar("orderId").sortKey()
    val amount = integer("amount")
}

// Full primary key: a GetItem
val order = Orders.get(database) {
    (Orders.customerId eq "cust#1") and (Orders.orderId eq "order#001")
}

// Partition key only: a Query over the partition
val customerOrders = Orders.selectAll(database)
    .where { Orders.customerId eq "cust#1" }
    .toList()
```

## Schema Creation

`SchemaUtils` builds the table from the definition itself, the way Exposed's
`SchemaUtils.create(table)` does for SQL - keys, GSIs, LSIs and projections included.

```kotlin
SchemaUtils.createTable(database, Users)

// Only the ones that are not there yet
SchemaUtils.createMissingTables(database, Users, Orders)

// Provisioned capacity instead of on-demand
SchemaUtils.createTable(
    database,
    Users,
    billingMode = BillingMode.Provisioned,
    provisionedThroughput = ProvisionedThroughput(readCapacityUnits = 5, writeCapacityUnits = 5),
)

SchemaUtils.dropTable(database, Users)
```

This is meant for tests, local development and bootstrapping. In production the table is normally
owned by infrastructure-as-code, which also owns tags, TTL, encryption, backups, deletion
protection and everything else `SchemaUtils` deliberately does not model.

What it does:

- Derives attribute definitions from the key columns of the table and every index, each declared
  exactly once, inferring the scalar type from the column type: `varchar`, `text`, `timestamp`,
  `enumerationByName` and `customEnumeration` are `S`; `integer`, `long`, `double` and
  `enumeration` are `N`; `binary` is `B`. A key column of any other type is rejected with an
  `IllegalArgumentException` naming it - DynamoDB allows only those three as key attributes.
- Maps each index's `projectionType` and `nonKeyAttributes` onto the service's projection. An
  `INCLUDE` projection that names no attributes is rejected, as is a `nonKeyAttributes` list on an
  `ALL` or `KEYS_ONLY` projection.
- Resolves the table name through `tableNameMapper`.
- Gives every GSI the table's `provisionedThroughput`. Per-index capacity is a tuning decision that
  belongs with whatever owns the production table; LSIs share the table's capacity by definition.

What it does not do:

- **Wait.** `CreateTable` and `DeleteTable` are asynchronous on AWS; the call returns while the
  table is still `CREATING`. DynamoDB Local and LocalStack create synchronously, which is why test
  suites can write immediately.
- **Migrate.** `createMissingTables` decides existence with `DescribeTable` and leaves an existing
  table exactly as it is, even when its schema no longer matches the definition. Only
  `ResourceNotFoundException` counts as missing, so a throttle or a credentials failure propagates
  rather than being answered with a doomed `CreateTable`.

## Queries and Filters

`selectAll(database)` and `select(database, columns...)` return a `Query` that is configured by
chaining and executed by a terminal operator.

```kotlin
Orders.selectAll(database)
    .where { (Orders.customerId eq "cust#1") and (Orders.orderId beginsWith "2024#") }
    .orderBy(descending = true)
    .limit(20)
    .toList()
```

| Builder | Effect |
|---------|--------|
| `where { }` | Set the condition. Calling it twice throws; use `andWhere` |
| `andWhere { }` | AND another condition into the existing one |
| `limit(n)` | Cap the rows returned across all pages (or bound a single `page()`) |
| `orderBy(descending)` | `ScanIndexForward`; queries only, ignored by scans and GetItem lookups |
| `startAfter(token)` | Resume after a previous `page()`; `null` is a no-op |

| Terminal | Result |
|----------|--------|
| `asFlow()` | `Flow<ResultRow>`, paginating automatically |
| `toList()` | `List<ResultRow>` |
| `firstOrNull()` | The first row or `null` |
| `single()` / `singleOrNull()` | Exactly one row / at most one |
| `page()` | One `PageResult` from exactly one request |
| `count()` | `Long`, without transferring the rows |

`firstOrNull()` asks DynamoDB for a single row when no `limit` was set, and `single()` /
`singleOrNull()` for two, so neither reads a whole partition to answer.

### Automatic Index Selection

`where` does not name an index. Every candidate - the base table, each GSI, each LSI - whose
partition key carries a top-level `eq` is considered, and the best is chosen:

1. The base table with the full primary key pinned by `eq` and nothing left over: a **GetItem**.
2. Any candidate whose sort key is also constrained: a **Query** on that index.
3. A bare partition-key match: a **Query** on that index.

The base table wins ties. When nothing pins a partition key with `eq`, `NoIndexMatchException` is
thrown naming the columns in the condition - use `scan()` instead.

```kotlin
object Docs : Table("docs") {
    val ownerId = varchar("ownerId").partitionKey()
    val docId = varchar("docId").sortKey()
    val updatedAt = varchar("updatedAt")
    val title = varchar("title")

    val byUpdated = gsi("byUpdated") {
        partitionKey(ownerId)
        sortKey(updatedAt)
    }
}

// byUpdated: its sort key is constrained, which beats a bare partition-key match on the table
Docs.selectAll(database)
    .where { (Docs.ownerId eq "owner#1") and (Docs.updatedAt ge "2024-01") }
```

**Consistent reads.** `defaultConsistentRead` is honoured by GetItem, base-table queries, LSI
queries and batch gets. It is forced off for GSI queries, which DynamoDB does not allow to be
consistent, and it is not applied to scans.

### Filters

Conditions the chosen index cannot express as key conditions do not disappear: they are rendered
into a server-side `FilterExpression`, so the rows that come back always satisfy the whole `where`
clause.

```kotlin
// ownerId is the key condition; status and amount become the filter
Orders.selectAll(database)
    .where {
        (Orders.ownerId eq "owner#1") and
            (Orders.status neq "CANCELLED") and
            (Orders.amount gt 100)
    }
```

Two rules follow from DynamoDB's own:

- **A sort key takes one condition.** `ge` combined with `le` folds into an inclusive `BETWEEN`;
  any other combination throws, rather than being silently narrowed.
- **A filter cannot mention the queried index's key attributes.** A leftover condition on one of
  them therefore throws with an explanation. A condition on a *table* key that is not a key of the
  chosen index is fine - it is a legal filter there.

Filters are applied after items are read, so they reduce the data transferred but **not** the read
capacity consumed.

### Operators

| Operator | Key condition | Filter / condition |
|----------|---------------|--------------------|
| `eq` | yes | yes |
| `gt`, `lt`, `ge`, `le` | sort key only | yes |
| `between(from, to)` | sort key only | yes |
| `beginsWith` | sort key only | yes |
| `neq` | no | yes |
| `not(op)` | no | yes |
| `inList(values)` | no | yes (1-100 values) |
| `contains(x)` | no | yes |
| `exists()`, `notExists()` | no | yes |
| `and` | yes | yes |
| `or` | no | yes |
| `keys(list)` | routes to BatchGetItem | no |

`contains` has two forms: on a `Column<String>` it is substring containment, and on a column
declared with `list(...)` it is element membership, encoded through the list's element column.

```kotlin
Orders.scan(database)
    .where {
        not(Orders.status inList listOf("CANCELLED", "REFUNDED")) and
            (Orders.tags contains "priority")
    }
```

`or` is a filter or condition operator only. A top-level `or` leaves no extractable partition key,
so a query using one throws `NoIndexMatchException` - it is most useful in conditional writes, such
as `attribute_not_exists(ts) or (ts le incoming)`.

### Projections

`select(database, columns...)` projects, so DynamoDB returns only the named attributes:

```kotlin
val names = Users.select(database, Users.id, Users.name)
    .where { Users.id eq "user#123" }
    .toList()
```

Reading a column that was not projected behaves exactly like reading one that is absent.

### Pagination

Flows paginate for you: `asFlow()`, `toList()` and `count()` follow `LastEvaluatedKey` to the end,
and `limit(n)` caps the total emitted across all pages.

For request/response paging, `page()` issues exactly one request and returns a `PageResult`:

```kotlin
val first = Orders.selectAll(database)
    .where { Orders.customerId eq "cust#1" }
    .limit(25)
    .page()

val next = Orders.selectAll(database)
    .where { Orders.customerId eq "cust#1" }
    .limit(25)
    .startAfter(first.nextToken)
    .page()
```

`nextToken` is surfaced faithfully from `LastEvaluatedKey`: it can be non-null on a short or even
empty page (DynamoDB's 1 MB cap, or a filter that rejected everything on it) and is `null` only
when the request reports no more pages. The token encoding matches the base `dynamokt` library's
`toJsonItemString()`/`fromJsonToItem()`, so tokens interoperate between the two layers. A corrupt or
foreign token raises `InvalidPageTokenException` from `startAfter` rather than a raw serialization
failure.

`page()` on a GetItem-routed lookup or a `keys(...)` read always reports `nextToken = null`; there
is no cursor concept on those paths, and `limit`/ordering are ignored.

### Counting

```kotlin
val open = Orders.selectAll(database)
    .where { (Orders.customerId eq "cust#1") and (Orders.status eq "OPEN") }
    .count()
```

Queries use `Select=COUNT`; scans project only the partition key and sum the per-page `Count`.
Both walk every page, so the answer covers the whole result rather than the first page, and
`limit(n)` caps it. `count()` is not available for `keys(...)` reads - `BatchGetItem` has no
server-side count - and throws `UnsupportedOperationException` there.

### Scan (Full Table)

```kotlin
val allOrders = Orders.scan(database).toList()

val pending = Orders.scan(database)
    .where { Orders.status eq "pending" }
    .toList()
```

A scan reads every item in the table and pays for every item read, filtered or not. Prefer a query
whenever an access pattern can be served by a key.

## Batch Operations

### Reading many keys

`keys(...)` routes the query to `BatchGetItem`. It cannot be combined with any other condition.

```kotlin
val rows = Orders.selectAll(database)
    .where { keys(listOf("cust#1" to "order#001", "cust#2" to "order#003")) }
    .toList()
```

Chunking to DynamoDB's 100-key limit and the retry of `UnprocessedKeys` happen in the client layer.
What the caller sees:

- **Order is undefined** - match rows back to keys by their key attributes, not by position.
- **Duplicate keys collapse** to one request and at most one row.
- **Missing items are simply absent**, exactly as `BatchGetItem` reports them.
- **A shortfall throws** `BatchGetIncompleteException`, which carries the items already retrieved
  and the keys still owed rather than quietly returning a short list.

Keys are validated against the table's key schema before anything is sent, so a pair that omits the
sort key of a composite-key table (or supplies one for a table without a sort key) fails with a
message naming the offending pair instead of a service-side `ValidationException` covering the
whole request.

### Writing many items

```kotlin
Users.batchInsert(database, newUsers) { statement, user ->
    statement[id] = user.id
    statement[name] = user.name
}

Users.batchDelete(database, listOf("user#1" to null, "user#2" to null))
```

Both chunk to 25 writes per request, retry `UnprocessedItems`, and throw
`BatchWriteIncompleteException` naming every write that did not land if they still do not - writes
not listed there did land, because a batch is not a transaction. `concurrency` (default 1) controls
how many chunks may be in flight, and consumes write capacity proportionally faster.

**Conditions are not available.** `BatchWriteItem` carries no condition expressions at all, so
calling `condition { }` or `ifNotExists()` inside a `batchInsert` block fails fast with
`IllegalArgumentException` rather than silently writing unconditionally. Use `insert` per item, or a
transaction, when the condition has to hold.

`batchDelete` de-duplicates its key list; `batchInsert` does **not** de-duplicate, because two
elements writing the same key may carry different attributes and dropping one would be a silent
choice. DynamoDB rejects a 25-item chunk containing the same key twice, so de-duplicate upstream
when the input may repeat a key.

## Global and Local Secondary Indexes

```kotlin
object Users : Table("users") {
    val id = varchar("id").partitionKey()
    val createdAt = varchar("createdAt").sortKey()
    val email = varchar("email")
    val name = varchar("name")
    val status = varchar("status")

    val byEmail = gsi("byEmail") {
        partitionKey(email)
        projectionType = ProjectionType.INCLUDE
        nonKeyAttributes = listOf("name")
    }

    val byStatus = lsi("byStatus") {
        sortKey(status)
    }
}
```

A GSI declares its own partition key and optional sort key; an LSI shares the table's partition key
and declares a different sort key. `projectionType` defaults to `ALL`; `INCLUDE` requires
`nonKeyAttributes`. Both are read by `SchemaUtils` when it creates the table, and index selection
uses the declarations to route queries.

```kotlin
// Automatically uses byEmail
val user = Users.selectAll(database)
    .where { Users.email eq "john@example.com" }
    .firstOrNull()
```

Rows read through a `KEYS_ONLY` or `INCLUDE` index only carry the projected attributes.

## Update Operations

An update expression is built from three clauses. A column may appear in only one of them - DynamoDB
rejects an expression that mentions the same attribute twice, and so does this builder, before the
request is sent.

```kotlin
Users.update(database, { Users.id eq "user#123" }) {
    it[name] = "New Name"        // SET
    it[age] = age + 1            // ADD (atomic increment)
    it[score] = score - 10       // ADD (atomic decrement)
    it.increment(Users.visits)   // ADD, the explicit spelling
    it.remove(Users.temporary)   // REMOVE
}
```

`+` and `-` work on `Column<Int>` and `Column<Long>`; `increment(column, amount)` has overloads for
both, and a negative amount decrements. An update with no clauses at all throws rather than sending
an empty expression.

The where clause of `update` and `delete` **must pin the full primary key with `eq`** - both target
exactly one item, and a range condition identifies no single item. Anything *beyond* the primary
key is folded into the operation's `ConditionExpression`, so:

```kotlin
Users.update(database, { (Users.id eq "user#123") and (Users.version eq 1) }) {
    it[Users.version] = 2
}
```

reads as optimistic locking and behaves that way: a version mismatch raises
`ConditionalCheckFailedException`. Extra conditions are never silently dropped.

`update` returns the item as it is after the write (`ReturnValues=ALL_NEW`).

## Conditional Writes

`condition { }` adds a `ConditionExpression` to an insert, update or delete; it is ANDed with any
residual conditions folded in from the where clause.

```kotlin
// Optimistic locking
Users.update(database, { Users.id eq "user#123" }) {
    it[name] = "Updated Name"
    it[version] = 2
    it.condition { version eq 1 }
}

// Insert only if absent
Users.insert(database) {
    it[id] = "user#123"
    it[name] = "John"
    it.ifNotExists()          // or: it.condition { id.notExists() }
}

// Conditional delete
Users.delete(database, { Users.id eq "user#123" }) {
    it.condition { status eq "inactive" }
}

// Last-writer-wins guard
Readings.insert(database) {
    it[sensorId] = "sensor#1"
    it[observedAt] = now
    it.condition { observedAt.notExists() or (observedAt le now) }
}
```

## Transactions

Writes can be committed atomically with `transaction`, which maps to DynamoDB's
`TransactWriteItems`. Operations may span multiple tables; either all of them apply or none do.

```kotlin
database.transaction {
    Accounts.update({ Accounts.id eq "account#1" }) {
        it.increment(Accounts.balance, -100)
        it.condition { Accounts.balance ge 100 }
    }
    Accounts.update({ Accounts.id eq "account#2" }) {
        it.increment(Accounts.balance, 100)
    }
    Transfers.insert {
        it[Transfers.id] = "transfer#1"
        it[Transfers.amount] = 100
    }
}
```

Inside the block the operations take **no `database` argument** - that is what enlists them in the
transaction. Passing one would run the operation immediately, outside the transaction, so those
forms are shadowed inside the block and **fail to compile** with an explanation rather than silently
losing atomicity. Bound tables work here too.

Because DynamoDB does not return attributes from a transaction, the operations return nothing; the
block's own value is returned instead.

### Condition Checks

`conditionCheck` asserts something about an item without writing to it. If the assertion fails, the
whole transaction is cancelled.

```kotlin
database.transaction {
    Accounts.conditionCheck({ Accounts.id eq "account#1" }) {
        Accounts.status eq "ACTIVE"
    }
    Transfers.insert {
        it[Transfers.id] = "transfer#1"
        it[Transfers.amount] = 25
    }
}
```

### Idempotent Retries

Pass a `clientRequestToken` so that a retry after an ambiguous failure doesn't apply the writes
twice. DynamoDB honours the token for roughly ten minutes.

```kotlin
database.transaction(clientRequestToken = requestId) {
    Orders.insert { it[Orders.id] = orderId }
}
```

### Transactional Reads

`transactionGet` reads several items as a single consistent snapshot (`TransactGetItems`). Results
come back in the order the reads were added, with `null` for items that don't exist.

```kotlin
val (user, order) = database.transactionGet {
    Users.get { Users.id eq "user#123" }
    Orders.get { Orders.id eq "order#456" }
}
```

A transactional get takes the primary key and nothing else: a read has no `ConditionExpression` to
carry extra conditions, so supplying them is an error rather than a silent no-op.

### Transaction Rules

- Both transaction types are limited to 100 operations, enforced as each one is added.
- A transaction may contain at most one operation per item; a duplicate raises
  `IllegalArgumentException` before any request is sent.
- Where clauses must specify the full primary key with equality; extra conditions become part of
  the operation's condition expression.
- A cancelled transaction throws `TransactionCanceledException`, whose `cancellationReasons` are
  positional - one entry per operation, in order.
- If the block throws, nothing is committed.
- **The builder is not thread-safe.** Add every operation from a single coroutine; do not `launch`
  inside the block and add operations concurrently. Gather your data first, then add the writes.

## Binding a Table to a Database

`database.bind(table)` returns a `BoundTable`, which carries the database so operations do not have
to repeat it. This is the binding API - it covers reads, writes, batches and transactions.

```kotlin
val users = database.bind(Users)

users.insert {
    it[Users.id] = "user#123"
    it[Users.name] = "John"
}

val user = users.get { Users.id eq "user#123" }
val page = users.selectAll().where { Users.id eq "user#123" }.toList()
val some = users.select(Users.id, Users.name).where { Users.id eq "user#123" }.toList()
val all  = users.scan().toList()

users.update({ Users.id eq "user#123" }) { it[Users.age] = 31 }
users.delete { Users.id eq "user#123" }

users.batchInsert(newUsers) { statement, u -> statement[Users.id] = u.id }
users.batchDelete(listOf("user#1" to null))

database.transaction {
    users.insert { it[Users.id] = "user#456" }
}
```

`Table.inDatabase(database)`, `TableInScope`, `DatabaseScope.inScope()`, `Database.withTables { }`
and the `DatabaseScope` free functions (`insert(table) { }`, `get(table) { }`, …) are **deprecated**
in favour of `bind`. They still work; each carries a `ReplaceWith` pointing at the equivalent bound
call. Note that `withTables` was never a transaction - for atomic writes use
`database.transaction { }`.

## Error Handling

The typed exceptions come from the `com.steamstreet.awskt.dynamodb` client, and the routing and
pagination errors from this module.

| Exception | Raised when |
|-----------|-------------|
| `ConditionalCheckFailedException` | A condition expression evaluated false |
| `TransactionCanceledException` | A transaction was cancelled; `cancellationReasons` says which operation |
| `ResourceNotFoundException` | The table (or index) does not exist |
| `ValidationException` | DynamoDB rejected the request |
| `ProvisionedThroughputExceededException` | The table ran out of provisioned capacity |
| `BatchGetIncompleteException` | Keys were still owed after retries; carries the partial results |
| `BatchWriteIncompleteException` | Writes were still owed after retries; carries the ones to resend |
| `NoIndexMatchException` | No table or index partition key was pinned with `eq` |
| `InvalidPageTokenException` | `startAfter` was given a token it cannot decode |

```kotlin
import com.steamstreet.awskt.dynamodb.ConditionalCheckFailedException
import com.steamstreet.awskt.dynamodb.TransactionCanceledException
import com.steamstreet.dynamokt.exposed.NoIndexMatchException

try {
    Users.update(database, { Users.id eq "user#123" }) {
        it[version] = 2
        it.condition { version eq 1 }
    }
} catch (e: ConditionalCheckFailedException) {
    // Another process modified the item
}

try {
    Users.selectAll(database).where { Users.name eq "John" }.toList()  // no index on name
} catch (e: NoIndexMatchException) {
    // Use scan(), or add a GSI
}
```

Argument errors - a where clause that does not pin the primary key, a sort key with two
irreconcilable conditions, an empty update, a batch key that does not match the schema - are
`IllegalArgumentException` and are raised before anything is sent.

## Limitations

### DynamoDB vs SQL

1. **No JOINs.** Design the data model for the access patterns instead.
2. **A query needs a partition key.** Nothing can be queried without an equality on the partition
   key of the table or one of its indices; `scan()` is the alternative and reads everything.
3. **Filters do not reduce read capacity.** They are applied after items are read, so a scan with a
   selective filter still pays for the whole table.
4. **A sort key takes one condition** in a key condition expression, and key attributes of the
   queried index cannot appear in a filter.
5. **Transactions are capped at 100 operations** and may touch each item only once.
6. **`inList` is capped at 100 values** by DynamoDB.
7. **Batch writes carry no conditions**, and report no per-item outcome.

### Not implemented here

1. **No DAO/entity pattern.** There is no entity class with property delegates; `ResultRow` is the
   read model and the statement builders are the write model.
2. **No set or list manipulation in updates.** The update expression supports `SET`, numeric `ADD`
   and `REMOVE`; there is no `list_append`, no update by list index, and no `ADD`/`DELETE` of set
   members.
3. **No parallel scan.** Scans are sequential; `Segment`/`TotalSegments` are not exposed.
4. **No explicit index override.** Index selection is automatic - a query cannot be pinned to a
   named index, only steered by what its `where` clause constrains.
5. **Per-query consistency is not configurable.** `defaultConsistentRead` is set on the `Database`;
   scans never request a consistent read.
6. **Scans always paginate to the end when counted.** `count()` on a scan walks every page.
7. **No schema migration.** `SchemaUtils` creates and drops; it does not reconcile an existing
   table, wait for `ACTIVE`, or manage TTL, tags, backups or auto-scaling.
8. **`update` returns only the new image.** `ReturnValues` is not configurable.

## Best Practices

1. **Design for access patterns.** Add a GSI for each one; index selection will find it.
2. **Use composite keys for hierarchical data** (`"USER#123"`, `"ORDER#2024#001"`).
3. **Use conditional writes for consistency.** `ifNotExists()`, `ifExists()` and version conditions
   turn races into `ConditionalCheckFailedException` instead of lost writes.
4. **Prefer queries to scans**, and `keys(...)` to a loop of `get` calls.
5. **Project what you need.** `select(columns...)` cuts the bytes transferred on wide items.
6. **Bind tables once** with `database.bind(table)` rather than threading `database` through.
7. **Let infrastructure own production tables**; keep `SchemaUtils` for tests and local development.

## Dependencies

```kotlin
dependencies {
    implementation("com.steamstreet:awskt-dynamokt-exposed:VERSION")
}
```

Required transitive dependencies:
- `com.steamstreet:awskt-aws-dynamodb` (the DynamoDB client)
- Kotlin Coroutines
- Kotlin Serialization
