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
 *
 * ### Connection reuse
 *
 * Curl does not have the defect that 3.1.1's CIO engine had on the JVM (see the JVM actual). Each
 * engine owns one `CurlMultiApiHandler`, whose single `curl_multi_init()` handle lives until the
 * client is closed. Each request's easy handle is removed and cleaned up when it completes, but
 * libcurl keeps the connection in the multi handle's connection cache. Nothing sets
 * `CURLOPT_FORBID_REUSE` or `CURLOPT_FRESH_CONNECT`, so the next request to the same host reuses the
 * connection. This comes from reading the Ktor 3.5.2 source; unlike the JVM, no test observes it.
 */
public actual fun awsHttpClient(caInfo: String?, timeouts: AwsHttpTimeouts): HttpClient = HttpClient(Curl) {
    configureAwsClient(timeouts)
    engine {
        // Left unset when null so libcurl falls back to the system trust store. On AL2023 the
        // Lambda bootstrap passes /etc/pki/tls/certs/ca-bundle.crt explicitly.
        if (caInfo != null) this.caInfo = caInfo
    }
}
