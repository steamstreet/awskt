package com.steamstreet.aws.lambda.eventbridge

import com.amazonaws.services.lambda.runtime.Context
import com.steamstreet.aws.lambda.MockLambdaContext
import com.steamstreet.aws.lambda.lambdaInput
import com.steamstreet.aws.lambda.lambdaJson
import com.steamstreet.aws.lambda.logger
import com.steamstreet.events.EventSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import net.logstash.logback.marker.Markers
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A handler for event bridge events. Also handles event bridge events packaged into an SQS
 * queue.
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
        val response = processEventBridge(element, tracePerformance, batchSqs, config)

        if (response != null && output != null) {
            val responseString = lambdaJson.encodeToString(response)
            if (response.batchItemFailures.isNotEmpty()) {
                logger.info(Markers.appendRaw("batch-response", responseString), "Batch response partial failure")
            }
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
                detailType = schema.type,
                detail = Json.encodeToJsonElement(schema.serializer, payload).jsonObject,
                source = source ?: "aws-kt",
            )
        )
    )
}
