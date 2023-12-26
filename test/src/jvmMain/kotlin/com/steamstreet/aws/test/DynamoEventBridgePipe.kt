package com.steamstreet.aws.test

import aws.sdk.kotlin.services.dynamodbstreams.model.Record
import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.sdk.kotlin.services.eventbridge.model.PutEventsRequestEntry
import aws.sdk.kotlin.services.eventbridge.putEvents
import aws.smithy.kotlin.runtime.time.epochMilliseconds
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import com.steamstreet.dynamokt.DynamoStreamEvent
import com.steamstreet.dynamokt.DynamoStreamEventDetail
import kotlinx.serialization.json.Json
import java.util.*

/**
 * Returns a StreamProcessorFunction that can be used to pipe stream events
 * to an EventBridge bus.
 *
 * Pass this to the DynamoStreamRunner constructor to pipe data to
 * the provided EventBridge client (which will typically be EventBridgeMock).
 */
public fun dynamoPipe(
    eventBridge: EventBridgeClient,
    eventBusArn: String,
    detailType: String,
    source: String
): StreamProcessorFunction {
    @Suppress("UNUSED_PARAMETER")
    suspend fun streamProcessor(event: DynamodbEvent, record: Record) {
        val pipeEvent = DynamoStreamEvent(
            UUID.randomUUID().toString(),
            record.eventName!!.toString(),
            record.eventVersion!!,
            record.eventSource!!,
            record.awsRegion!!,
            record.dynamodb!!.let {
                DynamoStreamEventDetail(
                    it.approximateCreationDateTime!!.epochMilliseconds.toDouble(),
                    record.dynamodb!!.keys!!.toModelAttributeValue(),
                    record.dynamodb?.newImage?.toModelAttributeValue(),
                    record.dynamodb?.oldImage?.toModelAttributeValue()
                )
            },
            record.eventSource!!
        )

        eventBridge.putEvents {
            entries = listOf(
                PutEventsRequestEntry {
                    eventBusName = (eventBusArn.substringAfterLast(":event-bus/"))
                    detail = (Json.encodeToString(DynamoStreamEvent.serializer(), pipeEvent))
                    this.detailType = detailType
                    this.source = source
                }
            )
        }
    }

    return ::streamProcessor
}