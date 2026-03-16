package com.steamstreet.aws.lambda.native

import com.steamstreet.aws.lambda.LambdaContext
import com.steamstreet.aws.lambda.lambdaContext
import io.ktor.client.*
import io.ktor.client.engine.curl.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

/**
 * Native Lambda context populated from Runtime API response headers.
 */
public class NativeLambdaContext(
    override val requestId: String,
    override val functionName: String,
    override val remainingTimeInMillis: Int
) : LambdaContext

/**
 * Minimal AWS Lambda custom runtime implementation for Kotlin/Native.
 *
 * Implements the Lambda Runtime API loop:
 * 1. GET /runtime/invocation/next — receive the next event
 * 2. Process the event with the handler
 * 3. POST /runtime/invocation/{requestId}/response — send the response
 * 4. On error, POST to /runtime/invocation/{requestId}/error
 */
public class LambdaRuntime(
    private val handler: suspend (String) -> String
) {
    private val runtimeApi = nativeGetEnv("AWS_LAMBDA_RUNTIME_API")
        ?: error("AWS_LAMBDA_RUNTIME_API environment variable not set")

    private val functionName = nativeGetEnv("AWS_LAMBDA_FUNCTION_NAME") ?: "unknown"

    private val baseUrl = "http://$runtimeApi/2018-06-01"

    private val client = HttpClient(Curl) {
        expectSuccess = false
    }

    /**
     * Run the main event loop. This never returns under normal operation.
     */
    public suspend fun run() {
        while (true) {
            processNextInvocation()
        }
    }

    private suspend fun processNextInvocation() {
        val nextResponse = client.get("$baseUrl/runtime/invocation/next")
        val requestId = nextResponse.headers["Lambda-Runtime-Aws-Request-Id"]
            ?: error("No request ID in response headers")
        val deadlineMs = nextResponse.headers["Lambda-Runtime-Deadline-Ms"]
            ?.toLongOrNull() ?: 0L
        val remainingMs = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0).toInt()

        val eventBody = nextResponse.bodyAsText()

        lambdaContext = NativeLambdaContext(
            requestId = requestId,
            functionName = functionName,
            remainingTimeInMillis = remainingMs
        )

        try {
            val result = handler(eventBody)

            client.post("$baseUrl/runtime/invocation/$requestId/response") {
                contentType(ContentType.Application.Json)
                setBody(result)
            }
        } catch (e: Exception) {
            val errorPayload = buildJsonObject {
                put("errorMessage", e.message ?: "Unknown error")
                put("errorType", e::class.simpleName ?: "Exception")
            }.toString()

            client.post("$baseUrl/runtime/invocation/$requestId/error") {
                contentType(ContentType.Application.Json)
                setBody(errorPayload)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nativeGetEnv(name: String): String? = getenv(name)?.toKString()

private object System {
    fun currentTimeMillis(): Long {
        return kotlin.time.Clock.System.now().toEpochMilliseconds()
    }
}
