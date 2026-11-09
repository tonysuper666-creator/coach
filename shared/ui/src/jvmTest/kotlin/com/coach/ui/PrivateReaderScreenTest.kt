package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
}
