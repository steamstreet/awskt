@file:OptIn(ExperimentalTime::class)

package com.steamstreet.aws.lambda.eventbridge

import com.amazonaws.services.lambda.runtime.Context
import com.steamstreet.aws.lambda.MockLambdaContext
import com.steamstreet.aws.lambda.lambdaInput
import com.steamstreet.aws.lambda.lambdaJson
import com.steamstreet.aws.lambda.logger
import com.steamstreet.aws.sqs.BatchResponse
import com.steamstreet.awskt.logging.logError
import com.steamstreet.awskt.logging.logInfo
import com.steamstreet.awskt.logging.logJson
import com.steamstreet.awskt.logging.mdcContext
import com.steamstreet.events.EventSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Unchanged from before this module became multiplatform — slf4j with the logstash JSON marker. */
internal actual fun logProcessingEvent(message: String, key: String, json: String) {
    logger.logJson(message, key, json)
}

/** Unchanged from before the driver moved into common — the logging module's `logError`. */
internal actual suspend fun logEventError(message: String, t: Throwable?, metadata: Map<String, String?>) {
    logError(message, t, *metadata.toList().toTypedArray())
}

/** Unchanged from before the driver moved into common — the logging module's `logInfo`. */
internal actual suspend fun logEventInfo(message: String, metadata: Map<String, String?>) {
    logInfo(message, *metadata.toList().toTypedArray())
}

/** Unchanged from before the driver moved into common — slf4j's MDC. */
internal actual suspend fun <T> withEventContext(detailType: String, block: suspend () -> T): T =
    mdcContext("event-detail-type" to detailType) {
        block()
    }

/**
 * A handler for event bridge events. Also handles event bridge events packaged into an SQS
 * queue.
 *
 * The dispatch itself is now [processEventBridgePayload] in `commonMain`; what remains here is the
 * JVM plumbing — reading the stream, and writing the batch response back to [output].
 */
public fun eventBridge(
    input: InputStream,
    context: Context,
    output: OutputStream? = null,
    tracePerformance: Boolean = true,
    batchSqs: Boolean = false,
    logRequestPayload: Boolean = false,
    config: suspend EventBridgeHandlerConfig.() -> Unit
) {
    lambdaInput<JsonElement>(input, context, logInput = logRequestPayload) { element ->
        // `batchSqs` alone is not enough: with no stream to write to there is nowhere to put the
        // response, so the driver has to fall back to throwing. That coupling was implicit in the
        // old `if (!batchSqs || output == null)` and is made explicit here.
        val response = processEventBridgePayload(
            element,
            tracePerformance = tracePerformance,
            batchSqs = batchSqs && output != null,
            config = config
        )

        if (response != null && output != null) {
            val responseString = lambdaJson.encodeToString(BatchResponse.serializer(), response)
            withContext(Dispatchers.IO) {
                output.write(responseString.encodeToByteArray())
            }
        }
    }
}

/**
 * Base class for a lambda that handles event bridge events.
 */
public interface EventBridgeFunction {
    public val tracePerformance: Boolean get() = true
    public val batchRetries: Boolean get() = false

    public fun execute(input: InputStream, output: OutputStream, context: Context) {
        eventBridge(input, context, output, tracePerformance, batchRetries) {
            onEvent()
        }
    }

    public suspend fun EventBridgeHandlerConfig.onEvent()
}

/**
 * Enables testability, allowing to send a raw event to an EventBridge function.
 */
public fun EventBridgeFunction.processEvent(str: String) {
    val output = ByteArrayOutputStream()
    execute(str.byteInputStream(), output, MockLambdaContext())
}

/**
 * Process an event directly. Useful for testing.
 */
public fun <T> EventBridgeFunction.processEvent(schema: EventSchema<T>, payload: T, source: String? = null) {
    processEvent(
        eventBridgeEventDecoder.encodeToString(
            EventBridgeEvent(
                id = UUID.randomUUID().toString(),
                detailType = schema.type,
                detail = Json.encodeToJsonElement(schema.serializer, payload).jsonObject,
                source = source ?: "aws-kt",
                time = Clock.System.now(),
                version = "0"
            )
        )
    )
}
