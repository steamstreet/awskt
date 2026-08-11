package com.steamstreet.awskt.dynamodb

import com.steamstreet.dynamokt.AttributeValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs.
 *
 * ### Why these are `data class`es
 *
 * `copy()` is the point. Paginators, retry wrappers and callers all need "this request, with one
 * field changed", and doing that by hand means re-listing every field at every such site — so
 * adding a field later silently drops it wherever someone forgot. `queryPaged` is the clearest
 * case: `page.copy(exclusiveStartKey = next)` cannot lose a field, where the field-by-field
 * reconstruction it replaced would have dropped any future addition on every page after the first.
 *
 * Structural `equals` is a second benefit — it is what lets the SDK differential harness and the
 * response-side tests compare whole request objects rather than field-spotting.
 *
 * The cost is that adding a constructor parameter is a binary-incompatible change to the generated
 * `copy$default`. That is already true of the constructor itself, so it changes nothing about how
 * this module must be versioned.
 *
 * ### The empty-collection invariant
 *
 * Every optional collection is nullable with a `null` default, and callers normalize empties to
 * null via [orNullIfEmpty]. With `encodeDefaults = false`, an assigned `emptyMap()` still emits
 * `"ExpressionAttributeValues":{}` — which DynamoDB **rejects**, where an absent field is fine.
 * The existing `dynamokt` code guards this by hand in three places; here it is a type-level rule.
 */
/**
 * An item: the shape every DynamoDB read and write is expressed in.
 *
 * Declared here rather than beside [AttributeValue] in `dynamo`, because that module's package is
 * `com.steamstreet.dynamokt` — which already contains a `class Item`, the rich entity wrapper
 * `dynamokt` is built around. Two `Item`s in one package is a redeclaration, not an overload.
 */
public typealias Item = Map<String, AttributeValue>

public fun <K, V> Map<K, V>?.orNullIfEmpty(): Map<K, V>? = this?.takeIf { it.isNotEmpty() }

public fun <T> List<T>?.orNullIfEmpty(): List<T>? = this?.takeIf { it.isNotEmpty() }

@Serializable
public enum class ReturnValue {
    @SerialName("NONE") None,

    @SerialName("ALL_OLD") AllOld,

    @SerialName("UPDATED_OLD") UpdatedOld,

    @SerialName("ALL_NEW") AllNew,

    @SerialName("UPDATED_NEW") UpdatedNew,
}

/**
 * Whether a failed condition should return the item it failed against.
 *
 * `AllOld` is the only way to learn what the item actually held when a conditional write lost the
 * race — DynamoDB puts it inside the *error* body, and it exists nowhere else. Without it a caller
 * that needs the current state has to issue a second, separately-racy `GetItem`.
 * [ConditionalCheckFailedException.item] and [CancellationReason.item] are populated from it.
 */
@Serializable
public enum class ReturnValuesOnConditionCheckFailure {
    @SerialName("NONE") None,

    @SerialName("ALL_OLD") AllOld,
}

@Serializable
public enum class Select {
    @SerialName("ALL_ATTRIBUTES") AllAttributes,

    @SerialName("ALL_PROJECTED_ATTRIBUTES") AllProjectedAttributes,

    @SerialName("SPECIFIC_ATTRIBUTES") SpecificAttributes,

    @SerialName("COUNT") Count,
}

// -- GetItem -----------------------------------------------------------------------------------

@Serializable
public data class GetItemRequest(
    @SerialName("TableName") public val tableName: String,
    @SerialName("Key") public val key: Item,
    @SerialName("ConsistentRead") public val consistentRead: Boolean? = null,
    @SerialName("ProjectionExpression") public val projectionExpression: String? = null,
    @SerialName("ExpressionAttributeNames") public val expressionAttributeNames: Map<String, String>? = null,
)

@Serializable
public data class GetItemResponse(
    @SerialName("Item") public val item: Item? = null,
)

// -- PutItem -----------------------------------------------------------------------------------

@Serializable
public data class PutItemRequest(
    @SerialName("TableName") public val tableName: String,
    @SerialName("Item") public val item: Item,
    @SerialName("ConditionExpression") public val conditionExpression: String? = null,
    @SerialName("ExpressionAttributeNames") public val expressionAttributeNames: Map<String, String>? = null,
    @SerialName("ExpressionAttributeValues") public val expressionAttributeValues: Item? = null,
    @SerialName("ReturnValues") public val returnValues: ReturnValue? = null,
    @SerialName("ReturnValuesOnConditionCheckFailure")
    public val returnValuesOnConditionCheckFailure: ReturnValuesOnConditionCheckFailure? = null,
)

@Serializable
public data class PutItemResponse(
    @SerialName("Attributes") public val attributes: Item? = null,
)

// -- UpdateItem --------------------------------------------------------------------------------

@Serializable
public data class UpdateItemRequest(
    @SerialName("TableName") public val tableName: String,
    @SerialName("Key") public val key: Item,
    @SerialName("UpdateExpression") public val updateExpression: String? = null,
    @SerialName("ConditionExpression") public val conditionExpression: String? = null,
    @SerialName("ExpressionAttributeNames") public val expressionAttributeNames: Map<String, String>? = null,
    @SerialName("ExpressionAttributeValues") public val expressionAttributeValues: Item? = null,
    @SerialName("ReturnValues") public val returnValues: ReturnValue? = null,
    @SerialName("ReturnValuesOnConditionCheckFailure")
    public val returnValuesOnConditionCheckFailure: ReturnValuesOnConditionCheckFailure? = null,
)

@Serializable
public data class UpdateItemResponse(
    @SerialName("Attributes") public val attributes: Item? = null,
)

// -- DeleteItem --------------------------------------------------------------------------------

@Serializable
public data class DeleteItemRequest(
    @SerialName("TableName") public val tableName: String,
    @SerialName("Key") public val key: Item,
    @SerialName("ConditionExpression") public val conditionExpression: String? = null,
    @SerialName("ExpressionAttributeNames") public val expressionAttributeNames: Map<String, String>? = null,
    @SerialName("ExpressionAttributeValues") public val expressionAttributeValues: Item? = null,
    @SerialName("ReturnValues") public val returnValues: ReturnValue? = null,
    @SerialName("ReturnValuesOnConditionCheckFailure")
    public val returnValuesOnConditionCheckFailure: ReturnValuesOnConditionCheckFailure? = null,
)

@Serializable
public data class DeleteItemResponse(
    @SerialName("Attributes") public val attributes: Item? = null,
)

// -- Query / Scan ------------------------------------------------------------------------------

@Serializable
public data class QueryRequest(
    @SerialName("TableName") public val tableName: String,
    @SerialName("IndexName") public val indexName: String? = null,
    @SerialName("KeyConditionExpression") public val keyConditionExpression: String? = null,
    @SerialName("FilterExpression") public val filterExpression: String? = null,
    @SerialName("ProjectionExpression") public val projectionExpression: String? = null,
    @SerialName("ExpressionAttributeNames") public val expressionAttributeNames: Map<String, String>? = null,
    @SerialName("ExpressionAttributeValues") public val expressionAttributeValues: Item? = null,
    @SerialName("ExclusiveStartKey") public val exclusiveStartKey: Item? = null,
    @SerialName("Limit") public val limit: Int? = null,
    @SerialName("ScanIndexForward") public val scanIndexForward: Boolean? = null,
    @SerialName("ConsistentRead") public val consistentRead: Boolean? = null,
    @SerialName("Select") public val select: Select? = null,
)

@Serializable
public data class QueryResponse(
    @SerialName("Items") public val items: List<Item>? = null,
    @SerialName("Count") public val count: Int? = null,
    @SerialName("ScannedCount") public val scannedCount: Int? = null,
    @SerialName("LastEvaluatedKey") public val lastEvaluatedKey: Item? = null,
)

@Serializable
public data class ScanRequest(
    @SerialName("TableName") public val tableName: String,
    @SerialName("IndexName") public val indexName: String? = null,
    @SerialName("FilterExpression") public val filterExpression: String? = null,
    @SerialName("ProjectionExpression") public val projectionExpression: String? = null,
    @SerialName("ExpressionAttributeNames") public val expressionAttributeNames: Map<String, String>? = null,
    @SerialName("ExpressionAttributeValues") public val expressionAttributeValues: Item? = null,
    @SerialName("ExclusiveStartKey") public val exclusiveStartKey: Item? = null,
    @SerialName("Limit") public val limit: Int? = null,
    @SerialName("ConsistentRead") public val consistentRead: Boolean? = null,
    @SerialName("Segment") public val segment: Int? = null,
    @SerialName("TotalSegments") public val totalSegments: Int? = null,
)

@Serializable
public data class ScanResponse(
    @SerialName("Items") public val items: List<Item>? = null,
    @SerialName("Count") public val count: Int? = null,
    @SerialName("ScannedCount") public val scannedCount: Int? = null,
    @SerialName("LastEvaluatedKey") public val lastEvaluatedKey: Item? = null,
)

// -- Batch -------------------------------------------------------------------------------------

@Serializable
public data class KeysAndAttributes(
    @SerialName("Keys") public val keys: List<Item>,
    @SerialName("ConsistentRead") public val consistentRead: Boolean? = null,
    @SerialName("ProjectionExpression") public val projectionExpression: String? = null,
    @SerialName("ExpressionAttributeNames") public val expressionAttributeNames: Map<String, String>? = null,
)

@Serializable
public data class BatchGetItemRequest(
    @SerialName("RequestItems") public val requestItems: Map<String, KeysAndAttributes>,
)

@Serializable
public data class BatchGetItemResponse(
    @SerialName("Responses") public val responses: Map<String, List<Item>>? = null,
    @SerialName("UnprocessedKeys") public val unprocessedKeys: Map<String, KeysAndAttributes>? = null,
)

@Serializable
public data class PutRequest(@SerialName("Item") public val item: Item)

@Serializable
public data class DeleteRequest(@SerialName("Key") public val key: Item)

@Serializable
public data class WriteRequest(
    @SerialName("PutRequest") public val putRequest: PutRequest? = null,
    @SerialName("DeleteRequest") public val deleteRequest: DeleteRequest? = null,
)

@Serializable
public data class BatchWriteItemRequest(
    @SerialName("RequestItems") public val requestItems: Map<String, List<WriteRequest>>,
)

@Serializable
public data class BatchWriteItemResponse(
    @SerialName("UnprocessedItems") public val unprocessedItems: Map<String, List<WriteRequest>>? = null,
)

// -- Transactions ------------------------------------------------------------------------------

@Serializable
public data class TransactPut(
    @SerialName("TableName") public val tableName: String,
    @SerialName("Item") public val item: Item,
    @SerialName("ConditionExpression") public val conditionExpression: String? = null,
    @SerialName("ExpressionAttributeNames") public val expressionAttributeNames: Map<String, String>? = null,
    @SerialName("ExpressionAttributeValues") public val expressionAttributeValues: Item? = null,
    @SerialName("ReturnValuesOnConditionCheckFailure")
    public val returnValuesOnConditionCheckFailure: ReturnValuesOnConditionCheckFailure? = null,
)

@Serializable
public data class TransactUpdate(
    @SerialName("TableName") public val tableName: String,
    @SerialName("Key") public val key: Item,
    @SerialName("UpdateExpression") public val updateExpression: String,
    @SerialName("ConditionExpression") public val conditionExpression: String? = null,
    @SerialName("ExpressionAttributeNames") public val expressionAttributeNames: Map<String, String>? = null,
    @SerialName("ExpressionAttributeValues") public val expressionAttributeValues: Item? = null,
    @SerialName("ReturnValuesOnConditionCheckFailure")
    public val returnValuesOnConditionCheckFailure: ReturnValuesOnConditionCheckFailure? = null,
)

@Serializable
public data class TransactDelete(
    @SerialName("TableName") public val tableName: String,
    @SerialName("Key") public val key: Item,
    @SerialName("ConditionExpression") public val conditionExpression: String? = null,
    @SerialName("ExpressionAttributeNames") public val expressionAttributeNames: Map<String, String>? = null,
    @SerialName("ExpressionAttributeValues") public val expressionAttributeValues: Item? = null,
    @SerialName("ReturnValuesOnConditionCheckFailure")
    public val returnValuesOnConditionCheckFailure: ReturnValuesOnConditionCheckFailure? = null,
)

@Serializable
public data class ConditionCheck(
    @SerialName("TableName") public val tableName: String,
    @SerialName("Key") public val key: Item,
    @SerialName("ConditionExpression") public val conditionExpression: String,
    @SerialName("ExpressionAttributeNames") public val expressionAttributeNames: Map<String, String>? = null,
    @SerialName("ExpressionAttributeValues") public val expressionAttributeValues: Item? = null,
    @SerialName("ReturnValuesOnConditionCheckFailure")
    public val returnValuesOnConditionCheckFailure: ReturnValuesOnConditionCheckFailure? = null,
)

@Serializable
public data class TransactWriteItem(
    @SerialName("Put") public val put: TransactPut? = null,
    @SerialName("Update") public val update: TransactUpdate? = null,
    @SerialName("Delete") public val delete: TransactDelete? = null,
    @SerialName("ConditionCheck") public val conditionCheck: ConditionCheck? = null,
)

@Serializable
public data class TransactWriteItemsRequest(
    @SerialName("TransactItems") public val transactItems: List<TransactWriteItem>,
    @SerialName("ClientRequestToken") public val clientRequestToken: String? = null,
)

@Serializable
public data class TransactWriteItemsResponse(
    @SerialName("ConsumedCapacity") public val consumedCapacity: List<ConsumedCapacity>? = null,
)

@Serializable
public data class ConsumedCapacity(
    @SerialName("TableName") public val tableName: String? = null,
    @SerialName("CapacityUnits") public val capacityUnits: Double? = null,
)

@Serializable
public data class Get(
    @SerialName("TableName") public val tableName: String,
    @SerialName("Key") public val key: Item,
    @SerialName("ProjectionExpression") public val projectionExpression: String? = null,
    @SerialName("ExpressionAttributeNames") public val expressionAttributeNames: Map<String, String>? = null,
)

@Serializable
public data class TransactGetItem(@SerialName("Get") public val get: Get)

@Serializable
public data class TransactGetItemsRequest(
    @SerialName("TransactItems") public val transactItems: List<TransactGetItem>,
)

@Serializable
public data class ItemResponse(@SerialName("Item") public val item: Item? = null)

@Serializable
public data class TransactGetItemsResponse(
    @SerialName("Responses") public val responses: List<ItemResponse>? = null,
)

/** A cancellation reason. `code` is non-nullable: DynamoDB returns the literal `"None"` on success. */
@Serializable
public data class CancellationReason(
    @SerialName("Code") public val code: String,
    @SerialName("Message") public val message: String? = null,
    @SerialName("Item") public val item: Item? = null,
)
