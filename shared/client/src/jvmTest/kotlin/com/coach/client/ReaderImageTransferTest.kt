package com.coach.client

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class ReaderImageTransferTest {
    private suspend fun transfer(cancel: Boolean) = coroutineScope {
        val body = byteArrayOf(1, 2, 3, 4)
        val sendBody = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "synthetic-reader-http").apply { isDaemon = true } }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/v1/courses/fake/reader/fake/pages/1/image") { exchange ->
                try {
                    check(exchange.requestURI.rawQuery == "version=v1")
                    check(exchange.requestHeaders.getFirst("Authorization") == "Bearer synthetic-token")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.flush()
                    check(sendBody.await(5, TimeUnit.SECONDS))
                    exchange.responseBody.write(body)
                } finally { exchange.close() }
            }
            start()
        }
        val client = CourseHttp("http://127.0.0.1:${server.address.port}", "fake-device", "synthetic-token", "fake")
        val stages = Channel<String>(Channel.UNLIMITED)
        try {
            val request = async(Dispatchers.Default) { client.reader.image("fake", 1, "v1") { stages.trySend(it) } }
            withTimeout(4000) {
                assertEquals("http_call_started", stages.receive())
                assertEquals("http_headers_received", stages.receive())
            }
            assertFalse(request.isCompleted, "headers must be observed before a delayed body is released")
            if (cancel) {
                request.cancelAndJoin()
                assertTrue(request.isCancelled)
                assertEquals("http_cancelled", withTimeout(3000) { stages.receive() })
            } else {
                sendBody.countDown()
                assertContentEquals(body, withTimeout(3000) { request.await() })
                assertEquals("http_body_received", withTimeout(3000) { stages.receive() })
            }
        } finally { sendBody.countDown(); client.close(); server.stop(0); executor.shutdownNow() }
    }

    @Test fun headersAndBodyHaveIndependentBoundaries() = runBlocking { transfer(cancel = false) }
    @Test fun cancellationWhileWaitingForBodyDoesNotReturnPartialImageBytes() = runBlocking { transfer(cancel = true) }
}
