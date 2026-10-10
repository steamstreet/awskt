package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2Http
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2RequestContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs the engine parity cases on Netty and on the adapter, and checks they answer alike: the
 * status, the body, and the status `call.response.status()` reports before a response is set.
 */
class EngineParityJvmTest {
    private data class Answer(val status: Int, val body: String)

    private val cases = listOf(
        "GET" to "/",
        "GET" to "/missing",
        "GET" to "/gone",
        "HEAD" to "/",
        "POST" to "/",
        "DELETE" to "/missing"
    )

    private fun compare(
        label: String,
        application: Application.(statusBeforeResponse: (String, HttpStatusCode?) -> Unit) -> Unit
    ) {
        val nettyStatuses = mutableMapOf<String, HttpStatusCode?>()
        val adapterStatuses = mutableMapOf<String, HttpStatusCode?>()

        val netty = mutableMapOf<Pair<String, String>, Answer>()
        val engine = embeddedServer(Netty, port = 0) {
            application { path, status -> nettyStatuses[path] = status }
        }.start(wait = false)
        try {
            val port = runBlocking { engine.engine.resolvedConnectors().first().port }
            for ((method, path) in cases) {
                Socket("127.0.0.1", port).use { socket ->
                    socket.getOutputStream().write(
                        "$method $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray()
                    )
                    val raw = socket.getInputStream().readBytes().decodeToString()
                    val status = raw.substringBefore("\r\n").split(" ")[1].toInt()
                    val body = raw.substringAfter("\r\n\r\n", "")
                    // Netty may frame a body as chunks; the cases' bodies are short single chunks.
                    netty[method to path] = Answer(status, dechunk(body))
                }
            }
        } finally {
            engine.stop(0, 0)
        }

        val server = APIGatewayV2KtorServer { application { path, status -> adapterStatuses[path] = status } }
        for ((method, path) in cases) {
            val response = runBlocking {
                server.processRequest(
                    ApiGatewayV2HttpRequest(
                        rawPath = path,
                        requestContext = ApiGatewayV2RequestContext(
                            http = ApiGatewayV2Http(method = method, path = path)
                        )
                    )
                )
            }
            assertEquals(
                netty.getValue(method to path),
                Answer(response.statusCode ?: 0, if (method == "HEAD") "" else response.body ?: ""),
                "$label $method $path"
            )
        }
        assertEquals(nettyStatuses, adapterStatuses, "$label status before a response")
    }

    // Netty sends no body for HEAD, which an HTTP API does too, so a HEAD answer is compared on its
    // status alone.
    private fun dechunk(body: String): String {
        if (body.isEmpty() || !body.first().isLetterOrDigit()) return body
        val size = body.substringBefore("\r\n").toIntOrNull(16) ?: return body
        return if (size == 0) "" else body.substringAfter("\r\n").take(size)
    }

    @Test
    fun withStatusPages() = compare("StatusPages") { record -> engineParityApplication(record) }

    @Test
    fun withoutStatusPages() = compare("plain") { _ -> engineParityApplicationWithoutStatusPages() }
}
