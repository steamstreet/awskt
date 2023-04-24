package com.steamstreet.aws.appsync

import com.amazonaws.services.lambda.runtime.Context
import com.steamstreet.aws.lambda.lambdaInput
import com.steamstreet.awskt.logging.logJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream

private val appSyncLogger = LoggerFactory.getLogger("AppSyncLambda")
private val appSyncJson = Json {
    ignoreUnknownKeys = true
}

/**
 * Handles AppSync lambda input/output processing for different field combinations
 */
public fun appSync(
    input: InputStream,
    output: OutputStream,
    context: Context,
    config: suspend AppSyncTypeHandler.() -> Unit
) {
    lambdaInput(input,  AppSyncContext.serializer(), context) { appSyncContext ->
        (object : AppSyncTypeHandler {
            override val context: AppSyncContext = appSyncContext
            override val output: OutputStream = output

            override suspend fun write(str: String) {
                withContext(Dispatchers.IO) {
                    output.writer().let {
                        it.write(str)
                        it.flush()
                    }
                    appSyncLogger.logJson( "Lambda response sent", "response", str)
                }
            }
        }).config()
    }
}

/**
 * An interface for installing handlers for different fields of an AppSync GraphQL call.
 */
public interface AppSyncTypeHandler {
    public val context: AppSyncContext
    public val output: OutputStream

    public suspend fun write(str: String)
}

public class AppSyncFieldHandler(
    internal val type: String,
    public val typeHandler: AppSyncTypeHandler
) {

}

public suspend fun AppSyncTypeHandler.type(type: String, typeCallback: suspend AppSyncFieldHandler.()->Unit) {
    AppSyncFieldHandler(type, this).typeCallback()
}

public suspend fun <T, R> AppSyncFieldHandler.field(
    field: String,
    inputSerializer: KSerializer<T>,
    outputSerializer: KSerializer<R>,
    handler: suspend AppSyncContext.(T) -> R
) {
    return this.typeHandler.typeAndField(this.type, field, inputSerializer, outputSerializer, handler)
}

public suspend fun <R> AppSyncFieldHandler.field(
    field: String,
    outputSerializer: KSerializer<R>,
    handler: suspend AppSyncContext.() -> R?
) {
    return this.typeHandler.typeAndField<Unit, R>(this.type, field, null, outputSerializer) { handler() }
}

public suspend fun <T, R> AppSyncTypeHandler.typeAndField(
    type: String, field: String,
    inputSerializer: KSerializer<T>?,
    outputSerializer: KSerializer<R>,
    handler: suspend AppSyncContext.(T) -> R?
) {
    if (context.info.parentTypeName == type && context.info.fieldName == field) {
        val result = coroutineScope {
            if (inputSerializer == null) {
                @Suppress("UNCHECKED_CAST")
                context.handler(Unit as T)
            } else {
                val param = appSyncJson.decodeFromJsonElement(inputSerializer, context.arguments)
                coroutineScope {
                    context.handler(param)
                }
            }
        }
        if (result != null) {
            val resultString = appSyncJson.encodeToString(outputSerializer, result)
            write(resultString)
        }
    }
}