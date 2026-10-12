package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyRequest
import com.steamstreet.aws.lambda.apigateway.ProxyRequestContext
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the [ApiGatewayJWT] plugin, run inside [APIGatewayKtorServer] the way a Lambda runs it.
 */
class ApiGatewayJWTTest {
    private fun request(subject: String) = ApiGatewayProxyRequest(
        resource = "/me",
        path = "/me",
        httpMethod = "GET",
        requestContext = ProxyRequestContext(
            authorizer = mapOf("claims" to buildJsonObject { put("sub", subject) })
        )
    )

    /**
     * Sends a request whose `sub` claim is "real" and returns the subject the route saw, or
     * "none" if the call had no principal.
     */
    private suspend fun subjectSeen(configure: ApiGatewayJWTConfig.() -> Unit = {}): String? {
        val server = APIGatewayKtorServer {
            install(ApiGatewayJWT, configure)
            routing {
                authenticate("api-gateway-jwt") {
                    get("/me") {
                        val principal = call.principal<JWTPrincipal>()
                        call.respondText(principal?.payload?.getClaim("sub")?.asString() ?: "none")
                    }
                }
            }
        }

        val response = server.processRequest(request("real"))
        assertEquals(200, response.statusCode)
        return response.body
    }

    @Test
    fun thePrincipalComesFromTheRequest() = runTest {
        assertEquals("real", subjectSeen())
    }

    @Test
    fun theMockPrincipalReplacesTheRequestOne() = runTest {
        assertEquals("mock", subjectSeen { mockPrinciple = { JWTPrincipal(request("mock")) } })
    }

    @Test
    fun aNullMockPrincipalLeavesTheCallWithoutOne() = runTest {
        assertEquals("none", subjectSeen { mockPrinciple = { null } })
    }
}
