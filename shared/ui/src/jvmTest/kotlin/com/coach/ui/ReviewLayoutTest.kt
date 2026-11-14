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
import com.coach.domain.reviewqueue.QueueQuestion
import com.coach.domain.reviewqueue.VariantCandidate
import com.coach.domain.reviewqueue.VariantCandidatePort
import com.coach.protocol.*
import kotlinx.serialization.json.JsonArray
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Synthetic ledger, no corpus, server, window or Android device. */
@OptIn(ExperimentalTestApi::class)
class ReviewLayoutTest {
    private val now = 1_794_052_800_000L

    private fun queue(name: String, width: Float, height: Float, count: Int, offline: Boolean = false) {
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
        // A cached weekly overview must not resurrect an ordinary-learning entry in review.
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
        // 每行一道已核键的合成变式：开始前必须先备好题，点开的必须是这一行的题。
        val port = VariantCandidatePort { _, _, kp ->
            listOf(VariantCandidate("C", kp, QueueQuestion("synthetic-variant-$kp"), keyVerified = true))
        }
        val opened = mutableListOf<Pair<String, String>>()
        val mainline = mutableListOf<Pair<Int?, Boolean>>()
        var mocks = 0
        var essays = 0
        var reminders = 0
        var backs = 0
        try {
            assertEquals(count, view.dueReviews(now).size)
            runSkikoComposeUiTest(size = Size(width, height), density = Density(2f)) {
                setContent { CoachTheme(false) { Surface {
                    ReviewScreen(c, view,
                        { id, start -> fail("Legacy KP route started review for $id (startNow=$start)") }, { backs++ },
                        onOpenMock = { mocks++ }, onOpenEssay = { essays++ },
                        onOpenMainline = { week, quiz -> mainline += week to quiz },
                        reminderSettings = { Button(onClick = { reminders++ }) { Text("每日提醒 08:00") } },
                        variantCandidates = port,
                        onOpenPractice = { target -> opened += target.kpId to target.question.id })
                } } }
                onNodeWithText("每日提醒 08:00").assertIsDisplayed()
                capture("$name-top")
                if (count > 0) {
                    onNodeWithText("$count 节到期").reveal()
                    onNodeWithText("Synthetic review objective 0").reveal()
                }
                onNodeWithText("离线练习").reveal()
                if (offline) {
                    onNodeWithText("复习 1 题").reveal().performClick()
                    onNodeWithTag("study-option-offline-A").reveal().performClick().assertIsSelected()
                } else {
                    onNodeWithText("复习 3 题").assertIsNotEnabled()
                    onNodeWithText("存到本机").reveal().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Mac 未连接，下载未完成；已有题仍可练习。").fetchSemanticsNodes().isNotEmpty()
                    }
                }
                onNodeWithTag("open-mainline").assertDoesNotExist()
                assertTrue(mainline.isEmpty(), "Review must not open the ordinary weekly question route")
                onNodeWithTag("open-mock").reveal().performClick()
                assertEquals(1, mocks)
                onNodeWithTag("essay-entry").reveal().performClick()
                assertEquals(1, essays)
                if (count == 0) {
                    onNodeWithText("今天没有到期的复习。").reveal()
                    onNodeWithText("开始", substring = false).assertDoesNotExist()
                } else {
                    onNodeWithText("$count 节到期").reveal()
                    view.dueReviews(now).forEach { due ->
                        val id = due.kp.id
                        onNodeWithTag("review-$id").performScrollTo().assertIsDisplayed()
                        val bounds = onNodeWithTag("review-$id").fetchSemanticsNode().boundsInRoot
                        assertTrue(bounds.top >= onNodeWithText("每日提醒 08:00").fetchSemanticsNode().boundsInRoot.bottom)
                        assertTrue(bounds.bottom <= height && bounds.left >= 0 && bounds.right <= width, "The complete due row must fit in the viewport: $bounds")
                        onNodeWithText(due.kp.title).assertIsDisplayed()
                        val row = hasAnyAncestor(hasTestTag("review-$id"))
                        val label = if (due.overdueDays == 0) "今天到期" else "逾期 ${due.overdueDays} 天"
                        onNode(hasText(label) and row).assertIsDisplayed()
                        onNode(hasText("开始", substring = false) and row).assertIsDisplayed().performClick()
                    }
                    val expectedStarts = view.dueReviews(now).map { it.kp.id to "synthetic-variant-${it.kp.id}" }.sortedBy { it.first }
                    assertEquals(expectedStarts, opened.sortedBy { it.first },
                        "Every reachable due row must start its own prepared variant, not an arbitrary KP question")
                    assertEquals(count, opened.map { it.second }.distinct().size,
                        "One row must not reuse another row's variant")
                    assertEquals(count, view.dueReviews(now).size, "Navigation must not alter the due ledger")
                }
                // Fixed actions remain usable even after scrolling to the very last due row.
                onNodeWithText("每日提醒 08:00").assertIsDisplayed().performClick()
                assertEquals(1, reminders)
                capture("$name-due")
                if (offline) {
                    onNodeWithText("离线练习").reveal()
                    onNodeWithTag("study-option-offline-A").reveal().assertIsSelected()
                    onNodeWithText("提交本组答案").reveal().assertIsEnabled()
                } else {
                    onNodeWithText("Mac 未连接，下载未完成；已有题仍可练习。").reveal()
                }
                onNodeWithTag("open-mock").reveal()
                onNodeWithText("← 今天").assertIsDisplayed().performClick()
                assertEquals(1, backs)
            }
        } finally { tutor.close(); driver.close() }
    }

    private fun SemanticsNodeInteraction.reveal(): SemanticsNodeInteraction {
        if (!isDisplayed()) performScrollTo()
        return assertIsDisplayed()
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
    @Test fun scrollingPastDueRowsKeepsOfflineAnswers() = queue("review-offline", 1812f, 1128f, 24, offline = true)
}
