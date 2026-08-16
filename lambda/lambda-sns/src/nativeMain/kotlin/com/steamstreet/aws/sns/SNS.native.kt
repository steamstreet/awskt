package com.steamstreet.aws.sns

import com.steamstreet.aws.lambda.native.nativeLambdaInput
import kotlinx.serialization.KSerializer

/**
 * Convenience entry point for a Kotlin/Native Lambda subscribed to an SNS topic.
 *
 * Call it from your own `main`; it does not define one:
 *
 * ```kotlin
 * fun main() = snsLambda(OrderPlaced.serializer()) { order ->
 *     handle(order)
 * }
 * ```
 *
 * The handler runs with the enclosing [SnsRecord] as a context parameter, so `record.Sns.MessageId`
 * and `record.Sns.Subject` are in scope.
 *
 * This is a two-line wrapper over `nativeLambdaInput` and [SnsPayload.processMessages], both public.
 * Nothing here is a gate: a function that needs its own dispatch — several event sources in one
 * binary, a custom initialisation order, its own error reporting — should call those two directly
 * and skip this.
 */
public fun <T> snsLambda(
    serializer: KSerializer<T>,
    initialize: suspend () -> Unit = {},
    handler: suspend context(SnsRecord) (T) -> Unit
): Unit = nativeLambdaInput(SnsPayload.serializer(), initialize) { payload ->
    payload.processMessages(serializer, handler)
}

/**
 * Convenience entry point that hands the handler undecoded message bodies.
 *
 * The counterpart of [SnsPayload.processRawMessages], for a topic that carries more than one message
 * shape. Call it from `main` exactly as [snsLambda] is called.
 */
public fun snsRawLambda(
    initialize: suspend () -> Unit = {},
    handler: suspend context(SnsRecord) (String) -> Unit
): Unit = nativeLambdaInput(SnsPayload.serializer(), initialize) { payload ->
    payload.processRawMessages(handler)
}
