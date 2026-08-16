package com.steamstreet.aws.lambda

import com.steamstreet.aws.lambda.kinesis.KinesisBatchInfo
import com.steamstreet.awskt.kinesis.GetRecordsRequest
import com.steamstreet.awskt.kinesis.GetShardIteratorRequest
import com.steamstreet.awskt.kinesis.Kinesis
import com.steamstreet.awskt.kinesis.ShardIteratorType
import com.steamstreet.dynamokt.DynamoStreamEvent

/**
 * Resolves a DLQ redrive message back into the records it names.
 *
 * When Kinesis fails to deliver a batch, the message that lands on the failure-destination queue
 * carries a [KinesisBatchInfo] — a shard id and a sequence range — rather than the payload itself.
 * Reprocessing it means going back to Kinesis for the records in that range, which is what this
 * does.
 *
 * Common, and therefore available on Kotlin/Native, because it is written against
 * `:aws:aws-kinesis` rather than the JVM-only AWS SDK. That is the whole reason that module exists:
 * before it, this path could only be implemented on the JVM, and a native stream handler had to
 * drop redriven records.
 *
 * Pass it to [parseDynamoStreamPayload] or to `dynamoStreamBatchLambda`:
 *
 * ```kotlin
 * fun main() {
 *     val kinesis = Kinesis()
 *     dynamoStreamBatchLambda(onKinesisBatch = { kinesis.resolveRedrive(it) }) { record ->
 *         handle(record)
 *     }
 * }
 * ```
 *
 * @throws StreamHandlingException if the range cannot be read, or resolves to no records at all —
 *   the latter means the records aged out of the stream's retention window before the redrive was
 *   processed, which is data loss worth failing loudly on rather than silently returning nothing.
 */
public suspend fun Kinesis.resolveRedrive(batchInfo: KinesisBatchInfo): List<RecordInfo> {
    val iterator = try {
        getShardIterator(
            GetShardIteratorRequest(
                shardId = batchInfo.shardId,
                shardIteratorType = ShardIteratorType.AT_SEQUENCE_NUMBER,
                streamArn = batchInfo.streamArn,
                startingSequenceNumber = batchInfo.startSequenceNumber,
            ),
        ).shardIterator ?: throw StreamHandlingException(
            "Kinesis returned no shard iterator for shard ${batchInfo.shardId}",
        )
    } catch (e: StreamHandlingException) {
        throw e
    } catch (e: Exception) {
        throw StreamHandlingException("Failed to get shard iterator for Kinesis batch record", e)
    }

    val records = try {
        getRecords(
            GetRecordsRequest(
                shardIterator = iterator,
                streamArn = batchInfo.streamArn,
                limit = batchInfo.batchSize,
            ),
        ).records
    } catch (e: Exception) {
        throw StreamHandlingException("Failed to process Kinesis batch record", e)
    }

    // The iterator starts *at* the range's first sequence number but the page can run past its end,
    // so the tail has to be trimmed. String comparison is what the previous SDK-based implementation
    // used and is correct here only because Kinesis sequence numbers within one shard are
    // fixed-width decimal strings, which order the same lexicographically as numerically.
    val matching = records.filter {
        it.sequenceNumber >= batchInfo.startSequenceNumber &&
            it.sequenceNumber <= batchInfo.endSequenceNumber
    }

    if (matching.isEmpty()) {
        throw StreamHandlingException("No matching records found in Kinesis batch")
    }

    return matching.map { record ->
        RecordInfo(
            streamJson.decodeFromString<DynamoStreamEvent>(record.data.decodeToString()),
            record.sequenceNumber,
        )
    }
}
