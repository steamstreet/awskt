package com.steamstreet.awskt.logging

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*
import kotlin.coroutines.CoroutineContext

/**
 * Log levels for events
 */
public enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

/**
 * Coroutine context element for storing event context data
 */
public class EventContext(public val data: Map<String, JsonElement> = emptyMap()) : CoroutineContext.Element {
    public companion object Key : CoroutineContext.Key<EventContext>

    public override val key: CoroutineContext.Key<*> = Key

    public fun plus(additional: Map<String, JsonElement>): EventContext {
        return EventContext(data + additional)
    }
}

/**
 * Coroutine context element for conditional logging
 */
public class ConditionalLoggingContext(
    public val bufferedEvents: MutableList<JsonObject> = mutableListOf(),
    public var hasWarningOrError: Boolean = false
) : CoroutineContext.Element {
    public companion object Key : CoroutineContext.Key<ConditionalLoggingContext>

    public override val key: CoroutineContext.Key<*> = Key
}

/**
 * Builder for constructing log events
 */
public class LogBuilder {
    private val fields = mutableMapOf<String, JsonElement>()

    public fun message(message: String) {
        field("message", message)
    }

    public fun field(key: String, value: String) {
        fields[key] = JsonPrimitive(value)
    }

    public fun field(key: String, value: Number) {
        fields[key] = JsonPrimitive(value)
    }

    public fun field(key: String, value: Boolean) {
        fields[key] = JsonPrimitive(value)
    }

    public fun field(key: String, value: JsonElement) {
        fields[key] = value
    }

    public fun field(key: String, value: JsonObject) {
        fields[key] = value
    }

    public fun field(key: String, value: Instant) {
        fields[key] = JsonPrimitive(value.toString())
    }

    public inline fun <reified T> field(key: String, value: T) {
        field(key, AppEvents.jsonInstance.encodeToJsonElement(value))
    }

    // Infix operators for cleaner syntax
    public infix fun String.to(value: String) {
        field(this, value)
    }

    public infix fun String.to(value: Number) {
        field(this, value)
    }

    public infix fun String.to(value: Boolean) {
        field(this, value)
    }

    public infix fun String.to(value: JsonElement) {
        field(this, value)
    }

    public infix fun String.to(value: JsonObject) {
        field(this, value)
    }

    public infix fun String.to(value: Instant) {
        field(this, value)
    }

    public inline infix fun <reified T> String.to(value: T) {
        field(this, value)
    }

    public inline fun <reified T> serialize(obj: T) {
        val jsonElement = AppEvents.jsonInstance.encodeToJsonElement(obj)
        if (jsonElement is JsonObject) {
            jsonElement.forEach { (key, value) ->
                field(key, value)
            }
        } else {
            throw IllegalArgumentException("serialize() can only be used with objects that serialize to JsonObject")
        }
    }

    internal fun build(): Map<String, JsonElement> = fields.toMap()
}

/**
 * Custom exception that captures event context when thrown
 */
public class ContextualException(
    message: String,
    cause: Throwable? = null,
    public val eventContext: Map<String, JsonElement> = emptyMap()
) : Exception(message, cause)

/**
 * Structured event logging system with coroutine context support
 */
public object AppEvents {
    private var outputHandler: ((JsonObject, Exception?) -> Unit)? = null
    public var jsonInstance: Json = Json.Default

    /**
     * Set the output handler for events
     */
    public fun output(handler: (JsonObject, Exception?) -> Unit) {
        outputHandler = handler
    }

    /**
     * Configure the Json instance used for serialization
     */
    public fun configure(json: Json) {
        jsonInstance = json
    }

    /**
     * Add context that will be inherited by all events within the block
     */
    public suspend fun <T> context(builder: LogBuilder.() -> Unit, block: suspend () -> T): T {
        val contextData = LogBuilder().apply(builder).build()
        val existing = currentCoroutineContext()[EventContext]?.data ?: emptyMap()
        val combined = EventContext(existing + contextData)

        return withContext(combined) {
            block()
        }
    }

    /**
     * Log an info level event
     */
    public suspend inline fun event(noinline builder: LogBuilder.() -> Unit) {
        logEvent(LogLevel.INFO, null, builder)
    }

    /**
     * Log an info level event
     */
    public suspend inline fun info(noinline builder: LogBuilder.() -> Unit) {
        logEvent(LogLevel.INFO, null, builder)
    }

    /**
     * Log a debug level event (only output if WARN+ occurs in same context)
     */
    public suspend inline fun debug(noinline builder: LogBuilder.() -> Unit) {
        logEvent(LogLevel.DEBUG, null, builder)
    }

    /**
     * Log a warning level event
     */
    public suspend inline fun warn(noinline builder: LogBuilder.() -> Unit) {
        logEvent(LogLevel.WARN, null, builder)
    }

    /**
     * Log a warning level event with exception
     */
    public suspend inline fun warn(exception: Exception, noinline builder: LogBuilder.() -> Unit) {
        logEvent(LogLevel.WARN, exception, builder)
    }

    /**
     * Log an error level event
     */
    public suspend inline fun error(noinline builder: LogBuilder.() -> Unit) {
        logEvent(LogLevel.ERROR, null, builder)
    }

    /**
     * Log an error level event with exception
     */
    public suspend inline fun error(exception: Exception, noinline builder: LogBuilder.() -> Unit) {
        logEvent(LogLevel.ERROR, exception, builder)
    }

    /**
     * Wrap an exception with current context and re-throw
     */
    public suspend fun wrap(throwable: Throwable): Nothing {
        val currentContext = currentCoroutineContext()[EventContext]?.data ?: emptyMap()
        throw ContextualException(
            message = throwable.message ?: "Unknown error",
            cause = throwable,
            eventContext = currentContext
        )
    }

    /**
     * Execute a block with conditional logging.
     * DEBUG logs are buffered and only output if a WARN or ERROR occurs within the block.
     */
    public suspend fun <T> conditionalLog(block: suspend () -> T): T {
        val conditionalContext = ConditionalLoggingContext()

        return withContext(conditionalContext) {
            try {
                val result = block()

                // After block completes, flush buffered events if there were warnings/errors
                if (conditionalContext.hasWarningOrError) {
                    conditionalContext.bufferedEvents.forEach { event ->
                        outputHandler?.invoke(event, null)
                    }
                }
                // Otherwise, buffered DEBUG events are discarded

                result
            } catch (e: Exception) {
                // On exception, flush all buffered events
                conditionalContext.bufferedEvents.forEach { event ->
                    outputHandler?.invoke(event, null)
                }
                throw e
            }
        }
    }

    /**
     * Log an event with specified level and optional exception
     */
    public suspend fun logEvent(level: LogLevel, exception: Exception?, builder: LogBuilder.() -> Unit) {
        val eventData = LogBuilder().apply(builder).build()
        val contextData = currentCoroutineContext()[EventContext]?.data ?: emptyMap()

        val eventJson = buildJsonObject {
            put("timestamp", JsonPrimitive(Clock.System.now().toString()))
            put("level", JsonPrimitive(level.name))

            // Add context data
            contextData.forEach { (key, value) ->
                put(key, value)
            }

            // Add event-specific data
            eventData.forEach { (key, value) ->
                put(key, value)
            }

            // Add exception info if present
            exception?.let { ex ->
                put("exception", buildJsonObject {
                    put("message", JsonPrimitive(ex.message ?: ""))
                    put("type", JsonPrimitive(ex::class.simpleName))
                    put("stackTrace", JsonPrimitive(ex.stackTraceToString()))

                    // Include context from ContextualException if available
                    if (ex is ContextualException) {
                        putJsonObject("context") {
                            ex.eventContext.forEach { (key, value) ->
                                put(key, value)
                            }
                        }
                    }
                })
            }
        }

        handleConditionalLogging(level, eventJson, exception)
    }

    private suspend fun handleConditionalLogging(level: LogLevel, event: JsonObject, exception: Exception?) {
        val conditionalContext = currentCoroutineContext()[ConditionalLoggingContext]

        if (conditionalContext != null) {
            // We're in a conditional logging context
            when (level) {
                LogLevel.DEBUG -> {
                    // Buffer DEBUG events
                    conditionalContext.bufferedEvents.add(event)
                }

                LogLevel.INFO -> {
                    // Always output INFO immediately
                    outputHandler?.invoke(event, exception)
                }

                LogLevel.WARN, LogLevel.ERROR -> {
                    // Mark that we need to flush and output immediately
                    conditionalContext.hasWarningOrError = true
                    outputHandler?.invoke(event, exception)
                }
            }
        } else {
            // No conditional logging, output everything directly
            outputHandler?.invoke(event, exception)
        }
    }
}
public typealias EVT = AppEvents