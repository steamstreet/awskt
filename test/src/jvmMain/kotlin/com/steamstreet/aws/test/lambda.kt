package com.steamstreet.aws.test

import com.amazonaws.services.lambda.runtime.ClientContext
import com.amazonaws.services.lambda.runtime.CognitoIdentity
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.LambdaLogger
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class LambdaLocalContext(val name: String = "UnknownFunction") : Context {
    val uuid = UUID.randomUUID().toString()
    override fun getAwsRequestId(): String {
        return uuid
    }

    override fun getLogGroupName(): String {
        TODO("not implemented")
    }

    override fun getLogStreamName(): String {
        TODO("not implemented")
    }

    override fun getFunctionName(): String {
        return name
    }

    override fun getFunctionVersion(): String {
        return "1"
    }

    override fun getInvokedFunctionArn(): String {
        return "arn:aws:lambda:us-west-2:1234:function:${name}"
    }

    override fun getIdentity(): CognitoIdentity {
        TODO("not implemented")
    }

    override fun getClientContext(): ClientContext {
        TODO("not implemented")
    }

    override fun getRemainingTimeInMillis(): Int {
        return 10000
    }

    override fun getMemoryLimitInMB(): Int {
        return 1024
    }

    override fun getLogger(): LambdaLogger {
        return object : LambdaLogger {
            override fun log(message: String) {
                println(message)
            }

            override fun log(message: ByteArray) {
                println(String(message))
            }
        }
    }
}

class LocalLambdaClient : LambdaClient {
    val functions = HashMap<String, LambdaInvocationHandler>()
    val errors = ArrayList<Throwable>()

    val active = AtomicInteger(0)
    val processing: Boolean get() = active.get() > 0

    private val eventSourceMappings = HashMap<String, EventSourceMappingConfiguration>()

    override fun close() {}

    override fun serviceName(): String = "LambdaLocal"

    override fun invoke(invokeRequest: InvokeRequest): InvokeResponse {
        active.incrementAndGet()
        val name = invokeRequest.functionName().let {
            if (it.startsWith("arn")) {
                it.substringAfterLast(":")
            } else {
                it
            }
        }
        val handler = functions[name] ?: throw AwsServiceException.builder().build()

        return when (invokeRequest.invocationType()) {
            InvocationType.EVENT -> {
                thread {
                    try {
                        handler.invoke(invokeRequest.payload())
                    } catch (t: Throwable) {
                        t.printStackTrace()
                        errors.add(t)
                    }
                    active.decrementAndGet()
                }
                InvokeResponse.builder().statusCode(200).build()
            }
            else -> {
                try {
                    val responsePayload = handler.invoke(invokeRequest.payload())
                    InvokeResponse.builder().payload(responsePayload).statusCode(200).build()
                } catch (e: Throwable) {
                    errors.add(e)
                    InvokeResponse.builder().functionError(e.message).statusCode(200).build()
                }.also {
                    active.decrementAndGet()
                }
            }
        }
    }

    fun createFunction(name: String, handler: (InputStream) -> Unit) {
        functions.put(name, DirectInvocationHandler(handler))
    }

    override fun createFunction(createFunctionRequest: CreateFunctionRequest): CreateFunctionResponse {
        val handler = createFunctionRequest.handler()
        functions.put(
            createFunctionRequest.functionName(), ReflectionHandler(
                createFunctionRequest.functionName(),
                handler.substringBefore("::"), handler.substringAfter("::")
            )
        )
        return CreateFunctionResponse.builder().functionName(createFunctionRequest.functionName())
            .functionArn("arn:aws:lambda:us-west-2:141660060409:function:${createFunctionRequest.functionName()}")
            .build()
    }

    override fun createEventSourceMapping(createEventSourceMappingRequest: CreateEventSourceMappingRequest): CreateEventSourceMappingResponse {
        val uuid = UUID.randomUUID().toString()
        eventSourceMappings[uuid] = getEventSourceConfiguration(uuid, createEventSourceMappingRequest)
        return CreateEventSourceMappingResponse.builder().uuid(uuid).build()
    }

    override fun listEventSourceMappings(): ListEventSourceMappingsResponse {
        return ListEventSourceMappingsResponse.builder().eventSourceMappings(eventSourceMappings.values).build()
    }

    override fun listEventSourceMappings(listEventSourceMappingsRequest: ListEventSourceMappingsRequest): ListEventSourceMappingsResponse {
        val mappings = ArrayList<EventSourceMappingConfiguration>()
        val sourceArn = listEventSourceMappingsRequest.eventSourceArn()
        if (sourceArn != null) {
            mappings.addAll(eventSourceMappings.values.filter {
                it.eventSourceArn() == sourceArn
            })
        }
        return ListEventSourceMappingsResponse.builder().eventSourceMappings(mappings).build()
    }

    private fun getFunctionArn(functionNameOrArn: String): String {
        return functionNameOrArn.let {
            if (it.startsWith("arn:aws")) {
                it
            } else {
                "arn:aws:lambda:us-west-2:1234:function:${it}"
            }
        }
    }

    override fun getEventSourceMapping(getEventSourceMappingRequest: GetEventSourceMappingRequest): GetEventSourceMappingResponse {
        val config = eventSourceMappings[getEventSourceMappingRequest.uuid()]
            ?: throw AwsServiceException.create("Unknown source mapping ${getEventSourceMappingRequest.uuid()}", null)
        return GetEventSourceMappingResponse.builder()
            .eventSourceArn(config.eventSourceArn())
            .functionArn(config.functionArn())
            .uuid(getEventSourceMappingRequest.uuid())
            .build()
    }

    private fun getEventSourceConfiguration(
        uuid: String,
        mapping: CreateEventSourceMappingRequest
    ): EventSourceMappingConfiguration {
        return EventSourceMappingConfiguration.builder()
            .eventSourceArn(mapping.eventSourceArn())
            .functionArn(getFunctionArn(mapping.functionName()))
            .uuid(uuid)
            .build()
    }

    override fun listFunctions(listFunctionsRequest: ListFunctionsRequest): ListFunctionsResponse {
        return ListFunctionsResponse.builder().functions(
            functions.values.mapNotNull {
                it as? ReflectionHandler
            }.map { function ->
                FunctionConfiguration.builder().functionName(function.name).functionArn(getFunctionArn(function.name))
                    .build()
            }
        ).build()
    }
}

private val json = jacksonObjectMapper().apply {
    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}

interface LambdaInvocationHandler {
    fun invoke(payload: SdkBytes): SdkBytes
}

class ReflectionHandler(
    val name: String,
    val clazz: String,
    val methodName: String
) : LambdaInvocationHandler {
    override fun invoke(payload: SdkBytes): SdkBytes {
        val output = ByteArrayOutputStream()
        val parameterValues = method.parameters.map {
            val clz = it.type
            when {
                clz == InputStream::class.java -> {
                    ByteBufferBackedInputStream(payload.asByteBuffer())
                }
                clz == String::class.java -> {
                    String(payload.asByteArray())
                }
                clz == Context::class.java -> LambdaLocalContext(name)
                clz == OutputStream::class.java -> output
                else -> json.readValue(ByteBufferBackedInputStream(payload.asByteBuffer()).reader(), clz)
            }
        }
        method.invoke(instance, *(parameterValues.toTypedArray()))

        return SdkBytes.fromByteArray(output.toByteArray())
    }

    private val kclass: Class<*> get() = Class.forName(clazz)
    private val method
        get() = kclass.methods.find {
            it.name == methodName
        } ?: throw AwsServiceException.create("$name $clazz::$methodName", null)
    val instance get() = if (Modifier.isStatic(method.modifiers)) null else kclass.getConstructor().newInstance()
}

class DirectInvocationHandler(
    val function: (InputStream) -> Unit
) : LambdaInvocationHandler {
    override fun invoke(payload: SdkBytes): SdkBytes {
        function(payload.asInputStream())
        return SdkBytes.fromByteArray(ByteArray(0))
    }
}

class ByteBufferBackedInputStream(var buf: ByteBuffer) : InputStream() {
    @Throws(IOException::class)
    override fun read(): Int {
        return if (!buf.hasRemaining()) {
            -1
        } else (buf.get().toInt())
    }

    @Throws(IOException::class)
    override fun read(bytes: ByteArray, off: Int, len: Int): Int {
        var length = len
        if (!buf.hasRemaining()) {
            return -1
        }
        length = Math.min(length, buf.remaining())
        buf[bytes, off, length]
        return length
    }

}
