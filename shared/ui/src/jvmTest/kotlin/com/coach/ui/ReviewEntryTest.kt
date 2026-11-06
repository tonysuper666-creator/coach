package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
                    if (integrated) CoachApp(c, dark = false, settings = { Text("每日提醒 08:00") }) else when (page) {
                        "today" -> TodayScreen(c, view, TutorUiState(), null, { page = "review" }, { _, _ -> })
                        "review" -> ReviewScreen(c, view, { id, start -> assertEquals("K", id); startNow = start; page = "study" }, { page = "today" })
                        else -> StudyScreen(c, view.kp("K")!!, view, TutorUiState(), autoStart = startNow, onBack = { page = "review" }, reviewMode = true)
                    }
                } } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("1 个知识点到期").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("1 个知识点到期").assertIsDisplayed()
                if (integrated) onNodeWithText("每日提醒 08:00").assertDoesNotExist()
                if (!examKnown) onNodeWithText("日期未公布", substring = true).assertIsDisplayed()
                capture("review-today-$width${if (integrated) "-app" else ""}")
                onNodeWithText("到期复习").performClick()
                onNodeWithText("1 个知识点到期").assertIsDisplayed()
                if (integrated) {
                    onNodeWithText("每日提醒 08:00").assertIsDisplayed()
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
                if (integrated) onNodeWithText("每日提醒 08:00").assertDoesNotExist()
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

    private fun ComposeUiTest.capture(name: String) {
        val dir = File("build/screens").also { it.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }
    @Test fun phoneQueueStartsReviewAndShowsUnknownDate() = entry(420, 880, false)
    @Test fun desktopQueueStartsReviewInTheFinalWeek() = entry(1000, 800, true)
    @Test fun appKeepsMockEntryAndIsolatesReviewFromOrdinaryLearning() = entry(420, 880, true, integrated = true)
}
