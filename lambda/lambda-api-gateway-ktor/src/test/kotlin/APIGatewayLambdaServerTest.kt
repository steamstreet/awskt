import com.steamstreet.aws.lambda.MockLambdaContext
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyResponse
import com.steamstreet.aws.lambda.apigateway.ProxyRequestContext
import com.steamstreet.aws.lambda.apigateway.ktor.APIGatewayLambdaServer
import com.steamstreet.aws.lambda.apigateway.ktor.apiGatewayRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import java.io.ByteArrayOutputStream
import kotlin.test.Test

/**
 * Tests for the API gateway ktor server.
 */
class APIGatewayLambdaServerTest {
    /**
     * Make a test request with the given handler
     */
    private fun testRoute(
        request: ApiGatewayProxyRequest,
        handler: suspend RoutingContext.() -> Unit
    ): ApiGatewayProxyResponse {
        val server = object : APIGatewayLambdaServer() {
            override fun Application.module() {
                install(StatusPages) {
                    exception<Throwable> { call: ApplicationCall, cause: Throwable ->
                        call.respond(HttpStatusCode.InternalServerError)
                    }
                    this.unhandled {
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
        }

        val output = ByteArrayOutputStream()
        val requestBytes = ByteArrayOutputStream()

        @Suppress("OPT_IN_USAGE")
        Json.encodeToStream(request, requestBytes)
        server.execute(requestBytes.toByteArray().inputStream(), output, MockLambdaContext())

        @Suppress("OPT_IN_USAGE")
        return Json.decodeFromStream(output.toByteArray().inputStream())
    }

    /**
     * Basic api test
     */
    @Test
    fun basics() = runTest {
        val response = testRoute(
            ApiGatewayProxyRequest(
                resource = "/my/path",
                path = "/my/path",
                httpMethod = "POST",
                body = "Hello from Lambda!",
                requestContext = ProxyRequestContext()
            )
        ) {
            val receivedBody = call.receiveText()

            call.apiGatewayRequest.path.shouldBeEqualTo("/my/path")
            call.respondText(receivedBody.replace("Hello", "Goodbye"))
        }
        response.statusCode.shouldBeEqualTo(200)
        response.body.shouldNotBeNull().shouldBeEqualTo("Goodbye from Lambda!")
    }

    /**
     * Test that headers are passed through
     */
    @Test
    fun receivedHeaders() = runTest {
        val headerKey = "X-Some-Header"
        val headerValue = "Hello!"
        val response = testRoute(
            ApiGatewayProxyRequest(
                resource = "/my/path",
                path = "/my/path",
                httpMethod = "POST",
                body = "Hello from Lambda!",
                requestContext = ProxyRequestContext(),
                headers = mapOf(headerKey to headerValue)
            )
        ) {
            call.request.header(headerKey).shouldBeEqualTo(headerValue)
            // response with an odd code to make sure this was handled
            call.respond(HttpStatusCode.TooEarly)
        }
        response.statusCode.shouldBeEqualTo(HttpStatusCode.TooEarly.value)
    }

    /**
     * Throw an exception and confirm the response is 500
     */
    @Test
    fun fail() = runTest {
        val response = testRoute(
            ApiGatewayProxyRequest(
                resource = "/my/path",
                path = "/my/path",
                httpMethod = "POST",
                body = "Hello from Lambda!",
                requestContext = ProxyRequestContext()
            )
        ) {
            throw IllegalStateException("Whoops")
        }
        response.statusCode.shouldBeEqualTo(500)
    }

    /**
     * Throw an exception and confirm the response is 500
     */
    @Test
    fun testBodyNon200() = runTest {
        val response = testRoute(
            ApiGatewayProxyRequest(
                resource = "/my/path",
                path = "/my/path",
                httpMethod = "POST",
                body = "Hello from Lambda!",
                requestContext = ProxyRequestContext()
            )
        ) {
            call.respond(HttpStatusCode.BadRequest, "Something here")
        }
        response.statusCode.shouldBeEqualTo(HttpStatusCode.BadRequest.value)
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
            ApiGatewayProxyRequest(
                resource = "/my/path",
                path = "/my/path",
                httpMethod = "POST",
                body = "",
                requestContext = ProxyRequestContext(),
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

        response.statusCode.shouldBeEqualTo(200)
        observedUri.shouldNotBeNull()
        observedUri!!.startsWith("/my/path?").shouldBeEqualTo(true)
        observedUri!!.contains("a=1").shouldBeEqualTo(true)
        observedUri!!.contains("useAltUrl=true").shouldBeEqualTo(true)
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
        val response = testRoute(
            ApiGatewayProxyRequest(
                resource = "/my/path",
                path = "/my/path",
                httpMethod = "POST",
                body = "",
                requestContext = ProxyRequestContext()
            )
        ) {
            call.response.header(HttpHeaders.LastModified, lastModified)
            call.response.header(HttpHeaders.CacheControl, cacheControl)
            call.respond(HttpStatusCode.OK)
        }

        response.statusCode.shouldBeEqualTo(200)
        response.multiValueHeaders.shouldNotBeNull()
        response.multiValueHeaders!![HttpHeaders.LastModified].shouldBeEqualTo(listOf(lastModified))
        response.multiValueHeaders!![HttpHeaders.CacheControl].shouldBeEqualTo(listOf(cacheControl))
    }

    /**
     * Test form parameter parsing
     */
    @Test
    fun formParameterParsing() = runTest {
        val formRequest = Json { ignoreUnknownKeys = true }.decodeFromString<ApiGatewayProxyRequest>(formParamsRequest)

        val response = testRoute(formRequest) {
            val parameters = call.receiveParameters()
            parameters["grant_type"].shouldNotBeNull()
            parameters["grant_type"].shouldBeEqualTo("client_credentials")
            parameters["client_id"].shouldBeEqualTo("kinflix-mobile")
            parameters["client_secret"].shouldBeEqualTo("kinflix-mobile-secret")

            call.respondText("Form parameters parsed successfully")
        }

        response.statusCode.shouldBeEqualTo(200)
        response.body.shouldNotBeNull().shouldBeEqualTo("Form parameters parsed successfully")
    }


}

const val formParamsRequest = """
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
            "Accept-Encoding": [
                "br, deflate, gzip, x-gzip"
            ],
            "CloudFront-Forwarded-Proto": [
                "https"
            ],
            "CloudFront-Is-Desktop-Viewer": [
                "true"
            ],
            "CloudFront-Is-Mobile-Viewer": [
                "false"
            ],
            "CloudFront-Is-SmartTV-Viewer": [
                "false"
            ],
            "CloudFront-Is-Tablet-Viewer": [
                "false"
            ],
            "CloudFront-Viewer-ASN": [
                "22773"
            ],
            "CloudFront-Viewer-Country": [
                "US"
            ],
            "Content-Type": [
                "application/x-www-form-urlencoded"
            ],
            "Host": [
                "75z6vj9jr4.execute-api.us-west-2.amazonaws.com"
            ],
            "User-Agent": [
                "IntelliJ HTTP Client/IntelliJ IDEA 2025.1.4.1"
            ],
            "Via": [
                "2.0 7645be6ac68aa5701b850abcb21df526.cloudfront.net (CloudFront)"
            ],
            "X-Amz-Cf-Id": [
                "awCkvVEQff3GJpz46QReu9x93mviAWDUFlqqlhkKLGtU7nGbFsWm9w=="
            ],
            "X-Amzn-Trace-Id": [
                "Root=1-6894fc9b-58f3f30e36e9171e0962b2a6"
            ],
            "X-Forwarded-For": [
                "68.229.51.5, 18.68.47.245"
            ],
            "X-Forwarded-Port": [
                "443"
            ],
            "X-Forwarded-Proto": [
                "https"
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