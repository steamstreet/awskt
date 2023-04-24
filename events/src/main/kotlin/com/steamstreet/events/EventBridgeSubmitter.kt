package com.steamstreet.events

import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry

/**
 * Submitter that posts to the event bridge
 */
public class EventBridgeSubmitter(
    private val busName: String, private val source: String,
    private val eventBridge: EventBridgeClient = EventBridgeClient.builder().build()
) : ApplicationEventPoster {
    override fun post(eventType: String, eventDetail: String, source: String?) {
        eventBridge.putEvents {
            it.entries(
                PutEventsRequestEntry.builder()
                    .eventBusName(busName)
                    .detail(eventDetail)
                    .detailType(eventType)
                    .source(source ?: this.source)
                    .build()
            )
        }
    }

    override suspend fun post(events: Collection<Event>) {
        eventBridge.putEvents {
            it.entries(
                events.map {
                    PutEventsRequestEntry.builder()
                        .eventBusName(busName)
                        .detail(it.detail)
                        .detailType(it.type)
                        .source(it.source ?: source)
                        .build()
                }
            )
        }
    }
}