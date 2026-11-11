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

/** Synthetic ledger, no corpus, server, window or Android device. */
@OptIn(ExperimentalTestApi::class)
class ReviewLayoutTest {
    private val now = 1_794_052_800_000L

    private fun queue(name: String, width: Float, height: Float, count: Int) {
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
        val view = CourseView(events, project(events))
        val opened = mutableListOf<Pair<String, Boolean>>()
        val mainline = mutableListOf<Pair<Int?, Boolean>>()
        var mocks = 0
        var essays = 0
        var reminders = 0
        var backs = 0
        try {
            assertEquals(count, view.dueReviews(now).size)
            runSkikoComposeUiTest(size = Size(width, height), density = Density(2f)) {
                setContent { CoachTheme(false) { Surface {
                    ReviewScreen(c, view, { id, start -> opened += id to start }, { backs++ },
                        onOpenMock = { mocks++ }, onOpenEssay = { essays++ },
                        onOpenMainline = { week, quiz -> mainline += week to quiz },
                        reminderSettings = { Button(onClick = { reminders++ }) { Text("每日提醒 08:00") } })
                } } }
                onNodeWithText("每日提醒 08:00").assertIsDisplayed()
                capture("$name-top")
                if (count > 0) onNodeWithText("$count 节到期").performScrollTo().assertIsDisplayed()
                onNodeWithText("离线练习").performScrollTo().assertIsDisplayed()
                onNodeWithText("复习 3 题").assertIsNotEnabled()
                onNodeWithText("存到本机").performScrollTo().assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithText("Mac 未连接，下载未完成；已有题仍可练习。").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("选其他周").performScrollTo().assertIsDisplayed().performClick()
                assertEquals(listOf<Pair<Int?, Boolean>>(null to false), mainline)
                onNodeWithTag("open-mock").performScrollTo().assertIsDisplayed().performClick()
                assertEquals(1, mocks)
                onNodeWithTag("essay-entry").performScrollTo().assertIsDisplayed().performClick()
                assertEquals(1, essays)
                if (count == 0) {
                    onNodeWithText("今天没有到期的复习。").performScrollTo().assertIsDisplayed()
                    onNodeWithText("开始", substring = false).assertDoesNotExist()
                } else {
                    onNodeWithText("$count 节到期").performScrollTo().assertIsDisplayed()
                    view.dueReviews(now).forEach { due ->
                        val id = due.kp.id
                        onNodeWithTag("review-$id").performScrollTo().assertIsDisplayed()
                        onNodeWithText(due.kp.title).assertIsDisplayed()
                        val row = hasAnyAncestor(hasTestTag("review-$id"))
                        val label = if (due.overdueDays == 0) "今天到期" else "逾期 ${due.overdueDays} 天"
                        onNode(hasText(label) and row).assertIsDisplayed()
                        onNode(hasText("开始", substring = false) and row).assertIsDisplayed().performClick()
                    }
                    assertEquals(view.dueReviews(now).map { it.kp.id to true }, opened)
                    assertEquals(count, view.dueReviews(now).size, "Navigation must not alter the due ledger")
                }
                // Fixed actions remain usable even after scrolling to the very last due row.
                onNodeWithText("每日提醒 08:00").assertIsDisplayed().performClick()
                assertEquals(1, reminders)
                capture("$name-due")
                onNodeWithText("Mac 未连接，下载未完成；已有题仍可练习。").performScrollTo().assertIsDisplayed()
                onNodeWithTag("open-mock").performScrollTo().assertIsDisplayed()
                onNodeWithText("← 今天").assertIsDisplayed().performClick()
                assertEquals(1, backs)
            }
        } finally { tutor.close(); driver.close() }
    }

    private fun ComposeUiTest.capture(name: String) {
        val dir = File("build/screens").also { it.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    // Evidence: 1972x1248, rail 160px, system insets 56px + 64px => 1812x1128.
    @Test fun foldLandscapeKeepsAllEntriesAndDueStartReachable() = queue("review-fold", 1812f, 1128f, 1)
    @Test fun narrowKeepsAllEntriesAndDueStartReachable() = queue("review-narrow", 840f, 1280f, 1)
    @Test fun foldCanReachAndStartEveryDueRow() = queue("review-fold-many", 1812f, 1128f, 24)
    @Test fun shortEmptyQueueKeepsAllEntriesAndEmptyNoticeReachable() = queue("review-empty", 840f, 760f, 0)
}
