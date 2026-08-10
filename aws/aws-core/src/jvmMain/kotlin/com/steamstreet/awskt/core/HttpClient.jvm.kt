package com.steamstreet.awskt.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

public actual fun awsHttpClient(caInfo: String?): HttpClient = HttpClient(CIO) {
    followRedirects = false
    expectSuccess = false
}
