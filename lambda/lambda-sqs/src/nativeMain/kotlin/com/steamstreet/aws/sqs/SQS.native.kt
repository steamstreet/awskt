package com.steamstreet.aws.sqs

import com.steamstreet.aws.lambda.native.nativeLambdaIO
import com.steamstreet.aws.lambda.native.nativeLambdaInput
import kotlinx.serialization.KSerializer

/**
 * Convenience entry point for a Kotlin/Native Lambda reading an SQS queue.
 *
 * Call it from your own `main`; it does not define one:
 *
 * ```kotlin
 * fun main() = sqsLambda(OrderPlaced.serializer()) { order ->
 *     handle(order)
 * }
 * ```
 *
 * The handler runs with the [SQSRecord] as a context parameter, so `record.messageId` and
 * `record.attributes?.ApproximateReceiveCount` are in scope.
 *
 * The first exception fails the invocation and SQS redrives the entire batch. Use [sqsBatchLambda]
 * when the queue reports partial batch failures.
 *
 * A wrapper over `nativeLambdaInput` and [SQSEvent.processMessages], both public — call those
 * directly for anything this shape does not cover.
 */
public fun <T> sqsLambda(
    serializer: KSerializer<T>,
    initialize: suspend () -> Unit = {},
    handler: suspend context(SQSRecord) (T) -> Unit
): Unit = nativeLambdaInput(SQSEvent.serializer(), initialize) { event ->
    event.processMessages(serializer, handler)
}

/**
 * Convenience entry point that hands the handler undecoded message bodies.
 *
 * The counterpart of [SQSEvent.processRawMessages], for a queue carrying more than one message
 * shape.
 */
public fun sqsRawLambda(
    initialize: suspend () -> Unit = {},
    handler: suspend context(SQSRecord) (String) -> Unit
): Unit = nativeLambdaInput(SQSEvent.serializer(), initialize) { event ->
    event.processRawMessages(handler)
}

/**
 * Convenience entry point for an SQS Lambda that reports partial batch failures.
 *
 * ```kotlin
 * fun main() = sqsBatchLambda(OrderPlaced.serializer()) { order ->
 *     handle(order)
 * }
 * ```
 *
 * Records whose handler throws come back in the [BatchResponse] by message id, so SQS redrives only
 * those. This requires `ReportBatchItemFailures` on the event source mapping — without it, Lambda
 * ignores the response and a failed record is deleted from the queue like any other.
 *
 * Note that decoding happens for the whole batch up front, so one malformed body fails the whole
 * invocation rather than being reported as a single failed item. That matches the JVM
 * [SQSBatchHandler]; use [sqsRawLambda] with your own decoding if you need per-record tolerance.
 */
public fun <T> sqsBatchLambda(
    serializer: KSerializer<T>,
    async: Boolean = false,
    logExceptions: Boolean = true,
    initialize: suspend () -> Unit = {},
    handler: suspend context(SQSRecord) (T) -> Unit
): Unit = nativeLambdaIO(SQSEvent.serializer(), BatchResponse.serializer(), initialize) { event ->
    event.processBatch(serializer, async, logExceptions, handler)
}
