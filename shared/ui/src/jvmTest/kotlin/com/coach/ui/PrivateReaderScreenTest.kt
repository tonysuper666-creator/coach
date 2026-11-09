package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextRange
import com.coach.client.CourseHttp
import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalTestApi::class)
class PrivateReaderScreenTest {
    private class FakeReader(private val stale: Boolean = false, private val course: String = "C", private val offset: Int = 1) : AutoCloseable {
        val pages = CopyOnWriteArrayList<Int>()
        @Volatile var currentPage = 2
        @Volatile var revision = 7L
        @Volatile var conflictNext = false
        val writes = CopyOnWriteArrayList<JsonObject>()
        val notes = CopyOnWriteArrayList<JsonObject>()
        val noteWrites = CopyOnWriteArrayList<JsonObject>()
        @Volatile var version = "v1"
        /** null → the study route fails; otherwise the exact body for any page. */
        @Volatile var study: String? = null
        /** null → the map route fails; otherwise the exact body. */
        @Volatile var map: String? = null
        /** Word boxes (PDF points) for any page; null → the boxes route fails with 503. */
        @Volatile var boxWords: String? = null
        @Volatile var boxWidth = 300.0
        @Volatile var boxHeight = 400.0
        val auth = CopyOnWriteArrayList<String>()
        private val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(30, 40, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/courses/$course/reader") { ex ->
                auth += ex.requestHeaders.getFirst("Authorization").orEmpty()
                val path = ex.requestURI.path
                val image = path.endsWith("/image")
                var status = 200
                val bytes = if (image) {
                    pages += path.substringBeforeLast('/').substringAfterLast('/').toInt()
                    png
                } else when {
                    path.endsWith("/resolve") -> {
                        val query = java.net.URLDecoder.decode(ex.requestURI.rawQuery, "UTF-8")
                        if (query == "source=教材 p.1") """{"documentId":"synthetic","version":"v1","pdfPage":${1 + offset},"anchorStatus":"page_only"}""".toByteArray()
                        else { status = 422; "{}".toByteArray() }
                    }
                    path.endsWith("/notes") -> {
                        val body = Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject
                        noteWrites += body
                        val existing = notes.singleOrNull { it["id"] == body["id"] }
                        val base = body.getValue("baseRevision").jsonPrimitive.long
                        val oldRevision = existing?.get("revision")?.jsonPrimitive?.long ?: 0L
                        if (base != oldRevision || body.getValue("version").jsonPrimitive.content != version) {
                            status = 409; "{}".toByteArray()
                        } else {
                            val next = oldRevision + 1
                            notes.removeIf { it["id"] == body["id"] }
                            notes += JsonObject(body + mapOf("revision" to JsonPrimitive(next), "stale" to JsonPrimitive(false)))
                            """{"revision":$next}""".toByteArray()
                        }
                    }
                    path.matches(Regex(".*/pages/[0-9]+")) -> """{"version":"$version","text":"Synthetic page text","textSha256":"hash-$version"}""".toByteArray()
                    path.endsWith("/progress") -> {
                        val body = Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject
                        writes += body
                        if (conflictNext || body.getValue("baseRevision").jsonPrimitive.long != revision) {
                            conflictNext = false
                            status = 409
                            "{}".toByteArray()
                        } else {
                            currentPage = body.getValue("pdfPage").jsonPrimitive.int
                            revision++
                            """{"revision":$revision}""".toByteArray()
                        }
                    }
                    path.endsWith("/boxes") -> {
                        val asked = ex.requestURI.rawQuery.orEmpty().substringAfter("version=", "")
                        if (asked != version) { status = 409; "{}".toByteArray() }
                        else boxWords?.let { """{"version":"$version","pdfPage":${path.substringBeforeLast('/').substringAfterLast('/')},"width":$boxWidth,"height":$boxHeight,"words":$it}""".toByteArray() }
                            ?: run { status = 503; "{}".toByteArray() }
                    }
                    path.endsWith("/map") -> map?.toByteArray() ?: run { status = 503; "{}".toByteArray() }
                    path.endsWith("/study") -> study?.toByteArray() ?: run { status = 503; "{}".toByteArray() }
                    path.endsWith("/state") -> buildJsonObject {
                        put("document", buildJsonObject { put("version", version) })
                        put("progress", buildJsonObject { put("version", if (stale) "old" else version); put("pdfPage", currentPage); put("revision", revision); put("stale", stale) })
                        put("notes", JsonArray(notes.map { JsonObject(it + ("stale" to JsonPrimitive(it["version"]?.jsonPrimitive?.content != version))) }))
                    }.toString().toByteArray()
                    else -> """{"documents":[{"id":"synthetic","title":"Synthetic book","version":"$version","pages":4}]}""".toByteArray()
                }
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val http = CourseHttp("http://127.0.0.1:${server.address.port}", "test-device", "synthetic-token", course)
        override fun close() { http.close(); server.stop(0) }
    }

    @Test fun annotationAutosavesAndReopensAndOldVersionIsClearlyMarked() = FakeReader().use { fake ->
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(600.dp, 900.dp)) { PrivateReaderScreen(fake.http.reader) {} } } } }
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-book-synthetic").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-2").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-open-notes").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-note-text").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-note-text").performTextInputSelection(TextRange(0, 9))
            onNodeWithTag("private-note-add").performClick()
            onNodeWithTag("private-note-editor").performTextInput("my observation")
            waitUntil(timeoutMillis = 10000) { fake.notes.singleOrNull()?.get("note")?.jsonPrimitive?.content == "my observation" }
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-note-editor-close").fetchSemanticsNodes().firstOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false }
            onNodeWithTag("private-note-editor-close").performClick()
            onNodeWithTag("private-notes-close").performClick()
            onNodeWithTag("private-document-back").performClick()
            onNodeWithTag("private-book-synthetic").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-2").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-open-notes").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithText("我的批注：my observation").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Synthetic", fake.notes.single().getValue("quote").jsonPrimitive.content)
        }
        fake.version = "v2"
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(600.dp, 900.dp)) { PrivateReaderScreen(fake.http.reader) {} } } } }
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-book-synthetic").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-2").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-open-notes").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-note-stale").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("我的批注：my observation").assertExists()
            onAllNodesWithTag("private-note-edit-${fake.notes.single().getValue("id").jsonPrimitive.content}").assertCountEquals(0)
        }
    }

    @Test fun phoneRestoresSameVersionAndTurnsPages() = FakeReader().use { fake ->
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) { PrivateReaderScreen(fake.http.reader) {} } } } }
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-book-synthetic").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-2").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-page-label").assertTextContains("第 2 页", substring = true)
            onNodeWithTag("private-next").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-3").fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 10000) { fake.currentPage == 3 && onAllNodesWithTag("private-next").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-prev").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-2").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(fake.auth.all { it == "Bearer synthetic-token" })
        }
    }

    @Test fun wideSpreadShowsTwoImagesAndStopsAtLastPage() = FakeReader().use { fake ->
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(1000.dp, 800.dp)) { PrivateReaderScreen(fake.http.reader) {} } } } }
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-book-synthetic").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-2").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-spread").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-3").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-page-2").assertIsDisplayed()
            onNodeWithTag("private-next").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-4").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-next").assertIsNotEnabled()
        }
    }

    @Test fun staleProgressIsNotAppliedToNewVersion() = FakeReader(stale = true).use { fake ->
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) { PrivateReaderScreen(fake.http.reader) {} } } } }
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-book-synthetic").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-1").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("旧版本的阅读位置未自动套用，请重新定位。").assertIsDisplayed()
            assertFalse(2 in fake.pages)
        }
    }

    @Test fun savedPositionSurvivesClosingAndReopeningReader() = FakeReader().use { fake ->
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) { PrivateReaderScreen(fake.http.reader) {} } } } }
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-book-synthetic").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-2").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-next").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-document-back").performClick()
            onNodeWithTag("private-book-synthetic").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-3").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(7L, fake.writes.single().getValue("baseRevision").jsonPrimitive.long)
        }
    }

    @Test fun conflictingDeviceKeepsCurrentPageUntilUserLoadsRemote() = FakeReader().use { fake ->
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) { PrivateReaderScreen(fake.http.reader) {} } } } }
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-book-synthetic").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-2").fetchSemanticsNodes().isNotEmpty() }
            fake.currentPage = 4
            fake.revision = 8
            onNodeWithTag("private-next").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-load-remote").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-page-label").assertTextContains("第 3 页", substring = true)
            onNodeWithTag("private-document-back").assertIsNotEnabled()
            onNodeWithTag("private-load-remote").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-4").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-prev").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(8L, fake.writes.last().getValue("baseRevision").jsonPrimitive.long)
        }
    }

    @Test fun citationClickUsesEachCoursesConfiguredResolution() {
        for ((course, offset) in listOf("C" to 1, "OTHER" to 2)) FakeReader(course = course, offset = offset).use { fake ->
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) { PrivateSourceReference(fake.http.reader, "教材 p.1") } } } }
                onNodeWithTag("private-citation").performClick()
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-${offset + 1}").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("private-page-label").assertTextContains("第 ${offset + 1} 页", substring = true)
            }
        }
    }

    @Test fun conflictingDeviceCanExplicitlyKeepLocalPage() = FakeReader().use { fake ->
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) { PrivateReaderScreen(fake.http.reader) {} } } } }
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-book-synthetic").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-2").fetchSemanticsNodes().isNotEmpty() }
            fake.currentPage = 4; fake.revision = 8
            onNodeWithTag("private-next").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-keep-page").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-keep-page").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(3, fake.currentPage)
            assertEquals(8L, fake.writes.last().getValue("baseRevision").jsonPrimitive.long)
        }
    }

    private fun study(course: String, fact: String, mastered: Boolean, unlocated: Int) =
        """{"facts":[{"id":"f-$course","week":1,"text":"$fact","mastered":$mastered}],"questions":[{"id":"q-$course","week":1,"tier":"setup","stem":"Stem $course","status":"${if (mastered) "mastered" else "missed"}"}],"unlocated":$unlocated}"""

    @Test fun pageStudyShowsEachCoursesOwnPointsAndMissingSourcesHonestly() {
        for ((course, mastered, unlocated) in listOf(Triple("C", true, 0), Triple("OTHER", false, 3))) FakeReader(course = course).use { fake ->
            fake.study = study(course, "Invented point $course", mastered, unlocated)
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) { PrivateReaderScreen(fake.http.reader) {} } } } }
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("private-book-synthetic").performClick()
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-study-summary").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("private-study-summary").assertTextContains("本页考点 1 个 · 已掌握 ${if (mastered) 1 else 0}", substring = true)
                if (unlocated > 0) onNodeWithTag("private-study-unlocated").assertTextContains("另有 3 条", substring = true) else onAllNodesWithTag("private-study-unlocated").assertCountEquals(0)
                onNodeWithTag("private-study-toggle").performClick()
                onNodeWithTag("private-study-fact-f-$course").assertExists()
                onNodeWithTag("private-study-question-q-$course").assertExists()
                onAllNodesWithTag("private-study-fact-f-${if (course == "C") "OTHER" else "C"}").assertCountEquals(0)
            }
        }
    }

    @Test fun pageStudyFailureAndMalformedReplyAreNotShownAsAnEmptyPage() = FakeReader().use { fake ->
        for (body in listOf<String?>(null, """{"facts":"oops"}""")) {
            fake.study = body
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) { PrivateReaderScreen(fake.http.reader) {} } } } }
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("private-book-synthetic").performClick()
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-study-failed").fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithTag("private-study-summary").assertCountEquals(0)
                fake.study = study("C", "Recovered point", false, 0)
                onNodeWithTag("private-study-retry").performClick()
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-study-summary").fetchSemanticsNodes().isNotEmpty() }
            }
        }
    }

    @Test fun practiceButtonOpensOverReaderAndClosingKeepsTheSamePageWithoutNewProgressWrites() = FakeReader().use { fake ->
        fake.study = """{"facts":[],"questions":[{"id":"Q1","week":2,"tier":"setup","stem":"Stem","status":"new"},{"id":"Q2","week":2,"tier":"detail","stem":"Stem two","status":"new"}],"unlocated":0}"""
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) {
                PrivateReaderScreen(fake.http.reader, practice = { week, ids, close -> androidx.compose.material3.TextButton(onClick = close, modifier = Modifier.semantics { testTag = "stub-close" }) { androidx.compose.material3.Text("practice $week ${ids.sorted()}") } }) {}
            } } } }
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-book-synthetic").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-study-toggle").fetchSemanticsNodes().isNotEmpty() }
            val writesBefore = fake.writes.size
            onNodeWithTag("private-study-practice-2").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("stub-close").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("practice 2 [Q1, Q2]").assertExists()
            onNodeWithTag("stub-close").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-study-practice-2").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-page-label").assertTextContains("第 2 页", substring = true)
            assertEquals(writesBefore, fake.writes.size)
        }
    }

    @Test fun noPracticeButtonWhenPageHasNoQuestionsOrStudyFails() {
        for (body in listOf<String?>("""{"facts":[{"id":"F","week":1,"text":"Only a point","mastered":false}],"questions":[],"unlocated":0}""", null)) FakeReader().use { fake ->
            fake.study = body
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) {
                    PrivateReaderScreen(fake.http.reader, practice = { _, _, _ -> }) {}
                } } } }
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("private-book-synthetic").performClick()
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-study-summary").fetchSemanticsNodes().isNotEmpty() || onAllNodesWithTag("private-study-failed").fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithTag("private-study-practice-1").assertCountEquals(0)
                onAllNodesWithTag("private-study-practice-2").assertCountEquals(0)
            }
        }
    }

    private fun mapBody(tag: String, hasOutline: Boolean = true, unplaced: Int = 0) = if (!hasOutline) """{"hasOutline":false,"chapters":[],"unlocated":0,"ambiguous":0,"outsideOutline":0}""" else
        """{"hasOutline":true,"unlocated":$unplaced,"ambiguous":0,"outsideOutline":0,"chapters":[
            {"title":"Chapter $tag","pdfPage":1,"endPage":4,"facts":[],"questions":[],"factsMastered":0,"questionsMastered":0,"sections":[
              {"title":"Section $tag","pdfPage":3,"endPage":4,"facts":[{"id":"f-$tag","week":1,"text":"Point $tag","pdfPage":3,"mastered":false}],
               "questions":[{"id":"q-$tag","week":1,"tier":"setup","stem":"Stem $tag","status":"new","pdfPage":3}],"sections":[]}]}]}"""

    @Test fun chapterMapJumpsToRegisteredPagesAndIsolatesCourses() {
        for (tag in listOf("C", "OTHER")) FakeReader(course = tag).use { fake ->
            fake.map = mapBody(tag, unplaced = 2)
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) {
                    PrivateReaderScreen(fake.http.reader, practice = { week, ids, close -> androidx.compose.material3.TextButton(onClick = close, modifier = Modifier.semantics { testTag = "stub-close" }) { androidx.compose.material3.Text("practice $week ${ids.sorted()}") } }) {}
                } } } }
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("private-book-synthetic").performClick()
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-2").fetchSemanticsNodes().isNotEmpty() }
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-open-map").fetchSemanticsNodes().firstOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false }
                onNodeWithTag("private-open-map").performClick()
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-map-fact-f-$tag").fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithTag("private-map-fact-f-${if (tag == "C") "OTHER" else "C"}").assertCountEquals(0)
                onNodeWithTag("private-map-unplaced").assertTextContains("有 2 条", substring = true)
                // A question opens the existing practice over the map; closing it returns to the map, not to another page.
                onNodeWithTag("private-map-question-q-$tag").performClick()
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("stub-close").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("practice 1 [q-$tag]").assertExists()
                onNodeWithTag("stub-close").performClick()
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-map-fact-f-$tag").fetchSemanticsNodes().isNotEmpty() }
                // A point jumps to its own page, saves the position once, and the map closes.
                onNodeWithTag("private-map-fact-f-$tag").performClick()
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-3").fetchSemanticsNodes().isNotEmpty() && fake.currentPage == 3 }
                onNodeWithTag("private-page-label").assertTextContains("第 3 页", substring = true)
                onAllNodesWithTag("private-map").assertCountEquals(0)
            }
        }
    }

    @Test fun chapterMapNeverInventsStructure() = FakeReader().use { fake ->
        for ((body, expect) in listOf<Pair<String?, String>>(mapBody("C", hasOutline = false) to "private-map-empty", null to "private-map-failed",
            """{"hasOutline":true,"chapters":[{"title":"No page","facts":[],"questions":[],"sections":[]}],"unlocated":0}""" to "private-map-failed")) {
            fake.map = body
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) { PrivateReaderScreen(fake.http.reader) {} } } } }
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("private-book-synthetic").performClick()
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-2").fetchSemanticsNodes().isNotEmpty() }
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-open-map").fetchSemanticsNodes().firstOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false }
                onNodeWithTag("private-open-map").performClick()
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag(expect).fetchSemanticsNodes().isNotEmpty() }
                if (expect == "private-map-failed") {
                    fake.map = mapBody("C")
                    onNodeWithTag("private-map-retry").performClick()
                    waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-map-fact-f-C").fetchSemanticsNodes().isNotEmpty() }
                }
                onNodeWithTag("private-map-close").performClick()
                onAllNodesWithTag("private-map").assertCountEquals(0)
                onNodeWithTag("private-page-label").assertTextContains("第 2 页", substring = true)
            }
        }
    }

    private fun w(x0: Int, y0: Int, x1: Int, y1: Int, t: String) = """{"x0":$x0,"y0":$y0,"x1":$x1,"y1":$y1,"text":"$t"}"""
    private fun anchorStudy(anchor: String) =
        """{"facts":[{"id":"f1","week":1,"text":"Point","mastered":false,"anchor":"$anchor"}],"questions":[{"id":"q1","week":1,"tier":"setup","stem":"Stem","status":"new","anchor":""}],"unlocated":0}"""

    private fun ComposeUiTest.openReader(fake: FakeReader, width: Int) {
        setContent { CoachTheme(false) { Surface { Box(Modifier.size(width.dp, 900.dp)) { PrivateReaderScreen(fake.http.reader) {} } } } }
        waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("private-book-synthetic").performClick()
        waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-2").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun sourceAnchorHighlightScalesWithThePageForTwoCourses() {
        // Two invented pages in different frames: 300x400 and 600x800 points; the same on-page share must be marked at every rendered width.
        for ((course, pageW, pageH) in listOf(Triple("C", 300.0, 400.0), Triple("OTHER", 600.0, 800.0))) FakeReader(course = course).use { fake ->
            fake.study = anchorStudy("Alpha beta")
            fake.boxWidth = pageW; fake.boxHeight = pageH
            val k = pageW / 300.0
            fake.boxWords = "[" + listOf(w((30 * k).toInt(), (40 * k).toInt(), (80 * k).toInt(), (55 * k).toInt(), "Alpha"), w((85 * k).toInt(), (40 * k).toInt(), (120 * k).toInt(), (55 * k).toInt(), "beta"),
                w((30 * k).toInt(), (200 * k).toInt(), (60 * k).toInt(), (215 * k).toInt(), "gamma")).joinToString(",") + "]"
            val ratios = mutableListOf<Double>()
            for (width in listOf(412, 700)) runComposeUiTest {
                openReader(fake, width)
                onNodeWithTag("private-study-toggle").performClick()
                onNodeWithTag("private-study-anchor-fact-f1").performClick()
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-highlight-0").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("private-highlight-status").assertTextContains("已在页上标出", substring = true)
                val image = onNodeWithTag("private-page-2").getUnclippedBoundsInRoot(); val mark = onNodeWithTag("private-highlight-0").getUnclippedBoundsInRoot()
                val shareW = ((mark.right - mark.left).value / (image.right - image.left).value).toDouble(); ratios += shareW
                assertEquals((120 - 30) / 300.0, shareW, 0.01, "marked share of the page width is (120-30)/300 at width $width")
                assertEquals(40 / 400.0, ((mark.top - image.top).value / (image.bottom - image.top).value).toDouble(), 0.01, "top edge at 40/400 of the page height")
                onNodeWithTag("private-study-noanchor-question-q1").assertExists()
            }
            assertEquals(ratios[0], ratios[1], 0.01, "highlight follows the page when it is drawn wider")
        }
    }

    @Test fun anchorMismatchesNeverInventAPosition() = FakeReader().use { fake ->
        val cases = listOf(
            Triple("Alpha beta", "[" + w(30, 40, 80, 55, "Alpha") + "," + w(85, 40, 120, 55, "beta") + "," + w(30, 200, 80, 215, "Alpha") + "," + w(85, 200, 120, 215, "beta") + "]", "出现多处"),
            Triple("Missing words", "[" + w(30, 40, 80, 55, "Alpha") + "]", "找不到这句话"),
            Triple("Alpha", "[]", "没有文字层"),
            Triple("Alpha", "[" + w(30, 40, 500, 55, "Alpha") + "]", "不可用"))
        for ((anchor, words, message) in cases) {
            fake.study = anchorStudy(anchor); fake.boxWords = words
            runComposeUiTest {
                openReader(fake, 412)
                onNodeWithTag("private-study-toggle").performClick()
                onNodeWithTag("private-study-anchor-fact-f1").performClick()
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-highlight-status").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("private-highlight-status").assertTextContains(message, substring = true)
                onAllNodesWithTag("private-highlight-0").assertCountEquals(0)
            }
        }
        // A failing coordinate service can be retried and then marks the page.
        fake.study = anchorStudy("Alpha"); fake.boxWords = null
        runComposeUiTest {
            openReader(fake, 412)
            onNodeWithTag("private-study-toggle").performClick()
            onNodeWithTag("private-study-anchor-fact-f1").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-highlight-retry").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag("private-highlight-0").assertCountEquals(0)
            fake.boxWords = "[" + w(30, 40, 80, 55, "Alpha") + "]"
            onNodeWithTag("private-highlight-retry").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-highlight-0").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun savedNoteMarksItsQuoteAfterReopenAndStaleVersionIsNotMarked() = FakeReader().use { fake ->
        fake.boxWords = "[" + w(30, 40, 90, 55, "Synthetic") + "," + w(95, 40, 125, 55, "page") + "]"
        fake.notes += buildJsonObject {
            put("id", "n1"); put("version", "v1"); put("pdfPage", 2); put("textSha256", "hash-v1"); put("start", 0); put("end", 9)
            put("quote", "Synthetic"); put("note", "mine"); put("revision", 1); put("stale", false)
        }
        repeat(2) { // second pass is a fresh composition: reopening restores the marking from the saved note
            runComposeUiTest {
                openReader(fake, 412)
                onNodeWithTag("private-open-notes").performClick()
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-note-show-n1").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("private-note-show-n1").performClick()
                waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-highlight-0").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("private-highlight-status").assertTextContains("已在页上标出", substring = true)
                onNodeWithTag("private-highlight-clear").performClick()
                onAllNodesWithTag("private-highlight-0").assertCountEquals(0)
            }
        }
        fake.version = "v2"
        runComposeUiTest {
            openReader(fake, 412)
            onNodeWithTag("private-open-notes").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-note-stale").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag("private-note-show-n1").assertCountEquals(0)
            onAllNodesWithTag("private-highlight-0").assertCountEquals(0)
        }
    }

    @Test fun boxParsingAndQuoteLocationAreStrict() {
        fun boxes(vararg words: WordBox) = PageBoxes(300.0, 400.0, words.toList())
        fun b(x0: Double, y0: Double, x1: Double, y1: Double, t: String) = WordBox(x0, y0, x1, y1, t)
        val twoLines = boxes(b(10.0, 10.0, 40.0, 20.0, "one"), b(45.0, 10.0, 80.0, 20.0, "two"), b(10.0, 30.0, 50.0, 40.0, "three"), b(60.0, 30.0, 90.0, 40.0, "four"))
        val found = assertIs<Located.Found>(locateQuote(twoLines, "two three"))
        assertEquals(2, found.rects.size, "a quote across a line break is one rectangle per line")
        assertEquals(45.0, found.rects[0].x0); assertEquals(50.0, found.rects[1].x1)
        assertEquals(Located.NotFound, locateQuote(twoLines, "two four")) // not consecutive
        assertEquals(Located.NotFound, locateQuote(twoLines, "   "))
        assertEquals(Located.NoTextLayer, locateQuote(boxes(), "one"))
        assertEquals(Located.Ambiguous, locateQuote(boxes(b(1.0, 1.0, 5.0, 5.0, "x"), b(1.0, 9.0, 5.0, 12.0, "x")), "x"))
        val good = Json.parseToJsonElement("""{"version":"v","pdfPage":3,"width":300,"height":400,"words":[${w(1, 1, 5, 5, "a")}]}""").jsonObject
        assertNotNull(pageBoxes(good, "v", 3))
        assertNull(pageBoxes(good, "other", 3), "another document version is never drawn")
        assertNull(pageBoxes(good, "v", 4), "another page is never drawn")
        assertNull(pageBoxes(Json.parseToJsonElement("""{"version":"v","pdfPage":3,"width":300,"height":400,"words":[${w(1, 1, 500, 5, "a")}]}""").jsonObject, "v", 3), "out of the page frame")
        assertNull(pageBoxes(Json.parseToJsonElement("""{"version":"v","pdfPage":3,"width":0,"height":400,"words":[]}""").jsonObject, "v", 3))
    }
}
