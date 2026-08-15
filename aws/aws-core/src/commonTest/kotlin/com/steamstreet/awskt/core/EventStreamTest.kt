package com.steamstreet.awskt.core

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Builds real `vnd.amazon.eventstream` frames, CRCs included.
 *
 * A hand-written builder rather than canned bytes: the decoder's job is arithmetic over lengths and
 * checksums, and a fixture that was produced by the same misunderstanding as the parser would agree
 * with it. This encodes from the specification independently — and the CRC values it produces are
 * checked against a known vector below, so "independently" means something.
 */
private object Frames {

    fun frame(headers: List<Pair<String, String>> = emptyList(), payload: ByteArray = ByteArray(0)): ByteArray {
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

    /** Only the string type (7), which is all AWS sends — other types are exercised separately. */
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

    /** A header block with one header of an arbitrary wire type, for the skip-correctly tests. */
    fun typedHeader(name: String, type: Int, value: ByteArray): ByteArray {
        val nameBytes = name.encodeToByteArray()
        return byteArrayOf(nameBytes.size.toByte()) + nameBytes + byteArrayOf(type.toByte()) + value
    }

    fun frameWithRawHeaders(headerBytes: ByteArray, payload: ByteArray = ByteArray(0)): ByteArray {
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

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}

private suspend fun decode(vararg frames: ByteArray): List<EventStreamMessage> {
    val bytes = frames.fold(ByteArray(0)) { acc, frame -> acc + frame }
    return ByteReadChannel(bytes).awsEventStream().toList()
}

class Crc32Test {

    /**
     * The published IEEE CRC-32 check value: the ASCII string `123456789` hashes to `0xCBF43926`.
     *
     * Worth asserting rather than assuming. Every other test here builds frames using this same
     * function, so a wrong polynomial would produce frames the decoder happily accepts and AWS
     * would reject — the tests would all pass and the client would not work.
     */
    @Test
    fun matchesThePublishedCheckValue() {
        assertEquals(0xCBF43926u, crc32("123456789".encodeToByteArray()).toUInt())
    }

    @Test
    fun isEmptyForNoBytes() {
        assertEquals(0u, crc32(ByteArray(0)).toUInt())
    }

    /** Chaining has to equal hashing the concatenation, because the message CRC relies on it. */
    @Test
    fun chainsAcrossTwoBuffers() {
        val whole = crc32("123456789".encodeToByteArray())
        val chained = crc32("56789".encodeToByteArray(), previous = crc32("1234".encodeToByteArray()))
        assertEquals(whole, chained)
    }
}

class EventStreamDecodingTest {

    @Test
    fun decodesHeadersAndPayload() = runTest {
        val messages = decode(
            Frames.frame(
                headers = listOf(
                    ":message-type" to "event",
                    ":event-type" to "contentBlockDelta",
                    ":content-type" to "application/json",
                ),
                payload = """{"delta":{"text":"hi"}}""".encodeToByteArray(),
            ),
        )

        val message = messages.single()
        assertEquals("event", message.messageType)
        assertEquals("contentBlockDelta", message.eventType)
        assertEquals("application/json", message.headers[":content-type"])
        assertEquals("""{"delta":{"text":"hi"}}""", message.payload.decodeToString())
    }

    @Test
    fun decodesManyFramesBackToBack() = runTest {
        val messages = decode(
            Frames.frame(listOf(":event-type" to "messageStart"), "{}".encodeToByteArray()),
            Frames.frame(listOf(":event-type" to "contentBlockDelta"), """{"i":1}""".encodeToByteArray()),
            Frames.frame(listOf(":event-type" to "messageStop"), "{}".encodeToByteArray()),
        )

        assertEquals(
            listOf("messageStart", "contentBlockDelta", "messageStop"),
            messages.map { it.eventType },
        )
        assertEquals("""{"i":1}""", messages[1].payload.decodeToString())
    }

    @Test
    fun decodesAFrameWithNoHeadersAndNoPayload() = runTest {
        val message = decode(Frames.frame()).single()
        assertTrue(message.headers.isEmpty())
        assertEquals(0, message.payload.size)
    }

    @Test
    fun aCleanCloseAtAFrameBoundaryEndsTheFlow() = runTest {
        assertTrue(decode().isEmpty())
    }

    /**
     * Every wire value type has to be decoded, not because AWS sends them, but because the widths
     * are type-dependent: a type the parser skipped wrongly turns every *following* header into
     * garbage.
     */
    @Test
    fun skipsEveryHeaderValueTypeCorrectly() = runTest {
        val headerBytes =
            Frames.typedHeader("bool-true", 0, ByteArray(0)) +
                Frames.typedHeader("bool-false", 1, ByteArray(0)) +
                Frames.typedHeader("byte", 2, byteArrayOf(7)) +
                Frames.typedHeader("short", 3, byteArrayOf(0, 9)) +
                Frames.typedHeader("int", 4, byteArrayOf(0, 0, 1, 0)) +
                Frames.typedHeader("long", 5, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 5)) +
                Frames.typedHeader("bytes", 6, byteArrayOf(0, 3) + byteArrayOf(1, 2, 3)) +
                Frames.typedHeader("uuid", 9, ByteArray(16)) +
                // The one that matters, placed LAST: if any skip above is wrong, this is unreadable.
                Frames.typedHeader("str", 7, byteArrayOf(0, 2) + "ok".encodeToByteArray())

        val message = decode(Frames.frameWithRawHeaders(headerBytes)).single()

        assertEquals("ok", message.headers["str"])
        assertEquals("true", message.headers["bool-true"])
        assertEquals("false", message.headers["bool-false"])
        assertEquals("7", message.headers["byte"])
        assertEquals("9", message.headers["short"])
        assertEquals("256", message.headers["int"])
        assertEquals("5", message.headers["long"])
        assertEquals("<3 bytes>", message.headers["bytes"])
    }

    @Test
    fun rejectsAnUnknownHeaderValueType() = runTest {
        val bad = Frames.frameWithRawHeaders(Frames.typedHeader("weird", 42, ByteArray(0)))
        val failure = assertFailsWith<EventStreamException> { decode(bad) }
        assertContains(failure.message!!, "42")
    }
}

class EventStreamCorruptionTest {

    /**
     * The prelude CRC is checked **before** the lengths it protects are used to allocate, which is
     * the whole reason it exists at that position in the format.
     */
    @Test
    fun rejectsACorruptPrelude() = runTest {
        val frame = Frames.frame(listOf(":event-type" to "messageStart"), "{}".encodeToByteArray())
        // Corrupt the headers-length field, leaving the prelude CRC describing the original.
        frame[7] = (frame[7] + 1).toByte()

        val failure = assertFailsWith<EventStreamException> { decode(frame) }
        assertContains(failure.message!!, "prelude CRC mismatch")
    }

    @Test
    fun rejectsACorruptPayload() = runTest {
        val frame = Frames.frame(listOf(":event-type" to "messageStart"), "hello".encodeToByteArray())
        // Flip a payload byte; the prelude is untouched, so only the message CRC catches this.
        frame[frame.size - 6] = (frame[frame.size - 6] + 1).toByte()

        val failure = assertFailsWith<EventStreamException> { decode(frame) }
        assertContains(failure.message!!, "message CRC mismatch")
    }

    /**
     * A stream that dies mid-frame is a failure, not a clean end. The alternative is a caller
     * treating a connection that dropped mid-answer as a complete answer.
     */
    @Test
    fun rejectsATruncatedStream() = runTest {
        val frame = Frames.frame(listOf(":event-type" to "messageStart"), "hello".encodeToByteArray())
        val truncated = frame.copyOfRange(0, frame.size - 3)

        val failure = assertFailsWith<EventStreamException> { decode(truncated) }
        assertContains(failure.message!!, "part-way through a frame")
    }

    /**
     * A length is read before the CRC protecting it can be verified against the bytes it describes
     * — that is unavoidable, since the length says how many bytes to read — so it is bounded.
     */
    @Test
    fun rejectsAnAbsurdFrameLength() = runTest {
        val absurd = byteArrayOf(0x7F, -1, -1, -1) + ByteArray(12)
        val failure = assertFailsWith<EventStreamException> { decode(absurd) }
        assertContains(failure.message!!, "outside the legal range")
    }

    @Test
    fun rejectsAFrameShorterThanItsOwnOverhead() = runTest {
        val tooShort = byteArrayOf(0, 0, 0, 4) + ByteArray(12)
        assertFailsWith<EventStreamException> { decode(tooShort) }
    }

    /** The frames before a corrupt one are still delivered; the failure lands where it happened. */
    @Test
    fun deliversGoodFramesBeforeFailing() = runTest {
        val good = Frames.frame(listOf(":event-type" to "messageStart"), "{}".encodeToByteArray())
        val bad = Frames.frame(listOf(":event-type" to "messageStop"), "{}".encodeToByteArray())
        bad[bad.size - 5] = (bad[bad.size - 5] + 1).toByte()

        val seen = mutableListOf<EventStreamMessage>()
        assertFailsWith<EventStreamException> {
            ByteReadChannel(good + bad).awsEventStream().collect { seen += it }
        }
        assertEquals(listOf("messageStart"), seen.map { it.eventType })
    }
}
