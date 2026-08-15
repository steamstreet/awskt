package com.steamstreet.awskt.bedrock

import com.steamstreet.awskt.core.EventStreamMessage
import com.steamstreet.awskt.core.awsJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `ConverseStream`'s events.
 *
 * ### The shape of a stream
 *
 * ```
 * messageStart(role=assistant)
 *   contentBlockStart(index=0)          ← only for tool-use blocks
 *   contentBlockDelta(index=0, text="The ")
 *   contentBlockDelta(index=0, text="answer")
 *   contentBlockStop(index=0)
 * messageStop(stopReason=end_turn)
 * metadata(usage, metrics)              ← after the stop, and it is where the token counts are
 * ```
 *
 * **`contentBlockIndex` matters as soon as anything but plain text is in play.** A reply that
 * interleaves reasoning and text, or that calls two tools, produces several concurrent blocks and
 * the deltas carry the index they belong to. Concatenating every delta's text regardless of index
 * works for the simple case and silently interleaves the complicated one — which is exactly why
 * [accumulate] exists rather than being left as an exercise.
 *
 * ### Unknown events are an arm, not a failure
 *
 * As with [ContentBlock], AWS adds event types, so [ConverseStreamEvent.Unknown] carries anything
 * unrecognised rather than failing the stream. A stream that dies on an event the caller did not
 * need is a worse outcome than one that ignores it.
 */
public sealed interface ConverseStreamEvent {

    /** The reply is beginning. [role] is always `assistant`. */
    public data class MessageStart(public val role: String) : ConverseStreamEvent

    /**
     * A content block is beginning.
     *
     * Emitted only for blocks that need announcing — a tool use, whose id and name arrive here
     * while its arguments arrive as deltas. Plain text blocks start with their first delta.
     */
    public data class ContentBlockStart(
        public val contentBlockIndex: Int,
        public val toolUse: ToolUseStart? = null,
    ) : ConverseStreamEvent

    /** A fragment of a content block. */
    public data class ContentBlockDelta(
        public val contentBlockIndex: Int,
        /** Text, when this delta is text. Null for a tool-input or reasoning delta. */
        public val text: String? = null,
        /**
         * A fragment of a tool call's JSON arguments, **as a string**.
         *
         * Bedrock streams tool input as partial JSON text, so a single delta is usually not valid
         * JSON on its own — `{"loc` then `ation":"NY"}`. It has to be concatenated across every
         * delta of the block before parsing. [accumulate] does that.
         */
        public val toolUseInput: String? = null,
        /** A fragment of reasoning text, for reasoning models. */
        public val reasoningText: String? = null,
        /** The whole delta, for anything the fields above do not cover. */
        public val raw: JsonElement? = null,
    ) : ConverseStreamEvent

    /** A content block is complete. */
    public data class ContentBlockStop(public val contentBlockIndex: Int) : ConverseStreamEvent

    /** The reply is complete. See [StopReason]. */
    public data class MessageStop(
        public val stopReason: String? = null,
        public val additionalModelResponseFields: JsonElement? = null,
    ) : ConverseStreamEvent

    /**
     * Token counts and timings, emitted **after** [MessageStop].
     *
     * A caller that stops collecting at `MessageStop` never sees its own token usage, which is the
     * most common way to end up unable to attribute Bedrock spend.
     */
    public data class Metadata(
        public val usage: TokenUsage? = null,
        public val metrics: ConverseMetrics? = null,
        public val trace: JsonElement? = null,
    ) : ConverseStreamEvent

    /** An event type this library does not model, carried verbatim. */
    public data class Unknown(
        public val eventType: String?,
        public val payload: JsonElement,
    ) : ConverseStreamEvent
}

/** The identity of a tool call, announced by [ConverseStreamEvent.ContentBlockStart]. */
public data class ToolUseStart(
    public val toolUseId: String,
    public val name: String,
)

@Serializable
private class MessageStartPayload(@SerialName("role") val role: String = ConversationRole.ASSISTANT)

@Serializable
private class MetadataPayload(
    @SerialName("usage") val usage: TokenUsage? = null,
    @SerialName("metrics") val metrics: ConverseMetrics? = null,
    @SerialName("trace") val trace: JsonElement? = null,
)

/**
 * Turns one decoded event-stream frame into a typed event.
 *
 * An `exception` frame **throws** rather than becoming an event. That is deliberate: a modelled
 * mid-stream failure is a failure of the call, and delivering it as a value would let a caller
 * iterating for text quietly ignore the fact that the answer stopped being produced.
 */
internal fun EventStreamMessage.toConverseEvent(): ConverseStreamEvent {
    val json = runCatching { awsJson.parseToJsonElement(payload.decodeToString()) }
        .getOrElse { JsonObject(emptyMap()) }

    if (messageType == "exception" || messageType == "error") {
        val text = (json as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.content }
        throw mapStreamException(exceptionType ?: headers[":error-code"], text)
    }

    val obj = json as? JsonObject ?: JsonObject(emptyMap())
    return when (eventType) {
        "messageStart" -> ConverseStreamEvent.MessageStart(
            role = obj["role"]?.jsonPrimitive?.content ?: ConversationRole.ASSISTANT,
        )

        "contentBlockStart" -> ConverseStreamEvent.ContentBlockStart(
            contentBlockIndex = obj.blockIndex(),
            toolUse = (obj["start"] as? JsonObject)?.get("toolUse")?.let { it as? JsonObject }?.let {
                ToolUseStart(
                    toolUseId = it["toolUseId"]?.jsonPrimitive?.content.orEmpty(),
                    name = it["name"]?.jsonPrimitive?.content.orEmpty(),
                )
            },
        )

        "contentBlockDelta" -> {
            val delta = obj["delta"] as? JsonObject
            ConverseStreamEvent.ContentBlockDelta(
                contentBlockIndex = obj.blockIndex(),
                text = delta?.get("text")?.let { (it as? JsonPrimitive)?.content },
                toolUseInput = (delta?.get("toolUse") as? JsonObject)?.get("input")
                    ?.let { (it as? JsonPrimitive)?.content },
                reasoningText = (delta?.get("reasoningContent") as? JsonObject)?.get("text")
                    ?.let { (it as? JsonPrimitive)?.content },
                raw = delta,
            )
        }

        "contentBlockStop" -> ConverseStreamEvent.ContentBlockStop(obj.blockIndex())

        "messageStop" -> ConverseStreamEvent.MessageStop(
            stopReason = obj["stopReason"]?.jsonPrimitive?.content,
            additionalModelResponseFields = obj["additionalModelResponseFields"],
        )

        "metadata" -> awsJson.decodeFromJsonElement(MetadataPayload.serializer(), obj).let {
            ConverseStreamEvent.Metadata(it.usage, it.metrics, it.trace)
        }

        else -> ConverseStreamEvent.Unknown(eventType, obj)
    }
}

private fun JsonObject.blockIndex(): Int =
    (this["contentBlockIndex"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

// -- Consuming a stream --------------------------------------------------------------------------

/**
 * Just the text, as it arrives — the shape a UI streaming tokens to a user wants.
 *
 * Drops every non-text event, **including [ConverseStreamEvent.MessageStop] and the usage
 * metadata**. That is the trade: it is the simplest possible consumption and it discards the stop
 * reason, so a reply truncated by [StopReason.MAX_TOKENS] looks exactly like one that finished.
 * Use [accumulate] when that distinction matters, which for anything but a demo it does.
 */
public fun Flow<ConverseStreamEvent>.textDeltas(): Flow<String> =
    mapNotNull { (it as? ConverseStreamEvent.ContentBlockDelta)?.text }

/**
 * Reassembles a stream into the [ConverseResponse] the non-streaming call would have returned.
 *
 * ### Why this is not a fold over concatenated text
 *
 * Deltas are **per content block**, and a reply can have several open at once — reasoning
 * alongside text, or two tool calls. Concatenating every delta regardless of
 * `contentBlockIndex` produces the right answer for a plain text reply and interleaves the
 * characters of two tool calls into unparseable JSON for the interesting one. This accumulates per
 * index and assembles the blocks in index order.
 *
 * Tool input needs the same care for a second reason: Bedrock streams it as **partial JSON text**,
 * so it is only parseable once every delta of that block has been concatenated.
 *
 * ### It consumes the whole stream
 *
 * By definition — it cannot know the reply is complete until `messageStop`, and the usage metadata
 * arrives after that. A caller that wants both live tokens *and* the assembled result should
 * `onEach { }` for display and let this consume the same flow, rather than collecting twice: the
 * flow is cold and backed by one HTTP response, so collecting it a second time issues a second
 * request and gets a different answer.
 *
 * @throws ModelStreamErrorException or another [BedrockException] if the stream carried a modelled
 *   failure part-way through. Whatever had already been emitted stays emitted.
 */
public suspend fun Flow<ConverseStreamEvent>.accumulate(): ConverseResponse {
    val texts = mutableMapOf<Int, StringBuilder>()
    val reasoning = mutableMapOf<Int, StringBuilder>()
    val toolStarts = mutableMapOf<Int, ToolUseStart>()
    val toolInputs = mutableMapOf<Int, StringBuilder>()
    var role = ConversationRole.ASSISTANT
    var stopReason: String? = null
    var additionalFields: JsonElement? = null
    var usage: TokenUsage? = null
    var metrics: ConverseMetrics? = null
    var trace: JsonElement? = null

    collect { event ->
        when (event) {
            is ConverseStreamEvent.MessageStart -> role = event.role

            is ConverseStreamEvent.ContentBlockStart ->
                event.toolUse?.let { toolStarts[event.contentBlockIndex] = it }

            is ConverseStreamEvent.ContentBlockDelta -> {
                event.text?.let {
                    texts.getOrPut(event.contentBlockIndex) { StringBuilder() }.append(it)
                }
                event.toolUseInput?.let {
                    toolInputs.getOrPut(event.contentBlockIndex) { StringBuilder() }.append(it)
                }
                event.reasoningText?.let {
                    reasoning.getOrPut(event.contentBlockIndex) { StringBuilder() }.append(it)
                }
            }

            is ConverseStreamEvent.ContentBlockStop -> Unit

            is ConverseStreamEvent.MessageStop -> {
                stopReason = event.stopReason
                additionalFields = event.additionalModelResponseFields
            }

            is ConverseStreamEvent.Metadata -> {
                usage = event.usage
                metrics = event.metrics
                trace = event.trace
            }

            is ConverseStreamEvent.Unknown -> Unit
        }
    }

    // In content-block index order, which is the order the model produced them in — the maps are
    // keyed by index precisely so this can be reconstructed after interleaved delivery.
    val indices = (texts.keys + reasoning.keys + toolStarts.keys).sorted()
    val content = indices.mapNotNull { index ->
        when {
            toolStarts.containsKey(index) -> {
                val start = toolStarts.getValue(index)
                val raw = toolInputs[index]?.toString().orEmpty()
                ContentBlock.ToolUse(
                    toolUseId = start.toolUseId,
                    name = start.name,
                    // An empty input is `{}`, not an empty string: a tool with no arguments streams
                    // no input deltas at all, and "" is not valid JSON.
                    input = runCatching { awsJson.parseToJsonElement(raw.ifBlank { "{}" }) }
                        .getOrElse { JsonObject(emptyMap()) },
                )
            }

            reasoning.containsKey(index) -> ContentBlock.Reasoning(
                JsonObject(
                    mapOf(
                        "reasoningText" to JsonObject(
                            mapOf("text" to JsonPrimitive(reasoning.getValue(index).toString())),
                        ),
                    ),
                ),
            )

            texts.containsKey(index) -> ContentBlock.Text(texts.getValue(index).toString())
            else -> null
        }
    }

    return ConverseResponse(
        output = ConverseOutput(Message(role, content)),
        stopReason = stopReason,
        usage = usage,
        metrics = metrics,
        additionalModelResponseFields = additionalFields,
        trace = trace,
    )
}
