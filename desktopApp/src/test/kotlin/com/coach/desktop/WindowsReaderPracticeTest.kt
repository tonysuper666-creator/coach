package com.coach.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.ui.*
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** The server and database are isolated fixtures; no production traffic. */
@OptIn(ExperimentalTestApi::class)
class WindowsReaderPracticeTest {
    private fun exercise(block: (CoachController, AtomicReference<String>, MutableList<String>) -> Unit) {
        val mode = AtomicReference("match")
        val paths = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.toString(); paths.add(path)
                val week = if (path.contains("/mainline/2?")) 2 else 1
                val state = mode.get()
                if (state == "network") {
                    exchange.sendResponseHeaders(503, -1); exchange.close()
                } else {
                    val ladder = LadderQuestion("same-id", "K", "S", "introduction", 0, "synthetic", "synthetic", eligible = true)
                    val item = json("id" to "same-id", "stem" to "Week $week question", "options" to listOf("A", "B"),
                        "status" to "new", "keyState" to "VERIFIED", "factIds" to listOf("F2"), "ladder" to LedgerJson.encodeToJsonElement(ladder))
                    val notes = when (state) {
                        "empty" -> listOf(json("title" to "Wrong fact", "body" to "Must stay hidden", "fact_ids" to listOf("F1")),
                            json("title" to "Empty", "body" to "", "fact_ids" to listOf("F2")))
                        else -> listOf(json("title" to "Registered fact", "body" to "Actual same-week fact", "fact_ids" to listOf("F2")),
                            json("title" to "Other fact", "body" to "Must stay hidden", "fact_ids" to listOf("F1")))
                    }
                    val body = json("week" to week, "learning" to true,
                        "items" to JsonArray(if (state == "filtered") emptyList() else listOf(item)),
                        "notes" to JsonArray(notes), "notesError" to if (state == "notesError") "Notes unavailable" else "")
                    val bytes = body.toString().toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
                }
            }
            start()
        }
        val http = CourseHttp("http://127.0.0.1:${server.address.port}", "test", "synthetic", "C")
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val db = ClientDatabase(driver)
        val event = Event(1, "fixture", "C", "fixture_unknown", recordedAt = 1, occurredAt = 1, deviceId = "synthetic", actor = "synthetic")
        db.ledgerQueries.putEvent(1, "C", LedgerJson.encodeToString(event))
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No ledger network")
            override suspend fun snapshot(course: String): Snapshot = error("No ledger network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No ledger network")
            override suspend fun command(command: WireCommand): Receipt = error("No ledger network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No ledger network")
        }
        val now = 1_794_052_800_000L
        val c = CoachController("C", SyncEngine(db, "synthetic", transport, listOf("C"), { "id" }, { "hash" }),
            TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ now })),
            formatClock = { "12:00" }, nowMillis = { now }, course = http)
        try { block(c, mode, paths) } finally { http.close(); server.stop(0); driver.close() }
    }

    @Test fun sameWeekQuestionRelatedFactsAndDraftSurviveReturn() = exercise { c, mode, paths ->
        runComposeUiTest {
            var open by mutableStateOf(true)
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(1000.dp, 800.dp)) {
                if (open) PagePractice(c, 2, setOf("same-id")) { open = false }
                else androidx.compose.material3.TextButton(onClick = { open = true }) { androidx.compose.material3.Text("Reopen") }
            } } } }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Week 2 question", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText("Week 1 question", substring = true).assertCountEquals(0)
            onAllNodesWithTag("mainline-source-unknown").assertCountEquals(0) // This question is already registered on the current page.
            onNodeWithTag("mainline-option-1").performClick()
            onNodeWithTag("mainline-related-notes").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("related-note-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("related-note-0").performClick()
            onNodeWithTag("related-note-body").assertTextEquals("Actual same-week fact")
            onAllNodesWithText("Must stay hidden").assertCountEquals(0)
            onNodeWithText("返回题目").performClick()
            onNodeWithText("暂离 · 阅读").performClick()
            onNodeWithText("Reopen").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Week 2 question", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("mainline-option-1").assertIsSelected()
            val weekRequests = paths.filter { it.contains("/mainline/") }
            assertTrue(weekRequests.isNotEmpty() && weekRequests.all { it.contains("/mainline/2?") }, "No request may silently switch to week 1: $paths")
        }
    }

    @Test fun weeklyQuestionWithoutSourceSaysPositionIsUnknown() = exercise { c, _, _ ->
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(1000.dp, 800.dp)) {
                MainlineScreen(c, CourseView(emptyList(), project(emptyList())), {}, {}, {}, {}, directEntry = true, initialWeek = 2)
            } } } }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Week 2 question", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("mainline-source-unknown").assertTextEquals("教材位置待确认")
            onNodeWithTag("mainline-related-notes").assertExists()
        }
    }

    @Test fun filteredQuestionAndNotesFailuresStayExplicitAndRetryable() = exercise { c, mode, _ ->
        runComposeUiTest {
            var open by mutableStateOf(true)
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(1000.dp, 800.dp)) {
                if (open) PagePractice(c, 2, setOf("same-id")) { open = false }
                else androidx.compose.material3.TextButton(onClick = { open = true }) { androidx.compose.material3.Text("Reopen") }
            } } } }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Week 2 question", substring = true).fetchSemanticsNodes().isNotEmpty() }
            mode.set("empty")
            onNodeWithTag("mainline-related-notes").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("related-notes-empty").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText("Must stay hidden").assertCountEquals(0)
            onNodeWithText("返回题目").performClick()
            mode.set("notesError")
            onNodeWithTag("mainline-related-notes").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("related-notes-error").fetchSemanticsNodes().isNotEmpty() }
            mode.set("network")
            onNodeWithTag("related-notes-retry").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("503", substring = true).fetchSemanticsNodes().isNotEmpty() }
            mode.set("filtered")
            onNodeWithTag("related-notes-retry").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("这题当前暂不能开始练习").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("返回题目").performClick()
            onNodeWithText("暂离 · 阅读").performClick()
            onNodeWithText("Reopen").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("这题当前暂不能开始练习", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText("Week 2 question", substring = true).assertCountEquals(0)
        }
    }
}
