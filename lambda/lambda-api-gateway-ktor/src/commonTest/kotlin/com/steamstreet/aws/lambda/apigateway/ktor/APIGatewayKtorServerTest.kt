package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyResponse
import com.steamstreet.aws.lambda.apigateway.ProxyRequestContext
import com.steamstreet.exceptions.NotFoundException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the request/response mapping.
 *
 * These drive [APIGatewayKtorServer.processRequest] directly rather than going through the JVM
 * [APIGatewayLambdaServer], which is what lets them live in `commonTest` and run on native as well
 * as the JVM. That is not a weaker test: `APIGatewayLambdaServer` only deserializes the event and
 * delegates, so everything interesting — the pipeline execution, the status/header/body mapping —
 * happens below the line these tests draw. The JVM entry point itself is covered separately in
 * `jvmTest`.
 */
class APIGatewayKtorServerTest {
    /**
     * Make a test request with the given handler, routed at POST /my/path.
     */
    private suspend fun testRoute(
        request: ApiGatewayProxyRequest,
        handler: suspend RoutingContext.() -> Unit
    ): ApiGatewayProxyResponse {
        val server = APIGatewayKtorServer {
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

    private fun request(
        path: String = "/my/path",
        method: String = "POST",
        body: String? = null,
        headers: Map<String, String>? = null,
        queryStringParameters: Map<String, String>? = null,
        multiValueQueryStringParameters: Map<String, List<String>>? = null
    ) = ApiGatewayProxyRequest(
        resource = path,
        path = path,
        httpMethod = method,
        body = body,
        headers = headers,
        queryStringParameters = queryStringParameters,
        multiValueQueryStringParameters = multiValueQueryStringParameters,
        requestContext = ProxyRequestContext()
    )

    /**
     * Basic api test
     */
    @Test
    fun basics() = runTest {
        val response = testRoute(request(body = "Hello from Lambda!")) {
            val receivedBody = call.receiveText()

            assertEquals("/my/path", call.apiGatewayRequest.path)
            call.respondText(receivedBody.replace("Hello", "Goodbye"))
        }
        assertEquals(200, response.statusCode)
        assertEquals("Goodbye from Lambda!", assertNotNull(response.body))
    }

    /**
     * Test that headers are passed through
     */
    @Test
    fun receivedHeaders() = runTest {
        val headerKey = "X-Some-Header"
        val headerValue = "Hello!"
        val response = testRoute(
            request(body = "Hello from Lambda!", headers = mapOf(headerKey to headerValue))
        ) {
            assertEquals(headerValue, call.request.header(headerKey))
            // response with an odd code to make sure this was handled
            call.respond(HttpStatusCode.TooEarly)
        }
        assertEquals(HttpStatusCode.TooEarly.value, response.statusCode)
    }

    /**
     * Throw an exception and confirm the response is 500
     */
    @Test
    fun fail() = runTest {
        val response = testRoute(request(body = "Hello from Lambda!")) {
            throw IllegalStateException("Whoops")
        }
        assertEquals(500, response.statusCode)
    }

    /**
     * A body sent with a non-200 status must still reach the response.
     */
    @Test
    fun testBodyNon200() = runTest {
        val response = testRoute(request(body = "Hello from Lambda!")) {
            call.respond(HttpStatusCode.BadRequest, "Something here")
        }
        assertEquals(HttpStatusCode.BadRequest.value, response.statusCode)
    }

    /**
     * call.response.isCommitted must reflect whether respond has been called.
     * Ktor plugins (and Ktor itself) read it across re-accesses of `call.response`,
     * so it must not be snapshotted at construction.
     */
    @Test
    fun isCommittedReflectsRespondState() = runTest {
        var committedBefore: Boolean? = null
        var committedAfter: Boolean? = null
        val response = testRoute(request(body = "")) {
            committedBefore = call.response.isCommitted
            call.respondText("hi")
            committedAfter = call.response.isCommitted
        }
        assertEquals(200, response.statusCode)
        assertEquals(false, committedBefore)
        assertEquals(true, committedAfter)
    }

    /**
     * RequestConnectionPoint.uri must include the query string, matching what
     * other Ktor engines (Netty, testApplication) populate. Code that hashes
     * request.uri to distinguish URL variants depends on this.
     */
    @Test
    fun uriIncludesQueryString() = runTest {
        var observedUri: String? = null
        val response = testRoute(
            request(
                body = "",
                queryStringParameters = mapOf("a" to "1", "useAltUrl" to "true"),
                multiValueQueryStringParameters = mapOf(
                    "a" to listOf("1"),
                    "useAltUrl" to listOf("true")
                )
            )
        ) {
            observedUri = call.request.uri
            call.respond(HttpStatusCode.OK)
        }

        assertEquals(200, response.statusCode)
        val uri = assertNotNull(observedUri)
        assertTrue(uri.startsWith("/my/path?"), "expected a query string in '$uri'")
        assertTrue(uri.contains("a=1"), "expected 'a=1' in '$uri'")
        assertTrue(uri.contains("useAltUrl=true"), "expected 'useAltUrl=true' in '$uri'")
    }

    /**
     * Headers whose value legitimately contains a comma (e.g. IMF-fixdate Last-Modified,
     * comma-list Cache-Control) must round-trip through response() as a single
     * multiValueHeaders entry — not be split on commas.
     */
    @Test
    fun headerValuesWithCommasArePreserved() = runTest {
        val lastModified = "Sun, 06 Nov 2026 15:00:00 GMT"
        val cacheControl = "no-store, max-age=0, private"
        val response = testRoute(request(body = "")) {
            call.response.header(HttpHeaders.LastModified, lastModified)
            call.response.header(HttpHeaders.CacheControl, cacheControl)
            call.respond(HttpStatusCode.OK)
        }

        assertEquals(200, response.statusCode)
        val headers = assertNotNull(response.multiValueHeaders)
        assertEquals(listOf(lastModified), headers[HttpHeaders.LastModified])
        assertEquals(listOf(cacheControl), headers[HttpHeaders.CacheControl])
    }

    /**
     * Reproduces a bug report: a route throwing NotFoundException should be
     * converted to a 404 by the StatusPages plugin, but was reportedly returning 500.
     *
     * This variant installs ContentNegotiation so the JsonObject body can be
     * serialized — which mirrors the user's actual app configuration.
     */
    @Test
    fun statusPagesMapsNotFoundExceptionTo404_withContentNegotiation() = runTest {
        val server = APIGatewayKtorServer {
            install(ContentNegotiation) {
                json()
            }
            install(StatusPages) {
                exception<NotFoundException> { call, cause ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        buildJsonObject {
                            put("message", cause.message ?: "Not Found")
                            cause.resourceId?.let { put("resourceId", it) }
                        }
                    )
                }
            }

            routing {
                get("/missing") {
                    throw NotFoundException("thing was not found", resourceId = "abc-123")
                }
            }
        }

        val response = server.processRequest(request(path = "/missing", method = "GET"))

        assertEquals(HttpStatusCode.NotFound.value, response.statusCode)
        val parsedBody = Json.parseToJsonElement(assertNotNull(response.body)).toString()
        assertTrue(
            parsedBody.contains("\"message\":\"thing was not found\""),
            "missing message in $parsedBody"
        )
        assertTrue(
            parsedBody.contains("\"resourceId\":\"abc-123\""),
            "missing resourceId in $parsedBody"
        )
    }

    /**
     * Same as above but without a body — confirms that StatusPages itself can
     * map the exception to 404. If this passes while the JSON-body variant
     * fails, the regression is in body transformation, not the exception
     * mapping.
     */
    @Test
    fun statusPagesMapsNotFoundExceptionTo404_noBody() = runTest {
        val server = APIGatewayKtorServer {
            install(StatusPages) {
                exception<NotFoundException> { call, _ ->
                    call.respond(HttpStatusCode.NotFound)
                }
            }
            routing {
                get("/missing") {
                    throw NotFoundException("nope")
                }
            }
        }

        val response = server.processRequest(request(path = "/missing", method = "GET"))
        assertEquals(HttpStatusCode.NotFound.value, response.statusCode)
    }

    /**
     * Test form parameter parsing
     */
    @Test
    fun formParameterParsing() = runTest {
        val formRequest = lenientJson.decodeFromString<ApiGatewayProxyRequest>(formParamsRequest)

        val response = testRoute(formRequest) {
            val parameters = call.receiveParameters()
            assertEquals("client_credentials", parameters["grant_type"])
            assertEquals("kinflix-mobile", parameters["client_id"])
            assertEquals("kinflix-mobile-secret", parameters["client_secret"])

            call.respondText("Form parameters parsed successfully")
        }

        assertEquals(200, response.statusCode)
        assertEquals("Form parameters parsed successfully", assertNotNull(response.body))
    }
}

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * A real request captured from API Gateway, kept in its on-the-wire form rather than built with the
 * data classes — it carries fields (`deploymentId`, `domainPrefix`, `principalOrgId`) the model does
 * not declare, which is exactly what `ignoreUnknownKeys` has to survive.
 */
const val formParamsRequest: String = """
    {
        "resource": "/{proxy+}",
        "path": "/my/path",
        "httpMethod": "POST",
        "headers": {
            "Accept": "*/*",
            "Accept-Encoding": "br, deflate, gzip, x-gzip",
            "CloudFront-Forwarded-Proto": "https",
            "CloudFront-Is-Desktop-Viewer": "true",
            "CloudFront-Is-Mobile-Viewer": "false",
            "CloudFront-Is-SmartTV-Viewer": "false",
            "CloudFront-Is-Tablet-Viewer": "false",
            "CloudFront-Viewer-ASN": "22773",
            "CloudFront-Viewer-Country": "US",
            "Content-Type": "application/x-www-form-urlencoded",
            "Host": "75z6vj9jr4.execute-api.us-west-2.amazonaws.com",
            "User-Agent": "IntelliJ HTTP Client/IntelliJ IDEA 2025.1.4.1",
            "Via": "2.0 7645be6ac68aa5701b850abcb21df526.cloudfront.net (CloudFront)",
            "X-Amz-Cf-Id": "awCkvVEQff3GJpz46QReu9x93mviAWDUFlqqlhkKLGtU7nGbFsWm9w==",
            "X-Amzn-Trace-Id": "Root=1-6894fc9b-58f3f30e36e9171e0962b2a6",
            "X-Forwarded-For": "68.229.51.5, 18.68.47.245",
            "X-Forwarded-Port": "443",
            "X-Forwarded-Proto": "https"
        },
        "multiValueHeaders": {
            "Accept": [
                "*/*"
            ],
            "Content-Type": [
                "application/x-www-form-urlencoded"
            ],
            "X-Forwarded-For": [
                "68.229.51.5, 18.68.47.245"
            ]
        },
        "queryStringParameters": null,
        "multiValueQueryStringParameters": null,
        "pathParameters": {
            "proxy": "oauth/token"
        },
        "stageVariables": null,
        "requestContext": {
            "resourceId": "2dqop8",
            "resourcePath": "/{proxy+}",
            "httpMethod": "POST",
            "extendedRequestId": "O8xoXH6DPHcEsfQ=",
            "requestTime": "07/Aug/2025:19:20:59 +0000",
            "path": "/live/oauth/token",
            "accountId": "627754054305",
            "protocol": "HTTP/1.1",
            "stage": "live",
            "domainPrefix": "75z6vj9jr4",
            "requestTimeEpoch": 1754594459544,
            "requestId": "13cb05ea-8bc2-4846-a1df-ea6c4e956a76",
            "identity": {
                "cognitoIdentityPoolId": null,
                "accountId": null,
                "cognitoIdentityId": null,
                "caller": null,
                "sourceIp": "68.229.51.5",
                "principalOrgId": null,
                "accessKey": null,
                "cognitoAuthenticationType": null,
                "cognitoAuthenticationProvider": null,
                "userArn": null,
                "userAgent": "IntelliJ HTTP Client/IntelliJ IDEA 2025.1.4.1",
                "user": null
            },
            "domainName": "75z6vj9jr4.execute-api.us-west-2.amazonaws.com",
            "deploymentId": "y7c6g3",
            "apiId": "75z6vj9jr4"
        },
        "body": "grant_type=client_credentials&client_id=kinflix-mobile&client_secret=kinflix-mobile-secret",
        "isBase64Encoded": false
    }
"""
