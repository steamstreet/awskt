package com.steamstreet.aws.test

import aws.sdk.kotlin.runtime.AwsServiceException
import com.steamstreet.dynamokt.AttributeValue
import aws.sdk.kotlin.services.dynamodbstreams.*
import aws.sdk.kotlin.services.dynamodbstreams.model.Record
import aws.sdk.kotlin.services.dynamodbstreams.model.ResourceNotFoundException
import aws.sdk.kotlin.services.dynamodbstreams.model.ShardIteratorType
import aws.sdk.kotlin.services.dynamodbstreams.model.TrimmedDataAccessException
import aws.smithy.kotlin.runtime.time.epochMilliseconds
import com.steamstreet.aws.lambda.DynamoStreamHandler
import com.steamstreet.dynamokt.DynamoStreamEvent
import com.steamstreet.dynamokt.DynamoStreamEventDetail
import com.steamstreet.dynamokt.DynamoStreamRecords
import com.steamstreet.exceptions.retry
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

public typealias StreamProcessorFunction = (suspend (DynamoStreamEvent) -> Unit)

/**
 * Handles reading from a dynamo stream and writing to a callback.
 */
public class DynamoStreamRunner(
    private val tableName: String,
    private val streamsClient: DynamoDbStreamsClient,
    private val streamProcessor: StreamProcessorFunction
) : MockService {
    private val iterators = mutableSetOf<String>()
    private val starting = AtomicBoolean(true)

    private val running = AtomicBoolean(true)
    private val completed = AtomicBoolean(false)

    /** How many calls in a row may fail before a shard, or the stream itself, is given up on. */
    internal var maxConsecutiveFailures: Int = 8

    /** The wait after the first failure. It doubles with each further failure in a row. */
    internal var initialBackoffMillis: Long = 100

    /** The longest wait between retries. */
    internal var maxBackoffMillis: Long = 5_000

    override val isProcessing: Boolean
        get() {
            return starting.get() || iterators.isNotEmpty()
        }

    override val isReady: Boolean
        get() = (!starting.get())

    override suspend fun start() {
        starting.set(true)
        val stream = retry(5, delay = 100, exceptionType = IllegalArgumentException::class) {
            streamsClient.listStreams {
                tableName = this@DynamoStreamRunner.tableName
            }.streams?.find {
                it.tableName == this@DynamoStreamRunner.tableName
            } ?: throw IllegalArgumentException("Unknown table")
        }

        withContext(Dispatchers.IO) {
            launch {
                processStream(stream.streamArn!!)
            }
        }
    }

    /**
     * Reads one shard until the runner stops, the shard closes, the shard or stream disappears, or
     * [maxConsecutiveFailures] calls in a row fail. A runner whose test aborted before teardown never sees
     * [stop], so every exit other than the first must be reachable without it, and no failure may be retried
     * without a pause.
     */
    private suspend fun processShard(streamArn: String, shardId: String) {
        var lastSequence: String? = null
        var hasFetchedIterator = false
        var failures = 0

        try {
            while (running.get()) {
                var currentIterator: String? = null
                try {
                    val fetchIterator: suspend () -> String? = {
                        streamsClient.getShardIterator {
                            this.streamArn = streamArn
                            this.shardId = shardId
                            if (lastSequence == null) {
                                shardIteratorType = ShardIteratorType.TrimHorizon
                            } else {
                                shardIteratorType = ShardIteratorType.AfterSequenceNumber
                                sequenceNumber = lastSequence
                            }
                        }.shardIterator
                    }
                    // The first fetch retries quickly, because a new table's stream may not be ready yet. Later
                    // fetches fail through to the backoff below.
                    currentIterator = if (hasFetchedIterator) fetchIterator() else retry(30, delay = 100) {
                        fetchIterator()
                    }
                    hasFetchedIterator = true

                    while (currentIterator != null && running.get()) {
                        val iterator: String = currentIterator
                        try {
                            val recordsResult = streamsClient.getRecords {
                                shardIterator = iterator
                            }
                            failures = 0
                            iterators += iterator
                            recordsResult.records?.forEach {
                                processRecord(streamArn, it)
                                lastSequence = it.dynamodb?.sequenceNumber
                            }

                            if (recordsResult.records.isNullOrEmpty()) {
                                iterators.remove(iterator)
                                delay(100)
                            }

                            if (recordsResult.nextShardIterator != iterator) {
                                iterators.remove(iterator)
                                currentIterator = recordsResult.nextShardIterator
                                if (currentIterator == null) {
                                    // The shard is closed and fully read. Its children appear in describeStream.
                                    return
                                }
                            }
                        } catch (_: TrimmedDataAccessException) {
                            // The records after lastSequence aged out, so start again from the oldest one left.
                            iterators.remove(iterator)
                            lastSequence = null
                            currentIterator = null
                        }
                        starting.set(false)
                    }
                } catch (e: AwsServiceException) {
                    currentIterator?.let { iterators.remove(it) }
                    if (e.isShardOrStreamGone()) {
                        log("Stopping shard $shardId, which no longer exists", e)
                        return
                    }
                    // An expired iterator lands here too: the next pass fetches a fresh one from lastSequence.
                    failures++
                    if (failures >= maxConsecutiveFailures) {
                        log("Stopping shard $shardId after $failures consecutive failures", e)
                        return
                    }
                    log("Retrying shard $shardId (failure $failures of $maxConsecutiveFailures)", e)
                    pause(backoff(failures))
                } finally {
                    starting.set(false)
                }
            }
        } finally {
            completed.set(true)
            starting.set(false)
        }
    }

    private suspend fun processRecord(streamArn: String, record: Record) {
        val event = DynamoStreamEvent(
            record.eventId!!,
            record.eventName!!.value,
            record.eventVersion!!,
            record.eventSource!!,
            record.awsRegion!!,
            with(record.dynamodb!!) {
                DynamoStreamEventDetail(
                    approximateCreationDateTime!!.epochMilliseconds.toDouble(),
                    keys!!.mapValues {
                        it.value.toModelAttributeValue()
                    },
                    newImage?.toModelAttributeValue(),
                    oldImage?.toModelAttributeValue(),
                    sequenceNumber,
                    sizeBytes,
                    streamViewType?.value
                )
            },
            streamArn,
            null
        )
        streamProcessor.invoke(event)
    }

    private suspend fun processStream(streamArn: String) {
        // A shard stays in this map after its job ends, so a shard that was given up on is not restarted.
        val shards = HashMap<String, Job>()
        var failures = 0
        withContext(Dispatchers.IO) {
            while (running.get()) {
                try {
                    streamsClient.describeStream {
                        this.streamArn = streamArn
                    }.streamDescription?.shards?.forEach {
                        if (!shards.containsKey(it.shardId)) {
                            val shardJob = launch(Dispatchers.IO) {
                                processShard(streamArn, it.shardId!!)
                            }
                            shards[it.shardId!!] = shardJob
                        }
                    }
                    failures = 0
                    delay(300)
                } catch (e: AwsServiceException) {
                    if (e.isShardOrStreamGone()) {
                        log("Stopping the stream reader for $tableName, whose stream no longer exists", e)
                        break
                    }
                    failures++
                    if (failures >= maxConsecutiveFailures) {
                        log("Stopping the stream reader for $tableName after $failures consecutive failures", e)
                        break
                    }
                    log("Retrying describeStream for $tableName (failure $failures of $maxConsecutiveFailures)", e)
                    pause(backoff(failures))
                }
            }
        }
    }

    /**
     * The wait before retry number [failure], doubling from [initialBackoffMillis] up to [maxBackoffMillis].
     */
    private fun backoff(failure: Int): Long {
        val shift = (failure - 1).coerceIn(0, 20)
        return (initialBackoffMillis shl shift).coerceAtMost(maxBackoffMillis)
    }

    /**
     * Waits [millis], but returns early once the runner stops, so that [stop] is not held up by a backoff.
     */
    private suspend fun pause(millis: Long) {
        var remaining = millis
        while (remaining > 0 && running.get()) {
            val step = remaining.coerceAtMost(100)
            delay(step)
            remaining -= step
        }
    }

    /**
     * One line per failure. A stack trace each time is what let a looping runner fill a 50 GB daemon log.
     */
    private fun log(message: String, e: AwsServiceException) {
        println("DynamoStreamRunner: $message: ${e::class.simpleName}: ${e.message}")
    }

    override suspend fun stop() {
        val wasCompleted = completed.get()
        running.set(false)

        withContext(Dispatchers.IO) {
            // wait for the stream processing to be finished.
            if (!wasCompleted) {
                withTimeout(2000) {
                    while (!completed.get()) {
                        delay(100)
                    }
                }
            }
        }
    }
}


/**
 * Whether the error means the shard or the stream is gone, for example because a test deleted and recreated
 * its table, or the local DynamoDB was reset. Retrying such an error can never succeed. DynamoDB Local reports
 * a stale shard as a validation error, "Invalid ShardId in ShardIterator", rather than as a modeled exception.
 */
private fun AwsServiceException.isShardOrStreamGone(): Boolean =
    this is ResourceNotFoundException || message?.contains("Invalid ShardId", ignoreCase = true) == true

internal fun aws.sdk.kotlin.services.dynamodbstreams.model.AttributeValue.toModelAttributeValue(): AttributeValue {
    return when (this) {
        is aws.sdk.kotlin.services.dynamodbstreams.model.AttributeValue.S -> AttributeValue.S(this.asS())
        is aws.sdk.kotlin.services.dynamodbstreams.model.AttributeValue.N -> AttributeValue.N(this.asN())
        is aws.sdk.kotlin.services.dynamodbstreams.model.AttributeValue.B -> AttributeValue.B(this.asB())
        is aws.sdk.kotlin.services.dynamodbstreams.model.AttributeValue.Ss -> AttributeValue.Ss(this.asSs())
        is aws.sdk.kotlin.services.dynamodbstreams.model.AttributeValue.Ns -> AttributeValue.Ns(this.asNs())
        is aws.sdk.kotlin.services.dynamodbstreams.model.AttributeValue.Bool -> AttributeValue.Bool(this.asBool())
        is aws.sdk.kotlin.services.dynamodbstreams.model.AttributeValue.M -> AttributeValue.M(
            this.asM().mapValues { (_, value) ->
                value.toModelAttributeValue()
            })

        is aws.sdk.kotlin.services.dynamodbstreams.model.AttributeValue.L -> AttributeValue.L(this.asL().map {
            it.toModelAttributeValue()
        })

        is aws.sdk.kotlin.services.dynamodbstreams.model.AttributeValue.Bs -> AttributeValue.Bs(this.asBs())
        else -> AttributeValue.Null(true)
    }
}

internal fun Map<String, aws.sdk.kotlin.services.dynamodbstreams.model.AttributeValue>.toModelAttributeValue():
        Map<String, AttributeValue> {
    return this.mapValues { (_, value) ->
        value.toModelAttributeValue()
    }
}

/**
 * Create a stream runner that takes a DynamoStreamHandler instance, simplifying setup.
 */
public fun DynamoStreamRunner(
    tableName: String,
    streamsClient: DynamoDbStreamsClient,
    streamProcessor: DynamoStreamHandler
): DynamoStreamRunner {
    return DynamoStreamRunner(tableName, streamsClient) {
        streamProcessor.handle(DynamoStreamRecords(listOf(it)))
    }
}