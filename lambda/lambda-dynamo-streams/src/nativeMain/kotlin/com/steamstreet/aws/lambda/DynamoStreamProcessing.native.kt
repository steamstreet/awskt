package com.steamstreet.aws.lambda

import com.steamstreet.awskt.logging.`is`
import com.steamstreet.awskt.logging.log

/** Native counterpart of the JVM's slf4j-backed level functions. */
internal actual suspend fun logStreamFailure(
    level: StreamFailureLogLevel,
    message: String,
    t: Throwable?,
    metadata: Map<String, String?>
) {
    val builder: com.steamstreet.awskt.logging.Log.LoggingContextBuilder.() -> Unit = {
        metadata.forEach { (key, value) -> key `is` value }
    }
    when (level) {
        StreamFailureLogLevel.WARNING -> log.warning(message, t, builder)
        StreamFailureLogLevel.INFO -> log.info(message, builder)
        StreamFailureLogLevel.SEVERE -> log.error(message, t, builder)
    }
}
