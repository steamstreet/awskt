package com.steamstreet.aws.appsync

import com.steamstreet.aws.lambda.lambdaJson
import com.steamstreet.aws.lambda.native.nativeLambdaIO
import com.steamstreet.aws.lambda.redactCredentials
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Convenience entry point for a Kotlin/Native AppSync resolver Lambda.
 *
 * Call it from your own `main`; it does not define one:
 *
 * ```kotlin
 * fun main() = appSyncLambda {
 *     type("Query") {
 *         field("order", String.serializer(), Order.serializer()) { id -> loadOrder(id) }
 *     }
 * }
 * ```
 *
 * The [config] block is the same one the JVM `appSync()` takes, so field declarations move across
 * untouched.
 *
 * Each request is logged as `Request received` and each response as `Lambda response sent`, as
 * `nativeLambdaIO` logs them, with `redactCredentials` applied first so that the `authorization`
 * header AppSync forwards in `request.headers` stays out of the logs. `LogIncomingData=false`
 * suppresses the request line unless the resolver fails.
 *
 * A wrapper over `nativeLambdaIO` and [processAppSync], both public — an application that needs its
 * own dispatch should call those directly.
 */
public fun appSyncLambda(
    initialize: suspend () -> Unit = {},
    config: suspend AppSyncTypeHandler.() -> Unit
): Unit = nativeLambdaIO(
    AppSyncContext.serializer(),
    JsonElement.serializer(),
    initialize,
    logRedactor = { redactCredentials(it) }
) { context ->
    // A resolver that matched nothing wrote nothing. The Runtime API still requires a response body,
    // and `null` is what AppSync reads as "no value for this field" — the same outcome the JVM
    // produces by leaving the output stream untouched.
    processAppSync(context, config)?.let { lambdaJson.parseToJsonElement(it) } ?: JsonNull
}
