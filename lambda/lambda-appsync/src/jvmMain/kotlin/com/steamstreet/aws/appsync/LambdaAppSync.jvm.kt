package com.steamstreet.aws.appsync

import com.amazonaws.services.lambda.runtime.Context
import com.steamstreet.aws.lambda.lambdaInput
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
 * Handles AppSync lambda input/output processing for different field combinations
 */
public fun appSync(
    input: InputStream,
    output: OutputStream,
    context: Context,
    config: suspend JvmAppSyncTypeHandler.() -> Unit
) {
    lambdaInput(input, AppSyncContext.serializer(), context) { appSyncContext ->
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
