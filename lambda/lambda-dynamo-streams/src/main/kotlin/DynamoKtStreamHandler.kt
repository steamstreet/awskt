package com.steamstreet.aws.lambda

import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kinesis.getRecords
import aws.sdk.kotlin.services.kinesis.getShardIterator
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType
import com.steamstreet.aws.lambda.kinesis.BatchItemFailure
import com.steamstreet.aws.lambda.kinesis.BatchItemFailuresResponse
import com.steamstreet.aws.lambda.kinesis.KinesisBatchInfo
import com.steamstreet.awskt.logging.logError
import com.steamstreet.awskt.logging.logInfo
import com.steamstreet.awskt.logging.logWarning
import com.steamstreet.awskt.logging.mdcContext
import com.steamstreet.dynamokt.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import net.logstash.logback.marker.Markers
import java.util.logging.Level
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Represents a record that could be from either Kinesis or direct DynamoDB stream
 */
public data class RecordInfo(
    val dynamoEvent: DynamoStreamEvent,
    val identifier: String // either sequenceNumber (Kinesis) or eventID (DynamoDB)
)

/**
 * DynamoKt specific stream handler. Takes care of session handling, and works for both
 * Kinesis or direct lambda streams.
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

    private val jsonDecode: Json = Json {
        ignoreUnknownKeys = true
    }
    public var logFailures: Level? = Level.WARNING

    override var logIncoming: Boolean = false
    override var logOutgoing: Boolean = false

    public var logRecords: Boolean = false

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun handle(input: JsonElement): BatchItemFailuresResponse {
        val records = input.jsonObject["Records"]?.jsonArray

        // Parse records and extract identifiers for both Kinesis and DynamoDB records
        val recordInfos = records?.flatMap { recordElement ->
            val kinesis = recordElement.jsonObject["kinesis"]
            val eventSource = recordElement.jsonObject["eventSource"]?.jsonPrimitive?.contentOrNull
            if (kinesis != null) {
                // Kinesis record - use sequenceNumber as identifier
                val dataString = kinesis.jsonObject["data"]?.jsonPrimitive?.contentOrNull
                val sequenceNumber = kinesis.jsonObject["sequenceNumber"]?.jsonPrimitive?.contentOrNull
                if (dataString != null && sequenceNumber != null) {
                    val decodedData = String(Base64.decode(dataString))
                    logRecord({ decodedData })
                    val dynamoEvent = jsonDecode.decodeFromString<DynamoStreamEvent>(decodedData)
                    listOf(RecordInfo(dynamoEvent, sequenceNumber))
                } else {
                    emptyList()
                }
            } else if (eventSource == "aws:sqs") {
                val bodyString = recordElement.jsonObject["body"]?.jsonPrimitive?.contentOrNull
                if (bodyString != null) {
                    val bodyJson = jsonDecode.parseToJsonElement(bodyString).jsonObject
                    // this is caused by a failure in the Kinesis stream, and the record has been
                    // placed on an SQS queue. Fetch all records from the batch.
                    val batchInfo = jsonDecode.decodeFromJsonElement<KinesisBatchInfo>(bodyJson["KinesisBatchInfo"]!!)
                    processKinesisBatchRecord(batchInfo)
                } else {
                    emptyList()
                }
            } else {
                // Direct DynamoDB stream record - use eventID as identifier
                val eventID = recordElement.jsonObject["eventID"]?.jsonPrimitive?.contentOrNull
                if (eventID != null) {
                    logRecord({ recordElement.toString() })
                    val dynamoEvent = jsonDecode.decodeFromJsonElement<DynamoStreamEvent>(recordElement)
                    listOf(RecordInfo(dynamoEvent, eventID))
                } else {
                    emptyList()
                }
            }
        }.orEmpty()

        val dynamoRecords = recordInfos.map { it.dynamoEvent }
        val results = handleRecords(dynamoRecords)

        // Check that results list size matches input
        check(results.size == recordInfos.size) {
            "Results list size (${results.size}) must match input records size (${recordInfos.size})"
        }

        // Collect failed records
        val failedRecords = recordInfos.filterIndexed { index, _ ->
            results[index] != null
        }.map { recordInfo ->
            BatchItemFailure(itemIdentifier = recordInfo.identifier)
        }

        // If batch failures are disabled and we have failures, throw the first exception
        if (!enableBatchItemFailures && failedRecords.isNotEmpty()) {
            val firstFailure = results.find { it != null }
            throw firstFailure ?: IllegalStateException("Processing failed but batch item failures are not enabled")
        }

        if (logFailures != null && failedRecords.isNotEmpty()) {
            results.forEachIndexed { index, result ->
                val record = recordInfos[index]
                if (result != null) {
                    val message = "Dynamo record processing failed"
                    val metadata = "itemIdentifier" to record.identifier
                    when (logFailures) {
                        Level.WARNING -> logWarning(message, result, metadata)
                        Level.INFO -> logInfo(message, metadata)
                        Level.SEVERE -> logError(message, result, metadata)
                    }
                }
            }
        }

        return BatchItemFailuresResponse(batchItemFailures = failedRecords)
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

        return mdcContext(
            "sequenceRange" to "${batchInfo.startSequenceNumber}..${batchInfo.endSequenceNumber}",
            "shardId" to batchInfo.shardId
        ) {
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
                    val dynamoEvent = jsonDecode.decodeFromString<DynamoStreamEvent>(decodedData)
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
    protected open suspend fun handleRecords(dynamoRecords: List<DynamoStreamEvent>): List<Exception?> {
        return if (async) {
            coroutineScope {
                dynamoRecords.map { record ->
                    async(Dispatchers.IO) {
                        try {
                            handleRecord(record)
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            e
                        }
                    }
                }.awaitAll()
            }
        } else {
            dynamoRecords.map { record ->
                try {
                    handleRecord(record)
                    null
                } catch (e: Exception) {
                    e
                }
            }
        }
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

public class StreamHandlingException(message: String, cause: Throwable? = null) :
    Exception(message, cause)