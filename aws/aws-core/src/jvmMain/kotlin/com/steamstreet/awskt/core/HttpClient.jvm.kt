package com.steamstreet.awskt.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

public actual fun awsHttpClient(caInfo: String?, timeouts: AwsHttpTimeouts): HttpClient = HttpClient(CIO) {
    // Installs `HttpTimeout`, which CIO honours per request through `HttpTimeoutCapability`. That
    // is what makes the plugin the single place the values live: CIO also carries engine-level
    // defaults (`endpoint.connectTimeout`, `requestTimeout`), and configuring the engine block
    // instead would leave two sets of numbers to disagree with each other.
    configureAwsClient(timeouts)
}
