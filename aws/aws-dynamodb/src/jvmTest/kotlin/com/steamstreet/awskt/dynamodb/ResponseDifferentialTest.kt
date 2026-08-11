package com.steamstreet.awskt.dynamodb

import com.steamstreet.dynamokt.AttributeValue
import com.steamstreet.dynamokt.AttributeValueSerializer
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
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
import aws.smithy.kotlin.runtime.net.url.Url
import com.sun.net.httpserver.HttpServer
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue as SdkAttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException as SdkConditionalCheckFailed
import aws.sdk.kotlin.services.dynamodb.model.TableDescription as SdkTableDescription
import aws.sdk.kotlin.services.dynamodb.model.TransactionCanceledException as SdkTransactionCanceled

/**
 * The response half of the wire differential: one canned AWS body, two independent deserializers,
 * one structural comparison.
 *
 * `WireDifferentialTest` proves our *requests* mean what the SDK's mean. Nothing proved the reverse
 * direction, and the failure modes there are quieter: a wrong `@SerialName` on a response field
 * does not error, it silently yields `null`, and the caller sees an item that simply has no
 * `LastEvaluatedKey`, or a `ConditionalCheckFailedException` that has lost the item it failed on.
 * The request harness cannot see any of that.
 *
 * ### How it works
 *
 * The SDK side is driven by a one-shot JDK `HttpServer` bound to an ephemeral loopback port, with
 * the client's `endpointUrl` pointed at it. That is entirely public, version-stable API — no
 * interceptor, and in particular no `HttpClientEngine` implementation over an `@InternalApi` base.
 * It also exercises the SDK's *real* deserialization path end to end, headers included, rather than
 * a fragment of it. Our side gets the identical bytes through `MockEngine`.
 *
 * ### On the comparison
 *
 * Both sides are rendered to `JsonElement` by hand-written functions in this file — deliberately
 * not by `AttributeValueSerializer.toJson`. Reusing our production encoder to check our production
 * decoder would let a symmetric mistake cancel itself out; two independently written renderers
 * cannot. The renderers are dumb on purpose, and `JsonObject` equality is order-independent, so a
 * difference in map iteration order is not a false failure.
 *
 * jvmTest-only, like the request harness: `aws.sdk.kotlin` never reaches `commonMain`.
 */
class ResponseDifferentialTest {

    // -- Plumbing -------------------------------------------------------------------------------

    /** Serves [body] with [status] on loopback for the duration of [block]. */
    private fun <T> withCannedResponse(status: Int, body: String, block: (String) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            // The request body must be drained, or the SDK's connection pool can stall on close.
            exchange.requestBody.use { it.readBytes() }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/x-amz-json-1.0")
            exchange.responseHeaders.add("x-amzn-RequestId", "canned-request-id")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    /** Runs [call] against the real SDK, with [body] as the whole HTTP response. */
    private fun <T> throughSdk(body: String, status: Int = 200, call: suspend (DynamoDbClient) -> T): T =
        withCannedResponse(status, body) { url ->
            DynamoDbClient {
                region = "us-west-2"
                endpointUrl = Url.parse(url)
                credentialsProvider = StaticCredentialsProvider {
                    accessKeyId = "AKIDEXAMPLE"
                    secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
                }
            }.use { client -> runBlocking { call(client) } }
        }

    /** Runs [call] against our client, with the identical bytes. */
    private fun <T> throughOurs(body: String, status: Int = 200, call: suspend (DynamoDb) -> T): T =
        runBlocking {
            val db = harnessDynamoDb(DynamoHarness()) { body to HttpStatusCode.fromValue(status) }
            db.use { call(it) }
        }

    // -- Renderers ------------------------------------------------------------------------------

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun tagged(tag: String, value: JsonElement): JsonElement = JsonObject(mapOf(tag to value))

    private fun sdkValue(v: SdkAttributeValue): JsonElement = when (v) {
        is SdkAttributeValue.S -> tagged("S", JsonPrimitive(v.value))
        is SdkAttributeValue.N -> tagged("N", JsonPrimitive(v.value))
        is SdkAttributeValue.B -> tagged("B", JsonPrimitive(b64(v.value)))
        is SdkAttributeValue.Bool -> tagged("BOOL", JsonPrimitive(v.value))
        is SdkAttributeValue.Null -> tagged("NULL", JsonPrimitive(v.value))
        is SdkAttributeValue.Ss -> tagged("SS", JsonArray(v.value.map { JsonPrimitive(it) }))
        is SdkAttributeValue.Ns -> tagged("NS", JsonArray(v.value.map { JsonPrimitive(it) }))
        is SdkAttributeValue.Bs -> tagged("BS", JsonArray(v.value.map { JsonPrimitive(b64(it)) }))
        is SdkAttributeValue.L -> tagged("L", JsonArray(v.value.map { sdkValue(it) }))
        is SdkAttributeValue.M -> tagged("M", JsonObject(v.value.mapValues { sdkValue(it.value) }))
        else -> tagged("SDK_UNKNOWN", JsonPrimitive(v.toString()))
    }

    private fun ourValue(v: AttributeValue): JsonElement = when (v) {
        is AttributeValue.S -> tagged("S", JsonPrimitive(v.value))
        is AttributeValue.N -> tagged("N", JsonPrimitive(v.value))
        is AttributeValue.B -> tagged("B", JsonPrimitive(b64(v.value)))
        is AttributeValue.Bool -> tagged("BOOL", JsonPrimitive(v.value))
        is AttributeValue.Null -> tagged("NULL", JsonPrimitive(v.value))
        is AttributeValue.Ss -> tagged("SS", JsonArray(v.value.map { JsonPrimitive(it) }))
        is AttributeValue.Ns -> tagged("NS", JsonArray(v.value.map { JsonPrimitive(it) }))
        is AttributeValue.Bs -> tagged("BS", JsonArray(v.value.map { JsonPrimitive(b64(it)) }))
        is AttributeValue.L -> tagged("L", JsonArray(v.value.map { ourValue(it) }))
        is AttributeValue.M -> tagged("M", JsonObject(v.value.mapValues { ourValue(it.value) }))
        // Rendered by discriminator so that if one ever appears in the corpus the comparison names
        // it, rather than passing because both sides stringified to the same placeholder.
        is AttributeValue.SdkUnknown -> tagged("SDK_UNKNOWN", JsonPrimitive(v.discriminator))
    }

    private fun sdkItem(item: Map<String, SdkAttributeValue>?): JsonElement =
        item?.let { JsonObject(it.mapValues { (_, v) -> sdkValue(v) }) } ?: JsonNull

    private fun ourItem(item: Item?): JsonElement =
        item?.let { JsonObject(it.mapValues { (_, v) -> ourValue(v) }) } ?: JsonNull

    private fun sdkItems(items: List<Map<String, SdkAttributeValue>>?): JsonElement =
        items?.let { list -> JsonArray(list.map { sdkItem(it) }) } ?: JsonNull

    private fun ourItems(items: List<Item>?): JsonElement =
        items?.let { list -> JsonArray(list.map { ourItem(it) }) } ?: JsonNull

    private fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(pairs.toMap())

    private fun num(value: Int?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull
    private fun num(value: Long?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull
    private fun str(value: String?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull
    private fun bool(value: Boolean?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull

    // -- GetItem --------------------------------------------------------------------------------

    /**
     * The corpus centrepiece: every one of the ten variants in one body, including the three that
     * hand-rolled codecs get wrong — an empty string, an empty map and an empty list — a 38-digit
     * number, a two-blob binary set and an explicit null.
     */
    @Test
    fun getItemWithEveryAttributeValueVariant() {
        val body = """
            {"Item":{
              "s":{"S":"hello"},
              "emptyString":{"S":""},
              "n":{"N":"99999999999999999999999999999999999999"},
              "negativeN":{"N":"-0.00000000000000000000000000000000000001"},
              "b":{"B":"AQIDBP8="},
              "bool":{"BOOL":false},
              "nul":{"NULL":true},
              "ss":{"SS":["a","b",""]},
              "ns":{"NS":["1","-2.5"]},
              "bs":{"BS":["AQI=","Awo="]},
              "emptyList":{"L":[]},
              "emptyMap":{"M":{}},
              "l":{"L":[{"S":"i"},{"N":"2"},{"M":{}}]},
              "m":{"M":{"inner":{"L":[{"NULL":true}]}}}
            }}
        """.trimIndent()

        val sdk = throughSdk(body) { it.getItem { tableName = "t"; key = mapOf("pk" to SdkAttributeValue.S("a")) } }
        val ours = throughOurs(body) { it.getItem(GetItemRequest("t", mapOf("pk" to AttributeValue.S("a")))) }

        assertEquals(sdkItem(sdk.item), ourItem(ours.item), "GetItem.Item")
        // Guards the comparison itself: an assertion between two nulls would pass vacuously.
        assertEquals(14, assertNotNull(ours.item).size, "every attribute must have been decoded")
    }

    /** Deep nesting: the recursion in both codecs, exercised past the first level. */
    @Test
    fun getItemWithDeeplyNestedMapsAndLists() {
        val body = """
            {"Item":{"root":{"M":{"a":{"L":[
              {"M":{"b":{"L":[{"M":{"c":{"L":[{"S":"deep"},{"B":"CQ=="}]}}}]}}},
              {"NULL":true}
            ]}}}}}
        """.trimIndent()

        val sdk = throughSdk(body) { it.getItem { tableName = "t"; key = mapOf("pk" to SdkAttributeValue.S("a")) } }
        val ours = throughOurs(body) { it.getItem(GetItemRequest("t", mapOf("pk" to AttributeValue.S("a")))) }

        assertEquals(sdkItem(sdk.item), ourItem(ours.item), "GetItem.Item (nested)")
        assertTrue(ourItem(ours.item).toString().contains("deep"))
    }

    /** A miss. Both must produce a null item, not an empty map — callers branch on it. */
    @Test
    fun getItemWithNoItemFound() {
        val body = "{}"
        val sdk = throughSdk(body) { it.getItem { tableName = "t"; key = mapOf("pk" to SdkAttributeValue.S("a")) } }
        val ours = throughOurs(body) { it.getItem(GetItemRequest("t", mapOf("pk" to AttributeValue.S("a")))) }

        assertEquals(sdkItem(sdk.item), ourItem(ours.item), "GetItem.Item on a miss")
        assertEquals(JsonNull, ourItem(ours.item))
    }

    // -- Query / Scan ---------------------------------------------------------------------------

    private fun sdkQuery(r: aws.sdk.kotlin.services.dynamodb.model.QueryResponse) = obj(
        "Items" to sdkItems(r.items),
        "Count" to num(r.count),
        "ScannedCount" to num(r.scannedCount),
        "LastEvaluatedKey" to sdkItem(r.lastEvaluatedKey),
    )

    private fun ourQuery(r: QueryResponse) = obj(
        "Items" to ourItems(r.items),
        "Count" to num(r.count),
        "ScannedCount" to num(r.scannedCount),
        "LastEvaluatedKey" to ourItem(r.lastEvaluatedKey),
    )

    @Test
    fun queryWithALastEvaluatedKey() {
        val body = """
            {"Items":[{"pk":{"S":"a"},"v":{"N":"1"}},{"pk":{"S":"b"},"v":{"N":"2"}}],
             "Count":2,"ScannedCount":7,
             "LastEvaluatedKey":{"pk":{"S":"b"},"sk":{"N":"2"}}}
        """.trimIndent()

        val sdk = throughSdk(body) { it.query { tableName = "t" } }
        val ours = throughOurs(body) { it.query(QueryRequest("t")) }

        assertEquals(sdkQuery(sdk), ourQuery(ours), "Query response")
    }

    @Test
    fun queryWithoutALastEvaluatedKey() {
        val body = """{"Items":[{"pk":{"S":"a"}}],"Count":1,"ScannedCount":1}"""

        val sdk = throughSdk(body) { it.query { tableName = "t" } }
        val ours = throughOurs(body) { it.query(QueryRequest("t")) }

        assertEquals(sdkQuery(sdk), ourQuery(ours), "Query response (last page)")
        assertEquals(JsonNull, ourItem(ours.lastEvaluatedKey))
    }

    /**
     * The case the paginator's termination rule exists for.
     *
     * DynamoDB can return `"LastEvaluatedKey":{}`. Both deserializers must yield an **empty map**,
     * not null — a `!= null` termination check on this loops forever, which is exactly why
     * `queryPaged` checks emptiness instead.
     */
    @Test
    fun queryWithAnEmptyLastEvaluatedKey() {
        val body = """{"Items":[{"pk":{"S":"a"}}],"Count":1,"ScannedCount":1,"LastEvaluatedKey":{}}"""

        val sdk = throughSdk(body) { it.query { tableName = "t" } }
        val ours = throughOurs(body) { it.query(QueryRequest("t")) }

        assertEquals(sdkQuery(sdk), ourQuery(ours), "Query response (empty LastEvaluatedKey)")
        assertEquals(emptyMap(), ours.lastEvaluatedKey, "an empty key must decode as empty, not null")
    }

    @Test
    fun scanResponse() {
        val body = """
            {"Items":[{"pk":{"S":"a"},"bin":{"B":"//8="}}],"Count":1,"ScannedCount":100,
             "LastEvaluatedKey":{"pk":{"S":"a"}}}
        """.trimIndent()

        val sdk = throughSdk(body) { it.scan { tableName = "t" } }
        val ours = throughOurs(body) { it.scan(ScanRequest("t")) }

        assertEquals(
            obj(
                "Items" to sdkItems(sdk.items),
                "Count" to num(sdk.count),
                "ScannedCount" to num(sdk.scannedCount),
                "LastEvaluatedKey" to sdkItem(sdk.lastEvaluatedKey),
            ),
            obj(
                "Items" to ourItems(ours.items),
                "Count" to num(ours.count),
                "ScannedCount" to num(ours.scannedCount),
                "LastEvaluatedKey" to ourItem(ours.lastEvaluatedKey),
            ),
            "Scan response",
        )
    }

    // -- Single-item writes ---------------------------------------------------------------------

    @Test
    fun putItemReturningTheOldAttributes() {
        val body = """{"Attributes":{"pk":{"S":"a"},"old":{"N":"1"},"gone":{"NULL":true}}}"""

        val sdk = throughSdk(body) { it.putItem { tableName = "t"; item = mapOf("pk" to SdkAttributeValue.S("a")) } }
        val ours = throughOurs(body) { it.putItem(PutItemRequest("t", mapOf("pk" to AttributeValue.S("a")))) }

        assertEquals(sdkItem(sdk.attributes), ourItem(ours.attributes), "PutItem.Attributes")
    }

    @Test
    fun updateItemReturningTheNewAttributes() {
        val body = """{"Attributes":{"pk":{"S":"a"},"counter":{"N":"42"},"tags":{"SS":["x","y"]}}}"""

        val sdk = throughSdk(body) {
            it.updateItem { tableName = "t"; key = mapOf("pk" to SdkAttributeValue.S("a")) }
        }
        val ours = throughOurs(body) { it.updateItem(UpdateItemRequest("t", mapOf("pk" to AttributeValue.S("a")))) }

        assertEquals(sdkItem(sdk.attributes), ourItem(ours.attributes), "UpdateItem.Attributes")
    }

    @Test
    fun deleteItemReturningTheOldAttributes() {
        val body = """{"Attributes":{"pk":{"S":"a"},"blob":{"B":"AA=="}}}"""

        val sdk = throughSdk(body) {
            it.deleteItem { tableName = "t"; key = mapOf("pk" to SdkAttributeValue.S("a")) }
        }
        val ours = throughOurs(body) { it.deleteItem(DeleteItemRequest("t", mapOf("pk" to AttributeValue.S("a")))) }

        assertEquals(sdkItem(sdk.attributes), ourItem(ours.attributes), "DeleteItem.Attributes")
    }

    // -- Batch ----------------------------------------------------------------------------------

    /**
     * `UnprocessedKeys` is the field whose loss is silent data loss — the caller gets fewer items
     * than it asked for and no error at all. It must survive deserialization on both sides.
     */
    @Test
    fun batchGetItemWithUnprocessedKeys() {
        val body = """
            {"Responses":{"t":[{"pk":{"S":"a"}},{"pk":{"S":"b"}}],"u":[{"pk":{"S":"z"}}]},
             "UnprocessedKeys":{"t":{"Keys":[{"pk":{"S":"c"}}],"ConsistentRead":true,
               "ProjectionExpression":"#a","ExpressionAttributeNames":{"#a":"alpha"}}}}
        """.trimIndent()

        val sdk = throughSdk(body) {
            it.batchGetItem {
                requestItems = mapOf(
                    "t" to aws.sdk.kotlin.services.dynamodb.model.KeysAndAttributes {
                        keys = listOf(mapOf("pk" to SdkAttributeValue.S("a")))
                    },
                )
            }
        }
        val ours = throughOurs(body) {
            it.batchGetItem(
                BatchGetItemRequest(mapOf("t" to KeysAndAttributes(listOf(mapOf("pk" to AttributeValue.S("a")))))),
            )
        }

        assertEquals(
            JsonObject(sdk.responses.orEmpty().mapValues { (_, v) -> sdkItems(v) }),
            JsonObject(ours.responses.orEmpty().mapValues { (_, v) -> ourItems(v) }),
            "BatchGetItem.Responses",
        )
        assertEquals(
            JsonObject(
                sdk.unprocessedKeys.orEmpty().mapValues { (_, v) ->
                    obj(
                        "Keys" to sdkItems(v.keys),
                        "ConsistentRead" to bool(v.consistentRead),
                        "ProjectionExpression" to str(v.projectionExpression),
                        "ExpressionAttributeNames" to JsonObject(
                            v.expressionAttributeNames.orEmpty().mapValues { (_, n) -> JsonPrimitive(n) },
                        ),
                    )
                },
            ),
            JsonObject(
                ours.unprocessedKeys.orEmpty().mapValues { (_, v) ->
                    obj(
                        "Keys" to ourItems(v.keys),
                        "ConsistentRead" to bool(v.consistentRead),
                        "ProjectionExpression" to str(v.projectionExpression),
                        "ExpressionAttributeNames" to JsonObject(
                            v.expressionAttributeNames.orEmpty().mapValues { (_, n) -> JsonPrimitive(n) },
                        ),
                    )
                },
            ),
            "BatchGetItem.UnprocessedKeys",
        )
    }

    /** The write-side mirror: dropping `UnprocessedItems` silently under-writes. */
    @Test
    fun batchWriteItemWithUnprocessedItems() {
        val body = """
            {"UnprocessedItems":{"t":[
              {"PutRequest":{"Item":{"pk":{"S":"a"},"v":{"N":"1"}}}},
              {"DeleteRequest":{"Key":{"pk":{"S":"b"}}}}
            ]}}
        """.trimIndent()

        val sdk = throughSdk(body) {
            it.batchWriteItem {
                requestItems = mapOf(
                    "t" to listOf(
                        aws.sdk.kotlin.services.dynamodb.model.WriteRequest {
                            deleteRequest = aws.sdk.kotlin.services.dynamodb.model.DeleteRequest {
                                key = mapOf("pk" to SdkAttributeValue.S("b"))
                            }
                        },
                    ),
                )
            }
        }
        val ours = throughOurs(body) {
            it.batchWriteItem(
                BatchWriteItemRequest(
                    mapOf("t" to listOf(WriteRequest(deleteRequest = DeleteRequest(mapOf("pk" to AttributeValue.S("b")))))),
                ),
            )
        }

        assertEquals(
            JsonObject(
                sdk.unprocessedItems.orEmpty().mapValues { (_, writes) ->
                    JsonArray(
                        writes.map { w ->
                            obj(
                                "PutRequest" to sdkItem(w.putRequest?.item),
                                "DeleteRequest" to sdkItem(w.deleteRequest?.key),
                            )
                        },
                    )
                },
            ),
            JsonObject(
                ours.unprocessedItems.orEmpty().mapValues { (_, writes) ->
                    JsonArray(
                        writes.map { w ->
                            obj(
                                "PutRequest" to ourItem(w.putRequest?.item),
                                "DeleteRequest" to ourItem(w.deleteRequest?.key),
                            )
                        },
                    )
                },
            ),
            "BatchWriteItem.UnprocessedItems",
        )
    }

    /** `TransactGetItems` returns positional slots, and a miss is an entry with no `Item`. */
    @Test
    fun transactGetItemsWithAMissingSlot() {
        val body = """{"Responses":[{"Item":{"pk":{"S":"a"}}},{},{"Item":{"pk":{"S":"c"},"n":{"N":"3"}}}]}"""

        val sdk = throughSdk(body) {
            it.transactGetItems {
                transactItems = listOf(
                    aws.sdk.kotlin.services.dynamodb.model.TransactGetItem {
                        get = aws.sdk.kotlin.services.dynamodb.model.Get {
                            tableName = "t"; key = mapOf("pk" to SdkAttributeValue.S("a"))
                        }
                    },
                )
            }
        }
        val ours = throughOurs(body) {
            it.transactGetItems(
                TransactGetItemsRequest(listOf(TransactGetItem(Get("t", mapOf("pk" to AttributeValue.S("a")))))),
            )
        }

        assertEquals(
            JsonArray(sdk.responses.orEmpty().map { sdkItem(it.item) }),
            JsonArray(ours.responses.orEmpty().map { ourItem(it.item) }),
            "TransactGetItems.Responses",
        )
    }

    // -- Control plane --------------------------------------------------------------------------

    private val tableBody = """
        {"Table":{"TableName":"t","TableStatus":"ACTIVE","ItemCount":17,"TableSizeBytes":4096,
          "KeySchema":[{"AttributeName":"pk","KeyType":"HASH"},{"AttributeName":"sk","KeyType":"RANGE"}],
          "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"},
                                  {"AttributeName":"sk","AttributeType":"N"}],
          "GlobalSecondaryIndexes":[{"IndexName":"gsi1","IndexStatus":"ACTIVE",
            "KeySchema":[{"AttributeName":"gsi1pk","KeyType":"HASH"}],
            "Projection":{"ProjectionType":"INCLUDE","NonKeyAttributes":["a","b"]}}],
          "StreamSpecification":{"StreamEnabled":true,"StreamViewType":"NEW_AND_OLD_IMAGES"},
          "LatestStreamArn":"arn:aws:dynamodb:us-west-2:1:table/t/stream/2026"}}
    """.trimIndent()

    private fun sdkTable(t: SdkTableDescription?): JsonElement = t?.let {
        obj(
            "TableName" to str(it.tableName),
            "TableStatus" to str(it.tableStatus?.value),
            "ItemCount" to num(it.itemCount),
            "TableSizeBytes" to num(it.tableSizeBytes),
            "KeySchema" to JsonArray(
                it.keySchema.orEmpty().map { k ->
                    obj("AttributeName" to str(k.attributeName), "KeyType" to str(k.keyType.value))
                },
            ),
            "AttributeDefinitions" to JsonArray(
                it.attributeDefinitions.orEmpty().map { a ->
                    obj("AttributeName" to str(a.attributeName), "AttributeType" to str(a.attributeType.value))
                },
            ),
            "GlobalSecondaryIndexes" to JsonArray(
                it.globalSecondaryIndexes.orEmpty().map { g ->
                    obj(
                        "IndexName" to str(g.indexName),
                        "IndexStatus" to str(g.indexStatus?.value),
                        "KeySchema" to JsonArray(
                            g.keySchema.orEmpty().map { k ->
                                obj("AttributeName" to str(k.attributeName), "KeyType" to str(k.keyType.value))
                            },
                        ),
                        "Projection" to obj(
                            "ProjectionType" to str(g.projection?.projectionType?.value),
                            "NonKeyAttributes" to JsonArray(
                                g.projection?.nonKeyAttributes.orEmpty().map { n -> JsonPrimitive(n) },
                            ),
                        ),
                    )
                },
            ),
            "StreamSpecification" to obj(
                "StreamEnabled" to bool(it.streamSpecification?.streamEnabled),
                "StreamViewType" to str(it.streamSpecification?.streamViewType?.value),
            ),
            "LatestStreamArn" to str(it.latestStreamArn),
        )
    } ?: JsonNull

    private fun ourTable(t: TableDescription?): JsonElement = t?.let {
        obj(
            "TableName" to str(it.tableName),
            "TableStatus" to str(it.tableStatus),
            "ItemCount" to num(it.itemCount),
            "TableSizeBytes" to num(it.tableSizeBytes),
            "KeySchema" to JsonArray(
                it.keySchema.orEmpty().map { k ->
                    obj("AttributeName" to str(k.attributeName), "KeyType" to str(wire(k.keyType)))
                },
            ),
            "AttributeDefinitions" to JsonArray(
                it.attributeDefinitions.orEmpty().map { a ->
                    obj("AttributeName" to str(a.attributeName), "AttributeType" to str(a.attributeType.name))
                },
            ),
            "GlobalSecondaryIndexes" to JsonArray(
                it.globalSecondaryIndexes.orEmpty().map { g ->
                    obj(
                        "IndexName" to str(g.indexName),
                        "IndexStatus" to str(g.indexStatus),
                        "KeySchema" to JsonArray(
                            g.keySchema.orEmpty().map { k ->
                                obj("AttributeName" to str(k.attributeName), "KeyType" to str(wire(k.keyType)))
                            },
                        ),
                        "Projection" to obj(
                            "ProjectionType" to str(g.projection?.projectionType?.let(::wire)),
                            "NonKeyAttributes" to JsonArray(
                                g.projection?.nonKeyAttributes.orEmpty().map { n -> JsonPrimitive(n) },
                            ),
                        ),
                    )
                },
            ),
            "StreamSpecification" to obj(
                "StreamEnabled" to bool(it.streamSpecification?.streamEnabled),
                "StreamViewType" to str(it.streamSpecification?.streamViewType?.let(::wire)),
            ),
            "LatestStreamArn" to str(it.latestStreamArn),
        )
    } ?: JsonNull

    /** Our enums carry the wire form on `@SerialName`, so render through the serializer, not `name`. */
    private fun wire(value: KeyType): String = when (value) {
        KeyType.Hash -> "HASH"
        KeyType.Range -> "RANGE"
    }

    private fun wire(value: ProjectionType): String = when (value) {
        ProjectionType.All -> "ALL"
        ProjectionType.KeysOnly -> "KEYS_ONLY"
        ProjectionType.Include -> "INCLUDE"
    }

    private fun wire(value: StreamViewType): String = when (value) {
        StreamViewType.NewImage -> "NEW_IMAGE"
        StreamViewType.OldImage -> "OLD_IMAGE"
        StreamViewType.NewAndOldImages -> "NEW_AND_OLD_IMAGES"
        StreamViewType.KeysOnly -> "KEYS_ONLY"
    }

    @Test
    fun describeTableWithIndexesAndAStream() {
        val sdk = throughSdk(tableBody) { it.describeTable { tableName = "t" } }
        val ours = throughOurs(tableBody) { it.describeTable(DescribeTableRequest("t")) }

        assertEquals(sdkTable(sdk.table), ourTable(ours.table), "DescribeTable.Table")
    }

    @Test
    fun createTableDescription() {
        val body = tableBody.replace("\"Table\":", "\"TableDescription\":")
            .replace("\"ACTIVE\"", "\"CREATING\"")

        val sdk = throughSdk(body) {
            it.createTable {
                tableName = "t"
                attributeDefinitions = listOf(
                    aws.sdk.kotlin.services.dynamodb.model.AttributeDefinition {
                        attributeName = "pk"
                        attributeType = aws.sdk.kotlin.services.dynamodb.model.ScalarAttributeType.S
                    },
                )
                keySchema = listOf(
                    aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement {
                        attributeName = "pk"
                        keyType = aws.sdk.kotlin.services.dynamodb.model.KeyType.Hash
                    },
                )
                billingMode = aws.sdk.kotlin.services.dynamodb.model.BillingMode.PayPerRequest
            }
        }
        val ours = throughOurs(body) {
            it.createTable(
                CreateTableRequest(
                    tableName = "t",
                    attributeDefinitions = listOf(AttributeDefinition("pk", ScalarAttributeType.S)),
                    keySchema = listOf(KeySchemaElement("pk", KeyType.Hash)),
                    billingMode = BillingMode.PayPerRequest,
                ),
            )
        }

        assertEquals(sdkTable(sdk.tableDescription), ourTable(ours.tableDescription), "CreateTable.TableDescription")
    }

    // -- Errors ---------------------------------------------------------------------------------

    /**
     * The item that failed the condition lives **in the error body** and nowhere else. Dropping it
     * costs the caller the only copy of the state it lost the race to — and drops silently, since
     * the exception is still thrown with the right type and message.
     */
    @Test
    fun conditionalCheckFailedCarriesTheItem() {
        val body = """
            {"__type":"com.amazonaws.dynamodb.v20120810#ConditionalCheckFailedException",
             "message":"The conditional request failed",
             "Item":{"pk":{"S":"a"},"version":{"N":"7"},"blob":{"B":"AQI="}}}
        """.trimIndent()

        val sdkError = assertFailsWith<SdkConditionalCheckFailed> {
            throughSdk(body, status = 400) {
                it.putItem { tableName = "t"; item = mapOf("pk" to SdkAttributeValue.S("a")) }
            }
        }
        val ourError = assertFailsWith<ConditionalCheckFailedException> {
            throughOurs(body, status = 400) { it.putItem(PutItemRequest("t", mapOf("pk" to AttributeValue.S("a")))) }
        }

        assertEquals(sdkItem(sdkError.item), ourItem(ourError.item), "ConditionalCheckFailedException.item")
        assertEquals(sdkError.message, ourError.message, "message")
        assertEquals("The conditional request failed", ourError.message)
        assertEquals(3, assertNotNull(ourError.item).size, "the failed item must survive the error path")
    }

    /**
     * `CancellationReasons` is positional — one entry per `TransactItems` entry, `"None"` for the
     * ones that were fine. Filtering the successes out would misalign every index after the first
     * failure, so the whole list, `None`s included, must survive.
     *
     * Note the capital `Message` here against the lowercase `message` in the case above: DynamoDB
     * really does use both, and the error parser reads either.
     */
    @Test
    fun transactionCanceledCarriesMixedReasons() {
        val body = """
            {"__type":"com.amazonaws.dynamodb.v20120810#TransactionCanceledException",
             "Message":"Transaction cancelled, please refer cancellation reasons for specific reasons [None, ConditionalCheckFailed, None]",
             "CancellationReasons":[
               {"Code":"None"},
               {"Code":"ConditionalCheckFailed","Message":"The conditional request failed",
                "Item":{"pk":{"S":"b"},"version":{"N":"2"}}},
               {"Code":"None"}]}
        """.trimIndent()

        fun sdkTransactItem(table: String, pk: String) =
            aws.sdk.kotlin.services.dynamodb.model.TransactWriteItem {
                put = aws.sdk.kotlin.services.dynamodb.model.Put {
                    tableName = table; item = mapOf("pk" to SdkAttributeValue.S(pk))
                }
            }

        val sdkError = assertFailsWith<SdkTransactionCanceled> {
            throughSdk(body, status = 400) {
                it.transactWriteItems {
                    transactItems = listOf(sdkTransactItem("t", "a"), sdkTransactItem("t", "b"), sdkTransactItem("t", "c"))
                }
            }
        }
        val ourError = assertFailsWith<TransactionCanceledException> {
            throughOurs(body, status = 400) {
                it.transactWriteItems(
                    TransactWriteItemsRequest(
                        listOf(
                            TransactWriteItem(put = TransactPut("t", mapOf("pk" to AttributeValue.S("a")))),
                            TransactWriteItem(put = TransactPut("t", mapOf("pk" to AttributeValue.S("b")))),
                            TransactWriteItem(put = TransactPut("t", mapOf("pk" to AttributeValue.S("c")))),
                        ),
                    ),
                )
            }
        }

        assertEquals(
            JsonArray(
                sdkError.cancellationReasons.orEmpty().map { r ->
                    obj("Code" to str(r.code), "Message" to str(r.message), "Item" to sdkItem(r.item))
                },
            ),
            JsonArray(
                ourError.cancellationReasons.map { r ->
                    obj("Code" to str(r.code), "Message" to str(r.message), "Item" to ourItem(r.item))
                },
            ),
            "TransactionCanceledException.cancellationReasons",
        )
        assertEquals(sdkError.message, ourError.message, "message")
        assertEquals(3, ourError.cancellationReasons.size, "the successful slots must not be filtered out")
    }

    /**
     * Guards the harness itself. If the canned server stopped serving, or the SDK stopped reaching
     * it, every comparison above would compare two empty results and pass for the wrong reason.
     */
    @Test
    fun theHarnessActuallyDeserializesACannedBody() {
        val body = """{"Item":{"canary":{"S":"present"}}}"""
        val sdk = throughSdk(body) { it.getItem { tableName = "t"; key = mapOf("pk" to SdkAttributeValue.S("a")) } }
        assertEquals("present", sdk.item?.get("canary")?.asS(), "the SDK must have read the canned body")

        val ours = throughOurs(body) { it.getItem(GetItemRequest("t", mapOf("pk" to AttributeValue.S("a")))) }
        assertEquals("present", ours.item?.get("canary")?.asS(), "our client must have read the same body")
    }
}
