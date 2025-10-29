package com.steamstreet.awskt.logging

import kotlinx.serialization.json.JsonElement
import net.logstash.logback.marker.Markers
import org.slf4j.LoggerFactory


/**
 * A LogPublisher implementation that publishes logs using SLF4J (Simple Logging Facade for Java).
 *
 * This publisher integrates with the Logback logging framework and supports structured logging
 * through Logstash markers. It translates log levels and context data from the common logging
 * interface into SLF4J logging calls.
 */
public class Slf4JLogPublisher : LogPublisher {
    private val slf4j = LoggerFactory.getLogger("Logger")

    /**
     * Publishes a log message using SLF4J with the specified level, message, and context.
     *
     * @param level The severity level of the log message
     * @param message The log message text, can be null
     * @param context The logging context containing additional structured data and exceptions
     */
    override suspend fun publish(
        level: Log.Level,
        message: String?,
        context: Log.LoggingContext
    ) {
        val msg = message ?: ""
        val t = context.exceptions.firstOrNull()
        val markers = context.contextMap.mapNotNull { (key, value) ->
            value?.let {
                Markers.appendRaw(
                    key, Log.encoder
                        .encodeToString(JsonElement.serializer(), it)
                )
            }
        }
        if (markers.isNotEmpty()) {
            val aggregated = Markers.aggregate(markers)
            when (level) {
                Log.Level.INFO -> slf4j.info(aggregated, msg, t)
                Log.Level.WARN -> slf4j.warn(aggregated, msg, t)
                Log.Level.ERROR -> slf4j.error(aggregated, msg, t)
                Log.Level.EVENT -> slf4j.info(aggregated, msg, t)
            }
        } else {
            when (level) {
                Log.Level.INFO -> slf4j.info(msg, t)
                Log.Level.WARN -> slf4j.warn(msg, t)
                Log.Level.ERROR -> slf4j.error(msg, t)
                Log.Level.EVENT -> slf4j.info(msg, t)
            }
        }
    }
}