package com.coach.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WeeklyReaderTest {
    private val section = json("section_id" to "S1", "body" to "Synthetic pears grow in an orchard.",
        "title" to "【W3·slides】Synthetic section", "content_version" to "v1", "fact_ids" to listOf("F1", "F2"), "sources" to listOf("Synthetic slide 4"))
    private val mark = json("id" to "m1", "section" to "S1", "version" to "v1", "original" to section.str("body"),
        "start" to 0, "end" to 9, "quote" to "Synthetic", "note" to "My synthetic note")
    @Test fun staleAnchorsNeverMatchAndGuessesNeverMasterMapNodes() {
        assertTrue(markMatches(mark, section))
        assertFalse(markMatches(mark, JsonObject(section + ("content_version" to JsonPrimitive("v2")))))
        assertFalse(markMatches(mark, JsonObject(section + ("body" to JsonPrimitive("Changed Synthetic pears")))))
        assertEquals(listOf(mark), relatedMarks(listOf(mark), listOf(section), listOf("F1")))
        assertEquals(emptyList(), relatedMarks(listOf(mark), listOf(section), listOf("OTHER")))
        assertFalse(factMastered("F1", emptyList()))
        for (status in listOf("guessed", "pending", "missed", "new"))
            assertFalse(factMastered("F1", listOf(json("factIds" to listOf("F1"), "status" to status))))
        assertTrue(factMastered("F1", listOf(json("factIds" to listOf("F1"), "status" to "mastered"))))
    }
    @Test fun realSelectionAutosavesNoteAndSurvivesDatabaseReopenWithoutCrossAccountOrWeekLeakage() {
        val file = File.createTempFile("reader-synthetic", ".db")
        var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        ClientDatabase.Schema.create(driver)
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("unused")
            override suspend fun snapshot(course: String): Snapshot = error("unused")
            override suspend fun events(course: String, after: Long): EventsPage = error("unused")
            override suspend fun command(command: WireCommand): Receipt = error("unused")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("unused")
        }
        fun sync() = SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf("C"), { "id" },
            { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } })
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests.add(path)
            val response = when {
                path.endsWith("/explain/cancel") -> "{}"
                path.endsWith("/explain") -> """{"text":"Synthetic explanation","sources":[{"docId":"internal-doc-id","anchor":"internal-anchor","pdfPage":7}]}"""
                path.endsWith("/sources/internal-doc-id/pages/7") -> """{"docId":"internal-doc-id","pdfPage":7,"text":"Synthetic page seven"}"""
                else -> "{}"
            }.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val http = CourseHttp(base, "synthetic", "account1", "C")
        val other = CourseHttp("http://127.0.0.1:19291", "synthetic", "account2", "C")
        val tutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ 0 }))
        try {
            var engine = sync()
            val c = CoachController("C", engine, tutor, formatClock = { "" }, course = http)
            var persisted: JsonObject? = null
            var selectedQuestion: Pair<String, String?>? = null
            runComposeUiTest {
                setContent {
                    var state by remember { mutableStateOf(http.readerState(engine, 1)) }
                    WeeklyReader(c, 1, listOf(section), section, listOf(json("id" to "secret-question-id", "factIds" to listOf("F1"), "stem" to "Synthetic question with a stem longer than forty characters", "status" to "guessed"), json("id" to "other-question-id", "factIds" to listOf("F2")), json("id" to "mastered-question-id", "factIds" to listOf("F1"), "stem" to "Synthetic mastered", "status" to "mastered")),
                        (state["marks"] as? JsonArray).orEmpty().map { it.jsonObject }, state, rememberScrollState(), "",
                        { transform -> state = transform(state); persisted = state; http.saveReaderState(engine, 1, state) }, {}, { sectionId, questionId -> selectedQuestion = sectionId to questionId }, {},
                        { state = JsonObject(state + ("focus" to JsonPrimitive(it))); persisted = state; http.saveReaderState(engine, 1, state) }, {})
                }
                onNodeWithText("再讲一遍").assertDoesNotExist()
                onNodeWithTag("reader-text").performScrollTo().performSemanticsAction(SemanticsActions.SetSelection) { it(0, 9, false) }
                onNodeWithText("一句批注").performScrollTo().performClick()
                onNodeWithTag("reader-note").performTextInput("My synthetic note")
                onNodeWithText("返回阅读").performClick()
                onNodeWithText("我的一句话：My synthetic note").performScrollTo().assertIsDisplayed()
                onNodeWithTag("reader-text").performScrollTo().performSemanticsAction(SemanticsActions.SetSelection) { it(0, 9, false) }
                onNodeWithText("再讲一遍").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("PDF 第 7 页").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("internal-doc-id", substring = true).assertDoesNotExist()
                onNodeWithText("internal-anchor", substring = true).assertDoesNotExist()
                onNodeWithText("PDF 第 7 页").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic page seven").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("internal-doc-id", substring = true).assertDoesNotExist()
                assertTrue(requests.any { it.endsWith("/sources/internal-doc-id/pages/7") })
                onNodeWithText("返回追问").performClick()
                onNodeWithText("返回阅读").performClick()
                onNodeWithText("深色阅读").performScrollTo().performClick()
                onNodeWithText("浅色阅读").assertIsDisplayed()
                // The weekly map starts collapsed and the tap must actually draw its rows, not just flip a flag.
                onNodeWithText("要点：Synthetic section").assertDoesNotExist()
                onNodeWithText("本周脉络：节 → 考点 → 题").performScrollTo().performClick()
                onNodeWithText("要点：Synthetic section").assertIsDisplayed()
                onNodeWithText("○ 考点 1 · Synthetic section").assertIsDisplayed()
                onNodeWithText("○ 考点 2 · Synthetic section").performScrollTo().assertIsDisplayed()
                onNodeWithText("这一题 · 待练").performScrollTo().assertIsDisplayed()
                onNodeWithText("题：Synthetic question with a stem longer th · 猜对，待练").performScrollTo().performClick()
                onNodeWithText("题：Synthetic mastered · 最近答对（非猜）").performScrollTo().assertIsDisplayed()
                onNodeWithText("答对过", substring = true).assertDoesNotExist()
                assertEquals("S1" to "secret-question-id", selectedQuestion)
                onNodeWithText("secret-question-id", substring = true).assertDoesNotExist()
                onNodeWithText("收起本周脉络").performClick()
                onNodeWithText("要点：Synthetic section").assertDoesNotExist()
                onNodeWithText("专心读").performClick()
                onNodeWithText("退出专心读").assertIsDisplayed()
                onNodeWithText("跳过要点，直接做题").assertIsDisplayed()
                // Leaving focus and then touching another control must not bring focus back: the write that follows
                // has to extend the current state, not re-save the snapshot taken while focus mode was still on.
                onNodeWithText("退出专心读").performClick()
                onNodeWithText("浅色阅读").performScrollTo().performClick()
                onNodeWithText("本周脉络：节 → 考点 → 题").performScrollTo().assertIsDisplayed()
                onNodeWithText("退出专心读").assertDoesNotExist()
            }
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            engine = sync()
            assertEquals(persisted, http.readerState(engine, 1))
            assertTrue(other.readerState(engine, 1).isEmpty())
            assertTrue(http.readerState(engine, 2).isEmpty())
            assertEquals("My synthetic note", http.readerState(engine, 1)["marks"]!!.jsonArray.single().jsonObject.str("note"))
        } finally { driver.close(); tutor.close(); http.close(); other.close(); server.stop(0); file.delete() }
    }
}
