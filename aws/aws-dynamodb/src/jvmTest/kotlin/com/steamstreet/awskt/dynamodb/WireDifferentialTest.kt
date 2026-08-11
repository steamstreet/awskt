package com.steamstreet.awskt.dynamodb

import com.steamstreet.dynamokt.AttributeValue
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
// The builder-DSL forms (`client.getItem { }`) are extension functions, not members. Without these
// imports the compiler silently binds to the `(request)` overload and the lambda becomes a Function0.
import aws.sdk.kotlin.services.dynamodb.batchGetItem
import aws.sdk.kotlin.services.dynamodb.batchWriteItem
import aws.sdk.kotlin.services.dynamodb.createTable
import aws.sdk.kotlin.services.dynamodb.deleteItem
import aws.sdk.kotlin.services.dynamodb.describeTable
import aws.sdk.kotlin.services.dynamodb.getItem
import aws.sdk.kotlin.services.dynamodb.putItem
import aws.sdk.kotlin.services.dynamodb.query
import aws.sdk.kotlin.services.dynamodb.scan
import aws.sdk.kotlin.services.dynamodb.transactGetItems
import aws.sdk.kotlin.services.dynamodb.transactWriteItems
import aws.sdk.kotlin.services.dynamodb.updateItem
import aws.smithy.kotlin.runtime.client.ProtocolRequestInterceptorContext
import aws.smithy.kotlin.runtime.http.HttpBody
import aws.smithy.kotlin.runtime.http.interceptors.HttpInterceptor
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import com.steamstreet.awskt.core.awsJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue as SdkAttributeValue

/**
 * Compares our serialized request bodies against the real `aws.sdk.kotlin` client's, byte for byte
 * (structurally, as JSON).
 *
 * This is the oracle that validates the hand-written DTOs. The vectors in `aws-signing` prove the
 * *signature* is right; this proves the *payload* is. Without it, a wrong `@SerialName` produces a
 * request that signs perfectly and means something else — or nothing — to DynamoDB.
 *
 * ### How it works
 *
 * A `HttpInterceptor` installed on the real SDK client captures the fully-serialized request at
 * `readBeforeTransmit` and throws a sentinel to short-circuit before any network I/O. That hook is
 * public, version-stable API; implementing `HttpClientEngine` instead would mean extending an
 * `@InternalApi` base and breaking on SDK upgrades.
 *
 * jvmTest-only. `aws.sdk.kotlin` never reaches `commonMain` — the entire point of the module is
 * that production code does not need it.
 */
class WireDifferentialTest {

    private class ShortCircuit : RuntimeException("captured; not sending")

    private class CapturingInterceptor : HttpInterceptor {
        var captured: HttpRequest? = null

        override fun readBeforeTransmit(context: ProtocolRequestInterceptorContext<Any, HttpRequest>) {
            captured = context.protocolRequest
            throw ShortCircuit()
        }
    }

    /** Runs an SDK operation, captures its serialized body, and returns it as JSON. */
    private fun sdkBody(block: suspend (DynamoDbClient) -> Unit): JsonElement {
        val interceptor = CapturingInterceptor()
        val client = DynamoDbClient {
            region = "us-west-2"
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = "AKIDEXAMPLE"
                secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
            }
            interceptors += interceptor
        }

        client.use {
            runBlocking {
                try {
                    block(it)
                } catch (e: Throwable) {
                    // The sentinel arrives wrapped by the SDK's error handling.
                    if (generateSequence(e) { it.cause }.none { it is ShortCircuit }) throw e
                }
            }
        }

        val body = interceptor.captured?.body ?: fail("the SDK request was never captured")
        val bytes = (body as? HttpBody.Bytes)?.bytes() ?: fail("expected an in-memory body, got $body")
        return Json.parseToJsonElement(bytes.decodeToString())
    }

    private fun <T> ourBody(value: T, serializer: KSerializer<T>): JsonElement =
        Json.parseToJsonElement(awsJson.encodeToString(serializer, value))

    private fun <T> assertSameWire(
        operation: String,
        ours: T,
        serializer: KSerializer<T>,
        sdkCall: suspend (DynamoDbClient) -> Unit,
    ) {
        assertEquals(sdkBody(sdkCall), ourBody(ours, serializer), "$operation request body")
    }

    // -- Operations -----------------------------------------------------------------------------

    @Test
    fun getItemMatchesTheSdk() = assertSameWire(
        "GetItem",
        GetItemRequest(
            tableName = "my-table",
            key = mapOf("pk" to AttributeValue.S("a"), "sk" to AttributeValue.N("1")),
            consistentRead = true,
            projectionExpression = "#a, #b",
            expressionAttributeNames = mapOf("#a" to "alpha", "#b" to "beta"),
        ),
        GetItemRequest.serializer(),
    ) { sdk ->
        sdk.getItem {
            tableName = "my-table"
            key = mapOf("pk" to SdkAttributeValue.S("a"), "sk" to SdkAttributeValue.N("1"))
            consistentRead = true
            projectionExpression = "#a, #b"
            expressionAttributeNames = mapOf("#a" to "alpha", "#b" to "beta")
        }
    }

    /** Exercises every AttributeValue variant through the SDK's own serializer. */
    @Test
    fun putItemWithEveryAttributeValueVariantMatchesTheSdk() = assertSameWire(
        "PutItem",
        PutItemRequest(
            tableName = "t",
            item = mapOf(
                "s" to AttributeValue.S("x"),
                "empty" to AttributeValue.S(""),
                "n" to AttributeValue.N("99999999999999999999999999999999999999"),
                "b" to AttributeValue.B(byteArrayOf(1, 2, 3)),
                "bool" to AttributeValue.Bool(true),
                "nul" to AttributeValue.Null(),
                "ss" to AttributeValue.Ss(listOf("a", "b")),
                "ns" to AttributeValue.Ns(listOf("1", "2")),
                "bs" to AttributeValue.Bs(listOf(byteArrayOf(4), byteArrayOf(5, 6))),
                "l" to AttributeValue.L(listOf(AttributeValue.S("i"), AttributeValue.M(emptyMap()))),
                "m" to AttributeValue.M(mapOf("inner" to AttributeValue.L(emptyList()))),
            ),
        ),
        PutItemRequest.serializer(),
    ) { sdk ->
        sdk.putItem {
            tableName = "t"
            item = mapOf(
                "s" to SdkAttributeValue.S("x"),
                "empty" to SdkAttributeValue.S(""),
                "n" to SdkAttributeValue.N("99999999999999999999999999999999999999"),
                "b" to SdkAttributeValue.B(byteArrayOf(1, 2, 3)),
                "bool" to SdkAttributeValue.Bool(true),
                "nul" to SdkAttributeValue.Null(true),
                "ss" to SdkAttributeValue.Ss(listOf("a", "b")),
                "ns" to SdkAttributeValue.Ns(listOf("1", "2")),
                "bs" to SdkAttributeValue.Bs(listOf(byteArrayOf(4), byteArrayOf(5, 6))),
                "l" to SdkAttributeValue.L(listOf(SdkAttributeValue.S("i"), SdkAttributeValue.M(emptyMap()))),
                "m" to SdkAttributeValue.M(mapOf("inner" to SdkAttributeValue.L(emptyList()))),
            )
        }
    }

    @Test
    fun updateItemMatchesTheSdk() = assertSameWire(
        "UpdateItem",
        UpdateItemRequest(
            tableName = "t",
            key = mapOf("pk" to AttributeValue.S("a")),
            updateExpression = "SET #n = :v ADD #c :one",
            conditionExpression = "attribute_exists(#n)",
            expressionAttributeNames = mapOf("#n" to "name", "#c" to "count"),
            expressionAttributeValues = mapOf(":v" to AttributeValue.S("v"), ":one" to AttributeValue.N("1")),
            returnValues = ReturnValue.AllNew,
        ),
        UpdateItemRequest.serializer(),
    ) { sdk ->
        sdk.updateItem {
            tableName = "t"
            key = mapOf("pk" to SdkAttributeValue.S("a"))
            updateExpression = "SET #n = :v ADD #c :one"
            conditionExpression = "attribute_exists(#n)"
            expressionAttributeNames = mapOf("#n" to "name", "#c" to "count")
            expressionAttributeValues = mapOf(":v" to SdkAttributeValue.S("v"), ":one" to SdkAttributeValue.N("1"))
            returnValues = aws.sdk.kotlin.services.dynamodb.model.ReturnValue.AllNew
        }
    }

    @Test
    fun queryMatchesTheSdk() = assertSameWire(
        "Query",
        QueryRequest(
            tableName = "t",
            indexName = "gsi1",
            keyConditionExpression = "#pk = :pk",
            filterExpression = "#x > :y",
            expressionAttributeNames = mapOf("#pk" to "pk", "#x" to "x"),
            expressionAttributeValues = mapOf(":pk" to AttributeValue.S("a"), ":y" to AttributeValue.N("5")),
            limit = 25,
            scanIndexForward = false,
            exclusiveStartKey = mapOf("pk" to AttributeValue.S("start")),
        ),
        QueryRequest.serializer(),
    ) { sdk ->
        sdk.query {
            tableName = "t"
            indexName = "gsi1"
            keyConditionExpression = "#pk = :pk"
            filterExpression = "#x > :y"
            expressionAttributeNames = mapOf("#pk" to "pk", "#x" to "x")
            expressionAttributeValues = mapOf(":pk" to SdkAttributeValue.S("a"), ":y" to SdkAttributeValue.N("5"))
            limit = 25
            scanIndexForward = false
            exclusiveStartKey = mapOf("pk" to SdkAttributeValue.S("start"))
        }
    }

    @Test
    fun deleteItemMatchesTheSdk() = assertSameWire(
        "DeleteItem",
        DeleteItemRequest(
            tableName = "t",
            key = mapOf("pk" to AttributeValue.S("a")),
            conditionExpression = "attribute_exists(pk)",
            returnValues = ReturnValue.AllOld,
        ),
        DeleteItemRequest.serializer(),
    ) { sdk ->
        sdk.deleteItem {
            tableName = "t"
            key = mapOf("pk" to SdkAttributeValue.S("a"))
            conditionExpression = "attribute_exists(pk)"
            returnValues = aws.sdk.kotlin.services.dynamodb.model.ReturnValue.AllOld
        }
    }

    @Test
    fun scanMatchesTheSdk() = assertSameWire(
        "Scan",
        ScanRequest(
            tableName = "t",
            filterExpression = "#x = :y",
            expressionAttributeNames = mapOf("#x" to "x"),
            expressionAttributeValues = mapOf(":y" to AttributeValue.S("v")),
            segment = 0,
            totalSegments = 4,
        ),
        ScanRequest.serializer(),
    ) { sdk ->
        sdk.scan {
            tableName = "t"
            filterExpression = "#x = :y"
            expressionAttributeNames = mapOf("#x" to "x")
            expressionAttributeValues = mapOf(":y" to SdkAttributeValue.S("v"))
            segment = 0
            totalSegments = 4
        }
    }

    @Test
    fun batchGetItemMatchesTheSdk() = assertSameWire(
        "BatchGetItem",
        BatchGetItemRequest(
            mapOf(
                "t" to KeysAndAttributes(
                    keys = listOf(mapOf("pk" to AttributeValue.S("a")), mapOf("pk" to AttributeValue.S("b"))),
                    consistentRead = true,
                    projectionExpression = "#a",
                    expressionAttributeNames = mapOf("#a" to "alpha"),
                ),
            ),
        ),
        BatchGetItemRequest.serializer(),
    ) { sdk ->
        sdk.batchGetItem {
            requestItems = mapOf(
                "t" to aws.sdk.kotlin.services.dynamodb.model.KeysAndAttributes {
                    keys = listOf(
                        mapOf("pk" to SdkAttributeValue.S("a")),
                        mapOf("pk" to SdkAttributeValue.S("b")),
                    )
                    consistentRead = true
                    projectionExpression = "#a"
                    expressionAttributeNames = mapOf("#a" to "alpha")
                },
            )
        }
    }

    @Test
    fun batchWriteItemMatchesTheSdk() = assertSameWire(
        "BatchWriteItem",
        BatchWriteItemRequest(
            mapOf(
                "t" to listOf(
                    WriteRequest(putRequest = PutRequest(mapOf("pk" to AttributeValue.S("a")))),
                    WriteRequest(deleteRequest = DeleteRequest(mapOf("pk" to AttributeValue.S("b")))),
                ),
            ),
        ),
        BatchWriteItemRequest.serializer(),
    ) { sdk ->
        sdk.batchWriteItem {
            requestItems = mapOf(
                "t" to listOf(
                    aws.sdk.kotlin.services.dynamodb.model.WriteRequest {
                        putRequest = aws.sdk.kotlin.services.dynamodb.model.PutRequest {
                            item = mapOf("pk" to SdkAttributeValue.S("a"))
                        }
                    },
                    aws.sdk.kotlin.services.dynamodb.model.WriteRequest {
                        deleteRequest = aws.sdk.kotlin.services.dynamodb.model.DeleteRequest {
                            key = mapOf("pk" to SdkAttributeValue.S("b"))
                        }
                    },
                ),
            )
        }
    }

    @Test
    fun transactWriteItemsMatchesTheSdk() = assertSameWire(
        "TransactWriteItems",
        TransactWriteItemsRequest(
            transactItems = listOf(
                TransactWriteItem(put = TransactPut("t", mapOf("pk" to AttributeValue.S("a")))),
                TransactWriteItem(
                    update = TransactUpdate(
                        tableName = "t",
                        key = mapOf("pk" to AttributeValue.S("b")),
                        updateExpression = "SET #x = :y",
                        expressionAttributeNames = mapOf("#x" to "x"),
                        expressionAttributeValues = mapOf(":y" to AttributeValue.N("1")),
                    ),
                ),
                TransactWriteItem(delete = TransactDelete("t", mapOf("pk" to AttributeValue.S("c")))),
            ),
            clientRequestToken = "0123456789abcdef0123456789abcdef0123",
        ),
        TransactWriteItemsRequest.serializer(),
    ) { sdk ->
        sdk.transactWriteItems {
            transactItems = listOf(
                aws.sdk.kotlin.services.dynamodb.model.TransactWriteItem {
                    put = aws.sdk.kotlin.services.dynamodb.model.Put {
                        tableName = "t"; item = mapOf("pk" to SdkAttributeValue.S("a"))
                    }
                },
                aws.sdk.kotlin.services.dynamodb.model.TransactWriteItem {
                    update = aws.sdk.kotlin.services.dynamodb.model.Update {
                        tableName = "t"
                        key = mapOf("pk" to SdkAttributeValue.S("b"))
                        updateExpression = "SET #x = :y"
                        expressionAttributeNames = mapOf("#x" to "x")
                        expressionAttributeValues = mapOf(":y" to SdkAttributeValue.N("1"))
                    }
                },
                aws.sdk.kotlin.services.dynamodb.model.TransactWriteItem {
                    delete = aws.sdk.kotlin.services.dynamodb.model.Delete {
                        tableName = "t"; key = mapOf("pk" to SdkAttributeValue.S("c"))
                    }
                },
            )
            clientRequestToken = "0123456789abcdef0123456789abcdef0123"
        }
    }

    @Test
    fun transactGetItemsMatchesTheSdk() = assertSameWire(
        "TransactGetItems",
        TransactGetItemsRequest(
            listOf(TransactGetItem(Get("t", mapOf("pk" to AttributeValue.S("a")), projectionExpression = "#a",
                expressionAttributeNames = mapOf("#a" to "alpha")))),
        ),
        TransactGetItemsRequest.serializer(),
    ) { sdk ->
        sdk.transactGetItems {
            transactItems = listOf(
                aws.sdk.kotlin.services.dynamodb.model.TransactGetItem {
                    get = aws.sdk.kotlin.services.dynamodb.model.Get {
                        tableName = "t"
                        key = mapOf("pk" to SdkAttributeValue.S("a"))
                        projectionExpression = "#a"
                        expressionAttributeNames = mapOf("#a" to "alpha")
                    }
                },
            )
        }
    }

    /**
     * The empty-collection invariant, verified against the SDK rather than against our own belief:
     * the SDK omits an unset map entirely, and so must we.
     */
    @Test
    fun omittedCollectionsMatchTheSdksOmissions() = assertSameWire(
        "UpdateItem (no expression maps)",
        UpdateItemRequest(
            tableName = "t",
            key = mapOf("pk" to AttributeValue.S("a")),
            updateExpression = "REMOVE x",
            expressionAttributeNames = emptyMap<String, String>().orNullIfEmpty(),
            expressionAttributeValues = emptyMap<String, AttributeValue>().orNullIfEmpty(),
        ),
        UpdateItemRequest.serializer(),
    ) { sdk ->
        sdk.updateItem {
            tableName = "t"
            key = mapOf("pk" to SdkAttributeValue.S("a"))
            updateExpression = "REMOVE x"
        }
    }

    /**
     * The most structurally complex request in the set — nested GSIs, key schemas, projections and
     * a stream spec — and the one the existing integration suites call through the seam being
     * replaced.
     */
    @Test
    fun createTableWithIndexesAndStreamsMatchesTheSdk() = assertSameWire(
        "CreateTable",
        CreateTableRequest(
            tableName = "t",
            attributeDefinitions = listOf(
                AttributeDefinition("pk", ScalarAttributeType.S),
                AttributeDefinition("sk", ScalarAttributeType.S),
                AttributeDefinition("gsi1pk", ScalarAttributeType.S),
            ),
            keySchema = listOf(
                KeySchemaElement("pk", KeyType.Hash),
                KeySchemaElement("sk", KeyType.Range),
            ),
            billingMode = BillingMode.PayPerRequest,
            globalSecondaryIndexes = listOf(
                GlobalSecondaryIndex(
                    indexName = "gsi1",
                    keySchema = listOf(KeySchemaElement("gsi1pk", KeyType.Hash)),
                    projection = Projection(ProjectionType.Include, listOf("a", "b")),
                ),
            ),
            streamSpecification = StreamSpecification(true, StreamViewType.NewAndOldImages),
        ),
        CreateTableRequest.serializer(),
    ) { sdk ->
        sdk.createTable {
            tableName = "t"
            attributeDefinitions = listOf(
                aws.sdk.kotlin.services.dynamodb.model.AttributeDefinition {
                    attributeName = "pk"
                    attributeType = aws.sdk.kotlin.services.dynamodb.model.ScalarAttributeType.S
                },
                aws.sdk.kotlin.services.dynamodb.model.AttributeDefinition {
                    attributeName = "sk"
                    attributeType = aws.sdk.kotlin.services.dynamodb.model.ScalarAttributeType.S
                },
                aws.sdk.kotlin.services.dynamodb.model.AttributeDefinition {
                    attributeName = "gsi1pk"
                    attributeType = aws.sdk.kotlin.services.dynamodb.model.ScalarAttributeType.S
                },
            )
            keySchema = listOf(
                aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement {
                    attributeName = "pk"
                    keyType = aws.sdk.kotlin.services.dynamodb.model.KeyType.Hash
                },
                aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement {
                    attributeName = "sk"
                    keyType = aws.sdk.kotlin.services.dynamodb.model.KeyType.Range
                },
            )
            billingMode = aws.sdk.kotlin.services.dynamodb.model.BillingMode.PayPerRequest
            globalSecondaryIndexes = listOf(
                aws.sdk.kotlin.services.dynamodb.model.GlobalSecondaryIndex {
                    indexName = "gsi1"
                    keySchema = listOf(
                        aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement {
                            attributeName = "gsi1pk"
                            keyType = aws.sdk.kotlin.services.dynamodb.model.KeyType.Hash
                        },
                    )
                    projection = aws.sdk.kotlin.services.dynamodb.model.Projection {
                        projectionType = aws.sdk.kotlin.services.dynamodb.model.ProjectionType.Include
                        nonKeyAttributes = listOf("a", "b")
                    }
                },
            )
            streamSpecification = aws.sdk.kotlin.services.dynamodb.model.StreamSpecification {
                streamEnabled = true
                streamViewType = aws.sdk.kotlin.services.dynamodb.model.StreamViewType.NewAndOldImages
            }
        }
    }

    @Test
    fun describeTableMatchesTheSdk() = assertSameWire(
        "DescribeTable",
        DescribeTableRequest("t"),
        DescribeTableRequest.serializer(),
    ) { sdk -> sdk.describeTable { tableName = "t" } }

    /**
     * `ReturnValuesOnConditionCheckFailure` is what makes `ConditionalCheckFailedException.item`
     * reachable at all — DynamoDB returns the losing item only when asked, and only in the error
     * body. Asserted on both a single-item write and a transact item, since the two carry the field
     * on different shapes.
     */
    @Test
    fun returnValuesOnConditionCheckFailureMatchesTheSdk() = assertSameWire(
        "PutItem (ALL_OLD on condition failure)",
        PutItemRequest(
            tableName = "t",
            item = mapOf("pk" to AttributeValue.S("a")),
            conditionExpression = "attribute_not_exists(pk)",
            returnValuesOnConditionCheckFailure = ReturnValuesOnConditionCheckFailure.AllOld,
        ),
        PutItemRequest.serializer(),
    ) { sdk ->
        sdk.putItem {
            tableName = "t"
            item = mapOf("pk" to SdkAttributeValue.S("a"))
            conditionExpression = "attribute_not_exists(pk)"
            returnValuesOnConditionCheckFailure =
                aws.sdk.kotlin.services.dynamodb.model.ReturnValuesOnConditionCheckFailure.AllOld
        }
    }

    @Test
    fun transactWriteItemsCarriesReturnValuesOnConditionCheckFailure() = assertSameWire(
        "TransactWriteItems (ALL_OLD on condition failure)",
        TransactWriteItemsRequest(
            transactItems = listOf(
                TransactWriteItem(
                    put = TransactPut(
                        tableName = "t",
                        item = mapOf("pk" to AttributeValue.S("a")),
                        conditionExpression = "attribute_not_exists(pk)",
                        returnValuesOnConditionCheckFailure = ReturnValuesOnConditionCheckFailure.AllOld,
                    ),
                ),
            ),
            clientRequestToken = "0123456789abcdef0123456789abcdef0123",
        ),
        TransactWriteItemsRequest.serializer(),
    ) { sdk ->
        sdk.transactWriteItems {
            transactItems = listOf(
                aws.sdk.kotlin.services.dynamodb.model.TransactWriteItem {
                    put = aws.sdk.kotlin.services.dynamodb.model.Put {
                        tableName = "t"
                        item = mapOf("pk" to SdkAttributeValue.S("a"))
                        conditionExpression = "attribute_not_exists(pk)"
                        returnValuesOnConditionCheckFailure =
                            aws.sdk.kotlin.services.dynamodb.model.ReturnValuesOnConditionCheckFailure.AllOld
                    }
                },
            )
            clientRequestToken = "0123456789abcdef0123456789abcdef0123"
        }
    }

    /** Guards the harness itself: if it stopped capturing, every comparison above would be vacuous. */
    @Test
    fun theHarnessActuallyCapturesARequest() {
        val body = sdkBody { it.getItem { tableName = "t"; key = mapOf("pk" to SdkAttributeValue.S("a")) } }
        assertTrue(body.toString().contains("TableName"), "expected a real serialized body, got $body")
    }
}
