package com.steamstreet.aws.test

import aws.sdk.kotlin.runtime.AwsServiceException
import aws.sdk.kotlin.services.lambda.LambdaClient
import aws.sdk.kotlin.services.lambda.model.*
import com.amazonaws.services.lambda.runtime.ClientContext
import com.amazonaws.services.lambda.runtime.CognitoIdentity
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.LambdaLogger
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Modifier
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

public class LambdaLocalContext(private val name: String = "UnknownFunction") : Context {
    private val uuid = UUID.randomUUID().toString()
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

public class LambdaMock(
    private val mock: LambdaClient = mockk(relaxed = true)
) : LambdaClient by mock, MockService {
    private val functions = HashMap<String, LambdaInvocationHandler>()
    private val errors = ArrayList<Throwable>()

    private val active = AtomicInteger(0)
    override val isProcessing: Boolean get() = active.get() > 0

    private val eventSourceMappings = HashMap<String, EventSourceMappingConfiguration>()

    override fun close() {}

    override suspend fun invoke(input: InvokeRequest): InvokeResponse {
        active.incrementAndGet()
        val name = input.functionName?.let {
            if (it.startsWith("arn")) {
                it.substringAfterLast(":")
            } else {
                it
            }
        } ?: throw IllegalArgumentException("Uknonwn function name")
        val handler = functions[name] ?: throw AwsServiceException()

        return when (input.invocationType) {
            InvocationType.Event -> {
                thread {
                    try {
                        handler.invoke(input.payload ?: ByteArray(0))
                    } catch (t: Throwable) {
                        t.printStackTrace()
                        errors.add(t)
                    }
                    active.decrementAndGet()
                }
                InvokeResponse { statusCode = 200 }
            }

            else -> {
                try {
                    val responsePayload = handler.invoke(input.payload ?: ByteArray(0))
                    InvokeResponse {
                        payload = responsePayload
                        statusCode = 200
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                    InvokeResponse {
                        functionError = e.message
                        statusCode = 200
                    }
                }.also {
                    active.decrementAndGet()
                }
            }
        }
    }

    public fun createFunction(name: String, handler: (InputStream) -> Unit) {
        functions[name] = DirectInvocationHandler(handler)
    }

    override suspend fun createFunction(input: CreateFunctionRequest): CreateFunctionResponse {
        val handler = input.handler!!
        functions.put(
            input.functionName!!, ReflectionHandler(
                input.functionName!!,
                handler.substringBefore("::"), handler.substringAfter("::")
            )
        )
        return CreateFunctionResponse {
            functionName = input.functionName
            functionArn = "arn:aws:lambda:us-west-2:141660060409:function:${input.functionName}"
        }
    }

    override suspend fun createEventSourceMapping(input: CreateEventSourceMappingRequest): CreateEventSourceMappingResponse {
        val uuid = UUID.randomUUID().toString()
        eventSourceMappings[uuid] = getEventSourceConfiguration(uuid, input)
        return CreateEventSourceMappingResponse { this.uuid = uuid }
    }
//
//    suspend fun listEventSourceMappings(): ListEventSourceMappingsResponse {
//        return ListEventSourceMappingsResponse {
//            this.eventSourceMappings = this@LocalLambdaClient.eventSourceMappings.values.toList()
//        }
//    }

    override suspend fun listEventSourceMappings(input: ListEventSourceMappingsRequest): ListEventSourceMappingsResponse {
        val mappings = ArrayList<EventSourceMappingConfiguration>()
        val sourceArn = input.eventSourceArn
        if (sourceArn != null) {
            mappings.addAll(eventSourceMappings.values.filter {
                it.eventSourceArn == sourceArn
            })
        }
        return ListEventSourceMappingsResponse {
            this.eventSourceMappings = (mappings)
        }
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

    override suspend fun getEventSourceMapping(input: GetEventSourceMappingRequest): GetEventSourceMappingResponse {
        val config = eventSourceMappings[input.uuid]
            ?: throw AwsServiceException("Unknown source mapping ${input.uuid}", null)
        return GetEventSourceMappingResponse {
            eventSourceArn = (config.eventSourceArn)
            functionArn = config.functionArn
            uuid = input.uuid
        }
    }

    private fun getEventSourceConfiguration(
        uuid: String,
        mapping: CreateEventSourceMappingRequest
    ): EventSourceMappingConfiguration {
        return EventSourceMappingConfiguration {
            eventSourceArn = mapping.eventSourceArn
            functionArn = getFunctionArn(mapping.functionName!!)
            this.uuid = uuid
        }
    }

    override suspend fun listFunctions(input: ListFunctionsRequest): ListFunctionsResponse {
        return ListFunctionsResponse {
            this.functions =
                this@LambdaMock.functions.values.mapNotNull {
                    it as? ReflectionHandler
                }.map { function ->
                    FunctionConfiguration {
                        functionName = function.name
                        functionArn = getFunctionArn(function.name)
                    }
                }
        }
    }
}

private val json = jacksonObjectMapper().apply {
    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}

public interface LambdaInvocationHandler {
    public fun invoke(payload: ByteArray): ByteArray
}

public class ReflectionHandler(
    public val name: String,
    public val clazz: String,
    public val methodName: String
) : LambdaInvocationHandler {
    override fun invoke(payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val parameterValues = method.parameters.map {
            val clz = it.type
            when {
                clz == InputStream::class.java -> {
                    payload.inputStream()
                }

                clz == String::class.java -> {
                    payload.decodeToString()
                }

                clz == Context::class.java -> LambdaLocalContext(name)
                clz == OutputStream::class.java -> output
                else -> json.readValue(payload.inputStream().reader(), clz)
            }
        }
        method.invoke(instance, *(parameterValues.toTypedArray()))

        return output.toByteArray()
    }

    private val kclass: Class<*> get() = Class.forName(clazz)
    private val method
        get() = kclass.methods.find {
            it.name == methodName
        } ?: throw AwsServiceException("$name $clazz::$methodName", null)
    public val instance: Any?
        get() = if (Modifier.isStatic(method.modifiers)) null else kclass.getConstructor().newInstance()
}

public class DirectInvocationHandler(
    public val function: (InputStream) -> Unit
) : LambdaInvocationHandler {
    override fun invoke(payload: ByteArray): ByteArray {
        function(payload.inputStream())
        return ByteArray(0)
    }
}

