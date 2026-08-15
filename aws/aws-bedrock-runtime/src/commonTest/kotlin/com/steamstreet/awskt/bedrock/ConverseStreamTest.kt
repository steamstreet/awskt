package com.steamstreet.awskt.bedrock

import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Builds real `vnd.amazon.eventstream` frames, CRCs included, so the streaming path is exercised
 * end to end rather than from the frame decoder inwards.
 *
 * Duplicated from `aws-core`'s own test rather than shared: that builder is in another module's
 * test source set, and a test fixture reaching across module boundaries is a dependency that only
 * exists to save twenty lines.
 */
private object Frames {

    fun event(eventType: String, payload: String): ByteArray = frame(
        headers = listOf(
            ":message-type" to "event",
            ":event-type" to eventType,
            ":content-type" to "application/json",
        ),
        payload = payload.encodeToByteArray(),
    )

    fun exception(exceptionType: String, payload: String): ByteArray = frame(
        headers = listOf(
            ":message-type" to "exception",
            ":exception-type" to exceptionType,
            ":content-type" to "application/json",
        ),
        payload = payload.encodeToByteArray(),
    )

    private fun frame(headers: List<Pair<String, String>>, payload: ByteArray): ByteArray {
        val headerBytes = encodeHeaders(headers)
        val totalLength = 16 + headerBytes.size + payload.size
        val out = ByteArray(totalLength)
        writeInt(out, 0, totalLength)
        writeInt(out, 4, headerBytes.size)
        writeInt(out, 8, crc32(out, 0, 8))
        headerBytes.copyInto(out, 12)
        payload.copyInto(out, 12 + headerBytes.size)
        writeInt(out, totalLength - 4, crc32(out, 0, totalLength - 4))
        return out
    }

    private fun encodeHeaders(headers: List<Pair<String, String>>): ByteArray {
        val out = mutableListOf<Byte>()
        for ((name, value) in headers) {
            val nameBytes = name.encodeToByteArray()
            val valueBytes = value.encodeToByteArray()
            out += nameBytes.size.toByte()
            out += nameBytes.toList()
            out += 7.toByte()
            out += (valueBytes.size ushr 8).toByte()
            out += valueBytes.size.toByte()
            out += valueBytes.toList()
        }
        return out.toByteArray()
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private val table = IntArray(256) { index ->
        var value = index
        repeat(8) { value = if (value and 1 != 0) (value ushr 1) xor -0x12477CE0 else value ushr 1 }
        value
    }

    private fun crc32(bytes: ByteArray, from: Int, until: Int): Int {
        var crc = 0.inv()
        for (i in from until until) crc = (crc ushr 8) xor table[(crc xor bytes[i].toInt()) and 0xFF]
        return crc.inv()
    }
}

private fun stream(vararg frames: ByteArray) = Triple(
    frames.fold(ByteArray(0)) { acc, frame -> acc + frame },
    HttpStatusCode.OK,
    headersOf("Content-Type", "application/vnd.amazon.eventstream"),
)

private const val MODEL = "anthropic.claude-3-5-sonnet-20241022-v2:0"

/** The canonical shape of a plain text reply. */
private val TEXT_REPLY = arrayOf(
    Frames.event("messageStart", """{"role":"assistant"}"""),
    Frames.event("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"text":"The "}}"""),
    Frames.event("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"text":"answer"}}"""),
    Frames.event("contentBlockStop", """{"contentBlockIndex":0}"""),
    Frames.event("messageStop", """{"stopReason":"end_turn"}"""),
    Frames.event(
        "metadata",
        """{"usage":{"inputTokens":10,"outputTokens":2,"totalTokens":12},"metrics":{"latencyMs":99}}""",
    ),
)

class ConverseStreamTest {

    @Test
    fun postsToTheConverseStreamPath() = runTest {
        val h = BedrockHarness()
        harnessBedrock(h) { stream(*TEXT_REPLY) }
            .converseStream(ConverseRequest(MODEL, listOf(Message.user("hi")))).toList()

        assertTrue(h.requests.single().url.encodedPath.endsWith("/converse-stream"))
        // Same request body as the non-streaming call — only the path differs.
        val body = com.steamstreet.awskt.core.awsJson.parseToJsonElement(h.bodies.single()).jsonObject
        val block = body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single()
        assertEquals("hi", block.jsonObject["text"]?.jsonPrimitive?.content)
    }

    /** Nothing is sent until the flow is collected. */
    @Test
    fun theFlowIsCold() = runTest {
        val h = BedrockHarness()
        val flow = harnessBedrock(h) { stream(*TEXT_REPLY) }
            .converseStream(ConverseRequest(MODEL, listOf(Message.user("hi"))))

        assertTrue(h.requests.isEmpty(), "building the flow must not issue a request")
        flow.toList()
        assertEquals(1, h.requests.size)
    }

    @Test
    fun decodesTheEventSequence() = runTest {
        val events = harnessBedrock(BedrockHarness()) { stream(*TEXT_REPLY) }
            .converseStream(ConverseRequest(MODEL, listOf(Message.user("hi")))).toList()

        assertEquals(6, events.size)
        assertIs<ConverseStreamEvent.MessageStart>(events[0])
        assertEquals("assistant", (events[0] as ConverseStreamEvent.MessageStart).role)
        assertEquals("The ", (events[1] as ConverseStreamEvent.ContentBlockDelta).text)
        assertIs<ConverseStreamEvent.ContentBlockStop>(events[3])
        assertEquals(StopReason.END_TURN, (events[4] as ConverseStreamEvent.MessageStop).stopReason)
        assertEquals(12, (events[5] as ConverseStreamEvent.Metadata).usage?.totalTokens)
    }

    @Test
    fun textDeltasYieldsOnlyTheText() = runTest {
        val text = harnessBedrock(BedrockHarness()) { stream(*TEXT_REPLY) }
            .converseStream(ConverseRequest(MODEL, listOf(Message.user("hi"))))
            .textDeltas().toList()

        assertEquals(listOf("The ", "answer"), text)
    }

    /** An event type this library does not model must not fail the stream. */
    @Test
    fun anUnknownEventTypeBecomesAnUnknownArm() = runTest {
        val events = harnessBedrock(BedrockHarness()) {
            stream(
                Frames.event("messageStart", """{"role":"assistant"}"""),
                Frames.event("somethingNew", """{"whatever":1}"""),
                Frames.event("messageStop", """{"stopReason":"end_turn"}"""),
            )
        }.converseStream(ConverseRequest(MODEL, listOf(Message.user("hi")))).toList()

        val unknown = assertIs<ConverseStreamEvent.Unknown>(events[1])
        assertEquals("somethingNew", unknown.eventType)
        assertEquals(3, events.size, "the stream must continue past an unknown event")
    }

    /**
     * A modelled failure arriving mid-stream throws rather than becoming an event: an answer that
     * stopped being produced is a failure of the call, and delivering it as a value lets a caller
     * iterating for text ignore it.
     */
    @Test
    fun anExceptionFrameThrowsWithItsTypedException() = runTest {
        val failure = assertFailsWith<ModelStreamErrorException> {
            harnessBedrock(BedrockHarness()) {
                stream(
                    Frames.event("messageStart", """{"role":"assistant"}"""),
                    Frames.exception("ModelStreamErrorException", """{"message":"model died"}"""),
                )
            }.converseStream(ConverseRequest(MODEL, listOf(Message.user("hi")))).toList()
        }

        assertContains(failure.message!!, "model died")
        // 200, because that is what the HTTP exchange returned — the failure was reported inside it.
        assertEquals(200, failure.statusCode)
    }

    /** Whatever was emitted before the failure stays emitted. */
    @Test
    fun eventsBeforeAMidStreamFailureAreStillDelivered() = runTest {
        val seen = mutableListOf<ConverseStreamEvent>()
        assertFailsWith<ThrottlingException> {
            harnessBedrock(BedrockHarness()) {
                stream(
                    Frames.event("messageStart", """{"role":"assistant"}"""),
                    Frames.event("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"text":"partial"}}"""),
                    Frames.exception("ThrottlingException", """{"message":"slow down"}"""),
                )
            }.converseStream(ConverseRequest(MODEL, listOf(Message.user("hi")))).collect { seen += it }
        }

        assertEquals(2, seen.size)
        assertEquals("partial", (seen[1] as ConverseStreamEvent.ContentBlockDelta).text)
    }

    /** An error before the stream opens is an ordinary restJson1 error, not a frame. */
    @Test
    fun anErrorBeforeTheStreamOpensIsMappedNormally() = runTest {
        val failure = assertFailsWith<ValidationException> {
            harnessBedrock(BedrockHarness()) {
                Triple(
                    """{"message":"bad conversation"}""".encodeToByteArray(),
                    HttpStatusCode.BadRequest,
                    headersOf(
                        "x-amzn-errortype" to listOf("ValidationException"),
                        "Content-Type" to listOf("application/json"),
                    ),
                )
            }.converseStream(ConverseRequest(MODEL, listOf(Message.user("hi")))).toList()
        }
        assertEquals(400, failure.statusCode)
    }
}

/**
 * Reassembly. The interesting cases are the ones a naive "concatenate every delta" gets wrong.
 */
class AccumulateTest {

    @Test
    fun reassemblesAPlainTextReplyIncludingUsage() = runTest {
        val response = harnessBedrock(BedrockHarness()) { stream(*TEXT_REPLY) }
            .converseStream(ConverseRequest(MODEL, listOf(Message.user("hi")))).accumulate()

        assertEquals("The answer", response.text)
        assertEquals(StopReason.END_TURN, response.stopReason)
        // Usage arrives after messageStop — a caller that stops collecting there never sees it.
        assertEquals(12, response.usage?.totalTokens)
        assertEquals(ConversationRole.ASSISTANT, response.message?.role)
    }

    /**
     * Tool input streams as **partial JSON text**: a single delta is usually not valid JSON. Only
     * the concatenation of every delta in the block parses.
     */
    @Test
    fun reassemblesToolInputFromPartialJsonFragments() = runTest {
        val response = harnessBedrock(BedrockHarness()) {
            stream(
                Frames.event("messageStart", """{"role":"assistant"}"""),
                Frames.event(
                    "contentBlockStart",
                    """{"contentBlockIndex":0,"start":{"toolUse":{"toolUseId":"tu-1","name":"get_weather"}}}""",
                ),
                Frames.event(
                    "contentBlockDelta",
                    """{"contentBlockIndex":0,"delta":{"toolUse":{"input":"{\"cit"}}}""",
                ),
                Frames.event(
                    "contentBlockDelta",
                    """{"contentBlockIndex":0,"delta":{"toolUse":{"input":"y\":\"Paris\"}"}}}""",
                ),
                Frames.event("contentBlockStop", """{"contentBlockIndex":0}"""),
                Frames.event("messageStop", """{"stopReason":"tool_use"}"""),
            )
        }.converseStream(ConverseRequest(MODEL, listOf(Message.user("weather?")))).accumulate()

        assertEquals(StopReason.TOOL_USE, response.stopReason)
        val toolUse = response.toolUses.single()
        assertEquals("tu-1", toolUse.toolUseId)
        assertEquals("get_weather", toolUse.name)
        assertEquals("Paris", toolUse.input.jsonObject["city"]?.jsonPrimitive?.content)
    }

    /** A tool with no arguments streams no input deltas at all; `""` is not valid JSON. */
    @Test
    fun aToolWithNoArgumentsAccumulatesToAnEmptyObject() = runTest {
        val response = harnessBedrock(BedrockHarness()) {
            stream(
                Frames.event(
                    "contentBlockStart",
                    """{"contentBlockIndex":0,"start":{"toolUse":{"toolUseId":"tu-1","name":"ping"}}}""",
                ),
                Frames.event("messageStop", """{"stopReason":"tool_use"}"""),
            )
        }.converseStream(ConverseRequest(MODEL, listOf(Message.user("ping")))).accumulate()

        assertEquals(0, response.toolUses.single().input.jsonObject.size)
    }

    /**
     * **The case a naive fold gets wrong.** Two blocks are open at once and their deltas interleave;
     * concatenating without regard to `contentBlockIndex` splices two tool calls into unparseable
     * JSON.
     */
    @Test
    fun keepsInterleavedBlocksApartByIndex() = runTest {
        val response = harnessBedrock(BedrockHarness()) {
            stream(
                Frames.event("messageStart", """{"role":"assistant"}"""),
                Frames.event(
                    "contentBlockStart",
                    """{"contentBlockIndex":0,"start":{"toolUse":{"toolUseId":"a","name":"first"}}}""",
                ),
                Frames.event(
                    "contentBlockStart",
                    """{"contentBlockIndex":1,"start":{"toolUse":{"toolUseId":"b","name":"second"}}}""",
                ),
                // Interleaved on purpose.
                Frames.event("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"toolUse":{"input":"{\"x\":"}}}"""),
                Frames.event("contentBlockDelta", """{"contentBlockIndex":1,"delta":{"toolUse":{"input":"{\"y\":"}}}"""),
                Frames.event("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"toolUse":{"input":"1}"}}}"""),
                Frames.event("contentBlockDelta", """{"contentBlockIndex":1,"delta":{"toolUse":{"input":"2}"}}}"""),
                Frames.event("messageStop", """{"stopReason":"tool_use"}"""),
            )
        }.converseStream(ConverseRequest(MODEL, listOf(Message.user("do both")))).accumulate()

        val tools = response.toolUses
        assertEquals(2, tools.size)
        assertEquals(listOf("first", "second"), tools.map { it.name })
        assertEquals(1, tools[0].input.jsonObject["x"]?.jsonPrimitive?.content?.toInt())
        assertEquals(2, tools[1].input.jsonObject["y"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun reassemblesReasoningAlongsideText() = runTest {
        val response = harnessBedrock(BedrockHarness()) {
            stream(
                Frames.event("messageStart", """{"role":"assistant"}"""),
                Frames.event(
                    "contentBlockDelta",
                    """{"contentBlockIndex":0,"delta":{"reasoningContent":{"text":"hmm"}}}""",
                ),
                Frames.event("contentBlockDelta", """{"contentBlockIndex":1,"delta":{"text":"Yes"}}"""),
                Frames.event("messageStop", """{"stopReason":"end_turn"}"""),
            )
        }.converseStream(ConverseRequest(MODEL, listOf(Message.user("think")))).accumulate()

        val content = response.message!!.content
        assertEquals(2, content.size)
        // Index order, which is production order.
        assertEquals("hmm", assertIs<ContentBlock.Reasoning>(content[0]).text)
        assertEquals("Yes", assertIs<ContentBlock.Text>(content[1]).text)
        // `text` reads only the text blocks, so reasoning does not leak into the answer.
        assertEquals("Yes", response.text)
    }

    @Test
    fun aMidStreamFailurePropagatesOutOfAccumulate() = runTest {
        assertFailsWith<ModelStreamErrorException> {
            harnessBedrock(BedrockHarness()) {
                stream(
                    Frames.event("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"text":"partial"}}"""),
                    Frames.exception("ModelStreamErrorException", """{"message":"died"}"""),
                )
            }.converseStream(ConverseRequest(MODEL, listOf(Message.user("hi")))).accumulate()
        }
    }
}
