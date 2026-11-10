package com.coach.ui

import com.coach.client.CourseHttp
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
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
            File(output, "transport.json").writeText(JsonObject(report + ("urlConnectionControl" to JsonArray(control))).toString())
        } finally {
            File(output, "trace.json").writeText(ReaderPageTrace.snapshot().toString())
            ReaderPageTrace.stop(); client.close()
        }
    }
}
