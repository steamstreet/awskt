package com.steamstreet.dynamokt.exposed

import com.steamstreet.awskt.dynamodb.AttributeDefinition
import com.steamstreet.awskt.dynamodb.BillingMode
import com.steamstreet.awskt.dynamodb.CreateTableRequest
import com.steamstreet.awskt.dynamodb.GlobalSecondaryIndex
import com.steamstreet.awskt.dynamodb.KeySchemaElement
import com.steamstreet.awskt.dynamodb.KeyType
import com.steamstreet.awskt.dynamodb.Projection
import com.steamstreet.awskt.dynamodb.ProjectionType
import com.steamstreet.awskt.dynamodb.ScalarAttributeType

import aws.sdk.kotlin.services.dynamodb.createTable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertFailsWith

@Testcontainers
class QueryTest : ExposedTestBase() {

    object Orders : Table("orders") {
        val customerId = varchar("customerId").partitionKey()
        val orderId = varchar("orderId").sortKey()
        val amount = integer("amount")
        val status = varchar("status")
    }

    object Users : Table("users") {
        val id = varchar("id").partitionKey()
        val name = varchar("name")
        val email = varchar("email")

        val byEmail = gsi("byEmail") {
            partitionKey(email)
        }
    }

    object Events : Table("events") {
        val pk = varchar("pk").partitionKey()
        val sk = long("sk").sortKey()
        val type = varchar("type")
    }

    // Container-style GSI: many rows share the same GSI partition key (artistType)
    // and are ordered by a GSI sort key (position). Exercises limit / ordering /
    // pagination on the IndexMatch.Query path.
    object Tracks : Table("tracks") {
        val trackId = varchar("trackId").partitionKey()
        val artistType = varchar("artistType")
        val position = varchar("position")
        val title = varchar("title")
        val blob = varchar("blob")

        val byArtist = gsi("byArtist") {
            partitionKey(artistType)
            sortKey(position)
        }
    }

    private suspend fun createOrdersTable() {
        database.client.createTable(
            CreateTableRequest(
                tableName = Orders.tableName,
                keySchema = listOf(
                    KeySchemaElement(Orders.customerId.name, KeyType.Hash),
                    KeySchemaElement(Orders.orderId.name, KeyType.Range),
                ),
                attributeDefinitions = listOf(
                    AttributeDefinition(Orders.customerId.name, ScalarAttributeType.S),
                    AttributeDefinition(Orders.orderId.name, ScalarAttributeType.S),
                ),
                billingMode = BillingMode.PayPerRequest,
            ),
        )
    }

    private suspend fun createUsersTableWithGsi() {
        database.client.createTable(
            CreateTableRequest(
                tableName = Users.tableName,
                keySchema = listOf(KeySchemaElement(Users.id.name, KeyType.Hash)),
                attributeDefinitions = listOf(
                    AttributeDefinition(Users.id.name, ScalarAttributeType.S),
                    AttributeDefinition(Users.email.name, ScalarAttributeType.S),
                ),
                globalSecondaryIndexes = listOf(
                    GlobalSecondaryIndex(
                        indexName = "byEmail",
                        keySchema = listOf(KeySchemaElement(Users.email.name, KeyType.Hash)),
                        projection = Projection(ProjectionType.All),
                    ),
                ),
                billingMode = BillingMode.PayPerRequest,
            ),
        )
    }

    private suspend fun createTracksTableWithGsi() {
        database.client.createTable(
            CreateTableRequest(
                tableName = Tracks.tableName,
                keySchema = listOf(KeySchemaElement(Tracks.trackId.name, KeyType.Hash)),
                attributeDefinitions = listOf(
                    AttributeDefinition(Tracks.trackId.name, ScalarAttributeType.S),
                    AttributeDefinition(Tracks.artistType.name, ScalarAttributeType.S),
                    AttributeDefinition(Tracks.position.name, ScalarAttributeType.S),
                ),
                globalSecondaryIndexes = listOf(
                    GlobalSecondaryIndex(
                        indexName = "byArtist",
                        keySchema = listOf(
                            KeySchemaElement(Tracks.artistType.name, KeyType.Hash),
                            KeySchemaElement(Tracks.position.name, KeyType.Range),
                        ),
                        projection = Projection(ProjectionType.All),
                    ),
                ),
                billingMode = BillingMode.PayPerRequest,
            ),
        )
    }

    private suspend fun insertTrack(artist: String, pos: String, blobBytes: Int = 0) {
        Tracks.insert(database) {
            it[trackId] = "$artist#$pos"
            it[artistType] = artist
            it[position] = pos
            it[title] = "Track $pos"
            it[blob] = if (blobBytes > 0) "x".repeat(blobBytes) else ""
        }
    }

    private suspend fun createEventsTable() {
        database.client.createTable(
            CreateTableRequest(
                tableName = Events.tableName,
                keySchema = listOf(
                    KeySchemaElement(Events.pk.name, KeyType.Hash),
                    KeySchemaElement(Events.sk.name, KeyType.Range),
                ),
                attributeDefinitions = listOf(
                    AttributeDefinition(Events.pk.name, ScalarAttributeType.S),
                    AttributeDefinition(Events.sk.name, ScalarAttributeType.N),
                ),
                billingMode = BillingMode.PayPerRequest,
            ),
        )
    }

    @Test
    fun `select with full key uses GetItem`() = runTest {
        createOrdersTable()

        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#001"
            it[amount] = 100
        }

        val results = Orders.select(database) {
            (Orders.customerId eq "cust#1") and (Orders.orderId eq "order#001")
        }.toList()

        results.shouldHaveSize(1)
        results[0][Orders.amount].shouldBeEqualTo(100)
    }

    @Test
    fun `select with partition key only uses Query`() = runTest {
        createOrdersTable()

        // Insert multiple orders for same customer
        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#001"
            it[amount] = 100
        }
        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#002"
            it[amount] = 200
        }
        Orders.insert(database) {
            it[customerId] = "cust#2"
            it[orderId] = "order#003"
            it[amount] = 300
        }

        val results = Orders.select(database) {
            Orders.customerId eq "cust#1"
        }.toList()

        results.shouldHaveSize(2)
    }

    @Test
    fun `select with sort key beginsWith`() = runTest {
        createOrdersTable()

        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "2023#001"
            it[amount] = 100
        }
        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "2023#002"
            it[amount] = 200
        }
        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "2024#001"
            it[amount] = 300
        }

        val results = Orders.select(database) {
            (Orders.customerId eq "cust#1") and (Orders.orderId beginsWith "2023#")
        }.toList()

        results.shouldHaveSize(2)
        results.sumOf { it[Orders.amount] }.shouldBeEqualTo(300)
    }

    @Test
    fun `select with sort key comparison operators`() = runTest {
        createEventsTable()

        // Insert events at different timestamps
        Events.insert(database) {
            it[pk] = "stream#1"
            it[sk] = 1000L
            it[type] = "created"
        }
        Events.insert(database) {
            it[pk] = "stream#1"
            it[sk] = 2000L
            it[type] = "updated"
        }
        Events.insert(database) {
            it[pk] = "stream#1"
            it[sk] = 3000L
            it[type] = "deleted"
        }

        // Greater than
        val gtResults = Events.select(database) {
            (Events.pk eq "stream#1") and (Events.sk gt 1500L)
        }.toList()
        gtResults.shouldHaveSize(2)

        // Less than or equal
        val leResults = Events.select(database) {
            (Events.pk eq "stream#1") and (Events.sk le 2000L)
        }.toList()
        leResults.shouldHaveSize(2)

        // Between
        val betweenResults = Events.select(database) {
            (Events.pk eq "stream#1") and Events.sk.between(1500L, 2500L)
        }.toList()
        betweenResults.shouldHaveSize(1)
        betweenResults[0][Events.type].shouldBeEqualTo("updated")
    }

    @Test
    fun `select on GSI`() = runTest {
        createUsersTableWithGsi()

        Users.insert(database) {
            it[id] = "user#1"
            it[name] = "Alice"
            it[email] = "alice@example.com"
        }
        Users.insert(database) {
            it[id] = "user#2"
            it[name] = "Bob"
            it[email] = "bob@example.com"
        }

        // Query by email (uses GSI automatically)
        val results = Users.select(database) {
            Users.email eq "alice@example.com"
        }.toList()

        results.shouldHaveSize(1)
        results[0][Users.name].shouldBeEqualTo("Alice")
    }

    @Test
    fun `select without index throws NoIndexMatchException`() = runTest {
        createUsersTableWithGsi()

        Users.insert(database) {
            it[id] = "user#1"
            it[name] = "Alice"
            it[email] = "alice@example.com"
        }

        // Query by name (no index) should throw
        assertFailsWith<NoIndexMatchException> {
            Users.select(database) {
                Users.name eq "Alice"
            }.toList()
        }
    }

    @Test
    fun `scan returns all items`() = runTest {
        createOrdersTable()

        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#001"
            it[amount] = 100
        }
        Orders.insert(database) {
            it[customerId] = "cust#2"
            it[orderId] = "order#002"
            it[amount] = 200
        }

        val results = Orders.scan(database).toList()
        results.shouldHaveSize(2)
    }

    @Test
    fun `scan with filter`() = runTest {
        createOrdersTable()

        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#001"
            it[amount] = 100
            it[status] = "pending"
        }
        Orders.insert(database) {
            it[customerId] = "cust#2"
            it[orderId] = "order#002"
            it[amount] = 200
            it[status] = "completed"
        }

        val results = Orders.scan(database) {
            Orders.status eq "pending"
        }.toList()

        results.shouldHaveSize(1)
        results[0][Orders.amount].shouldBeEqualTo(100)
    }

    @Test
    fun `selectAll batch gets multiple items`() = runTest {
        createOrdersTable()

        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#001"
            it[amount] = 100
        }
        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#002"
            it[amount] = 200
        }
        Orders.insert(database) {
            it[customerId] = "cust#2"
            it[orderId] = "order#003"
            it[amount] = 300
        }

        @Suppress("DEPRECATION")
        val results = Orders.selectAll(
            database,
            listOf(
                "cust#1" to "order#001",
                "cust#2" to "order#003"
            )
        ).toList()

        results.shouldHaveSize(2)
        results.sumOf { it[Orders.amount] }.shouldBeEqualTo(400)
    }

    // =========================================================================
    // New Query API tests
    // =========================================================================

    @Test
    fun `selectAll with where returns Query`() = runTest {
        createOrdersTable()

        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#001"
            it[amount] = 100
        }

        val results = Orders.selectAll(database)
            .where { (Orders.customerId eq "cust#1") and (Orders.orderId eq "order#001") }
            .toList()

        results.shouldHaveSize(1)
        results[0][Orders.amount].shouldBeEqualTo(100)
    }

    @Test
    fun `select with specific columns returns projected results`() = runTest {
        createOrdersTable()

        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#001"
            it[amount] = 100
            it[status] = "pending"
        }

        val results = Orders.select(database, Orders.customerId, Orders.amount)
            .where { (Orders.customerId eq "cust#1") and (Orders.orderId eq "order#001") }
            .toList()

        results.shouldHaveSize(1)
        results[0][Orders.customerId].shouldBeEqualTo("cust#1")
        results[0][Orders.amount].shouldBeEqualTo(100)
        // Note: status won't be in the result since we only projected customerId and amount
    }

    @Test
    fun `Query firstOrNull returns first result`() = runTest {
        createOrdersTable()

        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#001"
            it[amount] = 100
        }

        val result = Orders.selectAll(database)
            .where { Orders.customerId eq "cust#1" }
            .firstOrNull()

        result.shouldBeEqualTo(result)
        result!![Orders.amount].shouldBeEqualTo(100)
    }

    @Test
    fun `Query firstOrNull returns null when no results`() = runTest {
        createOrdersTable()

        val result = Orders.selectAll(database)
            .where { Orders.customerId eq "nonexistent" }
            .firstOrNull()

        result.shouldBeEqualTo(null)
    }

    @Test
    fun `Query single returns exactly one result`() = runTest {
        createOrdersTable()

        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#001"
            it[amount] = 100
        }

        val result = Orders.selectAll(database)
            .where { (Orders.customerId eq "cust#1") and (Orders.orderId eq "order#001") }
            .single()

        result[Orders.amount].shouldBeEqualTo(100)
    }

    @Test
    fun `Query single throws when no results`() = runTest {
        createOrdersTable()

        assertFailsWith<NoSuchElementException> {
            Orders.selectAll(database)
                .where { Orders.customerId eq "nonexistent" }
                .single()
        }
    }

    @Test
    fun `Query single throws when multiple results`() = runTest {
        createOrdersTable()

        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#001"
            it[amount] = 100
        }
        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#002"
            it[amount] = 200
        }

        assertFailsWith<IllegalArgumentException> {
            Orders.selectAll(database)
                .where { Orders.customerId eq "cust#1" }
                .single()
        }
    }

    @Test
    fun `Query singleOrNull returns null when no results`() = runTest {
        createOrdersTable()

        val result = Orders.selectAll(database)
            .where { Orders.customerId eq "nonexistent" }
            .singleOrNull()

        result.shouldBeEqualTo(null)
    }

    @Test
    fun `scan with new Query API`() = runTest {
        createOrdersTable()

        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#001"
            it[amount] = 100
            it[status] = "pending"
        }
        Orders.insert(database) {
            it[customerId] = "cust#2"
            it[orderId] = "order#002"
            it[amount] = 200
            it[status] = "completed"
        }

        val results = Orders.scan(database)
            .where { Orders.status eq "pending" }
            .toList()

        results.shouldHaveSize(1)
        results[0][Orders.amount].shouldBeEqualTo(100)
    }

    @Test
    fun `keys function for batch get`() = runTest {
        createOrdersTable()

        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#001"
            it[amount] = 100
        }
        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#002"
            it[amount] = 200
        }
        Orders.insert(database) {
            it[customerId] = "cust#2"
            it[orderId] = "order#003"
            it[amount] = 300
        }

        val results = Orders.selectAll(database)
            .where {
                keys(listOf(
                    "cust#1" to "order#001",
                    "cust#2" to "order#003"
                ))
            }
            .toList()

        results.shouldHaveSize(2)
        results.sumOf { it[Orders.amount] }.shouldBeEqualTo(400)
    }

    @Test
    fun `keys with projection selects specific columns`() = runTest {
        createOrdersTable()

        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#001"
            it[amount] = 100
            it[status] = "pending"
        }

        val results = Orders.select(database, Orders.customerId, Orders.amount)
            .where { keys(listOf("cust#1" to "order#001")) }
            .toList()

        results.shouldHaveSize(1)
        results[0][Orders.customerId].shouldBeEqualTo("cust#1")
        results[0][Orders.amount].shouldBeEqualTo(100)
    }

    @Test
    fun `BoundTable select operations`() = runTest {
        createOrdersTable()

        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#001"
            it[amount] = 100
        }

        val orders = database.bind(Orders)

        val result = orders.selectAll()
            .where { (Orders.customerId eq "cust#1") and (Orders.orderId eq "order#001") }
            .firstOrNull()

        result!![Orders.amount].shouldBeEqualTo(100)
    }

    @Test
    fun `BoundTable scan operations`() = runTest {
        createOrdersTable()

        Orders.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#001"
            it[amount] = 100
            it[status] = "pending"
        }

        val orders = database.bind(Orders)

        val results = orders.scan()
            .where { Orders.status eq "pending" }
            .toList()

        results.shouldHaveSize(1)
    }

    // =========================================================================
    // limit / ordering / pagination
    // =========================================================================

    @Test
    fun `GSI query with limit returns a bounded page and a resumable token`() = runTest {
        createTracksTableWithGsi()

        (1..5).forEach { insertTrack("artist#1", "%02d".format(it)) }

        // First bounded page.
        val first = Tracks.selectAll(database)
            .where { Tracks.artistType eq "artist#1" }
            .limit(2)
            .page()

        first.rows.shouldHaveSize(2)
        first.rows.map { it[Tracks.position] }.shouldBeEqualTo(listOf("01", "02"))
        first.nextToken.shouldNotBeNull()

        // Resume from the token: walk the rest of the pages.
        val collected = first.rows.map { it[Tracks.position] }.toMutableList()
        var token = first.nextToken
        while (token != null) {
            val next = Tracks.selectAll(database)
                .where { Tracks.artistType eq "artist#1" }
                .limit(2)
                .startAfter(token)
                .page()
            collected += next.rows.map { it[Tracks.position] }
            token = next.nextToken
        }

        // Round-trips through every page, no rows dropped or duplicated.
        collected.shouldBeEqualTo(listOf("01", "02", "03", "04", "05"))
    }

    @Test
    fun `GSI query can be returned in reverse sort order`() = runTest {
        createTracksTableWithGsi()

        (1..4).forEach { insertTrack("artist#1", "%02d".format(it)) }

        val ascending = Tracks.selectAll(database)
            .where { Tracks.artistType eq "artist#1" }
            .toList()
            .map { it[Tracks.position] }
        ascending.shouldBeEqualTo(listOf("01", "02", "03", "04"))

        val descending = Tracks.selectAll(database)
            .where { Tracks.artistType eq "artist#1" }
            .orderBy(descending = true)
            .toList()
            .map { it[Tracks.position] }
        descending.shouldBeEqualTo(listOf("04", "03", "02", "01"))
    }

    @Test
    fun `unbounded GSI query returns all rows across page boundaries`() = runTest {
        createTracksTableWithGsi()

        // ~180 KB per row over 8 rows (~1.4 MB) forces DynamoDB's 1 MB query page
        // boundary, so a single query response cannot hold them all.
        (1..8).forEach { insertTrack("artist#1", "%02d".format(it), blobBytes = 180_000) }

        val positions = Tracks.selectAll(database)
            .where { Tracks.artistType eq "artist#1" }
            .toList()
            .map { it[Tracks.position] }

        // No silent first-page truncation: asFlow follows lastEvaluatedKey.
        positions.shouldHaveSize(8)
        positions.shouldBeEqualTo((1..8).map { "%02d".format(it) })
    }

    @Test
    fun `table-PK query supports limit and cursor round-trip`() = runTest {
        createOrdersTable()

        (1..5).forEach { i ->
            Orders.insert(database) {
                it[customerId] = "cust#1"
                it[orderId] = "order#%02d".format(i)
                it[amount] = i * 10
            }
        }

        val first = Orders.selectAll(database)
            .where { Orders.customerId eq "cust#1" }
            .limit(2)
            .page()

        first.rows.shouldHaveSize(2)
        first.nextToken.shouldNotBeNull()

        val collected = first.rows.map { it[Orders.orderId] }.toMutableList()
        var token = first.nextToken
        while (token != null) {
            val next = Orders.selectAll(database)
                .where { Orders.customerId eq "cust#1" }
                .limit(2)
                .startAfter(token)
                .page()
            collected += next.rows.map { it[Orders.orderId] }
            token = next.nextToken
        }

        collected.shouldBeEqualTo((1..5).map { "order#%02d".format(it) })
    }

    @Test
    fun `limit caps total rows emitted by asFlow across pages`() = runTest {
        createTracksTableWithGsi()

        (1..10).forEach { insertTrack("artist#1", "%02d".format(it)) }

        val positions = Tracks.selectAll(database)
            .where { Tracks.artistType eq "artist#1" }
            .limit(3)
            .toList()
            .map { it[Tracks.position] }

        positions.shouldBeEqualTo(listOf("01", "02", "03"))
    }
}
