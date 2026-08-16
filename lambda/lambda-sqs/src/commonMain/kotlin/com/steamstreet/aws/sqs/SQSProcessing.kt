package com.steamstreet.aws.sqs

import com.steamstreet.aws.lambda.lambdaIODispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * The SQS processing loops, in `commonMain` so the JVM handler base classes and the native
 * `sqsLambda` wrappers share one implementation of the batch semantics rather than carrying two
 * that can drift.
 *
 * Everything here is a plain function over plain data — no runtime, no streams, no `Context` — so it
 * is equally callable from a test, from a JVM `RequestStreamHandler`, or from a `main` that does its
 * own dispatch.
 */

/**
 * Decodes SQS message bodies.
 *
 * Deliberately not `lambdaJson`: this is the lenient configuration the JVM handlers have always
 * created for themselves, kept identical so moving the loops into common does not change how an
 * existing handler parses a message.
 */
internal val sqsMessageJson: Json = Json {
    ignoreUnknownKeys = true
}

/**
 * Hand each record's raw body to [handler], with the [SQSRecord] in scope.
 *
 * The first exception propagates, which fails the invocation and makes SQS redrive the whole batch.
 * Use [processBatch] when the queue is configured to report partial batch failures.
 */
public suspend fun SQSEvent.processRawMessages(
    handler: suspend context(SQSRecord) (String) -> Unit
) {
    Records.forEach { record ->
        handler(record, record.body)
    }
}

/**
 * Decode each record's body and hand it to [handler], with the [SQSRecord] in scope.
 *
 * As with [processRawMessages], the first exception fails the whole batch.
 */
public suspend fun <T> SQSEvent.processMessages(
    serializer: KSerializer<T>,
    handler: suspend context(SQSRecord) (T) -> Unit
) {
    Records.forEach { record ->
        handler(record, sqsMessageJson.decodeFromString(serializer, record.body))
    }
}

/**
 * Decode every record, hand each to [handler], and collect the failures into a [BatchResponse].
 *
 * A record whose handler throws is reported in `batchItemFailures` by message id; a record that
 * returns normally is not. Returning this response lets SQS redrive only the failed records, which
 * requires `ReportBatchItemFailures` on the event source mapping — without it, Lambda ignores the
 * response body and a failed record is dropped.
 *
 * Decoding happens for the whole batch before any handler runs, matching the behaviour
 * `SQSBatchHandler` has always had: a single malformed body fails the invocation outright rather
 * than being reported as one failed item. That is worth knowing about, and is not changed here.
 *
 * @param async when true, records are dispatched concurrently on
 *   [lambdaIODispatcher][com.steamstreet.aws.lambda.lambdaIODispatcher].
 * @param logExceptions when true, each failure is logged before being collected.
 */
public suspend fun <T> SQSEvent.processBatch(
    serializer: KSerializer<T>,
    async: Boolean = false,
    logExceptions: Boolean = true,
    handler: suspend context(SQSRecord) (T) -> Unit
): BatchResponse = batchResponse(
    runMessages(Records.map { sqsMessageJson.decodeFromString(serializer, it.body) }, async, logExceptions, handler)
)

/**
 * Hand each already-decoded message to [handler] and report per-record success.
 *
 * The loop underneath [processBatch], split out for callers that have decoded the batch themselves —
 * `SQSBatchHandler.handleEvents` is one, since its contract hands the subclass a `List<T>` and takes
 * a `List<Boolean>` back.
 *
 * [messages] must line up with [Records] element for element; the caller owns that, because it is
 * the caller that produced the list.
 */
public suspend fun <T> SQSEvent.runMessages(
    messages: List<T>,
    async: Boolean = false,
    logExceptions: Boolean = true,
    handler: suspend context(SQSRecord) (T) -> Unit
): List<Boolean> {
    require(messages.size == Records.size) {
        "Decoded message count (${messages.size}) must match record count (${Records.size})"
    }
    return if (async) {
        coroutineScope {
            messages.mapIndexed { index, message ->
                async(lambdaIODispatcher) {
                    runRecord(Records[index], message, logExceptions, handler)
                }
            }.awaitAll()
        }
    } else {
        messages.mapIndexed { index, message ->
            runRecord(Records[index], message, logExceptions, handler)
        }
    }
}

/**
 * Turn per-record success flags into the partial-batch-failure response SQS expects.
 */
public fun SQSEvent.batchResponse(succeeded: List<Boolean>): BatchResponse {
    check(succeeded.size == Records.size) {
        "Result count (${succeeded.size}) must match record count (${Records.size})"
    }
    return BatchResponse(
        Records.filterIndexed { index, _ -> !succeeded[index] }
            .map { RecordResponse(it.messageId) }
    )
}

/**
 * Runs one record's handler under that record's logging context, reporting success as a boolean.
 *
 * `CancellationException` is deliberately *not* special-cased here, because the JVM
 * `SQSBatchHandler` never special-cased it and doing so now would change which records a
 * mid-invocation timeout reports as failed. It is caught and reported as a failure, same as before.
 */
private suspend fun <T> runRecord(
    record: SQSRecord,
    message: T,
    logExceptions: Boolean,
    handler: suspend context(SQSRecord) (T) -> Unit
): Boolean = withMessageContext(record.messageId) {
    try {
        handler(record, message)
        true
    } catch (t: Throwable) {
        if (logExceptions) {
            logMessageFailure("SQS Processing Failed", t)
        }
        false
    }
}

/**
 * Runs [block] with the message id attached to the logging context.
 *
 * `expect`/`actual` rather than the common `Log` API so the JVM keeps using slf4j's MDC exactly as
 * `SQSBatchHandler` did before this logic moved into common — the alternative would silently change
 * the log shape that existing JVM consumers parse.
 */
internal expect suspend fun <T> withMessageContext(messageId: String, block: suspend () -> T): T

/** Emits the per-record failure line. `expect`/`actual` for the same reason as [withMessageContext]. */
internal expect suspend fun logMessageFailure(message: String, t: Throwable)
