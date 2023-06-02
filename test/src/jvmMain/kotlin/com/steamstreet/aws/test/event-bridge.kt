package com.steamstreet.aws.test

import aws.sdk.kotlin.runtime.AwsServiceException
import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.sdk.kotlin.services.eventbridge.model.*
import aws.sdk.kotlin.services.eventbridge.putRule
import aws.sdk.kotlin.services.lambda.LambdaClient
import aws.sdk.kotlin.services.lambda.invoke
import aws.sdk.kotlin.services.lambda.model.InvocationType
import com.amazonaws.services.lambda.runtime.Context
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.steamstreet.aws.lambda.eventbridge.EventBridgeFunction
import com.steamstreet.events.EventSchema
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private class LocalTarget(
    val arn: String?,
    val handler: ((InputStream, Context) -> Unit)? = null
)

/**
 * A local mocked version of event bridge.
 */
class EventBridgeLocal(
    val lambda: LambdaClient, val accountId: String = "1234",
    val region: String = "us-west-2",
    val mockk: EventBridgeClient = mockk(relaxed = true)
) : EventBridgeClient by mockk {
    private val buses = hashMapOf(
        "default" to Bus("default")
    )


    val events = ArrayList<PutEventsRequestEntry>()

    val jackson = jacksonObjectMapper()
    var processSemaphore = AtomicInteger(0)

    val processing: Boolean get() = processSemaphore.get() != 0

    override fun close() {}

    private inner class EventRule(val rule: PutRuleRequest) {
        val targets = ArrayList<LocalTarget>()
        val ruleExpression = rule.eventPattern?.let { jackson.readValue<Map<String, Any>>(it) }
    }

    /**
     * Clear all saved events
     */
    fun clearSaved() {
        events.clear()
    }

    fun eventsOfType(detailType: String): List<PutEventsRequestEntry> {
        return events.filter {
            it.detailType == detailType
        }
    }

    private inner class Bus(val name: String) {
        val rules = ArrayList<EventRule>()

        fun putEvent(entry: PutEventsRequestEntry) {
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
                        match(it.rule.eventPattern!!, str)
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
                put("region", JsonPrimitive(this@EventBridgeLocal.region))
                put("id", JsonPrimitive(UUID.randomUUID().toString()))
                put("time", JsonPrimitive(Instant.now().toString()))
            }
            val buffer = event.toString().toByteArray()

            if (target.arn != null) {
                val arnPieces = target.arn.split(":")
                if (arnPieces.size > 2) {
                    when (arnPieces[2]) {
                        "lambda" -> {
                            lambda.invoke {
                                functionName = target.arn
                                payload = (buffer)
                                invocationType = InvocationType.Event
                            }
                        }
                    }
                }
            } else if (target.handler != null) {
                target.handler.invoke(buffer.inputStream(), LambdaLocalContext())
            }
        }

        fun filterEvent(entry: PutEventsRequestEntry, rule: EventRule): Boolean {
            if (rule.ruleExpression == null) return false
            // for now we just filter on detail type since that is our common use case.
            val detailTypeFilter = rule.ruleExpression["detail-type"] as? List<*>
            if (!detailTypeFilter.isNullOrEmpty()) {
                return detailTypeFilter.contains("*") || detailTypeFilter.contains(entry.detailType)
            }
            return false
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
        buses[input.name!!] = Bus(input.name!!)
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
     * Utility to set an EventBridgeFunction as a target for an event.
     */
    suspend fun putTarget(eventBusName: String, pattern: String, handler: EventBridgeFunction) {
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

    override suspend fun putTargets(input: PutTargetsRequest): PutTargetsResponse {
        val bus = buses[input.eventBusName ?: "default"]
            ?: throw throw AwsServiceException(input.eventBusName, null)
        val rule =
            bus.rules.find { it.rule.name == input.rule } ?: throw throw AwsServiceException(
                input.rule, null
            )
        rule.targets.addAll(input.targets!!.map {
            LocalTarget(it.arn)
        })
        return PutTargetsResponse {}
    }

    fun putTarget(eventBus: String, ruleName: String, handler: (InputStream, Context) -> Unit) {
        val bus = buses[eventBus]
            ?: throw throw AwsServiceException(eventBus, null)
        val rule = bus.rules.find { it.rule.name == ruleName } ?: throw throw AwsServiceException(
            ruleName, null
        )

        rule.targets.add(LocalTarget(null, handler))
    }

    override suspend fun putEvents(input: PutEventsRequest): PutEventsResponse {
        val results = input.entries!!.map { entry ->
            synchronized(events) {
                events.add(entry)
            }
            buses[entry.eventBusName]?.putEvent(entry)
            PutEventsResultEntry {}
        }
        return PutEventsResponse {
            entries = results
        }
    }

}

fun match(filter: JsonElement, data: JsonElement): Boolean {
    if (data is JsonPrimitive) {
        when (filter) {
            is JsonArray -> {
                filter.forEach { filterElement ->
                    if (match(filterElement, data)) {
                        return true
                    }
                }
            }

            is JsonPrimitive -> {
                return data == filter
            }

            is JsonObject -> {
                val prefix = (filter["prefix"] as? JsonPrimitive)?.content
                return if (prefix != null) {
                    (data.content.startsWith(prefix))
                } else {
                    false
                }
            }
        }
    } else if (data is JsonObject) {
        if (filter is JsonObject) {
            filter.forEach { (key, value) ->
                val dataValue = data[key] ?: return false
                if (!match(value, dataValue)) {
                    return false
                }
            }
            return true
        }
    }
    return false
}

fun match(filter: String, data: String): Boolean {
    val filterJson = Json.parseToJsonElement(filter)
    val dataJson = Json.parseToJsonElement(data)

    return match(filterJson, dataJson)
}

/**
 * Get events of a given type and deserialize
 */
fun <T> EventBridgeLocal.eventsOfType(type: EventSchema<T>): List<T> {
    return this.eventsOfType(type.type).map {
        Json.decodeFromString(type.serializer, it.detail!!)
    }
}