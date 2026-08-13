package com.steamstreet.aws.lambda.native

import com.steamstreet.aws.lambda.LambdaContext
import com.steamstreet.aws.lambda.lambdaContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * The invocation context on Kotlin/Native, populated from the Runtime API response headers.
 */
public class NativeLambdaContext(
    override val requestId: String,
    override val functionName: String,
    private val deadlineEpochMillis: Long
) : LambdaContext {
    /**
     * Recomputed on every read, deliberately.
     *
     * The Runtime API gives an absolute deadline, not a duration, so the only correct way to answer
     * "how long do I have left" is to subtract the clock at the moment of asking. Capturing it once
     * when the invocation starts — which is what a constructor parameter would do — reports the full
     * timeout forever, and handlers that use it to decide whether to start more work would always
     * decide yes.
     */
    override val remainingTimeInMillis: Int
        get() = (deadlineEpochMillis - nowEpochMillis())
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
}

/**
 * AWS Lambda custom runtime for Kotlin/Native.
 *
 * Implements the Runtime API loop:
 *  1. `GET  /runtime/invocation/next` — receive the next event
 *  2. run the handler
 *  3. `POST /runtime/invocation/{id}/response` — send the result
 *  4. on failure, `POST /runtime/invocation/{id}/error`
 *
 * Failures before the loop starts are reported to `POST /runtime/init/error`, which is what makes a
 * bad configuration show up in the Lambda console as an init failure rather than as a process that
 * exited silently.
 *
 * Every Runtime API response has its status checked. A 5xx exits the process, as the Runtime API
 * reference requires; any other non-2xx is written to stderr. Neither is optional — the status is
 * the only signal that a result was not accepted, and a runtime that ignores it reports success for
 * invocations whose results Lambda never received.
 */
public class LambdaRuntime internal constructor(
    private val handler: suspend (String) -> String,
    private val client: HttpClient,
    // Seams, not configuration: both are only ever substituted by tests. A test that let the real
    // implementations run would write to the test process's stderr and then terminate the test
    // process outright, so the exit path could not be asserted at all.
    private val logError: (String) -> Unit = ::printError,
    private val exitProcess: (Int) -> Nothing = { kotlin.system.exitProcess(it) }
) {
    /**
     * @param handler receives the raw event JSON and returns the raw response JSON.
     */
    // No HttpTimeout plugin, and none may be added. `GET /runtime/invocation/next` is a long poll:
    // it does not return until Lambda has an event, which for an idle function can be minutes or
    // hours. Installing Ktor's HttpTimeout plugin on this client makes that GET throw once the
    // function goes quiet; the runtime then loops, re-polls, throws again, and the function appears
    // to work perfectly under load and fail only when traffic stops — one of the hardest Lambda
    // faults to diagnose, because it never reproduces in a test.
    public constructor(handler: suspend (String) -> String) : this(handler, lambdaHttpClient())

    private val functionName = nativeGetEnv("AWS_LAMBDA_FUNCTION_NAME") ?: "unknown"

    /**
     * Runs the event loop. Does not return under normal operation.
     */
    public suspend fun run() {
        val runtimeApi = nativeGetEnv(RUNTIME_API_ENV)
        if (runtimeApi == null) {
            // The one failure that genuinely cannot be reported: the address to report it to is the
            // thing that is missing. Say so on stderr, which Lambda forwards to CloudWatch, rather
            // than dying with a bare `error()` whose message never leaves the process.
            logError(
                "$RUNTIME_API_ENV is not set, so this process is not running under the AWS Lambda " +
                    "runtime and has no endpoint to report the failure to."
            )
            throw IllegalStateException("$RUNTIME_API_ENV environment variable not set")
        }

        val baseUrl = "http://$runtimeApi/2018-06-01"

        while (true) {
            processNextInvocation(baseUrl)
        }
    }

    /**
     * Reports a failure that happened during initialization, before any invocation was received.
     *
     * Call this from an entry point that does setup work of its own — see `nativeLambda`.
     */
    public suspend fun reportInitializationError(t: Throwable) {
        val runtimeApi = nativeGetEnv(RUNTIME_API_ENV) ?: return
        reportInitializationError("http://$runtimeApi/2018-06-01", t)
    }

    internal suspend fun reportInitializationError(baseUrl: String, t: Throwable) {
        postError("$baseUrl/runtime/init/error", t)
    }

    internal suspend fun processNextInvocation(baseUrl: String) {
        val nextResponse = client.get("$baseUrl/runtime/invocation/next")

        // Read the body before inspecting the status: on a failure the body is the Runtime API's
        // error description, and on success it is the event. One read serves both, which also keeps
        // us clear of reading the response content twice.
        val eventBody = nextResponse.bodyAsText()
        checkRuntimeApiStatus(nextResponse.status, "GET /runtime/invocation/next", eventBody)

        // A non-500 failure falls through to here. There is no request id on such a response, so the
        // error below fires and takes the process down rather than looping on a poll that cannot
        // succeed — a 403 means the Runtime API is refusing this process, which no amount of
        // re-polling fixes.
        val requestId = nextResponse.headers[HEADER_REQUEST_ID]
            ?: error("No $HEADER_REQUEST_ID in the Runtime API response headers")
        val deadlineEpochMillis = nextResponse.headers[HEADER_DEADLINE]?.toLongOrNull()
            ?: (nowEpochMillis() + DEFAULT_DEADLINE_MILLIS)

        // X-Ray reads the trace id from the environment, not from a parameter, so the header has to
        // be re-exported on every invocation. Skipping this does not fail anything loudly — traces
        // simply never appear, and the function looks untraced rather than broken.
        nextResponse.headers[HEADER_TRACE_ID]?.let { traceId ->
            nativeSetEnv(TRACE_ID_ENV, traceId)
        }

        lambdaContext = NativeLambdaContext(
            requestId = requestId,
            functionName = functionName,
            deadlineEpochMillis = deadlineEpochMillis
        )

        val responseUrl = "$baseUrl/runtime/invocation/$requestId/response"
        val response = try {
            val result = handler(eventBody)

            client.post(responseUrl) {
                contentType(ContentType.Application.Json)
                setBody(result)
            }
        } catch (cancellation: CancellationException) {
            // A narrow carve-out from the Throwable catch below, and *only* that: cancellation means
            // the scope running this loop is being torn down, so there is no invocation left to
            // report against and the runtime must unwind rather than tell Lambda the function
            // failed. Reporting it would attribute a shutdown to the handler's code.
            //
            // This does not reopen the catch below to `Exception` — see the comment there.
            throw cancellation
        } catch (t: Throwable) {
            // Throwable, not Exception. A handler that throws an Error — StackOverflowError from a
            // runaway recursion, or any of the Kotlin/Native runtime's own errors — would otherwise
            // escape the poll loop and kill the process with the invocation still outstanding, so
            // Lambda would retry it and hit the same wall until the event expired.
            postError("$baseUrl/runtime/invocation/$requestId/error", t)
            return
        }

        // Deliberately outside the try: the status check can decide to exit, and a catch for
        // Throwable this broad would otherwise swallow that decision and report it to the Runtime
        // API as a handler failure.
        checkRuntimeApiStatus(response, "POST $responseUrl")
    }

    private suspend fun postError(url: String, t: Throwable) {
        val errorType = t::class.simpleName ?: "Throwable"
        val payload = buildJsonObject {
            put("errorMessage", t.message ?: "Unknown error")
            put("errorType", errorType)
            putJsonArray("stackTrace") {
                t.stackTraceToString().lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { add(it) }
            }
        }.toString()

        val response = try {
            client.post(url) {
                contentType(ContentType.Application.Json)
                // Required by the Runtime API. Without it the console shows the invocation as
                // failed but attributes no error type, which is the difference between a report
                // that can be alerted on and one that cannot.
                header(HEADER_ERROR_TYPE, errorType)
                setBody(payload)
            }
        } catch (reportFailure: Throwable) {
            // Reporting is best-effort: if the Runtime API itself is unreachable there is nothing
            // further to try, and throwing here would take down the loop that the catch above just
            // protected.
            logError("Failed to report error to $url: $reportFailure")
            return
        }

        // A rejected report is not best-effort in the same way. The Runtime API answering 500 says
        // the container is unusable, and continuing to poll from that state produces a function
        // that accepts invocations and quietly loses every one of them.
        checkRuntimeApiStatus(response, "POST $url")
    }

    /**
     * Applies the Runtime API's status contract to [response], reading its body for the diagnostic.
     */
    private suspend fun checkRuntimeApiStatus(response: HttpResponse, what: String) {
        if (response.status.isSuccess()) return

        val detail = try {
            response.bodyAsText()
        } catch (t: Throwable) {
            "<response body unavailable: $t>"
        }
        checkRuntimeApiStatus(response.status, what, detail)
    }

    /**
     * The Runtime API's status contract.
     *
     * A 5xx means the container is in a non-recoverable state, and the documented obligation is to
     * exit promptly. Continuing the poll loop instead is worse than crashing: Lambda keeps handing
     * invocations to a container that cannot deliver a single result, and every one of them fails
     * by timeout with nothing in the logs to say why.
     *
     * Anything else non-2xx — a 400 for a malformed payload, a 403 for a bad request id, a 413 for
     * an oversized response — is recoverable, but has to be said out loud. Discarding the status
     * altogether, which is what this code did before, turned each of those into a silent success.
     */
    private fun checkRuntimeApiStatus(status: HttpStatusCode, what: String, detail: String) {
        if (status.isSuccess()) return

        val trimmed = detail.trim()
        val summary = "The Lambda Runtime API returned ${status.value} ${status.description} " +
            "for $what" + if (trimmed.isEmpty()) "." else ": $trimmed"

        if (status.value >= 500) {
            logError("$summary The container is in a non-recoverable state; exiting.")
            exitProcess(RUNTIME_API_FAILURE_EXIT_CODE)
        }

        logError(summary)
    }

    private companion object {
        const val RUNTIME_API_ENV = "AWS_LAMBDA_RUNTIME_API"
        const val TRACE_ID_ENV = "_X_AMZN_TRACE_ID"

        const val HEADER_REQUEST_ID = "Lambda-Runtime-Aws-Request-Id"
        const val HEADER_DEADLINE = "Lambda-Runtime-Deadline-Ms"
        const val HEADER_TRACE_ID = "Lambda-Runtime-Trace-Id"
        const val HEADER_ERROR_TYPE = "Lambda-Runtime-Function-Error-Type"

        // Only used if Lambda omits the deadline header, which it does not do in practice. Three
        // seconds is Lambda's own default function timeout.
        const val DEFAULT_DEADLINE_MILLIS = 3_000L

        // Non-zero, so Lambda records the container as having failed rather than as having been
        // shut down normally.
        const val RUNTIME_API_FAILURE_EXIT_CODE = 1
    }
}

internal fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

@OptIn(ExperimentalForeignApi::class)
internal fun nativeGetEnv(name: String): String? = platform.posix.getenv(name)?.toKString()

@OptIn(ExperimentalForeignApi::class)
internal fun nativeSetEnv(name: String, value: String) {
    platform.posix.setenv(name, value, 1)
}

@OptIn(ExperimentalForeignApi::class)
internal fun printError(message: String) {
    // stderr, so it reaches CloudWatch even when the process is about to die.
    platform.posix.fprintf(platform.posix.stderr, "%s\n", message)
    platform.posix.fflush(platform.posix.stderr)
}
