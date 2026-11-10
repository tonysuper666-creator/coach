package com.coach.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coach.client.CourseHttp
import com.coach.ui.ReaderPageTrace
import com.coach.ui.measureSyntheticReaderTransport
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
            File(context.cacheDir,"reader-transport.json").writeText(report.toString())
        } finally {
            File(context.cacheDir,"reader-observed.json").writeText(ReaderPageTrace.snapshot().toString())
            ReaderPageTrace.stop(); client.close()
        }
    }
}
