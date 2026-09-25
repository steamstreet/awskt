package com.steamstreet.awskt.core

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.WeakHashMap

/**
 * Keeps [awsHttpClient] from writing a request onto a pooled connection that the server has
 * already closed, or has very probably forgotten.
 *
 * ### The defect this closes (3.1.2 and 3.1.3)
 *
 * The JVM engine turns OkHttp's `retryOnConnectionFailure` off, so that OkHttp cannot resend a
 * write that AWS may already have applied. That also stopped OkHttp from hiding the stale
 * keep-alive race: AWS closes an idle pooled connection, OkHttp hands it to the next call, the
 * request is written, and the read finds EOF before a status line
 * (`IOException: unexpected end of stream`). [classifyTransportFailure] calls that AMBIGUOUS, which
 * is correct, and so a non-idempotent write such as `PutEvents` surfaced the failure instead of
 * retrying. In production, a DynamoDB-stream Lambda that posts to EventBridge failed 24
 * invocations in its first burst after the upgrade. Lambda makes it worse: pooled connections
 * survive a freeze, and the server's idle timeout runs out while the process is frozen.
 *
 * ### Why the failure is not reclassified
 *
 * The obvious fix is to call "EOF before any response byte, on a reused connection" retryable,
 * which is roughly what browsers do. It cannot be made safe. From the client's side that failure is
 * the same as a server that read the whole request, perhaps applied it, and closed the connection
 * without answering. `AwsHttpClientJvmTest.theEngineDoesNotReplayARequestTheServerMayHaveApplied`
 * is exactly that case, and it must stay AMBIGUOUS. RFC 9110 §9.2.2 allows an automatic retry of a
 * non-idempotent request only with "some means to detect that the original request was never
 * applied", and an EOF after the write is not such a means. OkHttp does not claim otherwise:
 * `RetryAndFollowUpInterceptor.recover` treats every failure except `ConnectionShutdownException`
 * as possibly sent, and retries it anyway.
 *
 * ### What is done instead: decide before the first byte is written
 *
 * The only moment at which "the server has not seen this request" can be proved is before the
 * request is written. [networkInterceptor] runs after OkHttp has chosen a connection and before
 * `CallServerInterceptor` writes anything to it. For a reused HTTP/1.x connection it asks two
 * questions:
 *
 * 1. **Has it been idle for [maxIdleMillis] or longer?** Then it is closed without being probed.
 *    The limit is well under the idle timeouts AWS applies in practice, and it covers the cases a
 *    probe cannot see: a connection that a NAT or load balancer dropped silently, with no FIN, and
 *    a FIN that has not arrived yet because the process has just been thawed. Idle time is measured
 *    on both the monotonic clock and the wall clock, and the larger is taken, because a Lambda
 *    freeze is not guaranteed to advance the monotonic clock and the wall clock may step backwards.
 *    OkHttp's own pool measures only the monotonic clock, and it evicts from a background task that
 *    may not yet have run when the first call after a thaw takes a connection.
 * 2. **If it has been idle for at least [probeAfterIdleMillis], has the peer already closed it?**
 *    A one-byte read with a 1 ms timeout answers that. EOF, a reset or any unexpected byte means
 *    the connection is unusable, and a timeout means it is healthy. OkHttp runs the same probe
 *    itself, but only after 10 seconds of idleness. The threshold here keeps the millisecond the
 *    probe costs away from back-to-back calls, where the server's idle timer cannot have fired.
 *
 * A connection that fails either test is closed, and the attempt fails with
 * [StalePooledConnectionException] before anything is written. [applicationInterceptor] catches
 * that and runs the call again. OkHttp's route planner then finds the closed socket unhealthy and
 * moves on to another pooled connection or a new one. The retry is invisible to awskt's retry loop:
 * it costs no backoff and spends no retry budget. Should [MAX_STALE_DISCARDS] connections in a row
 * all be stale, the exception surfaces, and `platformTransportFailureHint` classifies it NOT_SENT,
 * which it provably is.
 *
 * ### What is left
 *
 * A server can still close a connection between the probe and the write, or close it for reasons
 * of its own after reading the request. Both remain AMBIGUOUS, as they must: retried for an
 * idempotent operation and surfaced for a write. The window is now the gap between a 1 ms probe and
 * the write, rather than the whole of the server's idle timeout.
 *
 * HTTP/2 connections are left alone. OkHttp reads their frames on a thread of its own, so a probe
 * would steal them, and it handles GOAWAY itself. Such a connection may also carry other calls'
 * streams, so closing it would break them.
 *
 * One guard serves every client that shares a pool, because a connection released by one client can
 * be acquired by another, and only the guard that saw it released knows how long it has been idle.
 */
internal class StaleConnectionGuard(
    private val maxIdleMillis: Long = DEFAULT_MAX_IDLE_MILLIS,
    private val probeAfterIdleMillis: Long = DEFAULT_PROBE_AFTER_IDLE_MILLIS,
    private val nanoTime: () -> Long = System::nanoTime,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private class Release(val nanos: Long, val wallMillis: Long)

    /** Weak keys, so that this map does not keep alive a connection the pool has evicted. */
    private val releases: MutableMap<Connection, Release> = Collections.synchronizedMap(WeakHashMap())

    /**
     * Records when each connection went back to the pool. A connection with no entry has never been
     * released, so it is fresh and there is nothing to check.
     */
    val eventListener: EventListener = object : EventListener() {
        override fun connectionReleased(call: Call, connection: Connection) {
            releases[connection] = Release(nanoTime(), currentTimeMillis())
        }
    }

    val networkInterceptor: Interceptor = Interceptor { chain ->
        val connection = chain.connection()
        val reason = if (connection != null) staleReason(connection) else null
        if (connection != null && reason != null) {
            try {
                connection.socket().close()
            } catch (_: IOException) {
                // Closing is best effort. The attempt is abandoned either way.
            }
            throw StalePooledConnectionException(reason)
        }
        chain.proceed(chain.request())
    }

    val applicationInterceptor: Interceptor = Interceptor { chain -> proceedPastStaleConnections(chain) }

    private fun proceedPastStaleConnections(chain: Interceptor.Chain): Response {
        var discarded = 0
        while (true) {
            try {
                return chain.proceed(chain.request())
            } catch (stale: StalePooledConnectionException) {
                if (++discarded >= MAX_STALE_DISCARDS) throw stale
            }
        }
    }

    private fun staleReason(connection: Connection): String? {
        val protocol = connection.protocol()
        if (protocol != Protocol.HTTP_1_1 && protocol != Protocol.HTTP_1_0) return null
        val released = releases[connection] ?: return null

        val idleMillis = maxOf(
            (nanoTime() - released.nanos) / 1_000_000,
            currentTimeMillis() - released.wallMillis,
        )
        if (idleMillis >= maxIdleMillis) return "idle for ${idleMillis}ms, at or over the ${maxIdleMillis}ms limit"
        if (idleMillis >= probeAfterIdleMillis && peerHasClosed(connection.socket())) {
            return "closed by the server after ${idleMillis}ms idle"
        }
        return null
    }

    /**
     * True when the socket holds something other than silence: EOF, a reset, or bytes that no idle
     * HTTP/1.x connection should have. The connection is between exchanges, so OkHttp's own buffer
     * is empty and the read cannot take anything OkHttp would have wanted. On a TLS socket the read
     * consumes post-handshake records, such as a TLS 1.3 session ticket, without returning them, and
     * a close_notify reads as EOF.
     */
    private fun peerHasClosed(socket: Socket): Boolean {
        val readTimeout = socket.soTimeout
        return try {
            socket.soTimeout = 1
            socket.getInputStream().read()
            true
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: IOException) {
            true
        } finally {
            try {
                socket.soTimeout = readTimeout
            } catch (_: IOException) {
                // A socket that cannot take a timeout is closed, and the exchange will find out.
            }
        }
    }

    internal companion object {
        /**
         * Well under the idle close AWS endpoints apply in practice. The AWS SDK for Java v2 keeps
         * idle connections for up to 60 seconds by default. Erring short costs one TLS handshake on
         * a call that follows a pause of 15 seconds or more.
         */
        const val DEFAULT_MAX_IDLE_MILLIS: Long = 15_000

        const val DEFAULT_PROBE_AFTER_IDLE_MILLIS: Long = 1_000

        /**
         * Enough to walk past the stale connections a thawed Lambda would ordinarily hold for one
         * host. If this many in a row are stale, the failure goes to awskt's retry loop as NOT_SENT.
         */
        const val MAX_STALE_DISCARDS: Int = 32
    }
}

/**
 * A pooled connection was found stale and closed **before any byte of the request was written to
 * it**. That is what makes it [TransportFailure.NOT_SENT]: the server cannot have seen the request.
 * Thrown only by [StaleConnectionGuard.networkInterceptor].
 */
internal class StalePooledConnectionException(reason: String) :
    IOException("Discarded a stale pooled connection before writing the request: $reason")
