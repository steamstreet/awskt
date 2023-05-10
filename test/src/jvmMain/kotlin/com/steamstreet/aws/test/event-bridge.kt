package com.steamstreet.aws.test

import com.amazonaws.services.lambda.runtime.Context
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.steamstreet.aws.lambda.eventbridge.EventBridgeFunction
import com.steamstreet.events.EventSchema
import kotlinx.serialization.json.*
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.model.*
import software.amazon.awssdk.services.lambda.LambdaClient
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
    val region: Region = Region.US_WEST_2
) : EventBridgeClient {
    private val buses = hashMapOf(
        "default" to Bus("default")
    )

    val events = ArrayList<PutEventsRequestEntry>()

    val jackson = jacksonObjectMapper()
    var processSemaphore = AtomicInteger(0)

    val processing: Boolean get() = processSemaphore.get() != 0

    override fun close() {}

    override fun serviceName(): String = "EventBridge"

    private inner class EventRule(val rule: PutRuleRequest) {
        val targets = ArrayList<LocalTarget>()
        val ruleExpression = rule.eventPattern()?.let { jackson.readValue<Map<String, Any>>(it) }
    }

    /**
     * Clear all saved events
     */
    fun clearSaved() {
        events.clear()
    }

    fun eventsOfType(detailType: String): List<PutEventsRequestEntry> {
        return events.filter {
            it.detailType() == detailType
        }
    }

    private inner class Bus(val name: String) {
        val rules = ArrayList<EventRule>()

        fun putEvent(entry: PutEventsRequestEntry) {
            processSemaphore.incrementAndGet()

            val str = buildJsonObject {
                put("source", entry.source())
                put("detail-type", entry.detailType())
                if (entry.detail().isNotBlank()) {
                    put("detail", Json.parseToJsonElement(entry.detail()))
                }
            }.toString()

            thread {
                rules.filter {
                    match(it.rule.eventPattern(), str)
                }.flatMap { it.targets }.forEach {
                    sendToTarget(entry, it)
                }
                processSemaphore.decrementAndGet()
            }
        }

        private fun sendToTarget(
            entry: PutEventsRequestEntry,
            target: LocalTarget
        ) {
            val event = buildJsonObject {
                put("source", JsonPrimitive(entry.source()))
                put("detail-type", JsonPrimitive(entry.detailType()))
                put("account", JsonPrimitive(accountId))
                put("detail", Json.parseToJsonElement(entry.detail()))
                put("region", JsonPrimitive(this@EventBridgeLocal.region.id()))
                put("id", JsonPrimitive(UUID.randomUUID().toString()))
                put("time", JsonPrimitive(Instant.now().toString()))
            }
            val buffer = SdkBytes.fromString(event.toString(), Charsets.UTF_8)

            if (target.arn != null) {
                val arnPieces = target.arn.split(":")
                if (arnPieces.size > 2) {
                    when (arnPieces[2]) {
                        "lambda" -> {
                            lambda.invoke {
                                it.functionName(target.arn)
                                it.payload(buffer)
                                it.invocationType(software.amazon.awssdk.services.lambda.model.InvocationType.EVENT)
                            }
                        }
                    }
                }
            } else if (target.handler != null) {
                target.handler.invoke(buffer.asInputStream(), LambdaLocalContext())
            }
        }

        fun filterEvent(entry: PutEventsRequestEntry, rule: EventRule): Boolean {
            if (rule.ruleExpression == null) return false
            // for now we just filter on detail type since that is our common use case.
            val detailTypeFilter = rule.ruleExpression["detail-type"] as? List<*>
            if (!detailTypeFilter.isNullOrEmpty()) {
                return detailTypeFilter.contains("*") || detailTypeFilter.contains(entry.detailType())
            }
            return false
        }

        fun putRule(rule: PutRuleRequest) {
            if (rules.find { it.rule.name() == rule.name() } != null) {
                throw AwsServiceException.create("Duplicate rule name", null)
            }
            rules.add(EventRule(rule))
        }
    }

    override fun listRules(listRulesRequest: ListRulesRequest): ListRulesResponse {
        val bus = buses[listRulesRequest.eventBusName() ?: "default"]
            ?: throw AwsServiceException.create(listRulesRequest.eventBusName(), null)

        return ListRulesResponse.builder().rules(
            bus.rules.map {
                Rule.builder().name(it.rule.name()).build()
            }).build()
    }

    override fun createEventBus(createEventBusRequest: CreateEventBusRequest): CreateEventBusResponse {
        buses[createEventBusRequest.name()] = Bus(createEventBusRequest.name())
        return CreateEventBusResponse.builder()
            .eventBusArn("arn:aws:events:${region.id()}:$accountId:event-bus/${createEventBusRequest.name()}")
            .build()
    }

    override fun putRule(putRuleRequest: PutRuleRequest): PutRuleResponse {
        val bus =
            buses[putRuleRequest.eventBusName() ?: "default"] ?: throw throw AwsServiceException.create(putRuleRequest.eventBusName(), null)

        bus.putRule(putRuleRequest)
        return PutRuleResponse.builder()
            .ruleArn("arn:aws:events:${region.id()}:$accountId:rule/${putRuleRequest.name()}").build()
    }

    /**
     * Utility to set an EventBridgeFunction as a target for an event.
     */
    fun putTarget(eventBusName: String, pattern: String, handler: EventBridgeFunction) {
        val ruleName = UUID.randomUUID().toString()

        putRule {
            it.eventBusName(eventBusName)
            it.eventPattern(pattern)
            it.name(ruleName)
        }

        putTarget(eventBusName, ruleName) { input, context ->
            handler.execute(input, ByteArrayOutputStream(), context)
        }
    }

    override fun putTargets(putTargetsRequest: PutTargetsRequest): PutTargetsResponse {
        val bus = buses[putTargetsRequest.eventBusName() ?: "default"]
            ?: throw throw AwsServiceException.create(putTargetsRequest.eventBusName(), null)
        val rule =
            bus.rules.find { it.rule.name() == putTargetsRequest.rule() } ?: throw throw AwsServiceException.create(
                putTargetsRequest.rule(), null
            )
        rule.targets.addAll(putTargetsRequest.targets().map {
            LocalTarget(it.arn())
        })
        return PutTargetsResponse.builder().build()
    }

    fun putTarget(eventBus: String, ruleName: String, handler: (InputStream, Context) -> Unit) {
        val bus = buses[eventBus]
            ?: throw throw AwsServiceException.create(eventBus, null)
        val rule = bus.rules.find { it.rule.name() == ruleName } ?: throw throw AwsServiceException.create(
            ruleName, null
        )

        rule.targets.add(LocalTarget(null, handler))
    }

    override fun putEvents(putEventsRequest: PutEventsRequest): PutEventsResponse {
        val results = putEventsRequest.entries().map { entry ->
            synchronized(events) {
                events.add(entry)
            }
            buses[entry.eventBusName()]?.putEvent(entry)
            PutEventsResultEntry.builder().build()
        }
        return PutEventsResponse.builder().entries(results).failedEntryCount(0).build()
    }

}

fun match(filter: JsonElement, data: JsonElement): Boolean {
    if (data is JsonPrimitive) {
        if (filter is JsonArray) {
            filter.forEach { filterElement ->
                if (match(filterElement, data)) {
                    return true
                }
            }
        } else if (filter is JsonPrimitive) {
            return data == filter
        } else if (filter is JsonObject) {
            val prefix = (filter["prefix"] as? JsonPrimitive)?.content
            return if (prefix != null) {
                (data.content.startsWith(prefix))
            } else {
                false
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
        Json.decodeFromString(type.serializer, it.detail())
    }
}