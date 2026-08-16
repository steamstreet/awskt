package com.steamstreet.aws.lambda

import com.steamstreet.awskt.logging.logError
import com.steamstreet.awskt.logging.logInfo
import com.steamstreet.awskt.logging.logWarning

/**
 * Unchanged from before this logic moved into common — the logging module's level functions.
 *
 * One small difference, and it is a widening rather than a change of shape: `DynamoKtStreamHandler`
 * passed the throwable on the WARNING and SEVERE paths while `DynamoKinesisStreamHandler` did not,
 * for no reason visible in either class. Both now pass it, so a failure logged from the Kinesis
 * handler carries its stack trace on the same line it always appeared on.
 */
internal actual suspend fun logStreamFailure(
    level: StreamFailureLogLevel,
    message: String,
    t: Throwable?,
    metadata: Map<String, String?>
) {
    val pairs = metadata.toList().toTypedArray()
    when (level) {
        StreamFailureLogLevel.WARNING -> logWarning(message, t, *pairs)
        StreamFailureLogLevel.INFO -> logInfo(message, *pairs)
        StreamFailureLogLevel.SEVERE -> logError(message, t, *pairs)
    }
}
