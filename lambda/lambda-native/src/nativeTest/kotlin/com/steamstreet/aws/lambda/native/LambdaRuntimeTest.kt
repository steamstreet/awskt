package com.steamstreet.aws.lambda.native

import com.steamstreet.aws.lambda.lambdaContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val BASE_URL = "http://runtime.invalid/2018-06-01"

/**
 * Drives the Runtime API loop against a [MockEngine], so each rule of the protocol is asserted
 * rather than assumed. Every test here corresponds to a defect in the 2.3.x runtime this module was
 * ported from; each one fails if the corresponding fix is reverted.
 */
class LambdaRuntimeTest {

    private class Recorder {
        val posts = mutableListOf<HttpRequestData>()

        fun bodyOf(request: HttpRequestData): String = (request.body as TextContent).text
    }

    /**
     * Builds a client whose `/invocation/next` returns one event with the given headers, and which
     * records every POST the runtime makes.
     */
    private fun clientFor(
        recorder: Recorder,
        event: String = """{"hello":"world"}""",
        requestId: String = "req-1",
        deadlineEpochMillis: Long = nowEpochMillis() + 30_000,
        traceId: String? = null
    ): HttpClient {
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/invocation/next")) {
                val headers = buildList {
                    add("Lambda-Runtime-Aws-Request-Id" to requestId)
                    add("Lambda-Runtime-Deadline-Ms" to deadlineEpochMillis.toString())
                    if (traceId != null) add("Lambda-Runtime-Trace-Id" to traceId)
                }
                respond(
                    content = event,
                    headers = headersOf(
                        *headers.map { (name, value) -> name to listOf(value) }.toTypedArray()
                    )
                )
            } else {
                recorder.posts += request
                respond(content = "")
            }
        }
        return HttpClient(engine)
    }

    @Test
    fun postsTheHandlerResultToTheResponseEndpoint() = runBlocking {
        val recorder = Recorder()
        val runtime = LambdaRuntime({ event -> """{"echoed":$event}""" }, clientFor(recorder))

        runtime.processNextInvocation(BASE_URL)

        assertEquals(1, recorder.posts.size)
        val post = recorder.posts.single()
        assertEquals("$BASE_URL/runtime/invocation/req-1/response", post.url.toString())
        assertEquals("""{"echoed":{"hello":"world"}}""", recorder.bodyOf(post))
    }

    @Test
    fun populatesTheLambdaContextFromTheResponseHeaders() = runBlocking {
        val recorder = Recorder()
        val runtime = LambdaRuntime({ "{}" }, clientFor(recorder, requestId = "abc-123"))

        runtime.processNextInvocation(BASE_URL)

        assertEquals("abc-123", lambdaContext.requestId)
    }

    /**
     * Fix 2: the error POST must carry `Lambda-Runtime-Function-Error-Type`. Without it the console
     * records a failure with no attributable type.
     */
    @Test
    fun reportsHandlerFailureWithTheFunctionErrorTypeHeader() = runBlocking {
        val recorder = Recorder()
        val runtime = LambdaRuntime(
            { throw IllegalStateException("handler blew up") },
            clientFor(recorder)
        )

        runtime.processNextInvocation(BASE_URL)

        val post = recorder.posts.single()
        assertEquals("$BASE_URL/runtime/invocation/req-1/error", post.url.toString())
        assertEquals("IllegalStateException", post.headers["Lambda-Runtime-Function-Error-Type"])

        val payload = Json.parseToJsonElement(recorder.bodyOf(post)).jsonObject
        assertEquals("handler blew up", payload["errorMessage"]?.jsonPrimitive?.content)
        assertEquals("IllegalStateException", payload["errorType"]?.jsonPrimitive?.content)
        assertTrue(payload["stackTrace"]!!.jsonArray.isNotEmpty(), "stack trace should not be empty")
    }

    /**
     * Fix 4: `catch (e: Exception)` let an [Error] escape and kill the poll loop with the invocation
     * still outstanding. This test throws a Throwable that is *not* an Exception; if the catch is
     * narrowed back to Exception it propagates and the test fails.
     */
    @Test
    fun reportsAThrowableThatIsNotAnException() = runBlocking {
        val recorder = Recorder()
        val runtime = LambdaRuntime(
            { throw NotImplementedError("not an Exception") },
            clientFor(recorder)
        )

        runtime.processNextInvocation(BASE_URL)

        val post = recorder.posts.single()
        assertContains(post.url.toString(), "/error")
        assertEquals("NotImplementedError", post.headers["Lambda-Runtime-Function-Error-Type"])
    }

    /**
     * The counterpart to [reportsAThrowableThatIsNotAnException], and a carve-out from it rather
     * than a reversal: cancellation means the scope running the loop is being torn down, so there
     * is nothing left to report against and reporting anyway attributes a shutdown to the handler's
     * code. Remove the `CancellationException` catch and the runtime POSTs a function error here.
     */
    @Test
    fun doesNotReportCancellationAsAFunctionError() = runBlocking {
        val recorder = Recorder()
        val runtime = LambdaRuntime(
            { throw CancellationException("scope cancelled") },
            clientFor(recorder)
        )

        assertFailsWith<CancellationException> { runtime.processNextInvocation(BASE_URL) }

        assertTrue(
            recorder.posts.isEmpty(),
            "cancellation must not be POSTed to /error, but was: ${recorder.posts.map { it.url }}"
        )
    }

    /**
     * Fix 3: `Lambda-Runtime-Trace-Id` has to be re-exported as `_X_AMZN_TRACE_ID` on every
     * invocation, because X-Ray reads it from the environment. Nothing fails loudly when this is
     * missing — traces just never appear.
     */
    @Test
    fun exportsTheTraceIdIntoTheEnvironment() = runBlocking {
        val recorder = Recorder()
        val traceId = "Root=1-5759e988-bd862e3fe1be46a994272793;Sampled=1"
        val runtime = LambdaRuntime({ "{}" }, clientFor(recorder, traceId = traceId))

        runtime.processNextInvocation(BASE_URL)

        assertEquals(traceId, nativeGetEnv("_X_AMZN_TRACE_ID"))
    }

    /**
     * Fix 1: an initialization failure must reach `POST /runtime/init/error`, otherwise a bad
     * configuration kills the process with nothing recorded against the function.
     */
    @Test
    fun reportsInitializationFailureToTheInitErrorEndpoint() = runBlocking {
        val recorder = Recorder()
        val runtime = LambdaRuntime({ "{}" }, clientFor(recorder))

        runtime.reportInitializationError(BASE_URL, IllegalArgumentException("bad config"))

        val post = recorder.posts.single()
        assertEquals("$BASE_URL/runtime/init/error", post.url.toString())
        assertEquals("IllegalArgumentException", post.headers["Lambda-Runtime-Function-Error-Type"])
        assertEquals(
            "bad config",
            Json.parseToJsonElement(recorder.bodyOf(post)).jsonObject["errorMessage"]?.jsonPrimitive?.content
        )
    }

    /**
     * A failure while reporting a failure must not escape — that would kill the loop the catch was
     * protecting.
     */
    @Test
    fun survivesTheRuntimeApiRejectingTheErrorReport() = runBlocking {
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                respond(
                    content = "{}",
                    headers = headersOf(
                        "Lambda-Runtime-Aws-Request-Id" to listOf("req-1"),
                        "Lambda-Runtime-Deadline-Ms" to listOf((nowEpochMillis() + 30_000).toString())
                    )
                )
            } else {
                throw RuntimeException("Runtime API unreachable")
            }
        }
        val runtime = LambdaRuntime({ throw IllegalStateException("boom") }, HttpClient(engine))

        // Completing at all is the assertion: an escaping throwable fails the test.
        runtime.processNextInvocation(BASE_URL)
    }

    /**
     * Fix 5: `remainingTimeInMillis` was a snapshot taken when the invocation started, so it always
     * reported the full timeout. Reading it twice either side of a real delay must show it falling.
     */
    @Test
    fun remainingTimeCountsDownAsTheInvocationRuns() = runBlocking {
        val context = NativeLambdaContext(
            requestId = "req-1",
            functionName = "fn",
            deadlineEpochMillis = nowEpochMillis() + 30_000
        )

        val first = context.remainingTimeInMillis
        delay(120)
        val second = context.remainingTimeInMillis

        assertTrue(
            second < first,
            "remaining time should fall as the invocation runs, but went $first -> $second"
        )
    }

    @Test
    fun remainingTimeIsClampedAtZeroOnceTheDeadlineHasPassed() {
        val context = NativeLambdaContext(
            requestId = "req-1",
            functionName = "fn",
            deadlineEpochMillis = nowEpochMillis() - 10_000
        )

        assertEquals(0, context.remainingTimeInMillis)
    }

    @Test
    fun readsTheEventBody() = runBlocking {
        val recorder = Recorder()
        var seen: String? = null
        val runtime = LambdaRuntime({ event -> seen = event; "{}" }, clientFor(recorder, event = """{"a":1}"""))

        runtime.processNextInvocation(BASE_URL)

        assertNotNull(seen)
        assertEquals("""{"a":1}""", seen)
    }
}
