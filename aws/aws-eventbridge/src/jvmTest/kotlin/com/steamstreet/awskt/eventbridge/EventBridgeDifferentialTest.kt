package com.steamstreet.awskt.eventbridge

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
// The builder-DSL form (`client.putEvents { }`) is an extension function, not a member. Without
// this import the compiler silently binds to the `(request)` overload and the lambda becomes a
// Function0 — the same trap called out in aws-dynamodb's harness.
import aws.sdk.kotlin.services.eventbridge.putEvents
import aws.smithy.kotlin.runtime.client.ProtocolRequestInterceptorContext
import aws.smithy.kotlin.runtime.http.HttpBody
import aws.smithy.kotlin.runtime.http.interceptors.HttpInterceptor
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.net.url.Url
import com.steamstreet.awskt.core.awsJson
import com.sun.net.httpserver.HttpServer
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import aws.sdk.kotlin.services.eventbridge.model.PutEventsRequestEntry as SdkPutEventsRequestEntry

/**
 * The M3 differential harness, both halves, applied to `PutEvents`.
 *
 * The technique is `aws-dynamodb`'s and the rationale carries over unchanged: the `aws-signing`
 * vectors prove the *signature* is right, and this proves the *payload* is. A wrong `@SerialName`
 * produces a request that signs perfectly and means something else to EventBridge — and on the
 * response side it does not error at all, it silently yields `null`, so a caller sees a batch in
 * which every entry appears to have published when in fact none did.
 *
 * That response-side failure mode is worse here than it is for DynamoDB. `PutEvents` reports
 * per-entry failures inside an HTTP 200 body, so `ErrorCode` deserializing to null is
 * indistinguishable from success at every layer above it.
 *
 * jvmTest-only: `aws.sdk.kotlin` never reaches `commonMain`.
 */
class EventBridgeDifferentialTest {

    // -- Request half ---------------------------------------------------------------------------

    private class ShortCircuit : RuntimeException("captured; not sending")

    private class CapturingInterceptor : HttpInterceptor {
        var captured: HttpRequest? = null

        override fun readBeforeTransmit(context: ProtocolRequestInterceptorContext<Any, HttpRequest>) {
            captured = context.protocolRequest
            throw ShortCircuit()
        }
    }

    private fun sdkBody(block: suspend (EventBridgeClient) -> Unit): JsonElement {
        val interceptor = CapturingInterceptor()
        val client = EventBridgeClient {
            region = "us-west-2"
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = "AKIDEXAMPLE"
                secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
            }
            interceptors += interceptor
        }

        client.use {
            runBlocking {
                try {
                    block(it)
                } catch (e: Throwable) {
                    if (generateSequence(e) { it.cause }.none { it is ShortCircuit }) throw e
                }
            }
        }

        val body = interceptor.captured?.body ?: fail("the SDK request was never captured")
        val bytes = (body as? HttpBody.Bytes)?.bytes() ?: fail("expected an in-memory body, got $body")
        return Json.parseToJsonElement(bytes.decodeToString())
    }

    private fun ourBody(entries: List<PutEventsEntry>): JsonElement =
        Json.parseToJsonElement(awsJson.encodeToString(PutEventsRequest.serializer(), PutEventsRequest(entries)))

    @Test
    fun putEventsRequestMatchesTheSdk() {
        val ours = listOf(
            PutEventsEntry(
                eventBusName = "my-bus",
                source = "my.source",
                detailType = "OrderPlaced",
                detail = """{"orderId":"7","total":12.5}""",
            ),
            PutEventsEntry(
                eventBusName = "arn:aws:events:us-west-2:1234:event-bus/other",
                source = "other.source",
                detailType = "OrderCancelled",
                detail = """{"orderId":"8"}""",
            ),
        )

        assertEquals(
            sdkBody { sdk ->
                sdk.putEvents {
                    entries = ours.map { e ->
                        SdkPutEventsRequestEntry {
                            eventBusName = e.eventBusName
                            source = e.source
                            detailType = e.detailType
                            detail = e.detail
                        }
                    }
                }
            },
            ourBody(ours),
            "PutEvents request body",
        )
    }

    /**
     * An entry with only `Source` set: the omitted fields must be *absent*, not `null`. With
     * `encodeDefaults = true` we would emit `"Detail":null` and EventBridge rejects it, so this is
     * the case where a serializer-configuration mistake actually shows up.
     */
    @Test
    fun sparseEntryMatchesTheSdk() {
        val ours = listOf(PutEventsEntry(source = "only.source"))

        assertEquals(
            sdkBody { sdk -> sdk.putEvents { entries = listOf(SdkPutEventsRequestEntry { source = "only.source" }) } },
            ourBody(ours),
            "sparse PutEvents entry",
        )
    }

    // -- Response half --------------------------------------------------------------------------

    /** Serves [body] with [status] on loopback for the duration of [block]. */
    private fun <T> withCannedResponse(status: Int, body: String, block: (String) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            // The request body must be drained, or the SDK's connection pool can stall on close.
            exchange.requestBody.use { it.readBytes() }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/x-amz-json-1.1")
            exchange.responseHeaders.add("x-amzn-RequestId", "canned-request-id")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun <T> throughSdk(body: String, call: suspend (EventBridgeClient) -> T): T =
        withCannedResponse(200, body) { url ->
            EventBridgeClient {
                region = "us-west-2"
                endpointUrl = Url.parse(url)
                credentialsProvider = StaticCredentialsProvider {
                    accessKeyId = "AKIDEXAMPLE"
                    secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
                }
            }.use { client -> runBlocking { call(client) } }
        }

    private fun <T> throughOurs(body: String, call: suspend (EventBridgeApi) -> T): T =
        runBlocking {
            harnessEventBridge(EventBridgeHarness()) { body to HttpStatusCode.OK }.use { call(it) }
        }

    /**
     * Rendered by hand on both sides rather than through our own serializer: reusing the
     * production encoder to check the production decoder lets a symmetric mistake cancel itself
     * out, where two independently written renderers cannot.
     */
    private fun render(failedEntryCount: Int, entries: List<Triple<String?, String?, String?>>): JsonElement =
        JsonObject(
            mapOf(
                "FailedEntryCount" to JsonPrimitive(failedEntryCount),
                "Entries" to JsonArray(
                    entries.map { (id, code, message) ->
                        JsonObject(
                            buildMap {
                                put("EventId", id?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
                                put("ErrorCode", code?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
                                put("ErrorMessage", message?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
                            },
                        )
                    },
                ),
            ),
        )

    private val mixedBatch = """
        {"FailedEntryCount":1,"Entries":[
          {"EventId":"11111111-2222-3333-4444-555555555555"},
          {"ErrorCode":"ThrottlingException","ErrorMessage":"Rate exceeded"}
        ]}
    """.trimIndent()

    @Test
    fun putEventsResponseMatchesTheSdk() {
        val sdk = throughSdk(mixedBatch) { client ->
            client.putEvents { entries = listOf(SdkPutEventsRequestEntry { source = "s" }) }
        }
        val ours = throughOurs(mixedBatch) { it.putEvents(listOf(PutEventsEntry(source = "s"))) }

        assertEquals(
            render(
                sdk.failedEntryCount ?: 0,
                sdk.entries.orEmpty().map { Triple(it.eventId, it.errorCode, it.errorMessage) },
            ),
            render(
                ours.failedEntryCount,
                ours.entries.orEmpty().map { Triple(it.eventId, it.errorCode, it.errorMessage) },
            ),
            "PutEvents response",
        )
    }

    /**
     * The all-failed batch, still on an HTTP 200. If our deserializer lost `ErrorCode`, this is
     * the assertion that catches it — `failedEntryCount` alone would not, since a client that
     * dropped every field would still report 2.
     */
    @Test
    fun fullyFailedBatchMatchesTheSdk() {
        val body = """
            {"FailedEntryCount":2,"Entries":[
              {"ErrorCode":"InternalFailure","ErrorMessage":"a"},
              {"ErrorCode":"MalformedDetail","ErrorMessage":"b"}
            ]}
        """.trimIndent()

        val sdk = throughSdk(body) { client ->
            client.putEvents { entries = listOf(SdkPutEventsRequestEntry { source = "s" }) }
        }
        val ours = throughOurs(body) { it.putEvents(listOf(PutEventsEntry(source = "s"))) }

        assertEquals(
            render(
                sdk.failedEntryCount ?: 0,
                sdk.entries.orEmpty().map { Triple(it.eventId, it.errorCode, it.errorMessage) },
            ),
            render(
                ours.failedEntryCount,
                ours.entries.orEmpty().map { Triple(it.eventId, it.errorCode, it.errorMessage) },
            ),
            "fully failed PutEvents response",
        )
    }
}
