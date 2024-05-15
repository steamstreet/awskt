package com.steamstreet.aws.lambda

import com.steamstreet.aws.lambda.kinesis.KinesisRecords
import com.steamstreet.dynamokt.DynamoStreamEvent
import com.steamstreet.dynamokt.DynamoStreamRecords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Handles changes to the Content Services table that are published as Kinesis records.
 */
public open class DynamoKinesisStreamHandler(
    /**
     * If true, the handleRecord method is called asynchronously for each item.
     */
    private val async: Boolean
) : InputLambda<KinesisRecords>(KinesisRecords.serializer()), DynamoEventHandler {
    private val jsonDecode = Json {
        ignoreUnknownKeys = true
    }

    override suspend fun handle(input: KinesisRecords) {
        val dynamoRecords = input.Records.map {
            val decodedData = it.kinesis.decodedData()
            jsonDecode.decodeFromString<DynamoStreamEvent>(decodedData)
        }
        handle(DynamoStreamRecords(dynamoRecords))
    }

    /**
     * Handle a batch of records. Default implementation calls handleRecord for each
     */
    protected open suspend fun handle(input: DynamoStreamRecords) {
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

    override suspend fun handleRecord(record: DynamoStreamEvent) {}
}