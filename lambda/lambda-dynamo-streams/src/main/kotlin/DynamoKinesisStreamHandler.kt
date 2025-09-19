package com.steamstreet.aws.lambda

import com.steamstreet.aws.lambda.kinesis.BatchItemFailure
import com.steamstreet.aws.lambda.kinesis.BatchItemFailuresResponse
import com.steamstreet.aws.lambda.kinesis.KinesisRecords
import com.steamstreet.dynamokt.DynamoStreamEvent
import com.steamstreet.dynamokt.DynamoStreamRecords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json

/**
 * Handles changes to the Content Services table that are published as Kinesis records.
 */
public open class DynamoKinesisStreamHandler(
    /**
     * If true, the handleRecord method is called asynchronously for each item.
     */
    private val async: Boolean,
    /**
     * If true, tracks failed records and returns BatchItemFailures response.
     * When false, behaves as InputLambda with no response.
     */
    public val enableBatchItemFailures: Boolean = false
) : IOLambda<KinesisRecords, BatchItemFailuresResponse>(
    KinesisRecords.serializer(),
    BatchItemFailuresResponse.serializer()
), DynamoEventHandler {
    private val jsonDecode = Json {
        ignoreUnknownKeys = true
    }

    override suspend fun handle(input: KinesisRecords): BatchItemFailuresResponse {
        val dynamoRecords = input.Records.map {
            val decodedData = it.kinesis.decodedData()
            jsonDecode.decodeFromString<DynamoStreamEvent>(decodedData)
        }
        val dynamoStreamRecords = DynamoStreamRecords(dynamoRecords)

        val results = handle(dynamoStreamRecords)

        // Check that results list size matches input
        check(results.size == input.Records.size) {
            "Results list size (${results.size}) must match input records size (${input.Records.size})"
        }

        // Collect failed records
        val failedRecords = input.Records.filterIndexed { index, _ ->
            results[index] != null
        }.map { kinesisRecord ->
            BatchItemFailure(itemIdentifier = kinesisRecord.kinesis.sequenceNumber)
        }

        // If batch failures are disabled and we have failures, throw the first exception
        if (!enableBatchItemFailures && failedRecords.isNotEmpty()) {
            val firstFailure = results.find { it != null }
            throw firstFailure ?: IllegalStateException("Processing failed but batch item failures are not enabled")
        }

        return BatchItemFailuresResponse(batchItemFailures = failedRecords)
    }

    /**
     * Handle a batch of records. Default implementation calls handleRecord for each.
     * Returns a list of exceptions corresponding to each record (null for success, Exception for failure).
     */
    protected open suspend fun handle(input: DynamoStreamRecords): List<Exception?> {
        return if (async) {
            coroutineScope {
                input.records.map { record ->
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
            input.records.map { record ->
                try {
                    handleRecord(record)
                    null
                } catch (e: Exception) {
                    e
                }
            }
        }
    }

    override suspend fun handleRecord(record: DynamoStreamEvent) {}
}