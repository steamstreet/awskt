package com.steamstreet.awskt.dynamodb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The control-plane types.
 *
 * `CreateTable` is here despite being reachable only from tests, and that is deliberate: the
 * existing suites call `DynamoKt.defaultClientBuilder(null).createTable { }` and
 * `database.client.createTable { }` — **through the very seam this client replaces**. Without it,
 * the project's headline success criterion ("the 100 existing integration tests pass") cannot even
 * compile.
 *
 * `TableDescription` is deliberately **narrow**. The SDK's version drags in a 36-type closure;
 * `describeTable()` has zero callers anywhere in the repo, so reproducing that would be modelling
 * for its own sake. These are the fields anything plausibly wants.
 */

@Serializable
public enum class ScalarAttributeType {
    @SerialName("S") S,

    @SerialName("N") N,

    @SerialName("B") B,
}

@Serializable
public enum class KeyType {
    @SerialName("HASH") Hash,

    @SerialName("RANGE") Range,
}

@Serializable
public enum class BillingMode {
    @SerialName("PROVISIONED") Provisioned,

    @SerialName("PAY_PER_REQUEST") PayPerRequest,
}

@Serializable
public enum class ProjectionType {
    @SerialName("ALL") All,

    @SerialName("KEYS_ONLY") KeysOnly,

    @SerialName("INCLUDE") Include,
}

@Serializable
public enum class StreamViewType {
    @SerialName("NEW_IMAGE") NewImage,

    @SerialName("OLD_IMAGE") OldImage,

    @SerialName("NEW_AND_OLD_IMAGES") NewAndOldImages,

    @SerialName("KEYS_ONLY") KeysOnly,
}

@Serializable
public data class AttributeDefinition(
    @SerialName("AttributeName") public val attributeName: String,
    @SerialName("AttributeType") public val attributeType: ScalarAttributeType,
)

@Serializable
public data class KeySchemaElement(
    @SerialName("AttributeName") public val attributeName: String,
    @SerialName("KeyType") public val keyType: KeyType,
)

@Serializable
public data class ProvisionedThroughput(
    @SerialName("ReadCapacityUnits") public val readCapacityUnits: Long,
    @SerialName("WriteCapacityUnits") public val writeCapacityUnits: Long,
)

@Serializable
public data class Projection(
    @SerialName("ProjectionType") public val projectionType: ProjectionType? = null,
    @SerialName("NonKeyAttributes") public val nonKeyAttributes: List<String>? = null,
)

@Serializable
public data class GlobalSecondaryIndex(
    @SerialName("IndexName") public val indexName: String,
    @SerialName("KeySchema") public val keySchema: List<KeySchemaElement>,
    @SerialName("Projection") public val projection: Projection,
    @SerialName("ProvisionedThroughput") public val provisionedThroughput: ProvisionedThroughput? = null,
)

@Serializable
public data class LocalSecondaryIndex(
    @SerialName("IndexName") public val indexName: String,
    @SerialName("KeySchema") public val keySchema: List<KeySchemaElement>,
    @SerialName("Projection") public val projection: Projection,
)

@Serializable
public data class StreamSpecification(
    @SerialName("StreamEnabled") public val streamEnabled: Boolean,
    @SerialName("StreamViewType") public val streamViewType: StreamViewType? = null,
)

@Serializable
public data class CreateTableRequest(
    @SerialName("TableName") public val tableName: String,
    @SerialName("AttributeDefinitions") public val attributeDefinitions: List<AttributeDefinition>,
    @SerialName("KeySchema") public val keySchema: List<KeySchemaElement>,
    @SerialName("BillingMode") public val billingMode: BillingMode? = null,
    @SerialName("ProvisionedThroughput") public val provisionedThroughput: ProvisionedThroughput? = null,
    @SerialName("GlobalSecondaryIndexes") public val globalSecondaryIndexes: List<GlobalSecondaryIndex>? = null,
    @SerialName("LocalSecondaryIndexes") public val localSecondaryIndexes: List<LocalSecondaryIndex>? = null,
    @SerialName("StreamSpecification") public val streamSpecification: StreamSpecification? = null,
)

/** A narrow index description. Enough to introspect an index; not a mirror of the SDK's. */
@Serializable
public data class GlobalSecondaryIndexDescription(
    @SerialName("IndexName") public val indexName: String? = null,
    @SerialName("KeySchema") public val keySchema: List<KeySchemaElement>? = null,
    @SerialName("Projection") public val projection: Projection? = null,
    @SerialName("IndexStatus") public val indexStatus: String? = null,
)

/** Narrow by design — see the file KDoc. `TableStatus` is a String, not an enum, so a new status AWS adds is not a crash. */
@Serializable
public data class TableDescription(
    @SerialName("TableName") public val tableName: String? = null,
    @SerialName("TableStatus") public val tableStatus: String? = null,
    @SerialName("ItemCount") public val itemCount: Long? = null,
    @SerialName("TableSizeBytes") public val tableSizeBytes: Long? = null,
    @SerialName("KeySchema") public val keySchema: List<KeySchemaElement>? = null,
    @SerialName("AttributeDefinitions") public val attributeDefinitions: List<AttributeDefinition>? = null,
    @SerialName("GlobalSecondaryIndexes")
    public val globalSecondaryIndexes: List<GlobalSecondaryIndexDescription>? = null,
    @SerialName("StreamSpecification") public val streamSpecification: StreamSpecification? = null,
    @SerialName("LatestStreamArn") public val latestStreamArn: String? = null,
)

@Serializable
public data class CreateTableResponse(
    @SerialName("TableDescription") public val tableDescription: TableDescription? = null,
)

@Serializable
public data class DescribeTableRequest(
    @SerialName("TableName") public val tableName: String,
)

@Serializable
public data class DescribeTableResponse(
    @SerialName("Table") public val table: TableDescription? = null,
)

@Serializable
public data class DeleteTableRequest(
    @SerialName("TableName") public val tableName: String,
)

@Serializable
public data class DeleteTableResponse(
    @SerialName("TableDescription") public val tableDescription: TableDescription? = null,
)
