package com.steamstreet.aws.lambda.apigateway.ktor

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * The application that the engine parity tests run on the adapter ([EngineParityTest], in
 * `commonTest`, so on `linuxArm64` too) and on Netty (`EngineParityJvmTest`).
 *
 * It has the three things Ktor's engines do that the adapter once did not: a `StatusPages`
 * handler for 404 and 405 that turns calls routing did not handle into pages, a `status` handler
 * that responds from inside the send pipeline of the response it replaces, and a plugin that
 * records `call.response.status()` before anything has set it.
 *
 * [statusBeforeResponse] receives the request path and the status as the call began.
 */
internal fun Application.engineParityApplication(
    statusBeforeResponse: (path: String, status: HttpStatusCode?) -> Unit = { _, _ -> }
) {
    install(createApplicationPlugin("RecordInitialStatus") {
        onCall { call -> statusBeforeResponse(call.request.local.uri, call.response.status()) }
    })
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondText("the 404 page", status = HttpStatusCode.NotFound)
        }
        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.respondText("the 405 page", status = HttpStatusCode.MethodNotAllowed)
        }
    }
    routing {
        get("/") { call.respondText("home") }
        // Responds with a bare status, which the 404 handler above replaces.
        get("/gone") { call.respond(HttpStatusCode.NotFound) }
    }
}

/**
 * The same application without `StatusPages`, for the answers an engine gives with no plugin to
 * shape them.
 */
internal fun Application.engineParityApplicationWithoutStatusPages() {
    routing { get("/") { call.respondText("home") } }
}
