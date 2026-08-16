package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyResponse
import io.ktor.events.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.util.*

/**
 * Starts a Ktor [Application] with no network engine behind it.
 *
 * This is the mechanism the whole module rests on. `embeddedServer` insists on an
 * [ApplicationEngineFactory], so it is given one that binds no socket, resolves no connectors and
 * does nothing on `start`/`stop` — all it does is capture the [Application] that Ktor built and
 * install the default send/receive transformations that a real engine would normally install. A
 * request is then executed directly against that application object.
 *
 * There is no server, no port and no selector loop, which is what makes the whole thing work inside
 * a Lambda invocation — and, because none of it is JVM-specific, on Kotlin/Native too.
 *
 * Both payload formats share this: [APIGatewayKtorServer] and [APIGatewayV2KtorServer] differ only
 * in which call object they build and which response type they return.
 */
internal class KtorApplicationHost(module: Application.() -> Unit) {
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

    private lateinit var appProvider: () -> Application

    // The config type is APIGatewayKtorServer.InternalEngineConfig only because that type is
    // already published: the factory needs *some* Configuration subclass, and adding a second one
    // would grow the public surface to no purpose.
    private val factory =
        object : ApplicationEngineFactory<ApplicationEngine, APIGatewayKtorServer.InternalEngineConfig> {
            private val config = APIGatewayKtorServer.InternalEngineConfig()
            override fun configuration(
                configure: APIGatewayKtorServer.InternalEngineConfig.() -> Unit
            ): APIGatewayKtorServer.InternalEngineConfig {
                config.configure()
                return config
            }

            override fun create(
                environment: ApplicationEnvironment,
                monitor: Events,
                developmentMode: Boolean,
                configuration: APIGatewayKtorServer.InternalEngineConfig,
                applicationProvider: () -> Application
            ): ApplicationEngine {
                appEnvironment = environment
                appProvider = applicationProvider

                val created = applicationProvider()
                created.sendPipeline.installDefaultTransformations()
                created.receivePipeline.installDefaultTransformations()

                app = created
                appProvider = {
                    app
                }
                return internalEngine
            }
        }

    init {
        embeddedServer(factory) {
            module()
        }.start()
    }

    val application: Application get() = appProvider()
}

/**
 * A server that can process ApiGateway proxy requests.
 *
 * This is the **payload format 1.0** server — REST APIs, and HTTP APIs explicitly configured for
 * 1.0. For an HTTP API on the default 2.0 format use [APIGatewayV2KtorServer]; the two are separate
 * because the payloads are separate, and an API is configured for exactly one of them.
 */
public class APIGatewayKtorServer(module: Application.() -> Unit) {
    private val host = KtorApplicationHost(module)

    public class InternalEngineConfig : ApplicationEngine.Configuration()

    public suspend fun processRequest(apiGatewayProxyRequest: ApiGatewayProxyRequest): ApiGatewayProxyResponse {
        val application = host.application
        val call = ApiGatewayKtorCall(application, apiGatewayProxyRequest)
        try {
            application.execute(call, Unit)
        } catch (e: Exception) {
            // If an exception is thrown, set the status code to 500
            call.response.status(HttpStatusCode.InternalServerError)
        }
        return call.response()
    }
}

/**
 * The attribute for the ApiGateway request.
 */
public val ApiGatewayRequest: AttributeKey<ApiGatewayProxyRequest> = AttributeKey("ApiGatewayRequest")
public val ApplicationCall.apiGatewayRequest: ApiGatewayProxyRequest get() = attributes[ApiGatewayRequest]
