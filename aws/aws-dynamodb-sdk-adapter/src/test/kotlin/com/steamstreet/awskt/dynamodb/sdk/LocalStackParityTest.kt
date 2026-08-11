package com.steamstreet.awskt.dynamodb.sdk

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider as SdkStaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.smithy.kotlin.runtime.net.url.Url
import com.steamstreet.awskt.core.StaticCredentialsProvider
import com.steamstreet.awskt.dynamodb.AttributeDefinition
import com.steamstreet.awskt.dynamodb.AttributeValue
import com.steamstreet.awskt.dynamodb.BillingMode
import com.steamstreet.awskt.dynamodb.ConditionalCheckFailedException
import com.steamstreet.awskt.dynamodb.CreateTableRequest
import com.steamstreet.awskt.dynamodb.DeleteRequest
import com.steamstreet.awskt.dynamodb.DeleteTableRequest
import com.steamstreet.awskt.dynamodb.DescribeTableRequest
import com.steamstreet.awskt.dynamodb.DynamoDb
import com.steamstreet.awskt.dynamodb.GetItemRequest
import com.steamstreet.awskt.dynamodb.Item
import com.steamstreet.awskt.dynamodb.KeySchemaElement
import com.steamstreet.awskt.dynamodb.KeyType
import com.steamstreet.awskt.dynamodb.PutItemRequest
import com.steamstreet.awskt.dynamodb.PutRequest
import com.steamstreet.awskt.dynamodb.QueryRequest
import com.steamstreet.awskt.dynamodb.ResourceNotFoundException
import com.steamstreet.awskt.dynamodb.ReturnValue
import com.steamstreet.awskt.dynamodb.ReturnValuesOnConditionCheckFailure
import com.steamstreet.awskt.dynamodb.ScalarAttributeType
import com.steamstreet.awskt.dynamodb.TransactPut
import com.steamstreet.awskt.dynamodb.TransactWriteItem
import com.steamstreet.awskt.dynamodb.TransactWriteItemsRequest
import com.steamstreet.awskt.dynamodb.TransactionCanceledException
import com.steamstreet.awskt.dynamodb.UpdateItemRequest
import com.steamstreet.awskt.dynamodb.WriteRequest
import com.steamstreet.awskt.dynamodb.batchGetAll
import com.steamstreet.awskt.dynamodb.batchWriteAll
import com.steamstreet.awskt.dynamodb.items
import com.steamstreet.awskt.dynamodb.queryPaged
import com.steamstreet.awskt.signing.AwsCredentials
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two implementations, run side by side against one real DynamoDB.
 *
 * ### Why this is the test that makes M5a landable
 *
 * The M5a/M5b split rests on a single claim: **a consumer cannot tell `SdkBackedDynamoDb` from
 * `DefaultDynamoDb`**. If that holds, the type swap can land first — validated by the existing
 * integration suites against behaviour that is still, byte for byte, the AWS SDK's — and the
 * implementation flip that follows is a one-line revert. If it does not hold, the split buys
 * nothing, because a failure after the swap is ambiguous between the two changes.
 *
 * Nothing else in the project tests that claim. The differential harnesses compare our client to
 * the *SDK's own types*; this compares our client to the *adapter*, through the same interface,
 * against the same service, on the same data. It is the only place a conversion bug in
 * `Conversions.kt` — a dropped optional, a mis-mapped enum, an exception with the wrong payload —
 * shows up as a behavioural difference rather than as an unexercised line.
 *
 * Every test runs its body twice, once per implementation, and compares the two results. That
 * shape is deliberate: an assertion written against one implementation's *expected* output can be
 * wrong in the same way the implementation is, but the two implementations cannot be wrong
 * identically without the bug being in shared code — which the other suites already cover.
 *
 * Like [com.steamstreet.awskt.dynamodb.LocalStack], this proves behaviour, **not signing**:
 * LocalStack accepts fabricated credentials with IAM enforcement off.
 */
class LocalStackParityTest {

    private object Container {
        val available: Boolean by lazy {
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        }

        // 4.0, not 3.0: 3.0 predates ReturnValuesOnConditionCheckFailure and silently ignores it.
        private val instance: LocalStackContainer by lazy {
            LocalStackContainer(DockerImageName.parse("localstack/localstack:4.0"))
                .withServices(LocalStackContainer.Service.DYNAMODB)
                .also { it.start() }
        }

        val endpoint: String
            get() = instance.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString()
    }

    /** Our hand-written client. */
    private fun handWritten(): DynamoDb = DynamoDb {
        region = "us-east-1"
        endpointUrl = Container.endpoint
        credentialsProvider = StaticCredentialsProvider(AwsCredentials("DummyKey", "DummySecret"))
    }

    /** The same interface, over the AWS SDK. */
    private fun sdkBacked(): DynamoDb = SdkBackedDynamoDb(
        DynamoDbClient {
            region = "us-east-1"
            endpointUrl = Url.parse(Container.endpoint)
            credentialsProvider = SdkStaticCredentialsProvider {
                accessKeyId = "DummyKey"
                secretAccessKey = "DummySecret"
            }
        },
    )

    /**
     * Runs [block] once per implementation, each against its own fresh table, and asserts the two
     * results are equal.
     *
     * Separate tables rather than a shared one, so the second run cannot observe the first's writes
     * and "agree" for the wrong reason.
     */
    private fun <T> assertParity(block: suspend (DynamoDb, String) -> T) {
        if (!Container.available) {
            println("[localstack] skipped — Docker is not available")
            return
        }

        fun run(db: DynamoDb): T = db.use {
            val tableName = "parity-" + Random.nextLong().toULong().toString(16)
            runBlocking {
                it.createTable(
                    CreateTableRequest(
                        tableName = tableName,
                        attributeDefinitions = listOf(
                            AttributeDefinition("pk", ScalarAttributeType.S),
                            AttributeDefinition("sk", ScalarAttributeType.S),
                        ),
                        keySchema = listOf(
                            KeySchemaElement("pk", KeyType.Hash),
                            KeySchemaElement("sk", KeyType.Range),
                        ),
                        billingMode = BillingMode.PayPerRequest,
                    ),
                )
                try {
                    block(it, tableName)
                } finally {
                    runCatching { it.deleteTable(DeleteTableRequest(tableName)) }
                }
            }
        }

        val ours = run(handWritten())
        val theirs = run(sdkBacked())
        assertEquals(ours, theirs, "the two implementations disagreed")
    }

    private fun key(pk: String, sk: String): Item =
        mapOf("pk" to AttributeValue.S(pk), "sk" to AttributeValue.S(sk))

    // -- Reads and writes -----------------------------------------------------------------------

    /**
     * Every variant, both implementations.
     *
     * Compared with plain equality, sets included: `Ss`/`Ns`/`Bs` compare as sets, so the arbitrary
     * order DynamoDB returns members in cannot make this fail for a reason nobody cares about.
     */
    @Test
    fun everyAttributeValueVariantRoundTripsIdentically() = assertParity { db, tableName ->
        val item = key("variants", "1") + mapOf(
            "emptyString" to AttributeValue.S(""),
            "bigNumber" to AttributeValue.N("99999999999999999999999999999999999999"),
            "binary" to AttributeValue.B(byteArrayOf(0, 1, -1, 127, -128)),
            "flag" to AttributeValue.Bool(false),
            "absent" to AttributeValue.Null(),
            "strings" to AttributeValue.Ss(listOf("a", "b")),
            "numbers" to AttributeValue.Ns(listOf("1", "-2.5")),
            "blobs" to AttributeValue.Bs(listOf(byteArrayOf(1, 2), byteArrayOf(3))),
            "emptyList" to AttributeValue.L(emptyList()),
            "emptyMap" to AttributeValue.M(emptyMap()),
            "nested" to AttributeValue.M(mapOf("in" to AttributeValue.L(listOf(AttributeValue.N("1"))))),
        )

        db.putItem(PutItemRequest(tableName, item))
        val read = assertNotNull(
            db.getItem(GetItemRequest(tableName, key("variants", "1"), consistentRead = true)).item,
        )
        assertEquals(item.size, read.size)
        read
    }

    @Test
    fun getItemOnAMissAgreesOnNull() = assertParity { db, tableName ->
        db.getItem(GetItemRequest(tableName, key("nobody", "home"), consistentRead = true)).item
    }

    @Test
    fun updateItemAgreesOnSetAddAndRemove() = assertParity { db, tableName ->
        db.putItem(
            PutItemRequest(
                tableName,
                key("upd", "1") + mapOf("counter" to AttributeValue.N("1"), "doomed" to AttributeValue.S("x")),
            ),
        )
        db.updateItem(
            UpdateItemRequest(
                tableName = tableName,
                key = key("upd", "1"),
                updateExpression = "SET #label = :label ADD #counter :one REMOVE #doomed",
                expressionAttributeNames = mapOf("#label" to "label", "#counter" to "counter", "#doomed" to "doomed"),
                expressionAttributeValues = mapOf(":label" to AttributeValue.S("set"), ":one" to AttributeValue.N("1")),
                returnValues = ReturnValue.AllNew,
            ),
        ).attributes
    }

    @Test
    fun queryPaginationAgreesPageForPage() = assertParity { db, tableName ->
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
        val items = db.queryPaged(request).items().toList()
        assertTrue(pages.size >= 3)
        // Page count plus the ordered item list: a paginator that terminated early or looped an
        // extra time on an empty LastEvaluatedKey diverges here rather than silently.
        pages.size to items.map { it["n"]?.asN() }
    }

    @Test
    fun batchChunkingAgrees() = assertParity { db, tableName ->
        val keys = (1..60).map { key("batch", "item-$it") }
        db.batchWriteAll(tableName, keys.map { WriteRequest(putRequest = PutRequest(it)) })
        val read = db.batchGetAll(tableName, keys, consistentRead = true)
        assertEquals(60, read.size)
        read.mapNotNull { it["sk"]?.asS() }.sorted()
    }

    // -- Errors ---------------------------------------------------------------------------------

    /**
     * The error hierarchy is the part a consumer's `catch` blocks are written against, so it has to
     * be identical — including the payload the exception carries, not just its type.
     */
    @Test
    fun aFailedConditionAgreesOnTypeMessageAndItem() = assertParity { db, tableName ->
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

        // requestId is deliberately excluded — it differs per call and proves nothing about parity.
        // The item is returned as itself, not stringified: AttributeValue equality is structural
        // (and set-aware), where toString() on a set type is not.
        listOf(error.code, error.message, error.statusCode.toString(), error.item)
    }

    @Test
    fun aCancelledTransactionAgreesOnPositionalReasons() = assertParity { db, tableName ->
        db.putItem(PutItemRequest(tableName, key("cancel", "2")))

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

        assertEquals(3, error.cancellationReasons.size, "reasons must stay positional on both sides")
        assertNull(db.getItem(GetItemRequest(tableName, key("cancel", "1"), consistentRead = true)).item)
        error.cancellationReasons.map { it.code }
    }

    @Test
    fun aMissingTableAgreesOnResourceNotFound() = assertParity { db, _ ->
        val error = assertFailsWith<ResourceNotFoundException> {
            db.describeTable(DescribeTableRequest("parity-no-such-table"))
        }
        error.code to error.statusCode
    }

    // -- Control plane --------------------------------------------------------------------------

    /** `TableDescription` is narrowed by hand on both paths, so the narrowing must agree. */
    @Test
    fun describeTableAgreesOnTheNarrowedDescription() = assertParity { db, tableName ->
        val table = assertNotNull(db.describeTable(DescribeTableRequest(tableName)).table)
        listOf(
            table.tableStatus,
            table.keySchema?.map { it.attributeName to it.keyType }.toString(),
            table.attributeDefinitions?.map { it.attributeName to it.attributeType }.toString(),
            table.globalSecondaryIndexes?.size.toString(),
        )
    }
}
