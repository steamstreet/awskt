package com.steamstreet.aws.appsync

import com.steamstreet.aws.lambda.native.nativeLambda

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
 * A wrapper over `nativeLambda` and [processAppSync], both public — an application that needs its
 * own dispatch should call those directly.
 */
public fun appSyncLambda(
    initialize: suspend () -> Unit = {},
    config: suspend AppSyncTypeHandler.() -> Unit
): Unit = nativeLambda(initialize) { body ->
    // A resolver that matched nothing wrote nothing. The Runtime API still requires a response body,
    // and `null` is what AppSync reads as "no value for this field" — the same outcome the JVM
    // produces by leaving the output stream untouched.
    processAppSync(body.appSyncContext(), config) ?: "null"
}
