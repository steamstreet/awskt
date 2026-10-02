package com.steamstreet.awskt.logging

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/*
 * The non-suspending logging functions, available on every platform.
 *
 * They began on the JVM, built on SLF4J's MDC and the Logstash encoder, and code that logs this way
 * could not compile for any other target. On the JVM their behaviour and output are unchanged. On
 * every other platform each call writes one JSON object per line in [JsonLogPublisher]'s format, so
 * the same CloudWatch Logs Insights queries read a JVM function and a native one.
 *
 * Metadata values are written as strings on every platform, as the JVM's MDC writes them. A null
 * value is left out.
 */

/**
 * Log an informational message, with [metadata] added to the log event.
 */
public expect fun logInfo(message: String, vararg metadata: Pair<String, Any?>)

/**
 * Log a warning, with [metadata] added to the log event.
 */
public expect fun logWarning(message: String, vararg metadata: Pair<String, Any?>)

/**
 * Log a warning with the [throwable] that caused it, with [metadata] added to the log event. When
 * the throwable carries MDC attributes (`MDCExceptionMixin`), they are added too, and [metadata]
 * takes precedence over them.
 */
public expect fun logWarning(message: String, throwable: Throwable?, vararg metadata: Pair<String, Any?>)

/**
 * Log an error, with [metadata] added to the log event.
 */
public expect fun logError(message: String, vararg metadata: Pair<String, Any?>)

/**
 * Log an error with the [throwable] that caused it, with [metadata] added to the log event. When
 * the throwable carries MDC attributes (`MDCExceptionMixin`), they are added too, and [metadata]
 * takes precedence over them.
 */
public expect fun logError(message: String, throwable: Throwable?, vararg metadata: Pair<String, Any?>)

/**
 * Log [data], serialized with [serializer], as structured JSON under [key].
 */
public expect fun <T> logJson(message: String, key: String, serializer: KSerializer<T>, data: T)

/**
 * Log [data] as structured JSON under [field].
 */
public inline fun <reified T> logValue(message: String, field: String, data: T) {
    logJson(message, field, serializer<T>(), data)
}

/**
 * Run [block] with [pairs] added to every log event it writes, through these functions or through
 * [log]. Null values are left out, and the rest are written as strings. Contexts nest, and an inner
 * value replaces an outer one with the same key.
 *
 * On the JVM the fields follow the coroutine across threads and suspensions, through SLF4J's MDC.
 *
 * Elsewhere, the coroutine's logging context carries them to [log], which therefore sees exactly the
 * fields of its own coroutine. The non-suspending functions cannot read a coroutine's context, so
 * they read a process-wide registry instead. It holds the fields of every `mdcContext` block that is
 * running, so two coroutines that run concurrently under different values can each see the other's
 * fields. A block's fields are always gone once it ends, so nothing outlives the request that set it.
 * A native Lambda runs one invocation at a time, so this matters only to concurrent work inside one
 * invocation.
 */
public expect suspend fun <T> mdcContext(vararg pairs: Pair<String, Any?>, block: suspend () -> T): T
