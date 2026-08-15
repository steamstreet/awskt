package com.steamstreet.awskt.sqs

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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class SqsHarness {
    val requests = mutableListOf<HttpRequestData>()
    val bodies = mutableListOf<String>()
}

internal fun harnessSqs(
    harness: SqsHarness,
    responder: (Int) -> Pair<String, HttpStatusCode>,
): Sqs {
    var call = 0
    val engine = MockEngine { request ->
        harness.requests += request
        harness.bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        val (body, status) = responder(call++)
        respond(body, status)
    }
    return DefaultSqs(
        AwsServiceClient(
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET", "TOKEN")),
            endpoint = resolveEndpoint("sqs", "us-west-2", "https://sqs.us-west-2.amazonaws.com"),
            region = "us-west-2",
            protocol = SQS_PROTOCOL,
            clock = { 1_700_000_000_000 },
            random = { 1.0 },
            sleep = {},
        ),
    )
}

private fun bodyJson(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

private const val QUEUE = "https://sqs.us-west-2.amazonaws.com/123456789012/my-queue"
private const val SEND_OK = """{"MessageId":"m-1","MD5OfMessageBody":"abc"}"""

class SqsProtocolTest {

    @Test
    fun targetsAmazonSqsOverAwsJson1_0() = runTest {
        val h = SqsHarness()
        harnessSqs(h) { SEND_OK to HttpStatusCode.OK }.sendMessage(SendMessageRequest(QUEUE, "hello"))

        val request = h.requests.single()
        assertEquals("AmazonSQS.SendMessage", request.headers["X-Amz-Target"])
        // 1.0, DynamoDB's dialect — not the 1.1 that EventBridge, KMS and Secrets Manager use.
        // Content type lives on the body, not in `headers` — Ktor moves it there.
        assertEquals("application/x-amz-json-1.0", request.body.contentType?.toString())
    }

    /**
     * The queue URL is a **body field**, and the request goes to the ordinary regional endpoint.
     * Routing to the queue URL's own host is what the old query protocol did, and getting this
     * wrong would break every cross-account queue.
     */
    @Test
    fun sendsToTheRegionalEndpointWithTheQueueUrlInTheBody() = runTest {
        val h = SqsHarness()
        harnessSqs(h) { SEND_OK to HttpStatusCode.OK }.sendMessage(
            SendMessageRequest("https://sqs.us-west-2.amazonaws.com/999999999999/other-account", "hi"),
        )

        val request = h.requests.single()
        assertEquals("sqs.us-west-2.amazonaws.com", request.url.host)
        assertEquals("/", request.url.encodedPath)
        assertEquals(
            "https://sqs.us-west-2.amazonaws.com/999999999999/other-account",
            bodyJson(h.bodies.single())["QueueUrl"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun omitsNullFieldsEntirely() = runTest {
        val h = SqsHarness()
        harnessSqs(h) { SEND_OK to HttpStatusCode.OK }.sendMessage(SendMessageRequest(QUEUE, "hello"))

        assertEquals(setOf("QueueUrl", "MessageBody"), bodyJson(h.bodies.single()).keys)
    }

    @Test
    fun sendsMessageAttributesWithBinaryValuesBase64Encoded() = runTest {
        val h = SqsHarness()
        harnessSqs(h) { SEND_OK to HttpStatusCode.OK }.sendMessage(
            SendMessageRequest(
                QUEUE, "hello",
                messageAttributes = mapOf(
                    "kind" to MessageAttributeValue("String", stringValue = "order"),
                    "blob" to MessageAttributeValue("Binary", binaryValue = "hello".encodeToByteArray()),
                ),
            ),
        )

        val attributes = bodyJson(h.bodies.single())["MessageAttributes"]!!.jsonObject
        assertEquals("order", attributes["kind"]!!.jsonObject["StringValue"]?.jsonPrimitive?.content)
        assertEquals("aGVsbG8=", attributes["blob"]!!.jsonObject["BinaryValue"]?.jsonPrimitive?.content)
    }

    @Test
    fun parsesReceivedMessages() = runTest {
        val messages = harnessSqs(SqsHarness()) {
            """{"Messages":[{"MessageId":"m-1","ReceiptHandle":"rh-1","Body":"payload",
               "Attributes":{"ApproximateReceiveCount":"3"}}]}""" to HttpStatusCode.OK
        }.receiveMessage(ReceiveMessageRequest(QUEUE)).messages

        val message = messages.single()
        assertEquals("m-1", message.messageId)
        assertEquals("rh-1", message.receiptHandle)
        assertEquals("payload", message.body)
        assertEquals("3", message.attributes["ApproximateReceiveCount"])
    }

    /**
     * "No messages" is the most common outcome of a long poll, and SQS omits the field entirely
     * rather than sending an empty array. A nullable list would put `?: emptyList()` in every
     * consumer loop in every project.
     */
    @Test
    fun anEmptyReceiveIsAnEmptyListRatherThanNull() = runTest {
        val messages = harnessSqs(SqsHarness()) { "{}" to HttpStatusCode.OK }
            .receiveMessage(ReceiveMessageRequest(QUEUE)).messages
        assertTrue(messages.isEmpty())
    }

    @Test
    fun deleteAndVisibilityOperationsReturnNothingAndTargetCorrectly() = runTest {
        val h = SqsHarness()
        val sqs = harnessSqs(h) { "{}" to HttpStatusCode.OK }
        sqs.deleteMessage(DeleteMessageRequest(QUEUE, "rh-1"))
        sqs.changeMessageVisibility(ChangeMessageVisibilityRequest(QUEUE, "rh-1", 60))

        assertEquals("AmazonSQS.DeleteMessage", h.requests[0].headers["X-Amz-Target"])
        assertEquals("AmazonSQS.ChangeMessageVisibility", h.requests[1].headers["X-Amz-Target"])
        assertEquals(60, bodyJson(h.bodies[1])["VisibilityTimeout"]?.jsonPrimitive?.content?.toInt())
    }
}

/**
 * Safety is a property of the request, not of the operation — the same construction
 * `aws-dynamodb` uses for `writeSafety`.
 *
 * These assert it *behaviourally*, through the retry loop, because `OperationSafety` is not
 * observable on the returned value. A `NOT_IDEMPOTENT` call must not replay an ambiguous transport
 * failure; an `IDEMPOTENT` one must.
 */
class SqsRetrySafetyTest {

    /** A 500 is TRANSIENT in `aws-core`'s status table, and a *response* rather than an ambiguous send. */
    @Test
    fun retriesTransientServerErrorsRegardlessOfDeduplication() = runTest {
        val h = SqsHarness()
        harnessSqs(h) { call ->
            if (call == 0) """{"__type":"InternalError"}""" to HttpStatusCode.InternalServerError
            else SEND_OK to HttpStatusCode.OK
        }.sendMessage(SendMessageRequest(QUEUE, "hello"))

        assertEquals(2, h.bodies.size, "a 500 should have been retried")
    }

    @Test
    fun aDeduplicatedSendIsMarkedIdempotent() = runTest {
        // Asserted through the request the client built rather than through a private field: a
        // FIFO send carries the id, and that id is what SQS de-duplicates a replay against.
        val h = SqsHarness()
        harnessSqs(h) { SEND_OK to HttpStatusCode.OK }.sendMessage(
            SendMessageRequest(QUEUE, "hello", messageDeduplicationId = "d-1", messageGroupId = "g-1"),
        )
        val body = bodyJson(h.bodies.single())
        assertEquals("d-1", body["MessageDeduplicationId"]?.jsonPrimitive?.content)
        assertEquals("g-1", body["MessageGroupId"]?.jsonPrimitive?.content)
    }
}

class SqsErrorMappingTest {

    private fun errorBody(code: String) = """{"__type":"com.amazonaws.sqs#$code","message":"nope"}"""

    @Test
    fun mapsKnownCodesOntoTypedExceptions() = runTest {
        suspend fun failingWith(code: String): Throwable = runCatching {
            harnessSqs(SqsHarness()) { errorBody(code) to HttpStatusCode.BadRequest }
                .sendMessage(SendMessageRequest(QUEUE, "hello"))
        }.exceptionOrNull()!!

        assertTrue(failingWith("QueueDoesNotExist") is QueueDoesNotExistException)
        assertTrue(failingWith("ReceiptHandleIsInvalid") is ReceiptHandleIsInvalidException)
        assertTrue(failingWith("MessageNotInflight") is MessageNotInflightException)
        assertTrue(failingWith("BatchEntryIdsNotDistinct") is BatchEntryIdsNotDistinctException)
        // Deliberately not named UnsupportedOperationException, which would shadow the stdlib type
        // inside this package and silently change what `catch` means.
        assertTrue(failingWith("UnsupportedOperation") is UnsupportedQueueOperationException)
    }

    /** All seven `Kms*` codes collapse onto one type, with [code] preserving which. */
    @Test
    fun collapsesTheKmsFamilyOntoOneTypeWithoutLosingTheCode() = runTest {
        val failure = assertFailsWith<QueueEncryptionKeyException> {
            harnessSqs(SqsHarness()) { errorBody("KmsDisabled") to HttpStatusCode.BadRequest }
                .sendMessage(SendMessageRequest(QUEUE, "hello"))
        }
        assertEquals("KmsDisabled", failure.code)
    }

    @Test
    fun unknownCodesFallThroughToTheBaseType() = runTest {
        val failure = assertFailsWith<SqsException> {
            harnessSqs(SqsHarness()) { errorBody("SomethingAwsAddedLater") to HttpStatusCode.BadRequest }
                .sendMessage(SendMessageRequest(QUEUE, "hello"))
        }
        assertEquals("SomethingAwsAddedLater", failure.code)
        assertTrue(failure.cause != null, "the transport exception was discarded")
    }
}

class SendMessagesAllTest {

    private fun ok(ids: List<String>) =
        """{"Successful":[${ids.joinToString(",") { """{"Id":"$it","MessageId":"m-$it"}""" }}]}"""

    private fun entries(count: Int) =
        (1..count).map { SendMessageBatchRequestEntry("e$it", "body-$it") }

    @Test
    fun chunksAtTenEntries() = runTest {
        val h = SqsHarness()
        val all = entries(25)
        val results = harnessSqs(h) { call ->
            val ids = when (call) {
                0 -> (1..10); 1 -> (11..20); else -> (21..25)
            }.map { "e$it" }
            ok(ids) to HttpStatusCode.OK
        }.sendMessagesAll(QUEUE, all)

        assertEquals(3, h.bodies.size, "25 entries should be split into three requests")
        assertEquals(10, bodyJson(h.bodies[0])["Entries"]!!.jsonArray.size)
        assertEquals(5, bodyJson(h.bodies[2])["Entries"]!!.jsonArray.size)
        assertEquals(all.map { it.id }, results.map { it.id }, "results should be in request order")
    }

    /**
     * `SenderFault = false` is SQS saying the failure was its own, so the entry is resubmitted.
     * Note the transport never sees this — it arrived inside an HTTP 200.
     */
    @Test
    fun resubmitsTransientFailuresAndReturnsCompleteResults() = runTest {
        val h = SqsHarness()
        val results = harnessSqs(h) { call ->
            if (call == 0) {
                """{"Successful":[{"Id":"e1","MessageId":"m-e1"}],
                   "Failed":[{"Id":"e2","SenderFault":false,"Code":"InternalError"}]}""" to HttpStatusCode.OK
            } else {
                ok(listOf("e2")) to HttpStatusCode.OK
            }
        }.sendMessagesAll(QUEUE, entries(2))

        assertEquals(2, h.bodies.size, "the transient failure should have been resubmitted")
        assertEquals(1, bodyJson(h.bodies[1])["Entries"]!!.jsonArray.size, "only the failure resends")
        assertEquals(listOf("e1", "e2"), results.map { it.id })
    }

    /** `SenderFault = true` means the entry itself is wrong; resubmitting spends budget on a certainty. */
    @Test
    fun stopsImmediatelyOnASenderFault() = runTest {
        val h = SqsHarness()
        val failure = assertFailsWith<SendMessageBatchPartialFailureException> {
            harnessSqs(h) {
                """{"Successful":[{"Id":"e1","MessageId":"m-e1"}],
                   "Failed":[{"Id":"e2","SenderFault":true,"Code":"InvalidMessageContents"}]}""" to
                    HttpStatusCode.OK
            }.sendMessagesAll(QUEUE, entries(2))
        }

        assertEquals(1, h.bodies.size, "a sender fault must not be resubmitted")
        assertEquals(200, failure.statusCode)
        assertEquals(listOf("e1"), failure.succeeded.map { it.id })
        assertEquals(listOf("e2"), failure.failed.map { it.first.id })
        assertContains(failure.message!!, "InvalidMessageContents")
    }

    /**
     * The partition is carried because the succeeded half is already on the queue: resubmitting the
     * original list would deliver those a second time.
     */
    @Test
    fun theFailureCarriesEnoughToRetrySafely() = runTest {
        val failure = assertFailsWith<SendMessageBatchPartialFailureException> {
            harnessSqs(SqsHarness()) {
                """{"Successful":[{"Id":"e1","MessageId":"m-e1"}],
                   "Failed":[{"Id":"e2","SenderFault":false,"Code":"InternalError"}]}""" to HttpStatusCode.OK
            }.sendMessagesAll(QUEUE, entries(2), maxRounds = 2)
        }

        // The failed half is the caller's own entry objects, handable straight back.
        assertEquals("body-2", failure.failed.single().first.messageBody)
    }

    /**
     * Duplicate ids across chunk boundaries are two requests that each look valid to SQS, and a
     * result that cannot be paired back up. Only a whole-list check catches it.
     */
    @Test
    fun rejectsDuplicateIdsAcrossTheWholeCallBeforeSendingAnything() = runTest {
        val h = SqsHarness()
        val duplicated = entries(10) + SendMessageBatchRequestEntry("e1", "again")

        val failure = assertFailsWith<IllegalArgumentException> {
            harnessSqs(h) { ok(emptyList()) to HttpStatusCode.OK }.sendMessagesAll(QUEUE, duplicated)
        }

        assertTrue(h.requests.isEmpty(), "nothing should have been sent")
        assertContains(failure.message!!, "e1")
    }

    @Test
    fun sendsNothingForAnEmptyList() = runTest {
        val h = SqsHarness()
        val results = harnessSqs(h) { "{}" to HttpStatusCode.OK }.sendMessagesAll(QUEUE, emptyList())
        assertTrue(results.isEmpty())
        assertTrue(h.requests.isEmpty(), "an empty batch should not reach the network")
    }
}

class DeleteMessagesAllTest {

    @Test
    fun deletesInChunksAndReturnsRequestOrder() = runTest {
        val h = SqsHarness()
        val entries = (1..12).map { DeleteMessageBatchRequestEntry("d$it", "rh-$it") }
        val results = harnessSqs(h) { call ->
            val ids = if (call == 0) (1..10) else (11..12)
            """{"Successful":[${ids.joinToString(",") { """{"Id":"d$it"}""" }}]}""" to HttpStatusCode.OK
        }.deleteMessagesAll(QUEUE, entries)

        assertEquals(2, h.bodies.size)
        assertEquals(entries.map { it.id }, results.map { it.id })
    }

    /**
     * A silently-failed delete is a message that comes back when its visibility timeout lapses and
     * is processed twice, hours later, with nothing connecting the two events.
     */
    @Test
    fun raisesRatherThanReportingSuccessOnAPartialDelete() = runTest {
        val failure = assertFailsWith<DeleteMessageBatchPartialFailureException> {
            harnessSqs(SqsHarness()) {
                """{"Successful":[{"Id":"d1"}],
                   "Failed":[{"Id":"d2","SenderFault":true,"Code":"ReceiptHandleIsInvalid"}]}""" to
                    HttpStatusCode.OK
            }.deleteMessagesAll(
                QUEUE,
                listOf(
                    DeleteMessageBatchRequestEntry("d1", "rh-1"),
                    DeleteMessageBatchRequestEntry("d2", "rh-2"),
                ),
            )
        }

        assertEquals(listOf("d1"), failure.succeeded.map { it.id })
        assertEquals(listOf("d2"), failure.failed.map { it.first.id })
        assertContains(failure.message!!, "redelivered")
    }
}

class SqsConvenienceTest {

    @Test
    fun receiveMessagesLongPollsByDefault() = runTest {
        val h = SqsHarness()
        harnessSqs(h) { "{}" to HttpStatusCode.OK }.receiveMessages(QUEUE)

        val body = bodyJson(h.bodies.single())
        // 20 rather than SQS's own default of 0: short polling bills for empty responses and adds
        // latency, and is almost never what a caller wants.
        assertEquals(20, body["WaitTimeSeconds"]?.jsonPrimitive?.content?.toInt())
        assertEquals(10, body["MaxNumberOfMessages"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun deleteMessageTakesAReceivedMessage() = runTest {
        val h = SqsHarness()
        harnessSqs(h) { "{}" to HttpStatusCode.OK }
            .deleteMessage(QUEUE, Message(messageId = "m-1", receiptHandle = "rh-1"))

        assertEquals("rh-1", bodyJson(h.bodies.single())["ReceiptHandle"]?.jsonPrimitive?.content)
    }

    @Test
    fun deletingAMessageWithNoReceiptHandleFailsWithAUsefulMessage() = runTest {
        val failure = assertFailsWith<IllegalArgumentException> {
            harnessSqs(SqsHarness()) { "{}" to HttpStatusCode.OK }
                .deleteMessage(QUEUE, Message(messageId = "m-1"))
        }
        assertContains(failure.message!!, "receipt handle")
    }

    @Test
    fun byteCarryingAttributeValuesCompareByContent() {
        assertEquals(
            MessageAttributeValue("Binary", binaryValue = "x".encodeToByteArray()),
            MessageAttributeValue("Binary", binaryValue = "x".encodeToByteArray()),
        )
        // A data class would print an identity hash that reads like content.
        val printed = MessageAttributeValue("Binary", binaryValue = "secret".encodeToByteArray()).toString()
        assertContains(printed, "6 bytes")
        assertFalse(printed.contains("secret"))
    }
}
