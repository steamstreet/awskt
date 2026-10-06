package com.steamstreet.dynamokt

import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.dynamodb.BatchGetItemRequest
import com.steamstreet.awskt.dynamodb.BatchGetItemResponse
import com.steamstreet.awskt.dynamodb.BatchWriteItemRequest
import com.steamstreet.awskt.dynamodb.BatchWriteItemResponse
import com.steamstreet.awskt.dynamodb.CreateTableRequest
import com.steamstreet.awskt.dynamodb.CreateTableResponse
import com.steamstreet.awskt.dynamodb.DeleteItemRequest
import com.steamstreet.awskt.dynamodb.DeleteItemResponse
import com.steamstreet.awskt.dynamodb.DeleteTableRequest
import com.steamstreet.awskt.dynamodb.DeleteTableResponse
import com.steamstreet.awskt.dynamodb.DescribeTableRequest
import com.steamstreet.awskt.dynamodb.DescribeTableResponse
import com.steamstreet.awskt.dynamodb.DynamoDb
import com.steamstreet.awskt.dynamodb.GetItemRequest
import com.steamstreet.awskt.dynamodb.GetItemResponse
import com.steamstreet.awskt.dynamodb.PutItemRequest
import com.steamstreet.awskt.dynamodb.PutItemResponse
import com.steamstreet.awskt.dynamodb.QueryRequest
import com.steamstreet.awskt.dynamodb.QueryResponse
import com.steamstreet.awskt.dynamodb.ScanRequest
import com.steamstreet.awskt.dynamodb.ScanResponse
import com.steamstreet.awskt.dynamodb.TransactGetItemsRequest
import com.steamstreet.awskt.dynamodb.TransactGetItemsResponse
import com.steamstreet.awskt.dynamodb.TransactWriteItemsRequest
import com.steamstreet.awskt.dynamodb.TransactWriteItemsResponse
import com.steamstreet.awskt.dynamodb.UpdateItemRequest
import com.steamstreet.awskt.dynamodb.UpdateItemResponse
import com.steamstreet.exceptions.NotFoundException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How the property delegates, [Item.allAttributes] and [Transaction.close] read: from memory where
 * the item can answer, and through `runBlocking` only where a DynamoDB call is unavoidable.
 *
 * In `commonTest`, so it runs on the JVM and on `macosArm64` alike. DynamoDB is a fake that serves
 * one stored item and counts the calls made to it.
 *
 * ## Detecting a nested event loop
 *
 * Each read runs inside an outer `runBlocking`, which is a single-threaded event loop, as a native
 * Lambda's serial handler is. Before the read, the test queues a task on that loop with `launch`
 * and does not yield, so the task cannot have run yet. A nested `runBlocking` on the same thread
 * shares the thread's event loop and drains its queue while it waits, so the queued task runs if,
 * and only if, the read started one. [assertNoNestedEventLoop] checks the task did not run, and
 * [assertNestedEventLoop] checks it did; the latter is the positive control that shows the probe
 * detects a nested loop on each platform, rather than passing vacuously.
 */
class DelegateReadTest {

    private enum class Kind { BAR, CLUB }

    private class Venue(override val entity: Item) : ItemContainer {
        val name: String? by stringAttribute()
        val city: String? by stringAttribute(defaultValue = "Las Vegas")
        val kind: Kind by enumAttribute(Kind.CLUB)
        val tags: Set<String> by stringSetAttribute()
        val capacity: Int? by intAttribute()
        val renamed: String? by stringAttribute(attributeName = "display_name")
    }

    private val stored = mapOf(
        "pk" to AttributeValue.S("venue"),
        "sk" to AttributeValue.S("1"),
        "name" to AttributeValue.S("The Golden Tiki"),
        "kind" to AttributeValue.S("BAR"),
        "tags" to AttributeValue.Ss(listOf("tiki", "late")),
        "capacity" to AttributeValue.N("120"),
        "display_name" to AttributeValue.S("Golden Tiki"),
    )

    private val dynamo = FakeDynamoDb(stored)
    private val session = DynamoKtSession(DynamoKt("table"), dynamo)

    private val key = mapOf("pk" to AttributeValue.S("venue"), "sk" to AttributeValue.S("1"))

    @Test
    fun loadedItemReadsEveryDelegateWithoutANestedEventLoop() {
        val venue = Venue(session.facade(stored))

        assertNoNestedEventLoop {
            assertEquals("The Golden Tiki", venue.name)
            assertEquals("Las Vegas", venue.city)
            assertEquals(Kind.BAR, venue.kind)
            assertEquals(setOf("tiki", "late"), venue.tags)
            assertEquals(120, venue.capacity)
            assertEquals("Golden Tiki", venue.renamed)
        }
        assertEquals(0, dynamo.getItemCalls)
    }

    @Test
    fun loadedItemAnswersAnAbsentAttributeFromMemory() {
        val venue = Venue(session.facade(key))

        assertNoNestedEventLoop {
            assertNull(venue.name)
            assertEquals("Las Vegas", venue.city)
            assertEquals(Kind.CLUB, venue.kind)
            assertEquals(emptySet(), venue.tags)
            assertNull(venue.capacity)
        }
        assertEquals(0, dynamo.getItemCalls)
    }

    @Test
    fun partiallyLoadedItemReadsAHeldAttributeWithoutANestedEventLoop() {
        val venue = Venue(session.unloaded(key + ("name" to AttributeValue.S("Held")), failOnLoading = true))

        assertNoNestedEventLoop {
            assertEquals("Held", venue.name)
        }
        assertEquals(0, dynamo.getItemCalls)
        assertFalse(venue.entity.loaded)
    }

    @Test
    fun partiallyLoadedItemFetchesOnceForAMissingAttribute() {
        val venue = Venue(session.unloaded(key + ("name" to AttributeValue.S("Held")), failOnLoading = true))

        assertNestedEventLoop {
            assertEquals(Kind.BAR, venue.kind)
        }
        assertEquals(1, dynamo.getItemCalls)
        assertTrue(venue.entity.loaded)

        // The fetch replaces the held attributes with the stored item, exactly as before.
        assertNoNestedEventLoop {
            assertEquals("The Golden Tiki", venue.name)
            assertEquals(120, venue.capacity)
        }
        assertEquals(1, dynamo.getItemCalls)
    }

    @Test
    fun unloadedItemFetchesOnFirstReadAndNotAfter() {
        val venue = Venue(session.unloaded(key, failOnLoading = true))

        assertNestedEventLoop {
            assertEquals("The Golden Tiki", venue.name)
        }
        assertNoNestedEventLoop {
            assertEquals(setOf("tiki", "late"), venue.tags)
            assertEquals("Las Vegas", venue.city)
        }
        assertEquals(1, dynamo.getItemCalls)
    }

    @Test
    fun unloadedItemThatIsNotFoundFollowsFailOnLoading() {
        dynamo.serve = null

        val lenient = Venue(session.unloaded(key, failOnLoading = false))
        assertNestedEventLoop {
            assertNull(lenient.name)
        }
        assertTrue(lenient.entity.loaded)
        assertNoNestedEventLoop {
            assertNull(lenient.capacity)
        }
        assertEquals(1, dynamo.getItemCalls)

        val strict = Venue(session.unloaded(key, failOnLoading = true))
        assertFailsWith<NotFoundException> { strict.name }
    }

    @Test
    fun allAttributesOfALoadedItemNeedNoNestedEventLoop() {
        val item = session.facade(stored)

        assertNoNestedEventLoop {
            assertEquals(stored, item.allAttributes)
        }
        assertEquals(0, dynamo.getItemCalls)
    }

    @Test
    fun allAttributesOfAnUnloadedItemFetch() {
        val item = session.unloaded(key, failOnLoading = true)

        assertNestedEventLoop {
            assertEquals(stored, item.allAttributes)
        }
        assertNoNestedEventLoop {
            assertEquals(stored, item.allAttributes)
        }
        assertEquals(1, dynamo.getItemCalls)
    }

    @Test
    fun mutableItemReadsItsPendingUpdatesWithoutANestedEventLoop() {
        val mutable = MutableItem(session, stored)
        mutable["name"] = "Renamed"
        mutable["capacity"] = null as Int?
        val venue = Venue(mutable)

        assertNoNestedEventLoop {
            assertEquals("Renamed", venue.name)
            assertNull(venue.capacity)
            assertEquals(Kind.BAR, venue.kind)
        }
        assertEquals(0, dynamo.getItemCalls)
    }

    @Test
    fun closingAnEmptyTransactionNeedsNoNestedEventLoop() {
        assertNoNestedEventLoop {
            session.transaction().close()
        }
        assertEquals(0, dynamo.transactWriteCalls)
    }

    @Test
    fun closingATransactionWithWritesCommitsIt() {
        assertNestedEventLoop {
            session.transaction().use { transaction ->
                runBlocking { transaction.delete("venue", "1") }
            }
        }
        assertEquals(1, dynamo.transactWriteCalls)
    }

    /** Runs [block] inside a single-threaded event loop and asserts it started no nested one. */
    private fun assertNoNestedEventLoop(block: () -> Unit) {
        assertFalse(runsQueuedTask(block), "the read started a nested event loop")
    }

    /** The positive control: asserts [block] did start a nested event loop. */
    private fun assertNestedEventLoop(block: () -> Unit) {
        assertTrue(runsQueuedTask(block), "the read did not start a nested event loop")
    }

    private fun runsQueuedTask(block: () -> Unit): Boolean = runBlocking {
        var drained = false
        launch { drained = true }
        block()
        drained
    }

    private class FakeDynamoDb(var serve: Map<String, AttributeValue>?) : DynamoDb {
        var getItemCalls = 0
        var transactWriteCalls = 0

        override val client: AwsServiceClient get() = error("not used")

        override suspend fun getItem(request: GetItemRequest): GetItemResponse {
            getItemCalls++
            return GetItemResponse(item = serve)
        }

        override suspend fun transactWriteItems(request: TransactWriteItemsRequest): TransactWriteItemsResponse {
            transactWriteCalls++
            return TransactWriteItemsResponse()
        }

        override suspend fun putItem(request: PutItemRequest): PutItemResponse = error("not used")
        override suspend fun updateItem(request: UpdateItemRequest): UpdateItemResponse = error("not used")
        override suspend fun deleteItem(request: DeleteItemRequest): DeleteItemResponse = error("not used")
        override suspend fun query(request: QueryRequest): QueryResponse = error("not used")
        override suspend fun scan(request: ScanRequest): ScanResponse = error("not used")
        override suspend fun batchGetItem(request: BatchGetItemRequest): BatchGetItemResponse = error("not used")
        override suspend fun batchWriteItem(request: BatchWriteItemRequest): BatchWriteItemResponse = error("not used")
        override suspend fun transactGetItems(request: TransactGetItemsRequest): TransactGetItemsResponse = error("not used")
        override suspend fun createTable(request: CreateTableRequest): CreateTableResponse = error("not used")
        override suspend fun describeTable(request: DescribeTableRequest): DescribeTableResponse = error("not used")
        override suspend fun deleteTable(request: DeleteTableRequest): DeleteTableResponse = error("not used")
        override fun close() {}
    }
}
