package com.steamstreet.awskt.logging

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A log publisher that sends log messages to a specified HTTP endpoint via PUT requests.
 *
 * This publisher creates a JSON structure containing the log context and message,
 * then sends it to the configured URL. It uses Ktor's HTTP client for cross-platform
 * compatibility.
 *
 * Logs below the minimum level are delegated to the backup publisher instead of being sent
 * to the HTTP endpoint. The backup publisher is also used as a fallback when HTTP requests fail.
 *
 * @param url The URL endpoint to send logs to
 * @param minimumLevel The minimum log level to post to the URL. Logs below this level use the backup publisher.
 * @param backupPublisher The publisher to use for logs below the minimum level or when HTTP requests fail. Defaults to DefaultLogPublisher.
 * @param httpClient Optional custom HTTP client. If not provided, a default client is created.
 *
 * Example usage:
 * ```
 * val logPublisher = HttpLogPublisher(
 *     url = "https://logs.example.com/ingest",
 *     minimumLevel = Log.Level.WARN
 * )
 * val log = Log(logPublisher)
 *
 * log.info("Application started") {  // Handled by backup publisher
 *     "version" `is` "1.0.0"
 * }
 * log.error("Fatal error occurred")  // Sent to HTTP endpoint
 * ```
 */
public class HttpLogPublisher(
    private val url: String,
    private val minimumLevel: Log.Level = Log.Level.INFO,
    private val backupPublisher: LogPublisher = DefaultLogPublisher(),
    private val httpClient: HttpClient = HttpClient()
) : LogPublisher {
    /**
     * If true, also publishes to the provided backup publisher, even
     * for events that are published to the HTTP endpoint.
     */
    public var alsoPublishToBackupPublisher: Boolean = true


    override suspend fun publish(
        level: Log.Level,
        message: String?,
        context: Log.LoggingContext
    ) {
        if (level.priority < minimumLevel.priority) {
            // Log level is below minimum, use backup publisher
            backupPublisher.publish(level, message, context)
        } else {
            // Log level meets or exceeds minimum, post to HTTP endpoint
            // Build JSON object with level and message fields, plus all context fields at root level
            val payload = buildJsonObject {
                put("level", level.name)
                put("timestamp", kotlin.time.Clock.System.now().toString())
                if (message != null) {
                    put("message", message)
                }
                // Merge all context fields at the root level
                context.contextMap.forEach { (key, value) ->
                    if (value != null) {
                        put(key, value)
                    }
                }

                if (context.exceptions.isNotEmpty()) {
                    val first = context.exceptions.first()
                    put("exception", buildJsonObject {
                        put("type", first::class.simpleName ?: "Throwable")
                        put("message", first.message ?: "")
                        // Optional (JVM only): stack as string (guard size!)
                        // put("stack", JsonPrimitive(first.stackTraceToString().take(20_000)))
                    })
                }
            }

            val jsonString = Log.encoder.encodeToString(payload)

            try {
                val response = httpClient.put(url) {
                    contentType(ContentType.Application.Json)
                    setBody(jsonString)
                }
                if (alsoPublishToBackupPublisher || !response.status.isSuccess()) {
                    // also publish to the local publisher.
                    backupPublisher.publish(level, message, context)
                }
            } catch (_: Exception) {
                // Fallback to the backup publisher if the HTTP request fails
                backupPublisher.publish(level, message, context)
            }
        }
    }

    /**
     * Close the HTTP client when done. Call this when shutting down the application
     * to ensure resources are properly released.
     */
    public fun close() {
        httpClient.close()
    }
}
