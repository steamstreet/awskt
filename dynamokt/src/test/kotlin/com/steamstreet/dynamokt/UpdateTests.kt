package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.createTable
import aws.sdk.kotlin.services.dynamodb.model.*
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test

@Testcontainers
class UpdateTests : DynamoKtTests() {
    @Test
    fun testUpdateListItemWithSimpleValue() = runTest {
        val db = createTable().session()

        // Create an item with a list
        db.put("person", "123") {
            set("tags", AttributeValue.L(
                listOf(
                    AttributeValue.S("tag1"),
                    AttributeValue.S("tag2"),
                    AttributeValue.S("tag3")
                )
            ))
        }

        // Update the second item in the list
        db.update("person", "123") {
            updateListItem("tags", 1, value = AttributeValue.S("updated-tag"))
        }

        val result = db.get("person", "123")
        val tags = result.get("tags")!!.asL()
        tags[0].asS().shouldBeEqualTo("tag1")
        tags[1].asS().shouldBeEqualTo("updated-tag")
        tags[2].asS().shouldBeEqualTo("tag3")
    }

    @Test
    fun testUpdateListItemWithNestedProperty() = runTest {
        val db = createTable().session()

        // Create an item with a list of maps
        db.put("person", "123") {
            set("items", AttributeValue.L(
                listOf(
                    AttributeValue.M(mapOf(
                        "name" to AttributeValue.S("Item 1"),
                        "status" to AttributeValue.S("active")
                    )),
                    AttributeValue.M(mapOf(
                        "name" to AttributeValue.S("Item 2"),
                        "status" to AttributeValue.S("pending")
                    ))
                )
            ))
        }

        // Update the status of the second item
        db.update("person", "123") {
            updateListItem("items", 1, nestedKey = "status", value = AttributeValue.S("completed"))
        }

        val result = db.get("person", "123")
        val items = result.get("items")!!.asL()
        items[0].asM()["status"]!!.asS().shouldBeEqualTo("active")
        items[1].asM()["status"]!!.asS().shouldBeEqualTo("completed")
        items[1].asM()["name"]!!.asS().shouldBeEqualTo("Item 2") // name unchanged
    }

    @Test
    fun testUpdateListItemUsingSetOperator() = runTest {
        val db = createTable().session()

        // Create an item with a list of maps
        db.put("person", "123") {
            set("items", AttributeValue.L(
                listOf(
                    AttributeValue.M(mapOf(
                        "id" to AttributeValue.N("1"),
                        "value" to AttributeValue.N("100")
                    )),
                    AttributeValue.M(mapOf(
                        "id" to AttributeValue.N("2"),
                        "value" to AttributeValue.N("200")
                    ))
                )
            ))
        }

        // Update using array index syntax with set operator
        db.update("person", "123") {
            set("items[0].value", AttributeValue.N("150"))
        }

        val result = db.get("person", "123")
        val items = result.get("items")!!.asL()
        items[0].asM()["value"]!!.asN().shouldBeEqualTo("150")
        items[1].asM()["value"]!!.asN().shouldBeEqualTo("200") // unchanged
    }

    @Test
    fun testUpdateDeeplyNestedListItem() = runTest {
        val db = createTable().session()

        // Create an item with nested lists
        db.put("person", "123") {
            set("data", AttributeValue.M(mapOf(
                "items" to AttributeValue.L(
                    listOf(
                        AttributeValue.M(mapOf(
                            "tags" to AttributeValue.L(
                                listOf(
                                    AttributeValue.S("a"),
                                    AttributeValue.S("b")
                                )
                            )
                        ))
                    )
                )
            )))
        }

        // Update deeply nested list item
        db.update("person", "123") {
            set("data.items[0].tags[1]", AttributeValue.S("updated"))
        }

        val result = db.get("person", "123")
        val tags = result.get("data")!!.asM()["items"]!!.asL()[0].asM()["tags"]!!.asL()
        tags[0].asS().shouldBeEqualTo("a")
        tags[1].asS().shouldBeEqualTo("updated")
    }

    @Test
    fun testRemoveListItemProperty() = runTest {
        val db = createTable().session()

        // Create an item with a list of maps
        db.put("person", "123") {
            set("items", AttributeValue.L(
                listOf(
                    AttributeValue.M(mapOf(
                        "name" to AttributeValue.S("Item 1"),
                        "optional" to AttributeValue.S("value1")
                    )),
                    AttributeValue.M(mapOf(
                        "name" to AttributeValue.S("Item 2"),
                        "optional" to AttributeValue.S("value2")
                    ))
                )
            ))
        }

        // Remove the optional property from the first item
        db.update("person", "123") {
            updateListItem("items", 0, nestedKey = "optional", value = null)
        }

        val result = db.get("person", "123")
        val items = result.get("items")!!.asL()
        items[0].asM()["optional"].shouldBeNull()
        items[0].asM()["name"]!!.asS().shouldBeEqualTo("Item 1") // name unchanged
        items[1].asM()["optional"]!!.asS().shouldBeEqualTo("value2") // other item unchanged
    }

    @Test
    fun testUpdateMultipleListItems() = runTest {
        val db = createTable().session()

        // Create an item with a list
        db.put("person", "123") {
            set("scores", AttributeValue.L(
                listOf(
                    AttributeValue.N("10"),
                    AttributeValue.N("20"),
                    AttributeValue.N("30")
                )
            ))
        }

        // Update multiple items in the same operation
        db.update("person", "123") {
            updateListItem("scores", 0, value = AttributeValue.N("15"))
            updateListItem("scores", 2, value = AttributeValue.N("35"))
        }

        val result = db.get("person", "123")
        val scores = result.get("scores")!!.asL()
        scores[0].asN().shouldBeEqualTo("15")
        scores[1].asN().shouldBeEqualTo("20") // unchanged
        scores[2].asN().shouldBeEqualTo("35")
    }

    @Test
    fun testNestedPropertyWithDotNotation() = runTest {
        val db = createTable().session()

        // Create an item with nested map inside a list
        db.put("person", "123") {
            set("users", AttributeValue.L(
                listOf(
                    AttributeValue.M(mapOf(
                        "profile" to AttributeValue.M(mapOf(
                            "name" to AttributeValue.S("John"),
                            "age" to AttributeValue.N("30")
                        ))
                    ))
                )
            ))
        }

        // Update nested property using dot notation
        db.update("person", "123") {
            updateListItem("users", 0, nestedKey = "profile.age", value = AttributeValue.N("31"))
        }

        val result = db.get("person", "123")
        val users = result.get("users")!!.asL()
        val profile = users[0].asM()["profile"]!!.asM()
        profile["age"]!!.asN().shouldBeEqualTo("31")
        profile["name"]!!.asS().shouldBeEqualTo("John") // unchanged
    }

    @Test
    fun testUpdateWithNestedArrayIndicesInPath() = runTest {
        val db = createTable().session()

        // Create an item with a list containing maps with lists
        db.put("person", "123") {
            set("data", AttributeValue.L(
                listOf(
                    AttributeValue.M(mapOf(
                        "values" to AttributeValue.L(
                            listOf(
                                AttributeValue.N("1"),
                                AttributeValue.N("2")
                            )
                        )
                    ))
                )
            ))
        }

        // Update using nested array indices
        db.update("person", "123") {
            updateListItem("data", 0, nestedKey = "values[1]", value = AttributeValue.N("99"))
        }

        val result = db.get("person", "123")
        val data = result.get("data")!!.asL()
        val values = data[0].asM()["values"]!!.asL()
        values[0].asN().shouldBeEqualTo("1")
        values[1].asN().shouldBeEqualTo("99")
    }

    private suspend fun createTable(tableName: String = "Table"): DynamoKt {
        val dynamoClient = DynamoKt.defaultClientBuilder(null)
        dynamoClient.createTable {
            this.tableName = tableName
            keySchema = listOf(
                KeySchemaElement {
                    attributeName = "pk"
                    keyType = KeyType.Hash
                },
                KeySchemaElement {
                    attributeName = "sk"
                    keyType = KeyType.Range
                }
            )
            attributeDefinitions = listOf(
                AttributeDefinition {
                    attributeName = "pk"
                    attributeType = ScalarAttributeType.S
                },
                AttributeDefinition {
                    attributeName = "sk"
                    attributeType = ScalarAttributeType.S
                }
            )
            billingMode = BillingMode.PayPerRequest
        }
        return DynamoKt(tableName)
    }
}