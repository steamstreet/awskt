package com.steamstreet.awskt.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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

    private fun HttpExchange.respond(body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/x-amz-json-1.0")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }
}
