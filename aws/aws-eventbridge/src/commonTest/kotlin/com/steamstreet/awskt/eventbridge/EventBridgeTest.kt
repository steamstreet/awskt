package com.steamstreet.awskt.eventbridge

import com.steamstreet.awskt.core.AwsCallEvent
import com.steamstreet.awskt.core.AwsCallObserver
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class EventBridgeHarness {
    val requests = mutableListOf<HttpRequestData>()
    val bodies = mutableListOf<String>()
}

internal fun harnessEventBridge(
    harness: EventBridgeHarness,
    responder: (Int) -> Pair<String, HttpStatusCode>,
): EventBridge {
    var call = 0
    val engine = MockEngine { request ->
        harness.requests += request
        harness.bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        val (body, status) = responder(call++)
        respond(body, status)
    }
    return DefaultEventBridge(
        AwsServiceClient(
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET", "TOKEN")),
            endpoint = resolveEndpoint("events", "us-west-2", "https://events.us-west-2.amazonaws.com"),
            region = "us-west-2",
            protocol = EVENTBRIDGE_PROTOCOL,
            clock = { 1_700_000_000_000 },
            random = { 1.0 },
            sleep = {},
        ),
    )
}

private fun bodyJson(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

private const val OK_ONE = """{"FailedEntryCount":0,"Entries":[{"EventId":"abc-123"}]}"""

class PutEventsRequestTest {

    @Test
    fun targetsAwsEventsPutEventsOverAwsJson1_1() = runTest {
        val h = EventBridgeHarness()
        harnessEventBridge(h) { OK_ONE to HttpStatusCode.OK }
            .putEvents(listOf(PutEventsEntry(eventBusName = "bus", source = "src", detailType = "T", detail = "{}")))

        val request = h.requests.single()
        assertEquals("AWSEvents.PutEvents", request.headers["X-Amz-Target"])
        // Content type lives on the body, not in `headers` — Ktor moves it there.
        // 1.1, not 1.0 — DynamoDB's dialect. Getting this wrong is a 400 from EventBridge.
        assertEquals("application/x-amz-json-1.1", request.body.contentType?.toString())
    }

    @Test
    fun serializesEntriesWithPascalCaseFieldNames() = runTest {
        val h = EventBridgeHarness()
        harnessEventBridge(h) { OK_ONE to HttpStatusCode.OK }.putEvents(
            listOf(
                PutEventsEntry(
                    eventBusName = "my-bus",
                    source = "my.source",
                    detailType = "OrderPlaced",
                    detail = """{"orderId":"7"}""",
                ),
            ),
        )

        val entry = bodyJson(h.bodies.single())["Entries"]!!.jsonArray.single().jsonObject
        assertEquals("my-bus", entry["EventBusName"]?.jsonPrimitive?.content)
        assertEquals("my.source", entry["Source"]?.jsonPrimitive?.content)
        assertEquals("OrderPlaced", entry["DetailType"]?.jsonPrimitive?.content)
        // Detail is a JSON *string* on the wire, not a nested object. Emitting it as an object is
        // the natural mistake and EventBridge rejects it.
        assertEquals("""{"orderId":"7"}""", entry["Detail"]?.jsonPrimitive?.content)
    }

    /**
     * `encodeDefaults = false` means a null field is absent rather than `"Source":null`. The
     * distinction is wire-correctness, not tidiness: an explicit null is a validation error.
     */
    @Test
    fun omitsNullFieldsEntirely() = runTest {
        val h = EventBridgeHarness()
        harnessEventBridge(h) { OK_ONE to HttpStatusCode.OK }
            .putEvents(listOf(PutEventsEntry(source = "only-source")))

        val entry = bodyJson(h.bodies.single())["Entries"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("Source"), entry.keys)
    }

    @Test
    fun preservesBatchOrder() = runTest {
        val h = EventBridgeHarness()
        harnessEventBridge(h) { OK_ONE to HttpStatusCode.OK }.putEvents(
            (1..3).map { PutEventsEntry(source = "s$it", detailType = "T$it", detail = "{}") },
        )

        val entries = bodyJson(h.bodies.single())["Entries"]!!.jsonArray
        assertEquals(listOf("s1", "s2", "s3"), entries.map { it.jsonObject["Source"]!!.jsonPrimitive.content })
    }
}

class PutEventsResponseTest {

    @Test
    fun parsesEventIds() = runTest {
        val response = harnessEventBridge(EventBridgeHarness()) {
            """{"FailedEntryCount":0,"Entries":[{"EventId":"a"},{"EventId":"b"}]}""" to HttpStatusCode.OK
        }.putEvents(listOf(PutEventsEntry(source = "s"), PutEventsEntry(source = "s")))

        assertEquals(0, response.failedEntryCount)
        assertEquals(listOf("a", "b"), response.entries?.map { it.eventId })
    }

    /**
     * **The case that matters most in this module.** EventBridge reports per-entry failures in the
     * body of an HTTP 200. A client that only checks the status line reports success on a batch
     * that was entirely rejected, and the events are silently gone.
     */
    @Test
    fun partialFailureIsReportedOnHttp200() = runTest {
        val response = harnessEventBridge(EventBridgeHarness()) {
            """
            {"FailedEntryCount":1,"Entries":[
              {"EventId":"ok-1"},
              {"ErrorCode":"ThrottlingException","ErrorMessage":"Rate exceeded"}
            ]}
            """.trimIndent() to HttpStatusCode.OK
        }.putEvents(listOf(PutEventsEntry(source = "s"), PutEventsEntry(source = "s")))

        assertEquals(1, response.failedEntryCount)
        assertEquals("ok-1", response.entries?.get(0)?.eventId)
        assertNull(response.entries?.get(1)?.eventId)
        assertEquals("ThrottlingException", response.entries?.get(1)?.errorCode)
        assertEquals("Rate exceeded", response.entries?.get(1)?.errorMessage)
    }

    @Test
    fun wholeBatchFailureIsStillHttp200() = runTest {
        val response = harnessEventBridge(EventBridgeHarness()) {
            """{"FailedEntryCount":2,"Entries":[
                 {"ErrorCode":"InternalFailure"},{"ErrorCode":"InternalFailure"}]}""" to HttpStatusCode.OK
        }.putEvents(listOf(PutEventsEntry(source = "s"), PutEventsEntry(source = "s")))

        assertEquals(2, response.failedEntryCount)
        assertTrue(response.entries!!.all { it.eventId == null })
    }

    @Test
    fun toleratesUnknownResponseFields() = runTest {
        val response = harnessEventBridge(EventBridgeHarness()) {
            """{"FailedEntryCount":0,"Entries":[{"EventId":"a","SomethingNew":1}],"Extra":true}""" to
                HttpStatusCode.OK
        }.putEvents(listOf(PutEventsEntry(source = "s")))

        assertEquals("a", response.entries?.single()?.eventId)
    }
}

class EventBridgeErrorTest {

    @Test
    fun mapsResourceNotFound() = runTest {
        val e = assertFailsWith<ResourceNotFoundException> {
            harnessEventBridge(EventBridgeHarness()) {
                """{"__type":"ResourceNotFoundException","message":"bus not found"}""" to
                    HttpStatusCode.BadRequest
            }.putEvents(listOf(PutEventsEntry(source = "s")))
        }
        assertEquals(400, e.statusCode)
    }

    @Test
    fun mapsValidationException() = runTest {
        assertFailsWith<ValidationException> {
            harnessEventBridge(EventBridgeHarness()) {
                """{"__type":"ValidationException","message":"bad detail"}""" to HttpStatusCode.BadRequest
            }.putEvents(listOf(PutEventsEntry(source = "s")))
        }
    }

    /** Unknown codes must stay typed rather than being swallowed. */
    @Test
    fun unknownCodeFallsThroughToBaseException() = runTest {
        val e = assertFailsWith<EventBridgeException> {
            harnessEventBridge(EventBridgeHarness()) {
                """{"__type":"SomeFutureException","message":"?"}""" to HttpStatusCode.BadRequest
            }.putEvents(listOf(PutEventsEntry(source = "s")))
        }
        assertEquals("SomeFutureException", e.code)
    }

    /**
     * A server-returned 500 **is** retried, and that is correct.
     *
     * [com.steamstreet.awskt.core.OperationSafety] governs only the case where we cannot tell
     * whether the request reached AWS. A 500 is not that case: AWS answered, so the batch was
     * rejected rather than half-applied, and replaying it is what the AWS SDKs do too. Asserting
     * "NOT_IDEMPOTENT means never retry" would have been asserting a contract this client does not
     * have.
     */
    @Test
    fun serverErrorIsRetried() = runTest {
        val h = EventBridgeHarness()
        assertFailsWith<EventBridgeException> {
            harnessEventBridge(h) {
                """{"__type":"InternalFailure","message":"boom"}""" to HttpStatusCode.InternalServerError
            }.putEvents(listOf(PutEventsEntry(source = "s")))
        }
        assertEquals(4, h.requests.size, "a 500 is unambiguous and retryable")
    }

    /**
     * The case `NOT_IDEMPOTENT` actually protects: the bytes may have reached EventBridge and the
     * socket died before the response came back. Replaying would publish the whole batch twice,
     * and every rule targeting it would fire twice, so the failure is surfaced instead.
     */
    @Test
    fun ambiguousTransportFailureIsNotRetried() = runTest {
        val h = EventBridgeHarness()
        assertFailsWith<RuntimeException> {
            harnessEventBridge(h) { throw RuntimeException("read timed out") }
                .putEvents(listOf(PutEventsEntry(source = "s")))
        }
        assertEquals(1, h.requests.size, "a replayed PutEvents double-publishes the batch")
    }
}

/**
 * `EventBridgeConfig.observer` reaches the transport that does the work.
 *
 * Through the real `EventBridge { }` factory, not the harness above: the harness builds its own
 * `AwsServiceClient` and would pass whether or not the factory forwarded the field.
 */
class EventBridgeObserverWiringTest {

    @Test
    fun theConfiguredObserverSeesTheAttempt() = runTest {
        val events = mutableListOf<AwsCallEvent>()
        val engine = MockEngine { respond(OK_ONE, HttpStatusCode.OK) }

        val bus = EventBridge {
            region = "us-west-2"
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET"))
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false }
            observer = AwsCallObserver { events += it }
        }

        bus.putEvents(listOf(PutEventsEntry(source = "src", detailType = "T", detail = "{}")))

        val event = events.single()
        assertEquals(AwsCallEvent.Outcome.SUCCESS, event.outcome)
        assertEquals("PutEvents", event.operation)
        assertEquals(1, event.attempt)
        assertEquals(200, event.statusCode)
    }
}
