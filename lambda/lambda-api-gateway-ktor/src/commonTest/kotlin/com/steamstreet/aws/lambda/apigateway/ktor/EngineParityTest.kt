package com.steamstreet.aws.lambda.apigateway.ktor

import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2Http
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpRequest
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2HttpResponse
import com.steamstreet.aws.lambda.apigateway.ApiGatewayV2RequestContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The places where the adapter once differed from Ktor's engines. `EngineParityJvmTest` runs the
 * same cases on Netty and checks the answers agree; these assert the answers themselves, so they
 * also hold on `linuxArm64`, where there is no engine to compare with.
 */
class EngineParityTest {
    private suspend fun run(
        method: String,
        path: String,
        module: Application.() -> Unit
    ): ApiGatewayV2HttpResponse = APIGatewayV2KtorServer(module).processRequest(
        ApiGatewayV2HttpRequest(
            rawPath = path,
            requestContext = ApiGatewayV2RequestContext(http = ApiGatewayV2Http(method = method, path = path))
        )
    )

    @Test
    fun anUnmatchedPathIsGivenToStatusPages() = runTest {
        val response = run("GET", "/missing") { engineParityApplication() }
        assertEquals(404, response.statusCode)
        assertEquals("the 404 page", response.body)
    }

    @Test
    fun aPathMatchedUnderAnotherMethodIs405AndIsGivenToStatusPages() = runTest {
        val response = run("HEAD", "/") { engineParityApplication() }
        assertEquals(405, response.statusCode)
        assertEquals("the 405 page", response.body)
    }

    @Test
    fun withoutStatusPagesTheFallbackStillSendsTheRoutingStatus() = runTest {
        val missing = run("GET", "/missing") { engineParityApplicationWithoutStatusPages() }
        assertEquals(404, missing.statusCode)
        assertNull(missing.body)

        val wrongMethod = run("POST", "/") { engineParityApplicationWithoutStatusPages() }
        assertEquals(405, wrongMethod.statusCode)
        assertNull(wrongMethod.body)
    }

    @Test
    fun theStatusIsNullUntilOneIsSet() = runTest {
        val seen = mutableMapOf<String, HttpStatusCode?>()
        run("GET", "/") { engineParityApplication { path, status -> seen[path] = status } }
        assertEquals(mapOf<String, HttpStatusCode?>("/" to null), seen)
    }

    @Test
    fun aStatusHandlerThatRespondsFromTheSendPipelineReplacesTheOuterResponse() = runTest {
        val response = run("GET", "/gone") { engineParityApplication() }
        assertEquals(404, response.statusCode)
        assertEquals("the 404 page", response.body)
    }

    @Test
    fun aMatchedRouteIsUntouched() = runTest {
        val response = run("GET", "/") { engineParityApplication() }
        assertEquals(200, response.statusCode)
        assertEquals("home", response.body)
    }
}
