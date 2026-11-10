package com.coach.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.AtomicFile
import com.coach.client.CourseHttp
import com.coach.ui.ReaderPageTrace
import com.coach.ui.measureSyntheticReaderTransport
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.Socket
import java.net.InetSocketAddress
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
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
        val runId=requireNotNull(InstrumentationRegistry.getArguments().getString("readerRunId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,48}")))
        require(port in 1024..65535 && port !in setOf(8790,8791,18791,3790))
        val base = "http://${BuildConfig.COACH_TAIL_HOST}:$port"
        require(JSONObject(URL("$base/health").readText()).getBoolean("syntheticOnly"))
        val routeControl=InstrumentationRegistry.getArguments().getString("readerRouteControl")
        require(routeControl == null || routeControl == "host-loopback")
        val watchMode=InstrumentationRegistry.getArguments().getString("readerQueueWatch")
        require(watchMode == null || (watchMode == "available" && routeControl != null))
        val scheduleMode=InstrumentationRegistry.getArguments().getString("readerSocketSchedule")
        require(scheduleMode == null || (scheduleMode == "enabled" && routeControl != null && watchMode == null))
        val suite=if (routeControl == null) "transport" else "route"
        val stageFile=AtomicFile(File(context.cacheDir,"reader-stage.json"))
        fun stage(name: String) {
            val data=JSONObject().put("runId",runId).put("suite",suite).put("pid",android.os.Process.myPid())
                .put("stage",name).put("deviceMonotonicNs",ReaderPageTrace.nowNs()).toString().toByteArray(Charsets.UTF_8)
            val stream=stageFile.startWrite()
            try { stream.write(data);stageFile.finishWrite(stream) }
            catch (error: Throwable) { stageFile.failWrite(stream);throw error }
        }
        val client = if (routeControl == null) CourseHttp(base, "baseline-device", "synthetic-baseline-token", "READER_BASELINE") else null
        var completed=false
        ReaderPageTrace.start()
        try {
            stage("transport_running") // Before probe timing; never retag a previous run's file.
            if (routeControl != null) {
                // Only raw diagnostic sockets use the fixed emulator host alias. The
                // app's cleartext allowlist and CourseHttp endpoint stay unchanged.
                val rows=JSONArray()
                val report=JSONObject().put("scope","route control only; no CIO or UI timing")
                    .put("socketControl",rows).put("routeControl","host-loopback")
                    .put("configuredHost",BuildConfig.COACH_TAIL_HOST).put("port",port)
                    .put("queueWatchMode",watchMode ?: "off")
                    .put("probeOrder","ABBA,BAAB,ABBA,BAAB; A=configured B=host-loopback")
                for (block in 1..4) {
                    val order=if (block % 2 == 1) listOf("configured","host-loopback","host-loopback","configured")
                        else listOf("host-loopback","configured","configured","host-loopback")
                    for (route in order) {
                        val sequence=rows.length()+1
                        val destination=if (route == "configured") BuildConfig.COACH_TAIL_HOST else "10.0.2.2"
                        rows.put(socketProbe(destination,port,block,"$runId-route-$sequence",BuildConfig.COACH_TAIL_HOST,watchMode!=null,scheduleMode!=null)
                            .put("route",route).put("block",block).put("sequence",sequence))
                        // Retain completed rows even if a later route is unavailable.
                        File(context.cacheDir,"reader-transport.json").writeText(report.toString())
                    }
                }
                completed=true
                return@runBlocking
            }
            val controls = JSONArray()
            for (page in 1..4) {
                val probe="$runId-url-$page"
                val connection = URL("$base/v1/courses/READER_BASELINE/reader/synthetic-baseline/pages/$page/image?version=v1&readerProbe=$probe").openConnection() as HttpURLConnection
                connection.setRequestProperty("Authorization", "Bearer synthetic-baseline-token")
                connection.setRequestProperty("X-Coach-Device", "baseline-device")
                connection.connectTimeout=3000; connection.readTimeout=15000
                try {
                    val start=ReaderPageTrace.nowNs()
                    assertEquals(200, connection.responseCode)
                    val headers=ReaderPageTrace.nowNs()
                    assertEquals(probe,connection.getHeaderField("X-Coach-Reader-Probe"))
                    val bytes=connection.inputStream.use { it.readBytes() }
                    controls.put(JSONObject().put("probeId",probe).put("responseEchoVerified",true)
                        .put("serverConnectionId",connection.getHeaderField("X-Coach-Reader-Connection").toInt())
                        .put("serverRequestOrdinal",connection.getHeaderField("X-Coach-Reader-Ordinal").toInt())
                        .put("startNs",start).put("headersNs",headers).put("endNs",ReaderPageTrace.nowNs()).put("bytes",bytes.size))
                } finally { connection.disconnect() }
            }
            val report=JSONObject(measureSyntheticReaderTransport(requireNotNull(client).reader,runId).toString()).put("urlConnectionControl",controls)
            val sockets=JSONArray()
            for(page in 1..4) sockets.put(socketProbe(BuildConfig.COACH_TAIL_HOST,port,page,"$runId-socket-$page"))
            report.put("socketControl",sockets).put("probeOrder","URL4,CIO12,socket4")
            File(context.cacheDir,"reader-transport.json").writeText(report.toString())
            completed=true
        } finally {
            try {
                stage(if (completed) "transport_complete" else "transport_failed")
                File(context.cacheDir,"reader-observed.json").writeText(ReaderPageTrace.snapshot().toString())
            } finally { ReaderPageTrace.stop();client?.close() }
        }
    }

    private fun socketProbe(host: String,port: Int,page: Int,probe: String,requestHost: String=host,watchQueue: Boolean=false,traceSchedule: Boolean=false): JSONObject = Socket().use { socket ->
        socket.soTimeout=15_000
        val start=ReaderPageTrace.nowNs()
        socket.connect(InetSocketAddress(host,port),3000)
        val connected=ReaderPageTrace.nowNs()
        val watchedInput=if (watchQueue) socket.getInputStream() else null
        val observer=watchedInput?.let { ReaderSocketQueueWatch(it) }
        try {
        observer?.start() // Joined readiness before write, outside write-to-read timing.
        val request="GET /v1/courses/READER_BASELINE/reader/synthetic-baseline/pages/$page/image?version=v1&readerProbe=$probe HTTP/1.1\r\nHost: $requestHost:$port\r\nAuthorization: Bearer synthetic-baseline-token\r\nX-Coach-Device: baseline-device\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII));flush() }
        val written=ReaderPageTrace.nowNs()
        observer?.written(written)
        val raw=watchedInput ?: socket.getInputStream()
        val header=ByteArrayOutputStream()
        val readRequested=ReaderPageTrace.nowNs()
        var value: Int
        val firstByte: Long
        val readTraceLabel=if (traceSchedule) "ReaderSocket/first-read/$probe" else null
        if (readTraceLabel!=null) {
            check(android.os.Trace.isEnabled()) { "requested socket trace is not enabled" }
            android.os.Trace.beginSection(readTraceLabel)
        }
        try { value=raw.read();firstByte=ReaderPageTrace.nowNs() }
        finally { if (readTraceLabel!=null) android.os.Trace.endSection();observer?.stop() }
        val input=raw.buffered()
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
        fun field(name: String)=text.lineSequence().first { it.startsWith("$name:",ignoreCase=true) }.substringAfter(':').trim()
        check(field("X-Coach-Reader-Probe")==probe)
        val bytes=input.readBytes()
        check(bytes.size==expected && bytes.size>8 && bytes[0]==0x89.toByte())
        val completed=ReaderPageTrace.nowNs() // Hashing is outside the transport interval.
        observer?.finish(socket)
        JSONObject().put("probeId",probe).put("responseEchoVerified",true)
            .put("bodySha256",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
            .put("destinationHost",host).put("destinationPort",port).put("requestHost",requestHost)
            .put("localPort",socket.localPort)
            .put("serverConnectionId",field("X-Coach-Reader-Connection").toInt())
            .put("serverRequestOrdinal",field("X-Coach-Reader-Ordinal").toInt())
            .put("readRequestedNs",readRequested).put("firstByteReadMode","unbuffered InputStream.read; not kernel receive")
            .put("page",page).put("startNs",start).put("connectedNs",connected).put("requestWrittenNs",written)
            .put("firstByteNs",firstByte).put("headersNs",headers).put("endNs",completed)
            .put("bytes",bytes.size).put("clientTcpNoDelayEnabled",socket.tcpNoDelay).put("freshConnection",true)
            .put("readTid",android.os.Process.myTid()).also { row ->
                observer?.let { row.put("queueWatch",it.report()) }
                readTraceLabel?.let { row.put("readTraceLabel",it) }
            }
        } finally { observer?.finish(socket) }
    }
}
