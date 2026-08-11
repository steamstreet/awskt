package com.steamstreet.events

import com.steamstreet.awskt.eventbridge.EventBridge
import com.steamstreet.awskt.eventbridge.EventBridgeApi
import com.steamstreet.awskt.eventbridge.PutEventsEntry
import com.steamstreet.awskt.eventbridge.PutEventsResponse
import com.steamstreet.awskt.logging.`is`
import com.steamstreet.awskt.logging.log

/**
 * Submitter that posts to the event bridge.
 *
 * The client is built eagerly by the default argument, as before — but [EventBridge] is not
 * suspending (plan Decision 4), so constructing this no longer wraps a `runBlocking` around
 * `fromEnvironment()`. That is what lets a submitter be created from a non-suspending context, and
 * it is one more of the `runBlocking` wrappers the plan counted.
 */
public class EventBridgeSubmitter(
    private val busName: String, private val source: String,
    private val eventBridge: EventBridgeApi = EventBridge()
) : ApplicationEventPoster {
    override suspend fun post(eventType: String, eventDetail: String, source: String?): String? {
        return checkResponse(
            eventBridge.putEvents(
                listOf(
                    PutEventsEntry(
                        eventBusName = busName,
                        detail = eventDetail,
                        detailType = eventType,
                        source = source ?: this.source,
                    )
                )
            )
        ).firstOrNull()
    }

    override suspend fun post(events: Collection<Event>): List<String?> {
        return checkResponse(
            eventBridge.putEvents(
                events.map {
                    PutEventsEntry(
                        eventBusName = busName,
                        detail = it.detail,
                        detailType = it.type,
                        source = it.source ?: this.source,
                    )
                }
            )
        )
    }

    /**
     * Check the response and return a list of event ids.
     *
     * **This runs on an HTTP 200.** EventBridge reports per-entry failures in the body, so an entry
     * that was rejected still arrives here with a null `eventId` and a populated `errorCode` — and
     * a caller that only checked the status would see a fully-failed batch as a success. The null
     * in the returned list is the signal, exactly as before.
     *
     * `suspend` now, and it logs through the multiplatform `log` rather than `logWarning`: the
     * latter is JVM-only, and this file is shared with the native targets. On the JVM `log`
     * publishes through slf4j, so the output is unchanged.
     */
    private suspend fun checkResponse(response: PutEventsResponse): List<String?> {
        return response.entries?.map { entry ->
            if (entry.errorCode != null) {
                log.warning("Unable to publish event") {
                    "errorCode" `is` entry.errorCode
                    "errorMessage" `is` entry.errorMessage
                    "eventId" `is` entry.eventId
                }
            }
            entry.eventId
        }.orEmpty()
    }
}
