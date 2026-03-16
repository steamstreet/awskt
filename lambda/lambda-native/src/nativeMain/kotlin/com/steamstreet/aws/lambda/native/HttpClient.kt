package com.steamstreet.aws.lambda.native

import io.ktor.client.*
import io.ktor.client.engine.curl.*

/**
 * Create an HttpClient pre-configured for use in AWS Lambda on Kotlin/Native.
 *
 * Uses the Curl engine with the CA certificate bundle path set for Amazon Linux 2023.
 * This is required because the CIO engine does not support TLS on Kotlin/Native.
 */
public fun lambdaHttpClient(
    block: HttpClientConfig<CurlClientEngineConfig>.() -> Unit = {}
): HttpClient = HttpClient(Curl) {
    engine {
        caInfo = "/etc/pki/tls/certs/ca-bundle.crt"
    }
    block()
}
