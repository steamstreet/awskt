package com.steamstreet.aws.lambda.eventbridge

import com.steamstreet.events.EventSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the dispatch driver, in `commonTest` so they cover the JVM `eventBridge()` and the
 * native `eventBridgeLambda` at once — both are now shells over [processEventBridgePayload].
 *
 * The SQS-wrapped path in particular had no test before it moved into `commonMain`, and it is the
 * part with real behaviour in it: which records are reported, and whether a failure throws.
 */
class EventBridgeProcessingTest {
    @Serializable
    data class OrderPlaced(val orderId: String)

    private val orderPlaced = EventSchema("OrderPlaced", OrderPlaced.serializer())

    private fun directEvent(orderId: String) = Json.parseToJsonElement(
        """
        {
          "version": "0",
          "id": "event-1",
          "detail-type": "OrderPlaced",
          "source": "test",
          "time": "2026-08-16T00:00:00Z",
          "detail": { "orderId": "$orderId" }
        }
        """.trimIndent()
    )

    private fun sqsWrapped(vararg orderIds: String) = Json.parseToJsonElement(
        buildString {
            append("""{"Records":[""")
            orderIds.forEachIndexed { index, orderId ->
                if (index > 0) append(",")
                val body = """
                    {"version":"0","id":"event-$orderId","detail-type":"OrderPlaced",
                     "source":"test","time":"2026-08-16T00:00:00Z","detail":{"orderId":"$orderId"}}
                """.trimIndent().replace("\n", "")
                append("""{"messageId":"msg-$orderId","body":${JsonPrimitive(body)}}""")
            }
            append("]}")
        }
    )

    @Test
    fun `a direct event reaches its typed handler and produces no response`() = runTest {
        var handled: OrderPlaced? = null

        val response = processEventBridgePayload(directEvent("order-1")) {
            orderPlaced { order -> handled = order }
        }

        assertEquals(OrderPlaced("order-1"), handled)
        assertNull(response, "a direct event has no batch response to return")
    }

    @Test
    fun `a handler failure on a direct event propagates`() = runTest {
        assertFailsWith<IllegalStateException> {
            processEventBridgePayload(directEvent("order-1")) {
                orderPlaced { error("nope") }
            }
        }
    }

    @Test
    fun `an SQS-wrapped batch dispatches every record`() = runTest {
        val handled = mutableListOf<String>()

        val response = processEventBridgePayload(sqsWrapped("a", "b"), batchSqs = true) {
            orderPlaced { order -> handled.add(order.orderId) }
        }

        assertEquals(listOf("a", "b"), handled)
        assertTrue(response!!.batchItemFailures.isEmpty())
    }

    @Test
    fun `only the failed record is reported when batching failures`() = runTest {
        val response = processEventBridgePayload(sqsWrapped("a", "b", "c"), batchSqs = true) {
            orderPlaced { order -> if (order.orderId == "b") error("failed") }
        }

        assertEquals(listOf("msg-b"), response!!.batchItemFailures.map { it.itemIdentifier })
    }

    @Test
    fun `a failure throws when not batching so the whole batch is redriven`() = runTest {
        assertFailsWith<IllegalStateException> {
            processEventBridgePayload(sqsWrapped("a", "b"), batchSqs = false) {
                orderPlaced { order -> if (order.orderId == "b") error("failed") }
            }
        }
    }
}

