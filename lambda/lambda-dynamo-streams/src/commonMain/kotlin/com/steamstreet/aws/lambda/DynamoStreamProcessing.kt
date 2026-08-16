package com.steamstreet.aws.lambda

import com.steamstreet.aws.lambda.kinesis.BatchItemFailure
import com.steamstreet.aws.lambda.kinesis.BatchItemFailuresResponse
import com.steamstreet.aws.lambda.kinesis.KinesisBatchInfo
import com.steamstreet.awskt.logging.log
import com.steamstreet.dynamokt.DynamoStreamEvent
import com.steamstreet.dynamokt.DynamoStreamRecords
import com.steamstreet.exceptions.StatefulException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * DynamoDB stream record parsing and dispatch, in `commonMain` so a stream handler can be written
 * for Kotlin/Native.
 *
 * The interesting part is [parseDynamoStreamPayload]. A DynamoDB stream can reach a Lambda three
 * different ways and the handler is expected to cope with all of them from one entry point, which is
 * exactly the kind of logic that should not exist twice.
 */

/**
 * Represents a record that could be from either Kinesis or direct DynamoDB stream
 */
public data class RecordInfo(
    val dynamoEvent: DynamoStreamEvent,
    val identifier: String // either sequenceNumber (Kinesis) or eventID (DynamoDB)
)

/**
 * How a per-record failure should be logged.
 *
 * Replaces `java.util.logging.Level`, which has no Kotlin/Native equivalent and was an odd
 * dependency for this module in any case. The entry names are deliberately the same three that were
 * in use, so a consumer that wrote `logFailures = Level.SEVERE` changes an import and nothing else.
 */
public enum class StreamFailureLogLevel {
    INFO, WARNING, SEVERE
}

internal val streamJson: Json = Json {
    ignoreUnknownKeys = true
}

/**
 * Pull the DynamoDB stream records out of whatever envelope Lambda delivered them in.
 *
 * Three shapes arrive at the same handler and are told apart by inspecting each record:
 *  - a **Kinesis** record, whose Base64 `data` holds the stream event. The DynamoDB event inside
 *    carries no sequence number, so it is copied down from the Kinesis envelope — that is what makes
 *    partial batch failures reportable for this path.
 *  - an **SQS** record (`eventSource == "aws:sqs"`) holding a `KinesisBatchInfo`. This is the DLQ
 *    redrive path: the message names a shard and a sequence range rather than carrying the payload,
 *    so the records have to be fetched back from Kinesis. [onKinesisBatch] does that, and defaults
 *    to returning nothing — see the note on the parameter.
 *  - a **direct** DynamoDB stream record, identified by its `eventID`.
 *
 * @param onKinesisBatch fetches the records named by a DLQ redrive message. The default drops them,
 *   which is the only thing this module can do on a platform with no Kinesis client: the AWS SDK is
 *   JVM-only and there is no hand-written `aws-kinesis` client in this repository yet. The JVM
 *   `DynamoKtStreamHandler` passes its own implementation when a `KinesisClient` was supplied — and
 *   logs a warning when one was not, which is the pre-existing behaviour for that case.
 */
@OptIn(ExperimentalEncodingApi::class)
public suspend fun parseDynamoStreamPayload(
    input: JsonElement,
    logRecord: (() -> String) -> Unit = {},
    onKinesisBatch: suspend (KinesisBatchInfo) -> List<RecordInfo> = { emptyList() }
): List<RecordInfo> {
    val records = input.jsonObject["Records"]?.jsonArray ?: return emptyList()

    return records.flatMap { recordElement ->
        val kinesis = recordElement.jsonObject["kinesis"]
        val eventSource = recordElement.jsonObject["eventSource"]?.jsonPrimitive?.contentOrNull
        if (kinesis != null) {
            // Kinesis record - use sequenceNumber as identifier
            val dataString = kinesis.jsonObject["data"]?.jsonPrimitive?.contentOrNull
            val sequenceNumber = kinesis.jsonObject["sequenceNumber"]?.jsonPrimitive?.contentOrNull
            if (dataString != null && sequenceNumber != null) {
                val decodedData = Base64.decode(dataString).decodeToString()
                logRecord { decodedData }
                val dynamoEvent = streamJson.decodeFromString<DynamoStreamEvent>(decodedData)
                // The DynamoDB record in the Kinesis payload doesn't carry a sequence number,
                // so copy it from the Kinesis envelope.
                val withSequenceNumber = dynamoEvent.copy(
                    dynamodb = dynamoEvent.dynamodb.copy(sequenceNumber = sequenceNumber)
                )
                listOf(RecordInfo(withSequenceNumber, sequenceNumber))
            } else {
                emptyList()
            }
        } else if (eventSource == "aws:sqs") {
            val bodyString = recordElement.jsonObject["body"]?.jsonPrimitive?.contentOrNull
            if (bodyString != null) {
                val bodyJson = streamJson.parseToJsonElement(bodyString).jsonObject
                // this is caused by a failure in the Kinesis stream, and the record has been
                // placed on an SQS queue. Fetch all records from the batch.
                val batchInfo = streamJson.decodeFromJsonElement<KinesisBatchInfo>(bodyJson["KinesisBatchInfo"]!!)
                onKinesisBatch(batchInfo)
            } else {
                emptyList()
            }
        } else {
            // Direct DynamoDB stream record - use eventID as identifier
            val eventID = recordElement.jsonObject["eventID"]?.jsonPrimitive?.contentOrNull
            if (eventID != null) {
                logRecord { recordElement.toString() }
                val dynamoEvent = streamJson.decodeFromJsonElement<DynamoStreamEvent>(recordElement)
                listOf(RecordInfo(dynamoEvent, eventID))
            } else {
                emptyList()
            }
        }
    }
}

/**
 * Hand each record to [handler], returning the exception each one threw or null where it succeeded.
 *
 * `CancellationException` is rethrown rather than recorded, matching what `DynamoKtStreamHandler`
 * already did on its async path: a cancelled invocation is being torn down, and reporting its
 * records as individually failed would be wrong.
 */
public suspend fun List<DynamoStreamEvent>.processStreamRecords(
    async: Boolean = false,
    handler: suspend (DynamoStreamEvent) -> Unit
): List<Exception?> = if (async) {
    coroutineScope {
        map { record ->
            async(lambdaIODispatcher) { runRecordCatching(record, handler) }
        }.awaitAll()
    }
} else {
    map { record -> runRecordCatching(record, handler) }
}

private suspend fun runRecordCatching(
    record: DynamoStreamEvent,
    handler: suspend (DynamoStreamEvent) -> Unit
): Exception? = try {
    handler(record)
    null
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    e
}

/** Convenience over [processStreamRecords] for the `DynamoStreamRecords` envelope. */
public suspend fun DynamoStreamRecords.processStreamRecords(
    async: Boolean = false,
    handler: suspend (DynamoStreamEvent) -> Unit
): List<Exception?> = records.processStreamRecords(async, handler)

/**
 * Run every parsed record through [handler] and build the partial-batch-failure response.
 *
 * @param enableBatchItemFailures when false, the first failure is rethrown so Lambda redrives the
 *   whole batch. When true, failures are named individually — which only has an effect if the event
 *   source mapping sets `ReportBatchItemFailures`.
 * @param logFailures the level at which to log each failure, or null to log none.
 */
public suspend fun List<RecordInfo>.processWithFailures(
    async: Boolean = false,
    enableBatchItemFailures: Boolean = false,
    logFailures: StreamFailureLogLevel? = StreamFailureLogLevel.WARNING,
    handler: suspend (DynamoStreamEvent) -> Unit
): BatchItemFailuresResponse = batchFailureResponse(
    map { it.dynamoEvent }.processStreamRecords(async, handler),
    enableBatchItemFailures,
    logFailures
)

/**
 * Build the partial-batch-failure response from per-record outcomes that have already been computed.
 *
 * Split out from [processWithFailures] for the handler classes whose extension point is "run the
 * whole batch and give me a `List<Exception?>`" — they produce the results themselves, and only need
 * the reporting half.
 *
 * @param results one entry per record, in order: null where the record succeeded, the exception
 *   where it failed.
 */
public suspend fun List<RecordInfo>.batchFailureResponse(
    results: List<Exception?>,
    enableBatchItemFailures: Boolean = false,
    logFailures: StreamFailureLogLevel? = StreamFailureLogLevel.WARNING
): BatchItemFailuresResponse {
    check(results.size == size) {
        "Results list size (${results.size}) must match input records size ($size)"
    }

    val failedRecords = filterIndexed { index, _ -> results[index] != null }
        .map { BatchItemFailure(itemIdentifier = it.identifier) }

    if (!enableBatchItemFailures && failedRecords.isNotEmpty()) {
        throw results.filterNotNull().first()
    }

    if (logFailures != null && failedRecords.isNotEmpty()) {
        results.forEachIndexed { index, result ->
            if (result != null) {
                logStreamFailure(
                    logFailures,
                    "Dynamo record processing failed",
                    result,
                    mapOf("itemIdentifier" to this[index].identifier)
                )
            }
        }
    }

    return BatchItemFailuresResponse(batchItemFailures = failedRecords)
}

public class StreamHandlingException(message: String, cause: Throwable? = null, state: Map<String, JsonElement>) :
    StatefulException(message, cause, state)

public suspend fun StreamHandlingException(message: String, cause: Throwable? = null): StreamHandlingException =
    StreamHandlingException(message, cause, log.ctx())

/**
 * Emits a per-record failure line.
 *
 * `expect`/`actual` so the JVM keeps logging through the logging module's `logWarning`/`logInfo`/
 * `logError` exactly as the handlers did before this logic moved into common, rather than silently
 * changing the shape existing consumers parse.
 */
internal expect suspend fun logStreamFailure(
    level: StreamFailureLogLevel,
    message: String,
    t: Throwable?,
    metadata: Map<String, String?>
)
