package com.steamstreet.awskt.logging

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

// Each call writes one JSON line through writeLogLine; see LineLogging.kt.

public actual fun logInfo(message: String, vararg metadata: Pair<String, Any?>) {
    writeLogLine(Log.Level.INFO, message, metadata = metadata)
}

public actual fun logWarning(message: String, vararg metadata: Pair<String, Any?>) {
    writeLogLine(Log.Level.WARN, message, metadata = metadata)
}

public actual fun logWarning(message: String, throwable: Throwable?, vararg metadata: Pair<String, Any?>) {
    writeLogLine(Log.Level.WARN, message, throwable, metadata)
}

public actual fun logError(message: String, vararg metadata: Pair<String, Any?>) {
    writeLogLine(Log.Level.ERROR, message, metadata = metadata)
}

public actual fun logError(message: String, throwable: Throwable?, vararg metadata: Pair<String, Any?>) {
    writeLogLine(Log.Level.ERROR, message, throwable, metadata)
}

// The JVM encodes with a default Json too, so the structure under the key matches.
public actual fun <T> logJson(message: String, key: String, serializer: KSerializer<T>, data: T) {
    writeLogLine(Log.Level.INFO, message, fields = mapOf(key to Json.encodeToJsonElement(serializer, data)))
}

public actual suspend fun <T> mdcContext(vararg pairs: Pair<String, Any?>, block: suspend () -> T): T =
    withLogFields(mdcFields(pairs), block)
