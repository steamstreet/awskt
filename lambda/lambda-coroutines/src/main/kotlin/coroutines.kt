package com.steamstreet.aws.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.steamstreet.awskt.logging.mdcContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToStream
import net.logstash.logback.marker.Markers
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream

public val logger: Logger = LoggerFactory.getLogger("Lambda")

@OptIn(ExperimentalSerializationApi::class)
public val lambdaJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

public var logIncoming: Boolean = System.getenv()["LogIncomingData"]?.toBoolean() ?: true
public lateinit var lambdaContext: Context

/**
 * Read the incoming data stream as text and log if configured to do so.
 */
public suspend fun InputStream.readIncoming(log: Boolean = logIncoming, handler: suspend (String) -> Unit) {
    val text = reader().readText()
    try {
        if (log) {
            logger.info(Markers.appendRaw("input", text), "Request received")
        }
        handler(text)
    } catch (t: Throwable) {
        // we always log failures to read.
        if (!log) {
            logger.info(Markers.appendRaw("input", text), "Request received")
        }
        throw t
    }
}

/**
 * Run a no-argument handler. Pass the Lambda context.
 */
public fun <T> lambda(context: Context = MockLambdaContext(), handler: suspend CoroutineScope.() -> T): T {
    return runBlocking {
        lambdaContext = context
        withContext(Dispatchers.Default) {
            mdcContext("requestId" to context.awsRequestId, "@requestId" to context.awsRequestId) {
                try {
                    handler()
                } catch (t: Throwable) {
                    logger.error("Handler failed", t)
                    throw t
                }
            }
        }
    }
}

/**
 * A lambda handler that has input but does not output anything.
 */
public inline fun <reified T> lambdaInput(
    input: InputStream,
    context: Context,
    logInput: Boolean = logIncoming,
    crossinline handler: suspend CoroutineScope.(T) -> Unit
) {
    lambda(context) {
        input.readIncoming(logInput) { text ->
            if (T::class == JsonElement::class) {
                val el = lambdaJson.parseToJsonElement(text)
                handler(el as T)
            } else {
                val parameter = lambdaJson.decodeFromString<T>(text)
                handler(parameter)
            }
        }
    }
}

/**
 * A lambda handler that has input but does not output anything.
 */
public inline fun <reified T> lambdaInput(
    input: InputStream,
    serializer: KSerializer<T>,
    context: Context,
    crossinline handler: suspend CoroutineScope.(T) -> Unit
) {
    lambda(context) {
        input.readIncoming { text ->
            if (T::class == JsonElement::class) {
                val el = lambdaJson.parseToJsonElement(text)
                handler(el as T)
            } else {
                val parameter = lambdaJson.decodeFromString(serializer, text)
                handler(parameter)
            }
        }
    }
}

/**
 * A lambda handler with input and output. Provide serializers so there is no reflection and thus
 * can be used by Graalvm.
 */
public inline fun <reified T, reified R> lambdaIO(
    input: InputStream,
    inSerializer: KSerializer<T>,
    output: OutputStream,
    outSerializer: KSerializer<R>,
    context: Context,
    crossinline handler: suspend CoroutineScope.(T) -> R
) {
    lambda(context) {
        input.readIncoming { text ->
            val parameter = lambdaJson.decodeFromString(inSerializer, text)
            val result = handler(parameter)
            val resultData = lambdaJson.encodeToString(outSerializer, result)

            logger.info(Markers.appendRaw("event", resultData), "Lambda response sent")

            output.writer().apply {
                write(resultData)
                flush()
            }
        }
    }
}

/**
 * A lambda handler with input and output.
 */
public inline fun <reified T, reified R> lambdaIO(
    input: InputStream,
    output: OutputStream,
    context: Context,
    crossinline handler: suspend CoroutineScope.(T) -> R
) {
    lambda(context) {
        input.readIncoming { text ->
            val parameter = lambdaJson.decodeFromString<T>(text)
            val result = handler(parameter)
            val resultData = lambdaJson.encodeToString(result)

            logger.info(Markers.appendRaw("event", resultData), "Lambda response sent")
            output.writer().apply {
                write(resultData)
                flush()
            }
        }
    }
}

/**
 * Base class for any lambda that wants to suspend.
 */
public interface SuspendingLambda {
    public var logIncoming: Boolean

    /**
     * The entry point from lambda. Sets up the context and then calls the suspending handle implementation
     */
    public fun execute(input: InputStream, output: OutputStream, context: Context) {
        lambda(context) {
            handle(input, output)
        }
    }

    /**
     * Override for a suspending implementation.
     */
    public suspend fun handle(input: InputStream, output: OutputStream)
}

/**
 * An abstract base class for lambdas that receive input but do not produce output.
 */
public abstract class InputLambda<T>(private val serializer: KSerializer<T>) : SuspendingLambda {
    override var logIncoming: Boolean = true

    override suspend fun handle(input: InputStream, output: OutputStream) {
        input.readIncoming(logIncoming) { text ->
            val parameter = lambdaJson.decodeFromString(serializer, text)
            handle(parameter)
        }
    }

    public abstract suspend fun handle(input: T)
}

/**
 * An abstract base class for lambdas that receive input and respond with output.
 */
public abstract class IOLambda<T, R>(
    private val serializer: KSerializer<T>,
    private val out: KSerializer<R>
) : SuspendingLambda {
    override var logIncoming: Boolean = true

    override suspend fun handle(input: InputStream, output: OutputStream) {
        input.readIncoming(logIncoming) { text ->
            val parameter = lambdaJson.decodeFromString(serializer, text)
            val result = handle(parameter)

            if (result != null) {
                @OptIn(ExperimentalSerializationApi::class)
                lambdaJson.encodeToStream(out, result, output)
            }
        }
    }

    public abstract suspend fun handle(input: T): R
}