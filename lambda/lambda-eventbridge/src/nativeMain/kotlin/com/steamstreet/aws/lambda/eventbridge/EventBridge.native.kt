package com.steamstreet.aws.lambda.eventbridge

import com.steamstreet.awskt.logging.`is`
import com.steamstreet.awskt.logging.log
import com.steamstreet.awskt.logging.put
import kotlinx.coroutines.runBlocking

/**
 * Native counterpart of the JVM's slf4j logging.
 *
 * The common `Log` API is suspend-only, and [logProcessingEvent] is called from non-suspend
 * positions in the DSL, so the call is bridged with `runBlocking`. That is acceptable here and
 * nowhere else in this module: it is one log line per event on a runtime that has no other work in
 * flight, not a call on a request path.
 */
internal actual fun logProcessingEvent(message: String, key: String, json: String) {
    runBlocking {
        log.info(message) {
            put(key, json)
        }
    }
}

internal actual suspend fun logEventError(message: String, t: Throwable?, metadata: Map<String, String?>) {
    log.error(message, t) {
        metadata.forEach { (key, value) -> key `is` value }
    }
}

internal actual suspend fun logEventInfo(message: String, metadata: Map<String, String?>) {
    log.info(message) {
        metadata.forEach { (key, value) -> key `is` value }
    }
}

internal actual suspend fun <T> withEventContext(detailType: String, block: suspend () -> T): T =
    log.ctx({ "event-detail-type" `is` detailType }) {
        block()
    }
