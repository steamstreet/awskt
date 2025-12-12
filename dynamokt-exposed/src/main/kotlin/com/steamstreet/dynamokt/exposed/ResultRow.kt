package com.steamstreet.dynamokt.exposed

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue

/**
 * Wraps a DynamoDB item with type-safe column access.
 * Similar to Exposed's ResultRow.
 *
 * Example:
 * ```
 * val user: ResultRow = Users.get(db, "user#123")
 * val name: String = user[Users.name]
 * val age: Int = user[Users.age]
 * ```
 */
public class ResultRow(
    public val table: Table,
    private val attributes: Map<String, AttributeValue>
) {
    /**
     * Get the value of a column with type safety
     */
    public operator fun <T> get(column: Column<T>): T {
        val value = attributes[column.name]

        // Handle nullable columns
        if (column is NullableColumn<*>) {
            // Check if value is null or explicitly NULL type in DynamoDB
            val isNull = value == null || runCatching { value.asNull() }.getOrNull() == true
            if (isNull) {
                @Suppress("UNCHECKED_CAST")
                return null as T
            }
            @Suppress("UNCHECKED_CAST")
            return (column as NullableColumn<Any>).wrapped.fromAttributeValue(value) as T
        }

        // Handle non-null columns
        if (value == null) {
            throw IllegalStateException("Column ${column.name} not found in result")
        }
        return column.fromAttributeValue(value)
    }

    /**
     * Get the value of a column, returning null if not present
     */
    public fun <T> getOrNull(column: Column<T>): T? {
        return attributes[column.name]?.let { column.fromAttributeValue(it) }
    }

    /**
     * Check if a column has a value
     */
    public fun hasValue(column: Column<*>): Boolean {
        return attributes.containsKey(column.name)
    }

    /**
     * Access the raw DynamoDB attributes
     */
    public val raw: Map<String, AttributeValue> get() = attributes

    override fun toString(): String {
        return "ResultRow(table=${table.tableName}, attributes=$attributes)"
    }
}
