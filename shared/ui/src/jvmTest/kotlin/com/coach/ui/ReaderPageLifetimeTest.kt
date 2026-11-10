package com.coach.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import com.coach.client.CourseHttp
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ReaderPageLifetimeTest {
    @Test fun bookVersionProfileAndCloseDuringDecode() {
        val host=requireNotNull(System.getenv("COACH_READER_FIXTURE_HOST"))
        val port=requireNotNull(System.getenv("COACH_READER_FIXTURE_PORT")).toInt()
        val output=File(requireNotNull(System.getenv("COACH_READER_OBSERVED_OUTPUT")))
        val base="http://$host:$port"
        require(Json.parseToJsonElement(URI("$base/health").toURL().readText()).jsonObject["syntheticOnly"]?.jsonPrimitive?.boolean==true)
        val client=CourseHttp(base,"baseline-device","synthetic-baseline-token","READER_BASELINE")
        val cases=mutableListOf<JsonObject>()
        var pause: ReaderDecodePause?=null
        ReaderPageTrace.start()
        fun trace()=ReaderPageTrace.snapshot()
        fun events()=trace().getValue("events").jsonArray.map { it.jsonObject }
        fun count(kind: String)=events().count { it["kind"]?.jsonPrimitive?.content==kind }
        fun record(name: String,image: Long=0) { cases+=buildJsonObject { put("case",name);put("passed",true);put("image",image) } }
        fun stage(name: String) {
            ReaderPageTrace.checkpoint(name)
            File(output,"stage.json").writeText(buildJsonObject { put("runId",System.getenv("COACH_READER_RUN_ID") ?: "manual");put("stage",name) }.toString())
            File(output,"lifetime-$name.json").writeText(trace().toString())
            Thread.sleep(1000) // outside performance measurements
        }
        File(output,"identity.json").writeText(trace().getValue("process").toString())
        try {
            runDesktopComposeUiTest(1000,900) {
                setContent { CoachTheme(false) { Surface { ReaderLifetimeExerciseSurface(client.reader) } } }
                fun click(tag: String) {
                    waitUntil(timeoutMillis=15_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().size==1 }
                    onNodeWithTag(tag).performMouseInput { click() }
                }
                fun pixels(colorPage: Int,after: Long=0,page: Int=1): Long {
                    waitUntil(timeoutMillis=15_000) {
                        val drawn=events().lastOrNull { it["kind"]?.jsonPrimitive?.content=="target_draw_commands" && it["page"]?.jsonPrimitive?.int==page }?.get("image")?.jsonPrimitive?.long ?: 0
                        if (drawn<=after || onAllNodesWithTag("private-page-$page").fetchSemanticsNodes().size!=1) false
                        else (onNodeWithTag("private-page-$page").captureToImage().toAwtImage().getRGB(2,2) and 0xffffff)==
                            (java.awt.Color(210+colorPage,230-colorPage,190+colorPage).rgb and 0xffffff)
                    }
                    waitUntil(timeoutMillis=15_000) {
                        onAllNodesWithTag("private-document-back").fetchSemanticsNodes().singleOrNull()?.let { !it.config.contains(SemanticsProperties.Disabled) }==true
                    }
                    return events().last { it["kind"]?.jsonPrimitive?.content=="target_draw_commands" }.getValue("image").jsonPrimitive.long
                }
                fun drained() {
                    waitUntil(timeoutMillis=15_000) { trace().getValue("compositionOwners").jsonPrimitive.int==0 && count("window_opened")==count("window_drained") }
                }
                fun close() { click("private-document-back");waitUntil(timeoutMillis=15_000) { onAllNodesWithTag("private-document-back").fetchSemanticsNodes().isEmpty() };drained() }
                waitUntil(timeoutMillis=15_000) { onAllNodesWithTag("lifetime-open").fetchSemanticsNodes().size==1 }
                stage("baseline_idle")
                click("lifetime-open");var image=pixels(1);record("book_a",image)
                waitUntil(timeoutMillis=15_000) { count("prefetch_decode_ready")>=1 }
                stage("book_a_ready")
                click("private-next");pixels(2,page=2)
                waitUntil(timeoutMillis=15_000) { events().any { it["kind"]?.jsonPrimitive?.content=="prefetch_decode_ready" && it["page"]?.jsonPrimitive?.int==3 } }
                stage("three_pages_ready")
                click("private-prev");image=pixels(1)
                click("lifetime-other");image=pixels(3,image);record("switch_book_b",image)
                click("lifetime-open");image=pixels(1,image);record("restore_book_a",image)
                val control=URI("$base/test/version").toURL().openConnection() as HttpURLConnection
                control.requestMethod="POST";control.doOutput=true
                control.setRequestProperty("Authorization","Bearer synthetic-baseline-token")
                try { control.outputStream.use { it.write("v2".toByteArray()) };assertEquals(200,control.responseCode);control.inputStream.close() }
                finally { control.disconnect() }
                click("lifetime-version");image=pixels(2,image);record("version_changed",image)
                click("lifetime-density");image=pixels(2,image);record("rendering_changed",image)
                close();record("closed");stage("after_close")
                pause=ReaderPageTrace.pauseNextDecode();click("lifetime-open")
                val late=runBlocking { withTimeout(15_000) { pause!!.awaitEntered() } }
                click("private-document-back")
                waitUntil(timeoutMillis=15_000) { onAllNodesWithTag("private-document-back").fetchSemanticsNodes().isEmpty() }
                assertEquals(1,trace().getValue("decodeInFlight").jsonPrimitive.int)
                stage("closed_decode_paused")
                pause!!.resume();drained()
                assertFalse(events().any { it["kind"]?.jsonPrimitive?.content=="target_draw_commands" && it["image"]?.jsonPrimitive?.long==late })
                assertTrue(events().any { it["kind"]?.jsonPrimitive?.content=="release_skia_handle" && it["image"]?.jsonPrimitive?.long==late })
                record("cancelled_decode",late)
                click("lifetime-open");image=pixels(2,late);record("reopened",image)
                close();stage("final_closed")
                System.gc();System.runFinalization();System.gc();stage("after_gc_request")
            }
        } finally {
            pause?.resume()
            File(output,"lifecycle-cases.json").writeText(buildJsonObject { put("cases",JsonArray(cases));put("barrierBoundary","before JNI inside noninterruptible decode scope");put("physicalMemoryBudgetVerified",false);put("gpuReleaseVerified",false) }.toString())
            File(output,"trace.json").writeText(trace().toString())
            ReaderPageTrace.stop();client.close()
        }
    }
}
