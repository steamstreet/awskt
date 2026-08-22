package com.steamstreet.awskt.lambda

import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Builds real `vnd.amazon.eventstream` frames, CRCs included, so the streaming path is exercised
 * end to end rather than from the frame decoder inwards.
 *
 * Duplicated from `aws-core`'s own test, as `aws-bedrock-runtime` duplicates it, rather than shared:
 * that builder lives in another module's test source set, and a fixture reaching across module
 * boundaries is a dependency that exists only to save twenty lines.
 */
private object Frames {

    /** A `PayloadChunk`, whose body is **raw bytes** rather than a JSON document. */
    fun chunk(payload: ByteArray): ByteArray = frame(
        headers = listOf(
            ":message-type" to "event",
            ":event-type" to "PayloadChunk",
            ":content-type" to "application/octet-stream",
        ),
        payload = payload,
    )

    fun event(eventType: String, payload: String): ByteArray = frame(
        headers = listOf(
            ":message-type" to "event",
            ":event-type" to eventType,
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

private fun stream(vararg frames: ByteArray) = Answer(
    frames.fold(ByteArray(0)) { acc, frame -> acc + frame },
    HttpStatusCode.OK,
    headersOf("Content-Type", "application/vnd.amazon.eventstream"),
)

private val COMPLETE = Frames.event("InvokeComplete", "{}")

class InvokeWithResponseStreamTest {

    @Test
    fun postsToTheResponseStreamingPath() = runTest {
        val h = LambdaHarness()
        harnessLambda(h) { stream(Frames.chunk("hi".encodeToByteArray()), COMPLETE) }
            .invokeWithResponseStream(InvokeRequest("worker", qualifier = "PROD"))
            .toList()

        val request = h.requests.single()
        assertEquals("POST", request.method.value)
        assertEquals(
            "/2021-11-15/functions/worker/response-streaming-invocations",
            request.url.encodedPath,
        )
        assertEquals("PROD", request.url.parameters["Qualifier"])
        assertEquals("RequestResponse", request.headers["X-Amz-Invocation-Type"])
    }

    /** Nothing is sent until the flow is collected. */
    @Test
    fun theFlowIsCold() = runTest {
        val h = LambdaHarness()
        val flow = harnessLambda(h) { stream(COMPLETE) }
            .invokeWithResponseStream(InvokeRequest("worker"))

        assertTrue(h.requests.isEmpty(), "building the flow must not issue a request")
        flow.toList()
        assertEquals(1, h.requests.size)
    }

    @Test
    fun deliversChunksAndThenTheTerminator() = runTest {
        val h = LambdaHarness()
        val events = harnessLambda(h) {
            stream(
                Frames.chunk("one".encodeToByteArray()),
                Frames.chunk("two".encodeToByteArray()),
                Frames.event("InvokeComplete", """{"LogResult":"YWJj"}"""),
            )
        }.invokeWithResponseStream(InvokeRequest("worker")).toList()

        assertEquals(3, events.size)
        assertEquals("one", (events[0] as InvokeStreamEvent.PayloadChunk).payload.decodeToString())
        assertEquals("two", (events[1] as InvokeStreamEvent.PayloadChunk).payload.decodeToString())
        val complete = assertIs<InvokeStreamEvent.Complete>(events[2])
        assertNull(complete.errorCode)
        assertEquals("YWJj", complete.logResult)
    }

    /**
     * A chunk's body is the function's output, not a JSON envelope around it. A function streaming
     * plain text or binary would otherwise fail on the first frame.
     */
    @Test
    fun aChunkCarriesRawBytes() = runTest {
        val h = LambdaHarness()
        val raw = byteArrayOf(0, 1, 2, -1)
        val events = harnessLambda(h) { stream(Frames.chunk(raw), COMPLETE) }
            .invokeWithResponseStream(InvokeRequest("worker")).toList()

        assertTrue(raw.contentEquals((events.first() as InvokeStreamEvent.PayloadChunk).payload))
    }

    /**
     * The reason [payloadChunks] answers bytes rather than text: a chunk boundary is wherever the
     * runtime flushed, and that can land in the middle of a multi-byte character. Decoding per
     * chunk would corrupt it; decoding the assembled whole does not.
     */
    @Test
    fun reassemblesACharacterSplitAcrossTwoChunks() = runTest {
        val h = LambdaHarness()
        val encoded = "café".encodeToByteArray()
        val split = encoded.size - 1

        val text = harnessLambda(h) {
            stream(
                Frames.chunk(encoded.copyOfRange(0, split)),
                Frames.chunk(encoded.copyOfRange(split, encoded.size)),
                COMPLETE,
            )
        }.invokeWithResponseStream(InvokeRequest("worker")).collectPayloadText()

        assertEquals("café", text)
    }

    /**
     * A streaming function that fails reports it in the terminator, on a `200`, after some of the
     * answer has already been delivered. [payloadChunks] turns that into an exception — and the
     * chunks that preceded it stay delivered, because there is no way to un-emit them.
     */
    @Test
    fun raisesTheTerminatorsErrorCodeAfterDeliveringWhatCameBefore() = runTest {
        val h = LambdaHarness()
        val delivered = mutableListOf<String>()

        val failure = assertFailsWith<FunctionErrorException> {
            harnessLambda(h) {
                stream(
                    Frames.chunk("partial".encodeToByteArray()),
                    Frames.event(
                        "InvokeComplete",
                        """{"ErrorCode":"Runtime.Timeout","ErrorDetails":"task timed out"}""",
                    ),
                )
            }.invokeWithResponseStream(InvokeRequest("worker"))
                .payloadChunks()
                .collect { delivered += it.decodeToString() }
        }

        assertEquals(listOf("partial"), delivered)
        assertEquals("Runtime.Timeout", failure.errorType)
        assertEquals("task timed out", failure.errorMessage)
    }

    /** A frame type AWS adds later must not fail an existing caller. */
    @Test
    fun toleratesAnUnknownEventType() = runTest {
        val h = LambdaHarness()
        val events = harnessLambda(h) { stream(Frames.event("SomethingNew", "{}"), COMPLETE) }
            .invokeWithResponseStream(InvokeRequest("worker")).toList()

        assertTrue(events.all { it is InvokeStreamEvent.Complete })
    }

    /** There is nothing to stream from an invocation that has only been queued. */
    @Test
    fun rejectsAnEventInvocation() = runTest {
        val h = LambdaHarness()
        assertFailsWith<IllegalArgumentException> {
            harnessLambda(h) { stream(COMPLETE) }
                .invokeWithResponseStream(
                    InvokeRequest("worker", invocationType = InvocationType.EVENT),
                )
        }
        assertTrue(h.requests.isEmpty())
    }

    /** A failure *before* the stream starts is an ordinary service error, typed as one. */
    @Test
    fun mapsAServiceErrorRaisedBeforeTheStream() = runTest {
        val h = LambdaHarness()
        assertFailsWith<ResourceNotFoundException> {
            harnessLambda(h) {
                Answer(
                    "{}",
                    HttpStatusCode.NotFound,
                    headersOf("x-amzn-errortype", "ResourceNotFoundException"),
                )
            }.invokeWithResponseStream(InvokeRequest("worker")).toList()
        }
    }

    /** The local payload ceiling applies here too, and still refuses before anything is sent. */
    @Test
    fun refusesAnOversizedPayload() = runTest {
        val h = LambdaHarness()
        assertFailsWith<LambdaPayloadTooLargeException> {
            harnessLambda(h) { stream(COMPLETE) }
                .invokeWithResponseStream(
                    InvokeRequest("worker", ByteArray(MAX_SYNC_PAYLOAD_BYTES + 1)),
                )
        }
        assertTrue(h.requests.isEmpty())
    }
}
