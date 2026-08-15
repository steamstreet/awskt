package com.steamstreet.awskt.core

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * `application/vnd.amazon.eventstream`: the binary framing AWS uses for streaming responses.
 *
 * ### Why this is in `aws-core` when `aws-sns`'s query codec is not
 *
 * The two look like the same judgement call and are not. `aws-sns`'s form encoder is shaped by
 * SNS's *service* model — which structures flatten, which map spelling each field wants — so
 * generalizing it would mean generalizing SNS. This is a **protocol** with no service content
 * whatsoever: the same frames carry Bedrock's `ConverseStream`, Kinesis's `SubscribeToShard`, S3's
 * `SelectObjectContent` and Transcribe's streaming, and the decoder cannot tell them apart because
 * there is nothing service-specific in it.
 *
 * The deciding argument is smaller than that, though: [AwsServiceClient.callStreaming] hands a
 * caller a raw [ByteReadChannel], and **every** AWS service that streams frames it this way. A
 * transport that ships without the decoder ships half a tool.
 *
 * ### The frame
 *
 * ```
 *  0      4              8              12                          N-4      N
 *  +------+--------------+--------------+---------------------------+--------+
 *  | total| headers len  | prelude CRC  | headers … | payload …     | msg CRC|
 *  +------+--------------+--------------+---------------------------+--------+
 * ```
 *
 * All integers are **big-endian**, and `total` counts the whole frame including both CRCs. The
 * payload length is therefore `total - headers - 16`, which is the one arithmetic relationship in
 * the format and the one worth checking: a corrupt `headers` field otherwise produces a negative
 * payload length and an allocation the size of the address space.
 *
 * ### Both CRCs are verified
 *
 * Not decoration. This is the one place in the library where a *partial* read is normal — a
 * response arrives over many frames and many seconds — so a truncated or mis-framed stream is a
 * real possibility rather than a theoretical one, and without the check it surfaces as a JSON parse
 * error inside a payload that was never a whole payload. The prelude CRC is checked before the
 * lengths it protects are used to allocate.
 */

/**
 * One decoded frame: its headers and its payload bytes.
 *
 * @property headers header name to value, values rendered as strings. Every header a streaming AWS
 *   service sends is a string in practice; the other eight wire types are decoded so the parser can
 *   *skip* them correctly, and rendered with [toString] rather than being dropped.
 * @property payload the frame's body, usually a JSON document.
 */
public class EventStreamMessage(
    public val headers: Map<String, String>,
    public val payload: ByteArray,
) {
    /** `:message-type` — `event`, `exception` or `error`. */
    public val messageType: String? get() = headers[":message-type"]

    /** `:event-type` — which event this is, on an `event` message. */
    public val eventType: String? get() = headers[":event-type"]

    /** `:exception-type` — which modelled exception this is, on an `exception` message. */
    public val exceptionType: String? get() = headers[":exception-type"]

    override fun toString(): String =
        "EventStreamMessage(messageType=$messageType, eventType=$eventType, " +
            "exceptionType=$exceptionType, payload=${payload.size} bytes)"
}

/** A frame that could not be decoded, or whose CRC did not match. */
public class EventStreamException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The fixed overhead of a frame: two length fields, two CRCs. */
private const val FRAME_OVERHEAD = 16

/**
 * A ceiling on one frame, so a corrupt length cannot ask for an unbounded allocation.
 *
 * 16 MiB is far above anything a streaming AWS service emits — Bedrock's largest frame is a few
 * kilobytes — and far below the point where a bad allocation kills a Lambda. The bound exists
 * because the length is read *before* the CRC that protects it can be checked against the bytes it
 * describes, which is unavoidable: the length is how many bytes to read.
 */
private const val MAX_FRAME_BYTES = 16 * 1024 * 1024

/**
 * Decodes an event stream into frames, lazily.
 *
 * Cold: nothing is read until the flow is collected, and collection drives the channel. The flow
 * completes when the channel closes cleanly at a frame boundary.
 *
 * @throws EventStreamException on a CRC mismatch, an impossible length, or a stream that ends
 *   part-way through a frame. A truncated stream is a failure rather than a clean completion —
 *   the alternative is a caller treating a connection that died mid-answer as a complete answer.
 */
public fun ByteReadChannel.awsEventStream(): Flow<EventStreamMessage> = flow {
    while (true) {
        // A clean close at a frame boundary is the normal end of a stream.
        if (isClosedForRead) break

        val totalLength = try {
            readInt()
        } catch (eof: Throwable) {
            // Distinguishing "closed exactly here" from "closed one byte in" is not worth a
            // sentinel: isClosedForRead above covers the clean case, so arriving here means the
            // channel ended between frames in a way the check missed. Treat it as the end.
            break
        }

        if (totalLength < FRAME_OVERHEAD || totalLength > MAX_FRAME_BYTES) {
            throw EventStreamException(
                "Event stream frame claims $totalLength bytes, outside the legal range " +
                    "$FRAME_OVERHEAD..$MAX_FRAME_BYTES. The stream is corrupt or misaligned.",
            )
        }

        // The rest of the frame, read as one block: everything after the first length field.
        val rest = try {
            readByteArray(totalLength - 4)
        } catch (truncated: Throwable) {
            throw EventStreamException(
                "Event stream ended part-way through a frame that claimed $totalLength bytes.",
                truncated,
            )
        }

        emit(decodeFrame(totalLength, rest))
    }
}

/**
 * Decodes one frame given its declared total length and everything after the first length field.
 *
 * Split out from the channel loop so it can be tested against bytes directly — the framing is
 * fiddly enough that it deserves tests that do not need a live channel.
 */
internal fun decodeFrame(totalLength: Int, rest: ByteArray): EventStreamMessage {
    val headersLength = readIntAt(rest, 0)
    val preludeCrc = readIntAt(rest, 4)

    // Checked BEFORE the lengths are used, because the prelude CRC is what makes them trustworthy.
    val prelude = ByteArray(8)
    writeIntAt(prelude, 0, totalLength)
    writeIntAt(prelude, 4, headersLength)
    val computedPreludeCrc = crc32(prelude)
    if (computedPreludeCrc != preludeCrc) {
        throw EventStreamException(
            "Event stream prelude CRC mismatch: frame says ${preludeCrc.toUInt()}, computed " +
                "${computedPreludeCrc.toUInt()}. The lengths in this frame cannot be trusted.",
        )
    }

    val payloadLength = totalLength - headersLength - FRAME_OVERHEAD
    if (headersLength < 0 || payloadLength < 0) {
        throw EventStreamException(
            "Event stream frame has impossible lengths: total=$totalLength, headers=$headersLength, " +
                "which leaves $payloadLength bytes of payload.",
        )
    }

    // rest = [headersLength:4][preludeCrc:4][headers][payload][messageCrc:4]
    val headerStart = 8
    val payloadStart = headerStart + headersLength
    val headers = decodeHeaders(rest, headerStart, payloadStart)
    val payload = rest.copyOfRange(payloadStart, payloadStart + payloadLength)

    val messageCrc = readIntAt(rest, rest.size - 4)
    // The message CRC covers the whole frame up to itself — including the first length field, which
    // is not in `rest`, so it is folded in first.
    val firstField = ByteArray(4).also { writeIntAt(it, 0, totalLength) }
    val computedMessageCrc = crc32(rest, 0, rest.size - 4, crc32(firstField))
    if (computedMessageCrc != messageCrc) {
        throw EventStreamException(
            "Event stream message CRC mismatch: frame says ${messageCrc.toUInt()}, computed " +
                "${computedMessageCrc.toUInt()}. The frame arrived corrupt.",
        )
    }

    return EventStreamMessage(headers, payload)
}

/**
 * Decodes the header block.
 *
 * Every value type in the format is handled, not only the strings AWS actually sends. That is not
 * completeness for its own sake: the types are variable-width, so a type this parser did not
 * recognise would leave the cursor in the wrong place and turn every *subsequent* header into
 * garbage. Skipping correctly requires decoding correctly.
 */
private fun decodeHeaders(bytes: ByteArray, from: Int, until: Int): Map<String, String> {
    val headers = mutableMapOf<String, String>()
    var cursor = from
    while (cursor < until) {
        val nameLength = bytes[cursor].toInt() and 0xFF
        cursor++
        val name = bytes.decodeToString(cursor, cursor + nameLength)
        cursor += nameLength
        val valueType = bytes[cursor].toInt() and 0xFF
        cursor++

        val value: String = when (valueType) {
            0 -> "true"
            1 -> "false"
            2 -> bytes[cursor].toString().also { cursor += 1 }
            3 -> readShortAt(bytes, cursor).toString().also { cursor += 2 }
            4 -> readIntAt(bytes, cursor).toString().also { cursor += 4 }
            // A long, a timestamp (epoch millis) and a UUID are all fixed-width and all rendered
            // rather than modelled: no AWS streaming service puts anything a caller needs in one.
            5, 8 -> readLongAt(bytes, cursor).toString().also { cursor += 8 }
            6 -> {
                val length = readShortAt(bytes, cursor).toInt() and 0xFFFF
                cursor += 2
                // Byte-array headers are rendered as a length rather than as mojibake: they are not
                // text, and no AWS service sends one this library needs to read.
                "<${length} bytes>".also { cursor += length }
            }

            7 -> {
                val length = readShortAt(bytes, cursor).toInt() and 0xFFFF
                cursor += 2
                bytes.decodeToString(cursor, cursor + length).also { cursor += length }
            }

            9 -> "<uuid>".also { cursor += 16 }
            else -> throw EventStreamException(
                "Unknown event stream header value type $valueType for header '$name'. The header " +
                    "block cannot be parsed past this point, because value widths are type-dependent.",
            )
        }
        headers[name] = value
    }
    return headers
}

private fun readShortAt(bytes: ByteArray, offset: Int): Short =
    (((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)).toShort()

private fun readIntAt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

private fun readLongAt(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
    return value
}

private fun writeIntAt(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 24).toByte()
    bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte()
    bytes[offset + 3] = value.toByte()
}

/**
 * The IEEE CRC-32 table, built once.
 *
 * The same polynomial zlib and every other CRC-32 uses (`0xEDB88320` reversed). Hand-written
 * because there is no multiplatform CRC in the Kotlin standard library and because pulling a
 * hashing dependency in for 20 lines of table lookup would be a poor trade — `aws-signing` already
 * carries the only crypto this library needs, and a CRC is not crypto.
 */
private val CRC32_TABLE: IntArray = IntArray(256) { index ->
    var value = index
    repeat(8) {
        value = if (value and 1 != 0) (value ushr 1) xor -0x12477CE0 else value ushr 1
    }
    value
}

/** IEEE CRC-32 over [bytes] from [from] until [until], continuing from [previous]. */
internal fun crc32(bytes: ByteArray, from: Int = 0, until: Int = bytes.size, previous: Int = 0): Int {
    var crc = previous.inv()
    for (i in from until until) {
        crc = (crc ushr 8) xor CRC32_TABLE[(crc xor bytes[i].toInt()) and 0xFF]
    }
    return crc.inv()
}
