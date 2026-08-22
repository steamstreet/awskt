package com.steamstreet.awskt.lambda

import com.steamstreet.awskt.core.awsEnv
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end proof against real Lambda. Self-skips without credentials:
 *
 * ```
 * eval "$(aws configure export-credentials --profile <profile> --format env)" \
 *   && AWS_REGION=us-west-2 \
 *      SMOKE_LAMBDA_FUNCTION=awskt-smoke-echo \
 *      ./gradlew :aws:aws-lambda:jvmTest
 * ```
 *
 * ### What the fixture has to be
 *
 * **Any function that runs and returns without throwing.** The suite sends `{"awskt":"live"}` and
 * asserts almost nothing about the answer, because it cannot know what the function does — what it
 * proves is the *wire*: the path, the signature, the headers Lambda answers with, and the
 * base64 log tail. An echo function makes the output easier to read and is not required.
 *
 * A function whose handler *fails* still passes the first test, deliberately: a `200` carrying
 * `X-Amz-Function-Error` is exactly the case this module exists to distinguish, so the test asserts
 * the distinction rather than requiring a green fixture. It prints which of the two happened.
 *
 * **It really invokes**, so point it at something cheap and idempotent. Set
 * `SMOKE_LAMBDA_STREAM_FUNCTION` as well — to a function configured with
 * `InvokeMode = RESPONSE_STREAM` — to cover the streaming path; without it that test skips.
 */
class LiveLambdaTest {

    private fun functionName(): String? {
        if (awsEnv("AWS_ACCESS_KEY_ID").isNullOrEmpty()) return null
        return awsEnv("SMOKE_LAMBDA_FUNCTION")?.takeIf { it.isNotBlank() }
    }

    private fun lambda() = Lambda { caInfo = awsEnv("SMOKE_CA_BUNDLE") }

    /**
     * The whole synchronous path: sign, invoke, read the payload, the executed version and the
     * decoded log tail.
     *
     * The log tail is the interesting half — it is the only base64 header in this module, and it is
     * the one thing `MockEngine` cannot confirm Lambda actually spells the way this client reads it.
     */
    @Test
    fun invokesAFunctionAndReadsTheLogTail() = runTest {
        val name = functionName() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_LAMBDA_FUNCTION")
            return@runTest
        }

        lambda().use { lambda ->
            val response = lambda.invoke(
                InvokeRequest(
                    functionName = name,
                    payload = """{"awskt":"live"}""".encodeToByteArray(),
                    logType = LogType.TAIL,
                    clientContextJson = """{"custom":{"suite":"awskt-live"}}""",
                ),
            )

            assertEquals(200, response.statusCode)
            val tail = response.logTail
            assertTrue(tail != null && tail.isNotBlank(), "LogType.TAIL should have produced logs")
            assertTrue(tail.contains("REPORT"), "the tail should reach Lambda's own REPORT line")

            if (response.isFunctionError) {
                println("[live] the function failed, which still proves the wire: ${response.payloadText}")
            } else {
                println("[live] payload=${response.payloadText} version=${response.executedVersion}")
            }
        }
    }

    /** `DryRun` answers `204` and runs nothing — the cheapest proof that the role may invoke. */
    @Test
    fun dryRunValidatesPermissions() = runTest {
        val name = functionName() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_LAMBDA_FUNCTION")
            return@runTest
        }

        lambda().use { it.dryRun(name) }
    }

    /** `Event` answers `202` with an empty body. The invocation itself happens out of sight. */
    @Test
    fun queuesAnAsynchronousInvocation() = runTest {
        val name = functionName() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_LAMBDA_FUNCTION")
            return@runTest
        }

        lambda().use { lambda ->
            val response = lambda.invoke(
                InvokeRequest(
                    functionName = name,
                    payload = """{"awskt":"live-async"}""".encodeToByteArray(),
                    invocationType = InvocationType.EVENT,
                ),
            )
            assertEquals(202, response.statusCode)
            assertEquals(0, response.payload.size)
        }
    }

    /** A missing function is the one error this suite can provoke without breaking anything. */
    @Test
    fun reportsAMissingFunction() = runTest {
        if (awsEnv("AWS_ACCESS_KEY_ID").isNullOrEmpty()) {
            println("[live] skipped — set AWS credentials")
            return@runTest
        }

        lambda().use { lambda ->
            try {
                lambda.invoke(InvokeRequest("awskt-live-does-not-exist-4b1c9f"))
                throw AssertionError("expected ResourceNotFoundException")
            } catch (expected: ResourceNotFoundException) {
                assertEquals(404, expected.statusCode)
            }
        }
    }

    /**
     * The streaming path against a real chunked response.
     *
     * `MockEngine` serves a body in one piece, so the hermetic tests prove the frame decoder works
     * and cannot prove the transport survives a frame split across two network reads — the same gap
     * `aws-bedrock-runtime`'s live stream test exists to close.
     */
    @Test
    fun streamsAResponse() = runTest {
        if (awsEnv("AWS_ACCESS_KEY_ID").isNullOrEmpty()) {
            println("[live] skipped — set AWS credentials")
            return@runTest
        }
        val name = awsEnv("SMOKE_LAMBDA_STREAM_FUNCTION")?.takeIf { it.isNotBlank() } ?: run {
            println("[live] skipped — set SMOKE_LAMBDA_STREAM_FUNCTION to a RESPONSE_STREAM function")
            return@runTest
        }

        lambda().use { lambda ->
            val events = lambda.invokeWithResponseStream(
                InvokeRequest(name, """{"awskt":"live-stream"}""".encodeToByteArray()),
            ).toList()

            assertTrue(events.isNotEmpty(), "a stream always ends with a terminator")
            assertTrue(events.last() is InvokeStreamEvent.Complete, "the last event must be the terminator")
            val chunks = events.filterIsInstance<InvokeStreamEvent.PayloadChunk>()
            println("[live] streamed ${chunks.size} chunk(s), ${chunks.sumOf { it.payload.size }} bytes")
        }
    }
}
