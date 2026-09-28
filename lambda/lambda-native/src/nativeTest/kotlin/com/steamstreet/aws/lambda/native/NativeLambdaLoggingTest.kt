package com.steamstreet.aws.lambda.native

import com.steamstreet.aws.lambda.redactCredentials
import com.steamstreet.awskt.logging.JsonLogPublisher
import com.steamstreet.awskt.logging.log
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val BASE_URL = "http://runtime.invalid/2018-06-01"

@Serializable
private data class Order(val id: String)

@Serializable
private data class Receipt(val orderId: String, val total: Int)

/**
 * The request and response lines that `nativeLambdaIO` and `nativeLambdaInput` write, driven
 * through [LambdaRuntime] so that the lines are asserted with the request id the runtime adds.
 */
class NativeLambdaLoggingTest {
    private val posts = mutableListOf<HttpRequestData>()

    private fun client(event: String, requestId: String = "req-1"): HttpClient =
        HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                respond(
                    content = event,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        "Lambda-Runtime-Aws-Request-Id" to listOf(requestId),
                        "Lambda-Runtime-Deadline-Ms" to listOf((nowEpochMillis() + 30_000).toString())
                    )
                )
            } else {
                posts += request
                respond(content = "", status = HttpStatusCode.Accepted)
            }
        })

    /** Runs one invocation of [handler] against [event], returning the log lines as JSON objects. */
    private suspend fun invoke(
        event: String,
        handler: suspend (String) -> String,
        requestId: String = "req-1"
    ): List<JsonObject> {
        val lines = mutableListOf<String>()
        val original = log.publisher
        log.publisher = JsonLogPublisher { lines += it }
        try {
            LambdaRuntime(handler, client(event, requestId), logError = {}, exitProcess = { error("exit $it") })
                .processNextInvocation(BASE_URL)
        } finally {
            log.publisher = original
        }
        return lines.map { Json.parseToJsonElement(it).jsonObject }
    }

    private fun List<JsonObject>.withMessage(message: String): List<JsonObject> =
        filter { it["message"]?.jsonPrimitive?.content == message }

    private fun io(
        logIncoming: Boolean = true,
        logOutgoing: Boolean = true,
        logRedactor: ((JsonElement) -> JsonElement)? = null,
        handler: suspend (Order) -> Receipt
    ) = ioHandler(serializer<Order>(), serializer<Receipt>(), logIncoming, logOutgoing, logRedactor, handler)

    @Test
    fun logsTheRequestAndResponseAsJsonWithTheRequestId() = runBlocking {
        val lines = invoke("""{"id":"o-1"}""", io { Receipt(it.id, 42) }, requestId = "req-9")

        val request = lines.withMessage("Request received").single()
        assertEquals("INFO", request["level"]?.jsonPrimitive?.content)
        assertEquals("req-9", request["requestId"]?.jsonPrimitive?.content)
        // Nested JSON, as the JVM's raw marker writes it, not an escaped string.
        assertEquals(Json.parseToJsonElement("""{"id":"o-1"}"""), request["input"])

        val response = lines.withMessage("Lambda response sent").single()
        assertEquals("req-9", response["requestId"]?.jsonPrimitive?.content)
        assertEquals(Json.parseToJsonElement("""{"orderId":"o-1","total":42}"""), response["event"])

        assertEquals("""{"orderId":"o-1","total":42}""", (posts.single().body as TextContent).text)
    }

    @Test
    fun logIncomingFalseSuppressesTheRequestButNotTheResponse() = runBlocking {
        val lines = invoke("""{"id":"secret"}""", io(logIncoming = false) { Receipt(it.id, 1) })

        assertTrue(lines.withMessage("Request received").isEmpty())
        assertEquals(1, lines.withMessage("Lambda response sent").size)
    }

    @Test
    fun logOutgoingFalseSuppressesTheResponseButNotTheRequest() = runBlocking {
        val lines = invoke("""{"id":"o-2"}""", io(logOutgoing = false) { Receipt("secret", 1) })

        assertEquals(1, lines.withMessage("Request received").size)
        assertTrue(lines.withMessage("Lambda response sent").isEmpty())
        assertTrue(lines.none { "secret" in it.toString() })
        assertEquals("""{"orderId":"secret","total":1}""", (posts.single().body as TextContent).text)
    }

    @Test
    fun aSuppressedRequestIsStillLoggedWhenTheHandlerFails() = runBlocking {
        val lines = invoke("""{"id":"o-3"}""", io(logIncoming = false) { error("boom") })

        val request = lines.withMessage("Request received").single()
        assertEquals(Json.parseToJsonElement("""{"id":"o-3"}"""), request["input"])
        assertEquals(1, lines.withMessage("Handler failed").size)
        assertTrue(lines.withMessage("Lambda response sent").isEmpty())
        assertContains(posts.single().url.toString(), "/error")
    }

    @Test
    fun aRequestThatIsNotJsonIsLoggedAsAString() = runBlocking {
        val lines = invoke("not json", io { Receipt(it.id, 0) })

        // Logged once, before decoding, and not again when decoding fails.
        val request = lines.withMessage("Request received").single()
        assertEquals(JsonPrimitive("not json"), request["input"])
        assertContains(posts.single().url.toString(), "/error")
    }

    @Test
    fun inputHandlerLogsTheRequestAndNoResponse() = runBlocking {
        var seen: Order? = null
        val lines = invoke("""{"id":"o-4"}""", inputHandler(serializer<Order>(), true, null) { seen = it })

        assertEquals(Order("o-4"), seen)
        assertEquals(Json.parseToJsonElement("""{"id":"o-4"}"""), lines.withMessage("Request received").single()["input"])
        assertTrue(lines.withMessage("Lambda response sent").isEmpty())
        assertEquals("null", (posts.single().body as TextContent).text)
    }

    @Test
    fun inputHandlerHonoursLogIncoming() = runBlocking {
        val lines = invoke("""{"id":"o-5"}""", inputHandler(serializer<Order>(), false, null) { })

        assertTrue(lines.isEmpty())
    }

    @Test
    fun aPublisherThatThrowsDoesNotFailTheInvocation() = runBlocking {
        val original = log.publisher
        log.publisher = JsonLogPublisher { throw RuntimeException("publisher broke") }
        try {
            LambdaRuntime(io { Receipt(it.id, 7) }, client("""{"id":"o-6"}"""), logError = {}, exitProcess = { error("exit $it") })
                .processNextInvocation(BASE_URL)
        } finally {
            log.publisher = original
        }

        assertContains(posts.single().url.toString(), "/response")
    }

    @Test
    fun theRedactorAppliesToTheLoggedRequestAndResponseOnly() = runBlocking {
        var seen: Order? = null
        val redactId: (JsonElement) -> JsonElement = { redactCredentials(it, setOf("id", "orderId")) }
        val lines = invoke("""{"id":"tok"}""", io(logRedactor = redactId) { seen = it; Receipt(it.id, 3) })

        assertEquals(Order("tok"), seen)
        assertEquals(Json.parseToJsonElement("""{"id":"[REDACTED]"}"""), lines.withMessage("Request received").single()["input"])
        assertEquals(
            Json.parseToJsonElement("""{"orderId":"[REDACTED]","total":3}"""),
            lines.withMessage("Lambda response sent").single()["event"]
        )
        assertEquals("""{"orderId":"tok","total":3}""", (posts.single().body as TextContent).text)
    }

    @Test
    fun theRedactorAppliesToARequestLoggedOnFailure() = runBlocking {
        val redactId: (JsonElement) -> JsonElement = { redactCredentials(it, setOf("id")) }
        val lines = invoke("""{"id":"tok"}""", io(logIncoming = false, logRedactor = redactId) { error("boom") })

        assertEquals(Json.parseToJsonElement("""{"id":"[REDACTED]"}"""), lines.withMessage("Request received").single()["input"])
    }
}
