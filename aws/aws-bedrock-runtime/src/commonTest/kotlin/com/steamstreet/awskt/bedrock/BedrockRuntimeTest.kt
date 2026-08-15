package com.steamstreet.awskt.bedrock

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
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class BedrockHarness {
    val requests = mutableListOf<HttpRequestData>()
    val bodies = mutableListOf<String>()
}

internal fun harnessBedrock(
    harness: BedrockHarness,
    responder: (Int) -> Triple<ByteArray, HttpStatusCode, io.ktor.http.Headers>,
): BedrockRuntime {
    var call = 0
    val engine = MockEngine { request ->
        harness.requests += request
        harness.bodies += (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
        val (body, status, headers) = responder(call++)
        respond(body, status, headers)
    }
    return DefaultBedrockRuntime(
        AwsServiceClient(
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET", "TOKEN")),
            endpoint = resolveEndpoint(
                "bedrock-runtime", "us-west-2", "https://bedrock-runtime.us-west-2.amazonaws.com",
            ),
            region = "us-west-2",
            protocol = BEDROCK_RUNTIME_PROTOCOL,
            clock = { 1_700_000_000_000 },
            random = { 1.0 },
            sleep = {},
        ),
    )
}

internal fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    Triple(body.encodeToByteArray(), status, headersOf("Content-Type", "application/json"))

private fun bodyJson(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

private const val MODEL = "anthropic.claude-3-5-sonnet-20241022-v2:0"

private const val REPLY_OK = """{
  "output": {"message": {"role": "assistant", "content": [{"text": "Hello there"}]}},
  "stopReason": "end_turn",
  "usage": {"inputTokens": 12, "outputTokens": 3, "totalTokens": 15},
  "metrics": {"latencyMs": 421}
}"""

class ConverseProtocolTest {

    @Test
    fun postsToTheModelConversePathWithNoTargetHeader() = runTest {
        val h = BedrockHarness()
        harnessBedrock(h) { json(REPLY_OK) }
            .converse(ConverseRequest(MODEL, listOf(Message.user("hi"))))

        val request = h.requests.single()
        assertNull(request.headers["X-Amz-Target"])
        assertEquals("POST", request.method.value)
        // The `:` in the model id is percent-encoded; the path segments around it are not.
        assertEquals(
            "/model/anthropic.claude-3-5-sonnet-20241022-v2%3A0/converse",
            request.url.encodedPath,
        )
        assertEquals("application/json", request.body.contentType?.toString()?.substringBefore(";"))
    }

    /**
     * The reason the encoding is not ceremonial: an inference-profile ARN contains `/`, and it
     * occupies **one** path segment. Left raw it would split the path and address an operation that
     * does not exist.
     */
    @Test
    fun percentEncodesSlashesInsideAnArnModelId() = runTest {
        val h = BedrockHarness()
        val arn = "arn:aws:bedrock:us-west-2:1:inference-profile/us.anthropic.claude-v2"
        harnessBedrock(h) { json(REPLY_OK) }.converse(ConverseRequest(arn, listOf(Message.user("hi"))))

        val path = h.requests.single().url.encodedPath
        assertContains(path, "inference-profile%2Fus.anthropic.claude-v2")
        assertTrue(path.endsWith("/converse"))
        // Exactly three real segments: "", "model", "<encoded id>", "converse".
        assertEquals(4, path.split("/").size)
    }

    @Test
    fun serializesMessagesWithTheContentBlockUnionShape() = runTest {
        val h = BedrockHarness()
        harnessBedrock(h) { json(REPLY_OK) }.converse(
            ConverseRequest(
                modelId = MODEL,
                messages = listOf(Message.user("hi")),
                system = listOf(SystemContentBlock.text("Be brief.")),
                inferenceConfig = InferenceConfiguration(maxTokens = 100, temperature = 0.2f),
            ),
        )

        val body = bodyJson(h.bodies.single())
        val message = body["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", message["role"]?.jsonPrimitive?.content)
        // A union: an object with exactly one key naming the variant.
        val block = message["content"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("text"), block.keys)
        assertEquals("hi", block["text"]?.jsonPrimitive?.content)
        assertEquals("Be brief.", body["system"]!!.jsonArray.single().jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals(100, body["inferenceConfig"]!!.jsonObject["maxTokens"]?.jsonPrimitive?.content?.toInt())
        // modelId rides in the path and must not also appear in the body.
        assertNull(body["modelId"])
    }

    @Test
    fun parsesTheReplyAndItsUsage() = runTest {
        val response = harnessBedrock(BedrockHarness()) { json(REPLY_OK) }
            .converse(ConverseRequest(MODEL, listOf(Message.user("hi"))))

        assertEquals("Hello there", response.text)
        assertEquals(StopReason.END_TURN, response.stopReason)
        assertEquals(12, response.usage?.inputTokens)
        assertEquals(421, response.metrics?.latencyMs)
        assertEquals("assistant", response.message?.role)
    }

    @Test
    fun askIsASingleTurnShortcut() = runTest {
        val h = BedrockHarness()
        val answer = harnessBedrock(h) { json(REPLY_OK) }.ask(MODEL, "hi", system = "Be brief.")

        assertEquals("Hello there", answer)
        assertEquals("Be brief.", bodyJson(h.bodies.single())["system"]!!.jsonArray.single()
            .jsonObject["text"]?.jsonPrimitive?.content)
    }
}

/**
 * The union encoding, in both directions. A round trip is the test that matters: a multi-turn
 * conversation sends the assistant's previous blocks back, so anything that does not survive
 * encode→decode→encode breaks the second turn rather than the first.
 */
class ContentBlockRoundTripTest {

    private fun roundTrip(block: ContentBlock): ContentBlock {
        val encoded = Json.encodeToString(ContentBlockSerializer, block)
        return Json.decodeFromString(ContentBlockSerializer, encoded)
    }

    @Test
    fun textRoundTrips() {
        assertEquals(ContentBlock.Text("hi"), roundTrip(ContentBlock.Text("hi")))
    }

    @Test
    fun toolUseRoundTrips() {
        val block = ContentBlock.ToolUse(
            toolUseId = "tu-1",
            name = "get_weather",
            input = buildJsonObject { put("city", "Paris") },
        )
        assertEquals(block, roundTrip(block))
    }

    @Test
    fun toolResultRoundTrips() {
        val block = ContentBlock.ToolResult(
            toolUseId = "tu-1",
            content = listOf(ToolResultContent.json(buildJsonObject { put("tempC", 18) })),
            status = ToolResultStatus.SUCCESS,
        )
        assertEquals(block, roundTrip(block))
    }

    @Test
    fun imageRoundTripsWithBase64Bytes() {
        val block = ContentBlock.Image("png", BinarySource.bytes("hello".encodeToByteArray()))
        val encoded = Json.encodeToString(ContentBlockSerializer, block)
        assertContains(encoded, "aGVsbG8=")
        assertEquals(block, roundTrip(block))
    }

    @Test
    fun documentRoundTripsWithAnS3Source() {
        val block = ContentBlock.Document("pdf", "report", BinarySource.s3("s3://bucket/report.pdf"))
        assertEquals(block, roundTrip(block))
    }

    /**
     * The forward-compatibility arm. A variant this library does not model must survive a round
     * trip byte-for-byte, or the next turn of the conversation is rejected.
     */
    @Test
    fun anUnmodelledVariantSurvivesUntouched() {
        val encoded = """{"citationsContent":{"citations":[{"title":"t"}],"content":[]}}"""
        val decoded = Json.decodeFromString(ContentBlockSerializer, encoded)

        val unknown = assertIs<ContentBlock.Unknown>(decoded)
        assertEquals("citationsContent", unknown.key)
        assertEquals(encoded, Json.encodeToString(ContentBlockSerializer, decoded))
    }

    /**
     * Reasoning must be echoed back **unchanged**, signature included, or a reasoning model rejects
     * the following turn. Keeping the raw element is what guarantees that.
     */
    @Test
    fun reasoningKeepsItsRawFormAndExposesTheText() {
        val encoded = """{"reasoningContent":{"reasoningText":{"text":"thinking","signature":"sig-abc"}}}"""
        val decoded = Json.decodeFromString(ContentBlockSerializer, encoded)

        val reasoning = assertIs<ContentBlock.Reasoning>(decoded)
        assertEquals("thinking", reasoning.text)
        // Byte-identical, signature and all.
        assertEquals(encoded, Json.encodeToString(ContentBlockSerializer, decoded))
    }

    @Test
    fun cachePointRoundTrips() {
        assertEquals(ContentBlock.CachePoint(), roundTrip(ContentBlock.CachePoint()))
    }

    @Test
    fun aBinarySourceMustCarryExactlyOneOfBytesOrS3() {
        assertFailsWith<IllegalArgumentException> { BinarySource() }
        assertFailsWith<IllegalArgumentException> {
            BinarySource(bytes = ByteArray(1), s3Location = S3Location("s3://b/k"))
        }
    }
}

class ToolCallingTest {

    private val toolReply = """{
      "output": {"message": {"role": "assistant", "content": [
        {"text": "Let me check."},
        {"toolUse": {"toolUseId": "tu-1", "name": "get_weather", "input": {"city": "Paris"}}}
      ]}},
      "stopReason": "tool_use"
    }"""

    @Test
    fun readsToolUseBlocksOutOfTheReply() = runTest {
        val response = harnessBedrock(BedrockHarness()) { json(toolReply) }
            .converse(ConverseRequest(MODEL, listOf(Message.user("weather?"))))

        assertEquals(StopReason.TOOL_USE, response.stopReason)
        val toolUse = response.toolUses.single()
        assertEquals("tu-1", toolUse.toolUseId)
        assertEquals("get_weather", toolUse.name)
        assertEquals("Paris", toolUse.input.jsonObject["city"]?.jsonPrimitive?.content)
        // The text block is still there alongside the tool call.
        assertEquals("Let me check.", response.text)
    }

    @Test
    fun sendsAToolConfigurationWithItsSchema() = runTest {
        val h = BedrockHarness()
        harnessBedrock(h) { json(toolReply) }.converse(
            ConverseRequest(
                modelId = MODEL,
                messages = listOf(Message.user("weather?")),
                toolConfig = ToolConfiguration(
                    tools = listOf(
                        Tool.spec(
                            ToolSpecification(
                                name = "get_weather",
                                description = "Current weather for a city.",
                                inputSchema = ToolInputSchema(
                                    buildJsonObject {
                                        put("type", "object")
                                        put("properties", buildJsonObject { put("city", buildJsonObject { put("type", "string") }) })
                                    },
                                ),
                            ),
                        ),
                    ),
                    toolChoice = ToolChoice.any(),
                ),
            ),
        )

        val toolConfig = bodyJson(h.bodies.single())["toolConfig"]!!.jsonObject
        val spec = toolConfig["tools"]!!.jsonArray.single().jsonObject["toolSpec"]!!.jsonObject
        assertEquals("get_weather", spec["name"]?.jsonPrimitive?.content)
        assertEquals("object", spec["inputSchema"]!!.jsonObject["json"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(setOf("any"), toolConfig["toolChoice"]!!.jsonObject.keys)
    }

    /** Continuing a conversation means echoing the assistant turn back exactly as it arrived. */
    @Test
    fun plusReplyAppendsTheAssistantTurnWithItsToolBlocks() = runTest {
        val response = harnessBedrock(BedrockHarness()) { json(toolReply) }
            .converse(ConverseRequest(MODEL, listOf(Message.user("weather?"))))

        val history = listOf(Message.user("weather?")).plusReply(response)
        assertEquals(2, history.size)
        assertEquals(ConversationRole.ASSISTANT, history[1].role)
        assertEquals(1, history[1].toolUses.size)
    }
}

class ConverseErrorTest {

    private fun errorHeaders(code: String) =
        headersOf("x-amzn-errortype" to listOf(code), "Content-Type" to listOf("application/json"))

    @Test
    fun mapsKnownCodesOntoTypedExceptions() = runTest {
        suspend fun failingWith(code: String, status: HttpStatusCode): Throwable = runCatching {
            harnessBedrock(BedrockHarness()) {
                Triple("""{"message":"nope"}""".encodeToByteArray(), status, errorHeaders(code))
            }.converse(ConverseRequest(MODEL, listOf(Message.user("hi"))))
        }.exceptionOrNull()!!

        assertTrue(failingWith("ValidationException", HttpStatusCode.BadRequest) is ValidationException)
        assertTrue(failingWith("AccessDeniedException", HttpStatusCode.Forbidden) is AccessDeniedException)
        assertTrue(
            failingWith("ResourceNotFoundException", HttpStatusCode.NotFound) is ResourceNotFoundException,
        )
        assertTrue(
            failingWith("ThrottlingException", HttpStatusCode.TooManyRequests) is ThrottlingException,
        )
    }

    @Test
    fun unknownCodesFallThroughToTheBaseType() = runTest {
        val failure = assertFailsWith<BedrockException> {
            harnessBedrock(BedrockHarness()) {
                Triple(
                    """{"message":"?"}""".encodeToByteArray(),
                    HttpStatusCode.BadRequest,
                    errorHeaders("SomethingAwsAddedLater"),
                )
            }.converse(ConverseRequest(MODEL, listOf(Message.user("hi"))))
        }
        assertEquals("SomethingAwsAddedLater", failure.code)
        assertTrue(failure.cause != null, "the transport exception was discarded")
    }
}
