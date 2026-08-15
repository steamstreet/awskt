package com.steamstreet.dynamokt.exposed

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import com.steamstreet.awskt.core.StaticCredentialsProvider as AwsKtStaticCredentialsProvider
import com.steamstreet.awskt.dynamodb.DynamoDb
import com.steamstreet.awskt.dynamodb.sdk.SdkBackedDynamoDb
import com.steamstreet.awskt.signing.AwsCredentials
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
        // M5b: these 72 tests run on the hand-written client by default.
        // `-Dawskt.dynamodb.impl=sdk` runs the same suite against unchanged AWS SDK behaviour, so a
        // failure can be attributed to the client implementation or the type swap in one command.
        database = Database(
            if (System.getProperty("awskt.dynamodb.impl") != "sdk") {
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
     * Create a DynamoDB table for the given Table definition.
     *
     * Goes through [SchemaUtils], the same path production callers use, rather than a second
     * hand-written mapping that can drift from it. For every table these suites create - string
     * keys, no secondary indices - the two produce the identical request.
     */
    suspend fun createTable(table: Table) {
        SchemaUtils.createTable(database, table)
    }
}
