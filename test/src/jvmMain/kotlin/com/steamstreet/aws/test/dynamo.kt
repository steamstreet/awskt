package com.steamstreet.aws.test

import com.amazonaws.services.dynamodbv2.local.main.ServerRunner
import com.amazonaws.services.dynamodbv2.local.server.DynamoDBProxyServer
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamViewType
import com.steamstreet.dynamokt.DynamoStreamEvent
import com.steamstreet.dynamokt.DynamoStreamEventDetail
import kotlinx.serialization.json.Json
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder
import software.amazon.awssdk.services.dynamodb.model.*
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry
import java.net.URI
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

typealias StreamProcessorFunction = ((DynamodbEvent, Record) -> Unit)

/**
 * Wraps up configuration of a local dynamo runner, allowing for custom stream processing logic.
 */
class DynamoRunner {
    private var server: DynamoDBProxyServer? = null
    var port: Int = 9945
    var listeners: List<StreamListener>? = null

    var streamProcessor: StreamProcessorFunction? = null
    var pipes: PipesConfiguration? = null

    val processing: Boolean
        get() {
            return listeners?.find { it.processing } != null
        }

    fun startStreamProcessing() {
        val streamList = streamsClient.listStreams {}
        listeners = streamList.streams().mapNotNull {
            StreamListener(streamProcessor, streamsClient, it.streamArn()).also {
                thread { it.run() }
            }
        }
    }

    private fun createServer(): DynamoDBProxyServer {
        return ServerRunner.createServerFromCommandLineArgs(
            arrayOf("-inMemory", "-port", port.toString())
        )
    }

    init {
        server = createServer()
        server?.start()
    }

    val client: DynamoDbClient by lazy {
        DynamoDbClient.builder()
            .endpointOverride(URI.create("http://localhost:$port")) // The region is meaningless for local DynamoDb but required for client builder validation
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("dummy-key", "dummy-secret")
                )
            ).build()
    }

    val clientBuilder: DynamoDbClientBuilder
        get() {
            return DynamoDbClient.builder()
                .endpointOverride(URI.create("http://localhost:$port")) // The region is meaningless for local DynamoDb but required for client builder validation
                .region(Region.US_EAST_1)
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("dummy-key", "dummy-secret")
                    )
                )
        }

    val streamsClient: DynamoDbStreamsClient by lazy {
        DynamoDbStreamsClient.builder()
            .endpointOverride(URI.create("http://localhost:$port"))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("dummy-key", "dummy-secret")
                )
            ).build()
    }

    inner class ShardReader(
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
            val iterators = HashSet<String>()
            var lastSequence: String? = null

            while (running) {
                try {
                    val shardIteratorResult = streams.getShardIterator {
                        it.streamArn(streamArn)
                        it.shardId(shardId)
                        if (lastSequence == null) {
                            it.shardIteratorType(ShardIteratorType.TRIM_HORIZON)
                        } else {
                            it.shardIteratorType(ShardIteratorType.AFTER_SEQUENCE_NUMBER)
                            it.sequenceNumber(lastSequence)
                        }
                    }
                    if (!iterators.contains(shardIteratorResult.shardIterator())) {
                        iterators.add(shardIteratorResult.shardIterator())
                    }

                    currentIterator = shardIteratorResult.shardIterator()
                    processingSemaphore.acquire()
                    while (currentIterator != null && running) {
                        val recordsResult: GetRecordsResponse?
                        try {
                            recordsResult = streams.getRecords {
                                it.shardIterator(currentIterator)
                            }
                            recordsResult.records().forEach {
                                processRecord(streamArn, it)
                                lastSequence = it.dynamodb().sequenceNumber()
                            }
                            currentIterator = recordsResult.nextShardIterator()

                            if (recordsResult.records().size == 0) {
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

        fun processRecord(streamArn: String, record: Record) {
            val event = DynamodbEvent().also {
                it.records = listOf(DynamodbEvent.DynamodbStreamRecord().also { ddbStreamRecord ->
                    ddbStreamRecord.eventSourceARN = streamArn
                    ddbStreamRecord.eventName = record.eventName().name
                    ddbStreamRecord.eventSource = record.eventSource()
                    ddbStreamRecord.awsRegion = record.awsRegion()
                    ddbStreamRecord.eventID = record.eventID()
                    ddbStreamRecord.eventVersion = record.eventVersion()
                    ddbStreamRecord.dynamodb = record.dynamodb().let {
                        com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord()
                            .withKeys(it.keys().toEventAttributeValue())
                            .withNewImage(it.newImage()?.toEventAttributeValue())
                            .withOldImage(it.oldImage()?.toEventAttributeValue())
                            .withSequenceNumber(it.sequenceNumber())
                            .withStreamViewType(StreamViewType.valueOf(it.streamViewTypeAsString()))
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

    inner class StreamListener(
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
                    val shardResult = streams.describeStream {
                        it.streamArn(streamArn)
                    }
                    shardResult.streamDescription().shards().filter {
                        !shards.containsKey(it.shardId())
                    }.forEach {
                        shards[it.shardId()] = ShardReader(processor, streams, streamArn, it.shardId()).also {
                            thread { it.run() }
                        }
                    }
                }
                Thread.sleep(10000)
            }
        }
    }

    fun stop() {
        listeners?.forEach {
            it.running = false
        }
        server?.stop()
    }
}


fun Map<String, AttributeValue>.toEventAttributeValue(): Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> {
    return mapValues { (_, value) -> value.toEventAttributeValue() }
}

fun AttributeValue.toEventAttributeValue(): com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue {
    return com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue().also {
        if (s() != null) {
            it.withS(s())
        } else if (!ss().isNullOrEmpty()) {
            it.withSS(ss())
        } else if (n() != null) {
            it.withN(n())
        } else if (hasNs()) {
            it.withNS(ns())
        } else if (hasM()) {
            it.withM(m().mapValues { (_, value) -> value.toEventAttributeValue() })
        } else if (hasL()) {
            it.withL(l().map { it.toEventAttributeValue() })
        } else if (b() != null) {
            it.withB(b().asByteBuffer())
        } else if (hasBs()) {
            it.withBS(bs().map { it.asByteBuffer() })
        } else if (bool() != null) {
            it.withBOOL(bool())
        }
    }
}

class PipesConfiguration(
    val client: EventBridgeClient,
    val busArn: String,
    val detailType: String,
    val source: String = "DefaultSource"
) {
    fun send(record: DynamodbEvent.DynamodbStreamRecord, srcRecord: Record) {
        val event = DynamoStreamEvent(
            UUID.randomUUID().toString(),
            record.eventName,
            record.eventVersion,
            record.eventSource,
            record.awsRegion,
            record.dynamodb.let {
                DynamoStreamEventDetail(
                    it.approximateCreationDateTime.time,
                    srcRecord.dynamodb().keys(),
                    srcRecord.dynamodb().newImage(),
                    srcRecord.dynamodb().oldImage()
                )
            },
            record.eventSourceARN
        )

        client.putEvents {
            it.entries(PutEventsRequestEntry.builder().apply {
                eventBusName(busArn.substringAfterLast(":event-bus/"))
                detail(Json.encodeToString(DynamoStreamEvent.serializer(), event))
                detailType(detailType)
                source(source)
            }.build())
        }
    }
}