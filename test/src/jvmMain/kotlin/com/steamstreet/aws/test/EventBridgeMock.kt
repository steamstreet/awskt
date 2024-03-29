package com.steamstreet.aws.test

import aws.sdk.kotlin.runtime.AwsServiceException
import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.sdk.kotlin.services.eventbridge.model.*
import aws.sdk.kotlin.services.eventbridge.putRule
import com.amazonaws.services.lambda.runtime.Context
import com.steamstreet.aws.lambda.eventbridge.EventBridgeFunction
import com.steamstreet.aws.lambda.lambdaJson
import com.steamstreet.events.EventSchema
import io.mockk.mockk
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
 */
public class EventBridgeMock(
    private val accountId: String = "1234",
    private val region: String = "us-west-2",
    private val mockk: EventBridgeClient = mockk(relaxed = true)
) : EventBridgeClient by mockk, MockService {
    private val buses = hashMapOf(
        "default" to Bus()
    )

    private val events = ArrayList<PutEventsRequestEntry>()

    private var processSemaphore = AtomicInteger(0)

    override val isProcessing: Boolean get() = processSemaphore.get() != 0

    override fun close() {}

    private inner class EventRule(val rule: PutRuleRequest) {
        val targets = ArrayList<LocalTarget>()
    }

    /**
     * Clear all saved events
     */
    public fun clearSaved() {
        events.clear()
    }

    public fun eventsOfType(detailType: String): List<PutEventsRequestEntry> {
        return events.filter {
            it.detailType == detailType
        }
    }

    private inner class Bus {
        val rules = ArrayList<EventRule>()

        suspend fun putEvent(entry: PutEventsRequestEntry) {
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
                    rules.filter {
                        Ruler.matchesRule(str, it.rule.eventPattern!!)
                    }.flatMap { it.targets }.forEach {
                        sendToTarget(entry, it)
                    }
                }
                processSemaphore.decrementAndGet()
            }
        }

        private suspend fun sendToTarget(
            entry: PutEventsRequestEntry,
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
                target.handler.invoke(buffer.inputStream(), LambdaLocalContext())
            }
        }

        fun putRule(rule: PutRuleRequest) {
            if (rules.find { it.rule.name == rule.name } != null) {
                throw AwsServiceException("Duplicate rule name", null)
            }
            rules.add(EventRule(rule))
        }
    }

    override suspend fun listRules(input: ListRulesRequest): ListRulesResponse {
        val bus = buses[input.eventBusName ?: "default"]
            ?: throw AwsServiceException(input.eventBusName, null)

        return ListRulesResponse {
            rules =
                bus.rules.map {
                    Rule { name = it.rule.name }
                }
        }
    }

    override suspend fun createEventBus(input: CreateEventBusRequest): CreateEventBusResponse {
        buses[input.name!!] = Bus()
        return CreateEventBusResponse {
            eventBusArn = "arn:aws:events:${region}:$accountId:event-bus/${input.name}"
        }
    }

    override suspend fun putRule(input: PutRuleRequest): PutRuleResponse {
        val bus =
            buses[input.eventBusName ?: "default"] ?: throw throw AwsServiceException(input.eventBusName, null)

        bus.putRule(input)
        return PutRuleResponse {
            ruleArn = "arn:aws:events:${region}:$accountId:rule/${input.name}"
        }
    }

    /**
     * Create a rule that targets the given function
     */
    public suspend fun putRule(bus: String, pattern: String, target: EventBridgeFunction) {
        val ruleName = UUID.randomUUID().toString()
        putRule(PutRuleRequest {
            eventBusName = bus
            eventPattern = pattern
            name = UUID.randomUUID().toString()
        })

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

        putRule {
            this.eventBusName = eventBusName
            eventPattern = pattern
            this.name = ruleName
        }

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

    override suspend fun putTargets(input: PutTargetsRequest): PutTargetsResponse {
        val bus = buses[input.eventBusName ?: "default"]
            ?: throw throw AwsServiceException(input.eventBusName, null)
        val rule =
            bus.rules.find { it.rule.name == input.rule } ?: throw throw AwsServiceException(
                input.rule, null
            )
        rule.targets.addAll(input.targets!!.map {
            LocalTarget()
        })
        return PutTargetsResponse {}
    }

    public fun putTarget(eventBus: String, ruleName: String, handler: suspend (InputStream, Context) -> Unit) {
        val bus = buses[eventBus]
            ?: throw throw AwsServiceException(eventBus, null)
        val rule = bus.rules.find { it.rule.name == ruleName } ?: throw throw AwsServiceException(
            ruleName, null
        )

        rule.targets.add(LocalTarget(handler))
    }

    override suspend fun putEvents(input: PutEventsRequest): PutEventsResponse {
        val results = input.entries!!.map { entry ->
            synchronized(events) {
                events.add(entry)
            }
            val busName = entry.eventBusName?.let {
                if (it.startsWith("arn:aws")) it.substringAfter("event-bus/")
                else it
            }
            buses[busName]?.putEvent(entry)
            PutEventsResultEntry {}
        }
        return PutEventsResponse {
            entries = results
        }
    }

}

/**
 * Get events of a given type and deserialize
 */
public fun <T> EventBridgeMock.eventsOfType(type: EventSchema<T>): List<T> {
    return this.eventsOfType(type.type).map {
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