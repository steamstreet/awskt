package com.steamstreet.dynamokt

/**
 * Defines an index on a DynamoDB table.
 */
public class DynamoKtIndex(
    /**
     * The index name
     */
    public val name: String,

    /**
     * The partition key name
     */
    public val pk: String,

    /**
     * The sort key name (can be null)
     */
    public val sk: String?
)