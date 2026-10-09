package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2Http
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2RequestContext
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Checks the adapter against Ktor's Netty engine, which is what Vegasful's web application runs on
 * today: for the same request target, `call.request.uri` and `call.request.path()` must agree.
 */
class HttpApiFrontEndJvmTest {
    private val targets = listOf(
        "/a%20b",
        "/a%2Fb/c",
        "/caf%C3%A9/%E6%97%A5%E6%9C%AC",
        "/events/",
        "/events/?q=caf%C3%A9&q=2"
    )

    @Test
    fun uriAndPathMatchNetty() {
        val netty = mutableMapOf<String, Pair<String, String>>()
        val capture = createApplicationPlugin("Capture") {
            onCall { call -> netty[call.request.uri] = call.request.uri to call.request.path() }
        }
        val engine = embeddedServer(Netty, port = 0) {
            install(capture)
            routing { get("{...}") { call.respondText("ok") } }
        }.start(wait = false)
        try {
            val port = runBlocking { engine.engine.resolvedConnectors().first().port }
            for (target in targets) {
                Socket("127.0.0.1", port).use { socket ->
                    socket.getOutputStream().write(
                        "GET $target HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray()
                    )
                    socket.getInputStream().readBytes()
                }
            }
        } finally {
            engine.stop(0, 0)
        }

        for (target in targets) {
            val rawPath = target.substringBefore('?')
            val query = target.substringAfter('?', "").ifEmpty { null }
            var uri: String? = null
            var path: String? = null
            val request = ApiGatewayV2HttpRequest(
                rawPath = rawPath,
                rawQueryString = query,
                requestContext = ApiGatewayV2RequestContext(http = ApiGatewayV2Http(method = "GET", path = rawPath))
            )
            runBlocking {
                APIGatewayV2KtorServer {
                    install(createApplicationPlugin("Capture") {
                        onCall { call -> uri = call.request.uri; path = call.request.path() }
                    })
                    routing { get("{...}") { call.respondText("ok") } }
                }.processRequest(request)
            }
            val expected = netty.getValue(target)
            assertEquals(expected, uri to path, target)
            assertEquals(target, uri, target)
        }
    }
}
