package com.steamstreet.aws.sqs

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.contextOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the batch semantics, in `commonTest` so they run on the JVM and on Kotlin/Native against
 * the one implementation both platforms use. None of this needs a Lambda runtime, an `InputStream`
 * or a mock `Context` — which is most of the reason the loops were worth moving out of the handler
 * base classes.
 */
class SQSProcessingTest {
    @Serializable
    data class Order(val id: String)

    private fun sqsRecord(messageId: String, body: String) = SQSRecord(
        messageId = messageId,
        receiptHandle = "handle-$messageId",
        body = body,
        eventSource = "aws:sqs",
        eventSourceARN = "arn:aws:sqs:us-east-1:1:queue",
        awsRegion = "us-east-1"
    )

    private fun event(vararg ids: String) = SQSEvent(
        ids.map { sqsRecord(it, Json.encodeToString(Order.serializer(), Order(it))) }
    )

    @Test
    fun `decodes each body and passes the record as context`() = runTest {
        val seen = mutableListOf<Pair<String, String>>()

        event("a", "b").processMessages(Order.serializer()) { order ->
            seen.add(contextOf<SQSRecord>().messageId to order.id)
        }

        assertEquals(listOf("a" to "a", "b" to "b"), seen)
    }

    @Test
    fun `a failure fails the whole batch when not reporting item failures`() = runTest {
        assertFailsWith<IllegalStateException> {
            event("a", "b").processMessages(Order.serializer()) {
                error("nope")
            }
        }
    }

    @Test
    fun `only failed records are reported in the batch response`() = runTest {
        val response = event("a", "b", "c").processBatch(Order.serializer(), logExceptions = false) { order ->
            if (order.id == "b") error("failed")
        }

        assertEquals(listOf("b"), response.batchItemFailures.map { it.itemIdentifier })
    }

    @Test
    fun `a batch with no failures reports none`() = runTest {
        val response = event("a", "b").processBatch(Order.serializer()) { }
        assertTrue(response.batchItemFailures.isEmpty())
    }

    @Test
    fun `every record is attempted even when an earlier one fails`() = runTest {
        val attempted = mutableListOf<String>()

        val response = event("a", "b", "c").processBatch(Order.serializer(), logExceptions = false) { order ->
            attempted.add(order.id)
            if (order.id == "a") error("failed")
        }

        assertEquals(listOf("a", "b", "c"), attempted)
        assertEquals(listOf("a"), response.batchItemFailures.map { it.itemIdentifier })
    }

    @Test
    fun `a malformed body fails the invocation rather than being one failed item`() = runTest {
        // Documents the pre-existing behaviour rather than endorsing it: decoding happens for the
        // whole batch before any handler runs, so one bad body takes the batch down with it.
        val event = SQSEvent(listOf(sqsRecord("a", """{"id":"a"}"""), sqsRecord("b", "not json")))

        assertFailsWith<Exception> {
            event.processBatch(Order.serializer()) { }
        }
    }

    @Test
    fun `runMessages rejects a message list that does not line up with the records`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            event("a", "b").runMessages(listOf(Order("a"))) { }
        }
    }

    @Test
    fun `concurrent dispatch reports the same failures as sequential`() = runTest {
        val response = event("a", "b", "c").processBatch(
            Order.serializer(),
            async = true,
            logExceptions = false
        ) { order ->
            if (order.id != "b") error("failed")
        }

        assertEquals(setOf("a", "c"), response.batchItemFailures.map { it.itemIdentifier }.toSet())
    }
}
