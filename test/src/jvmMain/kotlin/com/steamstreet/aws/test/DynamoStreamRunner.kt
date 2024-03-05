package com.steamstreet.aws.test

import aws.sdk.kotlin.runtime.AwsServiceException
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodbstreams.*
import aws.sdk.kotlin.services.dynamodbstreams.model.GetRecordsResponse
import aws.sdk.kotlin.services.dynamodbstreams.model.Record
import aws.sdk.kotlin.services.dynamodbstreams.model.ShardIteratorType
import aws.sdk.kotlin.services.dynamodbstreams.model.TrimmedDataAccessException
import aws.smithy.kotlin.runtime.time.epochMilliseconds
import com.steamstreet.aws.lambda.DynamoStreamHandler
import com.steamstreet.dynamokt.DynamoStreamEvent
import com.steamstreet.dynamokt.DynamoStreamEventDetail
import com.steamstreet.dynamokt.DynamoStreamRecords
import kotlinx.coroutines.*

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

    override val isProcessing: Boolean
        get() {
            return iterators.isNotEmpty()
        }

    override suspend fun start() {
        val stream = streamsClient.listStreams {}.streams?.find {
            it.tableName == this@DynamoStreamRunner.tableName
        } ?: throw IllegalArgumentException("Unknown table")

        processStream(stream.streamArn!!)
    }

    private fun CoroutineScope.processShard(streamArn: String, shardId: String) {
        var lastSequence: String? = null

        launch {
            while (true) {
                try {
                    val shardIteratorResult = streamsClient.getShardIterator {
                        this.streamArn = streamArn
                        this.shardId = shardId
                        if (lastSequence == null) {
                            shardIteratorType = ShardIteratorType.TrimHorizon
                        } else {
                            shardIteratorType = ShardIteratorType.AfterSequenceNumber
                            sequenceNumber = lastSequence
                        }
                    }
                    var currentIterator = shardIteratorResult.shardIterator
                    while (currentIterator != null) {
                        val recordsResult: GetRecordsResponse?
                        try {
                            recordsResult = streamsClient.getRecords {
                                shardIterator = currentIterator
                            }
                            iterators += currentIterator
                            recordsResult.records?.forEach {
                                processRecord(streamArn, it)
                                lastSequence = it.dynamodb?.sequenceNumber
                            }

                            if (recordsResult.records.isNullOrEmpty()) {
                                iterators.remove(currentIterator)
                                delay(100)
                            }

                            if (recordsResult.nextShardIterator != currentIterator) {
                                iterators.remove(currentIterator)
                                currentIterator = recordsResult.nextShardIterator
                            }
                        } catch (t: TrimmedDataAccessException) {
                            currentIterator = null
                        }
                    }
                } catch (e: AwsServiceException) {
                    throw e
                }
            }
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

    private suspend fun processStream(streamArn: String): Job {
        val shards = HashMap<String, Job>()
        coroutineScope {
            while (true) {
                streamsClient.describeStream {
                    this.streamArn = streamArn
                }.streamDescription?.shards?.map {
                    if (!shards.containsKey(it.shardId)) {
                        val shardJob = launch {
                            processShard(streamArn, it.shardId!!)
                        }
                        shards[it.shardId!!] = shardJob
                    }
                    it
                }.orEmpty()
                delay(1000)
            }
        }
    }

    override suspend fun stop() {
    }
}


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