package com.steamstreet.awskt.eventbridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs, `data class`es for the same reasons as `aws-dynamodb`'s: `copy()` for
 * "this request with one field changed", and structural equality so the SDK differential harness
 * can compare whole objects rather than field-spotting.
 */

/**
 * One entry in a `PutEvents` batch.
 *
 * [detail] is a **JSON document carried as a string** — that is EventBridge's wire contract, not a
 * modelling shortcut, and it is why this is a `String` rather than a `JsonElement`. Callers already
 * hold serialized detail (`ApplicationEventPoster` does), and re-parsing it here only to re-emit it
 * would be a lossy round trip for no gain.
 */
@Serializable
public data class PutEventsEntry(
    @SerialName("EventBusName") val eventBusName: String? = null,
    @SerialName("Source") val source: String? = null,
    @SerialName("DetailType") val detailType: String? = null,
    @SerialName("Detail") val detail: String? = null,
)

@Serializable
internal data class PutEventsRequest(
    @SerialName("Entries") val entries: List<PutEventsEntry>,
)

/**
 * The result of a `PutEvents` call.
 *
 * **[failedEntryCount] can be non-zero on an HTTP 200.** EventBridge reports per-entry failures in
 * the body, not the status line, so a transport that only checks the status reports success on a
 * fully-failed batch. Callers must read this — `EventBridgeSubmitter` does.
 *
 * Reading it is the *minimum*; acting on it correctly means resubmitting the retryable entries with
 * backoff and reporting the rest. [putEventsAll] does that, and returns only when every entry was
 * published, so a caller that uses it never has to inspect this field at all.
 */
@Serializable
public data class PutEventsResponse(
    @SerialName("FailedEntryCount") val failedEntryCount: Int = 0,
    @SerialName("Entries") val entries: List<PutEventsResultEntry>? = null,
)

/**
 * One entry's outcome. Positionally corresponds to the request entry at the same index.
 *
 * Exactly one of [eventId] or ([errorCode], [errorMessage]) is populated.
 */
@Serializable
public data class PutEventsResultEntry(
    @SerialName("EventId") val eventId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("ErrorMessage") val errorMessage: String? = null,
)
