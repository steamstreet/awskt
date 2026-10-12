package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyRequest
import com.steamstreet.aws.lambda.apigateway.ProxyRequestContext
import com.steamstreet.aws.lambda.apigateway.RequestIdentity
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Checks that a consumer moving from `ApiGatewayJWT` to [ApiGatewayAuth] reads the same values.
 *
 * `JWTPrincipal(request)` is in `:lambda:lambda-api-gateway-ktor-jwt`, which is JVM-only, so this
 * comparison can only run on the JVM.
 */
class ApiGatewayAuthParityJvmTest {
    private val claims = buildJsonObject {
        put("sub", "user-123")
        put("iss", "https://cognito-idp.us-west-2.amazonaws.com/us-west-2_example")
        put("email", "someone@example.com")
        put("cognito:username", "someone")
        put("auth_time", 1700000000)
        put("email_verified", true)
        put("nothing", JsonNull)
    }

    private val names = claims.keys + "missing"

    private fun request(authorizer: Map<String, JsonElement>?) = ApiGatewayProxyRequest(
        resource = "/me",
        path = "/me",
        httpMethod = "GET",
        requestContext = ProxyRequestContext(
            identity = RequestIdentity(sourceIp = "203.0.113.7"),
            authorizer = authorizer
        )
    )

    @Test
    fun claimsMatchTheJwtPrincipal() {
        val request = request(mapOf("claims" to claims))
        val jwt = JWTPrincipal(request)
        val principal = ApiGatewayPrincipal(request)

        for (name in names) {
            assertEquals(jwt.payload.getClaim(name).asString(), principal.claim(name), name)
        }
        assertEquals(jwt.subject, principal.subject)
        assertEquals(jwt.payload.getClaim("sourceIp").asString(), principal.sourceIp)
    }

    @Test
    fun claimsMatchTheJwtPrincipalWhenThereAreNone() {
        val request = request(authorizer = null)
        val jwt = JWTPrincipal(request)
        val principal = ApiGatewayPrincipal(request)

        for (name in names) {
            assertEquals(jwt.payload.getClaim(name).asString(), principal.claim(name), name)
        }
        assertEquals(jwt.payload.getClaim("sourceIp").asString(), principal.sourceIp)
    }

    /**
     * The one place the two differ: with no `sub` claim, `JWTPrincipal.subject` throws, because the
     * payload declares it non-null, and [ApiGatewayPrincipal.subject] is null.
     */
    @Test
    fun aMissingSubjectIsNullRatherThanAnException() {
        val request = request(authorizer = null)

        assertFailsWith<NullPointerException> { JWTPrincipal(request).subject }
        assertNull(ApiGatewayPrincipal(request).subject)
    }

    /**
     * The same comparison through the two plugins, each in its own application, under the provider
     * name they share.
     */
    @Test
    fun theTwoPluginsServeTheSameClaims() = runTest {
        suspend fun served(configure: Application.() -> Unit, read: RoutingCall.(String) -> String?): String? {
            val server = APIGatewayKtorServer {
                configure()
                routing {
                    authenticate("api-gateway-jwt") {
                        get("/me") {
                            call.respondText((names + "sourceIp").joinToString("\n") { "$it=${call.read(it)}" })
                        }
                    }
                }
            }
            val response = server.processRequest(request(mapOf("claims" to claims)))
            assertEquals(200, response.statusCode)
            return response.body
        }

        val fromJwt = served({ install(ApiGatewayJWT) }) { name ->
            principal<JWTPrincipal>()?.payload?.getClaim(name)?.asString()
        }
        val fromAuth = served({ install(ApiGatewayAuth) }) { name ->
            val principal = principal<ApiGatewayPrincipal>()
            if (name == "sourceIp") principal?.sourceIp else principal?.claim(name)
        }

        assertEquals(assertNotNull(fromJwt), fromAuth)
    }
}
