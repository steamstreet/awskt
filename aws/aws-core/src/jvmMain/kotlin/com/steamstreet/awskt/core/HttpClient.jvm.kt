package com.steamstreet.awskt.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit

/**
 * ### Why OkHttp (Ktor 3.5.2)
 *
 * The engine must keep connections open between calls. Through 3.1.1 this was CIO, and CIO with
 * pipelining off (its default) sends every request through `Endpoint.makeDedicatedRequest`. That
 * opens a socket for the request and closes it when the call completes, so every awskt call paid a
 * TCP connect and a TLS handshake, and left a socket in TIME_WAIT. A consumer's integration suite
 * ran macOS out of ephemeral ports at 13,373 TIME_WAIT sockets. The alternatives, in turn:
 *
 * - **CIO with `pipelining = true`** does not fix it. `requiresDedicatedConnection()` still sends a
 *   request down the dedicated path if its method is anything but GET or HEAD, or if it carries a
 *   connect or socket timeout. Every AWS JSON and query protocol call is a POST, and
 *   [configureAwsClient] sets both timeouts, so on both counts awskt would see no change. HTTP/1.1
 *   pipelining is also the wrong tool: one stalled response holds up every request queued behind it.
 * - **The Java engine** (`java.net.http.HttpClient`) pools connections and needs nothing beyond the
 *   JDK, but it does not honour the socket timeout. `JavaHttpEngine` reads only the connect timeout
 *   (onto the JDK client, built once from the first request) and the request timeout. No idle-gap
 *   bound exists on the JDK client, so a response that stalls mid-body would run to the full
 *   request budget. It also defaults to HTTP/2 and drops a list of restricted headers.
 * - **OkHttp** pools connections and honours all three timeouts. `OkHttpEngine.setupTimeoutAttributes`
 *   maps the connect timeout to `connectTimeout` and the socket timeout to both `readTimeout` and
 *   `writeTimeout`, and the `HttpTimeout` plugin enforces the request timeout itself. Ktor's engine
 *   defaults also disable OkHttp's own redirect following, which [configureAwsClient]'s
 *   `followRedirects = false` cannot reach. The cost is a dependency: `okhttp-jvm` 5.x and `okio`.
 *
 * So OkHttp. `AwsHttpClientJvmTest` holds it to each property below against a real socket. Three of
 * Ktor's OkHttp defaults are wrong for awskt, and are overridden here.
 *
 * ### Concurrency: 100 per host, 1,000 in total
 *
 * Ktor submits calls through OkHttp's `Dispatcher`, which by default runs 5 calls per host at once
 * and queues the rest. A queued call's wait counts against its request timeout, so a burst of slow
 * calls to one endpoint would have turned into timeouts. The limits here are CIO's
 * `maxConnectionsPerRoute` and `maxConnectionsCount`, so the change of engine does not narrow
 * throughput. Each client gets its own dispatcher, because closing a client shuts its dispatcher's
 * executor down.
 *
 * ### One pool of up to 100 idle connections, shared by every client
 *
 * OkHttp keeps 5 idle connections by default. Any burst wider than that closes the surplus when it
 * ends, and those sockets go to TIME_WAIT just as they did under CIO. The pool is shared across the
 * JVM, as Ktor's own default pool is, so an application that builds many clients holds at most 100
 * idle sockets, not 100 per client. Idle connections are closed after 15 seconds, not OkHttp's
 * default 5 minutes, which outlasted the idle timeout on the AWS side (see the next two sections).
 * Closing any one client evicts every idle connection in the pool. The other clients then open new
 * ones, which costs them a handshake but never fails a call.
 *
 * ### `retryOnConnectionFailure` is off
 *
 * Ktor's default OkHttp configuration turns it on. When a pooled connection fails, OkHttp then
 * resends the request on a new connection before the caller sees anything, and it does so even
 * after the server has read the whole request. Ktor hands awskt's JSON bodies to OkHttp as byte
 * arrays, which OkHttp treats as replayable, so a `PutItem` the server may already have applied
 * would be sent again. `AwsHttpClientJvmTest` observed one call reach the server three times. That
 * decides a question [classifyTransportFailure] exists to answer: the failure is AMBIGUOUS, which
 * awskt's retry loop retries for an idempotent operation and surfaces for a write. With the flag
 * off, the failure reaches that loop instead.
 *
 * It also gives up OkHttp's fallback to a second IP address after a failed connect, which the retry
 * loop already covers, because a connect failure is NOT_SENT.
 *
 * ### Stale pooled connections are discarded before the write
 *
 * The flag's real cost was the stale keep-alive race, and 3.1.2 shipped without an answer to it.
 * AWS closes an idle pooled connection, the next call writes its request onto it, and the read
 * finds EOF before a status line. With the flag on, OkHttp would have resent the request. With it
 * off, the failure is AMBIGUOUS and a write surfaces it. In production, a DynamoDB-stream Lambda
 * that posts to EventBridge failed 24 invocations with `unexpected end of stream` in its first burst.
 * [StaleConnectionGuard] now closes such a connection before anything is written to it and runs the
 * call on another. It is installed as an application interceptor, a network interceptor and an
 * event listener, and it shares the pool's lifetime. Its KDoc explains why the failure itself is
 * not reclassified as safe to retry.
 *
 * ### Timeouts
 *
 * [configureAwsClient] remains the single place the values live. OkHttp's engine builds one
 * `OkHttpClient` per distinct `HttpTimeoutCapability` and applies the values to it, so the
 * `engine { }` block below sets no timeouts of its own.
 *
 * ### `caInfo`
 *
 * Ignored on the JVM, as it was under CIO. The engine trusts the JVM's default trust store, and the
 * parameter exists for libcurl on native.
 */
public actual fun awsHttpClient(caInfo: String?, timeouts: AwsHttpTimeouts): HttpClient =
    awsHttpClient(timeouts, sharedConnectionPool, sharedStaleConnectionGuard)

/**
 * The engine with its pool and guard supplied, so that tests can give the guard their own clocks.
 * A guard must see every release from its pool, so the two are always passed as a pair.
 */
internal fun awsHttpClient(
    timeouts: AwsHttpTimeouts,
    connectionPool: ConnectionPool,
    staleConnectionGuard: StaleConnectionGuard,
): HttpClient {
    val dispatcher = Dispatcher().apply {
        maxRequests = 1_000
        maxRequestsPerHost = 100
    }
    return HttpClient(OkHttp) {
        configureAwsClient(timeouts)
        engine {
            // Applied after Ktor's own defaults, which stay in force for everything not named here.
            config {
                dispatcher(dispatcher)
                connectionPool(connectionPool)
                retryOnConnectionFailure(false)
                eventListener(staleConnectionGuard.eventListener)
                addInterceptor(staleConnectionGuard.applicationInterceptor)
                addNetworkInterceptor(staleConnectionGuard.networkInterceptor)
            }
        }
    }
}

private val sharedConnectionPool =
    ConnectionPool(100, StaleConnectionGuard.DEFAULT_MAX_IDLE_MILLIS, TimeUnit.MILLISECONDS)

private val sharedStaleConnectionGuard = StaleConnectionGuard()
