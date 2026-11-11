package com.coach.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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

/** One measured ready-hit next-page action; opening page one is explicit warmup. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderFirstPresentSmoke {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun onePrefetchedPageWithClockedFirstHardwareDraw() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ReaderMeasurementPackage.requireIsolated(context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val runId = requireNotNull(args.getString("readerRunId"))
        val port = requireNotNull(args.getString("readerPort")).toInt()
        require(port in 1024..65535 && port !in setOf(8790,8791,18791,3790))
        val base = "http://${BuildConfig.COACH_TAIL_HOST}:$port"
        require(JSONObject(URL("$base/health").readText()).getBoolean("syntheticOnly"))
        val client = CourseHttp(base,"baseline-device","synthetic-baseline-token","READER_BASELINE")
        val showReader = mutableStateOf(true)
        var testFailure: Throwable? = null
        ReaderPageTrace.start()
        fun events() = JSONObject(ReaderPageTrace.snapshot().toString()).getJSONArray("events").let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }
        }
        fun click(tag: String) {
            compose.waitUntil(timeoutMillis=15_000) {
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.let { !it.config.contains(SemanticsProperties.Disabled) } == true
            }
            compose.onNodeWithTag(tag).performTouchInput { click() }
        }
        fun pixels(page: Int) {
            compose.waitUntil(timeoutMillis=15_000) {
                compose.onAllNodesWithTag("private-page-$page").fetchSemanticsNodes().size == 1 &&
                    (compose.onNodeWithTag("private-page-$page").captureToImage().asAndroidBitmap().getPixel(2,2) and 0xffffff) ==
                    (android.graphics.Color.rgb(210+page,230-page,190+page) and 0xffffff)
            }
            ReaderPageTrace.pixelsObserved(page)
        }
        try {
            compose.setContent {
                if (showReader.value) CoachTheme(false) { Surface { ReaderMeasurementSurface(client.reader) } }
            }
            click("baseline-source"); pixels(1)
            compose.waitUntil(timeoutMillis=15_000) { events().any { it.getString("kind")=="prefetch_decode_ready" && it.getInt("page")==2 } }
            click("private-next"); pixels(2)
            compose.waitUntil(timeoutMillis=15_000) {
                val input=events().single { it.getString("kind")=="input_next" }
                events().any { it.getString("kind")=="target_window_draw_commands" && it.getLong("action")==input.getLong("action") }
            }
            val recorded = events()
            val input = recorded.single { it.getString("kind")=="input_next" }
            val draw = recorded.first { it.getString("kind")=="target_window_draw_commands" && it.getLong("action")==input.getLong("action") }
            assertTrue(recorded.any { it.getString("kind")=="lookup_hit" && it.getLong("action")==input.getLong("action") })
            for (event in listOf(input,draw)) {
                assertTrue(event.getBoolean("traceMarkerWritten"))
                assertTrue(event.getLong("elapsedRealtimeNanos")<=event.getLong("markerBeforeElapsedRealtimeNanos"))
                assertTrue(event.getLong("markerBeforeElapsedRealtimeNanos")<=event.getLong("markerAfterElapsedRealtimeNanos"))
            }
        } catch (error: Throwable) {
            testFailure = error
            throw error
        } finally {
            try {
                // ComposeTestRule permits one setContent call. Remove the existing
                // reader on the UI thread so its normal disposal still runs.
                compose.runOnIdle { showReader.value = false }
                compose.waitUntil(timeoutMillis=15_000) {
                    val snapshot=JSONObject(ReaderPageTrace.snapshot().toString())
                    snapshot.getInt("compositionOwners")==0 && snapshot.getInt("decodeInFlight")==0 &&
                        events().count { it.getString("kind")=="window_opened" }==events().count { it.getString("kind")=="window_drained" }
                }
            } catch (error: Throwable) {
                val original = testFailure
                if (original == null) throw error else original.addSuppressed(error)
            } finally {
                try {
                    File(context.cacheDir,"reader-observed.json").writeText(
                        JSONObject(ReaderPageTrace.snapshot().toString())
                            .put("runId",runId).put("applicationPackage",context.packageName).toString()
                    )
                } finally {
                    ReaderPageTrace.stop(); client.close()
                }
            }
        }
    }
}
