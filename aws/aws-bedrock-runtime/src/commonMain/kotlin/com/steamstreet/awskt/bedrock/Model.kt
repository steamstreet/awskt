package com.steamstreet.awskt.bedrock

import com.steamstreet.awskt.core.Base64BlobSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Request and response types for Bedrock's Converse API.
 *
 * ### Converse is the model-independent API, which is the whole reason to use it
 *
 * `InvokeModel` takes whatever JSON the chosen model's provider defined, so switching from Claude
 * to Llama means rewriting the request. `Converse` takes **one shape for every model** and Bedrock
 * translates. That is what makes a typed client worth writing at all — a typed `InvokeModel` would
 * be a typed wrapper around an untyped blob.
 *
 * ### Unions are modelled as sealed hierarchies with an `Unknown` arm
 *
 * `ContentBlock` is a Smithy union: on the wire it is an object with exactly one key naming the
 * variant. Modelling it as a sealed interface gives an exhaustive `when`; modelling it as a data
 * class of all-nullable fields would not. But a closed sealed hierarchy has the failure mode that
 * `aws-kms`'s KDoc argues against for enums — **AWS adds variants**, and `reasoningContent`,
 * `citationsContent` and `cachePoint` all postdate the API's launch — so a variant this library
 * does not know would be a `SerializationException` for every caller.
 *
 * [ContentBlock.Unknown] resolves that: unrecognised variants are carried as their raw
 * [JsonElement], round-trip byte-for-byte, and can be constructed deliberately to send something
 * this module does not model. The exhaustive `when` survives, and so does forward compatibility.
 *
 * ### Round-tripping is a correctness requirement, not a nicety
 *
 * A multi-turn conversation sends the assistant's previous replies back as `Message`s. For
 * reasoning models the reasoning block **must be echoed back unchanged** or Bedrock rejects the
 * turn, and the same is true of any provider-specific block a future model emits. That is why
 * [ContentBlock.Reasoning] keeps its [ContentBlock.Reasoning.raw] element rather than decomposing
 * into fields, and why [ContentBlock.Unknown] exists at all: a lossy model of a block we have to
 * hand back is worse than no model of it.
 */

/** `Message.role` values. */
public object ConversationRole {
    public const val USER: String = "user"
    public const val ASSISTANT: String = "assistant"
}

/** `stopReason` values on a [ConverseResponse]. Strings, not an enum — AWS extends the set. */
public object StopReason {
    /** The model finished its turn normally. */
    public const val END_TURN: String = "end_turn"

    /** The model wants a tool invoked. Read the [ContentBlock.ToolUse] blocks and reply with results. */
    public const val TOOL_USE: String = "tool_use"

    /** [InferenceConfiguration.maxTokens] was reached. The reply is **truncated mid-thought**. */
    public const val MAX_TOKENS: String = "max_tokens"

    public const val STOP_SEQUENCE: String = "stop_sequence"
    public const val GUARDRAIL_INTERVENED: String = "guardrail_intervened"
    public const val CONTENT_FILTERED: String = "content_filtered"
}

/** `ToolResult.status` values. */
public object ToolResultStatus {
    public const val SUCCESS: String = "success"

    /**
     * The tool failed. **Say so with this rather than by returning an error string as success** —
     * the model treats a failed result as something to recover from, and a success carrying the
     * text "error: timeout" as data to reason about.
     */
    public const val ERROR: String = "error"
}

/**
 * Where a binary attachment's bytes come from.
 *
 * **Not a data class** — it carries a `ByteArray`. Exactly one of [bytes] and [s3Location] is set,
 * enforced at construction because the wire form is a union and sending both is a
 * `ValidationException` with a message that names neither.
 */
@Serializable
public class BinarySource(
    @SerialName("bytes") @Serializable(with = Base64BlobSerializer::class)
    public val bytes: ByteArray? = null,
    @SerialName("s3Location") public val s3Location: S3Location? = null,
) {
    init {
        require((bytes != null) != (s3Location != null)) {
            "A BinarySource carries exactly one of bytes or s3Location; got " +
                "bytes=${bytes != null}, s3Location=${s3Location != null}."
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is BinarySource &&
                bytes.contentEquals(other.bytes) &&
                s3Location == other.s3Location
            )

    override fun hashCode(): Int = 31 * (bytes?.contentHashCode() ?: 0) + (s3Location?.hashCode() ?: 0)

    /** Reports the size, never the bytes. */
    override fun toString(): String =
        if (bytes != null) "BinarySource(${bytes.size} bytes)" else "BinarySource($s3Location)"

    public companion object {
        public fun bytes(bytes: ByteArray): BinarySource = BinarySource(bytes = bytes)

        public fun s3(uri: String, bucketOwner: String? = null): BinarySource =
            BinarySource(s3Location = S3Location(uri, bucketOwner))
    }
}

/** An S3 object holding an attachment. [bucketOwner] is required for a cross-account bucket. */
@Serializable
public data class S3Location(
    @SerialName("uri") public val uri: String,
    @SerialName("bucketOwner") public val bucketOwner: String? = null,
)

/**
 * One block of a message's content.
 *
 * A Smithy union: on the wire, an object with exactly one key. See the file KDoc for why this is a
 * sealed hierarchy *with* an [Unknown] arm rather than either alone.
 */
@Serializable(with = ContentBlockSerializer::class)
public sealed interface ContentBlock {

    /** Plain text — the overwhelmingly common block. */
    public data class Text(public val text: String) : ContentBlock

    /**
     * An image. [format] is `png`, `jpeg`, `gif` or `webp`.
     *
     * **Not a data class** — [source] carries bytes.
     */
    public class Image(public val format: String, public val source: BinarySource) : ContentBlock {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Image && format == other.format && source == other.source)

        override fun hashCode(): Int = 31 * format.hashCode() + source.hashCode()
        override fun toString(): String = "Image(format=$format, source=$source)"
    }

    /**
     * A document. [format] is `pdf`, `csv`, `doc`, `docx`, `xls`, `xlsx`, `html`, `txt` or `md`.
     *
     * @param name **must be unique within the request** and, per AWS, should avoid characters the
     *   model might read as instructions — it is passed to the model, so it is untrusted input's
     *   first foothold in a prompt.
     */
    public class Document(
        public val format: String,
        public val name: String,
        public val source: BinarySource,
    ) : ContentBlock {
        override fun equals(other: Any?): Boolean =
            this === other || (
                other is Document && format == other.format && name == other.name &&
                    source == other.source
                )

        override fun hashCode(): Int = 31 * (31 * format.hashCode() + name.hashCode()) + source.hashCode()
        override fun toString(): String = "Document(format=$format, name=$name, source=$source)"
    }

    /**
     * The model is asking for a tool to be run.
     *
     * Appears in the **assistant's** message when [ConverseResponse.stopReason] is
     * [StopReason.TOOL_USE]. Reply by appending that assistant message to the conversation
     * unchanged, then a user message carrying a [ToolResult] with the same [toolUseId].
     *
     * @param input the arguments, as the model produced them. A [JsonElement] rather than a typed
     *   shape because the schema is the caller's own [ToolSpecification.inputSchema] — and because
     *   **a model can produce input that does not satisfy its own declared schema**, so a typed
     *   deserialization here would turn a recoverable "ask it again" into a hard failure.
     */
    public data class ToolUse(
        public val toolUseId: String,
        public val name: String,
        public val input: JsonElement,
    ) : ContentBlock

    /**
     * The result of running a tool, sent back in a **user** message.
     *
     * @param toolUseId must match the [ToolUse.toolUseId] being answered. Every `ToolUse` in the
     *   assistant's turn must be answered before the model will continue.
     */
    public data class ToolResult(
        public val toolUseId: String,
        public val content: List<ToolResultContent>,
        public val status: String? = null,
    ) : ContentBlock

    /**
     * A reasoning model's chain of thought.
     *
     * **[raw] is kept whole and echoed back unchanged**, which is a correctness requirement rather
     * than laziness: a multi-turn conversation must return the assistant's reasoning block
     * byte-for-byte, signature included, or Bedrock rejects the turn. Decomposing it into fields
     * and re-serializing risks reordering keys or dropping one this module does not know about.
     * [text] reads the human-readable part out of it for display.
     */
    public class Reasoning(public val raw: JsonElement) : ContentBlock {
        /** The reasoning text, when present. Absent for a redacted block. */
        public val text: String?
            get() = (raw as? JsonObject)?.get("reasoningText")?.let { it as? JsonObject }
                ?.get("text")?.let { (it as? JsonPrimitive)?.content }

        override fun equals(other: Any?): Boolean = this === other || (other is Reasoning && raw == other.raw)
        override fun hashCode(): Int = raw.hashCode()
        override fun toString(): String = "Reasoning(text=${text?.length ?: 0} chars)"
    }

    /**
     * A prompt-caching checkpoint.
     *
     * Everything **before** this point in the conversation is eligible for reuse on the next
     * request, which on a long system prompt or a large document is the difference between paying
     * for it once and paying for it every turn. Support and minimum cacheable lengths vary by
     * model.
     */
    public data class CachePoint(public val type: String = "default") : ContentBlock

    /**
     * A block this library does not model, carried verbatim.
     *
     * The forward-compatibility arm — see the file KDoc. It round-trips exactly, so an unrecognised
     * block in an assistant reply can be handed straight back in the next turn, and it doubles as
     * the escape hatch for *sending* a block this module has no type for:
     *
     * ```kotlin
     * ContentBlock.Unknown("video", buildJsonObject { … })
     * ```
     */
    public data class Unknown(public val key: String, public val value: JsonElement) : ContentBlock
}

/**
 * One block of a tool's result.
 *
 * [json] is the form to prefer for structured data: it reaches the model as structured content
 * rather than as a string the model has to parse back, and the difference shows up in how reliably
 * it reads nested fields.
 */
@Serializable
public data class ToolResultContent(
    @SerialName("text") public val text: String? = null,
    @SerialName("json") public val json: JsonElement? = null,
) {
    public companion object {
        public fun text(text: String): ToolResultContent = ToolResultContent(text = text)
        public fun json(value: JsonElement): ToolResultContent = ToolResultContent(json = value)
    }
}

/**
 * Reads and writes a `ContentBlock` as its single-key wire object.
 *
 * Public so that a caller extending this module — a new block type, a differential harness — can
 * reuse it rather than re-deriving the union encoding.
 */
public object ContentBlockSerializer : KSerializer<ContentBlock> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ContentBlock")

    override fun serialize(encoder: Encoder, value: ContentBlock) {
        val json = encoder as? JsonEncoder
            ?: throw UnsupportedOperationException("ContentBlock can only be written to JSON.")
        json.encodeJsonElement(
            buildJsonObject {
                when (value) {
                    is ContentBlock.Text -> put("text", JsonPrimitive(value.text))
                    is ContentBlock.Image -> put(
                        "image",
                        buildJsonObject {
                            put("format", JsonPrimitive(value.format))
                            put("source", json.json.encodeToJsonElement(BinarySource.serializer(), value.source))
                        },
                    )

                    is ContentBlock.Document -> put(
                        "document",
                        buildJsonObject {
                            put("format", JsonPrimitive(value.format))
                            put("name", JsonPrimitive(value.name))
                            put("source", json.json.encodeToJsonElement(BinarySource.serializer(), value.source))
                        },
                    )

                    is ContentBlock.ToolUse -> put(
                        "toolUse",
                        buildJsonObject {
                            put("toolUseId", JsonPrimitive(value.toolUseId))
                            put("name", JsonPrimitive(value.name))
                            put("input", value.input)
                        },
                    )

                    is ContentBlock.ToolResult -> put(
                        "toolResult",
                        buildJsonObject {
                            put("toolUseId", JsonPrimitive(value.toolUseId))
                            put(
                                "content",
                                json.json.encodeToJsonElement(
                                    kotlinx.serialization.builtins.ListSerializer(ToolResultContent.serializer()),
                                    value.content,
                                ),
                            )
                            value.status?.let { put("status", JsonPrimitive(it)) }
                        },
                    )

                    // Verbatim, both of them — see the file KDoc on round-tripping.
                    is ContentBlock.Reasoning -> put("reasoningContent", value.raw)
                    is ContentBlock.Unknown -> put(value.key, value.value)

                    is ContentBlock.CachePoint -> put(
                        "cachePoint",
                        buildJsonObject { put("type", JsonPrimitive(value.type)) },
                    )
                }
            },
        )
    }

    override fun deserialize(decoder: Decoder): ContentBlock {
        val json = decoder as? JsonDecoder
            ?: throw UnsupportedOperationException("ContentBlock can only be read from JSON.")
        val obj = json.decodeJsonElement().jsonObject
        // `firstOrNull` rather than `single`: a union carries one key, and a response that somehow
        // carried two should not fail the whole turn over the extra.
        val (key, value) = obj.entries.firstOrNull()?.toPair()
            ?: return ContentBlock.Unknown("", JsonObject(emptyMap()))

        return when (key) {
            "text" -> ContentBlock.Text(value.jsonPrimitive.content)
            "image" -> ContentBlock.Image(
                format = value.jsonObject["format"]!!.jsonPrimitive.content,
                source = json.json.decodeFromJsonElement(BinarySource.serializer(), value.jsonObject["source"]!!),
            )

            "document" -> ContentBlock.Document(
                format = value.jsonObject["format"]!!.jsonPrimitive.content,
                name = value.jsonObject["name"]!!.jsonPrimitive.content,
                source = json.json.decodeFromJsonElement(BinarySource.serializer(), value.jsonObject["source"]!!),
            )

            "toolUse" -> ContentBlock.ToolUse(
                toolUseId = value.jsonObject["toolUseId"]!!.jsonPrimitive.content,
                name = value.jsonObject["name"]!!.jsonPrimitive.content,
                input = value.jsonObject["input"] ?: JsonObject(emptyMap()),
            )

            "toolResult" -> ContentBlock.ToolResult(
                toolUseId = value.jsonObject["toolUseId"]!!.jsonPrimitive.content,
                content = value.jsonObject["content"]?.let {
                    json.json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(ToolResultContent.serializer()), it,
                    )
                } ?: emptyList(),
                status = value.jsonObject["status"]?.jsonPrimitive?.content,
            )

            "reasoningContent" -> ContentBlock.Reasoning(value)
            "cachePoint" -> ContentBlock.CachePoint(
                value.jsonObject["type"]?.jsonPrimitive?.content ?: "default",
            )

            else -> ContentBlock.Unknown(key, value)
        }
    }
}

/** One turn of the conversation. */
@Serializable
public data class Message(
    @SerialName("role") public val role: String,
    @SerialName("content") public val content: List<ContentBlock>,
) {
    /** Every [ContentBlock.Text] block's text, joined — the usual way to read a reply. */
    public val text: String
        get() = content.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }

    /** Every tool the model asked for in this message. Empty unless `stopReason` was `tool_use`. */
    public val toolUses: List<ContentBlock.ToolUse>
        get() = content.filterIsInstance<ContentBlock.ToolUse>()

    public companion object {
        /** A user turn carrying one text block. */
        public fun user(text: String): Message =
            Message(ConversationRole.USER, listOf(ContentBlock.Text(text)))

        /** An assistant turn carrying one text block — for seeding or replaying a conversation. */
        public fun assistant(text: String): Message =
            Message(ConversationRole.ASSISTANT, listOf(ContentBlock.Text(text)))
    }
}

/**
 * A system prompt block.
 *
 * A separate type from [ContentBlock] because the wire union is genuinely different: system content
 * admits `text`, `guardContent` and `cachePoint`, and **not** images, documents or tool blocks.
 */
@Serializable
public data class SystemContentBlock(
    @SerialName("text") public val text: String? = null,
    @SerialName("cachePoint") public val cachePoint: CachePointSpec? = null,
) {
    public companion object {
        public fun text(text: String): SystemContentBlock = SystemContentBlock(text = text)

        /** Marks everything before this point cacheable. See [ContentBlock.CachePoint]. */
        public fun cachePoint(): SystemContentBlock = SystemContentBlock(cachePoint = CachePointSpec())
    }
}

@Serializable
public data class CachePointSpec(@SerialName("type") public val type: String = "default")

/**
 * Sampling and length limits.
 *
 * @param maxTokens the **output** ceiling, not the context window. Hitting it stops the reply
 *   mid-sentence with [StopReason.MAX_TOKENS] rather than raising anything, which is why that stop
 *   reason is worth branching on.
 * @param temperature and [topP] are alternative ways to control randomness and **should not both
 *   be set** — most providers document that tuning one and leaving the other at its default is the
 *   supported combination.
 * @param stopSequences the model stops when it would emit one of these. The sequence itself is not
 *   included in the output.
 */
@Serializable
public data class InferenceConfiguration(
    @SerialName("maxTokens") public val maxTokens: Int? = null,
    @SerialName("temperature") public val temperature: Float? = null,
    @SerialName("topP") public val topP: Float? = null,
    @SerialName("stopSequences") public val stopSequences: List<String>? = null,
)

/** What a tool takes. [json] is a JSON Schema object describing the arguments. */
@Serializable
public data class ToolInputSchema(
    @SerialName("json") public val json: JsonElement,
)

/**
 * A tool the model may call.
 *
 * @param description **the field that decides whether the tool gets used correctly.** The model
 *   picks tools by reading this, not by reading the name, so "look up an order by its id, returns
 *   status and line items" earns its length over "order lookup".
 */
@Serializable
public data class ToolSpecification(
    @SerialName("name") public val name: String,
    @SerialName("inputSchema") public val inputSchema: ToolInputSchema,
    @SerialName("description") public val description: String? = null,
)

/** One entry in [ToolConfiguration.tools]: a tool, or a cache checkpoint between tools. */
@Serializable
public data class Tool(
    @SerialName("toolSpec") public val toolSpec: ToolSpecification? = null,
    @SerialName("cachePoint") public val cachePoint: CachePointSpec? = null,
) {
    public companion object {
        public fun spec(spec: ToolSpecification): Tool = Tool(toolSpec = spec)
    }
}

/**
 * How the model chooses among the tools.
 *
 * Exactly one field is set. [auto] lets the model decide, [any] forces it to call *some* tool, and
 * [tool] forces a named one — the last two are how a caller stops a model answering in prose when
 * it was supposed to produce structured output.
 */
@Serializable
public data class ToolChoice(
    @SerialName("auto") public val auto: JsonObject? = null,
    @SerialName("any") public val any: JsonObject? = null,
    @SerialName("tool") public val tool: SpecificToolChoice? = null,
) {
    public companion object {
        public fun auto(): ToolChoice = ToolChoice(auto = JsonObject(emptyMap()))
        public fun any(): ToolChoice = ToolChoice(any = JsonObject(emptyMap()))
        public fun tool(name: String): ToolChoice = ToolChoice(tool = SpecificToolChoice(name))
    }
}

@Serializable
public data class SpecificToolChoice(@SerialName("name") public val name: String)

/** The tools available for this turn. Not all models support tools; those that do not reject this. */
@Serializable
public data class ToolConfiguration(
    @SerialName("tools") public val tools: List<Tool>,
    @SerialName("toolChoice") public val toolChoice: ToolChoice? = null,
)

/** Token counts. [cacheReadInputTokens] is what prompt caching actually saved. */
@Serializable
public data class TokenUsage(
    @SerialName("inputTokens") public val inputTokens: Int = 0,
    @SerialName("outputTokens") public val outputTokens: Int = 0,
    @SerialName("totalTokens") public val totalTokens: Int = 0,
    @SerialName("cacheReadInputTokens") public val cacheReadInputTokens: Int? = null,
    @SerialName("cacheWriteInputTokens") public val cacheWriteInputTokens: Int? = null,
)

/** Server-side timing. Not the wall clock the caller saw, which includes the network. */
@Serializable
public data class ConverseMetrics(
    @SerialName("latencyMs") public val latencyMs: Long = 0,
)

// -- Converse ------------------------------------------------------------------------------------

/**
 * `Converse`.
 *
 * [modelId] is a **path parameter**, not a body field, which is why this type is not
 * `@Serializable` and is converted to a wire form before sending.
 *
 * @param modelId a model id (`anthropic.claude-3-5-sonnet-20241022-v2:0`), an inference profile id
 *   (`us.anthropic.claude-…`), or a full ARN. **An ARN contains `/` and `:`**, and it occupies one
 *   path segment, so it is percent-encoded whole — see `BedrockRuntime`'s `modelPath`.
 * @param system the system prompt. A separate field from [messages] rather than a message with a
 *   `system` role, which is what the older completion APIs did.
 * @param additionalModelRequestFields provider-specific parameters Converse's common shape does not
 *   cover — Anthropic's `top_k`, or `thinking`. The escape hatch that keeps the typed API from
 *   being a ceiling.
 */
public data class ConverseRequest(
    public val modelId: String,
    public val messages: List<Message>,
    public val system: List<SystemContentBlock>? = null,
    public val inferenceConfig: InferenceConfiguration? = null,
    public val toolConfig: ToolConfiguration? = null,
    public val additionalModelRequestFields: JsonElement? = null,
    public val additionalModelResponseFieldPaths: List<String>? = null,
    public val guardrailConfig: JsonElement? = null,
)

/** The wire body of a `Converse` — everything except the path parameter. */
@Serializable
internal data class ConverseBody(
    @SerialName("messages") val messages: List<Message>,
    @SerialName("system") val system: List<SystemContentBlock>? = null,
    @SerialName("inferenceConfig") val inferenceConfig: InferenceConfiguration? = null,
    @SerialName("toolConfig") val toolConfig: ToolConfiguration? = null,
    @SerialName("additionalModelRequestFields") val additionalModelRequestFields: JsonElement? = null,
    @SerialName("additionalModelResponseFieldPaths")
    val additionalModelResponseFieldPaths: List<String>? = null,
    @SerialName("guardrailConfig") val guardrailConfig: JsonElement? = null,
)

internal fun ConverseRequest.toBody(): ConverseBody = ConverseBody(
    messages = messages,
    system = system,
    inferenceConfig = inferenceConfig,
    toolConfig = toolConfig,
    additionalModelRequestFields = additionalModelRequestFields,
    additionalModelResponseFieldPaths = additionalModelResponseFieldPaths,
    guardrailConfig = guardrailConfig,
)

/** Wraps the reply message. A one-field union in the API, modelled as the struct it is in practice. */
@Serializable
public data class ConverseOutput(
    @SerialName("message") public val message: Message? = null,
)

/**
 * `Converse`'s result.
 *
 * @property stopReason **branch on this before reading [text]**. [StopReason.TOOL_USE] means the
 *   reply is a request for tool calls rather than an answer, and [StopReason.MAX_TOKENS] means the
 *   text is truncated mid-thought rather than finished.
 */
@Serializable
public data class ConverseResponse(
    @SerialName("output") public val output: ConverseOutput? = null,
    @SerialName("stopReason") public val stopReason: String? = null,
    @SerialName("usage") public val usage: TokenUsage? = null,
    @SerialName("metrics") public val metrics: ConverseMetrics? = null,
    @SerialName("additionalModelResponseFields")
    public val additionalModelResponseFields: JsonElement? = null,
    @SerialName("trace") public val trace: JsonElement? = null,
) {
    /** The reply message, or null if the response carried none. */
    public val message: Message? get() = output?.message

    /** The reply's text blocks joined. Empty when the model asked for tools instead of answering. */
    public val text: String get() = message?.text.orEmpty()

    /** Every tool the model asked for. Non-empty exactly when [stopReason] is [StopReason.TOOL_USE]. */
    public val toolUses: List<ContentBlock.ToolUse> get() = message?.toolUses.orEmpty()
}
