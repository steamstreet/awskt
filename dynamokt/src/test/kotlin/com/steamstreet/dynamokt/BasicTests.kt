package com.steamstreet.dynamokt

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.createTable
import aws.sdk.kotlin.services.dynamodb.model.*
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test


@Testcontainers
class BasicTests {
    @Container
    val dynamo: GenericContainer<*> = GenericContainer("amazon/dynamodb-local:2.2.0")
        .withExposedPorts(8000)

    @BeforeTest
    fun initDynamo() {
        DynamoKt.defaultClientBuilder = DynamoDbClient.builder().apply {
            config.apply {
                endpointUrl = Url.parse("http://localhost:${dynamo.firstMappedPort}")
                region = "us-east-1"
                credentialsProvider = StaticCredentialsProvider {
                    accessKeyId = "DummyKey"
                    secretAccessKey = "DummySecret"

                }
            }
        }
    }

    @AfterTest
    fun destroy() {
    }

    @Test
    fun testBasics() = runTest {
        val db = createTable().session()

        db.put("person", "123") {
            set("name", "Jon")
        }

        db.get("person", "123").getString("name").shouldBeEqualTo("Jon")
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

    class TestMapping(override val entity: Item) : ItemContainer {
        val name: String by stringAttribute()
    }

    suspend fun createTable(tableName: String = "Table"): DynamoKt {
        val dynamoClient = DynamoKt.defaultClientBuilder.build()
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