package com.steamstreet.dynamokt

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.smithy.kotlin.runtime.net.url.Url
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
        DynamoKt.defaultClientBuilder = {
            val endpoint = localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString()
            DynamoDbClient {
                endpointUrl = Url.parse(endpoint)
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
}