package com.steamstreet.awskt.kinesis

import com.steamstreet.awskt.core.AwsCallObserver
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsHttpTimeouts
import com.steamstreet.awskt.core.AwsProtocol
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.awsHttpClient
import com.steamstreet.awskt.core.callJson
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.core.resolveRegion
import io.ktor.client.HttpClient

/**
 * Kinesis's AWS-JSON 1.1 dialect.
 *
 * The target prefix is `Kinesis_20131202` — the API date, which is how Kinesis spells it — and the
 * endpoint prefix is `kinesis`. Note the dialect is **1.1**, not the 1.0 that DynamoDB and SQS use;
 * getting it wrong is a 400 on every call.
 */
public val KINESIS_PROTOCOL: AwsProtocol =
    AwsProtocol.awsJson1_1(endpointPrefix = "kinesis", targetPrefix = "Kinesis_20131202")

/**
 * A Kinesis Data Streams client covering the **data plane**: the operations that move records.
 *
 * Put, read, and enumerate shards. Stream lifecycle — `CreateStream`, `DeleteStream`,
 * `UpdateShardCount`, the retention and tagging calls — is out of scope, as it is in every other
 * `aws-*` module here: streams are provisioned by whatever manages the infrastructure. All of them
 * remain reachable through the extension seam below.
 *
 * ### Why this module exists
 *
 * `:lambda:lambda-dynamo-streams` needs `GetShardIterator` and `GetRecords` to resolve a DLQ
 * redrive — an SQS message that names a shard and a sequence range instead of carrying the payload.
 * That path was JVM-only because the only Kinesis client available was the AWS SDK's. It is the
 * reason those two operations are here and the reason this is a `basic` client rather than a
 * complete one.
 *
 * ### Three things worth knowing before you use it
 *
 * 1. **`PutRecords` reports failures inside an HTTP 200.** See [PutRecordsResponse]. Use
 *    [putRecordsAll] unless you are handling `FailedRecordCount` yourself.
 * 2. **An empty `GetRecords` page does not mean the shard is done.** Only a null
 *    `NextShardIterator` means that — see [GetRecordsResponse].
 * 3. **Shard iterators expire after 5 minutes**, timed from when they were issued. See
 *    [ExpiredIteratorException].
 *
 * ### What is deliberately absent
 *
 * `SubscribeToShard` — enhanced fan-out — is not here and is not a small addition. It is an
 * HTTP/2 response stream carrying `application/vnd.amazon.eventstream` frames, the same binary
 * framing `aws-bedrock-runtime` decodes for `ConverseStream`, and it needs the streaming call path
 * rather than `callJson`. Polling with [getRecords] is what this client offers.
 *
 * ### Extending it
 *
 * [client] is public and no operation below has privileged access to it — each is a `callJson` on
 * that same object:
 *
 * ```kotlin
 * @Serializable
 * data class DescribeStreamSummaryRequest(@SerialName("StreamARN") val streamArn: String)
 *
 * suspend fun Kinesis.describeStreamSummary(
 *     request: DescribeStreamSummaryRequest,
 * ): DescribeStreamSummaryResponse =
 *     client.callJson(
 *         "DescribeStreamSummary",
 *         request,
 *         DescribeStreamSummaryRequest.serializer(),
 *         DescribeStreamSummaryResponse.serializer(),
 *     )
 * ```
 */
public interface Kinesis : AutoCloseable {
    /** The signed transport. Public because it is the extension seam — see the interface KDoc. */
    public val client: AwsServiceClient

    /**
     * Writes one record.
     *
     * `NOT_IDEMPOTENT`, and unconditionally so: Kinesis has no de-duplication token of any kind.
     * If the bytes reached the service and the socket died before the response came back, a replay
     * appends the record a **second time** with a second sequence number, and every consumer sees
     * both. This is a real difference from SQS, where a `MessageDeduplicationId` makes the same
     * situation safe — there is no equivalent here, so an ambiguous failure is surfaced rather than
     * retried.
     *
     * De-duplication in a Kinesis pipeline belongs in the consumer, keyed on something in the
     * payload.
     */
    public suspend fun putRecord(request: PutRecordRequest): PutRecordResponse

    /**
     * Writes up to 500 records in one call.
     *
     * **Read [PutRecordsResponse] before using this directly**: throttled and failed records are
     * reported per-entry inside an HTTP 200, where the retry layer never sees them. [putRecordsAll]
     * chunks, resubmits and raises instead.
     */
    public suspend fun putRecords(request: PutRecordsRequest): PutRecordsResponse

    /**
     * Gets an iterator positioned in a shard.
     *
     * `IDEMPOTENT` — it reads position metadata and changes nothing. Note the returned iterator
     * expires 5 minutes from **now**, not from first use.
     */
    public suspend fun getShardIterator(request: GetShardIteratorRequest): GetShardIteratorResponse

    /**
     * Reads a page of records.
     *
     * `IDEMPOTENT` in the sense the retry layer cares about — replaying the *same iterator* returns
     * the same records, because an iterator names a fixed position. It is the `NextShardIterator`
     * in the response that advances, so a retried call after an ambiguous failure re-reads rather
     * than skips.
     *
     * Counts against the shard's 5-reads-per-second limit whether or not it returns anything.
     */
    public suspend fun getRecords(request: GetRecordsRequest): GetRecordsResponse

    /** Lists a stream's shards. `IDEMPOTENT`. Paginate with [ListShardsResponse.nextToken]. */
    public suspend fun listShards(request: ListShardsRequest): ListShardsResponse
}

/** Configuration for [Kinesis]. */
public class KinesisConfig {
    public var region: String? = null
    public var endpointUrl: String? = null
    public var credentialsProvider: AwsCredentialsProvider? = null

    /**
     * A client to send on, instead of one built here.
     *
     * Supplying one **bypasses [caInfo] and [httpTimeouts]**: those are arguments to the client this
     * factory would have built, and a client the caller already owns is configured by the caller.
     */
    public var httpClient: HttpClient? = null
    public var retryConfig: RetryConfig = RetryConfig()

    /** CA bundle for the native Curl engine. Null uses the system trust store. */
    public var caInfo: String? = null

    /**
     * Per-attempt time limits.
     *
     * The library default, unlike `aws-sqs`, which raises it for long polling. Kinesis has no
     * equivalent: [Kinesis.getRecords] returns immediately whether or not there is data, so a
     * consumer polls on its own schedule rather than asking the service to hold the connection.
     * The one operation that *would* need a longer timeout — `SubscribeToShard` — is not in this
     * client.
     */
    public var httpTimeouts: AwsHttpTimeouts = AwsHttpTimeouts()

    /**
     * Notified of every attempt, retry decision and give-up. Null means no instrumentation.
     *
     * Worth wiring up here: `PutRecord` is unconditionally `NOT_IDEMPOTENT`, so an ambiguous
     * transport failure is surfaced rather than replayed, and the observer is how you find out how
     * often that happens.
     */
    public var observer: AwsCallObserver? = null
}

/** Builds a Kinesis client. */
public fun Kinesis(configure: KinesisConfig.() -> Unit = {}): Kinesis {
    val config = KinesisConfig().apply(configure)
    val region = resolveRegion(config.region)
    // One binding for the effective client, handed to both the transport and the close path — see
    // the same note in `DynamoDb()`, where splitting the two leaked the client we had just built.
    val httpClient = config.httpClient ?: awsHttpClient(config.caInfo, config.httpTimeouts)
    return DefaultKinesis(
        client = AwsServiceClient(
            httpClient = httpClient,
            credentialsProvider = config.credentialsProvider ?: defaultCredentialsProvider(),
            endpoint = resolveEndpoint("kinesis", region, config.endpointUrl),
            region = region,
            protocol = KINESIS_PROTOCOL,
            retryConfig = config.retryConfig,
            observer = config.observer,
        ),
        ownsHttpClient = config.httpClient == null,
        httpClient = httpClient,
    )
}

internal class DefaultKinesis(
    override val client: AwsServiceClient,
    private val ownsHttpClient: Boolean = false,
    private val httpClient: HttpClient? = null,
) : Kinesis {

    override suspend fun putRecord(request: PutRecordRequest): PutRecordResponse = mapErrors {
        client.callJson(
            "PutRecord", request, PutRecordRequest.serializer(), PutRecordResponse.serializer(),
            // Unconditional: Kinesis has no de-duplication token, so a replay always appends again.
            safety = OperationSafety.NOT_IDEMPOTENT,
        )
    }

    override suspend fun putRecords(request: PutRecordsRequest): PutRecordsResponse = mapErrors {
        client.callJson(
            "PutRecords", request, PutRecordsRequest.serializer(), PutRecordsResponse.serializer(),
            safety = OperationSafety.NOT_IDEMPOTENT,
        )
    }

    override suspend fun getShardIterator(
        request: GetShardIteratorRequest,
    ): GetShardIteratorResponse = mapErrors {
        client.callJson(
            "GetShardIterator", request, GetShardIteratorRequest.serializer(),
            GetShardIteratorResponse.serializer(), safety = OperationSafety.IDEMPOTENT,
        )
    }

    override suspend fun getRecords(request: GetRecordsRequest): GetRecordsResponse = mapErrors {
        client.callJson(
            "GetRecords", request, GetRecordsRequest.serializer(), GetRecordsResponse.serializer(),
            safety = OperationSafety.IDEMPOTENT,
        )
    }

    override suspend fun listShards(request: ListShardsRequest): ListShardsResponse = mapErrors {
        client.callJson(
            "ListShards", request, ListShardsRequest.serializer(), ListShardsResponse.serializer(),
            safety = OperationSafety.IDEMPOTENT,
        )
    }

    override fun close() {
        if (ownsHttpClient) httpClient?.close()
    }
}

// -- Conveniences --------------------------------------------------------------------------------

/** Writes a string payload to a stream, encoded as UTF-8. */
public suspend fun Kinesis.putRecord(
    streamArn: String,
    partitionKey: String,
    data: String,
): PutRecordResponse = putRecord(
    PutRecordRequest(
        data = data.encodeToByteArray(),
        partitionKey = partitionKey,
        streamArn = streamArn,
    ),
)

/**
 * Writes every entry, chunking to the 500-record limit and resubmitting per-entry failures.
 *
 * This is the operation most callers want instead of [Kinesis.putRecords]. It exists because
 * Kinesis reports throttled records inside an HTTP 200, so the transport's retry layer — which only
 * sees status codes — cannot help: a producer that ignores `FailedRecordCount` loses records
 * silently under exactly the load that makes throttling likely.
 *
 * Failed entries are resubmitted up to [maxAttempts] times. Entries that still fail are returned
 * rather than thrown, so the caller can decide: an empty list means everything was written.
 *
 * **Ordering is not preserved across a retry.** A resubmitted record is appended when it succeeds,
 * so it lands after records that were accepted on the first attempt. If you need strict per-key
 * ordering, write with [Kinesis.putRecord] and its `sequenceNumberForOrdering`, one record at a
 * time.
 *
 * No delay is inserted between attempts, because the caller's own pacing is what determines whether
 * a retry is likely to succeed; wrap it if you want backoff.
 */
public suspend fun Kinesis.putRecordsAll(
    entries: List<PutRecordsRequestEntry>,
    streamArn: String? = null,
    streamName: String? = null,
    maxAttempts: Int = 3,
): List<PutRecordsResultEntry> {
    require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    if (entries.isEmpty()) return emptyList()

    val stillFailing = mutableListOf<PutRecordsResultEntry>()

    entries.chunked(MAX_PUT_RECORDS_BATCH).forEach { chunk ->
        var pending = chunk
        var attempt = 0

        while (pending.isNotEmpty()) {
            attempt++
            val response = putRecords(
                PutRecordsRequest(records = pending, streamArn = streamArn, streamName = streamName),
            )

            if (response.failedRecordCount == 0) break

            // Entries are matched to the request positionally — that is the only correlation the
            // API offers, so the response list is required to line up with what was sent.
            check(response.records.size == pending.size) {
                "PutRecords returned ${response.records.size} results for ${pending.size} entries"
            }

            val retryable = pending.filterIndexed { index, _ -> response.records[index].failed }

            if (attempt >= maxAttempts) {
                stillFailing.addAll(response.records.filter { it.failed })
                break
            }
            pending = retryable
        }
    }

    return stillFailing
}

/** Kinesis's per-call cap on `PutRecords` entries. */
private const val MAX_PUT_RECORDS_BATCH = 500

/**
 * Reads from [shardIterator] until the shard is exhausted or [limit] records have been collected.
 *
 * Stops when `NextShardIterator` comes back null (the shard is closed and drained) or when an empty
 * page arrives, whichever is first. **The empty-page stop is a deliberate simplification for
 * finite reads** — draining a batch, resolving a redrive, reading a closed shard — and it is
 * exactly the wrong behaviour for a live tail, where an empty page is routine and the consumer
 * should keep polling. Drive [Kinesis.getRecords] yourself for that.
 */
public suspend fun Kinesis.readRecords(
    shardIterator: String,
    limit: Int? = null,
    streamArn: String? = null,
): List<Record> {
    val collected = mutableListOf<Record>()
    var iterator: String? = shardIterator

    while (iterator != null) {
        val response = getRecords(
            GetRecordsRequest(shardIterator = iterator, limit = limit, streamArn = streamArn),
        )
        if (response.records.isEmpty()) break

        collected.addAll(response.records)
        if (limit != null && collected.size >= limit) return collected.take(limit)

        iterator = response.nextShardIterator
    }

    return collected
}
