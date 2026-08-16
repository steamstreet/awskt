@file:Suppress("PropertyName")

package com.steamstreet.aws.lambda.kinesis

import com.steamstreet.aws.lambda.lambdaIODispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant

/**
 * The Kinesis event envelope and the record-processing loop, in `commonMain` so the same handler
 * body compiles for the JVM and for Kotlin/Native.
 */
@Serializable
public data class KinesisRecords(
    val Records: List<KinesisRecord>
)

@Serializable
public data class KinesisRecord(
    val kinesis: KinesisDetails,
    val eventSource: String,
    val eventVersion: String,
    val eventID: String,
    val eventName: String,
    val invokeIdentityArn: String? = null,
    val awsRegion: String? = null,
    val eventSourceARN: String? = null
)

@Serializable
public data class KinesisBatchInfo(
    val shardId: String,
    val startSequenceNumber: String,
    val endSequenceNumber: String,
    val approximateArrivalOfFirstRecord: Instant? = null,
    val approximateArrivalOfLastRecord: Instant? = null,
    val batchSize: Int,
    val streamArn: String
)

@Serializable
public data class KinesisDetails(
    val kinesisSchemaVersion: String,
    val partitionKey: String,
    val sequenceNumber: String,
    val data: String,
    val approximateArrivalTimestamp: Double
) {
    /**
     * The record payload, Base64-decoded.
     *
     * Was `String(Base64.decode(data))`; `decodeToString()` is the same operation — UTF-8, replacing
     * malformed input — spelled in a way that exists outside the JVM.
     */
    @OptIn(ExperimentalEncodingApi::class)
    public fun decodedData(): String = Base64.decode(data).decodeToString()
}

/**
 * Hand each record to [handler], sequentially or concurrently.
 *
 * This is what a Kinesis Lambda does, with no runtime behind it — callable from a test, from your
 * own `main`, or from either of the wrappers built on it.
 *
 * @param async when true, records are dispatched concurrently on [lambdaIODispatcher] and the call
 *   returns once all of them finish. Order of *completion* is then unspecified, so leave it false
 *   when the shard's ordering guarantee is the reason you are using Kinesis.
 */
public suspend fun KinesisRecords.processRecords(
    async: Boolean = false,
    handler: suspend (KinesisRecord) -> Unit
) {
    if (async) {
        coroutineScope {
            Records.map { record ->
                async(lambdaIODispatcher) { handler(record) }
            }.awaitAll()
        }
    } else {
        Records.forEach { record ->
            handler(record)
        }
    }
}

/**
 * Hand each record to [handler] and collect the failures, so the caller can return a partial-batch
 * response instead of failing the whole batch.
 *
 * A record whose handler throws is reported by sequence number; a record that returns normally is
 * not. [CancellationException][kotlin.coroutines.cancellation.CancellationException] is rethrown
 * rather than recorded — it means the invocation is being torn down, not that the record failed.
 */
public suspend fun KinesisRecords.processRecordsWithFailures(
    async: Boolean = false,
    handler: suspend (KinesisRecord) -> Unit
): BatchItemFailuresResponse {
    val failures = mutableListOf<BatchItemFailure>()
    val results: List<Throwable?> = if (async) {
        coroutineScope {
            Records.map { record ->
                async(lambdaIODispatcher) { runCatchingCancellable { handler(record) } }
            }.awaitAll()
        }
    } else {
        Records.map { record ->
            runCatchingCancellable { handler(record) }
        }
    }

    results.forEachIndexed { index, throwable ->
        if (throwable != null) {
            failures.add(BatchItemFailure(Records[index].kinesis.sequenceNumber))
        }
    }
    return BatchItemFailuresResponse(failures)
}

/**
 * Runs [block], returning the exception it threw or null if it completed.
 *
 * Not `runCatching`: that swallows `CancellationException`, which on a Lambda being torn down turns
 * a cancelled invocation into a reported per-record failure and leaves the coroutine machinery in an
 * inconsistent state.
 */
internal inline fun runCatchingCancellable(block: () -> Unit): Throwable? = try {
    block()
    null
} catch (t: kotlin.coroutines.cancellation.CancellationException) {
    throw t
} catch (t: Throwable) {
    t
}

@Serializable
public data class BatchItemFailuresResponse(
    val batchItemFailures: List<BatchItemFailure>
)

@Serializable
public data class BatchItemFailure(
    val itemIdentifier: String
)
