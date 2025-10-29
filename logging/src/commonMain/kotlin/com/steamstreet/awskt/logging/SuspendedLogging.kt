package com.steamstreet.awskt.logging

import com.steamstreet.awskt.logging.Log.Level
import com.steamstreet.awskt.logging.Log.LoggingContextBuilder
import com.steamstreet.collections.filterNotNullValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Interface for publishing log messages with structured context.
 *
 * Implement this interface to define how log messages should be published
 * (e.g., to console, CloudWatch, file, etc.). The publisher receives the log
 * level, message, and full logging context for each log event.
 *
 * This is a suspending interface to support asynchronous log publishing
 * operations such as network calls or buffered I/O.
 */
public interface LogPublisher {
    /**
     * Publish a log message with the specified level and context.
     *
     * @param level The severity level of the log message (INFO, WARN, ERROR, EVENT)
     * @param message The human-readable log message
     * @param context The structured logging context containing metadata and exceptions
     */
    public suspend fun publish(level: Level, message: String?, context: Log.LoggingContext)
}


/**
 * Main logging class providing structured, coroutine-aware logging capabilities.
 *
 * This class provides a modern approach to logging in Kotlin coroutines, using
 * CoroutineContext to propagate logging metadata instead of thread-local storage.
 * This makes it reliable across all dispatchers and coroutine contexts.
 *
 * Key features:
 * - Automatic context propagation to child coroutines
 * - Structured logging with type-safe JSON serialization
 * - Support for exception metadata via MDCExceptionMixin
 * - Suspending API for asynchronous log publishing
 *
 * @param publisher The log publisher that handles actual log output
 *
 * Example usage:
 * ```
 * val log = Log(ConsoleLogPublisher())
 *
 * log.ctx({
 *     "userId" `is` "123"
 *     "requestId" `is` "abc"
 * }) {
 *     log.info("Processing request")
 *     launch {
 *         // Context automatically propagates
 *         log.info("In child coroutine")
 *     }
 * }
 * ```
 */
public class Log(public var publisher: LogPublisher) {
    /**
     * Log severity levels.
     *
     * - INFO: Informational messages
     * - WARN: Warning conditions
     * - ERROR: Error conditions
     * - EVENT: Business events (structured event logging)
     */
    public enum class Level(public val priority: Int) {
        INFO(10), WARN(20), ERROR(30), EVENT(25)
    }

    public companion object {

        /**
         * The JSON encoder used for serializing structured logging context.
         *
         * This encoder is used to convert Kotlin objects to JsonElement for inclusion
         * in the logging context. Uses kotlinx.serialization for type-safe serialization.
         */
        public val encoder: Json = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
            prettyPrint = false
        }
    }

    /**
     * A CoroutineContext element that carries logging context metadata.
     * This propagates automatically to child coroutines and can be retrieved
     * at any logging call site.
     *
     * Unlike MDCContext which is tied to thread-local storage, LoggingContext
     * is part of the coroutine context and works reliably across all dispatchers.
     */
    public class LoggingContext(
        public val contextMap: Map<String, JsonElement?> = emptyMap(),
        public val exceptions: List<Throwable> = emptyList()
    ) : AbstractCoroutineContextElement(LoggingContext) {
        public companion object Key : CoroutineContext.Key<LoggingContext>

        public operator fun plus(other: Map<String, JsonElement?>): LoggingContext =
            LoggingContext(contextMap + other, exceptions)

        public operator fun plus(other: LoggingContext): LoggingContext =
            LoggingContext(contextMap + other.contextMap, exceptions + other.exceptions)

        override fun equals(other: Any?): Boolean =
            other is LoggingContext && contextMap == other.contextMap && exceptions == other.exceptions

        override fun hashCode(): Int = 31 * contextMap.hashCode() + exceptions.hashCode()
    }

    /**
     * Builder interface for constructing structured logging context.
     *
     * This builder allows adding metadata to the logging context in various formats:
     * - Key-value pairs with automatic JSON serialization
     * - Complete JSON objects that will be merged into the context
     * - Throwable exceptions that will be included in the log output
     *
     * Use extension functions like `put(key: String, value: T)` for type-safe
     * serialization of any type supported by kotlinx.serialization.
     */
    public interface LoggingContextBuilder {
        /**
         * Add a key-value pair to the logging context.
         *
         * @param key The metadata key
         * @param value The value as a JsonElement, or null to skip
         */
        public fun put(key: String, value: JsonElement?)

        /**
         * Merge a JSON object into the logging context.
         *
         * All key-value pairs from the object will be added to the context.
         *
         * @param value The JSON object to merge
         */
        public fun put(value: JsonObject)

        /**
         * Add a throwable exception to the logging context.
         *
         * The exception will be included in the log output, and if it implements
         * MDCExceptionMixin, its MDC attributes will be added to the context.
         *
         * @param t The exception to add, or null to skip
         */
        public fun put(t: Throwable?)
    }

    /**
     * Create a logging context that automatically propagates to child coroutines.
     * This is the recommended approach for structured logging across coroutines.
     *
     * Unlike MDCContext which uses thread-local storage, LoggingContext is part of
     * the CoroutineContext and automatically propagates to all child coroutines
     * created with launch, async, etc. No special helpers needed!
     *
     * The context defined here will be merged with any existing context, with new
     * values taking precedence. All child coroutines spawned within the block will
     * automatically inherit this context.
     *
     * @param builder Lambda to configure the logging context using LoggingContextBuilder
     * @param block Coroutine scope where the context is active
     * @return The result of the block execution
     *
     * Example:
     * ```
     * log.ctx({
     *     "requestId" `is` "123"
     *     "userId" `is` "456"
     *     put(exception)  // Add exception if present
     * }) {
     *     log.info("Processing request")
     *     launch {
     *         // Context automatically available in child coroutine!
     *         log.info("In child")  // Will include requestId and userId
     *     }
     * }
     * ```
     */
    public suspend fun <T> ctx(
        builder: LoggingContextBuilder.() -> Unit,
        block: suspend CoroutineScope.() -> T
    ): T {
        val map = mutableMapOf<String, JsonElement>()
        val throwables = mutableListOf<Throwable>()
        object : LoggingContextBuilder {
            override fun put(key: String, value: JsonElement?) {
                if (value != null) {
                    map[key] = value
                }
            }

            override fun put(value: JsonObject) {
                map.putAll(value)
            }

            override fun put(t: Throwable?) {
                if (t != null) {
                    throwables.add(t)
                }
            }
        }.builder()
        return jsonCtx(map, throwables, block)
    }

    private suspend fun <T> jsonCtx(
        map: Map<String, JsonElement>,
        t: List<Throwable>,
        block: suspend CoroutineScope.() -> T
    ): T {
        val currentContext = currentCoroutineContext()[LoggingContext]
        val newContext = currentContext
            ?.plus(LoggingContext(map, t))
            ?: LoggingContext(map, t)
        return withContext(newContext, block)
    }

    /**
     * Get the current logging context.
     */
    public suspend fun ctx(): Map<String, JsonElement> {
        return getLoggingContext().filterNotNullValues()
    }

    /**
     * Get the current logging context from the coroutine context, if available.
     */
    internal suspend fun getLoggingContext(): Map<String, JsonElement?> {
        return currentCoroutineContext()[LoggingContext]?.contextMap ?: emptyMap()
    }

    /**
     * Low-level log method that publishes a message with the current coroutine context.
     *
     * This method retrieves the LoggingContext from the current coroutine context
     * and publishes the log message. Most users should prefer the convenience methods
     * (info, warning, error) which handle context building automatically.
     *
     * @param level The severity level of the log message
     * @param message The log message
     * @param t Optional throwable to include (note: currently not used, context should be added via ctx)
     */
    public suspend fun log(level: Level, message: String?, t: Throwable? = null) {
        val ctx0 = currentCoroutineContext()[LoggingContext] ?: LoggingContext()
        val ctx = if (t != null) ctx0 + LoggingContext(emptyMap(), listOf(t)) else ctx0
        publisher.publish(level, message, ctx)
    }

    /**
     * Log a message with structured data serialized to JSON.
     *
     * This method automatically serializes the data object to JSON and includes it
     * in the logging context. The serialization uses kotlinx.serialization with
     * reified type parameters for automatic serializer selection.
     *
     * @param T The type of data to serialize (must be @Serializable)
     * @param message The log message
     * @param data The data object to serialize and include in context
     * @param level The log level (default: INFO)
     * @param field Optional field name to nest the data under. If null, the data object is merged directly into context
     *
     * Example:
     * ```
     * @Serializable
     * data class User(val id: String, val name: String)
     *
     * log.data("User created", User("123", "Alice"), field = "user")
     * // Logs: {"message": "User created", "user": {"id": "123", "name": "Alice"}}
     * ```
     */
    public suspend inline fun <reified T> data(
        message: String,
        data: T,
        level: Level = Level.INFO,
        field: String? = null
    ) {
        data(message, encoder.serializersModule.serializer<T>(), data, level, field)
    }

    /**
     * Log a message with structured data serialized to JSON using an explicit serializer.
     *
     * This overload allows you to provide a custom KSerializer for the data object,
     * which is useful when working with polymorphic types or when you need to use
     * a specific serializer configuration.
     *
     * @param T The type of data to serialize
     * @param message The log message
     * @param serializer The KSerializer to use for serialization
     * @param data The data object to serialize and include in context
     * @param level The log level (default: INFO)
     * @param field Optional field name to nest the data under. If null, the data object is merged directly into context
     */
    public suspend inline fun <reified T> data(
        message: String,
        serializer: KSerializer<T>,
        data: T,
        level: Level = Level.INFO,
        field: String? = null
    ) {
        ctx({
            val encoded = encoder.encodeToJsonElement(serializer, data)
            when {
                field != null -> put(field, encoded)
                encoded is JsonObject -> put(encoded)
                else -> put("data", encoded) // or error("field required for non-object data")
            }
        }) {
            log(level, message)
        }
    }

    /**
     * Log a warning message with optional throwable and additional metadata.
     *
     * Warnings indicate potentially harmful situations that don't prevent the
     * application from continuing. This method automatically includes the
     * LoggingContext from the current coroutine context and merges in any
     * additional metadata provided via the builder.
     *
     * If the throwable implements MDCExceptionMixin, its MDC attributes will
     * automatically be included in the logging context.
     *
     * @param message The warning message
     * @param throwable Optional exception associated with the warning
     * @param builder Optional lambda to add additional context metadata
     *
     * Example:
     * ```
     * log.warning("Cache miss", exception) {
     *     "cacheKey" `is` key
     *     "ttl" `is` 300
     * }
     * ```
     */
    public suspend fun warning(
        message: String,
        throwable: Throwable? = null,
        builder: LoggingContextBuilder.() -> Unit = {}
    ) {
        this.ctx({
            put(throwable)
            builder()
        }) {
            log(Level.WARN, message, throwable)
        }
    }

    /**
     * Log an error message with optional throwable and additional metadata.
     *
     * Errors indicate serious problems that have occurred. This method automatically
     * includes the LoggingContext from the current coroutine context and merges in
     * any additional metadata provided via the builder.
     *
     * If the throwable implements MDCExceptionMixin, its MDC attributes will
     * automatically be included in the logging context.
     *
     * @param message The error message
     * @param throwable Optional exception associated with the error
     * @param builder Optional lambda to add additional context metadata
     *
     * Example:
     * ```
     * log.error("Database connection failed", exception) {
     *     "host" `is` dbHost
     *     "port" `is` dbPort
     *     "retryCount" `is` retries
     * }
     * ```
     */
    public suspend fun error(
        message: String,
        throwable: Throwable? = null,
        builder: LoggingContextBuilder.() -> Unit = {}
    ) {
        this.ctx({
            put(throwable)
            builder()
        }) {
            log(Level.ERROR, message, throwable)
        }
    }

    /**
     * Log an informational message with optional additional metadata.
     *
     * Info messages provide general informational output about application flow.
     * This method automatically includes the LoggingContext from the current
     * coroutine context and merges in any additional metadata provided via the builder.
     *
     * @param message The info message
     * @param builder Optional lambda to add additional context metadata
     *
     * Example:
     * ```
     * log.info("Request processed successfully") {
     *     "duration" `is` elapsedMs
     *     "itemsProcessed" `is` count
     * }
     * ```
     */
    public suspend fun info(message: String, builder: LoggingContextBuilder.() -> Unit = {}) {
        ctx(builder) {
            log(Level.INFO, message)
        }
    }

    /**
     * Log an informational message asynchronously within the specified coroutine scope.
     *
     * This method launches a new coroutine to perform the logging operation, making it
     * non-blocking. The logging context from the current coroutine scope will be propagated
     * to the logging operation.
     *
     * @param scope The CoroutineScope in which to launch the logging operation
     * @param message The info message to log
     * @param builder Optional lambda to add additional context metadata
     *
     * Example:
     * ```
     * viewModelScope.info("Background task started") {
     *     "taskId" `is` id
     *     "priority" `is` priority
     * }
     * ```
     */
    public fun info(scope: CoroutineScope, message: String, builder: LoggingContextBuilder.() -> Unit = {}) {
        scope.launch {
            info(message, builder)
        }
    }

    public suspend fun event(
        builder: LoggingContextBuilder.() -> Unit = {}
    ) {
        this.ctx({
            builder()
        }) {
            log(Level.ERROR, null)
        }
    }
}

/**
 * Type-safe extension function to add any serializable value to the logging context.
 *
 * This extension automatically serializes the value using kotlinx.serialization.
 * The type must be annotated with @Serializable or have a custom serializer registered.
 * Null values are ignored and not added to the context.
 *
 * @param T The type of value to serialize (must be @Serializable)
 * @param key The metadata key
 * @param value The value to serialize and add, or null to skip
 *
 * Example:
 * ```
 * log.info("User updated") {
 *     put("userId", userId)
 *     put("changes", listOf("email", "name"))
 * }
 * ```
 */
public inline fun <reified T> LoggingContextBuilder.put(key: String, value: T?) {
    if (value != null) {
        put(key, Log.encoder.encodeToJsonElement(value))
    }
}

/**
 * Add a value to the logging context using an explicit serializer.
 *
 * This overload allows you to provide a custom KSerializer for the value,
 * useful for polymorphic types or custom serialization logic.
 * Null values are ignored and not added to the context.
 *
 * @param T The type of value to serialize
 * @param key The metadata key
 * @param serializer The KSerializer to use
 * @param value The value to serialize and add, or null to skip
 */
public fun <T> LoggingContextBuilder.put(key: String, serializer: KSerializer<T>, value: T?) {
    if (value != null) {
        put(key, Log.encoder.encodeToJsonElement(serializer, value))
    }
}

/**
 * DSL-style infix function to add typed values to logging context.
 *
 * This provides a readable syntax for adding metadata within logging context builders.
 * The value is automatically serialized using kotlinx.serialization. This is the generic
 * version that works with any @Serializable type.
 *
 * Requires context receiver [LoggingContextBuilder], so it can only be used within
 * logging context builder lambdas (e.g., in log.info { }, log.ctx { }, etc.).
 *
 * @param T The type of value to serialize (must be @Serializable)
 * @param value The value to add to the context
 * @receiver The key string
 *
 * Example:
 * ```
 * log.info("Processing") {
 *     "userId" `is` "123"
 *     "count" `is` 42
 *     "active" `is` true
 * }
 * ```
 */
context(ctx: LoggingContextBuilder)
public inline infix fun <reified T> String.`is`(value: T) {
    ctx.put(this, value)
}

/**
 * DSL-style infix function to add string values to logging context.
 *
 * Specialized overload for String values to avoid unnecessary serialization overhead.
 * Null values are ignored. Requires context receiver [LoggingContextBuilder].
 *
 * @param value The string value to add, or null to skip
 * @receiver The key string
 */
context(ctx: LoggingContextBuilder)
public infix fun String.`is`(value: String?) {
    if (value != null) {
        ctx.put(this, JsonPrimitive(value))
    }
}

/**
 * DSL-style infix function to add numeric values to logging context.
 *
 * Specialized overload for Number values (Int, Long, Double, etc.) to avoid
 * unnecessary serialization overhead. Null values are ignored.
 * Requires context receiver [LoggingContextBuilder].
 *
 * @param value The numeric value to add, or null to skip
 * @receiver The key string
 */
context(ctx: LoggingContextBuilder)
public infix fun String.`is`(value: Number?) {
    if (value != null) {
        ctx.put(this, JsonPrimitive(value))
    }
}

/**
 * DSL-style infix function to add boolean values to logging context.
 *
 * Specialized overload for Boolean values to avoid unnecessary serialization overhead.
 * Null values are ignored. Requires context receiver [LoggingContextBuilder].
 *
 * @param value The boolean value to add, or null to skip
 * @receiver The key string
 */
context(ctx: LoggingContextBuilder)
public fun String.`is`(value: Boolean?) {
    if (value != null) {
        ctx.put(this, JsonPrimitive(value))
    }
}

/**
 * Platform-specific global log instance.
 *
 * This is an expect/actual declaration that provides a global log instance
 * configured for the current platform (JVM, JS, iOS, etc.). Each platform
 * provides its own implementation with an appropriate LogPublisher.
 *
 * While convenient for quick logging, consider passing a Log instance explicitly
 * for better testability and dependency injection.
 *
 * Example:
 * ```
 * log.info("Application started") {
 *     "version" `is` appVersion
 * }
 * ```
 */
public expect var log: Log


/**
 * Backwards-compatible extension function for logging with vararg metadata.
 *
 * This extension provides compatibility with older logging code that used vararg
 * pairs instead of the builder DSL. New code should prefer the builder syntax
 * with log.info(message) { } for better type safety and readability.
 *
 * Converts various types to JSON primitives:
 * - Boolean, Number, String: Direct conversion to JsonPrimitive
 * - JsonElement: Used as-is
 * - Collection: Converted to JSON array of strings
 * - Other: Converted to string representation
 *
 * @param message The log message
 * @param metadata Variable number of key-value pairs to add to the context
 *
 * Example (legacy style):
 * ```
 * log.info("User login", "userId" to userId, "ip" to ipAddress)
 * ```
 *
 * Prefer (modern style):
 * ```
 * log.info("User login") {
 *     "userId" `is` userId
 *     "ip" `is` ipAddress
 * }
 * ```
 */
public suspend fun Log.info(message: String, vararg metadata: Pair<String, Any?>) {
    ctx({
        metadata.forEach { (k, v) ->
            when (v) {
                is JsonElement -> {
                    put(k, v)
                }

                is Boolean -> {
                    k `is` v
                }

                is Number -> {
                    k `is` v
                }

                is Collection<*> -> {
                    k `is` buildJsonArray {
                        v.forEach { add(JsonPrimitive(it?.toString() ?: "null")) }
                    }
                }

                else -> {
                    k `is` v.toString()
                }
            }
        }
    }) {
        log(Level.INFO, message)
    }
}
