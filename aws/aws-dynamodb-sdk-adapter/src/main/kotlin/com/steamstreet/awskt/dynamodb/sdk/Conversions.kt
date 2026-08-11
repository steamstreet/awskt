package com.steamstreet.awskt.dynamodb.sdk

import com.steamstreet.awskt.dynamodb.AttributeDefinition
import com.steamstreet.dynamokt.AttributeValue
import com.steamstreet.awskt.dynamodb.BillingMode
import com.steamstreet.awskt.dynamodb.CancellationReason
import com.steamstreet.awskt.dynamodb.ConsumedCapacity
import com.steamstreet.awskt.dynamodb.GlobalSecondaryIndexDescription
import com.steamstreet.awskt.dynamodb.Item
import com.steamstreet.awskt.dynamodb.ItemResponse
import com.steamstreet.awskt.dynamodb.KeySchemaElement
import com.steamstreet.awskt.dynamodb.KeyType
import com.steamstreet.awskt.dynamodb.KeysAndAttributes
import com.steamstreet.awskt.dynamodb.Projection
import com.steamstreet.awskt.dynamodb.ProjectionType
import com.steamstreet.awskt.dynamodb.ProvisionedThroughput
import com.steamstreet.awskt.dynamodb.ReturnValue
import com.steamstreet.awskt.dynamodb.ReturnValuesOnConditionCheckFailure
import com.steamstreet.awskt.dynamodb.ScalarAttributeType
import com.steamstreet.awskt.dynamodb.Select
import com.steamstreet.awskt.dynamodb.StreamSpecification
import com.steamstreet.awskt.dynamodb.StreamViewType
import com.steamstreet.awskt.dynamodb.TableDescription
import com.steamstreet.awskt.dynamodb.WriteRequest
import aws.sdk.kotlin.services.dynamodb.model.AttributeDefinition as SdkAttributeDefinition
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue as SdkAttributeValue
import aws.sdk.kotlin.services.dynamodb.model.BillingMode as SdkBillingMode
import aws.sdk.kotlin.services.dynamodb.model.CancellationReason as SdkCancellationReason
import aws.sdk.kotlin.services.dynamodb.model.ConsumedCapacity as SdkConsumedCapacity
import aws.sdk.kotlin.services.dynamodb.model.DeleteRequest as SdkDeleteRequest
import aws.sdk.kotlin.services.dynamodb.model.GlobalSecondaryIndexDescription as SdkGlobalSecondaryIndexDescription
import aws.sdk.kotlin.services.dynamodb.model.ItemResponse as SdkItemResponse
import aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement as SdkKeySchemaElement
import aws.sdk.kotlin.services.dynamodb.model.KeyType as SdkKeyType
import aws.sdk.kotlin.services.dynamodb.model.KeysAndAttributes as SdkKeysAndAttributes
import aws.sdk.kotlin.services.dynamodb.model.Projection as SdkProjection
import aws.sdk.kotlin.services.dynamodb.model.ProjectionType as SdkProjectionType
import aws.sdk.kotlin.services.dynamodb.model.ProvisionedThroughput as SdkProvisionedThroughput
import aws.sdk.kotlin.services.dynamodb.model.PutRequest as SdkPutRequest
import aws.sdk.kotlin.services.dynamodb.model.ReturnValue as SdkReturnValue
import aws.sdk.kotlin.services.dynamodb.model.ReturnValuesOnConditionCheckFailure as SdkReturnValuesOnConditionCheckFailure
import aws.sdk.kotlin.services.dynamodb.model.ScalarAttributeType as SdkScalarAttributeType
import aws.sdk.kotlin.services.dynamodb.model.Select as SdkSelect
import aws.sdk.kotlin.services.dynamodb.model.StreamSpecification as SdkStreamSpecification
import aws.sdk.kotlin.services.dynamodb.model.StreamViewType as SdkStreamViewType
import aws.sdk.kotlin.services.dynamodb.model.TableDescription as SdkTableDescription
import aws.sdk.kotlin.services.dynamodb.model.WriteRequest as SdkWriteRequest

/**
 * Value conversions between this library's DTOs and the AWS SDK's model types.
 *
 * These are mechanical and dull on purpose. The only judgement calls in the file are the two
 * `SdkUnknown` branches: the SDK models every enum and `AttributeValue` as an open sealed type so a
 * value AWS adds tomorrow deserializes rather than crashes, and this library does not. Rather than
 * inventing a silent fallback, both throw with the offending value named — the same failure the
 * hand-written codec produces on an unknown discriminator, so the two implementations behave alike
 * on data neither understands.
 */

// -- AttributeValue -----------------------------------------------------------------------------

internal fun AttributeValue.toSdk(): SdkAttributeValue = when (this) {
    is AttributeValue.S -> SdkAttributeValue.S(value)
    is AttributeValue.N -> SdkAttributeValue.N(value)
    is AttributeValue.B -> SdkAttributeValue.B(value)
    is AttributeValue.Bool -> SdkAttributeValue.Bool(value)
    is AttributeValue.Null -> SdkAttributeValue.Null(value)
    is AttributeValue.Ss -> SdkAttributeValue.Ss(value)
    is AttributeValue.Ns -> SdkAttributeValue.Ns(value)
    is AttributeValue.Bs -> SdkAttributeValue.Bs(value)
    is AttributeValue.L -> SdkAttributeValue.L(value.map { it.toSdk() })
    is AttributeValue.M -> SdkAttributeValue.M(value.mapValues { it.value.toSdk() })

    // Symmetric with the SdkUnknown branch below: the SDK's own SdkUnknown carries no payload, so
    // there is nothing to map it onto and a request built from it would be silently wrong.
    is AttributeValue.SdkUnknown -> throw IllegalArgumentException(
        "Cannot send AttributeValue.SdkUnknown('${'$'}{discriminator}') through the AWS SDK: it was " +
            "decoded from a variant this library does not model, and the SDK cannot represent it.",
    )
}

internal fun SdkAttributeValue.toAwsKt(): AttributeValue = when (this) {
    is SdkAttributeValue.S -> AttributeValue.S(value)
    is SdkAttributeValue.N -> AttributeValue.N(value)
    is SdkAttributeValue.B -> AttributeValue.B(value)
    is SdkAttributeValue.Bool -> AttributeValue.Bool(value)
    is SdkAttributeValue.Null -> AttributeValue.Null(value)
    is SdkAttributeValue.Ss -> AttributeValue.Ss(value)
    is SdkAttributeValue.Ns -> AttributeValue.Ns(value)
    is SdkAttributeValue.Bs -> AttributeValue.Bs(value)
    is SdkAttributeValue.L -> AttributeValue.L(value.map { it.toAwsKt() })
    is SdkAttributeValue.M -> AttributeValue.M(value.mapValues { it.value.toAwsKt() })
    is SdkAttributeValue.SdkUnknown -> throw IllegalArgumentException(
        "DynamoDB returned an AttributeValue variant this library does not model. " +
            "Add it to com.steamstreet.awskt.dynamodb.AttributeValue and its codec.",
    )
}

internal fun Item.toSdkItem(): Map<String, SdkAttributeValue> = mapValues { it.value.toSdk() }

internal fun Map<String, SdkAttributeValue>.toAwsKtItem(): Item = mapValues { it.value.toAwsKt() }

// -- Enums --------------------------------------------------------------------------------------

internal fun ReturnValue.toSdk(): SdkReturnValue = when (this) {
    ReturnValue.None -> SdkReturnValue.None
    ReturnValue.AllOld -> SdkReturnValue.AllOld
    ReturnValue.UpdatedOld -> SdkReturnValue.UpdatedOld
    ReturnValue.AllNew -> SdkReturnValue.AllNew
    ReturnValue.UpdatedNew -> SdkReturnValue.UpdatedNew
}

internal fun ReturnValuesOnConditionCheckFailure.toSdk(): SdkReturnValuesOnConditionCheckFailure = when (this) {
    ReturnValuesOnConditionCheckFailure.None -> SdkReturnValuesOnConditionCheckFailure.None
    ReturnValuesOnConditionCheckFailure.AllOld -> SdkReturnValuesOnConditionCheckFailure.AllOld
}

internal fun Select.toSdk(): SdkSelect = when (this) {
    Select.AllAttributes -> SdkSelect.AllAttributes
    Select.AllProjectedAttributes -> SdkSelect.AllProjectedAttributes
    Select.SpecificAttributes -> SdkSelect.SpecificAttributes
    Select.Count -> SdkSelect.Count
}

internal fun ScalarAttributeType.toSdk(): SdkScalarAttributeType = when (this) {
    ScalarAttributeType.S -> SdkScalarAttributeType.S
    ScalarAttributeType.N -> SdkScalarAttributeType.N
    ScalarAttributeType.B -> SdkScalarAttributeType.B
}

internal fun SdkScalarAttributeType.toAwsKt(): ScalarAttributeType = when (this) {
    SdkScalarAttributeType.S -> ScalarAttributeType.S
    SdkScalarAttributeType.N -> ScalarAttributeType.N
    SdkScalarAttributeType.B -> ScalarAttributeType.B
    else -> unknownEnum("ScalarAttributeType", value)
}

internal fun KeyType.toSdk(): SdkKeyType = when (this) {
    KeyType.Hash -> SdkKeyType.Hash
    KeyType.Range -> SdkKeyType.Range
}

internal fun SdkKeyType.toAwsKt(): KeyType = when (this) {
    SdkKeyType.Hash -> KeyType.Hash
    SdkKeyType.Range -> KeyType.Range
    else -> unknownEnum("KeyType", value)
}

internal fun BillingMode.toSdk(): SdkBillingMode = when (this) {
    BillingMode.Provisioned -> SdkBillingMode.Provisioned
    BillingMode.PayPerRequest -> SdkBillingMode.PayPerRequest
}

internal fun ProjectionType.toSdk(): SdkProjectionType = when (this) {
    ProjectionType.All -> SdkProjectionType.All
    ProjectionType.KeysOnly -> SdkProjectionType.KeysOnly
    ProjectionType.Include -> SdkProjectionType.Include
}

internal fun SdkProjectionType.toAwsKt(): ProjectionType = when (this) {
    SdkProjectionType.All -> ProjectionType.All
    SdkProjectionType.KeysOnly -> ProjectionType.KeysOnly
    SdkProjectionType.Include -> ProjectionType.Include
    else -> unknownEnum("ProjectionType", value)
}

internal fun StreamViewType.toSdk(): SdkStreamViewType = when (this) {
    StreamViewType.NewImage -> SdkStreamViewType.NewImage
    StreamViewType.OldImage -> SdkStreamViewType.OldImage
    StreamViewType.NewAndOldImages -> SdkStreamViewType.NewAndOldImages
    StreamViewType.KeysOnly -> SdkStreamViewType.KeysOnly
}

internal fun SdkStreamViewType.toAwsKt(): StreamViewType = when (this) {
    SdkStreamViewType.NewImage -> StreamViewType.NewImage
    SdkStreamViewType.OldImage -> StreamViewType.OldImage
    SdkStreamViewType.NewAndOldImages -> StreamViewType.NewAndOldImages
    SdkStreamViewType.KeysOnly -> StreamViewType.KeysOnly
    else -> unknownEnum("StreamViewType", value)
}

private fun unknownEnum(type: String, value: String): Nothing = throw IllegalArgumentException(
    "DynamoDB returned $type value '$value', which this library does not model. " +
        "Add it to com.steamstreet.awskt.dynamodb.",
)

// -- Structures ---------------------------------------------------------------------------------

internal fun KeysAndAttributes.toSdk(): SdkKeysAndAttributes = SdkKeysAndAttributes {
    keys = this@toSdk.keys.map { it.toSdkItem() }
    consistentRead = this@toSdk.consistentRead
    projectionExpression = this@toSdk.projectionExpression
    expressionAttributeNames = this@toSdk.expressionAttributeNames
}

internal fun SdkKeysAndAttributes.toAwsKt(): KeysAndAttributes = KeysAndAttributes(
    keys = keys.orEmpty().map { it.toAwsKtItem() },
    consistentRead = consistentRead,
    projectionExpression = projectionExpression,
    expressionAttributeNames = expressionAttributeNames,
)

internal fun WriteRequest.toSdk(): SdkWriteRequest = SdkWriteRequest {
    this@toSdk.putRequest?.let { p -> putRequest = SdkPutRequest { item = p.item.toSdkItem() } }
    this@toSdk.deleteRequest?.let { d -> deleteRequest = SdkDeleteRequest { key = d.key.toSdkItem() } }
}

internal fun SdkWriteRequest.toAwsKt(): WriteRequest = WriteRequest(
    putRequest = putRequest?.item?.let { com.steamstreet.awskt.dynamodb.PutRequest(it.toAwsKtItem()) },
    deleteRequest = deleteRequest?.key?.let { com.steamstreet.awskt.dynamodb.DeleteRequest(it.toAwsKtItem()) },
)

internal fun SdkItemResponse.toAwsKt(): ItemResponse = ItemResponse(item?.toAwsKtItem())

internal fun SdkConsumedCapacity.toAwsKt(): ConsumedCapacity =
    ConsumedCapacity(tableName = tableName, capacityUnits = capacityUnits)

internal fun SdkCancellationReason.toAwsKt(): CancellationReason = CancellationReason(
    // DynamoDB always sends a code — the literal "None" for the items that succeeded — so a null
    // here means the SDK saw a body without one. "None" is the honest reading of that.
    code = code ?: "None",
    message = message,
    item = item?.toAwsKtItem(),
)

internal fun AttributeDefinition.toSdk(): SdkAttributeDefinition = SdkAttributeDefinition {
    attributeName = this@toSdk.attributeName
    attributeType = this@toSdk.attributeType.toSdk()
}

internal fun SdkAttributeDefinition.toAwsKt(): AttributeDefinition =
    AttributeDefinition(attributeName, attributeType.toAwsKt())

internal fun KeySchemaElement.toSdk(): SdkKeySchemaElement = SdkKeySchemaElement {
    attributeName = this@toSdk.attributeName
    keyType = this@toSdk.keyType.toSdk()
}

internal fun SdkKeySchemaElement.toAwsKt(): KeySchemaElement = KeySchemaElement(attributeName, keyType.toAwsKt())

internal fun ProvisionedThroughput.toSdk(): SdkProvisionedThroughput = SdkProvisionedThroughput {
    readCapacityUnits = this@toSdk.readCapacityUnits
    writeCapacityUnits = this@toSdk.writeCapacityUnits
}

internal fun Projection.toSdk(): SdkProjection = SdkProjection {
    projectionType = this@toSdk.projectionType?.toSdk()
    nonKeyAttributes = this@toSdk.nonKeyAttributes
}

internal fun SdkProjection.toAwsKt(): Projection =
    Projection(projectionType?.toAwsKt(), nonKeyAttributes)

internal fun StreamSpecification.toSdk(): SdkStreamSpecification = SdkStreamSpecification {
    streamEnabled = this@toSdk.streamEnabled
    streamViewType = this@toSdk.streamViewType?.toSdk()
}

internal fun SdkStreamSpecification.toAwsKt(): StreamSpecification =
    StreamSpecification(streamEnabled ?: false, streamViewType?.toAwsKt())

internal fun SdkGlobalSecondaryIndexDescription.toAwsKt(): GlobalSecondaryIndexDescription =
    GlobalSecondaryIndexDescription(
        indexName = indexName,
        keySchema = keySchema?.map { it.toAwsKt() },
        projection = projection?.toAwsKt(),
        indexStatus = indexStatus?.value,
    )

/** Narrow by design — see `TableDescription`'s KDoc. The SDK's remaining ~27 fields are dropped. */
internal fun SdkTableDescription.toAwsKt(): TableDescription = TableDescription(
    tableName = tableName,
    tableStatus = tableStatus?.value,
    itemCount = itemCount,
    tableSizeBytes = tableSizeBytes,
    keySchema = keySchema?.map { it.toAwsKt() },
    attributeDefinitions = attributeDefinitions?.map { it.toAwsKt() },
    globalSecondaryIndexes = globalSecondaryIndexes?.map { it.toAwsKt() },
    streamSpecification = streamSpecification?.toAwsKt(),
    latestStreamArn = latestStreamArn,
)
