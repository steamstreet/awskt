package com.steamstreet.awskt.kinesis

import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.StaticCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class KinesisHarness {
    val requests = mutableListOf<HttpRequestData>()
    val bodies = mutableListOf<String>()
}

internal fun harnessKinesis(
    harness: KinesisHarness,
    responder: (Int) -> Pair<String, HttpStatusCode>,
): Kinesis {
    var call = 0
    val engine = MockEngine { request ->
        harness.requests += request
        harness.bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        val (body, status) = responder(call++)
        respond(body, status)
    }
    return DefaultKinesis(
        AwsServiceClient(
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET", "TOKEN")),
            endpoint = resolveEndpoint("kinesis", "us-west-2", "https://kinesis.us-west-2.amazonaws.com"),
            region = "us-west-2",
            protocol = KINESIS_PROTOCOL,
            clock = { 1_700_000_000_000 },
            random = { 1.0 },
            sleep = {},
        ),
    )
}

private fun bodyJson(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

private const val STREAM_ARN = "arn:aws:kinesis:us-west-2:123456789012:stream/my-stream"
private const val PUT_OK = """{"ShardId":"shardId-000000000000","SequenceNumber":"49590338271490"}"""

class KinesisProtocolTest {

    @Test
    fun targetsKinesisOverAwsJson1_1() = runTest {
        val h = KinesisHarness()
        harnessKinesis(h) { PUT_OK to HttpStatusCode.OK }
            .putRecord(PutRecordRequest("hi".encodeToByteArray(), "pk", streamArn = STREAM_ARN))

        val request = h.requests.single()
        // The API date is how Kinesis spells its target prefix.
        assertEquals("Kinesis_20131202.PutRecord", request.headers["X-Amz-Target"])
        // 1.1 — not the 1.0 that DynamoDB and SQS use. Getting this wrong is a 400 on every call.
        assertTrue(
            request.body.contentType.toString().contains("application/x-amz-json-1.1"),
            "expected AWS-JSON 1.1, got ${request.body.contentType}",
        )
    }

    @Test
    fun signsAgainstTheKinesisService() = runTest {
        val h = KinesisHarness()
        harnessKinesis(h) { PUT_OK to HttpStatusCode.OK }
            .putRecord(PutRecordRequest("hi".encodeToByteArray(), "pk", streamArn = STREAM_ARN))

        val auth = h.requests.single().headers["Authorization"] ?: ""
        assertTrue(auth.contains("/us-west-2/kinesis/aws4_request"), "unexpected credential scope: $auth")
    }
}

class KinesisBlobTest {

    @Test
    fun encodesRecordDataAsBase64() = runTest {
        val h = KinesisHarness()
        harnessKinesis(h) { PUT_OK to HttpStatusCode.OK }
            .putRecord(PutRecordRequest("hello".encodeToByteArray(), "pk", streamArn = STREAM_ARN))

        val sent = bodyJson(h.bodies.single())
        assertEquals(Base64.Default.encode("hello".encodeToByteArray()), sent["Data"]!!.jsonPrimitive.content)
        assertEquals("pk", sent["PartitionKey"]!!.jsonPrimitive.content)
        assertEquals(STREAM_ARN, sent["StreamARN"]!!.jsonPrimitive.content)
    }

    @Test
    fun decodesRecordDataFromBase64() = runTest {
        val payload = Base64.Default.encode("world".encodeToByteArray())
        val h = KinesisHarness()
        val response = harnessKinesis(h) {
            """{"Records":[{"SequenceNumber":"1","Data":"$payload","PartitionKey":"pk"}],
                "NextShardIterator":"next-1","MillisBehindLatest":0}""" to HttpStatusCode.OK
        }.getRecords(GetRecordsRequest("iter-1"))

        assertEquals("world", response.records.single().data.decodeToString())
        assertEquals("next-1", response.nextShardIterator)
    }

    @Test
    fun roundTripsArbitraryBytes() = runTest {
        // Not valid UTF-8 — proves the blob path is byte-transparent rather than string-based.
        val bytes = byteArrayOf(0, -1, 127, -128, 42)
        val h = KinesisHarness()
        harnessKinesis(h) { PUT_OK to HttpStatusCode.OK }
            .putRecord(PutRecordRequest(bytes, "pk", streamArn = STREAM_ARN))

        val encoded = bodyJson(h.bodies.single())["Data"]!!.jsonPrimitive.content
        assertTrue(Base64.Default.decode(encoded).contentEquals(bytes))
    }
}

class KinesisShardIteratorTest {

    @Test
    fun sendsIteratorTypeAndSequenceNumber() = runTest {
        val h = KinesisHarness()
        harnessKinesis(h) { """{"ShardIterator":"iter-1"}""" to HttpStatusCode.OK }
            .getShardIterator(
                GetShardIteratorRequest(
                    shardId = "shardId-000000000000",
                    shardIteratorType = ShardIteratorType.AT_SEQUENCE_NUMBER,
                    streamArn = STREAM_ARN,
                    startingSequenceNumber = "49590338271490",
                ),
            )

        val sent = bodyJson(h.bodies.single())
        assertEquals("AT_SEQUENCE_NUMBER", sent["ShardIteratorType"]!!.jsonPrimitive.content)
        assertEquals("49590338271490", sent["StartingSequenceNumber"]!!.jsonPrimitive.content)
        assertEquals("shardId-000000000000", sent["ShardId"]!!.jsonPrimitive.content)
    }

    @Test
    fun aClosedAndDrainedShardReportsNullNextIterator() = runTest {
        val h = KinesisHarness()
        val response = harnessKinesis(h) {
            """{"Records":[],"MillisBehindLatest":0}""" to HttpStatusCode.OK
        }.getRecords(GetRecordsRequest("iter-1"))

        // The only signal that a shard is finished — an empty Records list is not one.
        assertNull(response.nextShardIterator)
        assertTrue(response.records.isEmpty())
    }
}

class KinesisErrorTest {

    @Test
    fun mapsExpiredIterator() = runTest {
        val h = KinesisHarness()
        assertFailsWith<ExpiredIteratorException> {
            harnessKinesis(h) {
                """{"__type":"ExpiredIteratorException","message":"Iterator expired"}""" to
                    HttpStatusCode.BadRequest
            }.getRecords(GetRecordsRequest("stale"))
        }
    }

    @Test
    fun mapsResourceNotFound() = runTest {
        val h = KinesisHarness()
        assertFailsWith<ResourceNotFoundException> {
            harnessKinesis(h) {
                """{"__type":"ResourceNotFoundException","message":"Stream not found"}""" to
                    HttpStatusCode.BadRequest
            }.getShardIterator(
                GetShardIteratorRequest("s-0", ShardIteratorType.LATEST, streamName = "nope"),
            )
        }
    }

    @Test
    fun anUnrecognisedCodeStillArrivesAsAKinesisException() = runTest {
        val h = KinesisHarness()
        val failure = assertFailsWith<KinesisException> {
            harnessKinesis(h) {
                """{"__type":"SomeFutureException","message":"who knows"}""" to HttpStatusCode.BadRequest
            }.getRecords(GetRecordsRequest("iter-1"))
        }
        assertEquals("SomeFutureException", failure.code)
    }
}

class PutRecordsAllTest {

    private fun entries(vararg keys: String) =
        keys.map { PutRecordsRequestEntry(it.encodeToByteArray(), it) }

    @Test
    fun resubmitsOnlyTheFailedEntries() = runTest {
        val h = KinesisHarness()
        val remaining = harnessKinesis(h) { call ->
            when (call) {
                0 -> """{"FailedRecordCount":1,"Records":[
                        {"ShardId":"s","SequenceNumber":"1"},
                        {"ErrorCode":"ProvisionedThroughputExceededException","ErrorMessage":"slow down"},
                        {"ShardId":"s","SequenceNumber":"3"}]}""" to HttpStatusCode.OK

                else -> """{"FailedRecordCount":0,"Records":[
                        {"ShardId":"s","SequenceNumber":"2"}]}""" to HttpStatusCode.OK
            }
        }.putRecordsAll(entries("a", "b", "c"), streamArn = STREAM_ARN)

        assertTrue(remaining.isEmpty(), "everything should have been written by the second attempt")
        assertEquals(2, h.bodies.size)

        // The retry carries only the failed entry, not the whole batch.
        val retried = bodyJson(h.bodies[1])["Records"]!!.jsonArray
        assertEquals(1, retried.size)
        assertEquals("b", retried.single().jsonObject["PartitionKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun returnsEntriesStillFailingAfterMaxAttempts() = runTest {
        val h = KinesisHarness()
        val remaining = harnessKinesis(h) {
            """{"FailedRecordCount":1,"Records":[
                {"ErrorCode":"ProvisionedThroughputExceededException","ErrorMessage":"slow down"}]}""" to
                HttpStatusCode.OK
        }.putRecordsAll(entries("a"), streamArn = STREAM_ARN, maxAttempts = 2)

        assertEquals(2, h.bodies.size)
        assertEquals(1, remaining.size)
        assertEquals("ProvisionedThroughputExceededException", remaining.single().errorCode)
    }

    @Test
    fun chunksToFiveHundredEntriesPerCall() = runTest {
        val h = KinesisHarness()
        harnessKinesis(h) { """{"FailedRecordCount":0,"Records":[]}""" to HttpStatusCode.OK }
            .putRecordsAll(entries(*Array(501) { "k$it" }), streamArn = STREAM_ARN)

        assertEquals(2, h.bodies.size)
        assertEquals(500, bodyJson(h.bodies[0])["Records"]!!.jsonArray.size)
        assertEquals(1, bodyJson(h.bodies[1])["Records"]!!.jsonArray.size)
    }

    @Test
    fun anEmptyListSendsNothing() = runTest {
        val h = KinesisHarness()
        val remaining = harnessKinesis(h) { PUT_OK to HttpStatusCode.OK }
            .putRecordsAll(emptyList(), streamArn = STREAM_ARN)

        assertTrue(remaining.isEmpty())
        assertTrue(h.bodies.isEmpty())
    }
}

class ReadRecordsTest {

    private fun page(sequence: String, next: String?) = buildString {
        append("""{"Records":[{"SequenceNumber":"$sequence",""")
        append(""""Data":"${Base64.Default.encode("r$sequence".encodeToByteArray())}","PartitionKey":"pk"}]""")
        append(if (next != null) ""","NextShardIterator":"$next"}""" else "}")
    }

    @Test
    fun followsNextShardIteratorUntilTheShardIsDrained() = runTest {
        val h = KinesisHarness()
        val records = harnessKinesis(h) { call ->
            when (call) {
                0 -> page("1", "iter-2") to HttpStatusCode.OK
                1 -> page("2", "iter-3") to HttpStatusCode.OK
                else -> page("3", null) to HttpStatusCode.OK
            }
        }.readRecords("iter-1")

        assertEquals(listOf("1", "2", "3"), records.map { it.sequenceNumber })
        assertEquals(listOf("r1", "r2", "r3"), records.map { it.data.decodeToString() })
    }

    @Test
    fun stopsAtTheLimit() = runTest {
        val h = KinesisHarness()
        val records = harnessKinesis(h) { call ->
            page("${call + 1}", "iter-${call + 2}") to HttpStatusCode.OK
        }.readRecords("iter-1", limit = 2)

        assertEquals(2, records.size)
    }

    @Test
    fun stopsOnAnEmptyPage() = runTest {
        val h = KinesisHarness()
        val records = harnessKinesis(h) { call ->
            if (call == 0) page("1", "iter-2") to HttpStatusCode.OK
            else """{"Records":[],"NextShardIterator":"iter-3"}""" to HttpStatusCode.OK
        }.readRecords("iter-1")

        assertEquals(listOf("1"), records.map { it.sequenceNumber })
    }
}
