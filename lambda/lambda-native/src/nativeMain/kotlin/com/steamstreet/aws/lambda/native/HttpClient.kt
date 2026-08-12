package com.steamstreet.aws.lambda.native

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.curl.Curl
import io.ktor.client.engine.curl.CurlClientEngineConfig

/**
 * An [HttpClient] configured for AWS Lambda on Kotlin/Native.
 *
 * Curl rather than CIO because CIO has no TLS on Kotlin/Native (KTOR-7262), and `caInfo` is set
 * explicitly because the Curl build Kotlin/Native links does not find Amazon Linux's bundle on its
 * own — without it every HTTPS call fails certificate verification.
 *
 * **Do not install `HttpTimeout` on a client used for the Runtime API.** See [LambdaRuntime] for
 * why: `GET /invocation/next` is a long poll that legitimately blocks for as long as the function
 * sits idle.
 */
public fun lambdaHttpClient(
    block: HttpClientConfig<CurlClientEngineConfig>.() -> Unit = {}
): HttpClient = HttpClient(Curl) {
    engine {
        caInfo = "/etc/pki/tls/certs/ca-bundle.crt"
    }
    block()
}
