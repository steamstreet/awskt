package com.steamstreet.aws.lambda

import com.steamstreet.dynamokt.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * DynamoKt specific stream handler. Takes care of session handling, and works for both
 * Kinesis or direct lambda streams.
 */
public abstract class DynamoKtStreamHandler(
    dynamoKt: DynamoKt,
    private val async: Boolean
) : SuspendingLambda {
    /**
     * Get the session to be used. Can be the same session for all. If null is returned,
     * the onItemUpdate function will NOT be called, and implementations should instead
     * override handleRecord.
     */
    protected val dynamoKtSession: DynamoKtSession = dynamoKt.session()

    private val jsonDecode: Json = Json {
        ignoreUnknownKeys = true
    }

    override var logIncoming: Boolean = false
    override var logOutgoing: Boolean = false

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun handle(input: InputStream, output: OutputStream) {
        input.readIncoming(logIncoming) {
            val payload = jsonDecode.parseToJsonElement(it)

            val records = payload.jsonObject.get("Records")?.jsonArray

            val dynamoRecords = records?.mapNotNull {
                val kinesis = it.jsonObject.get("kinesis")
                if (kinesis != null) {
                    val dataString = kinesis.jsonObject.get("data")?.jsonPrimitive?.contentOrNull
                    if (dataString != null) {
                        val decodedData = String(Base64.decode(dataString))
                        jsonDecode.decodeFromString<DynamoStreamEvent>(decodedData)
                    } else {
                        null
                    }
                } else {
                    jsonDecode.decodeFromJsonElement<DynamoStreamEvent>(it)
                }
            }.orEmpty()

            handleRecords(dynamoRecords)
        }
    }

    /**
     * Handle the records in this request.
     */
    protected suspend fun handleRecords(dynamoRecords: List<DynamoStreamEvent>) {
        coroutineScope {
            dynamoRecords.forEach {
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
    protected suspend fun handleRecord(record: DynamoStreamEvent) {
        val (old, new) = record.oldAndNew(dynamoKtSession)
        onItemUpdate(old, new, record)
    }

    /**
     * A database item has been updated.
     */
    protected open suspend fun onItemUpdate(old: Item?, new: Item?, record: DynamoStreamEvent) {}
}