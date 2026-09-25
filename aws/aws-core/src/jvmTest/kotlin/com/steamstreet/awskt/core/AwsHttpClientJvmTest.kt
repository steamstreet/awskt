package com.steamstreet.awskt.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.ktor.client.network.sockets.SocketTimeoutException as KtorSocketTimeoutException

/**
 * The real JVM engine against a real socket. Every other transport test runs on `MockEngine`, which
 * has no connections, so none of the properties below can be observed there. `awsHttpClient`'s JVM
 * KDoc explains each engine setting these tests hold in place.
 *
 * **Connection reuse.** 3.1.1 shipped CIO with pipelining off, and in that mode CIO opens a socket
 * per request and closes it when the call completes. Each call then leaves a socket in TIME_WAIT,
 * and a busy JVM runs out of ephemeral ports: a consumer's integration suite failed with
 * `BindException: Can't assign requested address` at 13,373 TIME_WAIT sockets. The server here
 * records the client's address for every request it serves. A distinct address is a distinct
 * connection, because a closed port sits in TIME_WAIT and is not handed out again within one test.
 *
 * **Timeouts.** [configureAwsClient] installs `HttpTimeout`, but each engine decides for itself
 * which of its fields to honour. These tests prove that the socket and request timeouts fire on the
 * engine actually shipped. The connect timeout has no test, because no local setup makes a connect
 * hang reliably. Read `OkHttpEngine.setupTimeoutAttributes` instead: it maps the connect timeout
 * to `OkHttpClient.connectTimeout`.
 *
 * **Concurrency and replay.** Two of OkHttp's defaults, as Ktor configures it, cap concurrent calls
 * to one host at 5 and resend a request the server may already have applied. The tests below pin
 * the overrides.
 *
 * **Stale pooled connections.** With OkHttp's replay turned off, a pooled connection that the
 * server had closed while it was idle failed the next write outright. 3.1.2 did that in production.
 * The tests below pin [StaleConnectionGuard], which discards such a connection before writing to it
 * and leaves everything that happens after the write AMBIGUOUS.
 */
class AwsHttpClientJvmTest {

    private val clientAddresses: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
        createContext("/echo") { exchange ->
            clientAddresses += exchange.remoteAddress.toString()
            exchange.requestBody.readBytes()
            exchange.respond("""{"ok":true}""")
        }
        // Sends the headers and half the promised body, then goes quiet.
        createContext("/stall-mid-body") { exchange ->
            exchange.sendResponseHeaders(200, 100)
            exchange.responseBody.write(ByteArray(50))
            exchange.responseBody.flush()
            Thread.sleep(5_000)
            exchange.close()
        }
        // Never goes quiet for long enough to trip the socket timeout, but never finishes either.
        createContext("/trickle") { exchange ->
            exchange.sendResponseHeaders(200, 1_000)
            repeat(1_000) {
                exchange.responseBody.write(0)
                exchange.responseBody.flush()
                Thread.sleep(100)
            }
            exchange.close()
        }
        start()
    }

    private val baseUrl = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    /**
     * POST, because every AWS JSON and query protocol call is one. CIO's pipelining mode, which is
     * the only mode in which CIO keeps a connection, still gives a POST a dedicated connection, so
     * a GET-based test would have been the wrong test.
     */
    @Test
    fun sequentialCallsReuseOneConnection() = runBlocking {
        val calls = 200
        awsHttpClient().use { client ->
            repeat(calls) {
                val response = client.post("$baseUrl/echo") {
                    contentType(ContentType("application", "x-amz-json-1.0"))
                    setBody("""{"TableName":"t"}""")
                }
                assertEquals("""{"ok":true}""", response.bodyAsText())
            }
        }

        assertEquals(calls, clientAddresses.size)
        val connections = clientAddresses.toSet().size
        assertEquals(
            1,
            connections,
            "$calls sequential calls used $connections connections; the engine is not reusing them",
        )
    }

    /**
     * OkHttp's stock dispatcher runs 5 calls per host at once, and its stock pool keeps 5 idle
     * connections. A burst of 20 would then have run in four waves and, once it ended, closed 15
     * connections that the next burst had to open again.
     */
    @Test
    fun concurrentBurstsRunTogetherAndReuseTheirConnections() = runBlocking {
        val burst = 20
        val inFlight = AtomicInteger()
        val peakInFlight = AtomicInteger()
        server.createContext("/slow") { exchange ->
            clientAddresses += exchange.remoteAddress.toString()
            exchange.requestBody.readBytes()
            peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            Thread.sleep(300)
            inFlight.decrementAndGet()
            exchange.respond("{}")
        }

        awsHttpClient().use { client ->
            repeat(3) {
                (1..burst).map {
                    async(Dispatchers.IO) { client.post("$baseUrl/slow") { setBody("{}") }.bodyAsText() }
                }.awaitAll()
            }
        }

        assertEquals(burst, peakInFlight.get(), "calls to one host were queued behind a concurrency limit")
        assertEquals(3 * burst, clientAddresses.size)
        val connections = clientAddresses.toSet().size
        assertTrue(
            connections <= burst,
            "3 bursts of $burst used $connections connections; the pool closed connections between bursts",
        )
    }

    @Test
    fun theSocketTimeoutFiresWhenAResponseStallsMidBody() = runBlocking {
        val timeouts = AwsHttpTimeouts(socketTimeoutMillis = 300, requestTimeoutMillis = 10_000)
        awsHttpClient(timeouts = timeouts).use { client ->
            val started = System.nanoTime()
            val failure = assertFailsWith<Throwable> {
                client.post("$baseUrl/stall-mid-body").bodyAsText()
            }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue(
                failure.causes().any { it is KtorSocketTimeoutException || it is java.net.SocketTimeoutException },
                "expected a socket timeout, got $failure",
            )
            assertTrue(elapsedMillis < 4_000, "the socket timeout took ${elapsedMillis}ms to fire")
        }
    }

    /**
     * The trickle keeps every gap under the socket timeout, so only the whole-attempt budget can
     * end this call.
     */
    @Test
    fun theRequestTimeoutBoundsAResponseThatNeverStallsButNeverEnds() = runBlocking {
        val timeouts = AwsHttpTimeouts(socketTimeoutMillis = 2_000, requestTimeoutMillis = 700)
        awsHttpClient(timeouts = timeouts).use { client ->
            val started = System.nanoTime()
            val failure = assertFailsWith<Throwable> {
                client.post("$baseUrl/trickle").bodyAsText()
            }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue(
                failure.causes().any { it is HttpRequestTimeoutException },
                "expected a request timeout, got $failure",
            )
            assertTrue(elapsedMillis < 4_000, "the request timeout took ${elapsedMillis}ms to fire")
        }
    }

    /**
     * A server that reads the whole request and hangs up without answering has, as far as the
     * client can tell, possibly applied it. [classifyTransportFailure] calls that AMBIGUOUS and
     * surfaces it for a write. An engine that quietly resends the request on a new connection would
     * decide the question before that classifier ever sees it. OkHttp does exactly that with its
     * default `retryOnConnectionFailure`, and only when the failed connection came from the pool,
     * which is why the first call here succeeds and the hang-up comes on the second.
     */
    @Test
    fun theEngineDoesNotReplayARequestTheServerMayHaveApplied() = runBlocking {
        val requestsSeen = AtomicInteger()
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { hangUp ->
            val acceptor = Thread {
                while (!hangUp.isClosed) {
                    val socket = try {
                        hangUp.accept()
                    } catch (_: IOException) {
                        break
                    }
                    socket.use {
                        val input = it.getInputStream().bufferedReader()
                        while (true) {
                            val contentLength = generateSequence { input.readLine() }
                                .takeWhile { line -> line.isNotEmpty() }
                                .firstNotNullOfOrNull { line ->
                                    line.substringAfter("Content-Length:", "").trim().toIntOrNull()
                                } ?: 0
                            repeat(contentLength) { input.read() }
                            // The first request is answered and leaves the connection open for the
                            // pool. Every later one is read in full and then hung up on.
                            if (requestsSeen.incrementAndGet() > 1) break
                            it.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
                                flush()
                            }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }

            awsHttpClient().use { client ->
                suspend fun putItem() = client.post("http://127.0.0.1:${hangUp.localPort}/") {
                    contentType(ContentType("application", "x-amz-json-1.0"))
                    setBody("""{"TableName":"t","Item":{}}""")
                }.bodyAsText()

                assertEquals("{}", putItem())
                val failure = assertFailsWith<Throwable> { putItem() }
                assertEquals(TransportFailure.AMBIGUOUS, classifyTransportFailure(failure))
            }
            hangUp.close()
            acceptor.join(2_000)
        }
        assertEquals(2, requestsSeen.get(), "the engine resent a request the server may have applied")
    }

    /**
     * The retry loop may resend a write only if the failure proves the request never left the
     * process. That proof is made from the exception an engine raises, and each engine raises its
     * own, so it has to be checked against the engine actually shipped.
     */
    @Test
    fun aRefusedConnectionIsClassifiedAsNotSent() = runBlocking {
        val closedPort = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }
        awsHttpClient().use { client ->
            val failure = assertFailsWith<Throwable> {
                client.post("http://127.0.0.1:$closedPort/") { setBody("{}") }.bodyAsText()
            }
            assertEquals(TransportFailure.NOT_SENT, classifyTransportFailure(failure), "$failure")
        }
    }

    /**
     * The production failure in 3.1.2: AWS closes an idle keep-alive connection, the pool hands it
     * to the next call, and the request is written onto a socket whose FIN is already waiting. The
     * read then finds EOF before a status line, which [classifyTransportFailure] rightly calls
     * AMBIGUOUS, so a `PutEvents` surfaced the failure instead of retrying. The server here answers
     * a connection's first request and then closes the connection.
     *
     * The pause is longer than [StaleConnectionGuard.DEFAULT_PROBE_AFTER_IDLE_MILLIS], below which
     * a reused connection is not probed. No AWS endpoint closes a connection within a second of
     * answering on it.
     */
    @Test
    fun aConnectionTheServerClosedWhileIdleIsNotWrittenTo() = runBlocking {
        RawHttpServer { requestOnConnection ->
            if (requestOnConnection == 1) Reply.ANSWER_THEN_CLOSE else Reply.HANG_UP
        }.use { server ->
            awsHttpClient().use { client ->
                assertEquals("{}", client.putEvents(server.url))
                server.awaitClosedConnections(1)
                Thread.sleep(1_200)
                assertEquals("{}", client.putEvents(server.url))
            }
            assertEquals(
                listOf(1, 1),
                server.requestsPerConnection(),
                "each request must arrive exactly once, each on its own connection",
            )
        }
    }

    /**
     * A connection that is forgotten rather than closed sends no FIN, so no probe can see it: a NAT
     * that dropped it silently, or a Lambda thawed before the server's FIN has arrived. The server
     * stands in for that by hanging up on any second request on a connection. Only the idle limit
     * keeps the write off it, and here only the wall clock shows the limit was passed, as it may
     * after a Lambda freeze that did not advance the monotonic clock.
     */
    @Test
    fun aConnectionIdlePastTheLimitOnTheWallClockIsNotWrittenTo() = runBlocking {
        val wallClock = AtomicLong(System.currentTimeMillis())
        val guard = StaleConnectionGuard(nanoTime = { 0L }, currentTimeMillis = wallClock::get)
        RawHttpServer { requestOnConnection ->
            if (requestOnConnection == 1) Reply.ANSWER else Reply.HANG_UP
        }.use { server ->
            awsHttpClient(AwsHttpTimeouts(), ConnectionPool(), guard).use { client ->
                assertEquals("{}", client.putEvents(server.url))
                wallClock.addAndGet(10 * 60_000)
                assertEquals("{}", client.putEvents(server.url))
            }
            assertEquals(listOf(1, 1), server.requestsPerConnection(), "the forgotten connection was written to")
        }
    }

    /**
     * Below the idle limit and with no FIN waiting, the guard must leave the connection alone, so
     * that a hang-up after the write is still reported and not replayed. This is the same case as
     * [theEngineDoesNotReplayARequestTheServerMayHaveApplied], with the probe forced to run.
     */
    @Test
    fun aHealthyPooledConnectionIsKeptAndAHangUpAfterTheWriteStaysAmbiguous() = runBlocking {
        val guard = StaleConnectionGuard(probeAfterIdleMillis = 0)
        RawHttpServer { requestOnConnection ->
            if (requestOnConnection == 1) Reply.ANSWER else Reply.HANG_UP
        }.use { server ->
            awsHttpClient(AwsHttpTimeouts(), ConnectionPool(), guard).use { client ->
                assertEquals("{}", client.putEvents(server.url))
                val failure = assertFailsWith<Throwable> { client.putEvents(server.url) }
                assertEquals(TransportFailure.AMBIGUOUS, classifyTransportFailure(failure), "$failure")
            }
            assertEquals(listOf(2), server.requestsPerConnection(), "the probe discarded a healthy connection, or the write was replayed")
        }
    }

    /**
     * The fix only ever decides before a write. A server that reads the request on a fresh
     * connection and then hangs up may have applied it, and that must stay AMBIGUOUS.
     */
    @Test
    fun aHangUpAfterTheWriteOnAFreshConnectionStaysAmbiguous() = runBlocking {
        RawHttpServer { Reply.HANG_UP }.use { server ->
            awsHttpClient().use { client ->
                val failure = assertFailsWith<Throwable> { client.putEvents(server.url) }
                assertEquals(TransportFailure.AMBIGUOUS, classifyTransportFailure(failure), "$failure")
            }
            assertEquals(listOf(1), server.requestsPerConnection(), "the engine resent a request the server may have applied")
        }
    }

    private suspend fun HttpClient.putEvents(url: String): String = post(url) {
        contentType(ContentType("application", "x-amz-json-1.1"))
        setBody("""{"Entries":[{"Source":"s","DetailType":"d","Detail":"{}"}]}""")
    }.bodyAsText()

    private enum class Reply { ANSWER, ANSWER_THEN_CLOSE, HANG_UP }

    /**
     * A plain HTTP/1.1 server on a raw socket, for what `HttpServer` cannot stage: closing a
     * connection as soon as it is idle, and hanging up once a request has been read. [reply] is
     * asked about each request, with the 1-based index of that request on its connection.
     */
    private class RawHttpServer(private val reply: (requestOnConnection: Int) -> Reply) : AutoCloseable {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val requestCounts: MutableList<AtomicInteger> = Collections.synchronizedList(mutableListOf())
        private val closedConnections = AtomicInteger()

        val url = "http://127.0.0.1:${serverSocket.localPort}/"

        init {
            Thread {
                while (!serverSocket.isClosed) {
                    val socket = try {
                        serverSocket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    val requests = AtomicInteger().also { requestCounts += it }
                    Thread { serve(socket, requests) }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; start() }
        }

        private fun serve(socket: Socket, requests: AtomicInteger) {
            try {
                socket.use {
                    val input = it.getInputStream().bufferedReader()
                    while (true) {
                        val headers = generateSequence { input.readLine() }
                            .takeWhile { line -> line.isNotEmpty() }
                            .toList()
                        if (headers.isEmpty()) return
                        val contentLength = headers.firstNotNullOfOrNull { line ->
                            line.substringAfter("Content-Length:", "").trim().toIntOrNull()
                        } ?: 0
                        repeat(contentLength) { input.read() }
                        val action = reply(requests.incrementAndGet())
                        if (action == Reply.HANG_UP) return
                        it.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
                            flush()
                        }
                        if (action == Reply.ANSWER_THEN_CLOSE) return
                    }
                }
            } catch (_: IOException) {
                // The client went away. There is nothing to record.
            } finally {
                closedConnections.incrementAndGet()
            }
        }

        fun requestsPerConnection(): List<Int> = synchronized(requestCounts) { requestCounts.map { it.get() } }

        fun awaitClosedConnections(count: Int) {
            val deadline = System.nanoTime() + 5_000_000_000
            while (closedConnections.get() < count) {
                check(System.nanoTime() < deadline) { "the server did not close $count connection(s)" }
                Thread.sleep(10)
            }
        }

        override fun close() {
            serverSocket.close()
        }
    }

    private fun HttpExchange.respond(body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/x-amz-json-1.0")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }
}
