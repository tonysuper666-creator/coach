package com.coach.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coach.client.CourseHttp
import com.coach.ui.ReaderPageTrace
import com.coach.ui.measureSyntheticReaderTransport
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.Socket
import java.net.InetSocketAddress
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderImageTransportSmoke {
    @Test fun separatesHeadersBodyAndSyntheticServerTime() = runBlocking(Dispatchers.Default) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.coach.readermeasurement")
        val port = requireNotNull(InstrumentationRegistry.getArguments().getString("readerPort")).toInt()
        require(port in 1024..65535 && port !in setOf(8790,8791,18791,3790))
        val base = "http://${BuildConfig.COACH_TAIL_HOST}:$port"
        require(JSONObject(URL("$base/health").readText()).getBoolean("syntheticOnly"))
        val client = CourseHttp(base, "baseline-device", "synthetic-baseline-token", "READER_BASELINE")
        ReaderPageTrace.start()
        try {
            val controls = JSONArray()
            for (page in 1..4) {
                val connection = URL("$base/v1/courses/READER_BASELINE/reader/synthetic-baseline/pages/$page/image?version=v1").openConnection() as HttpURLConnection
                connection.setRequestProperty("Authorization", "Bearer synthetic-baseline-token")
                connection.setRequestProperty("X-Coach-Device", "baseline-device")
                connection.connectTimeout=3000; connection.readTimeout=15000
                try {
                    val start=ReaderPageTrace.nowNs()
                    assertEquals(200, connection.responseCode)
                    val headers=ReaderPageTrace.nowNs()
                    val bytes=connection.inputStream.use { it.readBytes() }
                    controls.put(JSONObject().put("startNs",start).put("headersNs",headers).put("endNs",ReaderPageTrace.nowNs()).put("bytes",bytes.size))
                } finally { connection.disconnect() }
            }
            val report=JSONObject(measureSyntheticReaderTransport(client.reader).toString()).put("urlConnectionControl",controls)
            val sockets=JSONArray()
            for(page in 1..4) sockets.put(socketProbe(BuildConfig.COACH_TAIL_HOST,port,page))
            report.put("socketControl",sockets).put("probeOrder","URL4,CIO12,socket4")
            File(context.cacheDir,"reader-transport.json").writeText(report.toString())
        } finally {
            File(context.cacheDir,"reader-observed.json").writeText(ReaderPageTrace.snapshot().toString())
            ReaderPageTrace.stop(); client.close()
        }
    }

    private fun socketProbe(host: String,port: Int,page: Int): JSONObject = Socket().use { socket ->
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
        JSONObject().put("page",page).put("startNs",start).put("connectedNs",connected).put("requestWrittenNs",written)
            .put("firstByteNs",firstByte).put("headersNs",headers).put("endNs",ReaderPageTrace.nowNs())
            .put("bytes",bytes.size).put("clientTcpNoDelayEnabled",socket.tcpNoDelay).put("freshConnection",true)
    }
}
