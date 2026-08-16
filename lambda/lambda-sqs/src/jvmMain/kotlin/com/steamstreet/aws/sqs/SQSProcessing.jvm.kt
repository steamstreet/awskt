package com.steamstreet.aws.sqs

import com.steamstreet.awskt.logging.logWarning
import com.steamstreet.awskt.logging.mdcContext

/** Unchanged from before this logic moved into common — slf4j's MDC. */
internal actual suspend fun <T> withMessageContext(messageId: String, block: suspend () -> T): T =
    mdcContext("sqs-message-id" to messageId) {
        block()
    }

/** Unchanged from before this logic moved into common — slf4j through the logging module. */
internal actual suspend fun logMessageFailure(message: String, t: Throwable) {
    logWarning(message, t)
}
