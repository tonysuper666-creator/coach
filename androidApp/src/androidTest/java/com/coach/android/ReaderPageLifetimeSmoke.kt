package com.coach.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coach.client.CourseHttp
import com.coach.ui.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderPageLifetimeSmoke {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun bookVersionProfileAndCloseDuringDecode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.coach.readermeasurement")
        val args = InstrumentationRegistry.getArguments()
        val port = requireNotNull(args.getString("readerPort")).toInt()
        require(port in 1024..65535 && port !in setOf(8790,8791,18791,3790))
        val base = "http://${BuildConfig.COACH_TAIL_HOST}:$port"
        require(JSONObject(URL("$base/health").readText()).getBoolean("syntheticOnly"))
        val client = CourseHttp(base,"baseline-device","synthetic-baseline-token","READER_BASELINE")
        val cases = JSONArray()
        var pause: ReaderDecodePause? = null
        ReaderPageTrace.start()
        fun trace() = JSONObject(ReaderPageTrace.snapshot().toString())
        fun events(): List<JSONObject> { val rows=trace().getJSONArray("events"); return (0 until rows.length()).map { rows.getJSONObject(it) } }
        fun count(kind: String) = events().count { it.getString("kind") == kind }
        fun click(tag: String) {
            compose.waitUntil(timeoutMillis=15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag(tag).performTouchInput { click() }
        }
        fun pixels(colorPage: Int, after: Long=0, page: Int=1): Long {
            compose.waitUntil(timeoutMillis=15_000) {
                val drawn=events().lastOrNull { it.getString("kind")=="target_draw_commands" && it.getInt("page")==page }?.optLong("image") ?: 0
                if (drawn<=after || compose.onAllNodesWithTag("private-page-$page").fetchSemanticsNodes().size != 1) false
                else (compose.onNodeWithTag("private-page-$page").captureToImage().asAndroidBitmap().getPixel(2,2) and 0xffffff) ==
                    (android.graphics.Color.rgb(210+colorPage,230-colorPage,190+colorPage) and 0xffffff)
            }
            compose.waitUntil(timeoutMillis=15_000) {
                compose.onAllNodesWithTag("private-document-back").fetchSemanticsNodes().singleOrNull()?.let { !it.config.contains(SemanticsProperties.Disabled) }==true
            }
            return events().last { it.getString("kind")=="target_draw_commands" }.getLong("image")
        }
        fun drained() {
            compose.waitUntil(timeoutMillis=15_000) { trace().getInt("compositionOwners")==0 && count("window_opened")==count("window_drained") }
        }
        fun close() { click("private-document-back"); compose.waitUntil(timeoutMillis=15_000) { compose.onAllNodesWithTag("private-document-back").fetchSemanticsNodes().isEmpty() }; drained() }
        fun record(name: String, image: Long=0) { cases.put(JSONObject().put("case",name).put("passed",true).put("image",image)) }
        fun stage(name: String) {
            ReaderPageTrace.checkpoint(name)
            File(context.cacheDir,"reader-stage.json").writeText(JSONObject().put("runId",args.getString("readerRunId") ?: "manual").put("stage",name).toString())
            File(context.cacheDir,"reader-lifetime-$name.json").writeText(trace().toString())
            Thread.sleep(1000) // separate memory observation; no performance samples in this suite
        }
        try {
            compose.setContent { CoachTheme(false) { Surface { ReaderLifetimeExerciseSurface(client.reader) } } }
            compose.waitUntil(timeoutMillis=15_000) { compose.onAllNodesWithTag("lifetime-open").fetchSemanticsNodes().size==1 }
            stage("baseline_idle")
            click("lifetime-open"); var image=pixels(1); record("book_a",image)
            compose.waitUntil(timeoutMillis=15_000) { count("prefetch_decode_ready")>=1 }
            stage("book_a_ready")
            click("private-next"); pixels(2,page=2)
            compose.waitUntil(timeoutMillis=15_000) { events().any { it.getString("kind")=="prefetch_decode_ready" && it.getInt("page")==3 } }
            stage("three_pages_ready")
            click("private-prev"); image=pixels(1)
            click("lifetime-other"); image=pixels(3,image); record("switch_book_b",image)
            click("lifetime-open"); image=pixels(1,image); record("restore_book_a",image)
            val control=URL("$base/test/version").openConnection() as HttpURLConnection
            control.requestMethod="POST"; control.doOutput=true
            control.setRequestProperty("Authorization","Bearer synthetic-baseline-token")
            try { control.outputStream.use { it.write("v2".toByteArray()) }; assertEquals(200,control.responseCode); control.inputStream.close() }
            finally { control.disconnect() }
            click("lifetime-version"); image=pixels(2,image); record("version_changed",image)
            click("lifetime-density"); image=pixels(2,image); record("rendering_changed",image)
            close(); record("closed"); stage("after_close")
            pause=ReaderPageTrace.pauseNextDecode(); click("lifetime-open")
            // Let input commit and the worker reach its pause before blocking the test dispatcher.
            compose.waitUntil(timeoutMillis=15_000) {
                compose.onAllNodesWithTag("private-document-back").fetchSemanticsNodes().size==1 &&
                    events().any { it.getString("kind")=="decode_paused_before_native" }
            }
            val late=runBlocking { withTimeout(15_000) { pause!!.awaitEntered() } }
            click("private-document-back")
            compose.waitUntil(timeoutMillis=15_000) { compose.onAllNodesWithTag("private-document-back").fetchSemanticsNodes().isEmpty() }
            assertEquals(1,trace().getInt("decodeInFlight"))
            stage("closed_decode_paused")
            pause!!.resume(); drained()
            assertFalse(events().any { it.getString("kind")=="target_draw_commands" && it.getLong("image")==late })
            assertTrue(events().any { it.getString("kind")=="release_unused_bitmap" && it.getLong("image")==late })
            record("cancelled_decode",late)
            click("lifetime-open"); image=pixels(2,late); record("reopened",image)
            close(); stage("final_closed")
            System.gc(); System.runFinalization(); System.gc(); stage("after_gc_request")
        } finally {
            pause?.resume()
            File(context.cacheDir,"reader-lifecycle-cases.json").writeText(JSONObject().put("cases",cases).put("barrierBoundary","before JNI inside noninterruptible decode scope").put("physicalMemoryBudgetVerified",false).put("gpuReleaseVerified",false).toString())
            File(context.cacheDir,"reader-observed.json").writeText(trace().toString())
            ReaderPageTrace.stop(); client.close()
        }
    }
}
