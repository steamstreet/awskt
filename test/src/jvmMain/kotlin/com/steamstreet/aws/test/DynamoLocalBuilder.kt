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
public class DynamoLocalBuilder(
    public var port: Int = 9945
) {
    private var server: DynamoDBProxyServer? = null

    public fun start() {
        server = ServerRunner.createServerFromCommandLineArgs(
            arrayOf("-inMemory", "-port", port.toString())
        )
        server?.start()
    }

    public val client: DynamoDbClient by lazy {
        DynamoDbClient {
            clientBuilder()
        }
    }

    public val clientBuilder: DynamoDbClient.Config.Builder.() -> Unit
        get() = {
            endpointUrl = Url.parse("http://localhost:$port")
            region = "us-east-1"
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = "dummyKey"
                secretAccessKey = "dummySecret"
            }
        }

    public val streamsClient: DynamoDbStreamsClient by lazy {
        DynamoDbStreamsClient {
            endpointUrl = Url.parse("http://localhost:$port")
            region = "us-east-1"
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = "dummyKey"
                secretAccessKey = "dummySecret"
            }
        }
    }

    public fun stop() {
        server?.stop()
    }
}