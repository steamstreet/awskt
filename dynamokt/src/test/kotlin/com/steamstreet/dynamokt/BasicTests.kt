package com.steamstreet.dynamokt

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.createTable
import aws.sdk.kotlin.services.dynamodb.model.*
import aws.smithy.kotlin.runtime.net.Url
import com.amazonaws.services.dynamodbv2.local.main.ServerRunner
import com.amazonaws.services.dynamodbv2.local.server.DynamoDBProxyServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BasicTests {
    lateinit var server: DynamoDBProxyServer

    @BeforeTest
    fun initDynamo() {
        val ports = 9945..9965
        val (port: Int, server: DynamoDBProxyServer) =
            ports.asSequence().mapNotNull {
                try {
                    it to ServerRunner.createServerFromCommandLineArgs(
                        arrayOf("-inMemory", "-port", it.toString())
                    )
                } catch (e: Throwable) {
                    null
                }
            }.first()

        this.server = server
        server.start()

        DynamoKt.defaultClientBuilder = DynamoDbClient.builder().apply {
            config.apply {
                endpointUrl = Url.parse("http://localhost:$port")
                region = "us-east-1"
                credentialsProvider = StaticCredentialsProvider {
                    accessKeyId = "dummy-key"
                    secretAccessKey = "dummy-secret"

                }
            }
        }
    }

    @AfterTest
    fun stopDynamo() {
        try {
            this.server.stop()
        } catch (e: Throwable) {
            // ignore
        }
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