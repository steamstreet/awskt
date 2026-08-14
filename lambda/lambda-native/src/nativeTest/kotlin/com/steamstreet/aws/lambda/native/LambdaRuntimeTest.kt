package com.steamstreet.aws.lambda.native

import com.steamstreet.aws.lambda.lambdaContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE_URL = "http://runtime.invalid/2018-06-01"

/**
 * Drives the Runtime API loop against a [MockEngine], so each rule of the protocol is asserted
 * rather than assumed. Every test here corresponds to a defect in the 2.3.x runtime this module was
 * ported from; each one fails if the corresponding fix is reverted.
 */
class LambdaRuntimeTest {

    /**
     * Stands in for the process exit, so a test can assert that the runtime decided to exit without
     * the test process actually going away.
     */
    private class ExitSignal(val code: Int) : RuntimeException("exitProcess($code)")

    private class Recorder {
        val posts = mutableListOf<HttpRequestData>()

        /** Everything the runtime wrote to stderr, captured instead of printed. */
        val logged = mutableListOf<String>()

        /** The code the runtime exited with, or null if it never tried to. */
        var exitCode: Int? = null

        val logError: (String) -> Unit = { logged += it }

        val exitProcess: (Int) -> Nothing = { code ->
            exitCode = code
            throw ExitSignal(code)
        }

        fun bodyOf(request: HttpRequestData): String = (request.body as TextContent).text

        fun loggedText(): String = logged.joinToString("\n")
    }

    /**
     * Builds a client whose `/invocation/next` returns one event with the given headers, and which
     * records every POST the runtime makes.
     *
     * [nextStatus] and [postStatus] exist because the Runtime API signals refusal by status code
     * and nothing else: it answers, so no exception is thrown, and the runtime only learns the
     * result was rejected by looking.
     *
     * [postFailures] is the other kind of failure, and the one a status code cannot express: the
     * first [postFailures] POST attempts throw instead of answering, which is what a dropped
     * connection to the Runtime API looks like from inside the runtime.
     */
    private fun clientFor(
        recorder: Recorder,
        event: String = """{"hello":"world"}""",
        requestId: String = "req-1",
        deadlineEpochMillis: Long = nowEpochMillis() + 30_000,
        traceId: String? = null,
        nextStatus: HttpStatusCode = HttpStatusCode.OK,
        postStatus: HttpStatusCode = HttpStatusCode.Accepted,
        postBody: String = "",
        postFailures: Int = 0
    ): HttpClient {
        var postAttempts = 0
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/invocation/next")) {
                val headers = buildList {
                    add("Lambda-Runtime-Aws-Request-Id" to requestId)
                    add("Lambda-Runtime-Deadline-Ms" to deadlineEpochMillis.toString())
                    if (traceId != null) add("Lambda-Runtime-Trace-Id" to traceId)
                }
                respond(
                    content = event,
                    status = nextStatus,
                    headers = headersOf(
                        *headers.map { (name, value) -> name to listOf(value) }.toTypedArray()
                    )
                )
            } else {
                recorder.posts += request
                postAttempts++
                if (postAttempts <= postFailures) {
                    throw RuntimeException("Runtime API connection failed (attempt $postAttempts)")
                }
                respond(content = postBody, status = postStatus)
            }
        }
        return HttpClient(engine)
    }

    private fun runtimeFor(
        recorder: Recorder,
        client: HttpClient,
        handler: suspend (String) -> String = { "{}" }
    ): LambdaRuntime = LambdaRuntime(handler, client, recorder.logError, recorder.exitProcess)

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
     * The environment outlives the invocation, so an invocation that arrives with no trace header
     * has to *clear* `_X_AMZN_TRACE_ID` rather than leave the last one in place. Leaving it does not
     * merely lose a trace: the untraced invocation emits segments attributed to the previous
     * invocation's trace, which reads as a complete trace of work that was never part of it.
     */
    @Test
    fun clearsTheTraceIdWhenTheNextInvocationArrivesWithoutOne() = runBlocking {
        val recorder = Recorder()
        val traceId = "Root=1-5759e988-bd862e3fe1be46a994272793;Sampled=1"
        var polls = 0
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                polls++
                val headers = buildList {
                    add("Lambda-Runtime-Aws-Request-Id" to "req-$polls")
                    add("Lambda-Runtime-Deadline-Ms" to (nowEpochMillis() + 30_000).toString())
                    // Only the first invocation is traced.
                    if (polls == 1) add("Lambda-Runtime-Trace-Id" to traceId)
                }
                respond(
                    content = "{}",
                    headers = headersOf(
                        *headers.map { (name, value) -> name to listOf(value) }.toTypedArray()
                    )
                )
            } else {
                recorder.posts += request
                respond(content = "", status = HttpStatusCode.Accepted)
            }
        }
        val runtime = runtimeFor(recorder, HttpClient(engine))

        runtime.processNextInvocation(BASE_URL)
        assertEquals(traceId, nativeGetEnv("_X_AMZN_TRACE_ID"))

        runtime.processNextInvocation(BASE_URL)
        assertNull(
            nativeGetEnv("_X_AMZN_TRACE_ID"),
            "an untraced invocation must not inherit the previous invocation's trace id"
        )
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
     * The Runtime API answering 500 to the result POST means the container is in a non-recoverable
     * state, and the reference requires the runtime to exit. Discarding the status instead — which
     * is what dropping the return value of `client.post` did — leaves the loop polling for the next
     * invocation as though the result had been delivered, so the function reports success for every
     * invocation whose result Lambda never received.
     */
    @Test
    fun exitsWhenTheRuntimeApiRejectsTheResultWithAServerError() = runBlocking {
        val recorder = Recorder()
        val runtime = runtimeFor(
            recorder,
            clientFor(recorder, postStatus = HttpStatusCode.InternalServerError, postBody = "container error")
        )

        assertFailsWith<ExitSignal> { runtime.processNextInvocation(BASE_URL) }

        assertEquals(1, recorder.exitCode, "a 500 must exit with a non-zero code")
        assertContains(recorder.loggedText(), "500")
        assertContains(recorder.loggedText(), "container error")
    }

    /**
     * A 4xx is recoverable — the container is fine, this one result was refused — so the runtime
     * carries on. It must still say so: silence here is indistinguishable from success.
     */
    @Test
    fun reportsWithoutExitingWhenTheResultIsRejectedWithAClientError() = runBlocking {
        val recorder = Recorder()
        val runtime = runtimeFor(
            recorder,
            clientFor(recorder, postStatus = HttpStatusCode.PayloadTooLarge, postBody = "response too large")
        )

        runtime.processNextInvocation(BASE_URL)

        assertNull(recorder.exitCode, "a 4xx must not take the container down")
        assertContains(recorder.loggedText(), "413")
        assertContains(recorder.loggedText(), "response too large")
        assertContains(recorder.loggedText(), "/runtime/invocation/req-1/response")
    }

    /**
     * A result POST that *throws* is a delivery failure, not a function failure. Reporting it to
     * `/invocation/{id}/error` — which is what a single `try` around both the handler call and the
     * POST did — labels an invocation whose handler completed as a function error, with an errorType
     * naming an HTTP transport exception, and for asynchronous and poll-based sources makes Lambda
     * redeliver the event so the handler re-runs work it already did.
     */
    @Test
    fun doesNotReportAResultDeliveryFailureAsAFunctionError() = runBlocking {
        val recorder = Recorder()
        var handlerRuns = 0
        val runtime = runtimeFor(
            recorder,
            clientFor(recorder, postFailures = Int.MAX_VALUE),
            handler = { handlerRuns++; "{}" }
        )

        assertFailsWith<ExitSignal> { runtime.processNextInvocation(BASE_URL) }

        assertEquals(1, handlerRuns, "the handler ran once and succeeded")
        assertTrue(
            recorder.posts.none { it.url.toString().endsWith("/error") },
            "a successful handler must never be reported to /error, but was: ${recorder.posts.map { it.url }}"
        )
        assertEquals(
            1,
            recorder.exitCode,
            "a runtime that cannot deliver results must exit so Lambda replaces the container"
        )
        assertContains(recorder.loggedText(), "/runtime/invocation/req-1/response")
    }

    /**
     * The retry is bounded at one attempt and exists because the failure above is a transport
     * failure, which is often over by the time the next request goes out. Without it a single
     * dropped connection discards a result the handler already computed.
     */
    @Test
    fun retriesTheResultPostOnceBeforeGivingUp() = runBlocking {
        val recorder = Recorder()
        val runtime = runtimeFor(recorder, clientFor(recorder, postFailures = 1))

        runtime.processNextInvocation(BASE_URL)

        assertEquals(2, recorder.posts.size, "the failed POST should have been retried exactly once")
        assertTrue(
            recorder.posts.all { it.url.toString() == "$BASE_URL/runtime/invocation/req-1/response" },
            "both attempts deliver the result: ${recorder.posts.map { it.url }}"
        )
        assertNull(recorder.exitCode, "a retry that succeeded must not take the container down")
    }

    /**
     * The counterpart to [doesNotReportAResultDeliveryFailureAsAFunctionError], and the regression
     * that narrowing the `try` could plausibly have caused: a throwable from the handler itself is
     * still reported to `/error`, and is still not fatal to the container.
     */
    @Test
    fun stillReportsHandlerFailuresToTheErrorEndpoint() = runBlocking {
        val recorder = Recorder()
        val runtime = runtimeFor(
            recorder,
            clientFor(recorder),
            handler = { throw IllegalStateException("handler blew up") }
        )

        runtime.processNextInvocation(BASE_URL)

        val post = recorder.posts.single()
        assertEquals("$BASE_URL/runtime/invocation/req-1/error", post.url.toString())
        assertEquals("IllegalStateException", post.headers["Lambda-Runtime-Function-Error-Type"])
        assertEquals(
            "handler blew up",
            Json.parseToJsonElement(recorder.bodyOf(post)).jsonObject["errorMessage"]?.jsonPrimitive?.content
        )
        assertNull(recorder.exitCode, "a handler failure is the function's problem, not the container's")
    }

    /**
     * The same contract on the error endpoint. Reporting a handler failure is best-effort against a
     * *transport* failure — see [survivesTheRuntimeApiRejectingTheErrorReport] — but a 500 is the
     * Runtime API answering that the container itself is done.
     */
    @Test
    fun exitsWhenTheErrorReportIsRejectedWithAServerError() = runBlocking {
        val recorder = Recorder()
        val runtime = runtimeFor(
            recorder,
            clientFor(recorder, postStatus = HttpStatusCode.InternalServerError),
            handler = { throw IllegalStateException("handler blew up") }
        )

        assertFailsWith<ExitSignal> { runtime.processNextInvocation(BASE_URL) }

        assertEquals(1, recorder.exitCode)
        assertContains(recorder.posts.single().url.toString(), "/error")
        assertContains(recorder.loggedText(), "500")
    }

    @Test
    fun reportsWithoutExitingWhenTheErrorReportIsRejectedWithAClientError() = runBlocking {
        val recorder = Recorder()
        val runtime = runtimeFor(
            recorder,
            clientFor(recorder, postStatus = HttpStatusCode.BadRequest, postBody = "malformed error payload"),
            handler = { throw IllegalStateException("handler blew up") }
        )

        runtime.processNextInvocation(BASE_URL)

        assertNull(recorder.exitCode)
        assertContains(recorder.loggedText(), "400")
        assertContains(recorder.loggedText(), "malformed error payload")
    }

    /**
     * The poll itself is subject to the same contract, and failing it early matters more here: a
     * handler invoked on an event that arrived with a 500 would do real work — writes, publishes —
     * whose result can never be delivered.
     */
    @Test
    fun exitsWhenPollingForTheNextInvocationFailsWithAServerError() = runBlocking {
        val recorder = Recorder()
        var handlerRuns = 0
        val runtime = runtimeFor(
            recorder,
            clientFor(recorder, nextStatus = HttpStatusCode.InternalServerError),
            handler = { handlerRuns++; "{}" }
        )

        assertFailsWith<ExitSignal> { runtime.processNextInvocation(BASE_URL) }

        assertEquals(1, recorder.exitCode)
        assertEquals(0, handlerRuns, "the handler must not run on an event the poll did not accept")
        assertTrue(recorder.posts.isEmpty(), "nothing should be posted for an invocation never accepted")
        assertContains(recorder.loggedText(), "/runtime/invocation/next")
    }

    /**
     * Only 5xx is fatal. A non-2xx poll that still carries a request id is surfaced and processed,
     * rather than taking the container down.
     */
    @Test
    fun reportsWithoutExitingWhenPollingReturnsAClientError() = runBlocking {
        val recorder = Recorder()
        val runtime = runtimeFor(recorder, clientFor(recorder, nextStatus = HttpStatusCode.Forbidden))

        runtime.processNextInvocation(BASE_URL)

        assertNull(recorder.exitCode)
        assertContains(recorder.loggedText(), "403")
    }

    /** A 2xx is the normal path and must stay silent — no spurious stderr, no exit. */
    @Test
    fun saysNothingWhenTheRuntimeApiAcceptsTheResult() = runBlocking {
        val recorder = Recorder()
        val runtime = runtimeFor(recorder, clientFor(recorder))

        runtime.processNextInvocation(BASE_URL)

        assertNull(recorder.exitCode)
        assertTrue(recorder.logged.isEmpty(), "an accepted result logged: ${recorder.loggedText()}")
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
