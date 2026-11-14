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
import com.coach.domain.reviewqueue.QueueQuestion
import com.coach.domain.reviewqueue.VariantCandidate
import com.coach.domain.reviewqueue.VariantCandidatePort
import com.coach.protocol.*
import kotlinx.serialization.json.JsonArray
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * Synthetic ledger only. Exercise the shared Android/desktop queue and the typed practice route.
 *
 * Since the queue allocates a variant before it starts, a row is only startable when the candidate
 * port has a verified question for that point; starting it hands the exact allocated identity to the
 * practice host instead of asking for an arbitrary KP question.
 */
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
        // 合成题库：K 只有一道已核键、带版本与指纹的新题。缺这道候选时这一行保持缺题，入口不能开始。
        val port = VariantCandidatePort { _, _, kp ->
            if (kp == "K") listOf(VariantCandidate("C", kp,
                QueueQuestion("synthetic-variant-K", version = "v1", fingerprint = "fp-K"), keyVerified = true))
            else emptyList()
        }
        val tutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ now }))
        val c = CoachController("C", SyncEngine(db, "synthetic", transport, listOf("C"), { "id" }, { "hash" }),
            tutor, formatClock = { "12:00" }, nowMillis = { now })
        val view = CourseView(events, project(events))
        var page by mutableStateOf("today")
        val opened = mutableListOf<ReviewPracticeTarget>()
        try {
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(width.dp, height.dp)) {
                    if (integrated) CoachApp(c, dark = false, settings = { Text("每日提醒 08:00") }) else when (page) {
                        "today" -> TodayScreen(c, view, TutorUiState(), null, { page = "review" }, { _, _ -> })
                        "review" -> ReviewScreen(c, view,
                            // The legacy KP callback must never start a review: only the allocated variant may.
                            { id, start -> fail("Legacy KP route started review for $id (startNow=$start)") },
                            { page = "today" }, variantCandidates = port,
                            onOpenPractice = { opened += it; page = "practice" })
                        "practice" -> ReviewPracticeScreen(c) { page = "review" }
                        else -> error("unexpected page $page")
                    }
                } } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("1 节到期").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("1 节到期").assertIsDisplayed()
                if (integrated) onNodeWithText("每日提醒 08:00").assertDoesNotExist()
                if (!examKnown) onNodeWithText("未设期末日期", substring = true).assertIsDisplayed()
                capture("review-today-$width${if (integrated) "-app" else ""}")
                onNodeWithText("到期复习").performClick()
                onNodeWithText("1 节到期").performScrollTo().assertIsDisplayed()
                if (integrated) {
                    onNodeWithTag("open-mainline").assertDoesNotExist()
                    onNodeWithText("每日提醒 08:00").assertDoesNotExist()
                    onNodeWithTag("open-settings").performClick()
                    onNodeWithText("每日提醒 08:00").assertIsDisplayed()
                    onNodeWithTag("navigation-back").performClick()
                    onNodeWithText("每日提醒 08:00").assertDoesNotExist()
                    onNodeWithTag("open-mock").performScrollTo().assertIsDisplayed().performClick()
                    onNodeWithText("模拟考要连接 Mac。").assertIsDisplayed()
                    onNodeWithText("← 复习").performClick()
                    onNodeWithTag("open-mock").performScrollTo().assertIsDisplayed()
                    onNodeWithText("1 节到期").performScrollTo().assertIsDisplayed()
                }
                capture("review-queue-$width${if (integrated) "-app" else ""}")
                if (integrated) {
                    // CoachApp 还没有接题库候选端口：这一行仍是「练同知识点变式」，
                    // 点「开始」只能如实说明还没有已备变式，不会退回任意考点题、也不会换一题。
                    onNodeWithText("练同知识点变式", substring = true).assertIsDisplayed()
                    onNodeWithText("已备", substring = true).assertDoesNotExist()
                    onNodeWithText("开始", substring = false).performScrollTo().assertIsDisplayed().performClick()
                    onNodeWithText("这项复习还没有可用的已核变式", substring = true).assertIsDisplayed()
                    onNodeWithText("复习这道题").assertDoesNotExist()
                    onNodeWithText("1 节到期").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("open-mock").performScrollTo().assertIsDisplayed().performClick()
                    onNodeWithText("模拟考要连接 Mac。").assertIsDisplayed()
                    onNodeWithText("← 复习").performClick()
                } else {
                    // 已备变式才显示可开始；这一行真的把「这道变式」交给练习宿主，而不是任意考点题。
                    onNodeWithText("已备 1 道变式", substring = true).assertIsDisplayed()
                    onNodeWithText("开始", substring = false).performScrollTo().assertIsDisplayed().performClick()
                    val target = opened.single()
                    assertEquals("K", target.kpId)
                    assertEquals("synthetic-variant-K", target.question.id)
                    assertEquals("v1", target.question.version)
                    assertEquals("fp-K", target.question.fingerprint)
                    assertTrue(target.attemptId.isNotBlank(), "The queue must hand over the allocated attempt")
                    assertEquals(target.attemptId, reviewCursorAttempt(c.sync, "C"), "The allocated identity is persisted")
                    assertEquals(target, reviewPracticeTarget(c.sync, "C", c.tutor.agentRunId),
                        "A fresh read must restore the same exact identity")
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("复习这道题").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("复习这道题").assertIsDisplayed()
                    // 本机没有服务端权威分配（也没有课程接口）：身份已核，精确宿主如实拒绝开始，
                    // 不伪造题面、不回落考点题，原作答保留。
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("这道题暂时无法开始。", substring = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithText("这道题暂时无法开始。", substring = true).assertIsDisplayed()
                    onNodeWithText("提交答案").assertDoesNotExist()
                    onNodeWithText("← 复习").performClick()
                    onNodeWithText("1 节到期").performScrollTo().assertIsDisplayed()
                    // 回到队列：这一行仍是刚才那一条，入口变成「继续」，光标没被换到别的题。
                    onNodeWithText("上次练到这里").assertIsDisplayed()
                    onNodeWithText("继续", substring = false).assertExists()
                    assertEquals(target.attemptId, reviewCursorAttempt(c.sync, "C"))
                }
                onNodeWithText("开始讲解").assertDoesNotExist()
                if (integrated) {
                    onNodeWithText("1 节到期").performScrollTo().assertIsDisplayed()
                    onNodeWithText("开始", substring = false).assertExists()
                    onNode(hasText("今天") and hasClickAction()).performClick()
                    onNode(hasText("课程") and hasClickAction()).performClick()
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
