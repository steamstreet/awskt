package com.steamstreet.awskt.logging

import com.steamstreet.exceptions.MDCExceptionMixin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import net.logstash.logback.marker.Markers
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

public object Log {
    public enum class Level {
        INFO, WARN, ERROR, EVENT
    }

    public val loggingEncoder: Json = Json

    /**
     * A CoroutineContext element that carries logging context metadata.
     * This propagates automatically to child coroutines and can be retrieved
     * at any logging call site.
     *
     * Unlike MDCContext which is tied to thread-local storage, LoggingContext
     * is part of the coroutine context and works reliably across all dispatchers.
     */
    public class LoggingContext(
        public val contextMap: Map<String, Any?> = emptyMap()
    ) : AbstractCoroutineContextElement(LoggingContext) {
        public companion object Key : CoroutineContext.Key<LoggingContext>

        /**
         * Merge with additional context, with new values taking precedence.
         */
        public operator fun plus(other: Map<String, Any?>): LoggingContext =
            LoggingContext(contextMap + other)

        /**
         * Merge with another LoggingContext, with other's values taking precedence.
         */
        public operator fun plus(other: LoggingContext): LoggingContext =
            LoggingContext(contextMap + other.contextMap)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LoggingContext) return false
            return contextMap == other.contextMap
        }

        override fun hashCode(): Int {
            return contextMap.hashCode()
        }

        override fun toString(): String {
            return "LoggingContext($contextMap)"
        }
    }

    /**
     * Create a suspendable context for logging.
     * Uses LoggingContext which automatically propagates to child coroutines.
     *
     * This is now an alias for loggingContext() for backward compatibility.
     * Consider using loggingContext() directly in new code.
     */
    public suspend fun <T> mdcContext(vararg pairs: Pair<String, Any?>, block: suspend CoroutineScope.() -> T): T {
        return logCtx(*pairs, block = block)
    }

    /**
     * Construct an MDC context with data.
     */
    public fun MDCContext(vararg pairs: Pair<String, String>): MDCContext =
        MDCContext(MDC.getCopyOfContextMap().orEmpty() + pairs)

    /**
     * Create a logging context that automatically propagates to child coroutines.
     * This is the recommended approach for structured logging across coroutines.
     *
     * Unlike MDCContext which uses thread-local storage, LoggingContext is part of
     * the CoroutineContext and automatically propagates to all child coroutines
     * created with launch, async, etc. No special helpers needed!
     *
     * Example:
     * ```
     * loggingContext("requestId" to "123", "userId" to "456") {
     *     launch {
     *         // Context automatically available in child coroutine!
     *         logInfo("Processing request")  // Will include requestId and userId
     *     }
     * }
     * ```
     */
    public suspend fun <T> logCtx(
        vararg pairs: Pair<String, Any?>,
        block: suspend CoroutineScope.() -> T
    ): T {
        val currentContext = currentCoroutineContext()[LoggingContext]
        val newContext = if (currentContext != null) {
            currentContext + pairs.toMap()
        } else {
            LoggingContext(pairs.toMap())
        }
        return withContext(newContext, block)
    }

    /**
     * Get the current logging context from the coroutine context, if available.
     */
    internal suspend fun getLoggingContext(): Map<String, Any?> {
        return currentCoroutineContext()[LoggingContext]?.contextMap ?: emptyMap()
    }

    /**
     * Merge logging context with additional metadata and exception MDC attributes.
     */
    private suspend fun mergeLoggingContext(
        throwable: Throwable?,
        vararg metadata: Pair<String, Any?>
    ): Map<String, Any?> {
        val loggingCtx = getLoggingContext()
        val exceptionMdc = if (throwable is MDCExceptionMixin) {
            throwable.mdcAttributes
        } else {
            emptyMap()
        }
        return loggingCtx + exceptionMdc + metadata.toMap()
    }

    /**
     * Wrap a block with MDC parameters
     */
    public fun <T> mdc(metadata: Map<String, Any?>, block: () -> T): T {
        val previous = hashMapOf<String, Any?>()
        metadata.forEach {
            previous[it.key] = MDC.get(it.key)
            MDC.put(it.key, it.value?.toString())
        }
        val result = block()
        previous.forEach { (key, value) ->
            if (value == null) {
                MDC.remove(key)
            } else {
                MDC.put(key, value.toString())
            }
        }
        return result
    }


    public fun <T> ctx(vararg metadata: Pair<String, Any?>, block: () -> T): T {
        return mdc(metadata.toMap(), block)
    }

    private val defaultLogger = LoggerFactory.getLogger("EventLogger")

    /**
     * Log warning with the given context
     */
    public fun logWarning(message: String, context: JsonElement, throwable: Throwable? = null) {
        if (context is JsonObject) {
            val markers = context.map { (key, value) ->
                Markers.appendRaw(key, value.toString())
            }
            defaultLogger.warn(Markers.aggregate(markers), message, throwable)
        } else {
            defaultLogger.warn(Markers.appendRaw("context", context.toString()), message, throwable)
        }
    }

    public inline fun <reified T> logWarningObject(message: String, data: T, throwable: Throwable? = null) {
        val element = loggingEncoder.encodeToJsonElement(data)
        logWarning(message, element, throwable)
    }


    /**
     * Log an error with the given context
     */
    public fun error(message: String, context: JsonElement, throwable: Throwable? = null) {
        if (context is JsonObject) {
            val markers = context.map { (key, value) ->
                Markers.appendRaw(key, value.toString())
            }
            defaultLogger.error(Markers.aggregate(markers), message, throwable)
        } else {
            defaultLogger.error(Markers.appendRaw("context", context.toString()), message, throwable)
        }
    }

    public inline fun <reified T> errorObject(message: String, data: T, throwable: Throwable? = null) {
        val element = loggingEncoder.encodeToJsonElement(data)
        error(message, element, throwable)
    }


    /**
     * Log serialized JSON to the log.
     */
    public inline fun <reified T> logValue(message: String, field: String, data: T) {
        logJson(message, field, loggingEncoder.serializersModule.serializer<T>(), data)
    }

    /**
     * Log serialized JSON to the log.
     */
    public inline fun <reified T> logValue(message: String, data: T) {
        val serializer = loggingEncoder.serializersModule.serializer<T>()
        logJson(message, loggingEncoder.encodeToJsonElement(serializer, data))
    }

    public fun logJson(message: String, data: JsonElement) {
        defaultLogger.logValue(message, data)
    }

    /**
     * Log a value as structured json, setting the data values at the root of the
     * log message.
     */
    private fun Logger.logValue(level: Level, message: String, element: JsonElement) {
        val markers = if (element is JsonObject) {
            val markers = element.map { (key, value) ->
                Markers.appendRaw(key, value.toString())
            }
            Markers.aggregate(markers)
        } else {
            Markers.appendRaw("data", element.toString())
        }
        when (level) {
            Level.INFO -> info(markers, message)
            Level.WARN -> warn(markers, message)
            Level.ERROR -> error(markers, message)
            Level.EVENT -> info(markers, message)
        }
    }

    public interface EventLogContext {
        public fun context(key: String, value: JsonElement)
        public fun context(key: String, value: String) {
            context(key, JsonPrimitive(value))
        }

//    public operator fun String.minus(value: JsonElement)
    }

    public inline fun <reified T> EventLogContext.context(key: String, value: T) {
        val element = loggingEncoder.encodeToJsonElement(value)
        context(key, element)
    }

    context(ctx: EventLogContext)
    public inline operator fun <reified T> String.minus(value: T) {
        ctx.context(this, value)
    }

    /**
     * Log an event. Context can be configured via the trailing lambda.
     */
    public fun event(message: String, config: EventLogContext.() -> Unit = {}) {
        val fields = mutableMapOf<String, JsonElement>()
        object : EventLogContext {
            override fun context(key: String, value: JsonElement) {
                fields[key] = value
            }
        }.config()

        val markers = fields.map { (key, value) ->
            Markers.appendRaw(key, value.toString())
        }
        defaultLogger.info(Markers.aggregate(markers), message)
    }

    /**
     * Log warning with additional metadata.
     * Automatically includes LoggingContext if called from within a coroutine.
     */
    public suspend fun warning(message: String, vararg metadata: Pair<String, Any?>) {
        val merged = mergeLoggingContext(null, *metadata)
        mdc(merged) {
            defaultLogger.warn(message)
        }
    }

    /**
     * Log warning with throwable and additional metadata.
     * Automatically includes LoggingContext if called from within a coroutine.
     */
    public suspend fun warning(message: String, throwable: Throwable?, vararg metadata: Pair<String, Any?>) {
        val merged = mergeLoggingContext(throwable, *metadata)
        mdc(merged) {
            defaultLogger.warn(message, throwable)
        }
    }

    /**
     * Log error with additional metadata.
     * Automatically includes LoggingContext if called from within a coroutine.
     */
    public suspend fun error(message: String, vararg metadata: Pair<String, Any?>) {
        val merged = mergeLoggingContext(null, *metadata)
        mdc(merged) {
            defaultLogger.error(message)
        }
    }

    /**
     * Log error with throwable and additional metadata.
     * Automatically includes LoggingContext if called from within a coroutine.
     */
    public suspend fun error(message: String, throwable: Throwable?, vararg metadata: Pair<String, Any?>) {
        val merged = mergeLoggingContext(throwable, *metadata)
        mdc(merged) {
            defaultLogger.error(message, throwable)
        }
    }

    /**
     * Log info with additional metadata.
     * Automatically includes LoggingContext if called from within a coroutine.
     */
    public suspend fun info(message: String, vararg metadata: Pair<String, Any?>) {
        val merged = mergeLoggingContext(null, *metadata)
        mdc(merged) {
            defaultLogger.info(message)
        }
    }

    /**
     * Log info with additional metadata using a builder.
     * Automatically includes LoggingContext if called from within a coroutine.
     */
    public suspend fun info(message: String, builderAction: MutableList<Pair<String, Any>>.() -> Unit) {
        val metadata: List<Pair<String, Any>> = buildList(builderAction)
        val merged = mergeLoggingContext(null, *metadata.toTypedArray())
        mdc(merged) {
            defaultLogger.info(message)
        }
    }
}

public val log: Log = Log

