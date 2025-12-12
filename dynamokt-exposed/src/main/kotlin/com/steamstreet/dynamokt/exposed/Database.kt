package com.steamstreet.dynamokt.exposed

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.smithy.kotlin.runtime.net.url.Url

/**
 * Database connection for DynamoDB operations.
 * Similar to Exposed's Database class.
 *
 * Example:
 * ```
 * val db = Database.connect(region = "us-east-1")
 * ```
 */
public class Database(
    public val client: DynamoDbClient,
    public val defaultConsistentRead: Boolean = false
) {
    public companion object {
        /**
         * Connect to DynamoDB.
         * Similar to Exposed's Database.connect()
         */
        public fun connect(
            region: String = "us-east-1",
            endpoint: String? = null,
            configure: DynamoDbClient.Config.Builder.() -> Unit = {}
        ): Database {
            val client = DynamoDbClient {
                this.region = region
                endpoint?.let { endpointUrl = Url.parse(it) }
                apply(configure)
            }
            return Database(client)
        }
    }
}
