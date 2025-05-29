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
}