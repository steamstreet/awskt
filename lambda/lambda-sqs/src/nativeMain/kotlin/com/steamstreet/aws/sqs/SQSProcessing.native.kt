package com.steamstreet.aws.sqs

import com.steamstreet.awskt.logging.`is`
import com.steamstreet.awskt.logging.log

/**
 * Native counterpart of the JVM's slf4j MDC.
 *
 * The common `Log` API carries its context on the coroutine context rather than a thread local,
 * which is the better fit here anyway — it survives the `async` dispatch in `processBatch` without
 * anything having to copy it across.
 */
internal actual suspend fun <T> withMessageContext(messageId: String, block: suspend () -> T): T =
    log.ctx({ "sqs-message-id" `is` messageId }) {
        block()
    }

internal actual suspend fun logMessageFailure(message: String, t: Throwable) {
    log.warning(message, t)
}
