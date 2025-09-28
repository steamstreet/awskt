# DynamoKt

DynamoKt is a type-safe Kotlin library for working with AWS DynamoDB. It provides a coroutine-based API with strong typing, making DynamoDB operations more intuitive and less error-prone in Kotlin applications. Use DynamoKt when you need efficient, type-safe DynamoDB operations with support for transactions, queries, and batch operations.

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.steamstreet:awskt-dynamokt:2.1.0")
}
```

### Maven

```xml
<dependency>
    <groupId>com.steamstreet</groupId>
    <artifactId>awskt-dynamokt</artifactId>
    <version>2.1.0</version>
</dependency>
```

## Quickstart

```kotlin
import com.steamstreet.dynamokt.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // Configure DynamoDB table
    val dynamoKt = DynamoKt(
        table = "users-table",
        pkName = "userId",
        skName = "sortKey"
    )

    // Create a session
    val session = dynamoKt.session()

    // Put an item
    session.put("user_123", "profile") {
        set("name", "Alice Smith")
        set("age", 30)
        set("email", "alice@example.com")
    }

    // Get an item
    val user = session.get("user_123", "profile")
    println("User name: ${user.getString("name")}")

    // Update an item
    user.update {
        set("lastLogin", System.currentTimeMillis())
        increment("loginCount")
    }
}
```

## Common Recipes

### Initialize DynamoDB connection with custom credentials

```kotlin
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.smithy.kotlin.runtime.auth.awscredentials.StaticCredentialsProvider

val dynamoKt = DynamoKt(
    table = "my-table",
    pkName = "pk",
    skName = "sk",
    builder = { credentialsProvider ->
        DynamoDbClient {
            region = "us-east-1"
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = "YOUR_ACCESS_KEY"
                secretAccessKey = "YOUR_SECRET_KEY"
            }
        }
    }
)
```

### Create and update items

```kotlin
val session = dynamoKt.session()

// Create new item
session.put("product_456", "details") {
    set("productName", "Laptop")
    set("price", 999)
    set("inStock", true)
    set("categories", listOf("electronics", "computers"))
}

// Update existing item
session.update("product_456", "details") {
    set("price", 899)  // Update price
    increment("viewCount")  // Increment counter
    delete("temporaryField")  // Remove field
    addToSet("tags", "on-sale")  // Add to set
}
```

### Query items with filters

```kotlin
// Query by partition key
val items = session.query("user_123") {
    forward = true  // Sort ascending
    limit = 10  // Limit results
    attributes("name", "email")  // Project specific attributes

    // Filter by sort key
    sk.startsWith("order_")

    // Add additional filters
    filter {
        equalTo("status", "active")
        greaterThan("amount", 100)
    }
}.items()

items.forEach { item ->
    println("Order: ${item.getString("orderId")}")
}
```

### Batch get multiple items

```kotlin
val keys = listOf(
    "user_1" to "profile",
    "user_2" to "profile",
    "user_3" to "settings"
)

val items = session.getAll(keys, consistent = true)
items.forEach { item ->
    println("${item.pk}: ${item.getString("name")}")
}
```

### Use transactions for atomic operations

```kotlin
session.transaction().use { tx ->
    // All operations succeed or fail together
    tx.put("account_1", "balance") {
        set("amount", 1000)
    }

    tx.update("account_2", "balance") {
        increment("amount", 100)
    }

    tx.delete("temp_item", "temp_sort")

    // Condition check
    tx.condition(
        "account_3", "info",
        "attribute_exists(#status)",
        mapOf("#status" to "status"),
        emptyMap()
    )

    tx.commit()  // Execute transaction
}
```

### Configure and use Global Secondary Indexes

```kotlin
val dynamoKt = DynamoKt(
    table = "orders-table",
    pkName = "orderId",
    skName = "timestamp"
)

// Register GSI
dynamoKt.registerIndex(
    name = "customer-index",
    pk = "customerId",
    sk = "orderDate"
)

val session = dynamoKt.session()

// Query using GSI
val customerOrders = session.query("customer_789") {
    index("customer-index", "customerId", "orderDate")
    sk.between("2024-01-01", "2024-12-31")
}.items()
```

### Handle conditional updates with optimistic locking

```kotlin
session.update("doc_123", "v1") {
    set("content", "Updated content")
    set("version", 2)

    // Only update if version matches
    condition("version = :oldVersion") {
        value(":oldVersion", 1)
    }

    doNoOverwrite(returnValuesInResponse = true)
}.onFailure { exception ->
    if (exception is ConditionalCheckFailedException) {
        println("Version mismatch - document was modified")
    }
}
```

### Stream query results efficiently

```kotlin
// Stream through large result sets
val allActiveUsers = session.query("status_active") {
    index("status-index", "status", "userId")
}.itemsFlow()  // Returns Flow<Item>

allActiveUsers.collect { user ->
    processUser(user)  // Process each user as it arrives
}
```

### Delete items

```kotlin
// Delete single item
session.delete("user_999", "profile")

// Delete with condition
session.transaction().use { tx ->
    tx.delete("order_456", "2024-01-15") {
        condition("attribute_not_exists(shipped)")
    }
    tx.commit()
}
```

### Work with TTL (Time To Live)

```kotlin
val dynamoKt = DynamoKt(
    table = "sessions-table",
    pkName = "sessionId",
    skName = null,
    ttlAttribute = "expiresAt"
)

session.put("session_abc", null) {
    set("userId", "user_123")
    set("createdAt", System.currentTimeMillis())
    // Item will auto-delete after this timestamp
    set("expiresAt", System.currentTimeMillis() / 1000 + 3600)
}
```

## API Reference Summary

### Core Classes

- `DynamoKt` – Main configuration class for table setup and session creation
- `DynamoKtSession` – Handles all DynamoDB operations for a specific table
- `Item` – Represents a DynamoDB item with type-safe attribute access
- `MutableItem` – Updateable item with modification tracking
- `Transaction` – Manages atomic batch operations
- `Query` – Fluent API for building and executing queries
- `DynamoKtIndex` – Represents a Global Secondary Index configuration

### Key Methods

- `DynamoKtSession.put(pk, sk, block)` – Create new item with attributes
- `DynamoKtSession.get(pk, sk)` – Retrieve item by primary key
- `DynamoKtSession.getOrNull(pk, sk)` – Retrieve item or null if not found
- `DynamoKtSession.update(pk, sk, block)` – Update existing item
- `DynamoKtSession.delete(pk, sk)` – Delete item
- `DynamoKtSession.query(pk, block)` – Query items by partition key
- `DynamoKtSession.scan(block)` – Scan entire table with filters
- `DynamoKtSession.transaction()` – Start atomic transaction
- `DynamoKtSession.getAll(keys)` – Batch get multiple items

### Item Methods

- `Item.getString(key)` – Get string attribute value
- `Item.getInt(key)` – Get integer attribute value
- `Item.getLong(key)` – Get long attribute value
- `Item.getBoolean(key)` – Get boolean attribute value
- `Item.get(key)` – Get raw AttributeValue
- `Item.update(block)` – Update item with modifications

### MutableItem Methods

- `set(key, value)` – Set attribute value
- `increment(key, amount)` – Increment numeric value
- `delete(key)` – Remove attribute
- `addToSet(key, value)` – Add to string/number set
- `removeFromSet(key, value)` – Remove from set
- `condition(expression)` – Add update condition

## Error Handling & Troubleshooting

### `NotFoundException: Unknown item`

**Cause:** Attempting to retrieve an item that doesn't exist in the table.

**Fix:** Use `getOrNull()` instead of `get()` to handle missing items gracefully:

```kotlin
val item = session.getOrNull("missing_key", "missing_sort")
if (item == null) {
    // Handle missing item
}
```

### `ConditionalCheckFailedException`

**Cause:** A conditional update/put failed because the condition wasn't met.

**Fix:** Catch the exception and handle the conflict:

```kotlin
try {
    session.update("item", "sort") {
        set("value", 100)
        condition("attribute_exists(pk)")
    }
} catch (e: ConditionalCheckFailedException) {
    // Item doesn't exist or condition failed
}
```

### `ValidationException: The provided key element does not match the schema`

**Cause:** Using wrong attribute names for partition/sort keys.

**Fix:** Verify table schema matches your DynamoKt configuration:

```kotlin
val dynamoKt = DynamoKt(
    table = "my-table",
    pkName = "correctPkName",  // Must match table schema
    skName = "correctSkName"    // Must match table schema
)
```

### `ResourceNotFoundException: Requested resource not found`

**Cause:** Table doesn't exist or wrong table name.

**Fix:** Verify table name and ensure it exists in the correct region:

```kotlin
val session = dynamoKt.session()
val description = session.describeTable()  // Verify table exists
```

### `ProvisionedThroughputExceededException`

**Cause:** Request rate exceeds provisioned capacity.

**Fix:** Implement exponential backoff or increase table capacity:

```kotlin
// Implement retry with backoff
var retries = 0
while (retries < 3) {
    try {
        session.put(pk, sk) { /* ... */ }
        break
    } catch (e: ProvisionedThroughputExceededException) {
        delay(2.0.pow(retries).seconds)
        retries++
    }
}
```

## Best Practices & Performance Notes

### When to use DynamoKt

- Building Kotlin applications that heavily interact with DynamoDB
- Need type-safe operations with compile-time checking
- Working with complex DynamoDB patterns (transactions, batch operations)
- Want coroutine-based async operations for better performance

### When not to use DynamoKt

- Simple, one-off DynamoDB operations where AWS SDK is sufficient
- Non-Kotlin JVM applications (use AWS SDK for Java instead)
- Need advanced DynamoDB Streams processing (use dedicated stream processors)

### Performance Tips

- **Use batch operations** for multiple items to reduce API calls
- **Project only needed attributes** in queries to reduce data transfer
- **Use consistent reads sparingly** as they consume more capacity
- **Leverage GSIs** for alternative access patterns instead of scans
- **Cache session objects** when using the same credentials repeatedly

### Edge Cases

- **Empty strings**: DynamoDB doesn't support empty string values; they'll be converted to null
- **Numeric precision**: Large numbers may lose precision when stored as Number type
- **Set attributes**: Sets cannot be empty and must contain unique values
- **Item size limit**: Individual items cannot exceed 400KB
- **Transaction limits**: Transactions support maximum 100 operations

### Design Patterns

```kotlin
// Repository pattern
class UserRepository(private val session: DynamoKtSession) {
    suspend fun findById(userId: String): User? {
        return session.getOrNull(userId, "profile")?.let { item ->
            User(
                id = item.pk,
                name = item.getString("name") ?: "",
                email = item.getString("email") ?: ""
            )
        }
    }

    suspend fun save(user: User) {
        session.put(user.id, "profile") {
            set("name", user.name)
            set("email", user.email)
            set("updatedAt", Instant.now().toString())
        }
    }
}
```

## Versioning & Migration Notes

DynamoKt follows semantic versioning (SemVer). Current version: 2.1.0

### Breaking Changes in 2.0

- Migrated from AWS SDK v1 to v2 (kotlin SDK)
- All operations are now suspend functions requiring coroutines
- Changed from `AmazonDynamoDB` to `DynamoDbClient`
- Removed synchronous API methods

### Migration from 1.x to 2.x

```kotlin
// Old (1.x)
val dynamo = DynamoKt(table = "my-table")
val item = dynamo.get("key", "sort")  // Blocking

// New (2.x)
val dynamo = DynamoKt(table = "my-table")
val item = runBlocking {
    dynamo.session().get("key", "sort")  // Suspend function
}
```

### Future Deprecations

- Legacy builder methods will be removed in 3.0
- Synchronous wrapper methods are deprecated

For latest updates and migration guides, check the project repository.