package com.steamstreet.dynamokt.exposed

import aws.sdk.kotlin.services.dynamodb.createTable
import aws.sdk.kotlin.services.dynamodb.model.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
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

    private suspend fun createOrdersTable() {
        database.client.createTable {
            tableName = Orders.tableName
            keySchema = listOf(
                KeySchemaElement {
                    attributeName = Orders.customerId.name
                    keyType = KeyType.Hash
                },
                KeySchemaElement {
                    attributeName = Orders.orderId.name
                    keyType = KeyType.Range
                }
            )
            attributeDefinitions = listOf(
                AttributeDefinition {
                    attributeName = Orders.customerId.name
                    attributeType = ScalarAttributeType.S
                },
                AttributeDefinition {
                    attributeName = Orders.orderId.name
                    attributeType = ScalarAttributeType.S
                }
            )
            billingMode = BillingMode.PayPerRequest
        }
    }

    private suspend fun createUsersTableWithGsi() {
        database.client.createTable {
            tableName = Users.tableName
            keySchema = listOf(
                KeySchemaElement {
                    attributeName = Users.id.name
                    keyType = KeyType.Hash
                }
            )
            attributeDefinitions = listOf(
                AttributeDefinition {
                    attributeName = Users.id.name
                    attributeType = ScalarAttributeType.S
                },
                AttributeDefinition {
                    attributeName = Users.email.name
                    attributeType = ScalarAttributeType.S
                }
            )
            globalSecondaryIndexes = listOf(
                GlobalSecondaryIndex {
                    indexName = "byEmail"
                    keySchema = listOf(
                        KeySchemaElement {
                            attributeName = Users.email.name
                            keyType = KeyType.Hash
                        }
                    )
                    projection = Projection {
                        projectionType = aws.sdk.kotlin.services.dynamodb.model.ProjectionType.All
                    }
                }
            )
            billingMode = BillingMode.PayPerRequest
        }
    }

    private suspend fun createEventsTable() {
        database.client.createTable {
            tableName = Events.tableName
            keySchema = listOf(
                KeySchemaElement {
                    attributeName = Events.pk.name
                    keyType = KeyType.Hash
                },
                KeySchemaElement {
                    attributeName = Events.sk.name
                    keyType = KeyType.Range
                }
            )
            attributeDefinitions = listOf(
                AttributeDefinition {
                    attributeName = Events.pk.name
                    attributeType = ScalarAttributeType.S
                },
                AttributeDefinition {
                    attributeName = Events.sk.name
                    attributeType = ScalarAttributeType.N
                }
            )
            billingMode = BillingMode.PayPerRequest
        }
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
}
