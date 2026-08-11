package com.steamstreet.aws.test

import com.amazonaws.services.lambda.runtime.Context
import com.steamstreet.aws.lambda.eventbridge.EventBridgeFunction
import com.steamstreet.aws.lambda.lambdaJson
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.eventbridge.EventBridgeApi
import com.steamstreet.awskt.eventbridge.PutEventsEntry
import com.steamstreet.awskt.eventbridge.PutEventsResponse
import com.steamstreet.awskt.eventbridge.PutEventsResultEntry
import com.steamstreet.events.EventSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import software.amazon.event.ruler.Ruler
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private class LocalTarget(
    val handler: (suspend (InputStream, Context) -> Unit)? = null
)

/**
 * A local mocked version of event bridge.
 *
 * **No longer `EventBridgeClient by mockk`.** It implements this library's own one-method
 * [EventBridgeApi] outright, plus the test-only [EventBridgeAdmin]. Delegating to a relaxed mockk
 * meant every operation nobody had overridden silently returned an empty response instead of
 * failing, so a test that called into an unimplemented corner of the client passed for the wrong
 * reason. With a one-method interface there is nothing left to relax.
 */
public class EventBridgeMock(
    private val accountId: String = "1234",
    private val region: String = "us-west-2",
) : EventBridgeApi, EventBridgeAdmin, MockService {
    private val buses = hashMapOf(
        "default" to Bus()
    )

    private val events = ArrayList<PutEventsEntry>()

    private var processSemaphore = AtomicInteger(0)

    override val isProcessing: Boolean get() = processSemaphore.get() != 0

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
            processSemaphore.incrementAndGet()

            val str = buildJsonObject {
                put("source", entry.source)
                put("detail-type", entry.detailType)
                if (entry.detail!!.isNotBlank()) {
                    put("detail", Json.parseToJsonElement(entry.detail!!))
                }
            }.toString()

            thread {
                runBlocking {
                    synchronized(rules) {
                        rules.filter {
                            Ruler.matchesRule(str, it.eventPattern)
                        }
                    }.flatMap { it.targets }.forEach {
                        sendToTarget(entry, it)
                    }
                }
                processSemaphore.decrementAndGet()
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

        rule.targets.add(LocalTarget(handler))
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
