package com.coach.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coach.client.CourseHttp
import com.coach.ui.CoachTheme
import com.coach.ui.ReaderMeasurementSurface
import com.coach.ui.ReaderPageTrace
import java.io.File
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the existing ui-test-manifest Activity, an isolated package and a synthetic-only HTTP fixture. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderPageObservedSmoke {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun twentyForwardReverseAndCitationWithLifetimeCheckpoints() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName == "com.coach.readermeasurement") { "Use the isolated measurement applicationId" }
        val args = InstrumentationRegistry.getArguments()
        val port = requireNotNull(args.getString("readerPort")).toInt()
        require(port in 1024..65535 && port !in setOf(8790, 8791, 18791, 3790))
        // The existing generated network policy permits only this configured host. No policy override.
        val base = "http://${BuildConfig.COACH_TAIL_HOST}:$port"
        require(JSONObject(URL("$base/health").readText()).getBoolean("syntheticOnly"))
        val client = CourseHttp(base, "baseline-device", "synthetic-baseline-token", "READER_BASELINE")
        val report = File(context.cacheDir, "reader-observed.json")
        ReaderPageTrace.start()
        try {
            compose.setContent { CoachTheme(false) { Surface { ReaderMeasurementSurface(client.reader) } } }
            fun clickAndObserve(tag: String, page: Int) {
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.let { !it.config.contains(SemanticsProperties.Disabled) } == true
                }
                compose.onNodeWithTag(tag).performTouchInput { click() }
                compose.waitUntil(15_000) {
                    if (compose.onAllNodesWithTag("private-page-$page").fetchSemanticsNodes().isEmpty()) false
                    else {
                        val pixels = compose.onNodeWithTag("private-page-$page").captureToImage().asAndroidBitmap()
                        val expected = android.graphics.Color.rgb(210 + page, 230 - page, 190 + page) and 0xffffff
                        (pixels.getPixel(2, 2) and 0xffffff) == expected
                    }
                }
                ReaderPageTrace.pixelsObserved(page)
            }
            fun close() {
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithTag("private-document-back").fetchSemanticsNodes().singleOrNull()?.let { !it.config.contains(SemanticsProperties.Disabled) } == true
                }
                compose.onNodeWithTag("private-document-back").performTouchInput { click() }
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("private-page-1").fetchSemanticsNodes().isEmpty() }
                compose.waitUntil(15_000) {
                    val snapshot=JSONObject(ReaderPageTrace.snapshot().toString())
                    val events=snapshot.getJSONArray("events")
                    fun count(kind: String)=(0 until events.length()).count { events.getJSONObject(it).getString("kind")==kind }
                    snapshot.getInt("compositionOwners")==0 && count("window_opened")==count("window_drained")
                }
            }
            clickAndObserve("baseline-source", 1)
            for (page in 2..21) clickAndObserve("private-next", page)
            for (page in 20 downTo 1) clickAndObserve("private-prev", page)
            close()
            clickAndObserve("baseline-source", 1)
            ReaderPageTrace.checkpoint("before_close")
            File(context.cacheDir, "reader-before-close.json").writeText(ReaderPageTrace.snapshot().toString())
            fun stage(label: String) {
                File(context.cacheDir, "reader-stage.json").writeText(JSONObject()
                    .put("runId", args.getString("readerRunId") ?: "manual").put("stage", label).toString())
            }
            stage("before_close")
            Thread.sleep(600) // outside the 42 latency samples
            close()
            ReaderPageTrace.checkpoint("reader_closed")
            File(context.cacheDir, "reader-closed.json").writeText(ReaderPageTrace.snapshot().toString())
            stage("after_close")
            Thread.sleep(600)
            System.gc(); System.runFinalization(); System.gc()
            ReaderPageTrace.checkpoint("after_gc_request")
            stage("after_gc_request")
            Thread.sleep(600)
            val trace = JSONObject(ReaderPageTrace.snapshot().toString())
            assertTrue(trace.getBoolean("complete"))
            assertEquals(0, trace.getInt("compositionOwners"))
            val events = trace.getJSONArray("events")
            assertEquals(42, (0 until events.length()).count { events.getJSONObject(it).getString("kind") == "pixels_observed" })
        } finally {
            report.writeText(ReaderPageTrace.snapshot().toString())
            ReaderPageTrace.stop()
            client.close()
        }
    }
}
