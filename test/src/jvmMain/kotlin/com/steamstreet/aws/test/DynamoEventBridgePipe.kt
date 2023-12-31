package com.steamstreet.aws.test

import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.sdk.kotlin.services.eventbridge.model.PutEventsRequestEntry
import aws.sdk.kotlin.services.eventbridge.putEvents
import com.steamstreet.dynamokt.DynamoStreamEvent
import kotlinx.serialization.json.Json

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
    suspend fun streamProcessor(event: DynamoStreamEvent) {
        eventBridge.putEvents {
            entries = listOf(
                PutEventsRequestEntry {
                    eventBusName = (eventBusArn.substringAfterLast(":event-bus/"))
                    detail = (Json.encodeToString(DynamoStreamEvent.serializer(), event))
                    this.detailType = detailType
                    this.source = source
                }
            )
        }
    }
    return ::streamProcessor
}