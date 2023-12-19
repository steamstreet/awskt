package com.steamstreet.aws.test

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodbstreams.DynamoDbStreamsClient
import aws.smithy.kotlin.runtime.net.Url
import com.amazonaws.services.dynamodbv2.local.main.ServerRunner
import com.amazonaws.services.dynamodbv2.local.server.DynamoDBProxyServer

/**
 * Manages a DynamoLocal instance.
 */
class DynamoLocalBuilder(
    var port: Int = 9945
) {
    private var server: DynamoDBProxyServer? = null

    private fun start() {
        server = ServerRunner.createServerFromCommandLineArgs(
            arrayOf("-inMemory", "-port", port.toString())
        )
        server?.start()
    }

    val client: DynamoDbClient by lazy {
        DynamoDbClient {
            clientBuilder()
        }
    }

    val clientBuilder: DynamoDbClient.Config.Builder.() -> Unit
        get() = {
            endpointUrl = Url.parse("http://localhost:$port")
            region = "us-east-1"
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = "dummy-key"
                secretAccessKey = "dummy-secret"
            }
        }

    val streamsClient: DynamoDbStreamsClient by lazy {
        DynamoDbStreamsClient {
            endpointUrl = Url.parse("http://localhost:$port")
            region = "us-east-1"
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = "dummy-key"
                secretAccessKey = "dummy-secret"
            }
        }
    }

    private fun stop() {
        server?.stop()
    }
}