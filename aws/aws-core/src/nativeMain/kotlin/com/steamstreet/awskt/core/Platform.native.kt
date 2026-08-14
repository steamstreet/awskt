package com.steamstreet.awskt.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformGetEnv(name: String): String? = getenv(name)?.toKString()

/** Native has no system properties. Returning null keeps the resolution chain uniform. */
internal actual fun platformGetProperty(name: String): String? = null

/**
 * Always null, and that is the whole design rather than a stub awaiting work.
 *
 * `ktor-client-curl` — the only engine native has — reports every failed transfer as a bare
 * `IllegalStateException`, whatever the underlying `CURLcode` (`CurlMultiApiHandler.kt:352-354`,
 * ktor 3.5.2). There is no type to match on, so a `when (failure)` here could only test
 * `IllegalStateException`, which is not evidence of anything. Native classification is carried
 * entirely by `CURL_NOT_SENT_MARKERS` in the shared classifier, which reads the `CURLE_…` code out
 * of the message text — see its KDoc for why matching the code and not the surrounding sentence is
 * what keeps a send error from being replayed.
 */
internal actual fun platformTransportFailureHint(failure: Throwable): TransportFailure? = null
