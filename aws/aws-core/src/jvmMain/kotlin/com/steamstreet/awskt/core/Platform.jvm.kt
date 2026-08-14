package com.steamstreet.awskt.core

import io.ktor.client.network.sockets.ConnectTimeoutException
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLHandshakeException

internal actual fun platformGetEnv(name: String): String? = System.getenv(name)

internal actual fun platformGetProperty(name: String): String? = System.getProperty(name)

/**
 * The five JVM shapes that prove a request never left the process, matched as types.
 *
 * Each one fails strictly before the request bytes are written: DNS lookup
 * ([UnknownHostException], [UnresolvedAddressException]), TCP connect ([ConnectException],
 * [ConnectTimeoutException]) and the TLS handshake ([SSLHandshakeException]). `is` rather than a
 * name comparison, so a subclass — which an engine is free to introduce in a patch release —
 * answers the same as its parent. [ConnectTimeoutException] is in fact already covered by that
 * rule, since ktor declares it as a [ConnectException] subclass on the JVM; it is named anyway
 * because the coverage is ktor's choice to keep, not ours.
 *
 * Nothing else may be added without the same proof. In particular
 * `java.net.SocketTimeoutException` must stay off this list even though it sounds like a sibling:
 * it carries "Read timed out" as often as "connect timed out", and the first of those happens after
 * AWS has already applied the write. Likewise `SSLException` in general — a TLS failure *mid-stream*
 * says nothing about whether the request was received, so only the handshake subclass qualifies.
 */
internal actual fun platformTransportFailureHint(failure: Throwable): TransportFailure? =
    when (failure) {
        is ConnectException,
        is UnknownHostException,
        is UnresolvedAddressException,
        is SSLHandshakeException,
        is ConnectTimeoutException,
        -> TransportFailure.NOT_SENT

        else -> null
    }
