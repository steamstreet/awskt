package com.steamstreet.aws.lambda.kinesis

import com.steamstreet.aws.lambda.InputLambda
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant

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
    @OptIn(ExperimentalEncodingApi::class)
    public fun decodedData(): String {
        return String(Base64.decode(data))
    }
}

public abstract class KinesisHandler : InputLambda<KinesisRecords>(KinesisRecords.serializer()) {
    protected var async: Boolean = false

    override suspend fun handle(input: KinesisRecords) {
        if (async) {
            coroutineScope {
                input.Records.map { t ->
                    async(Dispatchers.IO) {
                        processRecord(t)
                    }
                }.awaitAll()
            }
        } else {
            input.Records.map { t ->
                processRecord(t)
            }
        }

        return input.Records.forEach {
            processRecord(it)
        }
    }

    protected open suspend fun processRecord(record: KinesisRecord) {}
}

@Serializable
public data class BatchItemFailuresResponse(
    val batchItemFailures: List<BatchItemFailure>
)

@Serializable
public data class BatchItemFailure(
    val itemIdentifier: String
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