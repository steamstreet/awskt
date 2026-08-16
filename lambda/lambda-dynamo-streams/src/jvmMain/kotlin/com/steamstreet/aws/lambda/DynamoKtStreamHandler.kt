package com.steamstreet.aws.lambda

import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kinesis.getRecords
import aws.sdk.kotlin.services.kinesis.getShardIterator
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType
import com.steamstreet.aws.lambda.kinesis.BatchItemFailuresResponse
import com.steamstreet.aws.lambda.kinesis.KinesisBatchInfo
import com.steamstreet.awskt.logging.`is`
import com.steamstreet.awskt.logging.log
import com.steamstreet.awskt.logging.logWarning
import com.steamstreet.dynamokt.DynamoKt
import com.steamstreet.dynamokt.DynamoKtSession
import com.steamstreet.dynamokt.DynamoStreamEvent
import com.steamstreet.dynamokt.Item
import com.steamstreet.dynamokt.oldAndNew
import kotlinx.serialization.json.JsonElement
import net.logstash.logback.marker.Markers

/**
 * DynamoKt specific stream handler. Takes care of session handling, and works for both
 * Kinesis or direct lambda streams.
 *
 * Record parsing and dispatch are now the common [parseDynamoStreamPayload] and
 * [batchFailureResponse]; what stays here is the DLQ redrive, which needs the AWS SDK's
 * `KinesisClient` and so cannot leave the JVM. A native stream handler gets everything except that
 * path — see `dynamoKtStreamLambda`.
 *
 * `logFailures` now takes [StreamFailureLogLevel] rather than `java.util.logging.Level`; the entry
 * names are the same three, so only the import changes.
 */
public abstract class DynamoKtStreamHandler(
    dynamoKt: DynamoKt,
    private val async: Boolean,
    /**
     * If true, tracks failed records and returns BatchItemFailures response.
     * When false, throws exceptions immediately.
     */
    public val enableBatchItemFailures: Boolean = false,
    /**
     * If you want to be able to handle DLQ redrives, you'll need to provide a KinesisClient.
     */
    private val kinesis: KinesisClient? = null
) : IOLambda<JsonElement, BatchItemFailuresResponse>(
    JsonElement.serializer(),
    BatchItemFailuresResponse.serializer()
) {
    /**
     * Get the session to be used. Can be the same session for all. If null is returned,
     * the onItemUpdate function will NOT be called, and implementations should instead
     * override handleRecord.
     */
    protected val dynamoKtSession: DynamoKtSession = dynamoKt.session()

    public var logFailures: StreamFailureLogLevel? = StreamFailureLogLevel.WARNING

    override var logIncoming: Boolean = false
    override var logOutgoing: Boolean = false

    public var logRecords: Boolean = false

    override suspend fun handle(input: JsonElement): BatchItemFailuresResponse {
        val recordInfos = parseDynamoStreamPayload(
            input,
            logRecord = { logRecord(it) },
            onKinesisBatch = { processKinesisBatchRecord(it) }
        )

        val results = handleRecords(recordInfos.map { it.dynamoEvent })
        return recordInfos.batchFailureResponse(results, enableBatchItemFailures, logFailures)
    }

    /**
     * A kinesis batch record doesn't have the full payload, just a reference to the stream and the sequence
     * number. So we'll need to use the kinesis API to get the full payload, and then return it for processing.
     * Returns a list of RecordInfo for each record in the batch.
     */
    protected open suspend fun processKinesisBatchRecord(batchInfo: KinesisBatchInfo): List<RecordInfo> {
        if (kinesis == null) {
            logWarning("KinesisClient not provided, cannot process batch record")
            return emptyList()
        }

        return log.ctx({
            "sequenceRange" `is` "${batchInfo.startSequenceNumber}..${batchInfo.endSequenceNumber}"
            "shardId" `is` batchInfo.shardId
        }) {
            try {
                // Get shard iterator starting at the sequence number
                val shardIterator = kinesis.getShardIterator {
                    streamArn = batchInfo.streamArn
                    shardId = batchInfo.shardId
                    shardIteratorType = ShardIteratorType.AtSequenceNumber
                    startingSequenceNumber = batchInfo.startSequenceNumber
                }

                // Get records from Kinesis
                val response = kinesis.getRecords {
                    this.shardIterator = shardIterator.shardIterator
                    limit = batchInfo.batchSize
                }

                // Find all records that match our sequence range
                val matchingRecords = response.records.filter { record ->
                    val seqNum = record.sequenceNumber
                    seqNum >= batchInfo.startSequenceNumber && seqNum <= batchInfo.endSequenceNumber
                }

                if (matchingRecords.isEmpty()) {
                    throw StreamHandlingException("No matching records found in Kinesis batch")
                }

                // Decode all matching records
                matchingRecords.map { record ->
                    val decodedData = record.data.decodeToString()
                    logRecord { decodedData }
                    val dynamoEvent = streamJson.decodeFromString<DynamoStreamEvent>(decodedData)
                    RecordInfo(dynamoEvent, record.sequenceNumber)
                }
            } catch (e: Exception) {
                throw StreamHandlingException("Failed to process Kinesis batch record", e)
            }
        }
    }

    /**
     * Log an incoming record.
     */
    protected open fun logRecord(record: () -> String) {
        if (logRecords) {
            logger.info(Markers.appendRaw("input", record()), "Record received")
        }
    }

    /**
     * Handle the records in this request.
     * Returns a list of exceptions corresponding to each record (null for success, Exception for failure).
     */
    protected open suspend fun handleRecords(dynamoRecords: List<DynamoStreamEvent>): List<Exception?> =
        dynamoRecords.processStreamRecords(async) { record ->
            handleRecord(record)
        }

    /**
     * Default implementation calls this for each record. Parses the event to old
     * and new Items and calls onItemUpdate.
     */
    protected open suspend fun handleRecord(record: DynamoStreamEvent) {
        val (old, new) = record.oldAndNew(dynamoKtSession)
        onItemUpdate(old, new, record)
    }

    /**
     * A database item has been updated.
     */
    protected open suspend fun onItemUpdate(old: Item?, new: Item?, record: DynamoStreamEvent) {}
}
