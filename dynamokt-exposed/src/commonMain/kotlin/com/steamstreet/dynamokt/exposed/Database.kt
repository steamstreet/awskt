package com.steamstreet.dynamokt.exposed

import com.steamstreet.awskt.dynamodb.DynamoDb
import com.steamstreet.awskt.dynamodb.DynamoDbConfig

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
    public val client: DynamoDb,
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
        /**
         * No longer `suspend`: our client resolves region, endpoint and credentials lazily at the
         * first call rather than doing I/O at construction (plan Decision 4), so there is nothing
         * to await here. That is a source-compatible relaxation for callers already in a coroutine
         * and a genuine simplification for those that were only suspending to build this.
         */
        public fun connect(
            configure: DatabaseBuilder.() -> Unit = {}
        ): Database {
            val builder = DatabaseBuilder().apply(configure)
            val client = DynamoDb {
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
    internal var clientConfig: (DynamoDbConfig.() -> Unit)? = null

    /**
     * Configure the underlying DynamoDB client.
     */
    public fun client(configure: DynamoDbConfig.() -> Unit) {
        clientConfig = configure
    }
}
