package com.steamstreet.aws.lambda

import com.steamstreet.aws.lambda.kinesis.BatchItemFailuresResponse
import com.steamstreet.aws.lambda.kinesis.KinesisBatchInfo
import com.steamstreet.aws.lambda.native.nativeLambdaIO
import com.steamstreet.aws.lambda.native.nativeLambdaInput
import com.steamstreet.awskt.kinesis.Kinesis
import com.steamstreet.dynamokt.DynamoKt
import com.steamstreet.dynamokt.DynamoStreamEvent
import com.steamstreet.dynamokt.DynamoStreamRecords
import com.steamstreet.dynamokt.Item
import com.steamstreet.dynamokt.oldAndNew
import kotlinx.serialization.json.JsonElement

/**
 * Convenience entry point for a Kotlin/Native Lambda reading a DynamoDB stream directly.
 *
 * Call it from your own `main`; it does not define one:
 *
 * ```kotlin
 * fun main() = dynamoStreamLambda { record ->
 *     handle(record)
 * }
 * ```
 *
 * The first failure fails the invocation and Lambda redrives the batch. Use [dynamoStreamBatchLambda]
 * when the event source mapping reports partial batch failures.
 */
public fun dynamoStreamLambda(
    async: Boolean = false,
    initialize: suspend () -> Unit = {},
    handler: suspend (DynamoStreamEvent) -> Unit
): Unit = nativeLambdaInput(DynamoStreamRecords.serializer(), initialize) { records ->
    records.processStreamRecords(async, handler).filterNotNull().firstOrNull()?.let { throw it }
}

/**
 * Convenience entry point for a stream Lambda that reports partial batch failures.
 *
 * Accepts every envelope [parseDynamoStreamPayload] understands — a direct DynamoDB stream batch, or
 * one delivered through Kinesis — and names the failed records by `eventID` or sequence number
 * respectively.
 *
 * ### DLQ redrive
 *
 * An SQS record carrying a `KinesisBatchInfo` names a shard and sequence range rather than the
 * payload, so its records have to be fetched back from Kinesis. Pass [kinesis] and they are:
 *
 * ```kotlin
 * fun main() {
 *     val kinesis = Kinesis()
 *     dynamoStreamBatchLambda(kinesis = kinesis) { record -> handle(record) }
 * }
 * ```
 *
 * Leave it null — the default — and redriven records are skipped instead, which is what the JVM
 * [DynamoKtStreamHandler] does when built without a client. A handler with no failure destination
 * configured never receives one of these messages and does not need a client.
 *
 * [onKinesisBatch] overrides both if you have your own way to fetch them.
 */
public fun dynamoStreamBatchLambda(
    async: Boolean = false,
    enableBatchItemFailures: Boolean = true,
    logFailures: StreamFailureLogLevel? = StreamFailureLogLevel.WARNING,
    kinesis: Kinesis? = null,
    onKinesisBatch: suspend (KinesisBatchInfo) -> List<RecordInfo> =
        { batchInfo -> kinesis?.resolveRedrive(batchInfo) ?: emptyList() },
    initialize: suspend () -> Unit = {},
    handler: suspend (DynamoStreamEvent) -> Unit
): Unit = nativeLambdaIO(
    JsonElement.serializer(),
    BatchItemFailuresResponse.serializer(),
    initialize
) { input ->
    parseDynamoStreamPayload(input, onKinesisBatch = onKinesisBatch)
        .processWithFailures(async, enableBatchItemFailures, logFailures, handler)
}

/**
 * Convenience entry point for a stream Lambda that wants the records as DynamoKt [Item]s.
 *
 * The native counterpart of [DynamoKtStreamHandler]'s default `handleRecord`, which resolves each
 * event to its old and new item through a [DynamoKt] session:
 *
 * ```kotlin
 * fun main() = dynamoKtStreamLambda(dynamoKt) { old, new, _ ->
 *     if (old == null) indexNew(new!!)
 * }
 * ```
 *
 * Takes [kinesis] for DLQ redrive on the same terms as [dynamoStreamBatchLambda].
 */
public fun dynamoKtStreamLambda(
    dynamoKt: DynamoKt,
    async: Boolean = false,
    enableBatchItemFailures: Boolean = true,
    logFailures: StreamFailureLogLevel? = StreamFailureLogLevel.WARNING,
    kinesis: Kinesis? = null,
    initialize: suspend () -> Unit = {},
    onItemUpdate: suspend (old: Item?, new: Item?, record: DynamoStreamEvent) -> Unit
): Unit {
    val session = dynamoKt.session()
    return dynamoStreamBatchLambda(
        async = async,
        enableBatchItemFailures = enableBatchItemFailures,
        logFailures = logFailures,
        kinesis = kinesis,
        initialize = initialize
    ) { record ->
        val (old, new) = record.oldAndNew(session)
        onItemUpdate(old, new, record)
    }
}
