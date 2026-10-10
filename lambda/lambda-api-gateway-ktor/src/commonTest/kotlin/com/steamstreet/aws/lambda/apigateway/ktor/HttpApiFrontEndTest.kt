package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2Http
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpResponse
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2RequestContext
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a browser-facing web application sends through an HTTP API (payload format 2.0), as opposed
 * to the field-by-field mapping that [APIGatewayV2KtorServerTest] covers: request timing plugins,
 * session cookies, redirects, encoded paths, HEAD, and large form and HTML bodies.
 *
 * In `commonTest`, so it runs on `linuxArm64` as well as the JVM.
 */
class HttpApiFrontEndTest {
    private fun request(
        path: String = "/",
        method: String = "GET",
        rawQueryString: String? = null,
        body: String? = null,
        headers: Map<String, String>? = null,
        isBase64Encoded: Boolean? = null
    ) = ApiGatewayV2HttpRequest(
        rawPath = path,
        rawQueryString = rawQueryString,
        headers = headers,
        body = body,
        isBase64Encoded = isBase64Encoded,
        requestContext = ApiGatewayV2RequestContext(
            http = ApiGatewayV2Http(method = method, path = path, sourceIp = "203.0.113.7")
        )
    )

    private suspend fun run(
        request: ApiGatewayV2HttpRequest,
        module: Application.() -> Unit
    ): ApiGatewayV2HttpResponse = APIGatewayV2KtorServer(module).processRequest(request)

    /**
     * Vegasful's request-timing plugin records its latency metric from `on(ResponseSent)`. The hook
     * must run exactly once per request and see the final status.
     */
    @Test
    fun responseSentFiresOncePerRequestWithTheFinalStatus() = runTest {
        val seen = mutableListOf<Pair<String, Int>>()
        val timing = createApplicationPlugin("RequestTiming") {
            on(ResponseSent) { call ->
                seen.add(call.request.path() to (call.response.status()?.value ?: -1))
            }
        }
        val server = APIGatewayV2KtorServer {
            install(timing)
            routing {
                get("/ok") { call.respondText("fine") }
                get("/teapot") { call.respondText("short and stout", status = HttpStatusCode(418, "Teapot")) }
                get("/redirect") { call.respondRedirect("/ok") }
            }
        }

        server.processRequest(request(path = "/ok"))
        assertEquals(listOf("/ok" to 200), seen)

        server.processRequest(request(path = "/teapot"))
        server.processRequest(request(path = "/redirect"))
        // An unmatched route is answered by the engine fallback, which is a response like any other.
        server.processRequest(request(path = "/missing"))
        assertEquals(listOf("/ok" to 200, "/teapot" to 418, "/redirect" to 302, "/missing" to 404), seen)
    }

    @Test
    fun sessionCookieKeepsAllItsAttributes() = runTest {
        val response = run(request()) {
            routing {
                get("/") {
                    call.response.cookies.append(
                        Cookie(
                            name = "session",
                            value = "abc123",
                            secure = true,
                            httpOnly = true,
                            path = "/",
                            maxAge = 3600
                        )
                    )
                    call.respondText("hi")
                }
            }
        }
        val cookie = assertNotNull(response.cookies).single()
        assertTrue(cookie.startsWith("session=abc123"), cookie)
        val attributes = cookie.split(";").map { it.trim() }
        assertTrue("Secure" in attributes, cookie)
        assertTrue("HttpOnly" in attributes, cookie)
        assertTrue("Path=/" in attributes, cookie)
        assertTrue("Max-Age=3600" in attributes, cookie)
        assertNull(response.headers?.get(HttpHeaders.SetCookie))
    }

    @Test
    fun redirectThatSetsACookie() = runTest {
        val response = run(request(path = "/login")) {
            routing {
                get("/login") {
                    call.response.cookies.append(
                        Cookie("session", "xyz", secure = true, httpOnly = true, path = "/")
                    )
                    call.respondRedirect("/account")
                }
            }
        }
        assertEquals(302, response.statusCode)
        assertEquals("/account", assertNotNull(response.headers)[HttpHeaders.Location])
        val cookie = assertNotNull(response.cookies).single()
        assertTrue(cookie.startsWith("session=xyz"), cookie)
        assertTrue(cookie.contains("Secure") && cookie.contains("HttpOnly"), cookie)
    }

    /**
     * API Gateway delivers `rawPath` exactly as the client sent it: percent-encoded, with a UTF-8
     * segment encoded byte by byte and no decoding of `%2F`. Netty hands Ktor the request-line
     * target the same way, so `uri` and `path()` must be that string unchanged (the JVM test
     * `HttpApiFrontEndJvmTest` checks the Netty side of this).
     */
    @Test
    fun encodedPathsAreReportedAsReceived() = runTest {
        val paths = listOf(
            "/a%20b" to "/a%20b",
            "/a%2Fb/c" to "/a%2Fb/c",
            "/caf%C3%A9/%E6%97%A5%E6%9C%AC" to "/caf%C3%A9/%E6%97%A5%E6%9C%AC",
            "/events/" to "/events/"
        )
        for ((rawPath, expected) in paths) {
            var uri: String? = null
            var path: String? = null
            run(request(path = rawPath)) {
                install(createApplicationPlugin("Capture") {
                    onCall { call ->
                        uri = call.request.uri
                        path = call.request.path()
                    }
                })
                routing { get("{...}") { call.respondText("ok") } }
            }
            assertEquals(expected, uri, rawPath)
            assertEquals(expected, path, rawPath)
        }
    }

    @Test
    fun encodedSegmentsAreDecodedForRouting() = runTest {
        var segments: List<String>? = null
        val response = run(request(path = "/venues/caf%C3%A9%20bar/a%2Fb")) {
            routing {
                get("/venues/{name}/{other}") {
                    segments = listOf(call.parameters["name"]!!, call.parameters["other"]!!)
                    call.respondText("ok")
                }
            }
        }
        assertEquals(200, response.statusCode)
        assertEquals(listOf("café bar", "a/b"), segments)
    }

    /**
     * Pins what the adapter does with HEAD when the application has only GET routes: Ktor's routing
     * does not map HEAD onto GET without the AutoHeadResponse plugin, so the path matches under
     * another method, and the adapter answers 405 with no body, as Ktor's engines do.
     */
    @Test
    fun headWithOnlyAGetRouteIs405WithNoBody() = runTest {
        val response = run(request(method = "HEAD")) {
            routing { get("/") { call.respondText("<html>home</html>", ContentType.Text.Html) } }
        }
        assertEquals(405, response.statusCode)
        assertNull(response.body)
    }

    @Test
    fun headRouteReturnsStatusAndHeadersWithoutABody() = runTest {
        val module: Application.() -> Unit = {
            routing {
                get("/") { call.respondText("<html>home</html>", ContentType.Text.Html) }
                head("/") {
                    call.response.headers.append(HttpHeaders.ContentType, "text/html; charset=UTF-8")
                    call.response.headers.append(HttpHeaders.ContentLength, "17")
                    call.response.status(HttpStatusCode.OK)
                    call.respondText("", ContentType.Text.Html)
                }
            }
        }
        val get = run(request(method = "GET"), module)
        val head = run(request(method = "HEAD"), module)
        assertEquals(get.statusCode, head.statusCode)
        assertEquals(
            assertNotNull(get.headers)[HttpHeaders.ContentType],
            assertNotNull(head.headers)[HttpHeaders.ContentType]
        )
        assertNull(head.body)
    }

    @Test
    fun largeFormPostIsReadFromABase64Body() = runTest {
        val big = "x".repeat(1_000_000)
        val form = "big=$big&name=caf%C3%A9&emptyValue="
        val encoded = Base64.Default.encode(form.encodeToByteArray())
        var seen: Triple<Int?, String?, String?>? = null
        val response = run(
            request(
                method = "POST",
                path = "/submit",
                body = encoded,
                isBase64Encoded = true,
                headers = mapOf(HttpHeaders.ContentType to "application/x-www-form-urlencoded")
            )
        ) {
            routing {
                post("/submit") {
                    val parameters = call.receiveParameters()
                    seen = Triple(parameters["big"]?.length, parameters["name"], parameters["emptyValue"])
                    call.respondText("received")
                }
            }
        }
        assertEquals(200, response.statusCode)
        assertEquals(Triple(1_000_000, "café", ""), seen)
    }

    /**
     * A page of about 200 KB comes back byte for byte. HTML is text, so it is returned as a plain
     * UTF-8 string and `isBase64Encoded` stays false.
     */
    @Test
    fun largeHtmlResponseIsReturnedIntactAsText() = runTest {
        val html = buildString {
            append("<!doctype html><html><body>")
            while (length < 200_000) append("<p>Café 日本 — line $length</p>\n")
            append("</body></html>")
        }
        val response = run(request()) {
            routing { get("/") { call.respondText(html, ContentType.Text.Html) } }
        }
        assertEquals(200, response.statusCode)
        assertEquals(html, response.body)
        assertTrue(response.isBase64Encoded != true)
    }
}
