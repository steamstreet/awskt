package com.steamstreet.dynamokt

import com.steamstreet.awskt.dynamodb.AttributeDefinition
import com.steamstreet.awskt.dynamodb.BillingMode
import com.steamstreet.awskt.dynamodb.CreateTableRequest
import com.steamstreet.awskt.dynamodb.KeySchemaElement
import com.steamstreet.awskt.dynamodb.KeyType
import com.steamstreet.awskt.dynamodb.ScalarAttributeType

import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test


@Testcontainers
class BasicTests : DynamoKtTests() {
    @Test
    fun testBasics() = runTest {
        val db = createTable().session()

        db.put("person", "123") {
            set("name", "Jon")
        }

        db.get("person", "123").getString("name").shouldBeEqualTo("Jon")
    }

    @Test
    fun testExpressionBuilderNumericalComparisons() = runTest {
        val db = createTable().session()

        // Add test data with numerical values
        db.put("person", "1") {
            set("name", "Alice")
            set("age", 25)
            set("score", 85)
        }
        db.put("person", "2") {
            set("name", "Bob")
            set("age", 30)
            set("score", 92)
        }
        db.put("person", "3") {
            set("name", "Charlie")
            set("age", 35)
            set("score", 78)
        }

        // Test numerical comparison methods
        val builder = ExpressionBuilder()

        // Test greaterThan with Number
        builder.greaterThan("age", 28)
        builder.expressions.size.shouldBeEqualTo(1)

        // Test lessThan with Number
        builder.lessThan("score", 90)
        builder.expressions.size.shouldBeEqualTo(2)

        // Test greaterThanOrEqualTo
        builder.greaterThanOrEqualTo("age", 30)
        builder.expressions.size.shouldBeEqualTo(3)

        // Test lessThanOrEqualTo
        builder.lessThanOrEqualTo("score", 85)
        builder.expressions.size.shouldBeEqualTo(4)

        // Test FilterAttribute numerical methods
        builder.attribute("age").greaterThan(25)
        builder.expressions.size.shouldBeEqualTo(5)

        builder.attribute("score").lessThanOrEqualTo(100)
        builder.expressions.size.shouldBeEqualTo(6)

        // Test with Long values
        builder.greaterThan("age", 25L)
        builder.expressions.size.shouldBeEqualTo(7)

        // Test equalTo with Number
        builder.equalTo("age", 30)
        builder.expressions.size.shouldBeEqualTo(8)

        // Test FilterAttribute equalTo with Number
        builder.attribute("score").equalTo(85)
        builder.expressions.size.shouldBeEqualTo(9)

        // Test equalTo with Long
        builder.equalTo("score", 92L)
        builder.expressions.size.shouldBeEqualTo(10)
    }

    @Test
    fun testAdvancedExpressionBuilderFeatures() = runTest {
        val builder = ExpressionBuilder()

        // Test notEqualTo
        builder.notEqualTo("status", "inactive")
        builder.notEqualTo("count", 0)
        builder.expressions.size.shouldBeEqualTo(2)

        // Test between
        builder.between("age", 18, 65)
        builder.between("name", "A", "M")
        builder.expressions.size.shouldBeEqualTo(4)

        // Test contains
        builder.contains("tags", "important")
        builder.contains("scores", 100)
        builder.expressions.size.shouldBeEqualTo(6)

        // Test size operations
        builder.size("items").greaterThan(0)
        builder.size("tags").lessThanOrEqualTo(5)
        builder.size("comments").equalTo(3)
        builder.expressions.size.shouldBeEqualTo(9)

        // Test numeric IN operation
        builder.valueInNumbers("priorities", listOf(1, 2, 3))
        builder.expressions.size.shouldBeEqualTo(10)

        // Test attribute type checking
        builder.attributeType("data", AttributeType.MAP)
        builder.expressions.size.shouldBeEqualTo(11)

        // Test null/empty checks
        builder.isNull("deleted_at")
        builder.isNotNull("created_at")
        builder.isEmpty("errors")
        builder.isNotEmpty("tags")
        builder.expressions.size.shouldBeEqualTo(15)

        // Test FilterAttribute fluent API
        builder.attribute("category").notEqualTo("archived")
        builder.attribute("rating").between(3, 5)
        builder.attribute("description").contains("kotlin")
        builder.attribute("items").size().greaterThan(0)
        builder.attribute("priority").isOneOfNumbers(listOf(1, 2, 3))
        builder.attribute("metadata").hasType(AttributeType.MAP)
        builder.attribute("deleted_at").isNull()
        builder.attribute("tags").isNotEmpty()
        builder.expressions.size.shouldBeEqualTo(23)
    }

    @Test
    fun testOrExpressions() = runTest {
        val builder = ExpressionBuilder()

        // Simple OR with single conditions
        builder.equalTo("status", "active")
        builder.or {
            equalTo("status", "pending")
        }
        builder.expressions.size.shouldBeEqualTo(2)

        // OR with multiple conditions (should be grouped)
        builder.or {
            equalTo("priority", 1)
            lessThan("age", 18)
        }
        builder.expressions.size.shouldBeEqualTo(3)

        // Test that name and value maps are shared
        val initialNameMapSize = builder.nameMap.size
        val initialValueMapSize = builder.valueMap.size

        builder.or {
            greaterThan("score", 95)
            contains("tags", "urgent")
        }

        // Verify maps grew (shared state working)
        builder.nameMap.size.shouldBeEqualTo(initialNameMapSize + 2) // score, tags
        builder.valueMap.size.shouldBeEqualTo(initialValueMapSize + 2) // 95, "urgent"

        // Test complex OR with fluent API
        builder.or {
            attribute("category").isOneOf(listOf("A", "B"))
            attribute("rating").between(4, 5)
        }

        builder.expressions.size.shouldBeEqualTo(5)
    }


    @Test
    fun testObjectMapping() = runTest {
        val db = createTable().session()

        db.put("person", "123") {
            set("name", "Jon")
        }

        val mapping = db.get("person", "123", ::TestMapping)
        mapping.name.shouldBeEqualTo("Jon")
    }

    @Test
    fun testNestedDelete() = runTest {
        val db = createTable().session()

        db.put("person", "123") {
            set(
                "name", AttributeValue.M(
                    mapOf(
                        "first" to "Jon".attributeValue(),
                        "last" to "Nichols".attributeValue()
                    )
                )
            )
        }

        val added = db.get("person", "123")
        added.get("name")!!.asM().get("first")!!.asS().shouldBeEqualTo("Jon")

        db.update("person", "123") {
            delete("name.first")
        }

        db.get("person", "123").also { withoutFirst ->
            withoutFirst.get("name")!!.asM()["first"].shouldBeNull()
            withoutFirst.get("name")!!.asM()["last"]!!.asS().shouldBeEqualTo("Nichols")
        }
    }

    class TestMapping(override val entity: Item) : ItemContainer {
        val name: String by stringAttribute()
    }

    suspend fun createTable(tableName: String = "Table"): DynamoKt {
        val dynamoClient = DynamoKt.defaultClientBuilder(null)
        dynamoClient.createTable(
            CreateTableRequest(
                tableName = tableName,
                keySchema = listOf(
                    KeySchemaElement("pk", KeyType.Hash),
                    KeySchemaElement("sk", KeyType.Range),
                ),
                attributeDefinitions = listOf(
                    AttributeDefinition("pk", ScalarAttributeType.S),
                    AttributeDefinition("sk", ScalarAttributeType.S),
                ),
                billingMode = BillingMode.PayPerRequest,
            ),
        )
        return DynamoKt(tableName)
    }
}