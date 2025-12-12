package com.steamstreet.dynamokt.exposed

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.createTable
import aws.sdk.kotlin.services.dynamodb.model.*
import aws.smithy.kotlin.runtime.net.url.Url
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.utility.DockerImageName
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

abstract class ExposedTestBase {
    @Container
    val localstack: LocalStackContainer = LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
        .withServices(LocalStackContainer.Service.DYNAMODB)
        .withEnv("DEBUG", "1")
        .withEnv("PERSISTENCE", "1")

    lateinit var database: Database

    @BeforeTest
    fun setup() {
        val endpoint = localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString()
        database = Database.connect(endpoint = endpoint) {
            region = "us-east-1"
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = "DummyKey"
                secretAccessKey = "DummySecret"
            }
        }
    }

    @AfterTest
    fun teardown() {
        database.client.close()
    }

    /**
     * Create a DynamoDB table for the given Table definition
     */
    suspend fun createTable(table: Table, readCapacity: Long = 5, writeCapacity: Long = 5) {
        val pkColumn = table.partitionKey ?: error("Table must have partition key")
        val skColumn = table.sortKey

        database.client.createTable {
            tableName = table.tableName

            keySchema = buildList {
                add(KeySchemaElement {
                    attributeName = pkColumn.name
                    keyType = KeyType.Hash
                })
                if (skColumn != null) {
                    add(KeySchemaElement {
                        attributeName = skColumn.name
                        keyType = KeyType.Range
                    })
                }
            }

            attributeDefinitions = buildList {
                add(AttributeDefinition {
                    attributeName = pkColumn.name
                    attributeType = ScalarAttributeType.S
                })
                if (skColumn != null) {
                    add(AttributeDefinition {
                        attributeName = skColumn.name
                        attributeType = ScalarAttributeType.S
                    })
                }
            }

            billingMode = BillingMode.PayPerRequest
        }
    }
}
