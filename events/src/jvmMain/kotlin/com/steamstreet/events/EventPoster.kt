package com.steamstreet.events

import com.steamstreet.env.Env
import com.steamstreet.mutableLazy

/**
 * Defines an interface for submitting application events
 */
public interface ApplicationEventPoster {
    /**
     * Post the event as a string
     */
    public suspend fun post(eventType: String, eventDetail: String, source: String? = null)

    /**
     * Post a set of events
     */
    public suspend fun post(events: Collection<Event>)
}

/**
 * Mostly internal representation of an event.
 */
public interface Event {
    public val type: String
    public val detail: String?
    public val source: String?
}

public var poster: ApplicationEventPoster by mutableLazy {
    EventBridgeSubmitter(
        Env.optional("EventBusArn") ?: throw IllegalStateException("Missing EventBusArn"),
        Env.optional("EventPosterSource") ?: throw IllegalStateException("Missing EventPosterSource")
    )
}