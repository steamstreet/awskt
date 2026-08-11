package com.steamstreet.dynamokt

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.smithy.kotlin.runtime.net.url.Url
import com.steamstreet.awskt.core.StaticCredentialsProvider as AwsKtStaticCredentialsProvider
import com.steamstreet.awskt.dynamodb.DynamoDb
import com.steamstreet.awskt.dynamodb.sdk.SdkBackedDynamoDb
import com.steamstreet.awskt.signing.AwsCredentials
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.utility.DockerImageName
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

abstract class DynamoKtTests {
    @Container
    val localstack: LocalStackContainer = LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
        .withServices(
            LocalStackContainer.Service.DYNAMODB
        )
        .withEnv("DEBUG", "1")
        .withEnv("PERSISTENCE", "1")

    @BeforeTest
    fun initDynamo() {
        DynamoKt.defaultClientBuilder = { buildClient() }
    }

    /**
     * Which implementation the suite runs against.
     *
     * **M5b runs on the hand-written `DefaultDynamoDb` by default.** `-Dawskt.dynamodb.impl=sdk`
     * runs the identical suite against `SdkBackedDynamoDb`, whose behaviour is still, byte for
     * byte, the AWS SDK's — so a failure that appears under the default and disappears under `sdk`
     * is the hand-written client, and one that appears under both is the type swap. Being able to
     * tell those apart in one command is the whole reason the adapter exists (plan Decision 3).
     *
     * The test defaults to native rather than reading the Gradle-supplied property strictly, so an
     * IDE run — where no system property is set — exercises the shipping implementation too.
     */
    private fun buildClient(): DynamoDb {
        val endpoint = localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString()

        return if (System.getProperty("awskt.dynamodb.impl") != "sdk") {
            DynamoDb {
                endpointUrl = endpoint
                region = "us-east-1"
                credentialsProvider =
                    AwsKtStaticCredentialsProvider(AwsCredentials("DummyKey", "DummySecret"))
            }
        } else {
            SdkBackedDynamoDb(
                DynamoDbClient {
                    endpointUrl = Url.parse(endpoint)
                    region = "us-east-1"
                    credentialsProvider = StaticCredentialsProvider {
                        accessKeyId = "DummyKey"
                        secretAccessKey = "DummySecret"
                    }
                },
            )
        }
    }

    @AfterTest
    fun destroy() {
    }
}