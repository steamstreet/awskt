package com.steamstreet.awskt.dynamodb

import com.steamstreet.awskt.core.callJson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hand-written client, driven against a real DynamoDB implementation.
 *
 * ### Why this layer exists on top of everything else
 *
 * The differential harnesses prove our bytes match the AWS SDK's, in both directions. `MockEngine`
 * proves the retry and error paths branch correctly. **Neither can tell us the API works**, because
 * neither has an opinion: a mock answers whatever the test told it to answer, and a byte comparison
 * is satisfied by two clients being wrong in the same way.
 *
 * LocalStack has an opinion. It rejects `"ExpressionAttributeValues":{}`, enforces the 25-item
 * `BatchWriteItem` cap, evaluates condition expressions, and really applies `ADD`. Every test below
 * asserts against behaviour the service decides, not behaviour a fixture was told to perform.
 *
 * See [LocalStack] for the one thing this deliberately does **not** prove — signatures.
 */
class LocalStackDynamoDbTest {

    /**
     * Runs [block] against a table of its own, deleted afterwards.
     *
     * A table per test rather than a shared one: these tests assert on whole query and scan results,
     * and sharing would couple them to each other's leftovers and to execution order.
     */
    private fun withTable(
        globalSecondaryIndexes: List<GlobalSecondaryIndex>? = null,
        extraAttributes: List<AttributeDefinition> = emptyList(),
        block: suspend (DynamoDb, String) -> Unit,
    ) {
        if (!LocalStack.available) {
            println("[localstack] skipped — Docker is not available")
            return
        }

        val tableName = "awskt-" + Random.nextLong().toULong().toString(16)
        LocalStack.dynamoDb().use { db ->
            runBlocking {
                db.createTable(
                    CreateTableRequest(
                        tableName = tableName,
                        attributeDefinitions = listOf(
                            AttributeDefinition("pk", ScalarAttributeType.S),
                            AttributeDefinition("sk", ScalarAttributeType.S),
                        ) + extraAttributes,
                        keySchema = listOf(
                            KeySchemaElement("pk", KeyType.Hash),
                            KeySchemaElement("sk", KeyType.Range),
                        ),
                        billingMode = BillingMode.PayPerRequest,
                        globalSecondaryIndexes = globalSecondaryIndexes,
                    ),
                )
                try {
                    block(db, tableName)
                } finally {
                    runCatching { db.deleteTable(DeleteTableRequest(tableName)) }
                }
            }
        }
    }

    private fun key(pk: String, sk: String): Item =
        mapOf("pk" to AttributeValue.S(pk), "sk" to AttributeValue.S(sk))

    // -- Control plane --------------------------------------------------------------------------

    @Test
    fun createTableAndDescribeTableRoundTripThroughARealService() = withTable(
        extraAttributes = listOf(AttributeDefinition("gsi1pk", ScalarAttributeType.S)),
        globalSecondaryIndexes = listOf(
            GlobalSecondaryIndex(
                indexName = "gsi1",
                keySchema = listOf(KeySchemaElement("gsi1pk", KeyType.Hash)),
                projection = Projection(ProjectionType.Include, listOf("payload")),
            ),
        ),
    ) { db, tableName ->
        val table = assertNotNull(db.describeTable(DescribeTableRequest(tableName)).table)

        assertEquals(tableName, table.tableName)
        assertEquals(
            listOf("pk" to KeyType.Hash, "sk" to KeyType.Range),
            table.keySchema?.map { it.attributeName to it.keyType },
        )
        val gsi = assertNotNull(table.globalSecondaryIndexes?.single())
        assertEquals("gsi1", gsi.indexName)
        assertEquals(ProjectionType.Include, gsi.projection?.projectionType)
        assertEquals(listOf("payload"), gsi.projection?.nonKeyAttributes)
    }

    /** After a delete the table is genuinely gone, and the miss is our typed exception. */
    @Test
    fun describingADeletedTableRaisesResourceNotFound() {
        if (!LocalStack.available) {
            println("[localstack] skipped — Docker is not available")
            return
        }

        val tableName = "awskt-" + Random.nextLong().toULong().toString(16)
        LocalStack.dynamoDb().use { db ->
            runBlocking {
                db.createTable(
                    CreateTableRequest(
                        tableName = tableName,
                        attributeDefinitions = listOf(AttributeDefinition("pk", ScalarAttributeType.S)),
                        keySchema = listOf(KeySchemaElement("pk", KeyType.Hash)),
                        billingMode = BillingMode.PayPerRequest,
                    ),
                )
                db.deleteTable(DeleteTableRequest(tableName))

                assertFailsWith<ResourceNotFoundException> {
                    db.describeTable(DescribeTableRequest(tableName))
                }
            }
        }
    }

    // -- Items ----------------------------------------------------------------------------------

    /**
     * Every variant through a service that validates them.
     *
     * The round-trip tests in `commonTest` decode our own encoding, so they would happily agree with
     * a codec that is wrong in both directions. DynamoDB will not: a malformed base64 `B`, a set
     * with the wrong JSON shape, or a number outside its 38-digit range is rejected outright.
     */
    @Test
    fun everyAttributeValueVariantSurvivesARealRoundTrip() = withTable { db, tableName ->
        val item = key("variants", "1") + mapOf(
            "emptyString" to AttributeValue.S(""),
            "bigNumber" to AttributeValue.N("99999999999999999999999999999999999999"),
            "tinyNegative" to AttributeValue.N("-0.00000000000000000000000000000000000001"),
            "binary" to AttributeValue.B(byteArrayOf(0, 1, 2, -1, 127, -128)),
            "flag" to AttributeValue.Bool(false),
            "absent" to AttributeValue.Null(),
            "strings" to AttributeValue.Ss(listOf("a", "b")),
            "numbers" to AttributeValue.Ns(listOf("1", "-2.5")),
            "blobs" to AttributeValue.Bs(listOf(byteArrayOf(1, 2), byteArrayOf(3))),
            "emptyList" to AttributeValue.L(emptyList()),
            "emptyMap" to AttributeValue.M(emptyMap()),
            "nested" to AttributeValue.M(
                mapOf("inner" to AttributeValue.L(listOf(AttributeValue.S("x"), AttributeValue.Null()))),
            ),
        )

        db.putItem(PutItemRequest(tableName, item))

        val read = assertNotNull(
            db.getItem(GetItemRequest(tableName, key("variants", "1"), consistentRead = true)).item,
        )
        // Plain structural equality, including the set types. That works because `Ss`/`Ns`/`Bs`
        // compare as sets — a decision this very test forced: on its first run DynamoDB handed back
        // `NS: ["1","-2.5"]` as `["-2.5","1"]` and the assertion failed on ordering alone.
        for ((name, expected) in item) {
            assertEquals(expected, read[name], "attribute '$name' did not survive DynamoDB")
        }
    }

    @Test
    fun getItemOnAMissReturnsNull() = withTable { db, tableName ->
        assertNull(db.getItem(GetItemRequest(tableName, key("nobody", "home"))).item)
    }

    /**
     * `ADD` really increments, which is the whole reason `UpdateItem` is classified
     * `NOT_IDEMPOTENT` — a replayed attempt would double-apply. Asserted against a service that
     * actually performs the arithmetic rather than a mock that reflects a canned answer.
     */
    @Test
    fun updateItemAppliesSetAddAndRemove() = withTable { db, tableName ->
        db.putItem(
            PutItemRequest(
                tableName,
                key("upd", "1") + mapOf(
                    "counter" to AttributeValue.N("1"),
                    "doomed" to AttributeValue.S("delete me"),
                ),
            ),
        )

        val updated = db.updateItem(
            UpdateItemRequest(
                tableName = tableName,
                key = key("upd", "1"),
                updateExpression = "SET #label = :label ADD #counter :one REMOVE #doomed",
                expressionAttributeNames = mapOf(
                    "#label" to "label", "#counter" to "counter", "#doomed" to "doomed",
                ),
                expressionAttributeValues = mapOf(
                    ":label" to AttributeValue.S("set"), ":one" to AttributeValue.N("1"),
                ),
                returnValues = ReturnValue.AllNew,
            ),
        )

        val attributes = assertNotNull(updated.attributes)
        assertEquals(AttributeValue.S("set"), attributes["label"])
        assertEquals(AttributeValue.N("2"), attributes["counter"], "ADD must increment, not overwrite")
        assertNull(attributes["doomed"], "REMOVE must drop the attribute")
    }

    /**
     * The empty-collection invariant, enforced by the only party whose opinion counts.
     *
     * With `encodeDefaults = false` an *assigned* `emptyMap()` still serializes as
     * `"ExpressionAttributeValues":{}`, and DynamoDB rejects that outright where it accepts the
     * field being absent. Every offline test of this asserts on our own JSON; this one asserts that
     * the service takes it.
     */
    @Test
    fun anUpdateWithNoExpressionValuesIsAcceptedByTheService() = withTable { db, tableName ->
        db.putItem(PutItemRequest(tableName, key("empty", "1") + mapOf("gone" to AttributeValue.S("x"))))

        db.updateItem(
            UpdateItemRequest(
                tableName = tableName,
                key = key("empty", "1"),
                updateExpression = "REMOVE gone",
                expressionAttributeNames = emptyMap<String, String>().orNullIfEmpty(),
                expressionAttributeValues = emptyMap<String, AttributeValue>().orNullIfEmpty(),
            ),
        )

        val read = assertNotNull(db.getItem(GetItemRequest(tableName, key("empty", "1"), consistentRead = true)).item)
        assertNull(read["gone"])
    }

    @Test
    fun deleteItemReturnsTheOldItem() = withTable { db, tableName ->
        db.putItem(PutItemRequest(tableName, key("del", "1") + mapOf("v" to AttributeValue.N("9"))))

        val deleted = db.deleteItem(
            DeleteItemRequest(tableName, key("del", "1"), returnValues = ReturnValue.AllOld),
        )

        assertEquals(AttributeValue.N("9"), deleted.attributes?.get("v"))
        assertNull(db.getItem(GetItemRequest(tableName, key("del", "1"), consistentRead = true)).item)
    }

    // -- Conditions and errors ------------------------------------------------------------------

    /**
     * A real condition failure, producing a real error body.
     *
     * This is the end-to-end proof for the error-body path: the service decides the condition
     * failed, writes the losing item into the error document, and our parser lifts it back out.
     */
    @Test
    fun aFailedConditionRaisesTheTypedExceptionCarryingTheLosingItem() = withTable { db, tableName ->
        db.putItem(PutItemRequest(tableName, key("cond", "1") + mapOf("version" to AttributeValue.N("7"))))

        val error = assertFailsWith<ConditionalCheckFailedException> {
            db.putItem(
                PutItemRequest(
                    tableName = tableName,
                    item = key("cond", "1") + mapOf("version" to AttributeValue.N("8")),
                    conditionExpression = "attribute_not_exists(pk)",
                    returnValuesOnConditionCheckFailure = ReturnValuesOnConditionCheckFailure.AllOld,
                ),
            )
        }

        assertEquals("ConditionalCheckFailedException", error.code)
        assertEquals(
            AttributeValue.N("7"),
            assertNotNull(error.item, "ALL_OLD must return the item that failed the condition")["version"],
        )
        // The losing write must not have landed.
        assertEquals(
            AttributeValue.N("7"),
            db.getItem(GetItemRequest(tableName, key("cond", "1"), consistentRead = true)).item?.get("version"),
        )
    }

    // -- Query and scan -------------------------------------------------------------------------

    /**
     * Real pagination: `Limit = 1` over three items forces DynamoDB to hand back a real
     * `LastEvaluatedKey` and then, on the final page, to stop.
     *
     * The termination rule is the point. It is `lastEvaluatedKey?.takeIf { it.isNotEmpty() }`
     * because a `!= null` check loops forever on the empty map DynamoDB can return — and a fixture
     * can only assert that against a fixture's idea of the protocol.
     */
    @Test
    fun queryPagesAcrossRealPagesAndTerminates() = withTable { db, tableName ->
        for (i in 1..3) {
            db.putItem(PutItemRequest(tableName, key("page", "item-$i") + mapOf("n" to AttributeValue.N("$i"))))
        }

        val request = QueryRequest(
            tableName = tableName,
            keyConditionExpression = "#pk = :pk",
            expressionAttributeNames = mapOf("#pk" to "pk"),
            expressionAttributeValues = mapOf(":pk" to AttributeValue.S("page")),
            limit = 1,
            consistentRead = true,
        )

        val pages = db.queryPaged(request).toList()
        assertTrue(pages.size >= 3, "Limit=1 over three items must produce at least three pages")
        assertEquals(3, pages.sumOf { it.items?.size ?: 0 })
        assertTrue(pages.last().lastEvaluatedKey.isNullOrEmpty(), "the final page must not ask for another")

        val items = db.queryPaged(request).items().toList()
        assertEquals(listOf("1", "2", "3"), items.map { it["n"]?.asN() })
    }

    @Test
    fun queryHonoursFilterSortOrderAndProjection() = withTable { db, tableName ->
        for (i in 1..4) {
            db.putItem(
                PutItemRequest(
                    tableName,
                    key("q", "item-$i") + mapOf(
                        "n" to AttributeValue.N("$i"),
                        "payload" to AttributeValue.S("body-$i"),
                    ),
                ),
            )
        }

        val response = db.query(
            QueryRequest(
                tableName = tableName,
                keyConditionExpression = "#pk = :pk",
                filterExpression = "#n > :two",
                projectionExpression = "#n",
                expressionAttributeNames = mapOf("#pk" to "pk", "#n" to "n"),
                expressionAttributeValues = mapOf(
                    ":pk" to AttributeValue.S("q"), ":two" to AttributeValue.N("2"),
                ),
                scanIndexForward = false,
                consistentRead = true,
            ),
        )

        assertEquals(listOf("4", "3"), response.items?.map { it["n"]?.asN() }, "descending, filtered")
        assertTrue(
            response.items?.all { it.keys == setOf("n") } == true,
            "the projection must drop every other attribute, keys included",
        )
    }

    @Test
    fun queryAgainstAGlobalSecondaryIndex() = withTable(
        extraAttributes = listOf(AttributeDefinition("gsi1pk", ScalarAttributeType.S)),
        globalSecondaryIndexes = listOf(
            GlobalSecondaryIndex(
                indexName = "gsi1",
                keySchema = listOf(KeySchemaElement("gsi1pk", KeyType.Hash)),
                projection = Projection(ProjectionType.All),
            ),
        ),
    ) { db, tableName ->
        db.putItem(
            PutItemRequest(tableName, key("g", "1") + mapOf("gsi1pk" to AttributeValue.S("bucket-a"))),
        )
        db.putItem(
            PutItemRequest(tableName, key("g", "2") + mapOf("gsi1pk" to AttributeValue.S("bucket-b"))),
        )

        val response = db.query(
            QueryRequest(
                tableName = tableName,
                indexName = "gsi1",
                keyConditionExpression = "#gpk = :gpk",
                expressionAttributeNames = mapOf("#gpk" to "gsi1pk"),
                expressionAttributeValues = mapOf(":gpk" to AttributeValue.S("bucket-a")),
                // A GSI cannot be read consistently — asserting that here keeps the flag honest.
            ),
        )

        assertEquals(listOf("1"), response.items?.map { it["sk"]?.asS() })
    }

    @Test
    fun scanPagedReturnsEveryItem() = withTable { db, tableName ->
        for (i in 1..5) {
            db.putItem(PutItemRequest(tableName, key("scan-$i", "1")))
        }

        val items = db.scanPaged(ScanRequest(tableName, limit = 2)).scanItems().toList()
        assertEquals(5, items.size)
    }

    // -- Batch and transactions -----------------------------------------------------------------

    /**
     * 60 writes and 60 reads through a service that enforces the limits.
     *
     * DynamoDB rejects a `BatchWriteItem` above 25 items outright, so an unchunked call fails here
     * rather than being quietly accepted by a mock. That is the bug in the code this replaces.
     */
    @Test
    fun batchWriteAllAndBatchGetAllChunkCorrectly() = withTable { db, tableName ->
        val keys = (1..60).map { key("batch", "item-$it") }

        db.batchWriteAll(tableName, keys.map { WriteRequest(putRequest = PutRequest(it)) })

        val read = db.batchGetAll(tableName, keys, consistentRead = true)
        assertEquals(60, read.size, "every written item must come back")

        db.batchWriteAll(tableName, keys.map { WriteRequest(deleteRequest = DeleteRequest(it)) })
        assertEquals(0, db.batchGetAll(tableName, keys, consistentRead = true).size)
    }

    @Test
    fun transactWriteItemsAppliesEveryOperationAtomically() = withTable { db, tableName ->
        db.putItem(PutItemRequest(tableName, key("txn", "update-me") + mapOf("n" to AttributeValue.N("1"))))
        db.putItem(PutItemRequest(tableName, key("txn", "delete-me")))
        db.putItem(PutItemRequest(tableName, key("txn", "guard") + mapOf("ok" to AttributeValue.Bool(true))))

        db.transactWriteItems(
            TransactWriteItemsRequest(
                listOf(
                    TransactWriteItem(put = TransactPut(tableName, key("txn", "new") + mapOf("n" to AttributeValue.N("9")))),
                    TransactWriteItem(
                        update = TransactUpdate(
                            tableName = tableName,
                            key = key("txn", "update-me"),
                            updateExpression = "SET #n = :n",
                            expressionAttributeNames = mapOf("#n" to "n"),
                            expressionAttributeValues = mapOf(":n" to AttributeValue.N("2")),
                        ),
                    ),
                    TransactWriteItem(delete = TransactDelete(tableName, key("txn", "delete-me"))),
                    TransactWriteItem(
                        conditionCheck = ConditionCheck(
                            tableName = tableName,
                            key = key("txn", "guard"),
                            conditionExpression = "#ok = :true",
                            expressionAttributeNames = mapOf("#ok" to "ok"),
                            expressionAttributeValues = mapOf(":true" to AttributeValue.Bool(true)),
                        ),
                    ),
                ),
            ),
        )

        val response = db.transactGetItems(
            TransactGetItemsRequest(
                listOf(
                    TransactGetItem(Get(tableName, key("txn", "new"))),
                    TransactGetItem(Get(tableName, key("txn", "update-me"))),
                    TransactGetItem(Get(tableName, key("txn", "delete-me"))),
                ),
            ),
        )

        assertEquals(3, response.responses?.size, "one positional slot per requested key")
        assertEquals(AttributeValue.N("9"), response.responses?.get(0)?.item?.get("n"))
        assertEquals(AttributeValue.N("2"), response.responses?.get(1)?.item?.get("n"))
        assertNull(response.responses?.get(2)?.item, "the deleted item is an empty slot, not a dropped one")
    }

    /**
     * A cancelled transaction, with the reasons in their original positions.
     *
     * The middle item fails; the outer two succeed and come back as the literal code `"None"`.
     * Dropping those would misalign every index after the failure, which is why the parse keeps
     * them — and this is the only test that gets the list from DynamoDB rather than from a fixture.
     */
    @Test
    fun aCancelledTransactionReportsPositionalReasons() = withTable { db, tableName ->
        db.putItem(PutItemRequest(tableName, key("cancel", "2") + mapOf("version" to AttributeValue.N("5"))))

        val error = assertFailsWith<TransactionCanceledException> {
            db.transactWriteItems(
                TransactWriteItemsRequest(
                    listOf(
                        TransactWriteItem(put = TransactPut(tableName, key("cancel", "1"))),
                        TransactWriteItem(
                            put = TransactPut(
                                tableName = tableName,
                                item = key("cancel", "2"),
                                conditionExpression = "attribute_not_exists(pk)",
                            ),
                        ),
                        TransactWriteItem(put = TransactPut(tableName, key("cancel", "3"))),
                    ),
                ),
            )
        }

        assertEquals(
            listOf("None", "ConditionalCheckFailed", "None"),
            error.cancellationReasons.map { it.code },
            "reasons are positional; the successful slots must not be filtered out",
        )
        // Atomic: nothing landed.
        assertNull(db.getItem(GetItemRequest(tableName, key("cancel", "1"), consistentRead = true)).item)
        assertNull(db.getItem(GetItemRequest(tableName, key("cancel", "3"), consistentRead = true)).item)
    }

    // -- The extension seam ---------------------------------------------------------------------

    /**
     * An operation the library does not ship, added from outside it and run against a real service.
     *
     * `DynamoDbExtensibilityTest` proves the seam compiles and signs; this proves an extension
     * actually *works* — same transport, same signing, same error handling, no privileged access.
     */
    @Test
    fun anExtensionOperationWorksAgainstARealService() = withTable { db, tableName ->
        val response = db.listTables()
        assertTrue(
            tableName in response.tableNames.orEmpty(),
            "an extension operation must see the table the built-in operations just created",
        )
    }
}

@Serializable
private class ListTablesRequest

@Serializable
private class ListTablesResponse(
    @SerialName("TableNames") val tableNames: List<String>? = null,
)

/** Written exactly as a downstream consumer would write it: public API only, outside the library. */
private suspend fun DynamoDb.listTables(): ListTablesResponse =
    client.callJson(
        operation = "ListTables",
        request = ListTablesRequest(),
        requestSerializer = ListTablesRequest.serializer(),
        responseSerializer = ListTablesResponse.serializer(),
    )
