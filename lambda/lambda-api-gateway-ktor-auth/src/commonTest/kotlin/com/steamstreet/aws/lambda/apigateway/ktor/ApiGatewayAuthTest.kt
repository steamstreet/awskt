package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2Authorizer
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2Http
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2JwtAuthorizer
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2RequestContext
import com.steamstreet.aws.lambda.apigateway.ProxyRequestContext
import com.steamstreet.aws.lambda.apigateway.RequestIdentity
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Tests for [ApiGatewayAuth] and [ApiGatewayPrincipal].
 *
 * They run the plugin inside [APIGatewayKtorServer] and [APIGatewayV2KtorServer], the way a Lambda
 * does, and they are in `commonTest` so that the native targets run them too.
 */
class ApiGatewayAuthTest {
    private val claims = buildJsonObject {
        put("sub", "user-123")
        put("email", "someone@example.com")
        put("auth_time", 1700000000)
        put("email_verified", true)
        put("nothing", JsonNull)
        put("address", buildJsonObject { put("country", "US") })
        put("groups", buildJsonArray { add(JsonPrimitive("admins")) })
    }

    private fun request(
        authorizer: Map<String, JsonElement>? = mapOf("claims" to claims),
        sourceIp: String? = "203.0.113.7"
    ) = ApiGatewayProxyRequest(
        resource = "/me",
        path = "/me",
        httpMethod = "GET",
        requestContext = ProxyRequestContext(
            identity = sourceIp?.let { RequestIdentity(sourceIp = it) },
            authorizer = authorizer
        )
    )

    /**
     * Runs a GET /me request against an application whose /me route is inside
     * `authenticate(provider)`, and returns the principal the route saw.
     */
    private suspend fun principalSeenBy(
        request: ApiGatewayProxyRequest,
        provider: String? = ApiGatewayAuthProviderName,
        configure: Application.() -> Unit = { install(ApiGatewayAuth) }
    ): ApiGatewayPrincipal? {
        var reached = false
        var seen: ApiGatewayPrincipal? = null
        val server = APIGatewayKtorServer {
            configure()
            routing {
                authenticate(provider) {
                    get("/me") {
                        reached = true
                        seen = call.principal<ApiGatewayPrincipal>()
                        call.respondText("ok")
                    }
                }
            }
        }

        val response = server.processRequest(request)
        assertEquals(200, response.statusCode)
        assertEquals("ok", response.body)
        assertEquals(true, reached)
        return seen
    }

    @Test
    fun aRouteIsReachableWithClaims() = runTest {
        val principal = assertNotNull(principalSeenBy(request()))

        assertEquals("user-123", principal.subject)
        assertEquals(claims, principal.claims)
    }

    @Test
    fun aRouteIsReachableWithoutClaims() = runTest {
        // No authorizer at all, and an authorizer (a Lambda one, say) that supplies no claims.
        for (authorizer in listOf(null, mapOf<String, JsonElement>("principalId" to JsonPrimitive("abc")))) {
            val principal = assertNotNull(principalSeenBy(request(authorizer = authorizer)))

            assertNull(principal.claims)
            assertNull(principal.subject)
            assertNull(principal.claim("email"))
            assertEquals("203.0.113.7", principal.sourceIp)
        }
    }

    @Test
    fun claimsThatAreNotAnObjectAreTreatedAsAbsent() = runTest {
        val principal = assertNotNull(
            principalSeenBy(request(authorizer = mapOf("claims" to JsonPrimitive("not an object"))))
        )

        assertNull(principal.claims)
    }

    @Test
    fun thePrincipalExposesClaimsAsStrings() = runTest {
        val principal = assertNotNull(principalSeenBy(request()))

        assertEquals("user-123", principal.subject)
        assertEquals("someone@example.com", principal.claim("email"))
        assertEquals("1700000000", principal.claim("auth_time"))
        assertEquals("true", principal.claim("email_verified"))
        assertNull(principal.claim("missing"))
        assertNull(principal.claim("nothing"))
    }

    @Test
    fun aStructuredClaimIsNullAsAStringAndReadableFromTheClaims() = runTest {
        val principal = assertNotNull(principalSeenBy(request()))

        assertNull(principal.claim("address"))
        assertNull(principal.claim("groups"))
        assertEquals(claims["address"], principal.claims?.get("address"))
        assertEquals(claims["groups"], principal.claims?.get("groups"))
    }

    @Test
    fun thePrincipalExposesTheSourceIp() = runTest {
        assertEquals("203.0.113.7", principalSeenBy(request())?.sourceIp)
        assertNull(assertNotNull(principalSeenBy(request(sourceIp = null))).sourceIp)
    }

    @Test
    fun sourceIpIsNotAClaim() = runTest {
        // `JWTPrincipal(request)` answers `getClaim("sourceIp")` from the request identity. Here it
        // is a property, and `claim` reads only what the authorizer supplied.
        assertNull(assertNotNull(principalSeenBy(request())).claim("sourceIp"))
    }

    @Test
    fun theProviderNameCanBeChanged() = runTest {
        val principal = principalSeenBy(request(), provider = "cognito") {
            install(ApiGatewayAuth) { name = "cognito" }
        }

        assertEquals("user-123", principal?.subject)
    }

    @Test
    fun theProviderCanBeTheDefaultOne() = runTest {
        val principal = principalSeenBy(request(), provider = null) {
            install(ApiGatewayAuth) { name = null }
        }

        assertEquals("user-123", principal?.subject)
    }

    @Test
    fun thePrincipalIsRegisteredUnderTheProviderName() = runTest {
        var byName: ApiGatewayPrincipal? = null
        var byAnotherName: ApiGatewayPrincipal? = null
        val server = APIGatewayKtorServer {
            install(ApiGatewayAuth)
            routing {
                authenticate(ApiGatewayAuthProviderName) {
                    get("/me") {
                        byName = call.principal<ApiGatewayPrincipal>(ApiGatewayAuthProviderName)
                        byAnotherName = call.principal<ApiGatewayPrincipal>("another")
                        call.respondText("ok")
                    }
                }
            }
        }

        assertEquals(200, server.processRequest(request()).statusCode)
        assertEquals("user-123", byName?.subject)
        assertNull(byAnotherName)
    }

    @Test
    fun theMockPrincipalReplacesTheRequestOne() = runTest {
        val mock = ApiGatewayPrincipal(buildJsonObject { put("sub", "mock-user") }, sourceIp = "198.51.100.1")

        val principal = principalSeenBy(request()) {
            install(ApiGatewayAuth) { mockPrincipal = { mock } }
        }

        assertSame(mock, principal)
        assertEquals("mock-user", principal?.subject)
        assertEquals("198.51.100.1", principal?.sourceIp)
    }

    @Test
    fun theMockPrincipalIsAskedForEachRequest() = runTest {
        var user = "first"
        val server = APIGatewayKtorServer {
            install(ApiGatewayAuth) {
                mockPrincipal = { ApiGatewayPrincipal(buildJsonObject { put("sub", user) }) }
            }
            routing {
                authenticate(ApiGatewayAuthProviderName) {
                    get("/me") { call.respondText(call.principal<ApiGatewayPrincipal>()?.subject.orEmpty()) }
                }
            }
        }

        assertEquals("first", server.processRequest(request()).body)
        user = "second"
        assertEquals("second", server.processRequest(request()).body)
    }

    @Test
    fun aNullMockPrincipalFallsBackToTheRequest() = runTest {
        val principal = principalSeenBy(request()) {
            install(ApiGatewayAuth) { mockPrincipal = { null } }
        }

        assertEquals("user-123", principal?.subject)
    }

    @Test
    fun itAddsItsProviderToAnAuthenticationPluginAlreadyInstalled() = runTest {
        val principal = principalSeenBy(request()) {
            install(Authentication) {
                provider("other") {
                    authenticate { }
                }
            }
            install(ApiGatewayAuth)
        }

        assertEquals("user-123", principal?.subject)
    }

    @Test
    fun itReadsTheClaimsOfAnHttpApiJwtAuthorizer() = runTest {
        var seen: ApiGatewayPrincipal? = null
        val server = APIGatewayV2KtorServer {
            install(ApiGatewayAuth)
            routing {
                authenticate(ApiGatewayAuthProviderName) {
                    get("/me") {
                        seen = call.principal<ApiGatewayPrincipal>()
                        call.respondText("ok")
                    }
                }
            }
        }

        fun v2Request(authorizer: ApiGatewayV2Authorizer?) = ApiGatewayV2HttpRequest(
            routeKey = "GET /me",
            rawPath = "/me",
            requestContext = ApiGatewayV2RequestContext(
                http = ApiGatewayV2Http(method = "GET", path = "/me", sourceIp = "203.0.113.7"),
                authorizer = authorizer
            )
        )

        val withClaims = v2Request(ApiGatewayV2Authorizer(jwt = ApiGatewayV2JwtAuthorizer(claims = claims)))
        assertEquals(200, server.processRequest(withClaims).statusCode)
        assertEquals("user-123", seen?.subject)
        assertEquals("1700000000", seen?.claim("auth_time"))
        assertEquals(JsonObject(claims), seen?.claims)
        assertEquals("203.0.113.7", seen?.sourceIp)

        seen = null
        assertEquals(200, server.processRequest(v2Request(authorizer = null)).statusCode)
        assertNull(assertNotNull(seen).claims)
        assertEquals("203.0.113.7", seen?.sourceIp)
    }
}
