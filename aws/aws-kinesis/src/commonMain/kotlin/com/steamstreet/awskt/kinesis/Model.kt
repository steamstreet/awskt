package com.steamstreet.awskt.kinesis

import com.steamstreet.awskt.core.Base64BlobSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Kinesis Data Streams wire model.
 *
 * ### A stream is named two ways and they are not interchangeable
 *
 * Every request below takes `StreamName` **or** `StreamARN`, both optional, and exactly one must be
 * supplied. The ARN form is the one to prefer and the only one that works cross-account — a bare
 * name resolves against the calling account, so a consumer reading a producer's stream in another
 * account with a name gets `ResourceNotFound` rather than an access error, which sends people
 * looking at IAM for a problem that is in the request.
 *
 * The builders here take whichever the caller has and do not guess. Passing neither is a
 * `ValidationException` from the service; passing both is accepted, with the ARN winning.
 *
 * ### `Data` is a blob, so it is base64 on the wire and bytes in Kotlin
 *
 * [PutRecordRequest.data] and [Record.data] are `ByteArray`, encoded through
 * [Base64BlobSerializer]. This is the one place where a hand-written client and the AWS SDK differ
 * visibly in ergonomics: the SDK hands you `ByteArray` too, but a `String` payload has to be
 * encoded and decoded explicitly here. `record.data.decodeToString()` is the counterpart of
 * `lambda-kinesis`'s `KinesisDetails.decodedData()`.
 */
@Serializable
public data class PutRecordRequest(
    @SerialName("Data") @Serializable(with = Base64BlobSerializer::class) val data: ByteArray,
    @SerialName("PartitionKey") val partitionKey: String,
    @SerialName("StreamName") val streamName: String? = null,
    @SerialName("StreamARN") val streamArn: String? = null,
    @SerialName("ExplicitHashKey") val explicitHashKey: String? = null,
    /**
     * Forces this record onto the same shard as the record with this sequence number, preserving
     * strict ordering across a shard split. Rarely needed; leave it null unless you know you need
     * it.
     */
    @SerialName("SequenceNumberForOrdering") val sequenceNumberForOrdering: String? = null,
) {
    // `data class` over a ByteArray gives reference equality via the generated methods, which is
    // wrong often enough to be worth overriding rather than leaving as a trap.
    override fun equals(other: Any?): Boolean = this === other || (
        other is PutRecordRequest &&
            data.contentEquals(other.data) &&
            partitionKey == other.partitionKey &&
            streamName == other.streamName &&
            streamArn == other.streamArn &&
            explicitHashKey == other.explicitHashKey &&
            sequenceNumberForOrdering == other.sequenceNumberForOrdering
        )

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + partitionKey.hashCode()
        result = 31 * result + (streamName?.hashCode() ?: 0)
        result = 31 * result + (streamArn?.hashCode() ?: 0)
        result = 31 * result + (explicitHashKey?.hashCode() ?: 0)
        result = 31 * result + (sequenceNumberForOrdering?.hashCode() ?: 0)
        return result
    }
}

@Serializable
public data class PutRecordResponse(
    @SerialName("ShardId") val shardId: String,
    @SerialName("SequenceNumber") val sequenceNumber: String,
    @SerialName("EncryptionType") val encryptionType: String? = null,
)

@Serializable
public data class PutRecordsRequestEntry(
    @SerialName("Data") @Serializable(with = Base64BlobSerializer::class) val data: ByteArray,
    @SerialName("PartitionKey") val partitionKey: String,
    @SerialName("ExplicitHashKey") val explicitHashKey: String? = null,
) {
    override fun equals(other: Any?): Boolean = this === other || (
        other is PutRecordsRequestEntry &&
            data.contentEquals(other.data) &&
            partitionKey == other.partitionKey &&
            explicitHashKey == other.explicitHashKey
        )

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + partitionKey.hashCode()
        result = 31 * result + (explicitHashKey?.hashCode() ?: 0)
        return result
    }
}

@Serializable
public data class PutRecordsRequest(
    @SerialName("Records") val records: List<PutRecordsRequestEntry>,
    @SerialName("StreamName") val streamName: String? = null,
    @SerialName("StreamARN") val streamArn: String? = null,
)

/**
 * The result of a `PutRecords` call.
 *
 * **Read [failedRecordCount] before treating an HTTP 200 as success.** `PutRecords` reports
 * per-record failures inside a successful response, exactly as SQS's batch operations do: a
 * throttled or failed record comes back with [PutRecordsResultEntry.errorCode] set while the call
 * itself succeeded. Ignoring it is silent data loss, and it is the single most common way to lose
 * records on a Kinesis producer.
 *
 * [Kinesis.putRecordsAll] handles the resubmission; prefer it to calling this directly.
 */
@Serializable
public data class PutRecordsResponse(
    @SerialName("Records") val records: List<PutRecordsResultEntry> = emptyList(),
    @SerialName("FailedRecordCount") val failedRecordCount: Int = 0,
    @SerialName("EncryptionType") val encryptionType: String? = null,
)

/**
 * One entry's outcome, positionally matched to the request's `Records` list.
 *
 * Either [shardId]/[sequenceNumber] are set (success) or [errorCode]/[errorMessage] are (failure).
 * `ProvisionedThroughputExceededException` is the retryable one and by far the most common.
 */
@Serializable
public data class PutRecordsResultEntry(
    @SerialName("ShardId") val shardId: String? = null,
    @SerialName("SequenceNumber") val sequenceNumber: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("ErrorMessage") val errorMessage: String? = null,
) {
    /** True when this entry failed and should be resubmitted. */
    public val failed: Boolean get() = errorCode != null
}

/**
 * Where in a shard to start reading.
 *
 * An enum rather than an open string because this is a **request-only** field — it is never
 * deserialized from a Kinesis response, so an unrecognised value added by AWS later cannot break
 * parsing. That is the reasoning that would push the other way on a response field.
 */
@Serializable
public enum class ShardIteratorType {
    /** Start at a specific sequence number. Requires [GetShardIteratorRequest.startingSequenceNumber]. */
    AT_SEQUENCE_NUMBER,

    /** Start immediately after a sequence number. Requires [GetShardIteratorRequest.startingSequenceNumber]. */
    AFTER_SEQUENCE_NUMBER,

    /** Start at a timestamp. Requires [GetShardIteratorRequest.timestamp]. */
    AT_TIMESTAMP,

    /** Start at the oldest record still in the shard's retention window. */
    TRIM_HORIZON,

    /** Start at the newest record — reads nothing already in the shard. */
    LATEST,
}

@Serializable
public data class GetShardIteratorRequest(
    @SerialName("ShardId") val shardId: String,
    @SerialName("ShardIteratorType") val shardIteratorType: ShardIteratorType,
    @SerialName("StreamName") val streamName: String? = null,
    @SerialName("StreamARN") val streamArn: String? = null,
    @SerialName("StartingSequenceNumber") val startingSequenceNumber: String? = null,
    /** Unix epoch seconds, fractional. Only read when the type is `AT_TIMESTAMP`. */
    @SerialName("Timestamp") val timestamp: Double? = null,
)

@Serializable
public data class GetShardIteratorResponse(
    @SerialName("ShardIterator") val shardIterator: String? = null,
)

@Serializable
public data class GetRecordsRequest(
    @SerialName("ShardIterator") val shardIterator: String,
    @SerialName("StreamARN") val streamArn: String? = null,
    /** Up to 10,000, and also bounded by a 10 MB response cap. Null lets the service choose. */
    @SerialName("Limit") val limit: Int? = null,
)

/**
 * A page of records, plus the iterator for the next page.
 *
 * ### An empty page does not mean the end of the shard
 *
 * [records] being empty is normal and expected on a shard with no new data; the caller keeps
 * reading with [nextShardIterator]. The shard is finished — closed and fully drained — only when
 * [nextShardIterator] is **null**, which is what a resharding operation eventually produces. A
 * consumer that stops on an empty page stops early and silently.
 *
 * [millisBehindLatest] is the lag indicator worth alarming on.
 */
@Serializable
public data class GetRecordsResponse(
    @SerialName("Records") val records: List<Record> = emptyList(),
    @SerialName("NextShardIterator") val nextShardIterator: String? = null,
    @SerialName("MillisBehindLatest") val millisBehindLatest: Long? = null,
)

@Serializable
public data class Record(
    @SerialName("SequenceNumber") val sequenceNumber: String,
    @SerialName("Data") @Serializable(with = Base64BlobSerializer::class) val data: ByteArray,
    @SerialName("PartitionKey") val partitionKey: String,
    /** Unix epoch seconds, fractional — the same shape `lambda-kinesis` reports. */
    @SerialName("ApproximateArrivalTimestamp") val approximateArrivalTimestamp: Double? = null,
    @SerialName("EncryptionType") val encryptionType: String? = null,
) {
    override fun equals(other: Any?): Boolean = this === other || (
        other is Record &&
            sequenceNumber == other.sequenceNumber &&
            data.contentEquals(other.data) &&
            partitionKey == other.partitionKey &&
            approximateArrivalTimestamp == other.approximateArrivalTimestamp &&
            encryptionType == other.encryptionType
        )

    override fun hashCode(): Int {
        var result = sequenceNumber.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + partitionKey.hashCode()
        result = 31 * result + (approximateArrivalTimestamp?.hashCode() ?: 0)
        result = 31 * result + (encryptionType?.hashCode() ?: 0)
        return result
    }
}

@Serializable
public data class ListShardsRequest(
    @SerialName("StreamName") val streamName: String? = null,
    @SerialName("StreamARN") val streamArn: String? = null,
    /**
     * Continues a previous listing. **Mutually exclusive with every other parameter** — a request
     * carrying this and a stream name is a `ValidationException`, which is a surprising rule and
     * the usual reason a paginating caller's second call fails.
     */
    @SerialName("NextToken") val nextToken: String? = null,
    @SerialName("MaxResults") val maxResults: Int? = null,
)

@Serializable
public data class ListShardsResponse(
    @SerialName("Shards") val shards: List<Shard> = emptyList(),
    @SerialName("NextToken") val nextToken: String? = null,
)

@Serializable
public data class Shard(
    @SerialName("ShardId") val shardId: String,
    @SerialName("SequenceNumberRange") val sequenceNumberRange: SequenceNumberRange,
    @SerialName("ParentShardId") val parentShardId: String? = null,
    @SerialName("AdjacentParentShardId") val adjacentParentShardId: String? = null,
    @SerialName("HashKeyRange") val hashKeyRange: HashKeyRange? = null,
)

/** A null [endingSequenceNumber] means the shard is still open and accepting writes. */
@Serializable
public data class SequenceNumberRange(
    @SerialName("StartingSequenceNumber") val startingSequenceNumber: String,
    @SerialName("EndingSequenceNumber") val endingSequenceNumber: String? = null,
)

@Serializable
public data class HashKeyRange(
    @SerialName("StartingHashKey") val startingHashKey: String,
    @SerialName("EndingHashKey") val endingHashKey: String,
)

/** For operations whose response body is `{}`. */
@Serializable
internal class EmptyResponse
