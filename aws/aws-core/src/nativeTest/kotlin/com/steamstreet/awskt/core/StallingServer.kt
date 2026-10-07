package com.steamstreet.awskt.core

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import platform.posix.AF_INET
import platform.posix.AI_PASSIVE
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.close
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.socket
import platform.posix.usleep
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random

/**
 * A plain-HTTP server on 127.0.0.1 for native tests. It answers every request with a 404, keeps
 * connections alive, and holds the first request for [stallFirstMillis] before answering it, which
 * is how an AWS endpoint that stalls looks to the client.
 *
 * Blocking POSIX calls on a small thread pool: enough for a handful of connections, and the threads
 * end with the test process.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class StallingServer(private val stallFirstMillis: Long) {
    private val received = AtomicInt(0)
    private val answeredCount = AtomicInt(0)
    private val scope = CoroutineScope(newFixedThreadPoolContext(6, "stalling-server"))
    private val listener: Int
    val port: Int

    /** Requests received so far. */
    val requests: Int get() = received.load()

    /** Requests answered so far. */
    val answered: Int get() = answeredCount.load()

    init {
        var bound: Pair<Int, Int>? = null
        repeat(50) {
            if (bound == null) bound = tryBind(Random.nextInt(20_000, 60_000))
        }
        val (fd, chosen) = bound ?: error("Could not bind a local port")
        listener = fd
        port = chosen
        check(listen(listener, 16) == 0) { "listen failed" }
        scope.launch { acceptLoop() }
    }

    private fun tryBind(candidate: Int): Pair<Int, Int>? = memScoped {
        val hints = alloc<addrinfo>().apply {
            ai_family = AF_INET
            ai_socktype = SOCK_STREAM
            ai_flags = AI_PASSIVE
        }
        val result = allocPointerTo<addrinfo>()
        if (getaddrinfo("127.0.0.1", candidate.toString(), hints.ptr, result.ptr) != 0) return null
        val info = result.value!!.pointed
        val fd = socket(info.ai_family, info.ai_socktype, info.ai_protocol)
        val one = alloc<IntVar>().apply { value = 1 }
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, 4u.convert())
        val ok = bind(fd, info.ai_addr, info.ai_addrlen) == 0
        freeaddrinfo(result.value)
        if (ok) fd to candidate else {
            close(fd)
            null
        }
    }

    private fun acceptLoop() {
        while (true) {
            val connection = accept(listener, null, null)
            if (connection < 0) return
            scope.launch { serve(connection) }
        }
    }

    /** Answers each request on the connection in turn, until the client closes it. */
    private fun serve(connection: Int) {
        val pending = StringBuilder()
        val buffer = ByteArray(8192)
        try {
            while (true) {
                // Read up to the end of the headers, then the body Content-Length names.
                var headerEnd = pending.indexOf("\r\n\r\n")
                while (headerEnd < 0) {
                    val n = recv(connection, buffer.refTo(0), buffer.size.convert(), 0).toInt()
                    if (n <= 0) return
                    pending.append(buffer.decodeToString(0, n))
                    headerEnd = pending.indexOf("\r\n\r\n")
                }
                val headers = pending.substring(0, headerEnd)
                val length = Regex("(?im)^content-length:\\s*(\\d+)").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
                while (pending.length < headerEnd + 4 + length) {
                    val n = recv(connection, buffer.refTo(0), buffer.size.convert(), 0).toInt()
                    if (n <= 0) return
                    pending.append(buffer.decodeToString(0, n))
                }
                pending.deleteRange(0, headerEnd + 4 + length)

                if (received.incrementAndFetch() == 1) usleep((stallFirstMillis * 1000).convert())
                val body = """{"error":"not_found"}"""
                val response = "HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${body.length}\r\nConnection: keep-alive\r\n\r\n$body"
                val bytes = response.encodeToByteArray()
                send(connection, bytes.refTo(0), bytes.size.convert(), 0)
                answeredCount.incrementAndFetch()
            }
        } finally {
            close(connection)
        }
    }
}
