package com.steamstreet.aws.sqs

import com.steamstreet.aws.lambda.IOLambda
import com.steamstreet.aws.lambda.InputLambda
import kotlinx.serialization.KSerializer

/**
 * Handle SQS events directly from the body data.
 */
public abstract class SQSRawHandler : InputLambda<SQSEvent>(SQSEvent.serializer()) {
    override suspend fun handle(input: SQSEvent) {
        input.processRawMessages { body ->
            handleBody(body)
        }
    }

    context(record: SQSRecord)
    public abstract suspend fun handleBody(body: String)
}

/**
 * Base class for a lambda function that handles SQS messages.
 */
public abstract class SQSHandler<T>(private val serializer: KSerializer<T>) : SQSRawHandler() {
    context(record: SQSRecord) override suspend fun handleBody(body: String) {
        handleMessage(sqsMessageJson.decodeFromString(serializer, body))
    }

    /**
     * Handle an individual message
     */
    context(record: SQSRecord)
    public abstract suspend fun handleMessage(message: T)
}

/**
 * Implementation of an SQS batch handler that can handle batches of records, but must return
 * a boolean for each to indicate if the processing was successful.
 *
 * [handleEvents] is still the extension point for a subclass that wants to see the whole batch at
 * once; the default implementation is the common [processBatch], so a subclass that only overrides
 * [handleMessage] runs the same loop a native `sqsBatchLambda` runs.
 */
public abstract class SQSBatchHandler<T>(private val serializer: KSerializer<T>) : IOLambda<SQSEvent, BatchResponse>(
    SQSEvent.serializer(), BatchResponse.serializer()
) {
    protected var logExceptions: Boolean = true
    protected var async: Boolean = false

    override suspend fun handle(input: SQSEvent): BatchResponse {
        val events = input.Records.map {
            sqsMessageJson.decodeFromString(serializer, it.body)
        }

        val results: List<Boolean> = with(input) {
            handleEvents(events)
        }

        return input.batchResponse(results)
    }

    /**
     * Handle all events. Returns a list of booleans that must match with the events list.
     * For each event, returning true indicates that the event handling was a success.
     */
    context(event: SQSEvent)
    public open suspend fun handleEvents(events: List<T>): List<Boolean> =
        event.runMessages(events, async, logExceptions) { message ->
            handleMessage(message)
        }

    /**
     * Handle a record one at a time. Throw an exception if there is a failure and the batch response
     * will handle it appropriate.
     */
    context(record: SQSRecord)
    public open suspend fun handleMessage(message: T) {
    }
}
