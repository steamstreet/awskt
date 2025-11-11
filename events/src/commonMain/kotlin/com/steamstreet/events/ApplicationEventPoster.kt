package com.steamstreet.events


/**
 * Defines an interface for submitting application events
 */
public interface ApplicationEventPoster {
    /**
     * Post the event as a string
     * @return the event id
     */
    public suspend fun post(eventType: String, eventDetail: String, source: String? = null): String?

    /**
     * Post a set of events
     * @return a list of event ids. For those that are null, the event was not published.
     */
    public suspend fun post(events: Collection<Event>): List<String?>
}

/**
 * Mostly internal representation of an event.
 */
public interface Event {
    public val type: String
    public val detail: String?
    public val source: String?
}
