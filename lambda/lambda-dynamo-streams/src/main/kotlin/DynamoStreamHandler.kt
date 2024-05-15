package com.steamstreet.aws.lambda

import com.steamstreet.dynamokt.DynamoStreamEvent
import com.steamstreet.dynamokt.DynamoStreamRecords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
     */
    override suspend fun handle(input: DynamoStreamRecords) {
        coroutineScope {
            input.records.forEach {
                if (async) {
                    launch(Dispatchers.IO) {
                        handleRecord(it)
                    }
                } else {
                    handleRecord(it)
                }
            }
        }
    }
}

public interface DynamoEventHandler {
    public suspend fun handleRecord(record: DynamoStreamEvent)
}