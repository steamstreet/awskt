package com.steamstreet.awskt.logging

import kotlinx.serialization.json.Json

/**
 * A very simple log publisher that prints to stdout in a structured format.
 *
 * Outputs logs in the format: LEVEL: <message> <json of context>
 *
 * Most platforms will provide a more customized version, but this is a
 * useful default.
 */
public class DefaultLogPublisher: LogPublisher {
    override suspend fun publish(
        level: Log.Level,
        message: String?,
        context: Log.LoggingContext
    ) {
        val contextJson = if (context.contextMap.isNotEmpty()) {
            " ${Json.encodeToString(context.contextMap)}"
        } else {
            ""
        }
        println("${level.name}: $message$contextJson")
    }
}