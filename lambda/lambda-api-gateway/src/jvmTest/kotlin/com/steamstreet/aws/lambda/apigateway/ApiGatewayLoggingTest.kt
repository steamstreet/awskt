package com.steamstreet.aws.lambda.apigateway

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.steamstreet.aws.lambda.MockLambdaContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.logstash.logback.encoder.LogstashEncoder
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The request and response lines the JVM API Gateway handlers write, rendered through the same
 * Logstash encoder `lambda-logging` uses, so the assertion is on the JSON that reaches CloudWatch.
 */
class ApiGatewayLoggingTest {
    private fun captureLogs(block: () -> Unit): List<JsonObject> {
        val logger = LoggerFactory.getLogger("Lambda") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { context = logger.loggerContext; start() }
        val encoder = LogstashEncoder().apply { context = logger.loggerContext; start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.map { Json.parseToJsonElement(encoder.encode(it).decodeToString()).jsonObject }
    }

    private val request = """
        {
          "resource": "/orders",
          "path": "/orders",
          "httpMethod": "GET",
          "headers": {"Authorization": "Bearer secret-token", "Accept": "*/*"},
          "multiValueHeaders": {"Authorization": ["Bearer secret-token"]},
          "requestContext": {"identity": {"apiKey": "secret-key"}}
        }
    """.trimIndent()

    @Test
    fun v1RequestsAndResponsesAreLoggedWithoutCredentials() {
        val handler = object : ApiGatewayProxyHandler() {
            override suspend fun handle(input: ApiGatewayProxyRequest): ApiGatewayProxyResponse {
                assertEquals("Bearer secret-token", input.headers?.get("Authorization"))
                return ApiGatewayProxyResponse(200, headers = mapOf("Set-Cookie" to "session=secret-cookie"))
            }
        }
        val output = ByteArrayOutputStream()

        val lines = captureLogs { handler.execute(request.byteInputStream(), output, MockLambdaContext()) }

        val input = lines.single { it["message"]?.jsonPrimitive?.content == "Request received" }["input"]!!.jsonObject
        assertEquals("[REDACTED]", input["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content)
        assertEquals("*/*", input["headers"]!!.jsonObject["Accept"]!!.jsonPrimitive.content)
        val event = lines.single { it["message"]?.jsonPrimitive?.content == "Lambda response sent" }["event"]!!.jsonObject
        assertEquals("[REDACTED]", event["headers"]!!.jsonObject["Set-Cookie"]!!.jsonPrimitive.content)

        val logged = lines.joinToString("\n")
        assertFalse("secret" in logged, logged)
        // The response API Gateway receives is untouched.
        assertEquals(true, "session=secret-cookie" in output.toString())
    }

    @Test
    fun v2RequestsAreLoggedWithoutCredentials() {
        val handler = object : ApiGatewayV2HttpHandler() {
            override suspend fun handle(input: ApiGatewayV2HttpRequest): ApiGatewayV2HttpResponse =
                ApiGatewayV2HttpResponse(200, cookies = listOf("session=secret-cookie"))
        }
        val v2 = """{"rawPath": "/orders", "cookies": ["session=secret-cookie"], "headers": {"authorization": "Bearer secret-token"}}"""

        val lines = captureLogs { handler.execute(v2.byteInputStream(), ByteArrayOutputStream(), MockLambdaContext()) }

        val logged = lines.joinToString("\n")
        assertFalse("secret" in logged, logged)
        assertEquals(2, lines.size)
    }

    @Test
    fun aNullRedactorLogsTheRequestAsItArrived() {
        val handler = object : ApiGatewayProxyHandler() {
            override val logRedactor = null
            override suspend fun handle(input: ApiGatewayProxyRequest) = ApiGatewayProxyResponse(200)
        }

        val lines = captureLogs { handler.execute(request.byteInputStream(), ByteArrayOutputStream(), MockLambdaContext()) }

        assertEquals(true, "secret-token" in lines.joinToString("\n"))
    }
}
