package com.steamstreet.aws.appsync

import com.amazonaws.services.lambda.runtime.Context
import com.steamstreet.aws.lambda.lambda
import com.steamstreet.aws.lambda.lambdaJson
import com.steamstreet.aws.lambda.logIncoming
import com.steamstreet.aws.lambda.readIncoming
import com.steamstreet.aws.lambda.redactCredentials
import com.steamstreet.awskt.logging.logJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream

private val appSyncLogger = LoggerFactory.getLogger("AppSyncLambda")

/**
 * An [AppSyncTypeHandler] that writes its response to a Lambda output stream.
 *
 * The `output` property lives here rather than on [AppSyncTypeHandler] because an `OutputStream` has
 * no Kotlin/Native counterpart. [appSync] hands this subtype to its config block, so a block that
 * reads `output` is unaffected by the move.
 */
public interface JvmAppSyncTypeHandler : AppSyncTypeHandler {
    public val output: OutputStream
}

/**
 * Handles AppSync lambda input/output processing for different field combinations.
 *
 * The request is logged with [redactCredentials] applied, so the `authorization` header AppSync
 * forwards in `request.headers` stays out of the logs.
 */
public fun appSync(
    input: InputStream,
    output: OutputStream,
    context: Context,
    config: suspend JvmAppSyncTypeHandler.() -> Unit
) {
    lambda(context) {
        input.readIncoming(logIncoming, { redactCredentials(it) }) { text ->
            val appSyncContext = lambdaJson.decodeFromString(AppSyncContext.serializer(), text)
            (object : JvmAppSyncTypeHandler {
                override val context: AppSyncContext = appSyncContext
                override val output: OutputStream = output

                override suspend fun write(str: String) {
                    withContext(Dispatchers.IO) {
                        output.writer().let {
                            it.write(str)
                            it.flush()
                        }
                        appSyncLogger.logJson("Lambda response sent", "response", str)
                    }
                }
            }).config()
        }
    }
}
