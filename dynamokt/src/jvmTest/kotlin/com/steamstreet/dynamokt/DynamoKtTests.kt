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
     * **M5a runs on `SdkBackedDynamoDb`**: the type swap is validated while behaviour underneath is
     * still, byte for byte, the AWS SDK's, so a failure here means the swap is wrong rather than
     * the hand-written client. M5b flips the default and is a one-line revert — which is the whole
     * reason the adapter exists (plan Decision 3).
     */
    private fun buildClient(): DynamoDb {
        val endpoint = localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString()

        return if (System.getProperty("awskt.dynamodb.impl") == "native") {
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