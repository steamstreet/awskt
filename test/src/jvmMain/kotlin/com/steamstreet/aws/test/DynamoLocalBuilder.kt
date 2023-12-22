package com.steamstreet.aws.test

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient
import java.net.URI

/**
 * Manages a DynamoLocal instance.
 */
class DynamoLocalBuilder(
    var port: Int = 9945
) {
    val client: DynamoDbClient by lazy {
        DynamoDbClient.builder()
            .endpointOverride(URI.create("http://localhost:$port")) // The region is meaningless for local DynamoDb but required for client builder validation
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("DummyKey", "DummySecret")
                )
            ).build()
    }

    val clientBuilder: DynamoDbClientBuilder
        get() {
            return DynamoDbClient.builder()
                .endpointOverride(URI.create("http://localhost:$port")) // The region is meaningless for local DynamoDb but required for client builder validation
                .region(Region.US_EAST_1)
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("DummyKey", "DummySecret")
                    )
                )
        }

    val streamsClient: DynamoDbStreamsClient by lazy {
        DynamoDbStreamsClient.builder()
            .endpointOverride(URI.create("http://localhost:$port"))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("DummyKey", "DummySecret")
                )
            ).build()
    }
}
