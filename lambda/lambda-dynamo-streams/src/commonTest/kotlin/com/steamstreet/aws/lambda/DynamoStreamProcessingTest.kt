package com.steamstreet.aws.lambda

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers [parseDynamoStreamPayload] — the part of this module with real behaviour in it, and the
 * reason it was worth making common: one handler is expected to accept three different envelopes.
 */
@OptIn(ExperimentalEncodingApi::class)
class DynamoStreamProcessingTest {
    private fun streamEvent(eventId: String, key: String) = """
        {"eventID":"$eventId","eventName":"INSERT","eventSource":"aws:dynamodb","awsRegion":"us-east-1",
         "dynamodb":{"ApproximateCreationDateTime":1755302400,"Keys":{"pk":{"S":"$key"}},
         "SequenceNumber":"unset","SizeBytes":1,"StreamViewType":"NEW_AND_OLD_IMAGES"}}
    """.trimIndent().replace("\n", "")

    private fun directPayload(vararg events: String) =
        Json.parseToJsonElement("""{"Records":[${events.joinToString(",")}]}""")

    private fun kinesisPayload(vararg pairs: Pair<String, String>) = Json.parseToJsonElement(
        """{"Records":[${
            pairs.joinToString(",") { (sequenceNumber, event) ->
                """{"kinesis":{"sequenceNumber":"$sequenceNumber","data":"${
                    Base64.encode(event.encodeToByteArray())
                }"}}"""
            }
        }]}"""
    )

    private fun sqsRedrivePayload(shardId: String) = Json.parseToJsonElement(
        """{"Records":[{"eventSource":"aws:sqs","body":${
            JsonPrimitive(
                """{"KinesisBatchInfo":{"shardId":"$shardId","startSequenceNumber":"1",
                   "endSequenceNumber":"3","batchSize":3,"streamArn":"arn:stream"}}"""
                    .trimIndent().replace("\n", "")
            )
        }}]}"""
    )

    @Test
    fun `a direct stream record is identified by its eventID`() = runTest {
        val parsed = parseDynamoStreamPayload(directPayload(streamEvent("evt-1", "a"), streamEvent("evt-2", "b")))

        assertEquals(listOf("evt-1", "evt-2"), parsed.map { it.identifier })
    }

    @Test
    fun `a kinesis record is identified by its sequence number`() = runTest {
        val parsed = parseDynamoStreamPayload(kinesisPayload("seq-1" to streamEvent("evt-1", "a")))

        assertEquals(listOf("seq-1"), parsed.map { it.identifier })
    }

    @Test
    fun `the sequence number is copied from the kinesis envelope onto the dynamo event`() = runTest {
        // The DynamoDB event inside a Kinesis payload carries no sequence number of its own, so it
        // has to be copied down from the envelope. Without it, a partial batch failure could not
        // name the record.
        val parsed = parseDynamoStreamPayload(kinesisPayload("seq-42" to streamEvent("evt-1", "a")))

        assertEquals("seq-42", parsed.single().dynamoEvent.dynamodb.sequenceNumber)
    }

    @Test
    fun `an SQS redrive record is resolved through the supplied fetcher`() = runTest {
        var requestedShard: String? = null

        val parsed = parseDynamoStreamPayload(sqsRedrivePayload("shard-7")) { batchInfo ->
            requestedShard = batchInfo.shardId
            listOf(RecordInfo(streamJson.decodeFromString(streamEvent("evt-9", "a")), "seq-9"))
        }

        assertEquals("shard-7", requestedShard)
        assertEquals(listOf("seq-9"), parsed.map { it.identifier })
    }

    @Test
    fun `an SQS redrive record is skipped when no fetcher is supplied`() = runTest {
        // The native default, and what the JVM handler does when built without a KinesisClient.
        assertTrue(parseDynamoStreamPayload(sqsRedrivePayload("shard-7")).isEmpty())
    }

    @Test
    fun `a payload with no Records yields nothing`() = runTest {
        assertTrue(parseDynamoStreamPayload(Json.parseToJsonElement("{}")).isEmpty())
    }

    @Test
    fun `only failed records are reported when batching failures`() = runTest {
        val parsed = parseDynamoStreamPayload(
            directPayload(streamEvent("evt-1", "a"), streamEvent("evt-2", "b"), streamEvent("evt-3", "c"))
        )

        val response = parsed.processWithFailures(enableBatchItemFailures = true, logFailures = null) { record ->
            if (record.eventID == "evt-2") error("failed")
        }

        assertEquals(listOf("evt-2"), response.batchItemFailures.map { it.itemIdentifier })
    }

    @Test
    fun `a failure throws when batch item failures are disabled`() = runTest {
        val parsed = parseDynamoStreamPayload(directPayload(streamEvent("evt-1", "a")))

        assertFailsWith<IllegalStateException> {
            parsed.processWithFailures(enableBatchItemFailures = false, logFailures = null) {
                error("failed")
            }
        }
    }
}
