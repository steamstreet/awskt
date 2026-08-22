package com.steamstreet.awskt.lambda

import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.EventStreamMessage
import com.steamstreet.awskt.core.awsEventStream
import com.steamstreet.awskt.core.awsJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.transform
import kotlin.coroutines.cancellation.CancellationException

/**
 * `InvokeWithResponseStream`, decoded from `application/vnd.amazon.eventstream`.
 *
 * The framing itself is `aws-core`'s — the same decoder serves Bedrock's `ConverseStream` — so what
 * is left here is the two event types Lambda sends and the one structural surprise: **a Lambda
 * `PayloadChunk` frame carries raw bytes, not JSON.** The `Payload` member is `@eventPayload`, so
 * the frame's body *is* the function's output, unwrapped and un-base64'd. Every other streaming
 * service in this library sends a JSON document per frame, and reading these as JSON would fail on
 * the first chunk of anything that is not.
 */
internal fun responseStream(
    client: AwsServiceClient,
    request: InvokeRequest,
    payload: ByteArray,
    headers: List<Pair<String, String>>,
): Flow<InvokeStreamEvent> = channelFlow {
    // `channelFlow` + `send`, not `flow` + `emit`, and the difference is load-bearing. Ktor 3.x
    // runs the response block on the *engine's* dispatcher on non-JVM platforms — under the native
    // Curl engine the lambda below is on `Dispatchers.IO` while the collector is wherever it was —
    // and `emit` from there violates flow context preservation. `send` is legal from any context.
    // See the same construction, and the deployed Graviton failure that motivated it, in
    // `aws-bedrock-runtime`'s `converseStream`.
    //
    // The try/catch is what preserves "chunks delivered before a mid-stream failure stay
    // delivered". A `channelFlow` block that *throws* cancels the channel and discards whatever is
    // buffered in it; one that `close(cause)`s and returns lets the collector drain the buffer and
    // then throws the cause. Cancellation is rethrown, because it means the collector went away and
    // there is nobody left to close for.
    try {
        mapErrors {
            client.callStreaming(
                method = "POST",
                path = responseStreamingPath(request.functionName),
                query = request.qualifier?.let { listOf("Qualifier" to it) } ?: emptyList(),
                headers = headers,
                body = payload,
                operation = "InvokeWithResponseStream",
                safety = request.safety,
            ) { _, _, channel ->
                // Collected inside `callStreaming`'s scope, which is what keeps the connection open
                // for the duration.
                channel.awsEventStream().collect { frame -> send(frame.toInvokeEvent()) }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        close(failure)
    }
}

/**
 * Maps one decoded frame onto [InvokeStreamEvent].
 *
 * Unknown event types are reported as an empty [InvokeStreamEvent.Complete] rather than dropped or
 * thrown on: AWS adding a frame type is then a non-event for existing callers, in the same spirit
 * as `awsJson`'s `ignoreUnknownKeys`. An `InvokeComplete` whose body does not parse is treated the
 * same way — the stream ended, and inventing an error code for it would be worse than reporting the
 * end without one.
 */
internal fun EventStreamMessage.toInvokeEvent(): InvokeStreamEvent = when (eventType) {
    "PayloadChunk" -> InvokeStreamEvent.PayloadChunk(payload)

    "InvokeComplete" -> {
        val body = runCatching {
            awsJson.decodeFromString(InvokeCompleteBody.serializer(), payload.decodeToString())
        }.getOrNull()
        InvokeStreamEvent.Complete(body?.errorCode, body?.errorDetails, body?.logResult)
    }

    else -> InvokeStreamEvent.Complete()
}

/**
 * The chunks alone, with a failed invocation raised as [FunctionErrorException].
 *
 * The shape most streaming callers want: [InvokeStreamEvent.Complete] carries no data on the
 * success path, and on the failure path it carries the *only* signal that anything went wrong — so
 * a caller that filters the stream down to its chunks and forgets the terminator silently treats a
 * half-written answer as a whole one.
 *
 * **Returns bytes, not text, and that is deliberate.** Chunk boundaries are wherever the function's
 * runtime happened to flush, so a single UTF-8 character can straddle two of them; a per-chunk
 * `decodeToString()` would corrupt it. Concatenate first, decode second.
 *
 * @throws FunctionErrorException when the terminating event reports an error code. Chunks already
 *   emitted stay emitted — there is no way to un-deliver them — so a collector that has been
 *   writing them somewhere has a partial answer to clean up.
 */
public fun Flow<InvokeStreamEvent>.payloadChunks(): Flow<ByteArray> = transform { event ->
    when (event) {
        is InvokeStreamEvent.PayloadChunk -> emit(event.payload)
        is InvokeStreamEvent.Complete -> event.errorCode?.let { code ->
            throw FunctionErrorException(
                functionError = "Unhandled",
                errorType = code,
                errorMessage = event.errorDetails,
                payload = (event.errorDetails ?: "").encodeToByteArray(),
            )
        }
    }
}

/**
 * The whole streamed response, assembled.
 *
 * For the case where streaming was the *function's* choice rather than the caller's — a handler
 * configured with `RESPONSE_STREAM` that a batch job simply wants the output of. It defeats the
 * point of streaming, which is why it is a named function rather than the default: whoever calls it
 * has decided to hold the entire answer in memory, and unlike [Lambda.invoke] there is no 6 MB
 * service ceiling holding that down.
 */
public suspend fun Flow<InvokeStreamEvent>.collectPayload(): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    payloadChunks().collect { chunks += it }
    val out = ByteArray(chunks.sumOf { it.size })
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(out, offset)
        offset += chunk.size
    }
    return out
}

/** [collectPayload] decoded as UTF-8 — safe here, where the whole response is in hand. */
public suspend fun Flow<InvokeStreamEvent>.collectPayloadText(): String =
    collectPayload().decodeToString()
