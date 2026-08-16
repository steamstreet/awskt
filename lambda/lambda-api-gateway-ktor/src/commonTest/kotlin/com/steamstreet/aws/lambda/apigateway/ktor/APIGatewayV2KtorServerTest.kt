package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2Http
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpResponse
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2RequestContext
import com.steamstreet.exceptions.NotFoundException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the payload format 2.0 (HTTP API) mapping.
 *
 * These live in `commonTest` for the same reason the v1 ones do — they drive
 * [APIGatewayV2KtorServer.processRequest] directly, so they run on native as well as the JVM.
 */
class APIGatewayV2KtorServerTest {
    private fun request(
        path: String = "/my/path",
        method: String = "POST",
        rawQueryString: String? = null,
        body: String? = null,
        headers: Map<String, String>? = null,
        cookies: List<String>? = null,
        isBase64Encoded: Boolean? = null
    ) = ApiGatewayV2HttpRequest(
        rawPath = path,
        rawQueryString = rawQueryString,
        cookies = cookies,
        headers = headers,
        body = body,
        isBase64Encoded = isBase64Encoded,
        requestContext = ApiGatewayV2RequestContext(
            http = ApiGatewayV2Http(method = method, path = path, sourceIp = "203.0.113.7")
        )
    )

    private suspend fun testRoute(
        request: ApiGatewayV2HttpRequest,
        handler: suspend RoutingContext.() -> Unit
    ): ApiGatewayV2HttpResponse {
        val server = APIGatewayV2KtorServer {
            install(StatusPages) {
                exception<Throwable> { call, _ ->
                    call.respond(HttpStatusCode.InternalServerError)
                }
                unhandled {
                    it.respond(HttpStatusCode.InternalServerError)
                }
            }
            routing {
                route("my") {
                    route("path") {
                        post {
                            handler()
                        }
                    }
                }
            }
        }
        return server.processRequest(request)
    }

    /**
     * The method comes from `requestContext.http.method` and the path from `rawPath` — neither is
     * where v1 keeps them, so this failing means the basic field mapping is wrong.
     */
    @Test
    fun basics() = runTest {
        val response = testRoute(request(body = "Hello from Lambda!")) {
            val receivedBody = call.receiveText()
            assertEquals("/my/path", call.apiGatewayV2Request.rawPath)
            call.respondText(receivedBody.replace("Hello", "Goodbye"))
        }
        assertEquals(200, response.statusCode)
        assertEquals("Goodbye from Lambda!", assertNotNull(response.body))
    }

    @Test
    fun receivedHeaders() = runTest {
        val response = testRoute(request(headers = mapOf("X-Some-Header" to "Hello!"))) {
            assertEquals("Hello!", call.request.header("X-Some-Header"))
            call.respond(HttpStatusCode.TooEarly)
        }
        assertEquals(HttpStatusCode.TooEarly.value, response.statusCode)
    }

    /**
     * v2 hands over the query verbatim in `rawQueryString`, so it must be parsed from there and
     * appended to the URI unchanged.
     */
    @Test
    fun queryStringComesFromRawQueryString() = runTest {
        var observedUri: String? = null
        var a: String? = null
        val response = testRoute(request(rawQueryString = "a=1&useAltUrl=true")) {
            observedUri = call.request.uri
            a = call.request.queryParameters["a"]
            call.respond(HttpStatusCode.OK)
        }
        assertEquals(200, response.statusCode)
        assertEquals("/my/path?a=1&useAltUrl=true", observedUri)
        assertEquals("1", a)
    }

    /**
     * A repeated query parameter must stay two values. This is the reason the mapping parses
     * `rawQueryString` rather than `queryStringParameters`: v2 comma-joins repeats into that map,
     * at which point `?a=1&a=2` is indistinguishable from a literal `a=1,2`.
     */
    @Test
    fun repeatedQueryParametersSurvive() = runTest {
        var values: List<String>? = null
        testRoute(request(rawQueryString = "a=1&a=2")) {
            values = call.request.queryParameters.getAll("a")
            call.respond(HttpStatusCode.OK)
        }
        assertEquals(listOf("1", "2"), values)
    }

    /**
     * v2 lifts cookies out of the headers into their own array, so they have to be folded back into
     * a `Cookie` header or Ktor sees a request with no cookies.
     */
    @Test
    fun requestCookiesAreReadable() = runTest {
        var session: String? = null
        var theme: String? = null
        testRoute(request(cookies = listOf("session=abc123", "theme=dark"))) {
            session = call.request.cookies["session"]
            theme = call.request.cookies["theme"]
            call.respond(HttpStatusCode.OK)
        }
        assertEquals("abc123", session)
        assertEquals("dark", theme)
    }

    /**
     * The single most format-specific behaviour: v2 has no `multiValueHeaders`, and `Set-Cookie` is
     * the one header that must NOT be comma-joined — cookie `Expires` values contain a comma, so
     * joining two of them yields one corrupt cookie. They belong in the `cookies` array.
     */
    @Test
    fun setCookieGoesToTheCookiesArrayNotHeaders() = runTest {
        val first = "session=abc123; Path=/; Expires=Wed, 21 Oct 2026 07:28:00 GMT"
        val second = "theme=dark; Path=/"
        val response = testRoute(request()) {
            call.response.header(HttpHeaders.SetCookie, first)
            call.response.header(HttpHeaders.SetCookie, second)
            call.respond(HttpStatusCode.OK)
        }

        assertEquals(listOf(first, second), assertNotNull(response.cookies))
        assertNull(
            response.headers?.get(HttpHeaders.SetCookie),
            "Set-Cookie must not also appear in headers"
        )
    }

    /**
     * Every other repeated header is comma-joined, which is lossless for the list-valued headers in
     * the HTTP spec and is what API Gateway itself does.
     */
    @Test
    fun otherRepeatedHeadersAreCommaJoined() = runTest {
        val response = testRoute(request()) {
            call.response.header("X-Multi", "one")
            call.response.header("X-Multi", "two")
            call.respond(HttpStatusCode.OK)
        }
        assertEquals("one,two", assertNotNull(response.headers)["X-Multi"])
    }

    /**
     * A single header value that legitimately contains a comma must survive untouched.
     */
    @Test
    fun singleHeaderWithCommaIsPreserved() = runTest {
        val cacheControl = "no-store, max-age=0, private"
        val response = testRoute(request()) {
            call.response.header(HttpHeaders.CacheControl, cacheControl)
            call.respond(HttpStatusCode.OK)
        }
        assertEquals(cacheControl, assertNotNull(response.headers)[HttpHeaders.CacheControl])
    }

    /**
     * A base64 request body must be decoded before it reaches the handler.
     */
    @Test
    fun base64RequestBodyIsDecoded() = runTest {
        // "Hello from Lambda!" base64-encoded.
        val encoded = "SGVsbG8gZnJvbSBMYW1iZGEh"
        val response = testRoute(request(body = encoded, isBase64Encoded = true)) {
            call.respondText(call.receiveText())
        }
        assertEquals("Hello from Lambda!", assertNotNull(response.body))
    }

    @Test
    fun fail() = runTest {
        val response = testRoute(request(body = "x")) {
            throw IllegalStateException("Whoops")
        }
        assertEquals(500, response.statusCode)
    }

    /**
     * No route matched and nothing set a status — for API Gateway that is a 404.
     */
    @Test
    fun unmatchedRouteIs404() = runTest {
        val server = APIGatewayV2KtorServer {
            routing {
                get("/something-else") { call.respondText("nope") }
            }
        }
        val response = server.processRequest(request(path = "/missing", method = "GET"))
        assertEquals(404, response.statusCode)
    }

    @Test
    fun statusPagesMapsNotFoundExceptionTo404() = runTest {
        val server = APIGatewayV2KtorServer {
            install(StatusPages) {
                exception<NotFoundException> { call, _ ->
                    call.respond(HttpStatusCode.NotFound)
                }
            }
            routing {
                get("/missing") { throw NotFoundException("nope") }
            }
        }
        val response = server.processRequest(request(path = "/missing", method = "GET"))
        assertEquals(HttpStatusCode.NotFound.value, response.statusCode)
    }

    /**
     * A real HTTP API event, in its on-the-wire form — it carries plenty of fields the model does
     * not declare, which is what `ignoreUnknownKeys` has to survive.
     */
    @Test
    fun parsesARealEvent() = runTest {
        val parsed = lenientJson.decodeFromString<ApiGatewayV2HttpRequest>(v2Event)
        assertEquals("2.0", parsed.version)
        assertEquals("/my/path", parsed.rawPath)
        assertEquals("POST", parsed.requestContext.http.method)
        assertEquals("192.0.2.1", parsed.requestContext.http.sourceIp)
        assertEquals(listOf("cookie1=value1", "cookie2=value2"), parsed.cookies)
        assertTrue(parsed.requestContext.authorizer?.jwt?.scopes?.contains("read") == true)
    }
}

private val lenientJson = Json { ignoreUnknownKeys = true }

private const val v2Event = """
{
  "version": "2.0",
  "routeKey": "${'$'}default",
  "rawPath": "/my/path",
  "rawQueryString": "parameter1=value1&parameter1=value2&parameter2=value",
  "cookies": ["cookie1=value1", "cookie2=value2"],
  "headers": {
    "header1": "value1",
    "header2": "value1,value2",
    "content-type": "application/json"
  },
  "queryStringParameters": {
    "parameter1": "value1,value2",
    "parameter2": "value"
  },
  "requestContext": {
    "accountId": "123456789012",
    "apiId": "api-id",
    "authentication": {},
    "authorizer": {
      "jwt": {
        "claims": { "sub": "user-1" },
        "scopes": ["read", "write"]
      }
    },
    "domainName": "id.execute-api.us-east-1.amazonaws.com",
    "domainPrefix": "id",
    "http": {
      "method": "POST",
      "path": "/my/path",
      "protocol": "HTTP/1.1",
      "sourceIp": "192.0.2.1",
      "userAgent": "agent"
    },
    "requestId": "id",
    "routeKey": "${'$'}default",
    "stage": "${'$'}default",
    "time": "12/Mar/2020:19:03:58 +0000",
    "timeEpoch": 1583348638390
  },
  "body": "Hello from Lambda",
  "pathParameters": { "parameter1": "value1" },
  "isBase64Encoded": false,
  "stageVariables": { "stageVariable1": "value1" }
}
"""
