package com.steamstreet.aws.appsync

import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * The AppSync field-dispatch DSL, in `commonMain` so a resolver body compiles for the JVM and for
 * Kotlin/Native.
 *
 * The shape of this module is unusual among the Lambda adapters and worth stating: there is no
 * `processX(event): Response` function to call, because the DSL *is* the dispatch. A resolver
 * declares the type/field pairs it serves, each declaration checks the incoming context, and at most
 * one of them matches and writes a response. Making that common therefore meant making
 * [AppSyncTypeHandler] common, which is where the one API change lands — see below.
 */
private val appSyncJson = Json {
    ignoreUnknownKeys = true
}

/**
 * An interface for installing handlers for different fields of an AppSync GraphQL call.
 *
 * ### API change: `output` is no longer on this interface
 *
 * This used to carry `public val output: OutputStream` alongside [write]. An `OutputStream` cannot
 * exist on Kotlin/Native, so the response sink is now just [write] and the stream lives on the JVM's
 * `JvmAppSyncTypeHandler`, which this interface's JVM users receive anyway — `appSync(input, output,
 * context) { }` hands the config block that subtype, so a block that reads `output` still compiles.
 *
 * What breaks is code that names the supertype explicitly and then reaches for the stream — a
 * `fun install(handler: AppSyncTypeHandler) { handler.output.write(..) }`. Such code should take
 * `JvmAppSyncTypeHandler`, or call [write].
 */
public interface AppSyncTypeHandler {
    public val context: AppSyncContext

    /**
     * Write the resolver's response. Called at most once per invocation, by whichever field
     * declaration matched.
     */
    public suspend fun write(str: String)
}

public class AppSyncFieldHandler(
    internal val type: String,
    public val typeHandler: AppSyncTypeHandler
)

public suspend fun AppSyncTypeHandler.type(type: String, typeCallback: suspend AppSyncFieldHandler.() -> Unit) {
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

/**
 * An [AppSyncTypeHandler] that buffers the response in memory instead of writing it to a stream.
 *
 * This is what makes the DSL usable off the JVM: the native runtime wants the response as the return
 * value of the invocation, not written to a descriptor. It is equally useful in a test, where it
 * removes the need for a `ByteArrayOutputStream` and a decode step to see what a resolver produced.
 */
public class BufferedAppSyncTypeHandler(
    override val context: AppSyncContext
) : AppSyncTypeHandler {
    /** The response written by the matching field declaration, or null if none matched. */
    public var response: String? = null
        private set

    override suspend fun write(str: String) {
        response = str
    }
}

/**
 * Run [config] against [context] and return whatever the matching field declaration wrote.
 *
 * The whole of an AppSync resolver, with no runtime behind it — call it from a test, from your own
 * `main`, or from either platform's entry point. Returns null when no declaration matched the
 * incoming type and field, which is the same "no response" outcome the JVM produces by never
 * touching the output stream.
 */
public suspend fun processAppSync(
    context: AppSyncContext,
    config: suspend AppSyncTypeHandler.() -> Unit
): String? = BufferedAppSyncTypeHandler(context).apply { config() }.response
