package com.steamstreet.aws.test

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodbstreams.DynamoDbStreamsClient
import aws.smithy.kotlin.runtime.net.url.Url
import com.steamstreet.dynamokt.DynamoKt
import com.steamstreet.awskt.dynamodb.sdk.SdkBackedDynamoDb
import com.steamstreet.dynamokt.DynamoStreamEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@Testcontainers
class DynamoStreamTest {
    companion object {
        @JvmStatic
        @Container
        val localstack: LocalStackContainer = LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
            .withServices(
                LocalStackContainer.Service.DYNAMODB,
                LocalStackContainer.Service.DYNAMODB_STREAMS,
            ).withReuse(true)
    }

    lateinit var ddb: DynamoDbClient
    lateinit var streamClient: DynamoDbStreamsClient
    private lateinit var table: String
    var events = mutableListOf<DynamoStreamEvent>()
    lateinit var awsLocal: AWSLocal

    @BeforeTest
    fun initDdb() = runBlocking {
        ddb = DynamoDbClient {
            endpointUrl = Url.parse(
                localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString()
            )
            region = localstack.region
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = localstack.accessKey
                secretAccessKey = localstack.secretKey
            }
        }

        streamClient = DynamoDbStreamsClient {
            endpointUrl = Url.parse(
                localstack.getEndpointOverride(
                    LocalStackContainer.Service.DYNAMODB_STREAMS
                ).toString()
            )
            region = localstack.region
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = localstack.accessKey
                secretAccessKey = localstack.secretKey
            }
        }

        table = ddb.defaultTable("MyTable") {
            keySchema = KeySchema("pk", "sk")
            attributeDefinitions = listOf(
                AttributeDefinition("pk"),
                AttributeDefinition("sk")
            )
        }

        awsLocal = AWSLocal()

        awsLocal.addService(DynamoStreamRunner(table, streamClient, { event ->
            events += event
        }))

        awsLocal.start()
    }

    @AfterTest
    fun after() {
        runBlocking {
            awsLocal.stop()
        }
    }

    @Test
    fun testStreamReading() = runTest {
        val dynamoKt = DynamoKt(table, builder = { SdkBackedDynamoDb(ddb) }).session()

        dynamoKt.put("person", "123") {
            set("name", "Jon")
        }
        waitForEvents(1)
    }

    @Test
    fun testStreamReading2() = runTest {
        val dynamoKt = DynamoKt(table, builder = { SdkBackedDynamoDb(ddb) }).session()

        dynamoKt.put("person", "123") {
            set("name", "Jon")
        }
        dynamoKt.put("person", "456") {
            set("name", "Jon")
        }
        waitForEvents(2)
    }

    private suspend fun waitForEvents(expectedCount: Int, timeout: Long = 2000): List<DynamoStreamEvent> {
        withContext(kotlinx.coroutines.Dispatchers.Default) {
            withTimeout(timeout) {
                while (events.size < expectedCount) {
                    delay(50)
                }
            }
        }
        return events.take(expectedCount)
    }

}