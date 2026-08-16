package com.steamstreet.aws.lambda

import com.steamstreet.dynamokt.DynamoStreamEvent
import com.steamstreet.dynamokt.DynamoStreamRecords

/**
 * Handle dynamo streams
 */
public abstract class DynamoStreamHandler : InputLambda<DynamoStreamRecords>(
    DynamoStreamRecords.serializer()
), DynamoEventHandler {
    /**
     * If true, the handleRecord method is called asynchronously for each item.
     */
    protected var async: Boolean = false

    /**
     * Handle a batch of records. Default implementation calls handleRecord for each
     *
     * ### Behaviour change: an async failure now fails the invocation
     *
     * The previous implementation dispatched each record with a bare `launch` inside
     * `coroutineScope`, which meant an exception from one record cancelled its siblings and
     * propagated — but only by accident of structured concurrency, and with the surviving records'
     * outcomes lost. It now runs through the common [processStreamRecords], which collects a result
     * per record and rethrows the first failure after all of them have been attempted. Records that
     * would previously have been cancelled mid-flight now complete.
     */
    override suspend fun handle(input: DynamoStreamRecords) {
        val results = input.processStreamRecords(async) { record ->
            handleRecord(record)
        }
        results.filterNotNull().firstOrNull()?.let { throw it }
    }
}

public interface DynamoEventHandler {
    public suspend fun handleRecord(record: DynamoStreamEvent)
}
