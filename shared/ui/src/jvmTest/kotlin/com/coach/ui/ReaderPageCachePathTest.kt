package com.coach.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import com.coach.client.CourseHttp
import java.io.File
import java.net.URL
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ReaderPageCachePathTest {
    @Test fun nativeReaderPrefetchAndQuestionSourceReuseDecodedPixels() {
        val port = requireNotNull(System.getenv("COACH_READER_FIXTURE_PORT")).toInt()
        require(port in 1024..65535 && port !in setOf(8790, 8791, 18791, 3790))
        val host = System.getenv("COACH_READER_FIXTURE_HOST") ?: "127.0.0.1"
        require(host.matches(Regex("[0-9]+(\\.[0-9]+){3}")))
        val base = "http://$host:$port"
        require(Json.parseToJsonElement(URL("$base/health").readText()).jsonObject["syntheticOnly"]?.jsonPrimitive?.boolean == true)
        val output = File(System.getenv("COACH_READER_OBSERVED_OUTPUT") ?: "build/reader-cache-path").also { it.mkdirs() }
        val client = CourseHttp(base, "baseline-device", "synthetic-baseline-token", "READER_BASELINE")
        ReaderPageTrace.start()
        File(output, "identity.json").writeText(ReaderPageTrace.snapshot().getValue("process").toString())
        fun events() = ReaderPageTrace.snapshot().getValue("events").jsonArray.map { it.jsonObject }
        fun count(kind: String, page: Int) = events().count { it["kind"]?.jsonPrimitive?.content == kind && it["page"]?.jsonPrimitive?.int == page }
        try {
            runDesktopComposeUiTest(1000, 900) {
                setContent { CoachTheme(false) { Surface { ReaderCacheExerciseSurface(client.reader) } } }
                fun click(tag: String) {
                    waitUntil(timeoutMillis = 15_000) {
                        onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.let { !it.config.contains(SemanticsProperties.Disabled) } == true
                    }
                    onNodeWithTag(tag).performMouseInput { click() }
                }
                fun pixels(page: Int) {
                    waitUntil(timeoutMillis = 15_000) {
                        if (onAllNodesWithTag("private-page-$page").fetchSemanticsNodes().size != 1) false
                        else (onNodeWithTag("private-page-$page").captureToImage().toAwtImage().getRGB(2, 2) and 0xffffff) ==
                            (java.awt.Color(210 + page, 230 - page, 190 + page).rgb and 0xffffff)
                    }
                    ReaderPageTrace.pixelsObserved(page)
                }
                click("private-book-synthetic-baseline"); pixels(1)
                waitUntil(timeoutMillis = 15_000) { count("prefetch_decode_ready", 2) == 1 }
                click("private-next"); pixels(2)
                assertEquals(1, count("request_started", 2))
                assertEquals(1, count("prefetch_decode_ready", 2))
                assertEquals(1, count("lookup_hit", 2))
                click("cache-citation"); pixels(1)
                assertEquals(1, count("request_started", 1), "citation must reuse the existing raster")
                assertEquals(1, count("lookup_hit", 1))
                assertEquals(1, count("state_reused", 1))
                val sourceBack = hasTestTag("private-document-back") and hasAnyAncestor(hasTestTag("private-source-dialog"))
                waitUntil(timeoutMillis = 15_000) { onAllNodes(sourceBack).fetchSemanticsNodes().singleOrNull()?.let { !it.config.contains(SemanticsProperties.Disabled) } == true }
                onNode(sourceBack).performMouseInput { click() }
                pixels(2)
                assertEquals(1, count("request_started", 2), "closing the source restores the parent's existing raster")
                // Closing must remain possible after another turn; a stale revision leaves a pending conflict.
                click("private-next"); pixels(3)
                click("private-document-back")
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("private-page-2").fetchSemanticsNodes().isEmpty() && onAllNodesWithTag("private-page-3").fetchSemanticsNodes().isEmpty() }
            }
            assertEquals(0, ReaderPageTrace.snapshot().getValue("compositionOwners").jsonPrimitive.int)
            assertTrue(ReaderPageTrace.snapshot().getValue("complete").jsonPrimitive.boolean)
        } finally {
            File(output, "trace.json").writeText(ReaderPageTrace.snapshot().toString())
            ReaderPageTrace.stop(); client.close()
        }
    }
}
