package com.steamstreet.aws.sqs

import com.steamstreet.aws.lambda.InputLambda
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
class SQSRecord(
    val messageId: String,
    val receiptHandle: String,
    val body: String,
    val attributes: SQSRecordAttributes? = null,
    val messageAttributes: JsonObject? = null,
    val md5OfBody: String? = null,
    val eventSource: String,
    val eventSourceARN: String,
    val awsRegion: String
)

@Serializable
class SQSRecordAttributes(
    val ApproximateReceiveCount: String? = null,
    val SentTimestamp: String? = null,
    val SenderId: String? = null,
    val ApproximateFirstReceiveTimestamp: String? = null
)

@Serializable
class SQSEvent(
    val Records: List<SQSRecord>
)

abstract class SQSRawHandler : InputLambda<SQSEvent>(SQSEvent.serializer()) {
    override suspend fun handle(input: SQSEvent) {
        input.Records.forEach {
            it.apply {
                handleBody(this.body)
            }
        }
    }

    context(SQSRecord)
    abstract suspend fun handleBody(body: String)
}

/**
 * Base class for a lambda function that handles SQS messages.
 */
abstract class SQSHandler<T>(private val serializer: KSerializer<T>) : SQSRawHandler() {
    val json = Json {
        ignoreUnknownKeys = true
    }

    context(SQSRecord) override suspend fun handleBody(body: String) {
        val messagePayload = json.decodeFromString(serializer, body)
        handleMessage(messagePayload)
    }

    /**
     * Handle an individual message
     */
    context(SQSRecord)
    abstract suspend fun handleMessage(message: T)
}