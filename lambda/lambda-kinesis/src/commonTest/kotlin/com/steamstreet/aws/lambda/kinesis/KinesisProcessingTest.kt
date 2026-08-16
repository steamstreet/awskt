package com.steamstreet.aws.lambda.kinesis

import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class KinesisProcessingTest {
    private fun records(vararg payloads: String) = KinesisRecords(
        payloads.mapIndexed { index, payload ->
            KinesisRecord(
                kinesis = KinesisDetails(
                    kinesisSchemaVersion = "1.0",
                    partitionKey = "key-$index",
                    sequenceNumber = "seq-$index",
                    data = Base64.encode(payload.encodeToByteArray()),
                    approximateArrivalTimestamp = 0.0
                ),
                eventSource = "aws:kinesis",
                eventVersion = "1.0",
                eventID = "event-$index",
                eventName = "aws:kinesis:record"
            )
        }
    )

    @Test
    fun `each record is handled exactly once`() = runTest {
        // The regression this guards: the previous KinesisHandler ran the batch, then fell into a
        // trailing forEach that ran it again, so every record reached the handler twice.
        val seen = mutableListOf<String>()

        records("one", "two", "three").processRecords { record ->
            seen.add(record.kinesis.decodedData())
        }

        assertEquals(listOf("one", "two", "three"), seen)
    }

    @Test
    fun `concurrent dispatch maps each failure back to its own record`() = runTest {
        // No shared mutable state in the handler on purpose: the concurrent path runs on a
        // multi-threaded dispatcher on Native, so a `mutableListOf` written from the handler would
        // be a data race in the test itself. Everything asserted here comes off the return value.
        val response = records("one", "two", "three").processRecordsWithFailures(async = true) { record ->
            if (record.kinesis.decodedData() != "two") error("failed")
        }

        assertEquals(listOf("seq-0", "seq-2"), response.batchItemFailures.map { it.itemIdentifier })
    }

    @Test
    fun `decodedData round-trips non-ascii payloads`() = runTest {
        assertEquals("héllo — ok", records("héllo — ok").Records.single().kinesis.decodedData())
    }

    @Test
    fun `a failure propagates when not reporting item failures`() = runTest {
        assertFailsWith<IllegalStateException> {
            records("one", "two").processRecords { error("nope") }
        }
    }

    @Test
    fun `only failed records are reported by sequence number`() = runTest {
        val response = records("one", "two", "three").processRecordsWithFailures { record ->
            if (record.kinesis.decodedData() == "two") error("failed")
        }

        assertEquals(listOf("seq-1"), response.batchItemFailures.map { it.itemIdentifier })
    }

    @Test
    fun `a batch with no failures reports none`() = runTest {
        val response = records("one").processRecordsWithFailures { }
        assertTrue(response.batchItemFailures.isEmpty())
    }
}
