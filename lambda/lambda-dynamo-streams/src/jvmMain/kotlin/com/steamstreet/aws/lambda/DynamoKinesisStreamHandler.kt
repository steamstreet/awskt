package com.steamstreet.aws.lambda

import com.steamstreet.aws.lambda.kinesis.BatchItemFailuresResponse
import com.steamstreet.aws.lambda.kinesis.KinesisRecords
import com.steamstreet.dynamokt.DynamoStreamEvent
import com.steamstreet.dynamokt.DynamoStreamRecords

/**
 * Handles changes to a DynamoDB table that are published as Kinesis records.
 *
 * The dispatch and failure collection are the common [processWithFailures]; what is left here is the
 * Kinesis-to-DynamoDB record mapping and the [IOLambda] wiring.
 *
 * `logFailures` now takes [StreamFailureLogLevel] instead of `java.util.logging.Level`, which has no
 * Kotlin/Native equivalent. The entry names are unchanged, so an assignment like
 * `logFailures = Level.SEVERE` needs a different import and nothing else.
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
    public var logFailures: StreamFailureLogLevel? = StreamFailureLogLevel.WARNING

    override suspend fun handle(input: KinesisRecords): BatchItemFailuresResponse {
        val recordInfos = input.Records.map {
            RecordInfo(
                streamJson.decodeFromString<DynamoStreamEvent>(it.kinesis.decodedData()),
                it.kinesis.sequenceNumber
            )
        }

        val results = handle(DynamoStreamRecords(recordInfos.map { it.dynamoEvent }))
        return recordInfos.batchFailureResponse(results, enableBatchItemFailures, logFailures)
    }

    /**
     * Handle a batch of records. Default implementation calls handleRecord for each.
     * Returns a list of exceptions corresponding to each record (null for success, Exception for failure).
     */
    protected open suspend fun handle(input: DynamoStreamRecords): List<Exception?> =
        input.processStreamRecords(async) { record ->
            handleRecord(record)
        }

    override suspend fun handleRecord(record: DynamoStreamEvent) {}
}
