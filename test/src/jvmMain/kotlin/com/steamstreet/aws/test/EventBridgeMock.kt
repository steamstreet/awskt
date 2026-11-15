package com.steamstreet.aws.test

import com.amazonaws.services.lambda.runtime.Context
import com.steamstreet.aws.lambda.eventbridge.EventBridgeFunction
import com.steamstreet.aws.lambda.lambdaJson
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.eventbridge.EventBridge
import com.steamstreet.awskt.eventbridge.PutEventsEntry
import com.steamstreet.awskt.eventbridge.PutEventsResponse
import com.steamstreet.awskt.eventbridge.PutEventsResultEntry
import com.steamstreet.events.EventSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import software.amazon.event.ruler.Ruler
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.util.*
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private class LocalTarget(
    val handler: (suspend (InputStream, Context) -> Unit)? = null
)

/**
 * A local mocked version of event bridge.
 *
 * **No longer `EventBridgeClient by mockk`.** It implements this library's own one-method
 * [EventBridge] outright, plus the test-only [EventBridgeAdmin]. Delegating to a relaxed mockk
 * meant every operation nobody had overridden silently returned an empty response instead of
 * failing, so a test that called into an unimplemented corner of the client passed for the wrong
 * reason. With a one-method interface there is nothing left to relax.
 */
public class EventBridgeMock(
    private val accountId: String = "1234",
    private val region: String = "us-west-2",
) : EventBridge, EventBridgeAdmin, MockService {
    private val buses = hashMapOf(
        "default" to Bus()
    )

    private val events = ArrayList<PutEventsEntry>()

    private val inFlight = MutableStateFlow(0)

    private val failures = ArrayList<DeliveryFailure>()

    /**
     * The number of events whose delivery to matching targets has not yet finished. Each
     * [putEvents] entry counts once, from the moment it is accepted until every target it matched
     * has been invoked. An event published by a target counts before that target's own delivery
     * finishes, so a cascade never reads as idle part-way through.
     */
    public val inFlightDeliveries: StateFlow<Int> = inFlight.asStateFlow()

    override val isProcessing: Boolean get() = inFlight.value != 0

    /**
     * Suspends until no deliveries are in flight, including any events published by the targets
     * themselves. Call it before asserting on a target's side effects, and before tearing down
     * anything a target writes to.
     *
     * The wait runs on [Dispatchers.Default] so that [timeout] is real time even under `runTest`,
     * whose virtual clock would otherwise expire it at once while the delivery threads still run.
     *
     * @throws IllegalStateException if deliveries are still in flight after [timeout].
     */
    public suspend fun awaitIdle(timeout: Duration = 20.seconds) {
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeout) {
                inFlight.first { it == 0 }
            }
        } ?: error("EventBridgeMock still has ${inFlight.value} deliveries in flight after $timeout")
    }

    /**
     * A target that threw while handling an event.
     */
    public class DeliveryFailure(
        public val eventBusName: String?,
        public val detailType: String?,
        public val ruleName: String,
        public val error: Throwable,
    )

    /**
     * Every target invocation that threw, oldest first. A throwing target is logged and recorded
     * here rather than propagated: real EventBridge delivers to each target independently, so one
     * failing target must not stop the others or leave the mock reporting [isProcessing] forever.
     * Tests that expect every delivery to succeed can assert this is empty.
     */
    public val deliveryFailures: List<DeliveryFailure>
        get() = synchronized(failures) { failures.toList() }

    /**
     * Clear the recorded [deliveryFailures].
     */
    public fun clearDeliveryFailures() {
        synchronized(failures) {
            failures.clear()
        }
    }

    /**
     * The extension seam is not available on a mock: there is no signed transport behind it, and
     * an extension operation written against it would be talking to nothing. Failing loudly beats
     * handing back a client that cannot work.
     */
    override val client: AwsServiceClient
        get() = throw UnsupportedOperationException(
            "EventBridgeMock has no transport; extension operations cannot run against it",
        )

    override fun close() {}

    private inner class EventRule(val name: String, val eventPattern: String) {
        val targets = ArrayList<LocalTarget>()
    }

    /**
     * Clear all saved events
     */
    public fun clearSaved() {
        synchronized(events) {
            events.clear()
        }
    }

    public fun eventsOfType(detailType: String, bus: String? = null): List<PutEventsEntry> {
        synchronized(events) {
            return events.filter {
                it.detailType == detailType
            }.filter {
                bus == null || it.eventBusName == bus
            }
        }
    }

    private inner class Bus {
        val rules = ArrayList<EventRule>()

        fun putEvent(entry: PutEventsEntry) {
            val str = buildJsonObject {
                put("source", entry.source)
                put("detail-type", entry.detailType)
                if (entry.detail!!.isNotBlank()) {
                    put("detail", Json.parseToJsonElement(entry.detail!!))
                }
            }.toString()

            // Counted only once nothing above can throw, and released in `finally`, so no failure
            // inside the delivery thread can leave the mock reporting itself busy for good.
            inFlight.update { it + 1 }
            thread(name = "EventBridgeMock-delivery") {
                try {
                    val deliveries = synchronized(rules) {
                        rules.filter {
                            Ruler.matchesRule(str, it.eventPattern)
                        }.flatMap { rule -> rule.targets.map { rule to it } }
                    }
                    runBlocking {
                        deliveries.forEach { (rule, target) ->
                            try {
                                sendToTarget(entry, target)
                            } catch (t: Throwable) {
                                recordFailure(entry, rule.name, t)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    recordFailure(entry, "<rule matching>", t)
                } finally {
                    inFlight.update { it - 1 }
                }
            }
        }

        private fun recordFailure(entry: PutEventsEntry, ruleName: String, t: Throwable) {
            System.err.println(
                "EventBridgeMock: delivery of '${entry.detailType}' on bus " +
                    "'${entry.eventBusName ?: "default"}' to rule '$ruleName' failed: $t"
            )
            t.printStackTrace()
            synchronized(failures) {
                failures.add(DeliveryFailure(entry.eventBusName, entry.detailType, ruleName, t))
            }
        }

        private suspend fun sendToTarget(
            entry: PutEventsEntry,
            target: LocalTarget
        ) {
            val event = buildJsonObject {
                put("source", JsonPrimitive(entry.source))
                put("detail-type", JsonPrimitive(entry.detailType))
                put("account", JsonPrimitive(accountId))
                put("detail", Json.parseToJsonElement(entry.detail!!))
                put("region", JsonPrimitive(this@EventBridgeMock.region))
                put("id", JsonPrimitive(UUID.randomUUID().toString()))
                put("time", JsonPrimitive(Instant.now().toString()))
            }
            val buffer = event.toString().toByteArray()
            if (target.handler != null) {
                target.handler.invoke(
                    buffer.inputStream(), LambdaLocalContext(
                        region = this@EventBridgeMock.region,
                        account = accountId
                    )
                )
            }
        }

        fun putRule(name: String, eventPattern: String) {
            synchronized(rules) {
                check(rules.find { it.name == name } == null) { "Duplicate rule name" }
                rules.add(EventRule(name, eventPattern))
            }
        }
    }

    private fun bus(name: String?): Bus =
        buses[name ?: "default"] ?: throw IllegalArgumentException("No such event bus: $name")

    override suspend fun listRules(eventBusName: String?): List<String> {
        val bus = bus(eventBusName)
        return synchronized(bus.rules) { bus.rules.map { it.name } }
    }

    override suspend fun createEventBus(name: String): String {
        buses[name] = Bus()
        return "arn:aws:events:${region}:$accountId:event-bus/$name"
    }

    override suspend fun putRule(name: String, eventPattern: String, eventBusName: String?): String {
        bus(eventBusName).putRule(name, eventPattern)
        return "arn:aws:events:${region}:$accountId:rule/$name"
    }

    /**
     * Create a rule that targets the given function
     */
    public suspend fun putRule(bus: String, pattern: String, target: EventBridgeFunction) {
        val ruleName = UUID.randomUUID().toString()
        putRule(name = ruleName, eventPattern = pattern, eventBusName = bus)

        putTarget(bus, ruleName) { input, context ->
            val output = ByteArrayOutputStream()
            target.execute(input, output, context)
        }
    }

    public suspend fun putRule(bus: String, pattern: JsonObject, target: EventBridgeFunction) {
        putRule(bus, pattern.toString(), target)
    }

    /**
     * Utility to set an EventBridgeFunction as a target for an event.
     */
    public suspend fun putTarget(eventBusName: String, pattern: String, handler: EventBridgeFunction) {
        val ruleName = UUID.randomUUID().toString()

        putRule(name = ruleName, eventPattern = pattern, eventBusName = eventBusName)

        putTarget(eventBusName, ruleName) { input, context ->
            handler.execute(input, ByteArrayOutputStream(), context)
        }
    }

    public suspend fun putTarget(
        eventBusName: String,
        pattern: JsonObjectBuilder.() -> Unit,
        handler: EventBridgeFunction
    ) {
        putTarget(eventBusName, buildJsonObject(pattern).toString(), handler)
    }

    public suspend fun putTarget(eventBusName: String, detailTypes: List<String>, handler: EventBridgeFunction) {
        putTarget(eventBusName, buildJsonObject {
            put("detail-type", buildJsonArray {
                @Suppress("OPT_IN_USAGE")
                addAll(detailTypes.map { JsonPrimitive(it) })
            })
        }.toString(), handler)
    }

    public fun putTarget(eventBus: String, ruleName: String, handler: suspend (InputStream, Context) -> Unit) {
        val bus = bus(eventBus)
        val rule = synchronized(bus.rules) {
            bus.rules.find { it.name == ruleName }
                ?: throw IllegalArgumentException("No such rule: $ruleName")
        }

        synchronized(bus.rules) {
            rule.targets.add(LocalTarget(handler))
        }
    }

    /**
     * Records every entry and routes it to any matching local target.
     *
     * Returns a [PutEventsResultEntry] per input entry with a generated `EventId`, positionally
     * aligned with the request — the same contract real EventBridge has, and what
     * `EventBridgeSubmitter` reads to decide which events published.
     */
    override suspend fun putEvents(entries: List<PutEventsEntry>): PutEventsResponse {
        val results = entries.map { entry ->
            synchronized(events) {
                events.add(entry)
            }
            val busName = entry.eventBusName?.let {
                if (it.startsWith("arn:aws")) it.substringAfter("event-bus/")
                else it
            }
            buses[busName]?.putEvent(entry)
            PutEventsResultEntry(eventId = UUID.randomUUID().toString())
        }
        return PutEventsResponse(failedEntryCount = 0, entries = results)
    }
}

/**
 * Get events of a given type and deserialize
 */
public fun <T> EventBridgeMock.eventsOfType(type: EventSchema<T>, bus: String? = null): List<T> {
    return this.eventsOfType(type.type, bus).map {
        Json.decodeFromString(type.serializer, it.detail!!)
    }
}

/**
 * Forward all events of the given type to the handler
 */
public suspend inline fun <reified T> EventBridgeMock.forwardEvents(
    type: EventSchema<T>,
    handler: EventBridgeFunction
) {
    eventsOfType(type).let {
        type.post(it, handler)
    }
}

/**
 * Post events directly to an EventBridgeFunction
 */
public suspend fun <T> EventSchema<T>.post(input: Collection<T>, handler: EventBridgeFunction) {
    post(input) {
        handler.execute(it, ByteArrayOutputStream(), LambdaLocalContext())
    }
}

/**
 * Post an event directly to an EventBridgeFunction
 */
public suspend fun <T> EventSchema<T>.post(input: T, handler: EventBridgeFunction) {
    post(listOf(input), handler)
}

/**
 * Post an event directly to a handler.
 */
public suspend fun <T, R> EventSchema<T>.post(input: Collection<T>, handler: suspend (InputStream) -> R) {
    input.forEach {
        val jsonString = buildJsonObject {
            put("detail-type", type)
            put("detail", lambdaJson.encodeToJsonElement(this@post.serializer, it))
        }.toString()
        handler(jsonString.byteInputStream())
    }
}
