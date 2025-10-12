package com.steamstreet.awskt.logging

import net.logstash.logback.marker.Markers
import org.slf4j.LoggerFactory


public class Slf4JLogPublisher : LogPublisher {
    private val slf4j = LoggerFactory.getLogger("Logger")

    override suspend fun publish(
        level: Log.Level,
        message: String,
        context: Log.LoggingContext
    ) {
        val t = context.exceptions?.firstOrNull()
        val markers = context.contextMap.map { (key, value) ->
            Markers.appendRaw(key, value.toString())
        }
        if (markers.isNotEmpty()) {
            val aggregated = Markers.aggregate(markers)
            when (level) {
                Log.Level.INFO -> slf4j.info(aggregated, message, t)
                Log.Level.WARN -> slf4j.warn(aggregated, message, t)
                Log.Level.ERROR -> slf4j.error(aggregated, message, t)
                Log.Level.EVENT -> slf4j.info(aggregated, message, t)
            }
        } else {
            when (level) {
                Log.Level.INFO -> slf4j.info(message, t)
                Log.Level.WARN -> slf4j.warn(message, t)
                Log.Level.ERROR -> slf4j.error(message, t)
                Log.Level.EVENT -> slf4j.info(message, t)
            }
        }
    }
}