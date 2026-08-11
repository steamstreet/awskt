package com.steamstreet.awskt.dynamodb.sdk

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.awsHttpClient
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.core.resolveRegion
import com.steamstreet.awskt.dynamodb.BatchGetItemRequest
import com.steamstreet.awskt.dynamodb.BatchGetItemResponse
import com.steamstreet.awskt.dynamodb.BatchWriteItemRequest
import com.steamstreet.awskt.dynamodb.BatchWriteItemResponse
import com.steamstreet.awskt.dynamodb.ConditionalCheckFailedException
import com.steamstreet.awskt.dynamodb.CreateTableRequest
import com.steamstreet.awskt.dynamodb.CreateTableResponse
import com.steamstreet.awskt.dynamodb.DYNAMODB_PROTOCOL
import com.steamstreet.awskt.dynamodb.DeleteItemRequest
import com.steamstreet.awskt.dynamodb.DeleteItemResponse
import com.steamstreet.awskt.dynamodb.DeleteTableRequest
import com.steamstreet.awskt.dynamodb.DeleteTableResponse
import com.steamstreet.awskt.dynamodb.DescribeTableRequest
import com.steamstreet.awskt.dynamodb.DescribeTableResponse
import com.steamstreet.awskt.dynamodb.DynamoDb
import com.steamstreet.awskt.dynamodb.DynamoDbException
import com.steamstreet.awskt.dynamodb.GetItemRequest
import com.steamstreet.awskt.dynamodb.GetItemResponse
import com.steamstreet.awskt.dynamodb.IdempotentParameterMismatchException
import com.steamstreet.awskt.dynamodb.ProvisionedThroughputExceededException
import com.steamstreet.awskt.dynamodb.PutItemRequest
import com.steamstreet.awskt.dynamodb.PutItemResponse
import com.steamstreet.awskt.dynamodb.QueryRequest
import com.steamstreet.awskt.dynamodb.QueryResponse
import com.steamstreet.awskt.dynamodb.ResourceNotFoundException
import com.steamstreet.awskt.dynamodb.ScanRequest
import com.steamstreet.awskt.dynamodb.ScanResponse
import com.steamstreet.awskt.dynamodb.TransactGetItemsRequest
import com.steamstreet.awskt.dynamodb.TransactGetItemsResponse
import com.steamstreet.awskt.dynamodb.TransactWriteItemsRequest
import com.steamstreet.awskt.dynamodb.TransactWriteItemsResponse
import com.steamstreet.awskt.dynamodb.TransactionCanceledException
import com.steamstreet.awskt.dynamodb.UpdateItemRequest
import com.steamstreet.awskt.dynamodb.UpdateItemResponse
import com.steamstreet.awskt.dynamodb.ValidationException
import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import aws.sdk.kotlin.services.dynamodb.model.BatchGetItemRequest as SdkBatchGetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.BatchWriteItemRequest as SdkBatchWriteItemRequest
import aws.sdk.kotlin.services.dynamodb.model.ConditionCheck as SdkConditionCheck
import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException as SdkConditionalCheckFailedException
import aws.sdk.kotlin.services.dynamodb.model.CreateTableRequest as SdkCreateTableRequest
import aws.sdk.kotlin.services.dynamodb.model.Delete as SdkDelete
import aws.sdk.kotlin.services.dynamodb.model.DeleteItemRequest as SdkDeleteItemRequest
import aws.sdk.kotlin.services.dynamodb.model.DeleteTableRequest as SdkDeleteTableRequest
import aws.sdk.kotlin.services.dynamodb.model.DescribeTableRequest as SdkDescribeTableRequest
import aws.sdk.kotlin.services.dynamodb.model.DynamoDbException as SdkDynamoDbException
import aws.sdk.kotlin.services.dynamodb.model.Get as SdkGet
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest as SdkGetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.GlobalSecondaryIndex as SdkGlobalSecondaryIndex
import aws.sdk.kotlin.services.dynamodb.model.LocalSecondaryIndex as SdkLocalSecondaryIndex
import aws.sdk.kotlin.services.dynamodb.model.Put as SdkPut
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest as SdkPutItemRequest
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest as SdkQueryRequest
import aws.sdk.kotlin.services.dynamodb.model.ScanRequest as SdkScanRequest
import aws.sdk.kotlin.services.dynamodb.model.TransactGetItem as SdkTransactGetItem
import aws.sdk.kotlin.services.dynamodb.model.TransactGetItemsRequest as SdkTransactGetItemsRequest
import aws.sdk.kotlin.services.dynamodb.model.TransactWriteItem as SdkTransactWriteItem
import aws.sdk.kotlin.services.dynamodb.model.TransactWriteItemsRequest as SdkTransactWriteItemsRequest
import aws.sdk.kotlin.services.dynamodb.model.TransactionCanceledException as SdkTransactionCanceledException
import aws.sdk.kotlin.services.dynamodb.model.Update as SdkUpdate
import aws.sdk.kotlin.services.dynamodb.model.UpdateItemRequest as SdkUpdateItemRequest

/**
 * A [DynamoDb] backed by `aws.sdk.kotlin`'s `DynamoDbClient`.
 *
 * ### Why this exists
 *
 * It splits the one irreversible migration into two separately-revertible steps. **Swapping the
 * type** — every consumer moving from the SDK's client to this library's `DynamoDb` interface —
 * lands first, validated by the existing integration suites against behaviour that is still, byte
 * for byte, the AWS SDK's. **Flipping the implementation** to `DefaultDynamoDb` lands second, and
 * is a one-line revert if it misbehaves. Without this class those two changes are one change, and
 * any failure afterwards is ambiguous between "the type swap was wrong" and "the hand-written
 * client is wrong".
 *
 * It also stays useful afterwards: a JVM consumer that wants this library's API over the AWS SDK's
 * transport — because it needs a credential source in `aws-config` that `aws-core` deliberately
 * does not carry, say — has a supported path, and if AWS ever publishes native klibs it is the
 * escape hatch.
 *
 * JVM-only, and deliberately so. `aws.sdk.kotlin` has no `linuxArm64` variant; that is the entire
 * reason the rest of this library exists.
 *
 * ### The extension seam
 *
 * [DynamoDb.client] is part of the interface, because operations this library does not ship are
 * added downstream as extension functions over it. There is no `AwsServiceClient` inside an SDK
 * client to hand back, so one is built on demand from [delegate]'s own resolved region, credentials
 * and endpoint. An extension function therefore keeps working across the swap — it just travels
 * over this library's transport while the thirteen built-in operations travel over the SDK's.
 *
 * Nothing is constructed unless [client] is actually read, so a consumer that never uses an
 * extension pays nothing for the seam.
 */
public class SdkBackedDynamoDb(
    private val delegate: DynamoDbClient,
    extensionClient: AwsServiceClient? = null,
    private val retryConfig: RetryConfig = RetryConfig(),
) : DynamoDb {

    private var ownedHttpClient: HttpClient? = null

    private val extensionSeam = lazy { extensionClient ?: buildExtensionClient() }

    override val client: AwsServiceClient get() = extensionSeam.value

    private fun buildExtensionClient(): AwsServiceClient {
        val http = awsHttpClient().also { ownedHttpClient = it }
        val region = resolveRegion(delegate.config.region)
        return AwsServiceClient(
            httpClient = http,
            credentialsProvider = delegate.config.credentialsProvider
                ?.let(::SdkCredentialsProviderBridge)
                ?: defaultCredentialsProvider(),
            endpoint = resolveEndpoint("dynamodb", region, delegate.config.endpointUrl?.toString()),
            region = region,
            protocol = DYNAMODB_PROTOCOL,
            retryConfig = retryConfig,
        )
    }

    // -- Data plane -----------------------------------------------------------------------------

    override suspend fun getItem(request: GetItemRequest): GetItemResponse = mapSdkErrors {
        val response = delegate.getItem(
            SdkGetItemRequest {
                tableName = request.tableName
                key = request.key.toSdkItem()
                consistentRead = request.consistentRead
                projectionExpression = request.projectionExpression
                expressionAttributeNames = request.expressionAttributeNames
            },
        )
        GetItemResponse(item = response.item?.toAwsKtItem())
    }

    override suspend fun putItem(request: PutItemRequest): PutItemResponse = mapSdkErrors {
        val response = delegate.putItem(
            SdkPutItemRequest {
                tableName = request.tableName
                item = request.item.toSdkItem()
                conditionExpression = request.conditionExpression
                expressionAttributeNames = request.expressionAttributeNames
                expressionAttributeValues = request.expressionAttributeValues?.toSdkItem()
                returnValues = request.returnValues?.toSdk()
                returnValuesOnConditionCheckFailure =
                    request.returnValuesOnConditionCheckFailure?.toSdk()
            },
        )
        PutItemResponse(attributes = response.attributes?.toAwsKtItem())
    }

    override suspend fun updateItem(request: UpdateItemRequest): UpdateItemResponse = mapSdkErrors {
        val response = delegate.updateItem(
            SdkUpdateItemRequest {
                tableName = request.tableName
                key = request.key.toSdkItem()
                updateExpression = request.updateExpression
                conditionExpression = request.conditionExpression
                expressionAttributeNames = request.expressionAttributeNames
                expressionAttributeValues = request.expressionAttributeValues?.toSdkItem()
                returnValues = request.returnValues?.toSdk()
                returnValuesOnConditionCheckFailure =
                    request.returnValuesOnConditionCheckFailure?.toSdk()
            },
        )
        UpdateItemResponse(attributes = response.attributes?.toAwsKtItem())
    }

    override suspend fun deleteItem(request: DeleteItemRequest): DeleteItemResponse = mapSdkErrors {
        val response = delegate.deleteItem(
            SdkDeleteItemRequest {
                tableName = request.tableName
                key = request.key.toSdkItem()
                conditionExpression = request.conditionExpression
                expressionAttributeNames = request.expressionAttributeNames
                expressionAttributeValues = request.expressionAttributeValues?.toSdkItem()
                returnValues = request.returnValues?.toSdk()
                returnValuesOnConditionCheckFailure =
                    request.returnValuesOnConditionCheckFailure?.toSdk()
            },
        )
        DeleteItemResponse(attributes = response.attributes?.toAwsKtItem())
    }

    override suspend fun query(request: QueryRequest): QueryResponse = mapSdkErrors {
        val response = delegate.query(
            SdkQueryRequest {
                tableName = request.tableName
                indexName = request.indexName
                keyConditionExpression = request.keyConditionExpression
                filterExpression = request.filterExpression
                projectionExpression = request.projectionExpression
                expressionAttributeNames = request.expressionAttributeNames
                expressionAttributeValues = request.expressionAttributeValues?.toSdkItem()
                exclusiveStartKey = request.exclusiveStartKey?.toSdkItem()
                limit = request.limit
                scanIndexForward = request.scanIndexForward
                consistentRead = request.consistentRead
                select = request.select?.toSdk()
            },
        )
        QueryResponse(
            items = response.items?.map { it.toAwsKtItem() },
            count = response.count,
            scannedCount = response.scannedCount,
            lastEvaluatedKey = response.lastEvaluatedKey?.toAwsKtItem(),
        )
    }

    override suspend fun scan(request: ScanRequest): ScanResponse = mapSdkErrors {
        val response = delegate.scan(
            SdkScanRequest {
                tableName = request.tableName
                indexName = request.indexName
                filterExpression = request.filterExpression
                projectionExpression = request.projectionExpression
                expressionAttributeNames = request.expressionAttributeNames
                expressionAttributeValues = request.expressionAttributeValues?.toSdkItem()
                exclusiveStartKey = request.exclusiveStartKey?.toSdkItem()
                limit = request.limit
                consistentRead = request.consistentRead
                segment = request.segment
                totalSegments = request.totalSegments
            },
        )
        ScanResponse(
            items = response.items?.map { it.toAwsKtItem() },
            count = response.count,
            scannedCount = response.scannedCount,
            lastEvaluatedKey = response.lastEvaluatedKey?.toAwsKtItem(),
        )
    }

    override suspend fun batchGetItem(request: BatchGetItemRequest): BatchGetItemResponse = mapSdkErrors {
        val response = delegate.batchGetItem(
            SdkBatchGetItemRequest { requestItems = request.requestItems.mapValues { it.value.toSdk() } },
        )
        BatchGetItemResponse(
            responses = response.responses?.mapValues { (_, items) -> items.map { it.toAwsKtItem() } },
            unprocessedKeys = response.unprocessedKeys?.mapValues { it.value.toAwsKt() },
        )
    }

    override suspend fun batchWriteItem(request: BatchWriteItemRequest): BatchWriteItemResponse = mapSdkErrors {
        val response = delegate.batchWriteItem(
            SdkBatchWriteItemRequest {
                requestItems = request.requestItems.mapValues { (_, writes) -> writes.map { it.toSdk() } }
            },
        )
        BatchWriteItemResponse(
            unprocessedItems = response.unprocessedItems?.mapValues { (_, writes) -> writes.map { it.toAwsKt() } },
        )
    }

    override suspend fun transactWriteItems(
        request: TransactWriteItemsRequest,
    ): TransactWriteItemsResponse = mapSdkErrors {
        val response = delegate.transactWriteItems(
            SdkTransactWriteItemsRequest {
                transactItems = request.transactItems.map { item ->
                    SdkTransactWriteItem {
                        item.put?.let { p ->
                            put = SdkPut {
                                tableName = p.tableName
                                this.item = p.item.toSdkItem()
                                conditionExpression = p.conditionExpression
                                expressionAttributeNames = p.expressionAttributeNames
                                expressionAttributeValues = p.expressionAttributeValues?.toSdkItem()
                                returnValuesOnConditionCheckFailure =
                                    p.returnValuesOnConditionCheckFailure?.toSdk()
                            }
                        }
                        item.update?.let { u ->
                            update = SdkUpdate {
                                tableName = u.tableName
                                key = u.key.toSdkItem()
                                updateExpression = u.updateExpression
                                conditionExpression = u.conditionExpression
                                expressionAttributeNames = u.expressionAttributeNames
                                expressionAttributeValues = u.expressionAttributeValues?.toSdkItem()
                                returnValuesOnConditionCheckFailure =
                                    u.returnValuesOnConditionCheckFailure?.toSdk()
                            }
                        }
                        item.delete?.let { d ->
                            delete = SdkDelete {
                                tableName = d.tableName
                                key = d.key.toSdkItem()
                                conditionExpression = d.conditionExpression
                                expressionAttributeNames = d.expressionAttributeNames
                                expressionAttributeValues = d.expressionAttributeValues?.toSdkItem()
                                returnValuesOnConditionCheckFailure =
                                    d.returnValuesOnConditionCheckFailure?.toSdk()
                            }
                        }
                        item.conditionCheck?.let { c ->
                            conditionCheck = SdkConditionCheck {
                                tableName = c.tableName
                                key = c.key.toSdkItem()
                                conditionExpression = c.conditionExpression
                                expressionAttributeNames = c.expressionAttributeNames
                                expressionAttributeValues = c.expressionAttributeValues?.toSdkItem()
                                returnValuesOnConditionCheckFailure =
                                    c.returnValuesOnConditionCheckFailure?.toSdk()
                            }
                        }
                    }
                }
                // Passed through rather than regenerated. The SDK generates its own token when this
                // is null; overriding that here would be a behaviour change nobody asked for.
                clientRequestToken = request.clientRequestToken
            },
        )
        TransactWriteItemsResponse(consumedCapacity = response.consumedCapacity?.map { it.toAwsKt() })
    }

    override suspend fun transactGetItems(request: TransactGetItemsRequest): TransactGetItemsResponse =
        mapSdkErrors {
            val response = delegate.transactGetItems(
                SdkTransactGetItemsRequest {
                    transactItems = request.transactItems.map { item ->
                        SdkTransactGetItem {
                            get = SdkGet {
                                tableName = item.get.tableName
                                key = item.get.key.toSdkItem()
                                projectionExpression = item.get.projectionExpression
                                expressionAttributeNames = item.get.expressionAttributeNames
                            }
                        }
                    }
                },
            )
            TransactGetItemsResponse(responses = response.responses?.map { it.toAwsKt() })
        }

    // -- Control plane --------------------------------------------------------------------------

    override suspend fun createTable(request: CreateTableRequest): CreateTableResponse = mapSdkErrors {
        val response = delegate.createTable(
            SdkCreateTableRequest {
                tableName = request.tableName
                attributeDefinitions = request.attributeDefinitions.map { it.toSdk() }
                keySchema = request.keySchema.map { it.toSdk() }
                billingMode = request.billingMode?.toSdk()
                provisionedThroughput = request.provisionedThroughput?.toSdk()
                globalSecondaryIndexes = request.globalSecondaryIndexes?.map { gsi ->
                    SdkGlobalSecondaryIndex {
                        indexName = gsi.indexName
                        keySchema = gsi.keySchema.map { it.toSdk() }
                        projection = gsi.projection.toSdk()
                        provisionedThroughput = gsi.provisionedThroughput?.toSdk()
                    }
                }
                localSecondaryIndexes = request.localSecondaryIndexes?.map { lsi ->
                    SdkLocalSecondaryIndex {
                        indexName = lsi.indexName
                        keySchema = lsi.keySchema.map { it.toSdk() }
                        projection = lsi.projection.toSdk()
                    }
                }
                streamSpecification = request.streamSpecification?.toSdk()
            },
        )
        CreateTableResponse(tableDescription = response.tableDescription?.toAwsKt())
    }

    override suspend fun describeTable(request: DescribeTableRequest): DescribeTableResponse = mapSdkErrors {
        val response = delegate.describeTable(SdkDescribeTableRequest { tableName = request.tableName })
        DescribeTableResponse(table = response.table?.toAwsKt())
    }

    override suspend fun deleteTable(request: DeleteTableRequest): DeleteTableResponse = mapSdkErrors {
        val response = delegate.deleteTable(SdkDeleteTableRequest { tableName = request.tableName })
        DeleteTableResponse(tableDescription = response.tableDescription?.toAwsKt())
    }

    /** Closes the delegate, and the HTTP client only if the extension seam actually built one. */
    override fun close() {
        try {
            delegate.close()
        } finally {
            ownedHttpClient?.close()
        }
    }
}

/**
 * Presents the SDK's credential provider as this library's.
 *
 * One adapter is what lets [SdkBackedDynamoDb]'s extension seam inherit whatever the delegate was
 * configured with — a profile, SSO, `credential_process`, container credentials — none of which
 * `aws-core` carries, and all of which a JVM developer's laptop relies on.
 */
private class SdkCredentialsProviderBridge(
    private val delegate: aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider,
) : AwsCredentialsProvider {
    override suspend fun resolve(): AwsCredentials {
        val credentials = delegate.resolve()
        return AwsCredentials(
            accessKeyId = credentials.accessKeyId,
            secretAccessKey = credentials.secretAccessKey,
            sessionToken = credentials.sessionToken,
            // smithy-kotlin's Instant exposes seconds and sub-second nanos, not millis.
            expiresAtEpochMillis = credentials.expiration?.let {
                it.epochSeconds * 1_000L + it.nanosecondsOfSecond / 1_000_000L
            },
        )
    }

    /** Never render the wrapped secret. */
    override fun toString(): String = "SdkCredentialsProviderBridge($delegate)"
}

/**
 * Re-throws the SDK's exceptions as this library's.
 *
 * Not cosmetic: the whole point of the type swap is that consumer code — including every `catch`
 * block in the existing integration suites — is written against one hierarchy and does not change
 * again when the implementation flips underneath it.
 *
 * Dispatch is on the **error code**, not on the SDK's class hierarchy, except for the two errors
 * that carry structured payload we have to reach into. A code-driven `when` keeps working when the
 * SDK adds, renames or re-parents a modelled exception.
 */
private inline fun <T> mapSdkErrors(block: () -> T): T = try {
    block()
} catch (e: SdkDynamoDbException) {
    throw e.toAwsKt()
}

private fun SdkDynamoDbException.toAwsKt(): DynamoDbException {
    val status = (sdkErrorMetadata.protocolResponse as? HttpResponse)?.status?.value ?: 400
    val requestId = sdkErrorMetadata.requestId
    val code = sdkErrorMetadata.errorCode ?: this::class.simpleName

    // The service's own message, NOT `message`. smithy-kotlin's ServiceException getter appends
    // ", Request ID: …" to whatever the service said — so using `message` here would make the same
    // error read differently depending on which implementation produced it, which is exactly the
    // observable difference this class exists to avoid. The request id is carried in its own field.
    val text = sdkErrorMetadata.errorMessage ?: message

    return when {
        this is SdkConditionalCheckFailedException ->
            ConditionalCheckFailedException(text, status, item?.toAwsKtItem(), requestId)

        this is SdkTransactionCanceledException ->
            TransactionCanceledException(
                text, status, cancellationReasons.orEmpty().map { it.toAwsKt() }, requestId,
            )

        code == "ProvisionedThroughputExceededException" ->
            ProvisionedThroughputExceededException(text, status, requestId)

        code == "ResourceNotFoundException" -> ResourceNotFoundException(text, status, requestId)
        code == "ValidationException" -> ValidationException(text, status, requestId)
        code == "IdempotentParameterMismatchException" ->
            IdempotentParameterMismatchException(text, status, requestId)

        else -> DynamoDbException(code, text, status, requestId)
    }
}
