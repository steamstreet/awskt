package com.steamstreet.aws.lambda.eventbridge

import com.steamstreet.aws.lambda.lambdaJson
import com.steamstreet.aws.lambda.native.nativeLambda
import com.steamstreet.aws.sqs.BatchResponse
import kotlinx.serialization.json.JsonElement

/**
 * Convenience entry point for a Kotlin/Native Lambda that handles EventBridge events.
 *
 * Call it from your own `main`; it does not define one:
 *
 * ```kotlin
 * fun main() = eventBridgeLambda {
 *     OrderPlaced { order -> handle(order) }
 *     OrderShipped { order -> handle(order) }
 * }
 * ```
 *
 * The [config] block is the same `EventBridgeHandlerConfig` the JVM `eventBridge()` takes, so the
 * handler registrations move across untouched — that DSL has been in `commonMain` since this module
 * was converted, and as of this change so is the dispatch behind it.
 *
 * Like the JVM entry point, this accepts both a direct EventBridge event and a batch of them
 * delivered through SQS, deciding which by looking for a `Records` array.
 *
 * A wrapper over `nativeLambda` and [processEventBridgePayload], both public. An application that
 * wants to handle several event sources from one binary, or to control its own initialisation order,
 * should call those directly.
 *
 * @param batchSqs report per-record failures for an SQS-wrapped batch rather than failing the whole
 *   invocation. Needs `ReportBatchItemFailures` on the event source mapping to have any effect.
 */
public fun eventBridgeLambda(
    tracePerformance: Boolean = true,
    batchSqs: Boolean = false,
    initialize: suspend () -> Unit = {},
    config: suspend EventBridgeHandlerConfig.() -> Unit
): Unit = nativeLambda(initialize) { body ->
    val response = processEventBridgePayload(
        lambdaJson.decodeFromString(JsonElement.serializer(), body),
        tracePerformance = tracePerformance,
        batchSqs = batchSqs,
        config = config
    )

    // A direct event produces no response body, but the Runtime API requires one — the same reason
    // `nativeLambdaInput` posts "null".
    response?.let { lambdaJson.encodeToString(BatchResponse.serializer(), it) } ?: "null"
}
