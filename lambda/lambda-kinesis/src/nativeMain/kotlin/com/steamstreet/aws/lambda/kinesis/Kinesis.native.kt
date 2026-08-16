package com.steamstreet.aws.lambda.kinesis

import com.steamstreet.aws.lambda.native.nativeLambdaIO
import com.steamstreet.aws.lambda.native.nativeLambdaInput

/**
 * Convenience entry point for a Kotlin/Native Lambda reading a Kinesis stream.
 *
 * Call it from your own `main`; it does not define one:
 *
 * ```kotlin
 * fun main() = kinesisLambda { record ->
 *     handle(record.kinesis.decodedData())
 * }
 * ```
 *
 * A record whose handler throws fails the invocation, and Lambda redrives the whole batch. Use
 * [kinesisBatchLambda] when the stream is configured to report partial batch failures.
 *
 * A wrapper over `nativeLambdaInput` and [KinesisRecords.processRecords], both public — call those
 * directly if this function's shape does not fit.
 */
public fun kinesisLambda(
    async: Boolean = false,
    initialize: suspend () -> Unit = {},
    handler: suspend (KinesisRecord) -> Unit
): Unit = nativeLambdaInput(KinesisRecords.serializer(), initialize) { records ->
    records.processRecords(async, handler)
}

/**
 * Convenience entry point for a Kinesis Lambda that reports partial batch failures.
 *
 * ```kotlin
 * fun main() = kinesisBatchLambda { record ->
 *     handle(record.kinesis.decodedData())
 * }
 * ```
 *
 * Records whose handler throws are returned by sequence number in a [BatchItemFailuresResponse], so
 * Kinesis redrives only those. This is only correct if the event source mapping has
 * `ReportBatchItemFailures` set — without it, Lambda ignores the response and a failed record is
 * silently dropped.
 */
public fun kinesisBatchLambda(
    async: Boolean = false,
    initialize: suspend () -> Unit = {},
    handler: suspend (KinesisRecord) -> Unit
): Unit = nativeLambdaIO(
    KinesisRecords.serializer(),
    BatchItemFailuresResponse.serializer(),
    initialize
) { records ->
    records.processRecordsWithFailures(async, handler)
}
