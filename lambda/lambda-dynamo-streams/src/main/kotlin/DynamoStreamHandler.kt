package com.steamstreet.aws.lambda

import com.steamstreet.dynamokt.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Handle dynamo streams
 */
public abstract class DynamoStreamHandler : InputLambda<DynamoStreamRecords>(
    DynamoStreamRecords.serializer()
) {
    /**
     * Get the session to be used. Can be the same session for all. If null is returned,
     * the onItemUpdate function will NOT be called, and implementations should instead
     * override handleRecord.
     */
    protected open fun dynamoKtSession(): DynamoKtSession? = null

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

    /**
     * Default implementation calls this for each record. Parses the event to old
     * and new Items and calls onItemUpdate.
     */
    protected open suspend fun handleRecord(record: DynamoStreamEvent) {
        val session = dynamoKtSession()
        if (session != null) {
            val (old, new) = record.oldAndNew(session)
            onItemUpdate(old, new, record)
        }
    }

    /**
     * A database item has been updated.
     */
    protected open suspend fun onItemUpdate(old: Item?, new: Item?, record: DynamoStreamEvent) {}
}

/**
 * DynamoKt specific stream handler. Takes care of session handling.
 */
public abstract class DynamoKtStreamHandler(public val dynamoKt: DynamoKt) :
    DynamoStreamHandler() {

    /**
     * Constructs the session from the dynamoKt instance.
     */
    override fun dynamoKtSession(): DynamoKtSession? {
        return dynamoKt.session()
    }
}