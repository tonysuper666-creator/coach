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
        "title" to "Synthetic section", "content_version" to "v1", "fact_ids" to listOf("F1"), "sources" to listOf("Synthetic slide 4"))
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
        val http = CourseHttp("http://127.0.0.1:19291", "synthetic", "account1", "C")
        val other = CourseHttp("http://127.0.0.1:19291", "synthetic", "account2", "C")
        val tutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ 0 }))
        try {
            var engine = sync()
            val c = CoachController("C", engine, tutor, formatClock = { "" }, course = http)
            var persisted: JsonObject? = null
            runComposeUiTest {
                setContent {
                    var state by remember { mutableStateOf(http.readerState(engine, 1)) }
                    WeeklyReader(c, 1, listOf(section), section, emptyList(),
                        (state["marks"] as? JsonArray).orEmpty().map { it.jsonObject }, state, rememberScrollState(), "",
                        { state = it; persisted = it; http.saveReaderState(engine, 1, it) }, {}, { _, _ -> }, {},
                        { state = JsonObject(state + ("focus" to JsonPrimitive(it))); persisted = state; http.saveReaderState(engine, 1, state) }, {})
                }
                onNodeWithText("再讲一遍").assertDoesNotExist()
                onNodeWithTag("reader-text").performScrollTo().performSemanticsAction(SemanticsActions.SetSelection) { it(0, 9, false) }
                onNodeWithText("一句批注").performScrollTo().performClick()
                onNodeWithTag("reader-note").performTextInput("My synthetic note")
                onNodeWithText("返回阅读").performClick()
                onNodeWithText("我的一句话：My synthetic note").performScrollTo().assertIsDisplayed()
                onNodeWithText("深色阅读").performScrollTo().performClick()
                onNodeWithText("浅色阅读").assertIsDisplayed()
                onNodeWithText("专心读").performClick()
                onNodeWithText("退出专注").assertIsDisplayed()
                onNodeWithText("跳过要点，直接做题").assertIsDisplayed()
            }
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            engine = sync()
            assertEquals(persisted, http.readerState(engine, 1))
            assertTrue(other.readerState(engine, 1).isEmpty())
            assertTrue(http.readerState(engine, 2).isEmpty())
            assertEquals("My synthetic note", http.readerState(engine, 1)["marks"]!!.jsonArray.single().jsonObject.str("note"))
        } finally { driver.close(); tutor.close(); http.close(); other.close(); file.delete() }
    }
}
