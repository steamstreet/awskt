package com.steamstreet.awskt.dynamodb

import com.steamstreet.dynamokt.AttributeValueSerializer
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsProtocol
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.AwsServiceException
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.awsHttpClient
import com.steamstreet.awskt.core.awsJson
import com.steamstreet.awskt.core.callJson
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.core.resolveRegion
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

/** DynamoDB's AWS-JSON 1.0 dialect. Public so a downstream service module can reuse the shape. */
public val DYNAMODB_PROTOCOL: AwsProtocol =
    AwsProtocol.awsJson1_0(endpointPrefix = "dynamodb", targetPrefix = "DynamoDB_20120810")

/**
 * A DynamoDB client.
 *
 * ### Extending it
 *
 * This interface intentionally exposes [client]. The twelve operations below have no privileged
 * access — each is a `callJson` on that same object — so an operation this library does not ship
 * can be added downstream as an extension function with identical signing, retry and error
 * handling:
 *
 * ```kotlin
 * @Serializable class DescribeLimitsRequest
 * @Serializable class DescribeLimitsResponse(
 *     @SerialName("TableMaxWriteCapacityUnits") val maxWrite: Long? = null,
 * )
 *
 * suspend fun DynamoDb.describeLimits(): DescribeLimitsResponse =
 *     client.callJson(
 *         "DescribeLimits",
 *         DescribeLimitsRequest(),
 *         DescribeLimitsRequest.serializer(),
 *         DescribeLimitsResponse.serializer(),
 *     )
 * ```
 *
 * `DynamoDbExtensibilityTest` exercises exactly that, from outside the client's own file, so the
 * seam is proven rather than merely documented.
 */
public interface DynamoDb : AutoCloseable {
    /** The signed transport. Public because it is the extension seam — see the interface KDoc. */
    public val client: AwsServiceClient

    public suspend fun getItem(request: GetItemRequest): GetItemResponse
    public suspend fun putItem(request: PutItemRequest): PutItemResponse
    public suspend fun updateItem(request: UpdateItemRequest): UpdateItemResponse
    public suspend fun deleteItem(request: DeleteItemRequest): DeleteItemResponse
    public suspend fun query(request: QueryRequest): QueryResponse
    public suspend fun scan(request: ScanRequest): ScanResponse
    public suspend fun batchGetItem(request: BatchGetItemRequest): BatchGetItemResponse
    public suspend fun batchWriteItem(request: BatchWriteItemRequest): BatchWriteItemResponse
    public suspend fun transactWriteItems(request: TransactWriteItemsRequest): TransactWriteItemsResponse
    public suspend fun transactGetItems(request: TransactGetItemsRequest): TransactGetItemsResponse

    // Control plane. `createTable` is not test-only convenience: the existing integration suites
    // call it *through this seam*, so the headline success criterion needs it to exist.
    public suspend fun createTable(request: CreateTableRequest): CreateTableResponse
    public suspend fun describeTable(request: DescribeTableRequest): DescribeTableResponse
    public suspend fun deleteTable(request: DeleteTableRequest): DeleteTableResponse
}

/** Configuration for [DynamoDb]. */
public class DynamoDbConfig {
    public var region: String? = null
    public var endpointUrl: String? = null
    public var credentialsProvider: AwsCredentialsProvider? = null
    public var httpClient: HttpClient? = null
    public var retryConfig: RetryConfig = RetryConfig()

    /** CA bundle for the native Curl engine. Null uses the system trust store. */
    public var caInfo: String? = null
}

/** Builds a DynamoDB client. */
public fun DynamoDb(configure: DynamoDbConfig.() -> Unit = {}): DynamoDb {
    val config = DynamoDbConfig().apply(configure)
    val region = resolveRegion(config.region)
    // Bind the *effective* client once and hand the same reference to both the transport and the
    // close path. Passing `config.httpClient` to `DefaultDynamoDb` instead meant that in the only
    // case where `ownsHttpClient` is true — the caller supplied none, so we built one — the
    // reference was null and `close()` was a null-safe no-op, leaking the client we had just
    // created. The flag was right; the reference was not.
    val httpClient = config.httpClient ?: awsHttpClient(config.caInfo)
    return DefaultDynamoDb(
        client = AwsServiceClient(
            httpClient = httpClient,
            credentialsProvider = config.credentialsProvider ?: defaultCredentialsProvider(),
            endpoint = resolveEndpoint("dynamodb", region, config.endpointUrl),
            region = region,
            protocol = DYNAMODB_PROTOCOL,
            retryConfig = config.retryConfig,
        ),
        ownsHttpClient = config.httpClient == null,
        httpClient = httpClient,
    )
}

internal class DefaultDynamoDb(
    override val client: AwsServiceClient,
    private val ownsHttpClient: Boolean = false,
    private val httpClient: HttpClient? = null,
) : DynamoDb {

    private suspend inline fun <reified Req, reified Res> call(
        operation: String,
        request: Req,
        serializer: kotlinx.serialization.KSerializer<Req>,
        deserializer: kotlinx.serialization.KSerializer<Res>,
        safety: OperationSafety,
    ): Res = mapErrors {
        client.callJson(operation, request, serializer, deserializer, json, safety)
    }

    override suspend fun getItem(request: GetItemRequest): GetItemResponse =
        call("GetItem", request, GetItemRequest.serializer(), GetItemResponse.serializer(), OperationSafety.IDEMPOTENT)

    override suspend fun putItem(request: PutItemRequest): PutItemResponse =
        // An unconditional overwrite replays to the same end state; a conditional one does not.
        // See [writeSafety].
        call(
            "PutItem", request, PutItemRequest.serializer(), PutItemResponse.serializer(),
            writeSafety(request.conditionExpression, request.returnValues),
        )

    override suspend fun updateItem(request: UpdateItemRequest): UpdateItemResponse =
        // Unconditionally NOT idempotent, and deliberately not routed through `writeSafety`: `ADD`
        // and `list_append` double-apply if the first attempt landed, whatever the request's
        // condition and `ReturnValues` say.
        call(
            "UpdateItem", request, UpdateItemRequest.serializer(), UpdateItemResponse.serializer(),
            OperationSafety.NOT_IDEMPOTENT,
        )

    override suspend fun deleteItem(request: DeleteItemRequest): DeleteItemResponse =
        // Same rule as `putItem` — a conditional delete is not replayable. See [writeSafety].
        call(
            "DeleteItem", request, DeleteItemRequest.serializer(), DeleteItemResponse.serializer(),
            writeSafety(request.conditionExpression, request.returnValues),
        )

    override suspend fun query(request: QueryRequest): QueryResponse =
        call("Query", request, QueryRequest.serializer(), QueryResponse.serializer(), OperationSafety.IDEMPOTENT)

    override suspend fun scan(request: ScanRequest): ScanResponse =
        call("Scan", request, ScanRequest.serializer(), ScanResponse.serializer(), OperationSafety.IDEMPOTENT)

    override suspend fun batchGetItem(request: BatchGetItemRequest): BatchGetItemResponse =
        call(
            "BatchGetItem", request, BatchGetItemRequest.serializer(), BatchGetItemResponse.serializer(),
            OperationSafety.IDEMPOTENT,
        )

    override suspend fun batchWriteItem(request: BatchWriteItemRequest): BatchWriteItemResponse =
        call(
            "BatchWriteItem", request, BatchWriteItemRequest.serializer(), BatchWriteItemResponse.serializer(),
            OperationSafety.IDEMPOTENT,
        )

    override suspend fun transactWriteItems(
        request: TransactWriteItemsRequest,
    ): TransactWriteItemsResponse {
        // Materialized BEFORE the retry loop and reused verbatim for every attempt. The retry
        // policy explicitly retries TransactionInProgressException; without a stable token, doing
        // so would re-execute the transaction rather than resume it.
        val withToken = request.clientRequestToken?.let { request }
            ?: request.copy(clientRequestToken = newClientRequestToken())
        return call(
            "TransactWriteItems", withToken, TransactWriteItemsRequest.serializer(),
            TransactWriteItemsResponse.serializer(), OperationSafety.IDEMPOTENT,
        )
    }

    override suspend fun transactGetItems(request: TransactGetItemsRequest): TransactGetItemsResponse =
        call(
            "TransactGetItems", request, TransactGetItemsRequest.serializer(),
            TransactGetItemsResponse.serializer(), OperationSafety.IDEMPOTENT,
        )

    override suspend fun createTable(request: CreateTableRequest): CreateTableResponse =
        // NOT idempotent, for the reason `putItem` with a condition is not. It is true that a
        // second CreateTable cannot duplicate the table — but that is safety of the *end state*,
        // not of the *result the caller sees*. If the first attempt created the table and the
        // response was lost mid-flight, the replay returns ResourceInUseException, and the caller
        // is told its create failed when the table exists and is theirs. Reporting a table it
        // owns as one somebody else already took is the worse of the two failures, and it is
        // silent: nothing distinguishes it from a genuine name collision.
        call(
            "CreateTable", request, CreateTableRequest.serializer(), CreateTableResponse.serializer(),
            OperationSafety.NOT_IDEMPOTENT,
        )

    override suspend fun describeTable(request: DescribeTableRequest): DescribeTableResponse =
        call(
            "DescribeTable", request, DescribeTableRequest.serializer(), DescribeTableResponse.serializer(),
            OperationSafety.IDEMPOTENT,
        )

    override suspend fun deleteTable(request: DeleteTableRequest): DeleteTableResponse =
        // Mirror image of `createTable`: the replay of a delete that already landed returns
        // ResourceNotFoundException, which is indistinguishable from "the table was never there".
        call(
            "DeleteTable", request, DeleteTableRequest.serializer(), DeleteTableResponse.serializer(),
            OperationSafety.NOT_IDEMPOTENT,
        )

    override fun close() {
        if (ownsHttpClient) httpClient?.close()
    }

    private companion object {
        val json: Json = com.steamstreet.awskt.core.awsJson
    }
}

/**
 * Whether a single-item write may be replayed after an **ambiguous** transport failure — one where
 * the bytes may or may not have reached DynamoDB.
 *
 * Derived from the request, not from the operation name, because that is where the property
 * actually lives. `PutItem` was previously hardcoded [OperationSafety.IDEMPOTENT] on the reasoning
 * "a full overwrite: replaying produces the same end state". That reasoning holds for exactly the
 * request shape it was written against — no condition, no returned values — and silently fails for
 * the other two:
 *
 * - **A condition expression.** A create guarded by `attribute_not_exists(pk)` succeeds on AWS, the
 *   response is lost mid-flight, the replay evaluates the condition against the item the *first*
 *   attempt wrote, and the caller gets `ConditionalCheckFailedException` for a write that
 *   succeeded. That is not a false alarm the caller can safely ignore: for an idempotency guard or
 *   an optimistic-concurrency check, "somebody else got there first" is precisely the signal it is
 *   there to produce, so the caller does the thing it does on a genuine lost race.
 * - **`ReturnValues` that read prior state.** `ALL_OLD` on the first attempt returns the item as it
 *   was; on the replay it returns what the first attempt just wrote. Same end state, different
 *   answer — and the answer is the whole reason the caller asked.
 *
 * `ALL_NEW` / `UPDATED_NEW` describe the post-state, which a replay of an unconditional write
 * reproduces exactly, so they stay replayable. The `when` is exhaustive without an `else` on
 * purpose: a new [ReturnValue] entry must fail to compile here rather than default to replayable.
 *
 * `aws-s3` already reasons this way for `PutObject` with `ifNoneMatch`; this is the same rule
 * arriving in the module that has three ways to trip it instead of one.
 */
internal fun writeSafety(
    conditionExpression: String?,
    returnValues: ReturnValue?,
): OperationSafety {
    if (conditionExpression != null) return OperationSafety.NOT_IDEMPOTENT

    val readsPriorState = when (returnValues) {
        ReturnValue.AllOld, ReturnValue.UpdatedOld -> true
        ReturnValue.AllNew, ReturnValue.UpdatedNew, ReturnValue.None, null -> false
    }
    return if (readsPriorState) OperationSafety.NOT_IDEMPOTENT else OperationSafety.IDEMPOTENT
}

/**
 * DynamoDB's `ClientRequestToken`: exactly 36 characters, matching a UUID's shape.
 *
 * Not prefixed — DynamoDB's maximum is 36, so any prefix pushes it over.
 */
internal fun newClientRequestToken(): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder(36)
    for (i in 0 until 32) {
        if (i == 8 || i == 12 || i == 16 || i == 20) sb.append('-')
        sb.append(hex[Random.nextInt(16)])
    }
    return sb.toString()
}

// -- Typed exceptions --------------------------------------------------------------------------

/**
 * Base for DynamoDB errors.
 *
 * Extends `aws-core`'s [AwsServiceException] so every DynamoDB failure carries `code`,
 * `statusCode`, `requestId` and `extendedRequestId` — done here rather than later because
 * re-parenting a public exception hierarchy after publication is an API revision.
 */
public open class DynamoDbException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
) : AwsServiceException(code, message, statusCode, requestId, extendedRequestId)

/**
 * A condition expression evaluated false.
 *
 * [item] is populated whenever the request asked for it with
 * `ReturnValuesOnConditionCheckFailure = ALL_OLD` — DynamoDB then returns the item that failed the
 * condition inside the *error* body, which is the only place it is ever available. Null otherwise.
 */
public class ConditionalCheckFailedException(
    message: String?,
    statusCode: Int,
    public val item: Item? = null,
    requestId: String? = null,
) : DynamoDbException("ConditionalCheckFailedException", message, statusCode, requestId)

/**
 * An atomic transaction AWS refused.
 *
 * [cancellationReasons] is positional: one entry per `TransactItems` entry, in the same order, with
 * the literal code `"None"` for the items that were fine. That positional correspondence is the
 * whole diagnostic value — dropping the successful entries would misalign every index.
 *
 * These codes are **diagnostic only** and never reach the retry classifier. They include
 * `ThrottlingError` and `ProvisionedThroughputExceeded`, and treating either as retryable would
 * replay a transaction AWS has already permanently refused — see `aws-core`'s `AwsJsonErrorParser`.
 */
public class TransactionCanceledException(
    message: String?,
    statusCode: Int,
    public val cancellationReasons: List<CancellationReason> = emptyList(),
    requestId: String? = null,
) : DynamoDbException("TransactionCanceledException", message, statusCode, requestId)

public class ProvisionedThroughputExceededException(message: String?, statusCode: Int, requestId: String? = null) :
    DynamoDbException("ProvisionedThroughputExceededException", message, statusCode, requestId)

public class ResourceNotFoundException(message: String?, statusCode: Int, requestId: String? = null) :
    DynamoDbException("ResourceNotFoundException", message, statusCode, requestId)

public class ValidationException(message: String?, statusCode: Int, requestId: String? = null) :
    DynamoDbException("ValidationException", message, statusCode, requestId)

/** Explicitly non-retryable — see `aws-core`'s never-retry deny-list. */
public class IdempotentParameterMismatchException(message: String?, statusCode: Int, requestId: String? = null) :
    DynamoDbException("IdempotentParameterMismatchException", message, statusCode, requestId)

/**
 * Maps `aws-core`'s generic error onto DynamoDB's typed hierarchy.
 *
 * Two of these carry structured payload that only exists in the error body, so they are lifted out
 * of [AwsServiceException.rawErrorBody] here — in the module that knows DynamoDB's error schema —
 * rather than in protocol-agnostic `aws-core`.
 *
 * Unknown codes fall through to [DynamoDbException] rather than being swallowed, so an operation
 * added downstream still gets a useful, typed failure without registering anything.
 */
internal inline fun <T> mapErrors(block: () -> T): T = try {
    block()
} catch (e: AwsServiceException) {
    throw when (e.code) {
        "ConditionalCheckFailedException" ->
            ConditionalCheckFailedException(e.message, e.statusCode, errorItem(e.rawErrorBody), e.requestId)

        "TransactionCanceledException" ->
            TransactionCanceledException(
                e.message, e.statusCode, cancellationReasons(e.rawErrorBody), e.requestId,
            )

        "ProvisionedThroughputExceededException" ->
            ProvisionedThroughputExceededException(e.message, e.statusCode, e.requestId)

        "ResourceNotFoundException" -> ResourceNotFoundException(e.message, e.statusCode, e.requestId)
        "ValidationException" -> ValidationException(e.message, e.statusCode, e.requestId)
        "IdempotentParameterMismatchException" ->
            IdempotentParameterMismatchException(e.message, e.statusCode, e.requestId)

        else -> DynamoDbException(e.code, e.message, e.statusCode, e.requestId, e.extendedRequestId)
    }
}

/**
 * The `Item` a `ConditionalCheckFailedException` body carries.
 *
 * Every failure mode degrades to null. Throwing out of the error path would replace a useful
 * service error with a parse error, which is strictly worse for whoever has to debug it — the same
 * rule `aws-core`'s error parsers follow.
 */
internal fun errorItem(body: ByteArray?): Item? = runCatching {
    val obj = awsJson.parseToJsonElement(body?.decodeToString() ?: return null) as? JsonObject
    val item = obj?.get("Item") as? JsonObject ?: return null
    awsJson.decodeFromJsonElement(ITEM_SERIALIZER, item)
}.getOrNull()

/** The positional `CancellationReasons` of a `TransactionCanceledException` body. Degrades to empty. */
internal fun cancellationReasons(body: ByteArray?): List<CancellationReason> = runCatching {
    val obj = awsJson.parseToJsonElement(body?.decodeToString() ?: return emptyList()) as? JsonObject
    val reasons = obj?.get("CancellationReasons") as? JsonArray ?: return emptyList()
    awsJson.decodeFromJsonElement(CANCELLATION_REASONS_SERIALIZER, reasons)
}.getOrElse { emptyList() }

private val ITEM_SERIALIZER = MapSerializer(String.serializer(), AttributeValueSerializer)
private val CANCELLATION_REASONS_SERIALIZER = ListSerializer(CancellationReason.serializer())

// -- Pagination and batching -------------------------------------------------------------------

/**
 * Pages a query.
 *
 * Termination is `lastEvaluatedKey?.takeIf { it.isNotEmpty() }`, not a null check: DynamoDB can
 * return an **empty map**, and `!= null` loops forever on it. The page is emitted *before* the
 * termination check so the final page is always delivered.
 */
public fun DynamoDb.queryPaged(request: QueryRequest): Flow<QueryResponse> = flow {
    var page = request
    while (true) {
        val response = query(page)
        emit(response)
        val next = response.lastEvaluatedKey?.takeIf { it.isNotEmpty() } ?: return@flow
        page = page.copy(exclusiveStartKey = next)
    }
}

/** Pages a scan. Same empty-map termination rule as [queryPaged]. */
public fun DynamoDb.scanPaged(request: ScanRequest): Flow<ScanResponse> = flow {
    var page = request
    while (true) {
        val response = scan(page)
        emit(response)
        val next = response.lastEvaluatedKey?.takeIf { it.isNotEmpty() } ?: return@flow
        page = page.copy(exclusiveStartKey = next)
    }
}

public fun Flow<QueryResponse>.items(): Flow<Item> = flow {
    collect { page -> page.items?.forEach { emit(it) } }
}

public fun Flow<ScanResponse>.scanItems(): Flow<Item> = flow {
    collect { page -> page.items?.forEach { emit(it) } }
}

/**
 * BatchGetItem, looping `UnprocessedKeys` until everything asked for has been returned.
 *
 * `BatchGetItem` is not `@paginated` in the Smithy model, so there is no SDK paginator to inherit —
 * and reading only `Responses` silently returns **incomplete results** under throttling or the
 * 16 MB response cap. That is a live data-loss bug in the code this replaces.
 */
public suspend fun DynamoDb.batchGetAll(
    tableName: String,
    keys: List<Item>,
    consistentRead: Boolean = false,
    projectionExpression: String? = null,
    expressionAttributeNames: Map<String, String>? = null,
    maxRounds: Int = 10,
): List<Item> {
    val collected = mutableListOf<Item>()

    // The request shape is fixed; only the key set varies per chunk. Stating it once and varying
    // it with copy() means a future field on KeysAndAttributes is picked up by every chunk
    // automatically, rather than needing this loop to be found and updated.
    val template = KeysAndAttributes(
        keys = emptyList(),
        consistentRead = consistentRead.takeIf { it },
        projectionExpression = projectionExpression,
        expressionAttributeNames = expressionAttributeNames.orNullIfEmpty(),
    )

    // 100 is DynamoDB's hard per-call limit for BatchGetItem.
    for (chunk in keys.chunked(100)) {
        var pending: Map<String, KeysAndAttributes> = mapOf(tableName to template.copy(keys = chunk))
        var round = 0
        while (pending.isNotEmpty()) {
            val response = batchGetItem(BatchGetItemRequest(pending))
            response.responses?.get(tableName)?.let(collected::addAll)
            pending = response.unprocessedKeys.orNullIfEmpty() ?: break
            if (++round >= maxRounds) {
                throw DynamoDbException(
                    "UnprocessedKeysRemain",
                    "BatchGetItem still had unprocessed keys after $maxRounds rounds; " +
                        "returning partial results would be silent data loss.",
                    200,
                )
            }
        }
    }
    return collected
}

/**
 * BatchWriteItem, chunking to **25** and looping `UnprocessedItems`.
 *
 * Mirror image of the read bug: the code this replaces sends an unchunked batch and discards the
 * response, so it throws `ValidationException` above 25 items and silently under-deletes whenever a
 * batch is throttled.
 */
public suspend fun DynamoDb.batchWriteAll(
    tableName: String,
    writes: List<WriteRequest>,
    maxRounds: Int = 10,
) {
    for (chunk in writes.chunked(25)) {
        var pending: Map<String, List<WriteRequest>> = mapOf(tableName to chunk)
        var round = 0
        while (pending.isNotEmpty()) {
            val response = batchWriteItem(BatchWriteItemRequest(pending))
            pending = response.unprocessedItems.orNullIfEmpty()?.filterValues { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() } ?: break
            if (++round >= maxRounds) {
                throw DynamoDbException(
                    "UnprocessedItemsRemain",
                    "BatchWriteItem still had unprocessed items after $maxRounds rounds.",
                    200,
                )
            }
        }
    }
}
