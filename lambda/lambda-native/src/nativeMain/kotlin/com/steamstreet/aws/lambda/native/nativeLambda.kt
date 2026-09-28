package com.steamstreet.aws.lambda.native

import com.steamstreet.aws.lambda.lambdaJson
import com.steamstreet.awskt.logging.log
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.coroutines.cancellation.CancellationException

/**
 * Entry point for a Kotlin/Native Lambda that handles raw JSON.
 *
 * ```kotlin
 * fun main() = nativeLambda { json ->
 *     "{}"
 * }
 * ```
 *
 * Work done in [initialize] is reported to `POST /runtime/init/error` if it throws, so a failure to
 * read configuration or open a connection is attributed to initialization in the Lambda console
 * instead of surfacing as a process that exited without explanation. Setup done *before* calling
 * this function is outside that guard — put it in [initialize] if you want it covered.
 */
public fun nativeLambda(
    initialize: suspend () -> Unit = {},
    handler: suspend (String) -> String
): Unit = runBlocking {
    val runtime = LambdaRuntime(handler)
    try {
        initialize()
    } catch (t: Throwable) {
        runtime.reportInitializationError(t)
        throw t
    }
    runtime.run()
}

/**
 * Entry point for a Lambda that deserializes its event and returns nothing.
 *
 * The JVM equivalent is `lambdaInput`; the handler body is the same on both platforms.
 *
 * The event is logged as `Request received`, with the payload under `input`, as on the JVM. See
 * [nativeLambdaInput] with a serializer for what [logIncoming] suppresses.
 */
public inline fun <reified T> nativeLambdaInput(
    noinline initialize: suspend () -> Unit = {},
    logIncoming: Boolean = com.steamstreet.aws.lambda.logIncoming,
    noinline logRedactor: ((JsonElement) -> JsonElement)? = null,
    crossinline handler: suspend (T) -> Unit
): Unit = nativeLambdaInput(serializer<T>(), initialize, logIncoming, logRedactor) { handler(it) }

// Kept, like the overloads below, so that a klib compiled against 3.1.5 or earlier still links: on
// native, a call into another library's inline function is inlined only when the final binary is
// linked.
@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public inline fun <reified T> nativeLambdaInput(
    noinline initialize: suspend () -> Unit = {},
    crossinline handler: suspend (T) -> Unit
): Unit = nativeLambdaInput(serializer<T>(), initialize) { handler(it) }

/**
 * Entry point for a Lambda that deserializes its event and returns nothing, with an explicit
 * serializer so no serializer lookup happens at run time.
 *
 * Each event is logged as `Request received`, with the payload under `input` and the invocation's
 * request id, as the JVM's `lambdaInput` and `InputLambda` log it. [logIncoming] defaults to the
 * shared `logIncoming` switch, which the `LogIncomingData` environment variable sets. Turning it off
 * suppresses the line only while the handler succeeds: an event the handler fails on is still
 * logged, because the JVM handlers log it too, and a failure is not diagnosable without it.
 *
 * [logRedactor] is applied to the logged copy of the event, never to what the handler receives.
 * Pass `{ redactCredentials(it) }` to keep tokens out of the logs.
 */
public fun <T> nativeLambdaInput(
    deserializer: KSerializer<T>,
    initialize: suspend () -> Unit = {},
    logIncoming: Boolean = com.steamstreet.aws.lambda.logIncoming,
    logRedactor: ((JsonElement) -> JsonElement)? = null,
    handler: suspend (T) -> Unit
): Unit = nativeLambda(initialize, inputHandler(deserializer, logIncoming, logRedactor, handler))

// Kept so that code compiled against 3.1.5 or earlier still links: the inline overloads of that
// release compile down to a call to this signature.
@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public fun <T> nativeLambdaInput(
    deserializer: KSerializer<T>,
    initialize: suspend () -> Unit = {},
    handler: suspend (T) -> Unit
): Unit = nativeLambdaInput(deserializer, initialize, com.steamstreet.aws.lambda.logIncoming, handler = handler)

/**
 * Entry point for a Lambda that deserializes its event and serializes its result.
 *
 * The JVM equivalent is `lambdaIO`.
 *
 * The event and the response are logged as on the JVM. See [nativeLambdaIO] with serializers for
 * what [logIncoming] and [logOutgoing] suppress.
 */
public inline fun <reified T, reified R> nativeLambdaIO(
    noinline initialize: suspend () -> Unit = {},
    logIncoming: Boolean = com.steamstreet.aws.lambda.logIncoming,
    logOutgoing: Boolean = true,
    noinline logRedactor: ((JsonElement) -> JsonElement)? = null,
    crossinline handler: suspend (T) -> R
): Unit = nativeLambdaIO(serializer<T>(), serializer<R>(), initialize, logIncoming, logOutgoing, logRedactor) { handler(it) }

@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public inline fun <reified T, reified R> nativeLambdaIO(
    noinline initialize: suspend () -> Unit = {},
    crossinline handler: suspend (T) -> R
): Unit = nativeLambdaIO(serializer<T>(), serializer<R>(), initialize) { handler(it) }

/**
 * Entry point for a Lambda that deserializes its event and serializes its result, with explicit
 * serializers so no serializer lookup happens at run time.
 *
 * Each event is logged as `Request received`, with the payload under `input`, and each response as
 * `Lambda response sent`, with the payload under `event`. Both lines carry the invocation's request
 * id. These are the lines, fields and switches of the JVM's `IOLambda`:
 *
 * - [logIncoming] defaults to the shared `logIncoming` switch, which the `LogIncomingData`
 *   environment variable sets. Turning it off suppresses the request line only while the handler
 *   succeeds: an event the handler fails on is still logged, as on the JVM.
 * - [logOutgoing] suppresses the response line.
 * - [logRedactor] is applied to the logged copies of the request and response, never to what the
 *   handler receives or returns. Pass `{ redactCredentials(it) }` to keep tokens out of the logs.
 */
public fun <T, R> nativeLambdaIO(
    deserializer: KSerializer<T>,
    serializer: KSerializer<R>,
    initialize: suspend () -> Unit = {},
    logIncoming: Boolean = com.steamstreet.aws.lambda.logIncoming,
    logOutgoing: Boolean = true,
    logRedactor: ((JsonElement) -> JsonElement)? = null,
    handler: suspend (T) -> R
): Unit = nativeLambda(initialize, ioHandler(deserializer, serializer, logIncoming, logOutgoing, logRedactor, handler))

// Kept so that code compiled against 3.1.5 or earlier still links: the inline overloads of that
// release compile down to a call to this signature.
@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public fun <T, R> nativeLambdaIO(
    deserializer: KSerializer<T>,
    serializer: KSerializer<R>,
    initialize: suspend () -> Unit = {},
    handler: suspend (T) -> R
): Unit = nativeLambdaIO(deserializer, serializer, initialize, com.steamstreet.aws.lambda.logIncoming, true, handler = handler)

internal fun <T> inputHandler(
    deserializer: KSerializer<T>,
    logIncoming: Boolean,
    logRedactor: ((JsonElement) -> JsonElement)?,
    handler: suspend (T) -> Unit
): suspend (String) -> String = { body ->
    readIncoming(body, logIncoming, logRedactor) {
        handler(lambdaJson.decodeFromString(deserializer, body))
    }
    // The Runtime API requires a response body even when the handler produces no value.
    "null"
}

internal fun <T, R> ioHandler(
    deserializer: KSerializer<T>,
    serializer: KSerializer<R>,
    logIncoming: Boolean,
    logOutgoing: Boolean,
    logRedactor: ((JsonElement) -> JsonElement)?,
    handler: suspend (T) -> R
): suspend (String) -> String = { body ->
    readIncoming(body, logIncoming, logRedactor) {
        val result = handler(lambdaJson.decodeFromString(deserializer, body))
        val element = lambdaJson.encodeToJsonElement(serializer, result)
        if (logOutgoing) {
            logPayload("Lambda response sent", "event", element, logRedactor)
        }
        element.toString()
    }
}

/**
 * The native counterpart of the JVM's `InputStream.readIncoming`: logs [body] before running
 * [block] if [log] is set, and logs it on failure if it was not logged already.
 *
 * These run inside the runtime's per-invocation logging context, so the lines carry the request id
 * without adding it here.
 */
private suspend fun <T> readIncoming(
    body: String,
    log: Boolean,
    redactor: ((JsonElement) -> JsonElement)?,
    block: suspend () -> T
): T {
    if (log) {
        logPayload("Request received", "input", body.asLoggedJson(), redactor)
    }
    return try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (t: Throwable) {
        // Always logged on failure, as on the JVM.
        if (!log) {
            logPayload("Request received", "input", body.asLoggedJson(), redactor)
        }
        throw t
    }
}

/**
 * The payload as a JSON value, so it is logged as a nested object rather than as an escaped
 * string, as the JVM's raw Logstash marker logs it. A body that is not valid JSON is logged as a
 * string, since that is exactly the event a failure needs to show.
 */
private fun String.asLoggedJson(): JsonElement = try {
    lambdaJson.parseToJsonElement(this)
} catch (_: Exception) {
    JsonPrimitive(this)
}

/**
 * Logs one payload line, through [redactor] if there is one. A publisher or redactor that throws
 * must not fail an invocation the handler would have completed, so its failure goes to stderr
 * instead.
 */
private suspend fun logPayload(
    message: String,
    field: String,
    payload: JsonElement,
    redactor: ((JsonElement) -> JsonElement)?
) {
    try {
        val logged = redactor?.invoke(payload) ?: payload
        log.info(message) { put(field, logged) }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (logFailure: Throwable) {
        printError("Failed to log '$message': $logFailure")
    }
}
