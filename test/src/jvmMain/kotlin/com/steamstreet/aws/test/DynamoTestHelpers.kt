package com.steamstreet.aws.test

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.createTable
import aws.sdk.kotlin.services.dynamodb.model.*
import com.steamstreet.env.registerEnvironmentVariable
import java.util.*

/**
 * Constructs an AttributeDefinition with just a name, with a default type that can be provided
 */
public fun AttributeDefinition(name: String, type: ScalarAttributeType = ScalarAttributeType.S): AttributeDefinition =
    AttributeDefinition {
        attributeName = name
        attributeType = type
    }

/**
 * Construct a key schema element.
 */
public fun KeySchemaElement(name: String, type: KeyType): KeySchemaElement = KeySchemaElement {
    attributeName = name
    keyType = type
}

/**
 * Construct a key schema.
 */
public fun KeySchema(hashKey: String, rangeKey: String? = null): List<KeySchemaElement> = buildList {
    add(KeySchemaElement(hashKey, KeyType.Hash))
    if (rangeKey != null) {
        add(KeySchemaElement(rangeKey, KeyType.Range))
    }
}

/**
 * Construct a global secondary index with a string hash and range key.
 */
public fun GlobalSecondaryIndex(name: String, hashKey: String, rangeKey: String? = null): GlobalSecondaryIndex =
    GlobalSecondaryIndex {
        indexName = name
        keySchema = KeySchema(hashKey, rangeKey)
        projection {
            projectionType = ProjectionType.All
        }
        provisionedThroughput {
            readCapacityUnits = 1
            writeCapacityUnits = 1
        }
    }

/**
 * Create a table for testing with some basic defaults that aren't likely useful for testing environments.
 */
public suspend fun DynamoDbClient.defaultTable(envKey: String, block: CreateTableRequest.Builder.() -> Unit) {
    val tableName = "${envKey}-${UUID.randomUUID()}"
    createTable {
        this.tableName = tableName
        billingMode = BillingMode.PayPerRequest
        streamSpecification {
            streamEnabled = true
            streamViewType = StreamViewType.NewAndOldImages
        }
        provisionedThroughput {
            readCapacityUnits = 5
            writeCapacityUnits = 5
        }
        block()
    }
    registerEnvironmentVariable(envKey, tableName)
}