package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public class DynamoStreamEvent(
    public val eventID: String,
    public val eventName: String,
    public val eventVersion: String,
    public val eventSource: String,
    public val awsRegion: String,
    public val dynamodb: DynamoStreamEventDetail,
    public val eventSourceARN: String,
    public val userIdentity: UserIdentity? = null
)

@Serializable
public class UserIdentity(
    public val type: String? = null,
    public val principalId: String? = null
)

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
    public val createDateTime: Long = approximateCreationDateTime.toLong()
}

/**
 * Get the pair of values from the new and old images
 */
public fun DynamoStreamEventDetail.valuePair(attributeName: String): Pair<AttributeValue?, AttributeValue?> {
    return old?.get(attributeName) to new?.get(attributeName)
}

public data class Items(
    val old: Item? = null,
    val new: Item?
)

public fun DynamoStreamEvent.oldAndNew(session: DynamoKtSession): Items = items(session)
public fun DynamoStreamEvent.items(session: DynamoKtSession): Items {
    return Items(oldItem(session), newItem(session))
}

public fun DynamoStreamEvent.diffs(): List<String> {
    return findDifferences(this.dynamodb.old, this.dynamodb.new)
}

public fun DynamoStreamEvent.newItem(session: DynamoKtSession): Item? {
    return dynamodb.new?.let {
        session.unloaded(dynamodb.keys + it, false)
    }
}

public fun DynamoStreamEvent.oldItem(session: DynamoKtSession): Item? {
    return dynamodb.old?.let {
        session.unloaded(dynamodb.keys + it, false)
    }
}

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

/**
 * Mapping for the incoming dynamo event when it can include multiple records.
 */
@Serializable
public class DynamoStreamRecords(
    @SerialName("Records")
    public val records: List<DynamoStreamEvent>
)