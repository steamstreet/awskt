package com.steamstreet.awskt.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl

public actual fun awsHttpClient(caInfo: String?): HttpClient = HttpClient(Curl) {
    followRedirects = false
    expectSuccess = false
    engine {
        // Left unset when null so libcurl falls back to the system trust store. On AL2023 the
        // Lambda bootstrap passes /etc/pki/tls/certs/ca-bundle.crt explicitly.
        if (caInfo != null) this.caInfo = caInfo
    }
}
