package com.steamstreet.awskt.dynamodb

import com.steamstreet.dynamokt.AttributeValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M3 exit criterion (d): the whole client, against real DynamoDB.
 *
 * ### Why this exists when 163 offline tests are already green
 *
 * None of them proves a single production request would work. The vector corpus proves the
 * signature is arithmetically right; the differential harnesses prove the payload matches the AWS
 * SDK's byte for byte; MockEngine proves the retry and error paths behave. **All of that is
 * consistent with AWS rejecting every request** — and the `aws-signing` milestone already produced
 * one live failure (the JDK's legacy `HttpURLConnection` rewriting headers after signing) that no
 * offline test could have caught, because the bug was not in anything an offline test observes.
 *
 * So this is deliberately end-to-end and deliberately unmocked: a real table is created, written,
 * read, queried, transacted and deleted. A `SignatureDoesNotMatch` here fails the milestone.
 *
 * ### Running it
 *
 * ```
 * eval "$(aws configure export-credentials --profile ai-vegasful-test-deploy --format env)" \
 *   && AWS_REGION=us-west-2 ./gradlew :aws:aws-dynamodb:jvmTest
 * ```
 *
 * `eval` is what keeps the secret out of the shell's history and out of stdout. Without credentials
 * in the environment every test here self-skips, so CI and offline development stay green.
 *
 * **The credentials must be able to create a table.** Verified 2026-08-10: in account
 * `443844975891`, *neither* `ai-vegasful-test` (`AgentReadOnly`) nor `ai-vegasful-test-deploy`
 * (`AgentDeploy`) is granted `dynamodb:CreateTable`. Both sign perfectly and are then refused — an
 * IAM gap, not a client one. Two ways out:
 *
 * 1. grant `dynamodb:CreateTable`/`DeleteTable` on `arn:…:table/awskt-live-smoke-*` to whichever
 *    role the tests run as; or
 * 2. point the tests at a table that already exists:
 *    `AWSKT_LIVE_TABLE=<name>` — it must have a `String` hash key `pk` and a `String` range key
 *    `sk`, and the tests then create and delete nothing. They still write items, so it must be a
 *    scratch table.
 *
 * With neither, the write-path tests self-skip rather than fail — but
 * [awsAuthenticatesUsEvenWhenItRefusesTheAction] stays meaningful regardless, because an
 * *authorization* refusal is itself proof the signature was accepted.
 *
 * Uses `runBlocking`, not `runTest`: `runTest` runs on a virtual clock that skips `delay`, which
 * would turn the "wait for the table to become ACTIVE" loop into an unthrottled poll against a real
 * AWS API.
 */
class LiveDynamoDbTest {

    private fun credentialsPresent(): Boolean = !System.getenv("AWS_ACCESS_KEY_ID").isNullOrEmpty()

    private val region: String get() = System.getenv("AWS_REGION") ?: "us-west-2"

    /**
     * Creates a scratch on-demand table, runs [block] against it, and deletes it — deleting even
     * when the body fails, because a leaked table is a real (if small) bill and a confusing artefact
     * in someone's account.
     *
     * With `AWSKT_LIVE_TABLE` set, the supplied table is used as-is and nothing is created or
     * deleted. Its key schema is checked first: a mismatch would otherwise surface as a
     * `ValidationException` from every write, which reads like a client bug.
     */
    private fun withScratchTable(block: suspend (DynamoDb, String) -> Unit) {
        if (!credentialsPresent()) {
            println("[live] skipped — no AWS credentials in the environment")
            return
        }

        System.getenv("AWSKT_LIVE_TABLE")?.takeIf { it.isNotBlank() }?.let { supplied ->
            DynamoDb { region = this@LiveDynamoDbTest.region }.use { db ->
                runBlocking {
                    val table = assertNotNull(
                        db.describeTable(DescribeTableRequest(supplied)).table,
                        "AWSKT_LIVE_TABLE=$supplied could not be described",
                    )
                    assertEquals(
                        listOf("pk" to KeyType.Hash, "sk" to KeyType.Range),
                        table.keySchema?.map { it.attributeName to it.keyType },
                        "AWSKT_LIVE_TABLE must have a String hash key 'pk' and a String range key 'sk'",
                    )
                    block(db, supplied)
                }
            }
            return
        }

        val tableName = "awskt-live-smoke-" + Random.nextLong().toULong().toString(16)
        DynamoDb { region = this@LiveDynamoDbTest.region }.use { db ->
            runBlocking {
                try {
                    db.createTable(
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
                            // On-demand: no capacity to provision, nothing to pay for while idle.
                            billingMode = BillingMode.PayPerRequest,
                        ),
                    )
                } catch (e: DynamoDbException) {
                    // Read-only credentials are a permission problem, not a client problem — and
                    // failing here would read as "the client is broken", which is the opposite of
                    // what an authenticated refusal proves. Skip loudly instead.
                    if (e.code == "AccessDeniedException") {
                        println(
                            "[live] SKIPPED — these credentials cannot create a table, so the write " +
                                "path is unverified. Re-run with a profile that has dynamodb:CreateTable. " +
                                "AWS said: ${e.message}",
                        )
                        return@runBlocking
                    }
                    throw e
                }
                try {
                    awaitActive(db, tableName)
                    block(db, tableName)
                } finally {
                    runCatching { db.deleteTable(DeleteTableRequest(tableName)) }
                        .onFailure { println("[live] WARNING: failed to delete $tableName: $it") }
                }
            }
        }
    }

    private suspend fun awaitActive(db: DynamoDb, tableName: String) {
        repeat(60) {
            val status = db.describeTable(DescribeTableRequest(tableName)).table?.tableStatus
            if (status == "ACTIVE") return
            delay(1_000)
        }
        fail("table $tableName never became ACTIVE")
    }

    /**
     * The oracle that survives read-only credentials.
     *
     * An `AccessDeniedException` naming our own IAM principal is only reachable **after** AWS has
     * verified the signature — an unsigned or wrongly-signed request never gets as far as an
     * authorization decision, it comes back `InvalidSignatureException`. So this asserts the one
     * thing the whole project's risk register cares about (AWS accepts what we sign) without
     * needing permission to write anything.
     */
    @Test
    fun awsAuthenticatesUsEvenWhenItRefusesTheAction() {
        if (!credentialsPresent()) {
            println("[live] skipped — no AWS credentials in the environment")
            return
        }

        DynamoDb { region = this@LiveDynamoDbTest.region }.use { db ->
            runBlocking {
                // A table nobody has: either ResourceNotFoundException (we are allowed and it is
                // absent) or AccessDeniedException (we are not allowed). Both are *authenticated*
                // answers. Only a signature failure is a real failure here.
                val error = runCatching {
                    db.describeTable(DescribeTableRequest("awskt-live-no-such-table-${Random.nextLong()}"))
                }.exceptionOrNull()

                val failure = error as? DynamoDbException ?: fail("expected a typed DynamoDB error, got $error")
                assertTrue(
                    failure.code == "ResourceNotFoundException" || failure.code == "AccessDeniedException",
                    "AWS must have authenticated the request; got code=${failure.code}",
                )
                assertNotNull(failure.requestId, "an authenticated AWS error always carries a request id")
                println("[live] AWS authenticated a signed request — ${failure.code}, id ${failure.requestId}")
            }
        }
    }

    /**
     * Create, put, get, query — with every `AttributeValue` variant in the item.
     *
     * The variants are the point. `B`, `Bs`, `Ns` and `Null` had zero coverage anywhere in this
     * repo before this project, and they are exactly the ones where a base64 or set-encoding
     * mistake survives every round-trip test (which would decode its own mistake back) and is only
     * caught by DynamoDB itself validating the payload.
     */
    @Test
    fun createsPutsGetsAndQueriesAgainstRealAws() = withScratchTable { db, tableName ->
        val item: Item = mapOf(
            "pk" to AttributeValue.S("customer#1"),
            "sk" to AttributeValue.S("order#1"),
            "emptyString" to AttributeValue.S(""),
            "bigNumber" to AttributeValue.N("99999999999999999999999999999999999999"),
            "negative" to AttributeValue.N("-0.00000000000000000000000000000000000001"),
            "binary" to AttributeValue.B(byteArrayOf(0, 1, 2, -1, 127, -128)),
            "flag" to AttributeValue.Bool(false),
            "absent" to AttributeValue.Null(),
            "strings" to AttributeValue.Ss(listOf("a", "b")),
            "numbers" to AttributeValue.Ns(listOf("1", "-2.5")),
            "blobs" to AttributeValue.Bs(listOf(byteArrayOf(1, 2), byteArrayOf(3))),
            "list" to AttributeValue.L(listOf(AttributeValue.S("x"), AttributeValue.M(emptyMap()))),
            "map" to AttributeValue.M(mapOf("nested" to AttributeValue.L(emptyList()))),
        )

        db.putItem(PutItemRequest(tableName, item))

        val read = assertNotNull(
            db.getItem(
                GetItemRequest(
                    tableName = tableName,
                    key = mapOf("pk" to AttributeValue.S("customer#1"), "sk" to AttributeValue.S("order#1")),
                    consistentRead = true,
                ),
            ).item,
            "the item just written must be readable",
        )

        for ((name, expected) in item) {
            assertEquals(expected, read[name], "attribute '$name' did not survive the round trip")
        }

        val pages = db.queryPaged(
            QueryRequest(
                tableName = tableName,
                keyConditionExpression = "#pk = :pk",
                expressionAttributeNames = mapOf("#pk" to "pk"),
                expressionAttributeValues = mapOf(":pk" to AttributeValue.S("customer#1")),
                consistentRead = true,
            ),
        ).toList()

        assertEquals(1, pages.sumOf { it.items?.size ?: 0 }, "the query must find the item")
        // The paginator's termination rule, exercised by AWS rather than by a fixture.
        assertTrue(pages.last().lastEvaluatedKey.isNullOrEmpty(), "the last page must not ask for another")

        println("[live] create/put/get/query round-tripped all 10 AttributeValue variants on $tableName")
    }

    /** An atomic multi-item write, with a stable `ClientRequestToken` supplied by the client. */
    @Test
    fun transactWriteItemsAgainstRealAws() = withScratchTable { db, tableName ->
        db.transactWriteItems(
            TransactWriteItemsRequest(
                listOf(
                    TransactWriteItem(
                        put = TransactPut(
                            tableName,
                            mapOf(
                                "pk" to AttributeValue.S("txn"),
                                "sk" to AttributeValue.S("a"),
                                "n" to AttributeValue.N("1"),
                            ),
                        ),
                    ),
                    TransactWriteItem(
                        put = TransactPut(
                            tableName,
                            mapOf(
                                "pk" to AttributeValue.S("txn"),
                                "sk" to AttributeValue.S("b"),
                                "n" to AttributeValue.N("2"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val response = db.transactGetItems(
            TransactGetItemsRequest(
                listOf(
                    TransactGetItem(
                        Get(tableName, mapOf("pk" to AttributeValue.S("txn"), "sk" to AttributeValue.S("a"))),
                    ),
                    TransactGetItem(
                        Get(tableName, mapOf("pk" to AttributeValue.S("txn"), "sk" to AttributeValue.S("b"))),
                    ),
                    TransactGetItem(
                        Get(tableName, mapOf("pk" to AttributeValue.S("txn"), "sk" to AttributeValue.S("missing"))),
                    ),
                ),
            ),
        )

        assertEquals(3, response.responses?.size, "TransactGetItems returns one positional slot per request")
        assertEquals(AttributeValue.N("1"), response.responses?.get(0)?.item?.get("n"))
        assertEquals(AttributeValue.N("2"), response.responses?.get(1)?.item?.get("n"))
        assertNull(response.responses?.get(2)?.item, "a miss is an empty slot, not a dropped one")

        println("[live] transactWriteItems + transactGetItems round-tripped on $tableName")
    }

    /**
     * The error path, end to end — and specifically the *structured payload* in the error body.
     *
     * `ConditionalCheckFailedException.item` is read out of the error body, which no offline test
     * can prove AWS actually populates the way the fixtures assume. This is the assertion that ties
     * the canned corpus back to reality.
     */
    @Test
    fun aFailedConditionReturnsTheLosingItemFromRealAws() = withScratchTable { db, tableName ->
        val key = mapOf("pk" to AttributeValue.S("cond"), "sk" to AttributeValue.S("1"))
        db.putItem(PutItemRequest(tableName, key + mapOf("version" to AttributeValue.N("7"))))

        val error = runCatching {
            db.putItem(
                PutItemRequest(
                    tableName = tableName,
                    item = key + mapOf("version" to AttributeValue.N("8")),
                    conditionExpression = "attribute_not_exists(pk)",
                    returnValuesOnConditionCheckFailure = ReturnValuesOnConditionCheckFailure.AllOld,
                ),
            )
        }.exceptionOrNull()

        val failure = error as? ConditionalCheckFailedException
            ?: fail("expected ConditionalCheckFailedException, got $error")

        assertEquals(
            AttributeValue.N("7"),
            assertNotNull(failure.item, "AWS returns the losing item when asked; it must survive our error path")
                ["version"],
        )
        assertNotNull(failure.requestId, "every AWS error must carry a request id")

        println("[live] a failed condition returned the losing item, request id ${failure.requestId}")
    }

    /** A batch large enough to chunk, proving the loop that fixes the two live data-loss bugs. */
    @Test
    fun batchWriteAllAndBatchGetAllAgainstRealAws() = withScratchTable { db, tableName ->
        val keys = (1..30).map {
            mapOf("pk" to AttributeValue.S("batch"), "sk" to AttributeValue.S("item-$it"))
        }

        // 30 > DynamoDB's per-call limit of 25, so this must chunk or AWS rejects it outright.
        db.batchWriteAll(tableName, keys.map { WriteRequest(putRequest = PutRequest(it)) })

        val read = db.batchGetAll(tableName, keys, consistentRead = true)
        assertEquals(30, read.size, "every written item must come back")

        db.batchWriteAll(tableName, keys.map { WriteRequest(deleteRequest = DeleteRequest(it)) })
        assertEquals(0, db.batchGetAll(tableName, keys, consistentRead = true).size)

        println("[live] batchWriteAll/batchGetAll chunked 30 items correctly on $tableName")
    }
}
