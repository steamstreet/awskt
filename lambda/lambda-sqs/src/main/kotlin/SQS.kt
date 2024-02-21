package com.steamstreet.aws.sqs

import com.steamstreet.aws.lambda.IOLambda
import com.steamstreet.aws.lambda.InputLambda
import com.steamstreet.awskt.logging.logWarning
import com.steamstreet.awskt.logging.mdcContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
public class SQSRecord(
    public val messageId: String,
    public val receiptHandle: String,
    public val body: String,
    public val attributes: SQSRecordAttributes? = null,
    public val messageAttributes: JsonObject? = null,
    public val md5OfBody: String? = null,
    public val eventSource: String,
    public val eventSourceARN: String,
    public val awsRegion: String
)

@Serializable
public class SQSRecordAttributes(
    public val ApproximateReceiveCount: String? = null,
    public val SentTimestamp: String? = null,
    public val SenderId: String? = null,
    public val ApproximateFirstReceiveTimestamp: String? = null
)

@Serializable
public class SQSEvent(
    public val Records: List<SQSRecord>
)


@Serializable
public class BatchResponse(
    public val batchItemFailures: List<RecordResponse>
)

@Serializable
public class RecordResponse(
    public val itemIdentifier: String
)

/**
 * Handle SQS events directly from the body data.
 */
public abstract class SQSRawHandler : InputLambda<SQSEvent>(SQSEvent.serializer()) {
    override suspend fun handle(input: SQSEvent) {
        input.Records.forEach {
            it.apply {
                handleBody(this.body)
            }
        }
    }

    context(SQSRecord)
    public abstract suspend fun handleBody(body: String)
}

/**
 * Base class for a lambda function that handles SQS messages.
 */
public abstract class SQSHandler<T>(private val serializer: KSerializer<T>) : SQSRawHandler() {
    private val json = Json {
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
    public abstract suspend fun handleMessage(message: T)
}

/**
 * Implementation of an SQS batch handler that can handle batches of records, but must return
 * a boolean for each to indicate if the processing was successful.
 */
public abstract class SQSBatchHandler<T>(private val serializer: KSerializer<T>) : IOLambda<SQSEvent, BatchResponse>(
    SQSEvent.serializer(), BatchResponse.serializer()
) {
    private val json = Json {
        ignoreUnknownKeys = true
    }
    protected var logExceptions: Boolean = true
    protected var async: Boolean = false

    override suspend fun handle(input: SQSEvent): BatchResponse {
        val events = input.Records.map {
            json.decodeFromString(serializer, it.body)
        }

        val results: List<Boolean> = with(input) {
            handleEvents(events)
        }

        check(results.size == input.Records.size) {
            "Invalid response list"
        }
        return input.Records.filterIndexed { index, _ ->
            !results[index]
        }.map {
            it.messageId
        }.map {
            RecordResponse(it)
        }.let {
            BatchResponse(it)
        }
    }

    /**
     * Handle all events. Returns a list of booleans that must match with the events list.
     * For each event, returning true indicates that the event handling was a success.
     */
    context(SQSEvent)
    public open suspend fun handleEvents(events: List<T>): List<Boolean> {
        return if (async) {
            coroutineScope {
                events.mapIndexed { index, t ->
                    val record = Records[index]
                    async(Dispatchers.IO) {
                        handleRecord(record, t)
                    }
                }.awaitAll()
            }
        } else {
            events.mapIndexed { index, t ->
                handleRecord(Records[index], t)
            }
        }
    }

    private suspend fun handleRecord(sqsRecord: SQSRecord, message: T): Boolean {
        return with(sqsRecord) {
            mdcContext("sqs-message-id" to this.messageId) {
                try {
                    handleMessage(message)
                    true
                } catch (t: Throwable) {
                    if (logExceptions) {
                        logWarning("SQS Processing Failed", t)
                    }
                    false
                }
            }
        }
    }

    /**
     * Handle a record one at a time. Throw an exception if there is a failure and the batch response
     * will handle it appropriate.
     */
    context(SQSRecord)
    public open suspend fun handleMessage(message: T) {
    }

}