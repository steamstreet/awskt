package com.steamstreet.awskt.dynamodb.sdk

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.smithy.kotlin.runtime.net.url.Url
import com.steamstreet.awskt.core.callJson
import com.steamstreet.awskt.dynamodb.AttributeDefinition
import com.steamstreet.awskt.dynamodb.AttributeValue
import com.steamstreet.awskt.dynamodb.BillingMode
import com.steamstreet.awskt.dynamodb.ConditionalCheckFailedException
import com.steamstreet.awskt.dynamodb.CreateTableRequest
import com.steamstreet.awskt.dynamodb.DeleteItemRequest
import com.steamstreet.awskt.dynamodb.DescribeTableRequest
import com.steamstreet.awskt.dynamodb.DynamoDb
import com.steamstreet.awskt.dynamodb.GetItemRequest
import com.steamstreet.awskt.dynamodb.KeySchemaElement
import com.steamstreet.awskt.dynamodb.KeyType
import com.steamstreet.awskt.dynamodb.ProjectionType
import com.steamstreet.awskt.dynamodb.PutItemRequest
import com.steamstreet.awskt.dynamodb.QueryRequest
import com.steamstreet.awskt.dynamodb.ResourceNotFoundException
import com.steamstreet.awskt.dynamodb.ReturnValue
import com.steamstreet.awskt.dynamodb.ScalarAttributeType
import com.steamstreet.awskt.dynamodb.StreamViewType
import com.steamstreet.awskt.dynamodb.TransactPut
import com.steamstreet.awskt.dynamodb.TransactWriteItem
import com.steamstreet.awskt.dynamodb.TransactWriteItemsRequest
import com.steamstreet.awskt.dynamodb.TransactionCanceledException
import com.steamstreet.awskt.dynamodb.UpdateItemRequest
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The adapter's contract is that a consumer cannot tell which implementation it is talking to.
 *
 * Every test here drives [SdkBackedDynamoDb] with a canned AWS response and asserts on **this
 * library's** DTOs and **this library's** exceptions coming back out — because that is precisely
 * what M5a leans on: the type swap lands while behaviour is still, byte for byte, the AWS SDK's,
 * and the implementation flip that follows is a one-line revert.
 *
 * The canned body is served by a JDK `HttpServer` on loopback with the SDK client's `endpointUrl`
 * pointed at it, which also lets the request side be captured and asserted. Public API throughout.
 */
class SdkBackedDynamoDbTest {

    private class Captured {
        var body: String? = null
        var target: String? = null
        var authorization: String? = null
    }

    /** Serves [body] with [status], recording what was sent, for the duration of [block]. */
    private fun <T> canned(
        body: String,
        status: Int = 200,
        block: (url: String, captured: Captured) -> T,
    ): T {
        val captured = Captured()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            captured.body = exchange.requestBody.use { it.readBytes() }.decodeToString()
            captured.target = exchange.requestHeaders.getFirst("X-Amz-Target")
            captured.authorization = exchange.requestHeaders.getFirst("Authorization")
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/x-amz-json-1.0")
            // Both spellings: DynamoDB sends `x-amzn-RequestId`, and smithy-kotlin's error
            // deserializer reads `x-amz-request-id` (ResponseUtils.X_AMZN_REQUEST_ID_HEADER). Our
            // own transport reads either. A fixture that sends one only proves half the path.
            exchange.responseHeaders.add("x-amzn-RequestId", "canned-request-id")
            exchange.responseHeaders.add("x-amz-request-id", "canned-request-id")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return try {
            block("http://127.0.0.1:${server.address.port}", captured)
        } finally {
            server.stop(0)
        }
    }

    private fun sdkClient(url: String): DynamoDbClient = DynamoDbClient {
        region = "us-west-2"
        endpointUrl = Url.parse(url)
        credentialsProvider = StaticCredentialsProvider {
            accessKeyId = "AKIDEXAMPLE"
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
        }
    }

    private fun <T> withAdapter(
        body: String,
        status: Int = 200,
        block: suspend (DynamoDb, Captured) -> T,
    ): T = canned(body, status) { url, captured ->
        SdkBackedDynamoDb(sdkClient(url)).use { db ->
            kotlinx.coroutines.runBlocking { block(db, captured) }
        }
    }

    private fun requestJson(captured: Captured): JsonObject =
        Json.parseToJsonElement(assertNotNull(captured.body, "no request was captured")) as JsonObject

    // -- Pure conversions -----------------------------------------------------------------------

    /**
     * Every variant, both directions, with no I/O anywhere.
     *
     * `B` and `Bs` are the ones worth having: they are the only variants whose equality is
     * hand-written (`contentEquals`, not a data class), so a conversion that silently produced a
     * different array would be invisible to a reference comparison.
     */
    @Test
    fun everyAttributeValueVariantSurvivesBothConversions() {
        val cases = listOf<AttributeValue>(
            AttributeValue.S("hello"),
            AttributeValue.S(""),
            AttributeValue.N("99999999999999999999999999999999999999"),
            AttributeValue.B(byteArrayOf(0, 1, -1, 127, -128)),
            AttributeValue.Bool(true),
            AttributeValue.Bool(false),
            AttributeValue.Null(),
            AttributeValue.Ss(listOf("a", "")),
            AttributeValue.Ns(listOf("1", "-2.5")),
            AttributeValue.Bs(listOf(byteArrayOf(1, 2), byteArrayOf(3))),
            AttributeValue.L(emptyList()),
            AttributeValue.M(emptyMap()),
            AttributeValue.L(listOf(AttributeValue.S("x"), AttributeValue.M(emptyMap()))),
            AttributeValue.M(
                mapOf("deep" to AttributeValue.L(listOf(AttributeValue.M(mapOf("n" to AttributeValue.N("1")))))),
            ),
        )

        for (case in cases) {
            assertEquals(case, case.toSdk().toAwsKt(), "round trip of $case through the SDK type")
        }
    }

    // -- Reads ----------------------------------------------------------------------------------

    @Test
    fun getItemReturnsOurDtos() = withAdapter(
        """{"Item":{"pk":{"S":"a"},"n":{"N":"7"},"blob":{"B":"AQI="},"nul":{"NULL":true}}}""",
    ) { db, captured ->
        val response = db.getItem(
            GetItemRequest(
                tableName = "t",
                key = mapOf("pk" to AttributeValue.S("a")),
                consistentRead = true,
                projectionExpression = "#a",
                expressionAttributeNames = mapOf("#a" to "alpha"),
            ),
        )

        assertEquals(AttributeValue.S("a"), response.item?.get("pk"))
        assertEquals(AttributeValue.N("7"), response.item?.get("n"))
        assertEquals(AttributeValue.B(byteArrayOf(1, 2)), response.item?.get("blob"))
        assertEquals(AttributeValue.Null(), response.item?.get("nul"))

        // The request half: our fields must actually reach the wire, not be silently dropped.
        assertEquals("DynamoDB_20120810.GetItem", captured.target)
        val sent = requestJson(captured)
        assertTrue("ConsistentRead" in sent && "ProjectionExpression" in sent && "ExpressionAttributeNames" in sent)
    }

    @Test
    fun getItemOnAMissReturnsNullNotAnEmptyMap() = withAdapter("{}") { db, _ ->
        assertNull(db.getItem(GetItemRequest("t", mapOf("pk" to AttributeValue.S("a")))).item)
    }

    @Test
    fun queryCarriesTheLastEvaluatedKeyAndCounts() = withAdapter(
        """{"Items":[{"pk":{"S":"a"}},{"pk":{"S":"b"}}],"Count":2,"ScannedCount":9,
            "LastEvaluatedKey":{"pk":{"S":"b"}}}""",
    ) { db, captured ->
        val response = db.query(
            QueryRequest(
                tableName = "t",
                indexName = "gsi1",
                keyConditionExpression = "#pk = :pk",
                expressionAttributeNames = mapOf("#pk" to "pk"),
                expressionAttributeValues = mapOf(":pk" to AttributeValue.S("a")),
                limit = 25,
                scanIndexForward = false,
            ),
        )

        assertEquals(2, response.items?.size)
        assertEquals(2, response.count)
        assertEquals(9, response.scannedCount)
        assertEquals(mapOf("pk" to AttributeValue.S("b")), response.lastEvaluatedKey)

        val sent = requestJson(captured)
        assertTrue("IndexName" in sent && "KeyConditionExpression" in sent && "Limit" in sent)
        assertTrue("ScanIndexForward" in sent)
    }

    /** The empty map, again: it must arrive as empty and not as null, or the paginator never stops. */
    @Test
    fun queryWithAnEmptyLastEvaluatedKeyStaysEmptyNotNull() = withAdapter(
        """{"Items":[],"Count":0,"ScannedCount":0,"LastEvaluatedKey":{}}""",
    ) { db, _ ->
        assertEquals(emptyMap(), db.query(QueryRequest("t")).lastEvaluatedKey)
    }

    @Test
    fun describeTableNarrowsToOurTableDescription() = withAdapter(
        """
        {"Table":{"TableName":"t","TableStatus":"ACTIVE","ItemCount":17,"TableSizeBytes":4096,
          "KeySchema":[{"AttributeName":"pk","KeyType":"HASH"},{"AttributeName":"sk","KeyType":"RANGE"}],
          "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"}],
          "GlobalSecondaryIndexes":[{"IndexName":"gsi1","IndexStatus":"ACTIVE",
            "KeySchema":[{"AttributeName":"gsi1pk","KeyType":"HASH"}],
            "Projection":{"ProjectionType":"INCLUDE","NonKeyAttributes":["a"]}}],
          "StreamSpecification":{"StreamEnabled":true,"StreamViewType":"NEW_AND_OLD_IMAGES"},
          "LatestStreamArn":"arn:aws:dynamodb:us-west-2:1:table/t/stream/2026"}}
        """.trimIndent(),
    ) { db, _ ->
        val table = assertNotNull(db.describeTable(DescribeTableRequest("t")).table)

        assertEquals("t", table.tableName)
        assertEquals("ACTIVE", table.tableStatus)
        assertEquals(17L, table.itemCount)
        assertEquals(listOf(KeyType.Hash, KeyType.Range), table.keySchema?.map { it.keyType })
        assertEquals(ScalarAttributeType.S, table.attributeDefinitions?.single()?.attributeType)
        assertEquals(ProjectionType.Include, table.globalSecondaryIndexes?.single()?.projection?.projectionType)
        assertEquals(StreamViewType.NewAndOldImages, table.streamSpecification?.streamViewType)
        assertTrue(table.latestStreamArn!!.endsWith("/stream/2026"))
    }

    // -- Writes ---------------------------------------------------------------------------------

    @Test
    fun createTableSendsTheWholeIndexClosure() = withAdapter("""{"TableDescription":{"TableStatus":"CREATING"}}""") {
            db, captured ->
        val response = db.createTable(
            CreateTableRequest(
                tableName = "t",
                attributeDefinitions = listOf(
                    AttributeDefinition("pk", ScalarAttributeType.S),
                    AttributeDefinition("gsi1pk", ScalarAttributeType.N),
                ),
                keySchema = listOf(KeySchemaElement("pk", KeyType.Hash)),
                billingMode = BillingMode.PayPerRequest,
                globalSecondaryIndexes = listOf(
                    com.steamstreet.awskt.dynamodb.GlobalSecondaryIndex(
                        indexName = "gsi1",
                        keySchema = listOf(KeySchemaElement("gsi1pk", KeyType.Hash)),
                        projection = com.steamstreet.awskt.dynamodb.Projection(
                            ProjectionType.Include,
                            listOf("a", "b"),
                        ),
                    ),
                ),
                streamSpecification = com.steamstreet.awskt.dynamodb.StreamSpecification(
                    true,
                    StreamViewType.NewAndOldImages,
                ),
            ),
        )

        assertEquals("CREATING", response.tableDescription?.tableStatus)
        val sent = requestJson(captured).toString()
        assertTrue("PAY_PER_REQUEST" in sent, "billing mode must reach the wire in its SCREAMING_SNAKE form")
        assertTrue("INCLUDE" in sent && "gsi1" in sent && "NEW_AND_OLD_IMAGES" in sent)
    }

    @Test
    fun updateItemSendsExpressionsAndReturnsAttributes() = withAdapter(
        """{"Attributes":{"counter":{"N":"42"}}}""",
    ) { db, captured ->
        val response = db.updateItem(
            UpdateItemRequest(
                tableName = "t",
                key = mapOf("pk" to AttributeValue.S("a")),
                updateExpression = "ADD #c :one",
                conditionExpression = "attribute_exists(#c)",
                expressionAttributeNames = mapOf("#c" to "counter"),
                expressionAttributeValues = mapOf(":one" to AttributeValue.N("1")),
                returnValues = ReturnValue.AllNew,
            ),
        )

        assertEquals(AttributeValue.N("42"), response.attributes?.get("counter"))
        val sent = requestJson(captured).toString()
        assertTrue("ADD #c :one" in sent && "ALL_NEW" in sent)
    }

    /** An unset optional map must be absent from the request, not sent as `{}` — DynamoDB rejects `{}`. */
    @Test
    fun unsetExpressionMapsAreOmittedNotSentEmpty() = withAdapter("{}") { db, captured ->
        db.deleteItem(DeleteItemRequest("t", mapOf("pk" to AttributeValue.S("a"))))

        val sent = requestJson(captured)
        assertTrue("ExpressionAttributeNames" !in sent)
        assertTrue("ExpressionAttributeValues" !in sent)
    }

    @Test
    fun transactWriteItemsPassesTheCallersTokenThrough() = withAdapter("{}") { db, captured ->
        db.transactWriteItems(
            TransactWriteItemsRequest(
                transactItems = listOf(
                    TransactWriteItem(put = TransactPut("t", mapOf("pk" to AttributeValue.S("a")))),
                ),
                clientRequestToken = "0123456789abcdef0123456789abcdef0123",
            ),
        )

        assertTrue("0123456789abcdef0123456789abcdef0123" in requestJson(captured).toString())
    }

    // -- Errors ---------------------------------------------------------------------------------

    /**
     * The reason the error mapping is not cosmetic: consumer `catch` blocks are written once,
     * against this library's hierarchy, and must not need rewriting when the implementation flips.
     */
    @Test
    fun conditionalCheckFailedBecomesOurExceptionCarryingTheItem() {
        val error = assertFailsWith<ConditionalCheckFailedException> {
            withAdapter(
                """{"__type":"com.amazonaws.dynamodb.v20120810#ConditionalCheckFailedException",
                    "message":"The conditional request failed",
                    "Item":{"pk":{"S":"a"},"version":{"N":"7"}}}""",
                status = 400,
            ) { db, _ -> db.putItem(PutItemRequest("t", mapOf("pk" to AttributeValue.S("a")))) }
        }

        assertEquals("ConditionalCheckFailedException", error.code)
        assertEquals(400, error.statusCode)
        assertEquals("The conditional request failed", error.message)
        assertEquals(AttributeValue.N("7"), assertNotNull(error.item)["version"])
        assertEquals("canned-request-id", error.requestId)
    }

    @Test
    fun transactionCanceledBecomesOurExceptionWithPositionalReasons() {
        val error = assertFailsWith<TransactionCanceledException> {
            withAdapter(
                """{"__type":"com.amazonaws.dynamodb.v20120810#TransactionCanceledException",
                    "Message":"Transaction cancelled",
                    "CancellationReasons":[{"Code":"None"},
                      {"Code":"ConditionalCheckFailed","Message":"nope","Item":{"pk":{"S":"b"}}},
                      {"Code":"None"}]}""",
                status = 400,
            ) { db, _ ->
                db.transactWriteItems(
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

        assertEquals(listOf("None", "ConditionalCheckFailed", "None"), error.cancellationReasons.map { it.code })
        assertEquals(
            mapOf("pk" to AttributeValue.S("b")),
            error.cancellationReasons[1].item,
            "the failed slot's item must survive",
        )
    }

    @Test
    fun anUnmodelledErrorCodeStillBecomesATypedDynamoDbException() {
        val error = assertFailsWith<ResourceNotFoundException> {
            withAdapter(
                """{"__type":"com.amazonaws.dynamodb.v20120810#ResourceNotFoundException",
                    "message":"Requested resource not found"}""",
                status = 400,
            ) { db, _ -> db.getItem(GetItemRequest("t", mapOf("pk" to AttributeValue.S("a")))) }
        }
        assertEquals("ResourceNotFoundException", error.code)
    }

    // -- The extension seam ---------------------------------------------------------------------

    /**
     * `DynamoDb.client` is part of the interface, so it must work on **both** implementations —
     * otherwise an extension function written downstream compiles against the interface and blows
     * up on whichever one it happens to be handed.
     *
     * The adapter has no `AwsServiceClient` of its own, so it builds one from the delegate's own
     * resolved region, credentials and endpoint. This test proves that inherited configuration
     * actually reaches AWS: the call lands on the same canned endpoint the SDK client was pointed
     * at, signed, with the right `X-Amz-Target`.
     */
    @Test
    fun anExtensionOperationWorksOnTheSdkBackedClientToo() = withAdapter(
        """{"TableMaxWriteCapacityUnits":40000}""",
    ) { db, captured ->
        val response = db.describeLimits()

        assertEquals(40_000, response.tableMaxWriteCapacityUnits)
        assertEquals("DynamoDB_20120810.DescribeLimits", captured.target)
        assertTrue(
            assertNotNull(captured.authorization).startsWith("AWS4-HMAC-SHA256 "),
            "the seam must inherit the delegate's credentials and sign like any other call",
        )
    }

    /** `close()` must not blow up when the extension seam was never touched. */
    @Test
    fun closingWithoutTouchingTheExtensionSeamIsFine() = canned("{}") { url, _ ->
        val db = SdkBackedDynamoDb(sdkClient(url))
        kotlinx.coroutines.runBlocking {
            db.getItem(GetItemRequest("t", mapOf("pk" to AttributeValue.S("a"))))
        }
        db.close()
    }
}

@Serializable
private class DescribeLimitsRequest

@Serializable
private class DescribeLimitsResponse(
    @SerialName("TableMaxWriteCapacityUnits") val tableMaxWriteCapacityUnits: Long? = null,
)

/** Written exactly as a downstream consumer would write it: public API only, outside the library. */
private suspend fun DynamoDb.describeLimits(): DescribeLimitsResponse =
    client.callJson(
        operation = "DescribeLimits",
        request = DescribeLimitsRequest(),
        requestSerializer = DescribeLimitsRequest.serializer(),
        responseSerializer = DescribeLimitsResponse.serializer(),
    )
