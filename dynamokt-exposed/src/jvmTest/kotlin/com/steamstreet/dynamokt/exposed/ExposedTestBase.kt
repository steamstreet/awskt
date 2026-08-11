package com.steamstreet.dynamokt.exposed

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import com.steamstreet.awskt.core.StaticCredentialsProvider as AwsKtStaticCredentialsProvider
import com.steamstreet.awskt.dynamodb.AttributeDefinition
import com.steamstreet.awskt.dynamodb.DynamoDb
import com.steamstreet.awskt.dynamodb.BillingMode
import com.steamstreet.awskt.dynamodb.CreateTableRequest
import com.steamstreet.awskt.dynamodb.KeySchemaElement
import com.steamstreet.awskt.dynamodb.KeyType
import com.steamstreet.awskt.dynamodb.ScalarAttributeType
import com.steamstreet.awskt.dynamodb.sdk.SdkBackedDynamoDb
import com.steamstreet.awskt.signing.AwsCredentials
import aws.sdk.kotlin.services.dynamodb.createTable
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
        val endpoint = localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB)
        val client = DynamoDbClient {
            region = "us-east-1"
            endpointUrl = Url.parse(endpoint.toString())
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = "DummyKey"
                secretAccessKey = "DummySecret"
            }
        }
        // M5a runs on SdkBackedDynamoDb so these 72 tests validate the *type* swap against
        // unchanged AWS SDK behaviour; `-Dawskt.dynamodb.impl=native` runs the same suite on the
        // hand-written client, which is M5b.
        database = Database(
            if (System.getProperty("awskt.dynamodb.impl") == "native") {
                DynamoDb {
                    region = "us-east-1"
                    endpointUrl = endpoint.toString()
                    credentialsProvider =
                        AwsKtStaticCredentialsProvider(AwsCredentials("DummyKey", "DummySecret"))
                }
            } else {
                SdkBackedDynamoDb(client)
            },
        )
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

        database.client.createTable(
            CreateTableRequest(
                tableName = table.tableName,
                keySchema = buildList {
                    add(KeySchemaElement(pkColumn.name, KeyType.Hash))
                    if (skColumn != null) add(KeySchemaElement(skColumn.name, KeyType.Range))
                },
                attributeDefinitions = buildList {
                    add(AttributeDefinition(pkColumn.name, ScalarAttributeType.S))
                    if (skColumn != null) add(AttributeDefinition(skColumn.name, ScalarAttributeType.S))
                },
                billingMode = BillingMode.PayPerRequest,
            ),
        )
    }
}
