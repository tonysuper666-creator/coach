package com.coach.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.serialization.json.JsonArray
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * PR89 keeps 错题 / 到期 tabs on top of TON-1495's single scroll. Offline practice sits at the top of both tabs,
 * so a half-answered offline group must survive switching tabs, not only scrolling. Synthetic ledger only.
 */
@OptIn(ExperimentalTestApi::class)
class ReviewTabOfflineTest {
    private val now = 1_794_052_800_000L

    @Test fun offlineAnswersSurviveSwitchingTabs() {
        val count = 3
        val offline = true
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val db = ClientDatabase(driver)
        val day = logicalDay(now, "Europe/London")
        val payloads = mutableListOf("exam_set" to json("examDay" to day + 6))
        repeat(count) { i ->
            val id = "K$i"
            payloads += "outline_revised" to json("ops" to JsonArray(listOf(json("kpId" to id, "title" to "Synthetic review objective $i"))))
            payloads += "legacy_passed" to json("kpId" to id)
            payloads += "legacy_schedule" to json("kpId" to id, "dueDay" to day - i, "interval" to 15)
        }
        val events = payloads.mapIndexed { i, (type, payload) ->
            Event(i + 1L, "e$i", "C", type, recordedAt = now, occurredAt = now,
                deviceId = "synthetic", actor = "SYSTEM", payload = payload)
        }
        events.forEach { db.ledgerQueries.putEvent(it.seq, it.courseId, LedgerJson.encodeToString(Event.serializer(), it)) }
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
        // Match the original Fold evidence, including the extra empty-mainline notice.
        c.sync.saveMainlineState("C", json("body" to json("weeks" to JsonArray(emptyList()))), "home")
        if (offline) {
            db.ledgerQueries.putMeta("epoch", "synthetic-epoch")
            db.ledgerQueries.putOfflinePack("pack", "C", now + 86_400_000, "synthetic-epoch")
            val posed = Event(1, "offline-question", "C", "question_posed", recordedAt = now, occurredAt = now,
                deviceId = "synthetic", actor = "SYSTEM", payload = json("attemptId" to "offline", "groupId" to "group",
                    "stem" to "Synthetic offline choice", "letterMap" to json("one" to "A", "two" to "B"),
                    "options" to JsonArray(listOf(json("optionId" to "one", "text" to "First synthetic answer"), json("optionId" to "two", "text" to "Second synthetic answer")))))
            db.ledgerQueries.putOfflineItem("offline", "pack", "group", 0, LedgerJson.encodeToString(Event.serializer(), posed))
        }
        val view = CourseView(events, project(events))
        try {
            runSkikoComposeUiTest(size = Size(840f, 1280f), density = Density(2f)) {
                setContent { CoachTheme(false) { Surface { ReviewScreen(c, view, { _, _ -> }, {}, onOpenMock = {}, onOpenEssay = {}) } } }
                onNodeWithText("$count 节到期").assertExists()
                onNodeWithText("复习 1 题").performScrollTo().performClick()
                onNodeWithTag("study-option-offline-A").performScrollTo().performClick().assertIsSelected()
                onNodeWithText("错题 0").performClick()
                onNodeWithText("$count 节到期").assertDoesNotExist()
                onNodeWithTag("study-option-offline-A").performScrollTo().assertIsDisplayed().assertIsSelected()
                onNodeWithText("到期 $count").performClick()
                onNodeWithText("$count 节到期").assertExists()
                onNodeWithTag("study-option-offline-A").performScrollTo().assertIsDisplayed().assertIsSelected()
                onNodeWithText("提交本组答案").performScrollTo().assertIsEnabled()
            }
        } finally { tutor.close(); driver.close() }
    }
}
