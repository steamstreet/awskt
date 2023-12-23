package com.steamstreet.aws.test

import aws.sdk.kotlin.runtime.AwsServiceException
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodbstreams.*
import aws.sdk.kotlin.services.dynamodbstreams.model.GetRecordsResponse
import aws.sdk.kotlin.services.dynamodbstreams.model.Record
import aws.sdk.kotlin.services.dynamodbstreams.model.ShardIteratorType
import aws.sdk.kotlin.services.dynamodbstreams.model.TrimmedDataAccessException
import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.sdk.kotlin.services.eventbridge.model.PutEventsRequestEntry
import aws.sdk.kotlin.services.eventbridge.putEvents
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamViewType
import com.steamstreet.dynamokt.DynamoStreamEvent
import com.steamstreet.dynamokt.DynamoStreamEventDetail
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

public typealias StreamProcessorFunction = (suspend (DynamodbEvent, Record) -> Unit)

/**
 * Handles reading from a dynamo stream and writing to a callback.
 */
public class DynamoStreamRunner(
    private val streamsClient: DynamoDbStreamsClient
) : MockService {
    private var listeners: List<StreamListener>? = null

    private var streamProcessor: StreamProcessorFunction? = null
    private var pipes: PipesConfiguration? = null

    override val isProcessing: Boolean
        get() = listeners.orEmpty().any { it.processing }

    override suspend fun start() {
        val streamList = streamsClient.listStreams {
        }
        listeners = streamList.streams.orEmpty().map {
            StreamListener(streamProcessor, streamsClient, it.streamArn!!).also {
                thread { it.run() }
            }
        }
    }

    internal inner class ShardReader(
        val processor: StreamProcessorFunction?,
        val streams: DynamoDbStreamsClient,
        val streamArn: String,
        val shardId: String
    ) : Runnable {
        var running = true
        val processing: Boolean
            get() {
                processingSemaphore.tryAcquire(5, TimeUnit.SECONDS)
                val response = processingRecords.get()
                processingSemaphore.release()
                return response
            }
        var processingRecords = AtomicBoolean(true)
        var currentIterator: String? = null
        val processingSemaphore = Semaphore(1)

        override fun run() {
            runBlocking {
                val iterators = HashSet<String>()
                var lastSequence: String? = null

                while (running) {
                    try {
                        val shardIteratorResult = streams.getShardIterator {
                            streamArn = (streamArn)
                            shardId = this@ShardReader.shardId
                            if (lastSequence == null) {
                                shardIteratorType = ShardIteratorType.TrimHorizon
                            } else {
                                shardIteratorType = ShardIteratorType.AfterSequenceNumber
                                sequenceNumber = lastSequence
                            }
                        }
                        if (!iterators.contains(shardIteratorResult.shardIterator)) {
                            iterators.add(shardIteratorResult.shardIterator!!)
                        }

                        currentIterator = shardIteratorResult.shardIterator
                        processingSemaphore.acquire()
                        while (currentIterator != null && running) {
                            val recordsResult: GetRecordsResponse?
                            try {
                                recordsResult = streams.getRecords {
                                    shardIterator = currentIterator
                                }
                                recordsResult.records?.forEach {
                                    processRecord(streamArn, it)
                                    lastSequence = it.dynamodb?.sequenceNumber
                                }
                                currentIterator = recordsResult.nextShardIterator

                                if (recordsResult.records.isNullOrEmpty()) {
                                    processingRecords.set(false)
                                    processingSemaphore.release()
                                    Thread.sleep(25)
                                    processingSemaphore.acquire()
                                    processingRecords.set(true)
                                    Thread.sleep(100)
                                }
                            } catch (t: TrimmedDataAccessException) {
                                currentIterator = null
                                processingSemaphore.release()
                            }
                        }
                    } catch (e: AwsServiceException) {
                        processingSemaphore.release()
                        throw e
                    }
                }
            }
        }

        suspend fun processRecord(streamArn: String, record: Record) {
            val event = DynamodbEvent().also {
                it.records = listOf(DynamodbEvent.DynamodbStreamRecord().also { ddbStreamRecord ->
                    ddbStreamRecord.eventSourceARN = streamArn
                    ddbStreamRecord.eventName = record.eventName!!.value
                    ddbStreamRecord.eventSource = record.eventSource
                    ddbStreamRecord.awsRegion = record.awsRegion
                    ddbStreamRecord.eventID = record.eventId
                    ddbStreamRecord.eventVersion = record.eventVersion
                    ddbStreamRecord.dynamodb = record.dynamodb?.let {
                        com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord()
                            .withKeys(it.keys!!.toEventAttributeValue())
                            .withNewImage(it.newImage?.toEventAttributeValue())
                            .withOldImage(it.oldImage?.toEventAttributeValue())
                            .withSequenceNumber(it.sequenceNumber)
                            .withStreamViewType(StreamViewType.valueOf(it.streamViewType!!.value))
                            .withApproximateCreationDateTime(Date())
                    }
                })
            }
            if (running) {
                processor?.invoke(event, record)
                pipes?.send(event.records.first(), record)
            }
        }
    }

    internal inner class StreamListener(
        val processor: StreamProcessorFunction?,
        val streams: DynamoDbStreamsClient,
        val streamArn: String
    ) :
        Runnable {
        val shards = HashMap<String, ShardReader>()
        var _running: Boolean = true
        var running: Boolean
            get() {
                return _running
            }
            set(value) {
                _running = value
                if (!value) {
                    shards.values.forEach { it.running = false }
                }
            }

        val processing: Boolean
            get() = synchronized(shards) {
                shards.values.find { it.processing } != null
            }

        override fun run() {
            while (running) {
                synchronized(shards) {
                    runBlocking {
                        val shardResult = streams.describeStream {
                            streamArn = this@StreamListener.streamArn
                        }
                        shardResult.streamDescription?.shards?.filter {
                            !shards.containsKey(it.shardId)
                        }?.forEach {
                            shards[it.shardId!!] = ShardReader(processor, streams, streamArn, it.shardId!!).also {
                                thread { it.run() }
                            }
                        }
                    }
                }
                Thread.sleep(10000)
            }
        }
    }

    override suspend fun stop() {
        listeners?.forEach {
            it.running = false
        }
    }
}


internal fun Map<String, aws.sdk.kotlin.services.dynamodbstreams.model.AttributeValue>.toEventAttributeValue(): Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> {
    return mapValues { (_, value) -> value.toEventAttributeValue() }
}

internal fun aws.sdk.kotlin.services.dynamodbstreams.model.AttributeValue.toEventAttributeValue(): com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue {
    return com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue().also {
        if (asSOrNull() != null) {
            it.withS(asS())
        } else if (!asSsOrNull().isNullOrEmpty()) {
            it.withSS(asSs())
        } else if (asNOrNull() != null) {
            it.withN(asN())
        } else if (asNsOrNull() != null) {
            it.withNS(asNs())
        } else if (asMOrNull() != null) {
            it.withM(asM().mapValues { (_, value) -> value.toEventAttributeValue() })
        } else if (asLOrNull() != null) {
            it.withL(asL().map { it.toEventAttributeValue() })
        } else if (asBOrNull() != null) {
            it.withB(ByteBuffer.wrap(asB()))
        } else if (asBsOrNull() != null) {
            it.withBS(asBs().map { ByteBuffer.wrap(it) })
        } else if (asBoolOrNull() != null) {
            it.withBOOL(asBool())
        }
    }
}

internal fun aws.sdk.kotlin.services.dynamodbstreams.model.AttributeValue.toModelAttributeValue(): AttributeValue {
    return when {
        this.asSOrNull() != null -> AttributeValue.S(this.asS())
        this.asNOrNull() != null -> AttributeValue.N(this.asS())
        this.asBOrNull() != null -> AttributeValue.B(this.asB())
        this.asSsOrNull() != null -> AttributeValue.Ss(this.asSs())
        this.asNsOrNull() != null -> AttributeValue.Ns(this.asNs())
        this.asBoolOrNull() != null -> AttributeValue.Bool(this.asBool())
        this.asMOrNull() != null -> AttributeValue.M(this.asM().mapValues { (_, value) ->
            value.toModelAttributeValue()
        })

        this.asLOrNull() != null -> AttributeValue.L(this.asL().map {
            it.toModelAttributeValue()
        })

        this.asBOrNull() != null -> AttributeValue.B(this.asB())
        this.asBsOrNull() != null -> AttributeValue.Bs(this.asBs())
        else -> AttributeValue.Null(true)
    }
}

internal fun Map<String, aws.sdk.kotlin.services.dynamodbstreams.model.AttributeValue>.toModelAttributeValue():
        Map<String, AttributeValue> {
    return this.mapValues { (_, value) ->
        value.toModelAttributeValue()
    }
}

public class PipesConfiguration(
    public val client: EventBridgeClient,
    public val busArn: String,
    public val detailType: String,
    public val source: String = "DefaultSource"
) {
    public suspend fun send(record: DynamodbEvent.DynamodbStreamRecord, srcRecord: Record) {
        val event = DynamoStreamEvent(
            UUID.randomUUID().toString(),
            record.eventName,
            record.eventVersion,
            record.eventSource,
            record.awsRegion,
            record.dynamodb.let {
                DynamoStreamEventDetail(
                    it.approximateCreationDateTime.time.toDouble(),
                    srcRecord.dynamodb!!.keys!!.toModelAttributeValue(),
                    srcRecord.dynamodb?.newImage?.toModelAttributeValue(),
                    srcRecord.dynamodb?.oldImage?.toModelAttributeValue()
                )
            },
            record.eventSourceARN
        )

        client.putEvents {
            entries = listOf(
                PutEventsRequestEntry {
                    eventBusName = (busArn.substringAfterLast(":event-bus/"))
                    detail = (Json.encodeToString(DynamoStreamEvent.serializer(), event))
                    detailType = this@PipesConfiguration.detailType
                    source = this@PipesConfiguration.source
                }
            )
        }
    }
}