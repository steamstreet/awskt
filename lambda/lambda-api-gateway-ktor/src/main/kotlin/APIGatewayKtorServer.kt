package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyResponse
import io.ktor.events.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.util.*

/**
 * A server that can process ApiGateway proxy requests.
 */
public class APIGatewayKtorServer(module: Application.() -> Unit) {
    private lateinit var app: Application
    private lateinit var appEnvironment: ApplicationEnvironment
    private val internalEngine = object : ApplicationEngine {
        override val environment: ApplicationEnvironment
            get() = appEnvironment

        override suspend fun resolvedConnectors(): List<EngineConnectorConfig> {
            return emptyList()
        }

        override fun start(wait: Boolean): ApplicationEngine {
            return this
        }

        override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {
        }
    }

    public class InternalEngineConfig : ApplicationEngine.Configuration()

    private lateinit var appProvider: () -> Application
    private val factory = object : ApplicationEngineFactory<ApplicationEngine, InternalEngineConfig> {
        private val config = InternalEngineConfig()
        override fun configuration(configure: InternalEngineConfig.() -> Unit): InternalEngineConfig {
            config.configure()
            return config
        }

        override fun create(
            environment: ApplicationEnvironment,
            monitor: Events,
            developmentMode: Boolean,
            configuration: InternalEngineConfig,
            applicationProvider: () -> Application
        ): ApplicationEngine {
            appEnvironment = environment
            appProvider = applicationProvider
            with(applicationProvider()) {
                sendPipeline.installDefaultTransformations()
            }
            return internalEngine
        }
    }

    init {
        embeddedServer(factory) {
            module()
        }.start()
    }

    public suspend fun processRequest(apiGatewayProxyRequest: ApiGatewayProxyRequest): ApiGatewayProxyResponse {
        val application = appProvider()
        val call = ApiGatewayKtorCall(application, apiGatewayProxyRequest)
        try {
            application.execute(call, Unit)
        } catch (e: Exception) {
            // If an exception is thrown, set the status code to 500
            call.response.status(io.ktor.http.HttpStatusCode.InternalServerError)
        }
        return call.response()
    }
}

/**
 * The attribute for the ApiGateway request.
 */
public val ApiGatewayRequest: AttributeKey<ApiGatewayProxyRequest> = AttributeKey("ApiGatewayRequest")
public val ApplicationCall.apiGatewayRequest: ApiGatewayProxyRequest get() = attributes[ApiGatewayRequest]
