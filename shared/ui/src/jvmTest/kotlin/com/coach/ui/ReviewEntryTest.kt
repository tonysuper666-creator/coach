package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Synthetic ledger only. Exercise the shared Android/desktop queue and its direct-practice callback. */
@OptIn(ExperimentalTestApi::class)
class ReviewEntryTest {
    private val now = 1_794_052_800_000L
    private fun facts(examKnown: Boolean): List<Event> {
        val day = logicalDay(now, "Europe/London")
        val payloads = mutableListOf(
            "outline_revised" to json("ops" to JsonArray(listOf(json("kpId" to "K", "title" to "合成示例：复习知识点")))),
            "legacy_passed" to json("kpId" to "K"),
            "legacy_schedule" to json("kpId" to "K", "dueDay" to day, "interval" to 15))
        if (examKnown) payloads += "exam_set" to json("examDay" to day + 7)
        return payloads.mapIndexed { i, (type, payload) -> Event(i + 1L, "e$i", "C", type,
            recordedAt = now, occurredAt = now, deviceId = "synthetic", actor = "SYSTEM", payload = payload) }
    }

    private fun entry(width: Int, height: Int, examKnown: Boolean, integrated: Boolean = false) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val db = ClientDatabase(driver)
        val events = facts(examKnown)
        for (e in events) db.ledgerQueries.putEvent(e.seq, e.courseId, LedgerJson.encodeToString(Event.serializer(), e))
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No network in this queue test")
            override suspend fun snapshot(course: String): Snapshot = error("No network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No network")
            override suspend fun command(command: WireCommand): Receipt = error("No network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No network")
        }
        val tutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ now }))
        val c = CoachController("C", SyncEngine(db, "synthetic", transport, listOf("C"), { "id" }, { "hash" }),
            tutor, formatClock = { "12:00" }, nowMillis = { now })
        val view = CourseView(events, project(events))
        var page by mutableStateOf("today")
        var startNow = false
        try {
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(width.dp, height.dp)) {
                    if (integrated) CoachApp(c, dark = false) else when (page) {
                        "today" -> TodayScreen(c, view, TutorUiState(), null, { page = "review" }, { _, _ -> })
                        "review" -> ReviewScreen(c, view, { id, start -> assertEquals("K", id); startNow = start; page = "study" }, { page = "today" })
                        else -> StudyScreen(c, view.kp("K")!!, view, TutorUiState(), autoStart = startNow, onBack = { page = "review" }, reviewMode = true)
                    }
                } } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("1 个知识点到期").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("1 个知识点到期").assertIsDisplayed()
                if (!examKnown) onNodeWithText("日期未公布", substring = true).assertIsDisplayed()
                capture("review-today-$width${if (integrated) "-app" else ""}")
                onNodeWithText("到期复习").performClick()
                onNodeWithText("1 个知识点到期").assertIsDisplayed()
                if (integrated) {
                    onNodeWithTag("open-mock").assertIsDisplayed().performClick()
                    onNodeWithText("模拟考要连接 Mac。").assertIsDisplayed()
                    onNodeWithText("← 复习").performClick()
                    onNodeWithTag("open-mock").assertIsDisplayed()
                    onNodeWithText("1 个知识点到期").assertIsDisplayed()
                }
                capture("review-queue-$width${if (integrated) "-app" else ""}")
                onNodeWithText("开始", substring = false).performClick()
                if (!integrated) assertTrue(startNow, "Queue starts practice immediately on both layouts")
                onNodeWithText("到期复习").assertExists()
                onNodeWithText("开始复习").assertExists()
                onNodeWithText("开始讲解").assertDoesNotExist()
                onNodeWithText("← 复习").assertExists()
                if (integrated) {
                    onNodeWithText("← 复习").performClick()
                    onNodeWithTag("open-mock").assertIsDisplayed()
                    onNode(hasText("今天") and hasClickAction()).performClick()
                    onNodeWithText("复习知识点", substring = true).performClick()
                    onNodeWithText("开始讲解").assertExists()
                    onNodeWithText("开始复习").assertDoesNotExist()
                    onNodeWithText("到期复习").assertDoesNotExist()
                }
            }
        } finally { tutor.close(); driver.close() }
    }

    @Test fun wideStudyStartsAtFirstQuestionAndEnablesSubmitAfterThreeSelections() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val db = ClientDatabase(driver)
        val events = facts(true) + (1..3).map { i ->
            Event(10L + i, "posed$i", "C", "question_posed", threadId = "review:C:K",
                recordedAt = now, occurredAt = now, deviceId = "synthetic", actor = "SYSTEM",
                payload = json("attemptId" to "a$i", "questionId" to "q$i", "groupId" to "g1",
                    "format" to "SINGLE_CHOICE", "kpIds" to listOf("K"), "stem" to "Synthetic question $i",
                    "options" to JsonArray(listOf(JsonObject(mapOf("optionId" to JsonPrimitive("o$i"), "text" to JsonPrimitive("Synthetic option $i"))))),
                    "letterMap" to JsonObject(mapOf("o$i" to JsonPrimitive("A")))))
        }
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No network")
            override suspend fun snapshot(course: String): Snapshot = error("No network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No network")
            override suspend fun command(command: WireCommand): Receipt = error("No network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No network")
        }
        val tutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ now }))
        val c = CoachController("C", SyncEngine(db, "synthetic", transport, listOf("C"), { "id" }, { "hash" }),
            tutor, formatClock = { "12:00" }, nowMillis = { now })
        var view by mutableStateOf(CourseView(facts(true), project(facts(true))))
        try {
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { androidx.compose.foundation.layout.Row(Modifier.size(1100.dp, 800.dp)) {
                    Box(Modifier.size(80.dp, 800.dp))
                    StudyScreen(c, view.kp("K")!!, view, TutorUiState(), autoStart = false, onBack = {}, reviewMode = true)
                } } } }
                runOnIdle { view = CourseView(events, project(events)) }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("第 1 / 3 题").fetchSemanticsNodes().isNotEmpty() }
                val heading = onNodeWithText("第 1 / 3 题").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val list = onNodeWithTag("study-question-list").fetchSemanticsNode().boundsInRoot
                assertTrue(heading.top >= list.top && heading.top - list.top < 100f, "first card should start at top of question list")
                for (i in 1..3) {
                    onNodeWithTag("study-question-list").performScrollToNode(hasText("Synthetic option $i"))
                    onNodeWithText("Synthetic option $i").performClick()
                }
                onNodeWithTag("study-question-list").performScrollToNode(hasText("提交本组答案"))
                onNodeWithText("提交本组答案").assertIsEnabled()
            }
        } finally { tutor.close(); driver.close() }
    }

    private fun ComposeUiTest.capture(name: String) {
        val dir = File("build/screens").also { it.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }
    @Test fun phoneQueueStartsReviewAndShowsUnknownDate() = entry(420, 880, false)
    @Test fun desktopQueueStartsReviewInTheFinalWeek() = entry(1000, 800, true)
    @Test fun appKeepsMockEntryAndIsolatesReviewFromOrdinaryLearning() = entry(420, 880, true, integrated = true)
}
