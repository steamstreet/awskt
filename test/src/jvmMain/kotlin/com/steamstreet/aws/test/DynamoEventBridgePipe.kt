package com.steamstreet.aws.test

import com.steamstreet.awskt.eventbridge.EventBridge
import com.steamstreet.awskt.eventbridge.PutEventsEntry
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
    eventBridge: EventBridge,
    eventBusArn: String,
    detailType: String,
    source: String
): StreamProcessorFunction {
    suspend fun streamProcessor(event: DynamoStreamEvent) {
        eventBridge.putEvents(
            listOf(
                PutEventsEntry(
                    eventBusName = eventBusArn.substringAfterLast(":event-bus/"),
                    detail = Json.encodeToString(DynamoStreamEvent.serializer(), event),
                    detailType = detailType,
                    source = source,
                )
            )
        )
    }
    return ::streamProcessor
}
