package com.steamstreet.events

import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.sdk.kotlin.services.eventbridge.model.PutEventsRequestEntry
import aws.sdk.kotlin.services.eventbridge.putEvents
import kotlinx.coroutines.runBlocking


/**
 * Submitter that posts to the event bridge
 */
public class EventBridgeSubmitter(
    private val busName: String, private val source: String,
    private val eventBridge: EventBridgeClient = runBlocking { EventBridgeClient.fromEnvironment() }
) : ApplicationEventPoster {
    override suspend fun post(eventType: String, eventDetail: String, source: String?) {
        eventBridge.putEvents {
            entries = listOf(
                PutEventsRequestEntry {
                    eventBusName = busName
                    detail = eventDetail
                    detailType = eventType
                    this.source = source ?: this@EventBridgeSubmitter.source
                }
            )
        }
    }

    override suspend fun post(events: Collection<Event>) {
        eventBridge.putEvents {
            entries = events.map {
                PutEventsRequestEntry {
                    eventBusName = busName
                    detail = it.detail
                    detailType = it.type
                    this.source = it.source ?: this@EventBridgeSubmitter.source
                }
            }

        }
    }
}