package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a DynamoDB stream event record.
 * This is the format received from DynamoDB Streams via Lambda or EventBridge Pipes.
 */
@Serializable
public class DynamoStreamEvent(
    public val eventID: String,
    public val eventName: String,
    public val eventVersion: String? = null,
    public val eventSource: String,
    public val awsRegion: String,
    public val dynamodb: DynamoStreamEventDetail,
    public val eventSourceARN: String? = null,
    public val userIdentity: UserIdentity? = null,
    public val tableName: String? = null,
    public val recordFormat: String? = null,
)

/**
 * User identity information for stream events (used with DynamoDB Streams from global tables).
 */
@Serializable
public class UserIdentity(
    public val type: String? = null,
    public val principalId: String? = null
)

/**
 * The detail portion of a DynamoDB stream event containing the actual data changes.
 */
@Serializable
public class DynamoStreamEventDetail(
    @SerialName("ApproximateCreationDateTime")
    private val approximateCreationDateTime: Double,

    @SerialName("Keys")
    public val keys: Map<String, @Serializable(with = AttributeValueSerializer::class) AttributeValue>,
    @SerialName("NewImage")
    public val new: Map<String, @Serializable(with = AttributeValueSerializer::class) AttributeValue>? = null,
    @SerialName("OldImage")
    public val old: Map<String, @Serializable(with = AttributeValueSerializer::class) AttributeValue>? = null,

    @SerialName("SequenceNumber")
    public val sequenceNumber: String? = null,

    @SerialName("SizeBytes")
    public val size: Long? = null,

    @SerialName("StreamViewType")
    public val viewType: String? = null
) {
    /**
     * The approximate creation time as a Unix timestamp in seconds.
     */
    public val createDateTime: Long = approximateCreationDateTime.toLong()
}

/**
 * Get the pair of values from the new and old images for a specific attribute.
 * Returns (oldValue, newValue).
 */
public fun DynamoStreamEventDetail.valuePair(attributeName: String): Pair<AttributeValue?, AttributeValue?> {
    return old?.get(attributeName) to new?.get(attributeName)
}

/**
 * Mapping for the incoming dynamo event when it can include multiple records.
 * This is the format received from Lambda when processing DynamoDB Streams.
 */
@Serializable
public class DynamoStreamRecords(
    @SerialName("Records")
    public val records: List<DynamoStreamEvent>
)

/**
 * Create an event bridge detail rule that matches the given key.
 *
 * Examples:
 * - eventBridgeKeyRule("pk", listOf("media"))
 * - eventBridgeKeyRule("sk", listOf(prefix("url:"))
 */
public fun eventBridgeKeyRule(keyName: String, match: List<Any>): Map<String, Any> {
    return mapOf(
        "dynamodb" to mapOf(
            "Keys" to mapOf(
                keyName to mapOf(
                    "S" to match
                )
            )
        )
    )
}
