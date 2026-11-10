package com.coach.ui

import com.coach.client.CourseHttp
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.Socket
import java.net.InetSocketAddress
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ReaderImageTransportTest {
    @Test fun separatesHeadersBodyAndSyntheticServerTime() = runBlocking(Dispatchers.Default) {
        val host = requireNotNull(System.getenv("COACH_READER_FIXTURE_HOST"))
        val port = requireNotNull(System.getenv("COACH_READER_FIXTURE_PORT")).toInt()
        val output = File(requireNotNull(System.getenv("COACH_READER_OBSERVED_OUTPUT")))
        val base = "http://$host:$port"
        require(Json.parseToJsonElement(URI("$base/health").toURL().readText()).jsonObject["syntheticOnly"]?.jsonPrimitive?.boolean == true)
        val client = CourseHttp(base, "baseline-device", "synthetic-baseline-token", "READER_BASELINE")
        ReaderPageTrace.start()
        File(output, "identity.json").writeText(ReaderPageTrace.snapshot().getValue("process").toString())
        try {
            val control = (1..4).map { page ->
                val connection = URI("$base/v1/courses/READER_BASELINE/reader/synthetic-baseline/pages/$page/image?version=v1").toURL().openConnection() as HttpURLConnection
                connection.setRequestProperty("Authorization", "Bearer synthetic-baseline-token")
                connection.setRequestProperty("X-Coach-Device", "baseline-device")
                connection.connectTimeout = 3000; connection.readTimeout = 15000
                try {
                    val start = ReaderPageTrace.nowNs()
                    assertEquals(200, connection.responseCode)
                    val headers = ReaderPageTrace.nowNs()
                    val bytes = connection.inputStream.use { it.readBytes() }
                    buildJsonObject { put("startNs", start); put("headersNs", headers); put("endNs", ReaderPageTrace.nowNs()); put("bytes", bytes.size) }
                } finally { connection.disconnect() }
            }
            val report = measureSyntheticReaderTransport(client.reader)
            val sockets=(1..4).map { page -> socketProbe(host,port,page) }
            File(output, "transport.json").writeText(JsonObject(report + mapOf(
                "urlConnectionControl" to JsonArray(control), "socketControl" to JsonArray(sockets),
                "probeOrder" to JsonPrimitive("URL4,CIO12,socket4"))).toString())
        } finally {
            File(output, "trace.json").writeText(ReaderPageTrace.snapshot().toString())
            ReaderPageTrace.stop(); client.close()
        }
    }

    private fun socketProbe(host: String,port: Int,page: Int): JsonObject = Socket().use { socket ->
        socket.soTimeout=15_000
        val start=ReaderPageTrace.nowNs()
        socket.connect(InetSocketAddress(host,port),3000)
        val connected=ReaderPageTrace.nowNs()
        val request="GET /v1/courses/READER_BASELINE/reader/synthetic-baseline/pages/$page/image?version=v1 HTTP/1.1\r\nHost: $host:$port\r\nAuthorization: Bearer synthetic-baseline-token\r\nX-Coach-Device: baseline-device\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII));flush() }
        val written=ReaderPageTrace.nowNs()
        val input=socket.getInputStream().buffered()
        val header=ByteArrayOutputStream()
        var value=input.read()
        val firstByte=ReaderPageTrace.nowNs()
        var end=0
        while(value>=0) {
            header.write(value);end=(end shl 8) or value
            if(end==0x0d0a0d0a) break
            value=input.read()
        }
        val headers=ReaderPageTrace.nowNs()
        val text=header.toString("US-ASCII")
        check(text.startsWith("HTTP/1.0 200 ") || text.startsWith("HTTP/1.1 200 "))
        val expected=text.lineSequence().first { it.startsWith("Content-Length:",ignoreCase=true) }.substringAfter(':').trim().toInt()
        val bytes=input.readBytes()
        check(bytes.size==expected && bytes.size>8 && bytes[0]==0x89.toByte())
        buildJsonObject {
            put("page",page);put("startNs",start);put("connectedNs",connected);put("requestWrittenNs",written)
            put("firstByteNs",firstByte);put("headersNs",headers);put("endNs",ReaderPageTrace.nowNs())
            put("bytes",bytes.size);put("clientTcpNoDelayEnabled",socket.tcpNoDelay);put("freshConnection",true)
        }
    }
}
