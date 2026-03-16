package com.steamstreet.aws.lambda.native

import kotlinx.coroutines.runBlocking

/**
 * Entry point helper for native Lambda functions. Creates a [LambdaRuntime]
 * and starts the event processing loop.
 *
 * Usage in a Lambda module's main function:
 * ```kotlin
 * fun main() = nativeLambda { json ->
 *     // process the event JSON and return a response JSON string
 *     "{}"
 * }
 * ```
 */
public fun nativeLambda(handler: suspend (String) -> String) {
    runBlocking {
        LambdaRuntime(handler).run()
    }
}
