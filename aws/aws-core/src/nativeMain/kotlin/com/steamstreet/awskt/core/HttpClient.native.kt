package com.steamstreet.awskt.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl

/**
 * ### How the three timeouts land on libcurl (Ktor 3.5.2)
 *
 * The Curl engine declares `HttpTimeoutCapability`, but it reads only **one** field out of it:
 * `connectTimeoutMillis`, which it sets as `CURLOPT_CONNECTTIMEOUT_MS` and reports as a
 * `ConnectTimeoutException` (`CurlMultiApiHandler.kt`). `socketTimeoutMillis` is **ignored** by this
 * engine — it is not mapped to `CURLOPT_LOW_SPEED_LIMIT`/`CURLOPT_LOW_SPEED_TIME` or to anything
 * else — so on native the idle-gap bound does not exist.
 *
 * `requestTimeoutMillis` is what covers the gap, and it is engine-independent: the `HttpTimeout`
 * plugin enforces it itself, with a coroutine that cancels the call's job once the budget is spent.
 * So a native call that stalls mid-body is still bounded, just by the whole-attempt budget rather
 * than by the idle gap — which is why leaving `requestTimeoutMillis` unset on native turns a stalled
 * response back into the unbounded hang this exists to prevent, in a way it does not on JVM.
 *
 * Setting the curl options directly here instead was considered and rejected: `CURLOPT_LOW_SPEED_TIME`
 * has **second** granularity, so it cannot express the millisecond values this API takes, and the
 * engine rebuilds its easy handle per request from `CurlRequestData` — there is no supported seam to
 * add an option to it from outside.
 */
public actual fun awsHttpClient(caInfo: String?, timeouts: AwsHttpTimeouts): HttpClient = HttpClient(Curl) {
    configureAwsClient(timeouts)
    engine {
        // Left unset when null so libcurl falls back to the system trust store. On AL2023 the
        // Lambda bootstrap passes /etc/pki/tls/certs/ca-bundle.crt explicitly.
        if (caInfo != null) this.caInfo = caInfo
    }
}
