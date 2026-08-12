package com.steamstreet.aws.lambda.native

import com.steamstreet.aws.lambda.lambdaJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

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
 */
public inline fun <reified T> nativeLambdaInput(
    noinline initialize: suspend () -> Unit = {},
    crossinline handler: suspend (T) -> Unit
): Unit = nativeLambdaInput(serializer<T>(), initialize) { handler(it) }

/**
 * Entry point for a Lambda that deserializes its event and returns nothing, with an explicit
 * serializer so no serializer lookup happens at run time.
 */
public fun <T> nativeLambdaInput(
    deserializer: KSerializer<T>,
    initialize: suspend () -> Unit = {},
    handler: suspend (T) -> Unit
): Unit = nativeLambda(initialize) { body ->
    handler(lambdaJson.decodeFromString(deserializer, body))
    // The Runtime API requires a response body even when the handler produces no value.
    "null"
}

/**
 * Entry point for a Lambda that deserializes its event and serializes its result.
 *
 * The JVM equivalent is `lambdaIO`.
 */
public inline fun <reified T, reified R> nativeLambdaIO(
    noinline initialize: suspend () -> Unit = {},
    crossinline handler: suspend (T) -> R
): Unit = nativeLambdaIO(serializer<T>(), serializer<R>(), initialize) { handler(it) }

/**
 * Entry point for a Lambda that deserializes its event and serializes its result, with explicit
 * serializers so no serializer lookup happens at run time.
 */
public fun <T, R> nativeLambdaIO(
    deserializer: KSerializer<T>,
    serializer: KSerializer<R>,
    initialize: suspend () -> Unit = {},
    handler: suspend (T) -> R
): Unit = nativeLambda(initialize) { body ->
    val result = handler(lambdaJson.decodeFromString(deserializer, body))
    lambdaJson.encodeToString(serializer, result)
}
