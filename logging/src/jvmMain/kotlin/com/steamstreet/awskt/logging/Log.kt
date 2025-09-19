package com.steamstreet.awskt.logging

import com.steamstreet.exceptions.MDCExceptionMixin
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import net.logstash.logback.marker.Markers
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC

public val loggingEncoder: Json = Json

/**
 * Create a suspendable context for logging with MDC.
 */
public suspend inline fun <T> mdcContext(vararg pairs: Pair<String, Any?>, crossinline block: suspend () -> T): T {
    val notNull = pairs.mapNotNull {
        if (it.second == null) null
        else it.first to it.second!!.toString()
    }
    return withContext(MDCContext(*notNull.toTypedArray())) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is MDCException) {
                MDC.getCopyOfContextMap()?.let {
                    it.filter {
                        e.mdcAttributes.contains(it.key)
                    }.let {
                        e.mdcAttributes.putAll(it)
                    }
                }
            }
            throw e
        }
    }
}

/**
 * Construct an MDC context with data.
 */
public fun MDCContext(vararg pairs: Pair<String, String>): MDCContext =
    MDCContext(MDC.getCopyOfContextMap().orEmpty() + pairs)

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


public fun <T> mdc(vararg metadata: Pair<String, Any?>, block: () -> T): T {
    return mdc(metadata.toMap(), block)
}

private val defaultLogger = LoggerFactory.getLogger("EventLogger")

/**
 * Log a JSON structure to the log record.
 */
public fun Logger.logJson(message: String, key: String, json: String) {
    this.info(Markers.appendRaw(key, json), message)
}

/**
 * Log serialized JSON to the log.
 */
public fun <T> Logger.logJson(message: String, key: String, serializer: KSerializer<T>, data: T) {
    logJson(message, key, loggingEncoder.encodeToString(serializer, data))
}

/**
 * Log a value as structured json, assigning it to a field.
 */
public inline fun <reified T> Logger.logValue(message: String, field: String, data: T) {
    val element = loggingEncoder.encodeToString(data)
    logJson(message, field, element)
}

/**
 * Log serialized JSON to the log.
 */
public fun <T> logJson(message: String, key: String, serializer: KSerializer<T>, data: T) {
    defaultLogger.logJson(message, key, loggingEncoder.encodeToString(serializer, data))
}

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
public fun logError(message: String, context: JsonElement, throwable: Throwable? = null) {
    if (context is JsonObject) {
        val markers = context.map { (key, value) ->
            Markers.appendRaw(key, value.toString())
        }
        defaultLogger.error(Markers.aggregate(markers), message, throwable)
    } else {
        defaultLogger.error(Markers.appendRaw("context", context.toString()), message, throwable)
    }
}

public inline fun <reified T> logErrorObject(message: String, data: T, throwable: Throwable? = null) {
    val element = loggingEncoder.encodeToJsonElement(data)
    logError(message, element, throwable)
}


/**
 * Log serialized JSON to the log.
 */
public inline fun <reified T> logValue(message: String, field: String, data: T) {
    logJson(message, field, loggingEncoder.serializersModule.serializer<T>(), data)
}

/**
 * Log a value as structured json, setting the data values at the root of the
 * log message.
 */
public inline fun <reified T> Logger.logValue(message: String, data: T) {
    val element = loggingEncoder.encodeToJsonElement(data)
    if (element is JsonObject) {
        val markers = element.map { (key, value) ->
            Markers.appendRaw(key, value.toString())
        }
        info(Markers.aggregate(markers), message)
    } else {
        logJson(message, "data", element.toString())
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
public fun logEvent(message: String, config: EventLogContext.() -> Unit = {}) {
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
 * Log warning with additional metadata
 */
public fun logWarning(message: String, vararg metadata: Pair<String, Any?>) {
    mdc(*metadata) {
        defaultLogger.warn(message)
    }
}

public open class MDCException(message: String?, cause: Throwable? = null) : Exception(message, cause),
    MDCExceptionMixin {
    override val mdcAttributes: MutableMap<String, Any?> = MDC.getCopyOfContextMap()?.toMutableMap() ?: mutableMapOf()
}

/**
 * Merge MDC data from a throwable.
 */
private fun mergeMdc(throwable: Throwable?, metadata: Array<out Pair<String, Any?>>): Map<String, Any?> {
    return if (throwable != null && throwable is MDCExceptionMixin) {
        throwable.mdcAttributes + metadata.toMap()
    } else metadata.toMap()
}

public fun logWarning(message: String, throwable: Throwable?, vararg metadata: Pair<String, Any?>) {
    mdc(mergeMdc(throwable, metadata)) {
        defaultLogger.warn(message, throwable)
    }
}

public fun logError(message: String, vararg metadata: Pair<String, Any?>) {
    mdc(*metadata) {
        defaultLogger.error(message)
    }
}

public fun logError(message: String, throwable: Throwable?, vararg metadata: Pair<String, Any?>) {
    mdc(mergeMdc(throwable, metadata)) {
        defaultLogger.error(message, throwable)
    }
}

/**
 * Log info with additional metadata
 */
public fun logInfo(message: String, vararg metadata: Pair<String, Any?>) {
    mdc(*metadata) {
        defaultLogger.info(message)
    }
}


/**
 * Log info with additional metadata
 */
public fun logInfo(message: String, builderAction: MutableList<Pair<String, Any>>.() -> Unit) {
    val metadata: List<Pair<String, Any>> = buildList(builderAction)
    mdc(*metadata.toTypedArray()) {
        defaultLogger.info(message)
    }
}