package com.steamstreet.aws.lambda

import com.steamstreet.aws.lambda.kinesis.BatchItemFailure
import com.steamstreet.aws.lambda.kinesis.BatchItemFailuresResponse
import com.steamstreet.dynamokt.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import net.logstash.logback.marker.Markers
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Represents a record that could be from either Kinesis or direct DynamoDB stream
 */
private data class RecordInfo(
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
    public val enableBatchItemFailures: Boolean = false
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

    override var logIncoming: Boolean = false
    override var logOutgoing: Boolean = false

    public var logRecords: Boolean = false

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun handle(input: JsonElement): BatchItemFailuresResponse {
        val records = input.jsonObject["Records"]?.jsonArray

        // Parse records and extract identifiers for both Kinesis and DynamoDB records
        val recordInfos = records?.mapNotNull { recordElement ->
            val kinesis = recordElement.jsonObject["kinesis"]
            if (kinesis != null) {
                // Kinesis record - use sequenceNumber as identifier
                val dataString = kinesis.jsonObject["data"]?.jsonPrimitive?.contentOrNull
                val sequenceNumber = kinesis.jsonObject["sequenceNumber"]?.jsonPrimitive?.contentOrNull
                if (dataString != null && sequenceNumber != null) {
                    val decodedData = String(Base64.decode(dataString))
                    if (logRecords) {
                        logger.info(Markers.appendRaw("input", decodedData), "Record received")
                    }
                    val dynamoEvent = jsonDecode.decodeFromString<DynamoStreamEvent>(decodedData)
                    RecordInfo(dynamoEvent, sequenceNumber)
                } else {
                    null
                }
            } else {
                // Direct DynamoDB stream record - use eventID as identifier
                val eventID = recordElement.jsonObject["eventID"]?.jsonPrimitive?.contentOrNull
                if (eventID != null) {
                    if (logRecords) {
                        logger.info(Markers.appendRaw("input", recordElement.toString()), "Record received")
                    }
                    val dynamoEvent = jsonDecode.decodeFromJsonElement<DynamoStreamEvent>(recordElement)
                    RecordInfo(dynamoEvent, eventID)
                } else {
                    null
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

        return BatchItemFailuresResponse(batchItemFailures = failedRecords)
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