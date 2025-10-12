package com.steamstreet.awskt.logging

/**
 * A very simple log publisher that just prints to stdout. Most
 * platforms will provide a more customized version, but this is a
 * useful default.
 */
public class DefaultLogPublisher: LogPublisher {
    override suspend fun publish(
        level: Log.Level,
        message: String,
        context: Log.LoggingContext
    ) {
        println(message)
    }
}