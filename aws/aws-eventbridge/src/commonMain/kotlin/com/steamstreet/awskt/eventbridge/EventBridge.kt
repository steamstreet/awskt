package com.steamstreet.awskt.eventbridge

import com.steamstreet.awskt.core.AwsCallObserver
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsHttpTimeouts
import com.steamstreet.awskt.core.AwsProtocol
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.BatchRetry
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.awsHttpClient
import com.steamstreet.awskt.core.callJson
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.core.resolveRegion
import io.ktor.client.HttpClient

/**
 * EventBridge's AWS-JSON 1.1 dialect.
 *
 * The target prefix is `AWSEvents` and the endpoint prefix is `events` — they differ, which is why
 * both are named explicitly here rather than letting one default from the other.
 */
public val EVENTBRIDGE_PROTOCOL: AwsProtocol =
    AwsProtocol.awsJson1_1(endpointPrefix = "events", targetPrefix = "AWSEvents")

/**
 * An EventBridge client.
 *
 * ### Extending it
 *
 * As with `DynamoDb` (plan Decision 18), [client] is public and [putEvents] has no privileged
 * access to it — it is a `callJson` on that same object. An operation this library does not ship,
 * such as `PutRule` or `CreateEventBus`, can be added downstream as an extension function with
 * identical signing, retry and error handling.
 */
public interface EventBridgeApi : AutoCloseable {
    /** The signed transport. Public because it is the extension seam — see the interface KDoc. */
    public val client: AwsServiceClient

    /**
     * One raw `PutEvents` call: at most **10** entries, and per-entry failures reported in the body
     * of an HTTP 200 for the caller to handle.
     *
     * Most callers want [putEventsAll] instead, which chunks to 10 and resubmits the retryable
     * per-entry failures with backoff. This stays on the interface because it is the operation
     * EventBridge actually has, and because [putEventsAll] is written against it.
     */
    public suspend fun putEvents(entries: List<PutEventsEntry>): PutEventsResponse
}

/** Configuration for [EventBridgeApi]. */
public class EventBridgeConfig {
    public var region: String? = null
    public var endpointUrl: String? = null
    public var credentialsProvider: AwsCredentialsProvider? = null

    /**
     * A client to send on, instead of one built here.
     *
     * Supplying one **bypasses [caInfo] and [httpTimeouts]**: those are arguments to the client this
     * factory would have built, and a client the caller already owns is configured by the caller. A
     * caller-supplied client with no `HttpTimeout` plugin has no attempt bound at all.
     */
    public var httpClient: HttpClient? = null
    public var retryConfig: RetryConfig = RetryConfig()

    /** CA bundle for the native Curl engine. Null uses the system trust store. */
    public var caInfo: String? = null

    /**
     * Per-attempt time limits for the client this factory builds. Ignored when [httpClient] is set.
     *
     * Worth reading together with [EventBridgeApi.putEvents]'s `NOT_IDEMPOTENT` safety: a request
     * timeout may have landed, so a timed-out `PutEvents` is surfaced rather than replayed and the
     * caller decides whether to republish. Shortening this value therefore converts hangs into
     * *decisions*, not into duplicate deliveries.
     */
    public var httpTimeouts: AwsHttpTimeouts = AwsHttpTimeouts()

    /**
     * Notified of every attempt, retry decision and give-up. Null means no instrumentation.
     *
     * Worth wiring up in this module in particular: `PutEvents` is `NOT_IDEMPOTENT`, so an ambiguous
     * transport failure is surfaced rather than replayed, and the observer is how you find out how
     * often that is happening before someone notices the missing events.
     *
     * Unlike [httpTimeouts] and [caInfo], this is **not** bypassed by supplying your own
     * [httpClient]: it observes the retry loop, which is this library's, rather than the transport
     * underneath it, which may be the caller's.
     */
    public var observer: AwsCallObserver? = null
}

/** Builds an EventBridge client. */
public fun EventBridge(configure: EventBridgeConfig.() -> Unit = {}): EventBridgeApi {
    val config = EventBridgeConfig().apply(configure)
    val region = resolveRegion(config.region)
    // One binding for the effective client, handed to both the transport and the close path — see
    // the same note in `DynamoDb()`, where splitting the two leaked the client we had just built.
    val httpClient = config.httpClient ?: awsHttpClient(config.caInfo, config.httpTimeouts)
    return DefaultEventBridge(
        client = AwsServiceClient(
            httpClient = httpClient,
            credentialsProvider = config.credentialsProvider ?: defaultCredentialsProvider(),
            endpoint = resolveEndpoint("events", region, config.endpointUrl),
            region = region,
            protocol = EVENTBRIDGE_PROTOCOL,
            retryConfig = config.retryConfig,
            observer = config.observer,
        ),
        ownsHttpClient = config.httpClient == null,
        httpClient = httpClient,
    )
}

internal class DefaultEventBridge(
    override val client: AwsServiceClient,
    private val ownsHttpClient: Boolean = false,
    private val httpClient: HttpClient? = null,
) : EventBridgeApi {

    /**
     * `NOT_IDEMPOTENT`, and this is the interesting call in the module.
     *
     * `PutEvents` has no request token — unlike `TransactWriteItems`, there is nothing to
     * de-duplicate on. If the bytes reached EventBridge and the socket died before the response
     * came back, a retry publishes every entry in the batch a second time, and any rule targeting
     * them fires twice. Duplicate delivery is cheaper than silent loss for most callers, but it is
     * not a decision this client gets to make silently: an ambiguous transport failure surfaces as
     * an exception, and the caller decides whether to republish.
     */
    override suspend fun putEvents(entries: List<PutEventsEntry>): PutEventsResponse =
        mapErrors {
            client.callJson(
                "PutEvents",
                PutEventsRequest(entries),
                PutEventsRequest.serializer(),
                PutEventsResponse.serializer(),
                safety = OperationSafety.NOT_IDEMPOTENT,
            )
        }

    override fun close() {
        if (ownsHttpClient) httpClient?.close()
    }
}

// -- Batching ------------------------------------------------------------------------------------

/** `PutEvents` accepts at most this many entries per request. Above it, AWS returns a 400. */
private const val PUT_EVENTS_MAX_ENTRIES = 10

/** AWS's documented per-entry ceiling. The whole request is capped at 1 MB on top of this. */
private const val PUT_EVENTS_MAX_ENTRY_BYTES = 256 * 1024

/**
 * The per-entry `ErrorCode`s AWS documents as "retry with exponential backoff".
 *
 * Source: the `PutEvents` page of the *Amazon EventBridge API Reference*, which names exactly these
 * two under `PutEventsResultEntry.ErrorCode` — a throttled entry and an EventBridge-side fault are
 * both transient conditions on an entry that was otherwise well-formed. Everything else
 * (`MalformedDetail`, `InvalidArgument`, `NotAuthorizedForSourceException`, and any code added
 * later) describes the entry itself, so resubmitting it byte-for-byte produces the same rejection
 * and merely spends the backoff budget on a guaranteed failure.
 *
 * Deliberately *not* [com.steamstreet.awskt.core.RetryConfig]'s transport table: that one is keyed
 * on whole-request codes and includes entries — `TransactionInProgressException`, `SlowDown` — that
 * cannot appear here at all.
 */
private val RETRYABLE_ENTRY_ERROR_CODES: Set<String> = setOf(
    "ThrottlingException",
    "InternalFailure",
)

/**
 * `PutEvents` for a batch of any size: chunked to 10, with per-entry failures resubmitted.
 *
 * ### What this fixes
 *
 * [EventBridgeApi.putEvents] is one raw call, and `PutEvents` rejects the whole request above **10
 * entries**, so an eleven-entry batch is a `ValidationException` at runtime. The subtler half is
 * that EventBridge reports per-entry failures inside an **HTTP 200** — see
 * [PutEventsResponse.failedEntryCount] — with no error code on the response and no
 * `x-amz-retry-after`, so the transport's retry loop never sees a throttled entry and never paces
 * it. This is the same shape of problem as DynamoDB's `UnprocessedKeys`, and it is solved the same
 * way: `aws-dynamodb`'s `batchWriteAll` is the sibling to read alongside this.
 *
 * ### Which failures are resubmitted
 *
 * | `ErrorCode`                 | Handling                                              |
 * |-----------------------------|-------------------------------------------------------|
 * | `ThrottlingException`       | resubmitted, paced by [backoff]                        |
 * | `InternalFailure`           | resubmitted, paced by [backoff]                        |
 * | anything else               | terminal — see the rule below                          |
 * | *(an `EventId` is present)* | published; never resubmitted                           |
 *
 * ### The terminal-failure rule
 *
 * A non-retryable per-entry error **stops the whole call at the end of the round that produced
 * it**: the retryable entries of that same chunk are *not* resubmitted, and no later chunk is sent.
 * The alternative — finish the chunk, then report — spends the full backoff budget on a batch whose
 * outcome is already decided, delays the caller's discovery of an unrecoverable error by up to
 * [com.steamstreet.awskt.core.RetryConfig.maxTotalRetryDuration], and publishes *more* events that
 * the caller then has to reconcile. Stopping early keeps the account of what landed as small and as
 * accurate as possible.
 *
 * ### The budget
 *
 * Resubmissions are paced by [backoff] with a `slept` accumulator and a round counter that are both
 * **per chunk**, exactly as in `batchGetAll`: one budget shared across a 500-entry batch would
 * leave the last chunks nothing to spend. A chunk that still has retryable failures after
 * [maxRounds] rounds, or that exhausts [com.steamstreet.awskt.core.RetryConfig.maxTotalRetryDuration],
 * throws rather than returning a short result.
 *
 * ### Duplicate delivery
 *
 * Every exception this throws is a [PutEventsPartialFailureException] carrying both halves of the
 * batch, because **the successful entries have already been published and cannot be rolled back**
 * ([EventBridgeApi.putEvents] is `NOT_IDEMPOTENT` for the same underlying reason: there is no
 * request token). Republishing the original list therefore double-delivers everything in
 * [PutEventsPartialFailureException.succeeded]; republish
 * [PutEventsPartialFailureException.failed]`.map { it.first }` instead.
 *
 * @param entries the batch, in the order the caller wants it published. Chunk boundaries are the
 *   only reordering: within a chunk EventBridge preserves order, and the returned list is always in
 *   request order regardless of how many rounds an entry took.
 * @return one result entry per request entry, positionally, each carrying its `EventId`. Only ever
 *   returned when *every* entry was published.
 * @throws ValidationException before anything is sent, if an entry exceeds the 256 KB limit.
 * @throws PutEventsPartialFailureException if any entry could not be published.
 */
public suspend fun EventBridgeApi.putEventsAll(
    entries: List<PutEventsEntry>,
    maxRounds: Int = 10,
    backoff: BatchRetry = BatchRetry.Default,
): List<PutEventsResultEntry> {
    entries.forEachIndexed(::requireEntryWithinSizeLimit)

    // One slot per request entry, holding the most recent result seen for it. Keyed by the entry's
    // index in `entries` rather than by its position in a chunk, which is what keeps the returned
    // list in request order and lets the exception name the exact entries that failed.
    val outcomes = arrayOfNulls<PutEventsResultEntry>(entries.size)

    for (chunkStart in entries.indices step PUT_EVENTS_MAX_ENTRIES) {
        val chunkEnd = minOf(chunkStart + PUT_EVENTS_MAX_ENTRIES, entries.size)
        var pending: List<Int> = (chunkStart until chunkEnd).toList()
        var round = 0
        // Per chunk, not per call — see the matching note in `aws-dynamodb`'s batchGetAll.
        var slept = 0L

        while (true) {
            val response = putEvents(pending.map { entries[it] })
            val results = response.entries.orEmpty()

            val retryable = mutableListOf<Int>()
            val terminal = mutableListOf<Int>()
            pending.forEachIndexed { position, index ->
                // Result entry i corresponds to request entry i (see [PutEventsResultEntry]), and
                // `pending` is what we just sent, in order — so `position` indexes the response and
                // `index` indexes the caller's list. A response shorter than the request is not
                // something EventBridge does; if it ever happens, treating the missing tail as
                // failed is the only reading that cannot invent an EventId for an entry.
                val result = results.getOrNull(position) ?: MISSING_RESULT_ENTRY
                outcomes[index] = result
                when {
                    result.eventId != null -> Unit
                    result.errorCode in RETRYABLE_ENTRY_ERROR_CODES -> retryable += index
                    else -> terminal += index
                }
            }

            if (terminal.isNotEmpty()) {
                throw partialFailure(
                    entries, outcomes, terminal + retryable,
                    "PutEvents rejected ${terminal.size} " +
                        "${if (terminal.size == 1) "entry" else "entries"} with a non-retryable " +
                        "error (${terminal.joinToString { outcomes[it]?.errorCode ?: "unknown" }}); " +
                        "resubmitting them would fail identically, so the batch was stopped.",
                )
            }
            if (retryable.isEmpty()) break

            if (++round >= maxRounds) {
                throw partialFailure(
                    entries, outcomes, retryable,
                    "PutEvents still had ${retryable.size} retryable per-entry " +
                        "${if (retryable.size == 1) "failure" else "failures"} after $maxRounds " +
                        "rounds; reporting success on a partially published batch would be silent " +
                        "event loss.",
                )
            }
            slept = backoff.awaitResubmit(round - 1, slept) ?: throw partialFailure(
                entries, outcomes, retryable,
                "PutEvents still had ${retryable.size} retryable per-entry " +
                    "${if (retryable.size == 1) "failure" else "failures"} after $round rounds and " +
                    "${slept}ms of throttling backoff, which exhausted the " +
                    "${backoff.config.maxTotalRetryDuration} budget.",
            )
            pending = retryable
        }
    }

    // Unreachable with a null in it: every slot was filled by the round that published it, and any
    // slot that was not would have gone down one of the throw paths above.
    return outcomes.requireNoNulls().toList()
}

/** Stands in for a result entry EventBridge did not send. See the call site. */
private val MISSING_RESULT_ENTRY = PutEventsResultEntry(
    errorCode = "MissingResultEntry",
    errorMessage = "EventBridge returned fewer result entries than the request had entries.",
)

/**
 * Builds the failure, partitioning the batch into what was published and what was not.
 *
 * [failedIndices] is sorted so [PutEventsPartialFailureException.failed] comes back in request
 * order too — it arrives here as "terminal, then still-retryable", which is the order the loop
 * classified them in, not the order the caller wrote them in.
 */
private fun partialFailure(
    entries: List<PutEventsEntry>,
    outcomes: Array<PutEventsResultEntry?>,
    failedIndices: List<Int>,
    message: String,
): PutEventsPartialFailureException = PutEventsPartialFailureException(
    succeeded = outcomes.filterNotNull().filter { it.eventId != null },
    failed = failedIndices.sorted().map { entries[it] to (outcomes[it] ?: MISSING_RESULT_ENTRY) },
    // 200 because that is the status EventBridge actually returned: the failure was reported in the
    // body of a successful HTTP exchange, and pretending otherwise would misdescribe it.
    message = message,
    statusCode = 200,
)

/**
 * Rejects an oversized entry before anything is sent, naming the index that is at fault.
 *
 * Worth doing client-side because the server-side failure is far worse than it looks: one oversized
 * entry fails the **entire request**, so nine innocent entries in the same chunk are rejected with
 * a message that does not say which one was the problem. Failing here names it.
 *
 * The size is the UTF-8 byte count of `Source`, `DetailType` and `Detail`, which is the substance
 * of AWS's documented formula. That formula also adds 14 bytes when `Time` is set and counts each
 * `Resources` ARN — neither of which [PutEventsEntry] can express — and does not count
 * `EventBusName`. The check is therefore a floor rather than an exact reproduction: it catches
 * every entry AWS would reject on size, and an entry within a few bytes of the limit is still
 * decided by AWS.
 */
private fun requireEntryWithinSizeLimit(index: Int, entry: PutEventsEntry) {
    val size = utf8Length(entry.source) + utf8Length(entry.detailType) + utf8Length(entry.detail)
    if (size > PUT_EVENTS_MAX_ENTRY_BYTES) {
        throw ValidationException(
            "PutEvents entry $index is $size bytes of Source + DetailType + Detail, over the " +
                "$PUT_EVENTS_MAX_ENTRY_BYTES byte per-entry limit.",
            400,
        )
    }
}

/**
 * UTF-8 byte length without materialising the bytes.
 *
 * `encodeToByteArray().size` would allocate a second copy of every `Detail` in the batch purely to
 * measure it, which for a batch near the limit is megabytes of garbage per call.
 */
private fun utf8Length(value: String?): Int {
    if (value == null) return 0
    var length = 0
    var i = 0
    while (i < value.length) {
        val code = value[i].code
        length += when {
            code < 0x80 -> 1
            code < 0x800 -> 2
            // A surrogate pair is one 4-byte code point, counted once and then skipped over. An
            // unpaired surrogate encodes as the 3-byte replacement character, which is 3 either way.
            value[i].isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                i++
                4
            }

            else -> 3
        }
        i++
    }
    return length
}
