package com.coach.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import com.coach.client.CourseHttp
import java.io.File
import java.net.URI
import kotlinx.serialization.json.*
import kotlin.test.*

/** Supplemental observed baseline. The original ReaderPageBaselineTest and its fixture stay intact. */
@OptIn(ExperimentalTestApi::class)
class ReaderPageObservedTest {
    @Test fun observesRealClickHandlersDecodesDrawsAndClose() {
        val port = requireNotNull(System.getenv("COACH_READER_FIXTURE_PORT")).toInt()
        require(port in 1024..65535 && port !in setOf(8790, 8791, 18791, 3790))
        val host = System.getenv("COACH_READER_FIXTURE_HOST") ?: "127.0.0.1"
        require(host.matches(Regex("[0-9]+(\\.[0-9]+){3}")))
        val base = "http://$host:$port"
        val health = Json.parseToJsonElement(URI("$base/health").toURL().readText()).jsonObject
        require(health["syntheticOnly"]?.jsonPrimitive?.boolean == true)
        val output = File(System.getenv("COACH_READER_OBSERVED_OUTPUT") ?: "build/reader-observed").also { it.mkdirs() }
        val client = CourseHttp(base, "baseline-device", "synthetic-baseline-token", "READER_BASELINE")
        ReaderPageTrace.start()
        File(output, "identity.json").writeText(ReaderPageTrace.snapshot().getValue("process").toString())
        try {
            runDesktopComposeUiTest(1000, 900) {
                setContent { CoachTheme(false) { Surface { ReaderMeasurementSurface(client.reader) } } }
                fun clickAndObserve(tag: String, page: Int) {
                    waitUntil(timeoutMillis = 15_000) {
                        onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.let { !it.config.contains(SemanticsProperties.Disabled) } == true
                    }
                    onNodeWithTag(tag).performMouseInput { click() }
                    waitUntil(timeoutMillis = 15_000) {
                        if (onAllNodesWithTag("private-page-$page").fetchSemanticsNodes().isEmpty()) false
                        else {
                            val pixels = onNodeWithTag("private-page-$page").captureToImage().toAwtImage()
                            val expected = java.awt.Color(210 + page, 230 - page, 190 + page).rgb and 0xffffff
                            (pixels.getRGB(2, 2) and 0xffffff) == expected
                        }
                    }
                    ReaderPageTrace.pixelsObserved(page)
                }
                clickAndObserve("baseline-source", 1)
                for (page in 2..21) clickAndObserve("private-next", page)
                for (page in 20 downTo 1) clickAndObserve("private-prev", page)
                fun close() {
                    waitUntil(timeoutMillis = 15_000) {
                        onAllNodesWithTag("private-document-back").fetchSemanticsNodes().singleOrNull()?.let { !it.config.contains(SemanticsProperties.Disabled) } == true
                    }
                    onNodeWithTag("private-document-back").performMouseInput { click() }
                    waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("private-page-1").fetchSemanticsNodes().isEmpty() }
                }
                close()
                clickAndObserve("baseline-source", 1)
                ReaderPageTrace.checkpoint("before_close")
                File(output, "before-close.json").writeText(ReaderPageTrace.snapshot().toString())
                File(output, "stage.json").writeText(buildJsonObject {
                    put("runId", System.getenv("COACH_READER_RUN_ID") ?: "manual"); put("stage", "before_close")
                }.toString())
                Thread.sleep(600) // outside all latency samples, let the external PID sampler observe this phase
                close()
                ReaderPageTrace.checkpoint("reader_closed")
                File(output, "closed.json").writeText(ReaderPageTrace.snapshot().toString())
                File(output, "stage.json").writeText(buildJsonObject {
                    put("runId", System.getenv("COACH_READER_RUN_ID") ?: "manual"); put("stage", "after_close")
                }.toString())
                Thread.sleep(600)
            }
            // Diagnostic checkpoint outside all latency samples. A GC request is not a native-free assertion.
            System.gc(); System.runFinalization(); System.gc()
            ReaderPageTrace.checkpoint("after_gc_request")
            File(output, "stage.json").writeText(buildJsonObject {
                put("runId", System.getenv("COACH_READER_RUN_ID") ?: "manual"); put("stage", "after_gc_request")
            }.toString())
            Thread.sleep(600)
            val trace = ReaderPageTrace.snapshot()
            assertTrue(trace.getValue("complete").jsonPrimitive.boolean)
            assertEquals(0, trace.getValue("compositionOwners").jsonPrimitive.int)
            val events = trace.getValue("events").jsonArray
            assertEquals(42, events.count { it.jsonObject["kind"]?.jsonPrimitive?.content == "pixels_observed" })
        } finally {
            File(output, "trace.json").writeText(ReaderPageTrace.snapshot().toString())
            ReaderPageTrace.stop()
            client.close()
        }
    }
}
