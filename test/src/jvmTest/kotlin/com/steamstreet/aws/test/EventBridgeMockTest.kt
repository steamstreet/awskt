package com.steamstreet.aws.test

import com.steamstreet.aws.lambda.eventbridge.EventBridgeFunction
import com.steamstreet.aws.lambda.eventbridge.EventBridgeHandlerConfig
import com.steamstreet.awskt.eventbridge.PutEventsEntry
import com.steamstreet.events.eventSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coverage for [EventBridgeMock]'s routing.
 *
 * **The mock had no tests at all before M6** — it appeared nowhere outside its own file — which is
 * why M6's rewrite of it from `EventBridgeClient by mockk(relaxed = true)` onto our own
 * [com.steamstreet.awskt.eventbridge.EventBridge] would otherwise have shipped compile-verified
 * and nothing more. The plan's verification line refers to "the existing EventBridge rule-matching
 * tests"; there were none.
 */
class EventBridgeMockTest {

    /** Dispatch happens on a background thread, so a read has to wait for the queue to drain. */
    private fun EventBridgeMock.awaitQuiet(timeoutMillis: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (isProcessing) {
            check(System.currentTimeMillis() < deadline) { "mock still processing after ${timeoutMillis}ms" }
            Thread.sleep(10)
        }
    }

    private fun EventBridgeMock.collectInto(
        bus: String,
        rule: String,
        sink: MutableList<String>,
    ) = putTarget(bus, rule) { input: InputStream, _ ->
        sink.add(input.readBytes().decodeToString())
    }

    @Test
    fun matchingEventReachesTheTarget() = runTest {
        val mock = EventBridgeMock()
        val received = Collections.synchronizedList(mutableListOf<String>())

        mock.createEventBus("orders")
        mock.putRule("placed", """{"detail-type":["OrderPlaced"]}""", "orders")
        mock.collectInto("orders", "placed", received)

        mock.putEvents(
            listOf(
                PutEventsEntry(
                    eventBusName = "orders",
                    source = "shop",
                    detailType = "OrderPlaced",
                    detail = """{"orderId":"7"}""",
                ),
            ),
        )
        mock.awaitQuiet()

        assertEquals(1, received.size)
        val event = Json.parseToJsonElement(received.single()).jsonObject
        assertEquals("OrderPlaced", event["detail-type"]?.jsonPrimitive?.content)
        assertEquals("shop", event["source"]?.jsonPrimitive?.content)
        assertEquals("7", event["detail"]?.jsonObject?.get("orderId")?.jsonPrimitive?.content)
    }

    @Test
    fun nonMatchingEventDoesNotReachTheTarget() = runTest {
        val mock = EventBridgeMock()
        val received = Collections.synchronizedList(mutableListOf<String>())

        mock.createEventBus("orders")
        mock.putRule("placed", """{"detail-type":["OrderPlaced"]}""", "orders")
        mock.collectInto("orders", "placed", received)

        mock.putEvents(
            listOf(
                PutEventsEntry(
                    eventBusName = "orders",
                    source = "shop",
                    detailType = "OrderCancelled",
                    detail = """{"orderId":"7"}""",
                ),
            ),
        )
        mock.awaitQuiet()

        assertTrue(received.isEmpty(), "a rule matching OrderPlaced must not receive OrderCancelled")
    }

    /** Buses are addressable by ARN as well as name — `EventBridgeSubmitter` is configured with one. */
    @Test
    fun busArnResolvesToTheBus() = runTest {
        val mock = EventBridgeMock()
        val received = Collections.synchronizedList(mutableListOf<String>())

        val arn = mock.createEventBus("orders")
        mock.putRule("placed", """{"detail-type":["OrderPlaced"]}""", "orders")
        mock.collectInto("orders", "placed", received)

        mock.putEvents(
            listOf(PutEventsEntry(eventBusName = arn, source = "shop", detailType = "OrderPlaced", detail = "{}")),
        )
        mock.awaitQuiet()

        assertEquals(1, received.size)
    }

    @Test
    fun putEventsReturnsAnIdPerEntryInOrder() = runTest {
        val mock = EventBridgeMock()
        val response = mock.putEvents(
            (1..3).map { PutEventsEntry(source = "s$it", detailType = "T", detail = "{}") },
        )

        assertEquals(0, response.failedEntryCount)
        assertEquals(3, response.entries?.size)
        assertTrue(response.entries!!.all { it.eventId != null && it.errorCode == null })
    }

    @Test
    fun recordedEventsAreQueryableAndClearable() = runTest {
        val mock = EventBridgeMock()
        mock.putEvents(
            listOf(
                PutEventsEntry(source = "s", detailType = "Kept", detail = "{}"),
                PutEventsEntry(source = "s", detailType = "Other", detail = "{}"),
            ),
        )

        assertEquals(1, mock.eventsOfType("Kept").size)
        assertEquals(0, mock.eventsOfType("Missing").size)

        mock.clearSaved()
        assertEquals(0, mock.eventsOfType("Kept").size)
    }

    @Test
    fun duplicateRuleNameIsRejected() = runTest {
        val mock = EventBridgeMock()
        mock.createEventBus("orders")
        mock.putRule("dupe", """{"detail-type":["A"]}""", "orders")

        assertFailsWith<IllegalStateException> {
            mock.putRule("dupe", """{"detail-type":["B"]}""", "orders")
        }
    }

    @Test
    fun listRulesReportsWhatWasRegistered() = runTest {
        val mock = EventBridgeMock()
        mock.createEventBus("orders")
        mock.putRule("a", """{"detail-type":["A"]}""", "orders")
        mock.putRule("b", """{"detail-type":["B"]}""", "orders")

        assertEquals(listOf("a", "b"), mock.listRules("orders"))
        // The default bus is separate, and untouched.
        assertEquals(emptyList(), mock.listRules())
    }

    @Test
    fun unknownBusIsRejected() = runTest {
        val mock = EventBridgeMock()
        assertFailsWith<IllegalArgumentException> { mock.listRules("no-such-bus") }
    }

    private fun orderPlaced(bus: String = "orders") = PutEventsEntry(
        eventBusName = bus,
        source = "shop",
        detailType = "OrderPlaced",
        detail = """{"orderId":"7"}""",
    )

    /**
     * Real EventBridge delivers to each target independently. Through 3.1.5 the mock delivered to
     * every target on one thread with no error handling, so the first throwing target killed the
     * thread: later targets never ran, and the in-flight count was never released, leaving
     * [EventBridgeMock.isProcessing] true for good.
     */
    @Test
    fun throwingTargetDoesNotStopTheOthers() = runTest {
        val mock = EventBridgeMock()
        val received = Collections.synchronizedList(mutableListOf<String>())

        mock.createEventBus("orders")
        mock.putRule("fails", """{"detail-type":["OrderPlaced"]}""", "orders")
        mock.putTarget("orders", "fails") { _: InputStream, _ -> error("boom") }
        // A second target on the same rule, after the throwing one.
        mock.collectInto("orders", "fails", received)
        mock.putRule("succeeds", """{"detail-type":["OrderPlaced"]}""", "orders")
        mock.collectInto("orders", "succeeds", received)

        mock.putEvents(listOf(orderPlaced()))
        mock.awaitIdle(5_000.milliseconds)

        assertFalse(mock.isProcessing)
        assertEquals(2, received.size, "both healthy targets must receive the event")
        val failure = mock.deliveryFailures.single()
        assertEquals("fails", failure.ruleName)
        assertEquals("OrderPlaced", failure.detailType)
        assertEquals("orders", failure.eventBusName)
        assertEquals("boom", failure.error.message)

        mock.clearDeliveryFailures()
        assertTrue(mock.deliveryFailures.isEmpty())
    }

    /**
     * The path consumers actually hit: [EventBridgeFunction.execute] rethrows a handler's exception
     * when it is not in batch mode, so a failing function must be contained the same way.
     */
    @Test
    fun throwingEventBridgeFunctionIsContained() = runTest {
        val mock = EventBridgeMock()
        val received = Collections.synchronizedList(mutableListOf<String>())
        val schema = eventSchema<JsonObject>("OrderPlaced")

        mock.createEventBus("orders")
        mock.putTarget("orders", listOf("OrderPlaced"), object : EventBridgeFunction {
            override val tracePerformance: Boolean get() = false
            override suspend fun EventBridgeHandlerConfig.onEvent() {
                schema { error("handler failed") }
            }
        })
        mock.putRule("succeeds", """{"detail-type":["OrderPlaced"]}""", "orders")
        mock.collectInto("orders", "succeeds", received)

        mock.putEvents(listOf(orderPlaced(), orderPlaced()))
        mock.awaitIdle(5_000.milliseconds)

        assertFalse(mock.isProcessing)
        assertEquals(2, received.size)
        assertEquals(2, mock.deliveryFailures.size)
    }

    @Test
    fun awaitIdleWaitsForSlowDeliveries() = runTest {
        val mock = EventBridgeMock()
        val received = Collections.synchronizedList(mutableListOf<String>())

        mock.createEventBus("orders")
        mock.putRule("slow", """{"detail-type":["OrderPlaced"]}""", "orders")
        mock.putTarget("orders", "slow") { input: InputStream, _ ->
            Thread.sleep(200)
            received.add(input.readBytes().decodeToString())
        }

        mock.putEvents(listOf(orderPlaced()))
        assertEquals(1, mock.inFlightDeliveries.value)
        mock.awaitIdle(5_000.milliseconds)

        assertEquals(1, received.size)
        assertEquals(0, mock.inFlightDeliveries.value)
    }

    /**
     * An event a target publishes is counted before that target's own delivery is released, so
     * awaiting idle covers the whole cascade rather than returning between hops.
     */
    @Test
    fun awaitIdleCoversEventsPublishedByTargets() = runTest {
        val mock = EventBridgeMock()
        val received = Collections.synchronizedList(mutableListOf<String>())

        mock.createEventBus("orders")
        mock.putRule("placed", """{"detail-type":["OrderPlaced"]}""", "orders")
        mock.putTarget("orders", "placed") { _: InputStream, _ ->
            mock.putEvents(
                listOf(
                    PutEventsEntry(eventBusName = "orders", source = "shop", detailType = "OrderShipped", detail = "{}"),
                ),
            )
        }
        mock.putRule("shipped", """{"detail-type":["OrderShipped"]}""", "orders")
        mock.putTarget("orders", "shipped") { input: InputStream, _ ->
            Thread.sleep(200)
            received.add(input.readBytes().decodeToString())
        }

        mock.putEvents(listOf(orderPlaced()))
        mock.awaitIdle(5_000.milliseconds)

        assertEquals(1, received.size)
    }

    @Test
    fun awaitIdleTimesOutWhileADeliveryHangs() = runTest {
        val mock = EventBridgeMock()
        val release = CountDownLatch(1)

        mock.createEventBus("orders")
        mock.putRule("hangs", """{"detail-type":["OrderPlaced"]}""", "orders")
        mock.putTarget("orders", "hangs") { _: InputStream, _ -> release.await(10, TimeUnit.SECONDS) }

        mock.putEvents(listOf(orderPlaced()))
        try {
            assertFailsWith<IllegalStateException> { mock.awaitIdle(100.milliseconds) }
        } finally {
            release.countDown()
        }
        mock.awaitIdle(5_000.milliseconds)
    }

    /**
     * The extension seam cannot work without a transport, so the mock refuses rather than handing
     * back something unusable.
     */
    @Test
    fun clientAccessIsUnsupported() {
        assertFailsWith<UnsupportedOperationException> { EventBridgeMock().client }
    }
}
