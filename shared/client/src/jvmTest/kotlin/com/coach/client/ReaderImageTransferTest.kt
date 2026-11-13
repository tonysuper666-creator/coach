package com.coach.client

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.net.ServerSocket
import java.net.InetAddress
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

    @Test fun http10CloseResponsesStillReturnEveryImage() = runBlocking {
        val server=ServerSocket(0,3,InetAddress.getByName("127.0.0.1"))
        val responder=async(Dispatchers.IO) {
            try { repeat(3) { index -> server.accept().use { socket ->
                val input=socket.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { /* consume only the synthetic request headers */ }
                socket.getOutputStream().apply {
                    write("HTTP/1.0 200 OK\r\nContent-Length: 1\r\nConnection: close\r\n\r\n".toByteArray())
                    write(byteArrayOf((index+1).toByte()));flush()
                }
            } } } catch (error: java.net.SocketException) { if (isActive) throw error }
        }
        val client=CourseHttp("http://127.0.0.1:${server.localPort}","fake-device","synthetic-token","fake")
        try {
            withTimeout(5000) {
                for (page in 1..3) assertContentEquals(byteArrayOf(page.toByte()),client.reader.image("fake",page,"v1"))
                responder.await()
            }
        } finally { client.close();responder.cancel();server.close();responder.join() }
    }

    @Test fun repeatedCancelledBodiesDoNotBlockTheNextVisibleImage() = runBlocking {
        val releaseBodies=CountDownLatch(1)
        val executor=Executors.newCachedThreadPool { Thread(it,"reader-cancel-http").apply { isDaemon=true } }
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
            this.executor=executor
            createContext("/v1/courses/fake/reader/fake/pages/") { exchange ->
                try {
                    val page=exchange.requestURI.path.substringBeforeLast('/').substringAfterLast('/').toInt()
                    exchange.sendResponseHeaders(200,1);exchange.responseBody.flush()
                    if (page==1) check(releaseBodies.await(10,TimeUnit.SECONDS))
                    exchange.responseBody.write(byteArrayOf(page.toByte()))
                } finally { exchange.close() }
            };start()
        }
        val client=CourseHttp("http://127.0.0.1:${server.address.port}","fake-device","synthetic-token","fake")
        try {
            repeat(3) {
                val headers=CompletableDeferred<Unit>()
                val obsolete=async(Dispatchers.Default) { client.reader.image("fake",1,"v1") { stage -> if(stage=="http_headers_received") headers.complete(Unit) } }
                withTimeout(3000) { headers.await() }
                obsolete.cancelAndJoin()
                assertContentEquals(byteArrayOf(2),withTimeout(3000) { client.reader.image("fake",2,"v1") })
            }
        } finally { releaseBodies.countDown();client.close();server.stop(0);executor.shutdownNow() }
    }

    private suspend fun probeEcho(echo: String): Pair<ByteArray?,List<String>> {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
            createContext("/v1/courses/fake/reader/synthetic-baseline/pages/1/image") { exchange ->
                try {
                    check(exchange.requestURI.rawQuery=="version=v1&readerProbe=probe-1")
                    exchange.responseHeaders.set("X-Coach-Reader-Probe",echo)
                    exchange.sendResponseHeaders(200,1);exchange.responseBody.write(byteArrayOf(7))
                } finally { exchange.close() }
            };start()
        }
        val client=CourseHttp("http://127.0.0.1:${server.address.port}","fake-device","synthetic-token","fake")
        val stages=mutableListOf<String>()
        try { return client.reader.probeSyntheticImage(1,"probe-1") { stages+=it } to stages }
        finally { client.close();server.stop(0) }
    }

    @Test fun syntheticProbeRequiresItsOwnResponseEcho() = runBlocking {
        val (bytes,stages)=probeEcho("probe-1")
        assertContentEquals(byteArrayOf(7),bytes)
        assertTrue("http_probe_verified" in stages)
    }

    @Test fun anotherRequestsEchoCannotEstablishProbeSuccess() = runBlocking {
        val (bytes,stages)=probeEcho("some-other-request")
        assertNull(bytes)
        assertTrue("http_probe_mismatch" in stages)
        assertFalse("http_probe_verified" in stages)
    }
}
