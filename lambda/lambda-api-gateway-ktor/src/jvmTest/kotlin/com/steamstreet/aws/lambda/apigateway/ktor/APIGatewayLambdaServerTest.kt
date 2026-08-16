package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.MockLambdaContext
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyResponse
import com.steamstreet.aws.lambda.apigateway.ProxyRequestContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import java.io.ByteArrayOutputStream
import kotlin.test.Test

/**
 * Tests for the JVM Lambda entry point.
 *
 * The request/response mapping is covered in `commonTest` by `APIGatewayKtorServerTest`, which runs
 * on native too. What is left here — and what cannot move — is the part that is JVM by nature:
 * `APIGatewayLambdaServer` is an AWS `RequestStreamHandler`, so these go in through
 * `execute(InputStream, OutputStream, Context)` and assert that the event is deserialized from the
 * stream, dispatched to the Ktor application, and serialized back out.
 */
class APIGatewayLambdaServerTest {
    /**
     * Runs a request through the full JVM handler: JSON in on an `InputStream`, JSON out on an
     * `OutputStream`, exactly as the AWS runtime would call it.
     */
    private fun executeViaStreams(
        request: ApiGatewayProxyRequest,
        server: APIGatewayLambdaServer
    ): ApiGatewayProxyResponse {
        val output = ByteArrayOutputStream()
        val requestBytes = ByteArrayOutputStream()

        @Suppress("OPT_IN_USAGE")
        Json.encodeToStream(request, requestBytes)
        server.execute(requestBytes.toByteArray().inputStream(), output, MockLambdaContext())

        @Suppress("OPT_IN_USAGE")
        return Json.decodeFromStream(output.toByteArray().inputStream())
    }

    private fun request(body: String? = null) = ApiGatewayProxyRequest(
        resource = "/my/path",
        path = "/my/path",
        httpMethod = "POST",
        body = body,
        requestContext = ProxyRequestContext()
    )

    /**
     * The whole path: stream in, routing, stream out.
     */
    @Test
    fun handlesRequestFromStreams() = runTest {
        val server = object : APIGatewayLambdaServer() {
            override fun Application.module() {
                routing {
                    route("my") {
                        route("path") {
                            post {
                                val receivedBody = call.receiveText()
                                call.apiGatewayRequest.path.shouldBeEqualTo("/my/path")
                                call.respondText(receivedBody.replace("Hello", "Goodbye"))
                            }
                        }
                    }
                }
            }
        }

        val response = executeViaStreams(request("Hello from Lambda!"), server)

        response.statusCode.shouldBeEqualTo(200)
        response.body.shouldNotBeNull().shouldBeEqualTo("Goodbye from Lambda!")
    }

    /**
     * An exception must not escape `execute` — it has to come back as a 500 on the serialized
     * response, because a handler that throws is reported to Lambda as an invocation error rather
     * than as an HTTP response to the client.
     */
    @Test
    fun failureBecomesA500Response() = runTest {
        val server = object : APIGatewayLambdaServer() {
            override fun Application.module() {
                install(StatusPages) {
                    exception<Throwable> { call, _ ->
                        call.respond(HttpStatusCode.InternalServerError)
                    }
                }
                routing {
                    route("my") {
                        route("path") {
                            post {
                                throw IllegalStateException("Whoops")
                            }
                        }
                    }
                }
            }
        }

        executeViaStreams(request("Hello from Lambda!"), server).statusCode.shouldBeEqualTo(500)
    }
}
