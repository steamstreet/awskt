package com.steamstreet.awskt.bedrock

import com.steamstreet.awskt.core.awsEnv
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end proof against real Bedrock.
 *
 * ### `converseStreamReceivesFramesAcrossChunkBoundaries` is the important one
 *
 * The plan's M10 status note records that `callStreaming` has **never run against a real chunked
 * HTTP response**: `MockEngine` serves a whole body at once, so the hermetic tests prove the frame
 * *decoder* works and cannot prove the transport survives a body arriving slowly, in arbitrary
 * chunk boundaries, across many seconds. A frame split mid-read is precisely what a mock cannot
 * produce, and precisely what a real model generating tokens over several seconds does produce.
 * That test is the one that closes the gap, and it is the reason this file exists.
 *
 * Self-skips without credentials:
 *
 * ```
 * eval "$(aws configure export-credentials --profile <profile> --format env)" \
 *   && AWS_REGION=us-west-2 \
 *      SMOKE_BEDROCK_MODEL_ID=us.anthropic.claude-3-5-haiku-20241022-v1:0 \
 *      ./gradlew :aws:aws-bedrock-runtime:jvmTest
 * ```
 *
 * **These calls cost money.** They are deliberately tiny — a handful of tokens each, with
 * `maxTokens` capped — but they are real inference against a real model, unlike everything else
 * in this repository's live suites. Model access must be granted for the model in the account, and
 * an inference profile id (`us.…`) is usually needed rather than a bare model id.
 */
class LiveBedrockTest {

    private fun modelId(): String? {
        if (awsEnv("AWS_ACCESS_KEY_ID").isNullOrEmpty()) return null
        return awsEnv("SMOKE_BEDROCK_MODEL_ID")?.takeIf { it.isNotBlank() }
    }

    private fun bedrock() = BedrockRuntime { caInfo = awsEnv("SMOKE_CA_BUNDLE") }

    /** One non-streaming turn. Proves the restJson1 path, the `bedrock` signing name, and the union encoding. */
    @Test
    fun conversesWithARealModel() = runTest {
        val model = modelId() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_BEDROCK_MODEL_ID")
            return@runTest
        }

        bedrock().use { client ->
            val response = client.converse(
                ConverseRequest(
                    modelId = model,
                    messages = listOf(Message.user("Reply with exactly the word: pong")),
                    system = listOf(SystemContentBlock.text("Answer with one word and no punctuation.")),
                    inferenceConfig = InferenceConfiguration(maxTokens = 16, temperature = 0f),
                ),
            )

            assertTrue(response.text.isNotBlank(), "the model returned no text")
            assertEquals(StopReason.END_TURN, response.stopReason)
            // Usage is the field a caller needs for cost attribution, and the only proof the
            // response was deserialized past its first level.
            assertTrue((response.usage?.inputTokens ?: 0) > 0, "no input token count came back")
            println("[live] Bedrock converse -> '${response.text.trim()}' (${response.usage?.totalTokens} tokens)")
        }
    }

    /**
     * **The chunked-transport test.** A streamed generation arrives as many event-stream frames
     * over several seconds, with chunk boundaries chosen by the network rather than by a fixture.
     *
     * If `callStreaming` mishandles a partial read — a frame split across two chunks, a length
     * field arriving in pieces — this fails and nothing else in the repository would.
     */
    @Test
    fun converseStreamReceivesFramesAcrossChunkBoundaries() = runTest {
        val model = modelId() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_BEDROCK_MODEL_ID")
            return@runTest
        }

        bedrock().use { client ->
            val events = client.converseStream(
                ConverseRequest(
                    modelId = model,
                    // Long enough to span many frames and several seconds — a one-word answer would
                    // arrive in a single chunk and prove nothing about boundaries.
                    messages = listOf(Message.user("Count from 1 to 40, one number per line.")),
                    inferenceConfig = InferenceConfiguration(maxTokens = 300, temperature = 0f),
                ),
            ).toList()

            assertIs<ConverseStreamEvent.MessageStart>(events.first())
            val deltas = events.filterIsInstance<ConverseStreamEvent.ContentBlockDelta>()
            assertTrue(deltas.size > 5, "expected many deltas, got ${deltas.size} — did it really stream?")
            assertTrue(events.any { it is ConverseStreamEvent.MessageStop })
            // Metadata arrives after the stop; a caller that stops collecting there loses its usage.
            assertTrue(events.any { it is ConverseStreamEvent.Metadata })
            println("[live] Bedrock converseStream delivered ${events.size} events, ${deltas.size} deltas")
        }
    }

    /** The streamed reply must reassemble into the same shape the non-streaming call returns. */
    @Test
    fun accumulateRebuildsTheReplyFromARealStream() = runTest {
        val model = modelId() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_BEDROCK_MODEL_ID")
            return@runTest
        }

        bedrock().use { client ->
            val response = client.converseStream(
                ConverseRequest(
                    modelId = model,
                    messages = listOf(Message.user("Count from 1 to 20, separated by spaces.")),
                    inferenceConfig = InferenceConfiguration(maxTokens = 200, temperature = 0f),
                ),
            ).accumulate()

            assertTrue(response.text.isNotBlank())
            assertEquals(ConversationRole.ASSISTANT, response.message?.role)
            assertTrue((response.usage?.totalTokens ?: 0) > 0, "usage was lost during accumulation")
            println("[live] Bedrock accumulate -> ${response.text.length} chars, ${response.usage?.totalTokens} tokens")
        }
    }

    /**
     * Tool calling, end to end: the model must ask for the tool, and the reply must carry a
     * `toolUse` block whose input parses as the schema described.
     *
     * This is the only proof that the hand-written `ContentBlock` union encodes a `toolConfig` the
     * way Bedrock expects — a wrong shape is a `ValidationException`, and a subtly wrong one is a
     * model that never calls the tool.
     */
    @Test
    fun asksForAToolWithParsableInput() = runTest {
        val model = modelId() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_BEDROCK_MODEL_ID")
            return@runTest
        }

        bedrock().use { client ->
            val response = client.converse(
                ConverseRequest(
                    modelId = model,
                    messages = listOf(Message.user("What is the weather in Paris? Use the tool.")),
                    toolConfig = ToolConfiguration(
                        tools = listOf(
                            Tool.spec(
                                ToolSpecification(
                                    name = "get_weather",
                                    description = "Current weather for a named city.",
                                    inputSchema = ToolInputSchema(
                                        com.steamstreet.awskt.core.awsJson.parseToJsonElement(
                                            """{"type":"object","properties":{"city":{"type":"string"}},
                                               "required":["city"]}""",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        // Forced, so the assertion is about encoding rather than about whether the
                        // model felt like using it — which would make this test flaky by design.
                        toolChoice = ToolChoice.any(),
                    ),
                    inferenceConfig = InferenceConfiguration(maxTokens = 200, temperature = 0f),
                ),
            )

            assertEquals(StopReason.TOOL_USE, response.stopReason)
            val toolUse = response.toolUses.singleOrNull()
            assertTrue(toolUse != null, "the model did not ask for the tool")
            assertEquals("get_weather", toolUse.name)
            assertTrue(
                toolUse.input.toString().contains("city", ignoreCase = true),
                "the tool input did not carry the schema's field: ${toolUse.input}",
            )
            println("[live] Bedrock tool call -> ${toolUse.name}(${toolUse.input})")
        }
    }
}
