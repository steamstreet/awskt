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
 *
 * // With table name mapping for testing
 * val testDb = Database.connect {
 *     tableNameMapper = { "test_$it" }
 * }
 * ```
 */
public class Database(
    public val client: DynamoDbClient,
    public val defaultConsistentRead: Boolean = false,
    /**
     * Optional mapper to transform table names.
     * Useful for testing with prefixed table names or routing to different tables.
     */
    public val tableNameMapper: ((String) -> String)? = null
) {
    /**
     * Resolve the actual table name to use for operations.
     * Applies the tableNameMapper if configured, otherwise returns the original name.
     */
    public fun resolveTableName(table: Table): String {
        return tableNameMapper?.invoke(table.tableName) ?: table.tableName
    }

    /**
     * Resolve the actual table name to use for operations.
     * Applies the tableNameMapper if configured, otherwise returns the original name.
     */
    public fun resolveTableName(tableName: String): String {
        return tableNameMapper?.invoke(tableName) ?: tableName
    }

    public companion object {
        /**
         * Connect to DynamoDB.
         * Similar to Exposed's Database.connect()
         */
        public suspend fun connect(
            configure: DatabaseBuilder.() -> Unit = {}
        ): Database {
            val builder = DatabaseBuilder().apply(configure)
            val client = DynamoDbClient.fromEnvironment {
                builder.clientConfig?.invoke(this)
            }
            return Database(
                client = client,
                defaultConsistentRead = builder.defaultConsistentRead,
                tableNameMapper = builder.tableNameMapper
            )
        }
    }
}

/**
 * Builder for Database configuration.
 */
public class DatabaseBuilder {
    /**
     * Optional mapper to transform table names.
     * Useful for testing with prefixed table names or routing to different tables.
     *
     * Example:
     * ```
     * tableNameMapper = { tableName -> "test_$tableName" }
     * ```
     */
    public var tableNameMapper: ((String) -> String)? = null

    /**
     * Default consistent read setting for all operations.
     */
    public var defaultConsistentRead: Boolean = false

    /**
     * Configuration block for the underlying DynamoDB client.
     */
    internal var clientConfig: (DynamoDbClient.Config.Builder.() -> Unit)? = null

    /**
     * Configure the underlying DynamoDB client.
     */
    public fun client(configure: DynamoDbClient.Config.Builder.() -> Unit) {
        clientConfig = configure
    }
}
