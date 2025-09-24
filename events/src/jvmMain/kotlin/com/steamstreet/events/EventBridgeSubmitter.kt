package com.steamstreet.events

import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.sdk.kotlin.services.eventbridge.model.PutEventsRequestEntry
import aws.sdk.kotlin.services.eventbridge.model.PutEventsResponse
import aws.sdk.kotlin.services.eventbridge.putEvents
import com.steamstreet.awskt.logging.logWarning
import kotlinx.coroutines.runBlocking

/**
 * Submitter that posts to the event bridge
 */
public class EventBridgeSubmitter(
    private val busName: String, private val source: String,
    private val eventBridge: EventBridgeClient = runBlocking { EventBridgeClient.fromEnvironment() }
) : ApplicationEventPoster {
    override suspend fun post(eventType: String, eventDetail: String, source: String?): String? {
        return checkResponse(eventBridge.putEvents {
            entries = listOf(
                PutEventsRequestEntry {
                    eventBusName = busName
                    detail = eventDetail
                    detailType = eventType
                    this.source = source ?: this@EventBridgeSubmitter.source
                }
            )
        }).firstOrNull()
    }

    override suspend fun post(events: Collection<Event>): List<String?> {
        return checkResponse(eventBridge.putEvents {
            entries = events.map {
                PutEventsRequestEntry {
                    eventBusName = busName
                    detail = it.detail
                    detailType = it.type
                    this.source = it.source ?: this@EventBridgeSubmitter.source
                }
            }
        })
    }

    /**
     * Check the response and return a list of event ids.
     */
    private fun checkResponse(response: PutEventsResponse): List<String?> {
        return response.entries?.map {
            if (it.errorCode != null) {
                logWarning(
                    "Unable to publish event", "errorCode" to it.errorCode,
                    "errorMessage" to it.errorMessage,
                    "eventId" to it.eventId
                )
            }
            it.eventId
        }.orEmpty()
    }
}