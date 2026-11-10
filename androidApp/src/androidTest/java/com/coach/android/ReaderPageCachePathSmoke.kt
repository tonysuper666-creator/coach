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
import com.coach.ui.ReaderCacheExerciseSurface
import com.coach.ui.ReaderPageTrace
import java.io.File
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderPageCachePathSmoke {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun readerPrefetchAndQuestionSourceReuseDecodedRaster() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.coach.readermeasurement")
        val port = requireNotNull(InstrumentationRegistry.getArguments().getString("readerPort")).toInt()
        require(port in 1024..65535 && port !in setOf(8790, 8791, 18791, 3790))
        val base = "http://${BuildConfig.COACH_TAIL_HOST}:$port"
        require(JSONObject(URL("$base/health").readText()).getBoolean("syntheticOnly"))
        val client = CourseHttp(base, "baseline-device", "synthetic-baseline-token", "READER_BASELINE")
        ReaderPageTrace.start()
        fun count(kind: String, page: Int): Int {
            val events = JSONObject(ReaderPageTrace.snapshot().toString()).getJSONArray("events")
            return (0 until events.length()).count { events.getJSONObject(it).let { e -> e.getString("kind") == kind && e.getInt("page") == page } }
        }
        try {
            compose.setContent { CoachTheme(false) { Surface { ReaderCacheExerciseSurface(client.reader) } } }
            fun click(tag: String) {
                compose.waitUntil(timeoutMillis = 15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.let { !it.config.contains(SemanticsProperties.Disabled) } == true }
                compose.onNodeWithTag(tag).performTouchInput { click() }
            }
            fun pixels(page: Int) {
                compose.waitUntil(timeoutMillis = 15_000) {
                    if (compose.onAllNodesWithTag("private-page-$page").fetchSemanticsNodes().size != 1) false
                    else (compose.onNodeWithTag("private-page-$page").captureToImage().asAndroidBitmap().getPixel(2, 2) and 0xffffff) ==
                        (android.graphics.Color.rgb(210 + page, 230 - page, 190 + page) and 0xffffff)
                }
                ReaderPageTrace.pixelsObserved(page)
            }
            click("private-book-synthetic-baseline"); pixels(1)
            compose.waitUntil(timeoutMillis = 15_000) { count("prefetch_decode_ready", 2) == 1 }
            click("private-next"); pixels(2)
            assertEquals(1, count("request_started", 2)); assertEquals(1, count("prefetch_decode_ready", 2)); assertEquals(1, count("lookup_hit", 2))
            click("cache-citation"); pixels(1)
            assertEquals(1, count("request_started", 1)); assertEquals(1, count("lookup_hit", 1))
            val sourceBack = hasTestTag("private-document-back") and hasAnyAncestor(hasTestTag("private-source-dialog"))
            compose.waitUntil(timeoutMillis = 15_000) { compose.onAllNodes(sourceBack).fetchSemanticsNodes().singleOrNull()?.let { !it.config.contains(SemanticsProperties.Disabled) } == true }
            compose.onNode(sourceBack).performTouchInput { click() }
            pixels(2)
            assertEquals(1, count("request_started", 2))
            click("private-document-back")
            compose.waitUntil(timeoutMillis = 15_000) { compose.onAllNodesWithTag("private-page-2").fetchSemanticsNodes().isEmpty() }
            assertEquals(0, JSONObject(ReaderPageTrace.snapshot().toString()).getInt("compositionOwners"))
        } finally {
            File(context.cacheDir, "reader-observed.json").writeText(ReaderPageTrace.snapshot().toString())
            ReaderPageTrace.stop(); client.close()
        }
    }
}
