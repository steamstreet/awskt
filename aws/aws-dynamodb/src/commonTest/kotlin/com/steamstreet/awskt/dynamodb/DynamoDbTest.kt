package com.steamstreet.awskt.dynamodb

import com.steamstreet.dynamokt.AttributeValue
import com.steamstreet.dynamokt.AttributeValueSerializer
import com.steamstreet.awskt.core.AwsCallEvent
import com.steamstreet.awskt.core.AwsCallObserver
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.BatchRetry
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.StaticCredentialsProvider
import com.steamstreet.awskt.core.awsJson
import com.steamstreet.awskt.core.callJson
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

// ---------------------------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------------------------

internal class DynamoHarness {
    val requests = mutableListOf<HttpRequestData>()
    val bodies = mutableListOf<String>()
}

internal fun harnessDynamoDb(
    harness: DynamoHarness,
    responder: (Int) -> Pair<String, HttpStatusCode>,
): DynamoDb {
    var call = 0
    val engine = MockEngine { request ->
        harness.requests += request
        harness.bodies += (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
            .bytes().decodeToString()
        val (body, status) = responder(call++)
        respond(body, status)
    }
    return DefaultDynamoDb(
        AwsServiceClient(
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET", "TOKEN")),
            endpoint = resolveEndpoint("dynamodb", "us-west-2", "https://dynamodb.us-west-2.amazonaws.com"),
            region = "us-west-2",
            protocol = DYNAMODB_PROTOCOL,
            clock = { 1_700_000_000_000 },
            random = { 1.0 },
            sleep = {},
        ),
    )
}

private fun bodyJson(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

/** A `BatchGetItem` 200 that returns one item and reports the rest as throttled. */
private const val UNPROCESSED_READ =
    """{"Responses":{"t":[{"pk":{"S":"a"}}]},"UnprocessedKeys":{"t":{"Keys":[{"pk":{"S":"b"}}]}}}"""

/** A `BatchWriteItem` 200 reporting the whole batch as throttled. */
private const val UNPROCESSED_WRITE =
    """{"UnprocessedItems":{"t":[{"DeleteRequest":{"Key":{"pk":{"S":"a"}}}}]}}"""

/**
 * A [BatchRetry] that records what it was asked to wait instead of waiting.
 *
 * `random = { 0.5 }` rather than 1.0 so the assertions distinguish full jitter from a bare
 * exponential; the 100ms base keeps the whole sequence inside the default 25-second budget, which
 * the real 1-second base would exhaust after four rounds.
 */
private fun recordingBackoff(into: MutableList<Long>): BatchRetry = BatchRetry(
    config = RetryConfig(throttlingBaseDelayMillis = 100),
    random = { 0.5 },
    sleep = { into += it },
)

// ---------------------------------------------------------------------------------------------

class AttributeValueCodecTest {

    /**
     * Exit criterion (b): all ten variants round-trip exactly.
     *
     * A repo-wide grep confirms **zero** existing tests touch `B`, `Bs`, `Null` or `Ns`, so these
     * four are entirely new coverage rather than a re-assertion of something already proven.
     */
    @Test
    fun allTenVariantsRoundTrip() {
        val cases = listOf<AttributeValue>(
            AttributeValue.S("hello"),
            AttributeValue.S(""), // legal since 2020, and a classic off-by-one in hand-rolled codecs
            AttributeValue.N("123"),
            AttributeValue.N("-0.00000000000000000000000000000000000001"),
            AttributeValue.B(byteArrayOf(0, 1, 2, -1, 127, -128)),
            AttributeValue.Bool(true),
            AttributeValue.Bool(false),
            AttributeValue.Null(),
            AttributeValue.Ss(listOf("a", "b")),
            AttributeValue.Ns(listOf("1", "2")),
            AttributeValue.Bs(listOf(byteArrayOf(1, 2), byteArrayOf(3))),
            AttributeValue.L(emptyList()),
            AttributeValue.M(emptyMap()),
            AttributeValue.L(listOf(AttributeValue.S("x"), AttributeValue.N("1"))),
            AttributeValue.M(mapOf("k" to AttributeValue.S("v"))),
        )

        for (case in cases) {
            val encoded = awsJson.encodeToString(AttributeValueSerializer, case)
            val decoded = awsJson.decodeFromString(AttributeValueSerializer, encoded)
            assertEquals(case, decoded, "round trip of $case via $encoded")
        }
    }

    /** 38 significant digits. Routing this through a Double silently corrupts it. */
    @Test
    fun largeNumbersSurviveExactly() {
        val exact = "99999999999999999999999999999999999999"
        val encoded = awsJson.encodeToString(AttributeValueSerializer, AttributeValue.N(exact))
        assertEquals("""{"N":"$exact"}""", encoded)
        assertEquals(exact, awsJson.decodeFromString(AttributeValueSerializer, encoded).asN())
    }

    @Test
    fun theWireFormIsDynamoDbJson() {
        fun encode(v: AttributeValue) = awsJson.encodeToString(AttributeValueSerializer, v)

        assertEquals("""{"S":"x"}""", encode(AttributeValue.S("x")))
        assertEquals("""{"N":"1"}""", encode(AttributeValue.N("1")))
        assertEquals("""{"BOOL":true}""", encode(AttributeValue.Bool(true)))
        assertEquals("""{"NULL":true}""", encode(AttributeValue.Null()))
        assertEquals("""{"SS":["a"]}""", encode(AttributeValue.Ss(listOf("a"))))
        assertEquals("""{"L":[]}""", encode(AttributeValue.L(emptyList())))
        assertEquals("""{"M":{}}""", encode(AttributeValue.M(emptyMap())))
        // Binary is base64.
        assertEquals("""{"B":"AQI="}""", encode(AttributeValue.B(byteArrayOf(1, 2))))
    }

    @Test
    fun deeplyNestedStructuresRoundTrip() {
        val nested = AttributeValue.M(
            mapOf(
                "l" to AttributeValue.L(
                    listOf(
                        AttributeValue.M(mapOf("inner" to AttributeValue.L(listOf(AttributeValue.Null())))),
                        AttributeValue.B(byteArrayOf(9)),
                    ),
                ),
            ),
        )
        val encoded = awsJson.encodeToString(AttributeValueSerializer, nested)
        assertEquals(nested, awsJson.decodeFromString(AttributeValueSerializer, encoded))
    }

    @Test
    fun binaryEqualityIsByContentNotIdentity() {
        assertEquals(AttributeValue.B(byteArrayOf(1, 2)), AttributeValue.B(byteArrayOf(1, 2)))
        assertEquals(
            AttributeValue.Bs(listOf(byteArrayOf(1))).hashCode(),
            AttributeValue.Bs(listOf(byteArrayOf(1))).hashCode(),
        )
        assertTrue(AttributeValue.B(byteArrayOf(1)) != AttributeValue.B(byteArrayOf(2)))
    }

    /**
     * The three set types compare as sets, which is a deliberate divergence from the AWS SDK.
     *
     * DynamoDB returns set members in an arbitrary order — a real LocalStack round trip turned
     * `NS: ["1","-2.5"]` into `["-2.5","1"]`. With order-sensitive equality a set-valued attribute
     * is unequal to itself across a write and a read, which breaks every structural diff over items
     * (`dynamokt`'s `findDifferences` most concretely). See the type's KDoc.
     */
    @Test
    fun setTypesCompareAsSetsNotAsLists() {
        assertEquals(AttributeValue.Ss(listOf("a", "b")), AttributeValue.Ss(listOf("b", "a")))
        assertEquals(AttributeValue.Ns(listOf("1", "-2.5")), AttributeValue.Ns(listOf("-2.5", "1")))
        assertEquals(
            AttributeValue.Bs(listOf(byteArrayOf(1, 2), byteArrayOf(3))),
            AttributeValue.Bs(listOf(byteArrayOf(3), byteArrayOf(1, 2))),
        )
    }

    /** Equality is worthless without a matching hashCode — a reordered set must hash the same. */
    @Test
    fun reorderedSetsHashIdentically() {
        assertEquals(
            AttributeValue.Ss(listOf("a", "b")).hashCode(),
            AttributeValue.Ss(listOf("b", "a")).hashCode(),
        )
        assertEquals(
            AttributeValue.Ns(listOf("1", "2")).hashCode(),
            AttributeValue.Ns(listOf("2", "1")).hashCode(),
        )
        assertEquals(
            AttributeValue.Bs(listOf(byteArrayOf(1), byteArrayOf(2))).hashCode(),
            AttributeValue.Bs(listOf(byteArrayOf(2), byteArrayOf(1))).hashCode(),
        )
        // The property that actually matters downstream: a reordered set is usable as a map key.
        val index = mapOf<AttributeValue, String>(AttributeValue.Ss(listOf("a", "b")) to "found")
        assertEquals("found", index[AttributeValue.Ss(listOf("b", "a"))])
    }

    /** Set semantics must not become "everything is equal". Different contents stay different. */
    @Test
    fun setsWithDifferentContentsAreStillUnequal() {
        assertTrue(AttributeValue.Ss(listOf("a")) != AttributeValue.Ss(listOf("b")))
        assertTrue(AttributeValue.Ss(listOf("a")) != AttributeValue.Ss(listOf("a", "b")))
        assertTrue(AttributeValue.Ns(listOf("1")) != AttributeValue.Ns(listOf("1", "2")))
        assertTrue(AttributeValue.Bs(listOf(byteArrayOf(1))) != AttributeValue.Bs(listOf(byteArrayOf(2))))
        // Numbers compare as strings, deliberately: N carries an exact decimal so that nothing has
        // to decide what "1" and "1.0" mean, and equality does not get to decide either.
        assertTrue(AttributeValue.Ns(listOf("1")) != AttributeValue.Ns(listOf("1.0")))
        // And a set type is never equal to a different variant holding the same members.
        val strings: AttributeValue = AttributeValue.Ss(listOf("1"))
        val numbers: AttributeValue = AttributeValue.Ns(listOf("1"))
        assertTrue(strings != numbers)
    }

    /**
     * A documented consequence rather than an accident: DynamoDB rejects duplicate set members, so a
     * set carrying them was never going to round-trip. Collapsing them agrees with the service.
     */
    @Test
    fun duplicateSetMembersCollapse() {
        assertEquals(AttributeValue.Ss(listOf("a", "a")), AttributeValue.Ss(listOf("a")))
        assertEquals(AttributeValue.Bs(listOf(byteArrayOf(1), byteArrayOf(1))), AttributeValue.Bs(listOf(byteArrayOf(1))))
    }

    /** Only equality ignores order. The wire form, and `value` itself, still preserve it. */
    @Test
    fun serializationStillPreservesSetOrder() {
        assertEquals(
            """{"SS":["b","a"]}""",
            awsJson.encodeToString(AttributeValueSerializer, AttributeValue.Ss(listOf("b", "a"))),
        )
        assertEquals(listOf("b", "a"), AttributeValue.Ss(listOf("b", "a")).value)
    }

    @Test
    fun accessorsMatchTheSdkShape() {
        val value: AttributeValue = AttributeValue.S("x")
        assertEquals("x", value.asS())
        assertEquals("x", value.asSOrNull())
        assertNull(value.asNOrNull())
        assertFailsWith<ClassCastException> { value.asN() }
    }
}

class RequestSerializationTest {

    /**
     * The empty-collection invariant. `encodeDefaults = false` does not save an *assigned*
     * `emptyMap()` — it still emits `{}`, which DynamoDB rejects.
     */
    @Test
    fun emptyCollectionsAreOmittedNotEmitted() = runTest {
        val harness = DynamoHarness()
        val db = harnessDynamoDb(harness) { """{}""" to HttpStatusCode.OK }

        db.updateItem(
            UpdateItemRequest(
                tableName = "t",
                key = mapOf("pk" to AttributeValue.S("a")),
                updateExpression = "SET #x = :v",
                expressionAttributeNames = emptyMap<String, String>().orNullIfEmpty(),
                expressionAttributeValues = emptyMap<String, AttributeValue>().orNullIfEmpty(),
            ),
        )

        val body = bodyJson(harness.bodies.single())
        assertTrue("ExpressionAttributeNames" !in body, "an empty map must be absent, not {}")
        assertTrue("ExpressionAttributeValues" !in body)
        assertTrue("TableName" in body)
    }

    @Test
    fun requestsUsePascalCaseWireNames() = runTest {
        val harness = DynamoHarness()
        val db = harnessDynamoDb(harness) { """{}""" to HttpStatusCode.OK }

        db.getItem(
            GetItemRequest(
                tableName = "my-table",
                key = mapOf("pk" to AttributeValue.S("a")),
                consistentRead = true,
            ),
        )

        val body = bodyJson(harness.bodies.single())
        assertTrue("TableName" in body && "Key" in body && "ConsistentRead" in body)
        assertEquals(
            "DynamoDB_20120810.GetItem",
            harness.requests.single().headers["X-Amz-Target"],
        )
    }

    /**
     * Exit criterion (c). The retry policy retries `TransactionInProgressException`; without a
     * token that is stable across attempts, retrying re-executes the transaction.
     */
    @Test
    fun transactWriteItemsReusesOneClientRequestTokenAcrossRetries() = runTest {
        val harness = DynamoHarness()
        val db = harnessDynamoDb(harness) { call ->
            if (call == 0) {
                """{"__type":"com.amazon.coral.service#TransactionInProgressException"}""" to
                    HttpStatusCode.BadRequest
            } else {
                """{}""" to HttpStatusCode.OK
            }
        }

        db.transactWriteItems(
            TransactWriteItemsRequest(
                listOf(TransactWriteItem(put = TransactPut("t", mapOf("pk" to AttributeValue.S("a"))))),
            ),
        )

        assertEquals(2, harness.bodies.size, "the in-progress transaction must be retried")
        val tokens = harness.bodies.map { bodyJson(it)["ClientRequestToken"]?.toString() }
        assertEquals(1, tokens.toSet().size, "every attempt must carry the identical token")
        assertEquals(36 + 2, tokens.first()!!.length, "36 chars plus JSON quotes; DynamoDB's max is 36")
    }

    /**
     * Injecting the token must not disturb anything else in the request. Guaranteed by construction
     * now that it is a `copy()`, but asserted so that reverting to a hand-built reconstruction —
     * which would silently drop any field added later — fails loudly.
     */
    @Test
    fun injectingTheClientRequestTokenPreservesEveryOtherField() = runTest {
        val harness = DynamoHarness()
        val db = harnessDynamoDb(harness) { """{}""" to HttpStatusCode.OK }

        db.transactWriteItems(
            TransactWriteItemsRequest(
                listOf(
                    TransactWriteItem(put = TransactPut("t1", mapOf("pk" to AttributeValue.S("a")))),
                    TransactWriteItem(delete = TransactDelete("t2", mapOf("pk" to AttributeValue.S("b")))),
                ),
            ),
        )

        val body = bodyJson(harness.bodies.single())
        assertTrue("ClientRequestToken" in body)
        val items = body["TransactItems"].toString()
        assertTrue("t1" in items && "t2" in items, "both transact items must survive token injection")
    }

    @Test
    fun anExplicitClientRequestTokenIsRespected() = runTest {
        val harness = DynamoHarness()
        val db = harnessDynamoDb(harness) { """{}""" to HttpStatusCode.OK }

        db.transactWriteItems(
            TransactWriteItemsRequest(
                listOf(TransactWriteItem(delete = TransactDelete("t", mapOf("pk" to AttributeValue.S("a"))))),
                clientRequestToken = "caller-supplied-token",
            ),
        )

        assertTrue("caller-supplied-token" in harness.bodies.single())
    }

    @Test
    fun updateItemIsNotRetriedOnAnAmbiguousFailureButQueryIs() = runTest {
        // Proven at the transport layer in aws-core; asserted here at the operation layer, since
        // this is where the per-operation safety choice is actually made.
        val updates = DynamoHarness()
        val updateDb = harnessDynamoDb(updates) { throw RuntimeException("read timed out") }
        assertFailsWith<RuntimeException> {
            updateDb.updateItem(UpdateItemRequest("t", mapOf("pk" to AttributeValue.S("a"))))
        }
        assertEquals(1, updates.requests.size, "ADD and list_append double-apply on a replay")

        val queries = DynamoHarness()
        var call = 0
        val queryDb = harnessDynamoDb(queries) {
            if (call++ == 0) throw RuntimeException("read timed out")
            """{"Items":[]}""" to HttpStatusCode.OK
        }
        queryDb.query(QueryRequest("t"))
        assertEquals(2, queries.requests.size, "a read is safe to replay")
    }
}

/**
 * Replay safety is a property of the **request**, not of the operation name.
 *
 * `putItem` and `deleteItem` were hardcoded `IDEMPOTENT` on the reasoning "a full overwrite:
 * replaying produces the same end state". True for the request shape that reasoning was written
 * against, and false for two others the same types expose — a condition expression and a
 * `ReturnValues` that reads prior state. Each test below fails against the hardcoded version.
 */
class AmbiguousWriteSafetyTest {

    /** DynamoDB's reply to a conditional create whose *own earlier attempt* already wrote the item. */
    private val conditionFailed =
        """{"__type":"com.amazon.coral.service#ConditionalCheckFailedException","Message":"cond"}"""

    private val key = mapOf("pk" to AttributeValue.S("a"))

    /**
     * Runs [call] against a transport that dies ambiguously on every attempt, and reports how many
     * attempts were made. One means the request was classified NOT_IDEMPOTENT; more means it was
     * replayed. `RuntimeException("read timed out")` matches none of `classifyTransportFailure`'s
     * provably-not-sent markers, so it lands in the AMBIGUOUS bucket — the one this governs.
     */
    private suspend fun attemptsUnderAmbiguousFailure(call: suspend (DynamoDb) -> Unit): Int {
        val harness = DynamoHarness()
        val db = harnessDynamoDb(harness) { throw RuntimeException("read timed out") }
        assertFailsWith<RuntimeException> { call(db) }
        return harness.requests.size
    }

    /**
     * The headline failure, end to end: the write **succeeded** on AWS, its response was lost, and
     * the replay is refused by the condition the first attempt itself satisfied.
     *
     * The assertion that matters is the exception *type*. A caller guarding a create with
     * `attribute_not_exists` treats `ConditionalCheckFailedException` as "somebody else got there
     * first" — which is exactly what it is for, and exactly what did not happen here. Surfacing the
     * transport error instead is honest: the call's outcome is genuinely unknown.
     */
    @Test
    fun aConditionalCreateNeverReportsAConflictItsOwnRetryCaused() = runTest {
        val harness = DynamoHarness()
        var call = 0
        val db = harnessDynamoDb(harness) {
            if (call++ == 0) throw RuntimeException("read timed out")
            conditionFailed to HttpStatusCode.BadRequest
        }

        // Deliberately `Throwable`, not `RuntimeException`: `DynamoDbException` descends from
        // `Exception`, so the narrower form would fail on the *type mismatch* and never reach the
        // assertion that names the actual defect.
        val failure = assertFailsWith<Throwable> {
            db.putItem(
                PutItemRequest("t", key, conditionExpression = "attribute_not_exists(pk)"),
            )
        }

        assertTrue(
            failure !is ConditionalCheckFailedException,
            "the caller must not be told its create lost a race it actually won; got $failure",
        )
        assertEquals("read timed out", failure.message)
        assertEquals(1, harness.requests.size, "the conditional write must not be replayed")
    }

    @Test
    fun aConditionalPutItemIsNotReplayed() = runTest {
        assertEquals(
            1,
            attemptsUnderAmbiguousFailure {
                it.putItem(PutItemRequest("t", key, conditionExpression = "attribute_not_exists(pk)"))
            },
        )
    }

    @Test
    fun anUnconditionalPutItemIsStillReplayed() = runTest {
        // The regression guard on the other side: fixing the conditional case must not make every
        // ordinary overwrite give up on the first blip.
        assertTrue(attemptsUnderAmbiguousFailure { it.putItem(PutItemRequest("t", key)) } > 1)
    }

    /**
     * `ALL_OLD` does not change the end state — it changes the **answer**. The first attempt would
     * have returned the prior item; the replay returns what the first attempt just wrote.
     */
    @Test
    fun aPutItemReturningAllOldIsNotReplayed() = runTest {
        assertEquals(
            1,
            attemptsUnderAmbiguousFailure {
                it.putItem(PutItemRequest("t", key, returnValues = ReturnValue.AllOld))
            },
        )
    }

    @Test
    fun aPutItemReturningNoneIsStillReplayed() = runTest {
        assertTrue(
            attemptsUnderAmbiguousFailure {
                it.putItem(PutItemRequest("t", key, returnValues = ReturnValue.None))
            } > 1,
        )
    }

    @Test
    fun aConditionalDeleteItemIsNotReplayed() = runTest {
        assertEquals(
            1,
            attemptsUnderAmbiguousFailure {
                it.deleteItem(DeleteItemRequest("t", key, conditionExpression = "version = :v"))
            },
        )
    }

    @Test
    fun aDeleteItemReturningAllOldIsNotReplayed() = runTest {
        assertEquals(
            1,
            attemptsUnderAmbiguousFailure {
                it.deleteItem(DeleteItemRequest("t", key, returnValues = ReturnValue.AllOld))
            },
        )
    }

    @Test
    fun anUnconditionalDeleteItemIsStillReplayed() = runTest {
        assertTrue(attemptsUnderAmbiguousFailure { it.deleteItem(DeleteItemRequest("t", key)) } > 1)
    }

    /**
     * Same ambiguity, control plane: the replay of a create that landed returns
     * `ResourceInUseException`, which reads as "that name is taken" rather than "you already own
     * it".
     */
    @Test
    fun createTableIsNotReplayed() = runTest {
        assertEquals(
            1,
            attemptsUnderAmbiguousFailure {
                it.createTable(
                    CreateTableRequest(
                        tableName = "t",
                        attributeDefinitions = listOf(AttributeDefinition("pk", ScalarAttributeType.S)),
                        keySchema = listOf(KeySchemaElement("pk", KeyType.Hash)),
                    ),
                )
            },
        )
    }

    /** Mirror image: a replayed delete reports `ResourceNotFoundException` for a table it removed. */
    @Test
    fun deleteTableIsNotReplayed() = runTest {
        assertEquals(1, attemptsUnderAmbiguousFailure { it.deleteTable(DeleteTableRequest("t")) })
    }

    /** `describeTable` reads; the fix must not have swept the whole control plane up with it. */
    @Test
    fun describeTableIsStillReplayed() = runTest {
        assertTrue(
            attemptsUnderAmbiguousFailure { it.describeTable(DescribeTableRequest("t")) } > 1,
        )
    }

    /** The rule itself, stated once, over every [ReturnValue]. */
    @Test
    fun writeSafetyIsDerivedFromConditionAndReturnValues() {
        assertEquals(OperationSafety.IDEMPOTENT, writeSafety(null, null))
        assertEquals(OperationSafety.IDEMPOTENT, writeSafety(null, ReturnValue.None))

        // Post-state: a replay of an unconditional write reproduces it exactly.
        assertEquals(OperationSafety.IDEMPOTENT, writeSafety(null, ReturnValue.AllNew))
        assertEquals(OperationSafety.IDEMPOTENT, writeSafety(null, ReturnValue.UpdatedNew))

        // Prior state: the replay reads what the first attempt wrote.
        assertEquals(OperationSafety.NOT_IDEMPOTENT, writeSafety(null, ReturnValue.AllOld))
        assertEquals(OperationSafety.NOT_IDEMPOTENT, writeSafety(null, ReturnValue.UpdatedOld))

        // A condition dominates, whatever the ReturnValues.
        assertEquals(OperationSafety.NOT_IDEMPOTENT, writeSafety("attribute_not_exists(pk)", null))
        assertEquals(
            OperationSafety.NOT_IDEMPOTENT,
            writeSafety("attribute_not_exists(pk)", ReturnValue.AllNew),
        )
    }
}

class ErrorMappingTest {

    @Test
    fun knownCodesBecomeTypedExceptions() = runTest {
        val harness = DynamoHarness()
        val db = harnessDynamoDb(harness) {
            """{"__type":"com.amazon.coral.service#ConditionalCheckFailedException","Message":"nope"}""" to
                HttpStatusCode.BadRequest
        }

        val error = assertFailsWith<ConditionalCheckFailedException> {
            db.putItem(PutItemRequest("t", mapOf("pk" to AttributeValue.S("a"))))
        }
        assertEquals("nope", error.message)
        assertEquals("ConditionalCheckFailedException", error.code)
    }

    /** An unknown code must still be typed and useful, not swallowed. */
    @Test
    fun unknownCodesFallThroughToDynamoDbException() = runTest {
        val harness = DynamoHarness()
        val db = harnessDynamoDb(harness) {
            """{"__type":"com.amazon.coral.service#SomeBrandNewException","Message":"future"}""" to
                HttpStatusCode.BadRequest
        }

        val error = assertFailsWith<DynamoDbException> { db.getItem(GetItemRequest("t", emptyMap())) }
        assertEquals("SomeBrandNewException", error.code)
        assertEquals("future", error.message)
    }
}

class PaginationTest {

    /**
     * DynamoDB can return an **empty** `LastEvaluatedKey`. A `!= null` check loops forever on it —
     * which is why termination checks emptiness, and why the page is emitted first.
     */
    @Test
    fun paginationTerminatesOnAnEmptyLastEvaluatedKey() = runTest {
        val harness = DynamoHarness()
        val db = harnessDynamoDb(harness) { call ->
            when (call) {
                0 -> """{"Items":[{"pk":{"S":"a"}}],"LastEvaluatedKey":{"pk":{"S":"a"}}}""" to HttpStatusCode.OK
                else -> """{"Items":[{"pk":{"S":"b"}}],"LastEvaluatedKey":{}}""" to HttpStatusCode.OK
            }
        }

        val pages = db.queryPaged(QueryRequest("t")).toList()
        assertEquals(2, pages.size, "the final page must still be delivered")
        assertEquals(2, harness.requests.size)
    }

    @Test
    fun paginationTerminatesOnAnAbsentLastEvaluatedKey() = runTest {
        val harness = DynamoHarness()
        val db = harnessDynamoDb(harness) { """{"Items":[{"pk":{"S":"a"}}]}""" to HttpStatusCode.OK }

        val items = db.queryPaged(QueryRequest("t")).items().toList()
        assertEquals(1, items.size)
        assertEquals(1, harness.requests.size)
    }

    /** The read half of a live data-loss bug: `UnprocessedKeys` must be looped, not dropped. */
    @Test
    fun batchGetAllLoopsUnprocessedKeys() = runTest {
        val harness = DynamoHarness()
        val db = harnessDynamoDb(harness) { call ->
            when (call) {
                0 -> """{"Responses":{"t":[{"pk":{"S":"a"}}]},"UnprocessedKeys":{"t":{"Keys":[{"pk":{"S":"b"}}]}}}""" to
                    HttpStatusCode.OK

                else -> """{"Responses":{"t":[{"pk":{"S":"b"}}]}}""" to HttpStatusCode.OK
            }
        }

        val items = db.batchGetAll("t", listOf(mapOf("pk" to AttributeValue.S("a"))))
        assertEquals(2, items.size, "dropping UnprocessedKeys silently returns partial results")
        assertEquals(2, harness.requests.size)
    }

    /**
     * Every chunk must carry the caller's read settings, not just the first.
     *
     * This is what the shared template plus `copy(keys = chunk)` buys: a field added to
     * `KeysAndAttributes` later reaches every chunk without this loop being revisited.
     */
    @Test
    fun batchGetAllAppliesReadSettingsToEveryChunk() = runTest {
        val harness = DynamoHarness()
        val db = harnessDynamoDb(harness) { """{"Responses":{"t":[]}}""" to HttpStatusCode.OK }

        // 150 keys => two chunks, since DynamoDB caps BatchGetItem at 100.
        db.batchGetAll(
            tableName = "t",
            keys = (1..150).map { mapOf("pk" to AttributeValue.S("k$it")) },
            consistentRead = true,
            projectionExpression = "#a",
            expressionAttributeNames = mapOf("#a" to "alpha"),
        )

        assertEquals(2, harness.bodies.size, "150 keys must chunk to 100 + 50")
        for (body in harness.bodies) {
            assertTrue("ConsistentRead" in body, "every chunk must be consistent-read")
            assertTrue("#a" in body && "alpha" in body, "every chunk must carry the projection")
        }
    }

    /**
     * Resubmitting an unprocessed set is a *throttling* retry, and must be paced like one.
     *
     * The delays asserted here are exactly full jitter — `random * min(cap, base * 2^retry)` —
     * with `random` pinned to 0.5, so this also pins down that the jitter is applied rather than
     * the raw exponential being used: 50/100/200/400/800 is half of 100/200/400/800/1600.
     *
     * `currentTime` is the load-bearing assertion for "injected sleep, not wall-clock". `runTest`
     * runs on a virtual clock, so a real `delay(1000)` would not slow the test down at all — it
     * would just advance `currentTime` to 1000. Asserting it stayed at zero is what proves the
     * backoff went through [BatchRetry.sleep] and not through `delay`.
     */
    @Test
    fun batchWriteAllBacksOffExponentiallyBetweenRounds() = runTest {
        val harness = DynamoHarness()
        // Six throttled responses, then a clean one.
        val db = harnessDynamoDb(harness) { call ->
            if (call < 6) UNPROCESSED_WRITE to HttpStatusCode.OK else "{}" to HttpStatusCode.OK
        }

        val slept = mutableListOf<Long>()
        db.batchWriteAll(
            tableName = "t",
            writes = listOf(WriteRequest(deleteRequest = DeleteRequest(mapOf("pk" to AttributeValue.S("a"))))),
            backoff = recordingBackoff(slept),
        )

        assertEquals(7, harness.requests.size, "six throttled rounds, then the successful one")
        assertEquals(
            listOf(50L, 100L, 200L, 400L, 800L, 1600L),
            slept,
            "each resubmission must wait twice as long as the last",
        )
        assertEquals(0L, testScheduler.currentTime, "backoff must go through the injected sleep, not delay()")
    }

    /** The read path pays the same backoff — the bug and the fix are symmetric. */
    @Test
    fun batchGetAllBacksOffExponentiallyBetweenRounds() = runTest {
        val harness = DynamoHarness()
        val db = harnessDynamoDb(harness) { call ->
            if (call < 3) UNPROCESSED_READ to HttpStatusCode.OK
            else """{"Responses":{"t":[{"pk":{"S":"b"}}]}}""" to HttpStatusCode.OK
        }

        val slept = mutableListOf<Long>()
        val items = db.batchGetAll(
            tableName = "t",
            keys = listOf(mapOf("pk" to AttributeValue.S("a"))),
            backoff = recordingBackoff(slept),
        )

        assertEquals(4, items.size, "one item per round, and none of them may be dropped")
        assertEquals(listOf(50L, 100L, 200L), slept)
        assertEquals(0L, testScheduler.currentTime, "backoff must go through the injected sleep, not delay()")
    }

    /**
     * The backoff is bounded, and the bound is what makes it safe to add at all.
     *
     * Ten rounds at the default 1-second throttling base would block for roughly two and a half
     * minutes — long enough to turn a partial batch failure into an unexplained Lambda timeout. The
     * budget stops the loop first, and the loop still throws rather than returning a short result.
     */
    @Test
    fun batchGetAllStopsWhenTheBackoffBudgetIsExhausted() = runTest {
        val harness = DynamoHarness()
        val db = harnessDynamoDb(harness) { UNPROCESSED_READ to HttpStatusCode.OK }

        val slept = mutableListOf<Long>()
        val backoff = BatchRetry(
            // 100 + 200 + 400 fits in 750ms; the fourth wait of 800ms does not.
            config = RetryConfig(throttlingBaseDelayMillis = 100, maxTotalRetryDuration = 750.milliseconds),
            random = { 1.0 },
            sleep = { slept += it },
        )

        val failure = assertFailsWith<DynamoDbException> {
            db.batchGetAll("t", listOf(mapOf("pk" to AttributeValue.S("a"))), backoff = backoff)
        }

        assertEquals("UnprocessedKeysRemain", failure.code)
        assertEquals(listOf(100L, 200L, 400L), slept, "the budget must stop the loop before maxRounds")
        assertEquals(4, harness.requests.size, "three resubmissions, then give up")
        assertTrue("750ms" in (failure.message ?: ""), "the message should name the budget it hit")
    }

    /** The write half: chunk to 25 and loop `UnprocessedItems`. */
    @Test
    fun batchWriteAllChunksToTwentyFive() = runTest {
        val harness = DynamoHarness()
        val db = harnessDynamoDb(harness) { """{}""" to HttpStatusCode.OK }

        val writes = (1..60).map {
            WriteRequest(deleteRequest = DeleteRequest(mapOf("pk" to AttributeValue.S("k$it"))))
        }
        db.batchWriteAll("t", writes)

        assertEquals(3, harness.requests.size, "60 writes must become 25 + 25 + 10")
    }
}

// ---------------------------------------------------------------------------------------------
// Extensibility
// ---------------------------------------------------------------------------------------------

@Serializable
private class DescribeLimitsRequest

@Serializable
private class DescribeLimitsResponse(
    @SerialName("TableMaxWriteCapacityUnits") val tableMaxWriteCapacityUnits: Long? = null,
)

/**
 * A 6th operation, written exactly as a downstream consumer would write it: an extension function
 * on [DynamoDb], using only public API, in a file the library does not own.
 */
private suspend fun DynamoDb.describeLimits(): DescribeLimitsResponse =
    client.callJson(
        operation = "DescribeLimits",
        request = DescribeLimitsRequest(),
        requestSerializer = DescribeLimitsRequest.serializer(),
        responseSerializer = DescribeLimitsResponse.serializer(),
    )

class DynamoDbExtensibilityTest {

    /**
     * Proves the extension seam rather than documenting it.
     *
     * If `DynamoDb.client` were ever made internal, or `callJson` non-public, this test stops
     * compiling — which is the point. It is a compile-time guard on an API property that is
     * otherwise easy to erode.
     */
    @Test
    fun anOperationTheLibraryDoesNotShipCanBeAddedDownstream() = runTest {
        val harness = DynamoHarness()
        val db = harnessDynamoDb(harness) { """{"TableMaxWriteCapacityUnits":40000}""" to HttpStatusCode.OK }

        val response = db.describeLimits()

        assertEquals(40_000, response.tableMaxWriteCapacityUnits)
        // It is signed and targeted exactly like a built-in operation.
        val request = harness.requests.single()
        assertEquals("DynamoDB_20120810.DescribeLimits", request.headers["X-Amz-Target"])
        assertTrue(request.headers["Authorization"]!!.startsWith("AWS4-HMAC-SHA256 "))
    }

    /** An extension inherits the retry policy too — it is not a second-class path. */
    @Test
    fun anExtensionOperationInheritsRetryAndErrorHandling() = runTest {
        val harness = DynamoHarness()
        var call = 0
        val db = harnessDynamoDb(harness) {
            if (call++ == 0) {
                """{"__type":"com.amazon.coral.service#ThrottlingException"}""" to HttpStatusCode.BadRequest
            } else {
                """{"TableMaxWriteCapacityUnits":1}""" to HttpStatusCode.OK
            }
        }

        assertEquals(1, db.describeLimits().tableMaxWriteCapacityUnits)
        assertEquals(2, harness.requests.size, "the extension was retried like any built-in call")
    }
}

/**
 * `DynamoDbConfig.observer` reaches the transport that does the work.
 *
 * Driven through the real `DynamoDb { }` factory rather than the harness above, because the factory
 * is the thing that can drop the wiring: the harness constructs `AwsServiceClient` itself and would
 * pass whether or not `DynamoDb()` forwarded the field.
 */
class DynamoDbObserverWiringTest {

    @Test
    fun theConfiguredObserverSeesEveryAttemptAndTheRetryBetweenThem() = runTest {
        val events = mutableListOf<AwsCallEvent>()
        var call = 0
        val engine = MockEngine {
            if (call++ == 0) {
                respond(
                    """{"__type":"com.amazon.coral.service#ThrottlingException"}""",
                    HttpStatusCode.BadRequest,
                )
            } else {
                respond("""{"Item":{}}""", HttpStatusCode.OK)
            }
        }

        val db = DynamoDb {
            region = "us-west-2"
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET"))
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false }
            observer = AwsCallObserver { events += it }
        }

        db.getItem(GetItemRequest("my-table", mapOf("pk" to AttributeValue.S("a"))))

        assertEquals(
            listOf(
                AwsCallEvent.Outcome.SERVICE_ERROR,
                AwsCallEvent.Outcome.RETRY_SCHEDULED,
                AwsCallEvent.Outcome.SUCCESS,
            ),
            events.map { it.outcome },
        )
        // The operation name is what makes these events dimensionable per API call.
        assertEquals(listOf("GetItem"), events.map { it.operation }.distinct())
    }
}
