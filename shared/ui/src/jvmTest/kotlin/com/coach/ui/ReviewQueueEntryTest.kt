package com.coach.ui

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/**
 * 真实复习入口的行为：账本里答错、猜对和到期日程合成一队，未核答案只提醒不计分，
 * 退出重进仍然接得上。全部用合成账本，不碰内容、服务器或设备。
 */
@OptIn(ExperimentalTestApi::class)
class ReviewQueueEntryTest {
    private val now = 1_794_052_800_000L
    private val zone = "Europe/London"
    private val day = logicalDay(now, zone)
    private val yesterday = now - 86_400_000L

    private var seq = 0L
    private fun event(type: String, payload: JsonObject, at: Long = now): Event =
        Event(++seq, "e$seq", "C", type, recordedAt = at, occurredAt = at, deviceId = "synthetic", actor = "SYSTEM", payload = payload)

    private fun outline(vararg ops: JsonObject) = event("outline_revised", json("ops" to JsonArray(ops.toList())))
    private fun kp(id: String, title: String, vararg prereqs: String) =
        json("kpId" to id, "title" to title, "prereqs" to JsonArray(prereqs.map { JsonPrimitive(it) }))

    private fun posed(attemptId: String, questionId: String, kpId: String, keyState: String?, at: Long = yesterday) = event(
        "question_posed",
        json("attemptId" to attemptId, "questionId" to questionId, "groupId" to "g-$questionId",
            "kpIds" to JsonArray(listOf(JsonPrimitive(kpId))),
            "format" to "SINGLE_CHOICE", "mainline" to true, "posedAt" to at,
            "keyState" to keyState), at)

    private fun answered(attemptId: String, answer: String, guessed: Boolean = false, at: Long = yesterday) = event(
        "answer_submitted", json("attemptId" to attemptId, "answer" to answer, "guessed" to guessed), at)

    private fun graded(attemptId: String, score: Int, max: Int = 1, at: Long = yesterday) = event(
        "attempt_graded", json("attemptId" to attemptId, "score" to score, "max" to max, "status" to "VERIFIED"), at)

    /** 到期日程 + 同一道题的答错/猜对 + 一道未核答案。 */
    private fun mixedFacts(): List<Event> = listOf(
        outline(kp("K0", "极限基础"), kp("K1", "导数应用", "K0"), kp("K3", "未核知识点")),
        event("legacy_passed", json("kpId" to "K0")),
        event("legacy_schedule", json("kpId" to "K0", "dueDay" to day, "interval" to 15)),
        event("exam_weights_set", json("scope" to "course", "weights" to json("K0" to 1, "K1" to 3))),
        posed("a1", "q1", "K1", "VERIFIED"), answered("a1", "B", guessed = true), graded("a1", 1),
        posed("a2", "q1", "K1", "VERIFIED"), answered("a2", "C"), graded("a2", 0),
        posed("a9", "q9", "K3", keyState = null), answered("a9", "A"), graded("a9", 0),
    )

    private class Entry(private val driver: JdbcSqliteDriver, private val tutor: TutorClient, val c: CoachController, val events: List<Event>) : AutoCloseable {
        override fun close() { tutor.close(); driver.close() }
    }

    private fun entry(events: List<Event>): Entry {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val db = ClientDatabase(driver)
        for (e in events) db.ledgerQueries.putEvent(e.seq, e.courseId, LedgerJson.encodeToString(Event.serializer(), e))
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No network in this entry test")
            override suspend fun snapshot(course: String): Snapshot = error("No network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No network")
            override suspend fun command(command: WireCommand): Receipt = error("No network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No network")
        }
        val sync = SyncEngine(db, "synthetic", transport, listOf("C"), { "id" }, { "hash" })
        val tutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ now }))
        val c = CoachController("C", sync, tutor, formatClock = { "12:00" }, nowMillis = { now })
        return Entry(driver, tutor, c, events)
    }

    @Test fun realEntryMergesWrongGuessedAndDueThenResumesAfterReentry() {
        val e = entry(mixedFacts())
        val view = CourseView(e.events, project(e.events))
        val opened = mutableListOf<Pair<String, Boolean>>()
        try {
            runComposeUiTest {
                var generation by mutableStateOf(0)
                setContent {
                    CoachTheme(false) { Surface { Box(Modifier.size(420.dp, 880.dp)) {
                        key(generation) {
                            ReviewScreen(e.c, view, { id, start -> opened += id to start }, onBack = {})
                        }
                    } } }
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("3 项复习").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("3 项复习").assertIsDisplayed()
                // 未核答案报数量，但不计分；它仍按投影器的口径提醒「再看一次」。
                onNodeWithText("另有 1 道题答案还没核，只提醒再看一次，不计分").assertIsDisplayed()

                val mistake = onNodeWithTag("review-K1")
                mistake.performScrollTo().assertIsDisplayed()
                onNodeWithText(
                    "答错 · 猜对 · 到期 · 原题 q1 · 待生成变式 · 先修：极限基础 · 考纲权重 3",
                    substring = true,
                ).assertIsDisplayed()
                // 错题那一行和未核那一行都只是「再看一次」，但只有前者带答错/猜对来源。
                onAllNodesWithText("再看一次 · 今天到期").assertCountEquals(2)
                // 纯到期日程和未核答案都没有可追的原题，就不假装它是某道错题。
                onAllNodesWithText("原题未关联 · 练同知识点变式", substring = true).assertCountEquals(2)

                onAllNodesWithText("开始").onFirst().performClick()
                assertEquals(1, opened.size)
                assertEquals("K1", opened.single().first, "同一天权重高的错题排在到期日程前面")
                assertTrue(opened.single().second, "点开即直接进复习，不停在讲解")
                val saved = reviewCursor(e.c.sync, "C")
                assertNotNull(saved, "点开的那一项要写进本机状态")

                // 重新组合（等价于离开复习页再回来）：光标从本机存储读回，不靠界面内状态。
                generation += 1
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("继续").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("上次练到这里").assertIsDisplayed()
                onNodeWithText("继续").assertIsDisplayed()
                assertEquals(saved, reviewCursor(e.c.sync, "C"), "重进后仍指向同一项")
                onNodeWithText("3 项复习").assertIsDisplayed()
            }
        } finally { e.close() }
    }

    @Test fun emptyQueueSaysWhenTheNextReviewIs() {
        val events = listOf(
            outline(kp("K4", "还没到期")),
            event("legacy_passed", json("kpId" to "K4")),
            event("legacy_schedule", json("kpId" to "K4", "dueDay" to day + 3, "interval" to 15)),
        )
        val e = entry(events)
        val view = CourseView(events, project(events))
        try {
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(420.dp, 880.dp)) {
                    ReviewScreen(e.c, view, { _, _ -> }, onBack = {})
                } } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("今天没有到期的复习。").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("今天没有到期的复习。").assertIsDisplayed()
                onNodeWithText("下一次复习在", substring = true).assertIsDisplayed()
                onNodeWithText("开始").assertDoesNotExist()
            }
        } finally { e.close() }
    }

    @Test fun nothingScheduledYetSaysHowToGetThere() {
        val events = listOf(outline(kp("K5", "刚学的一节")))
        val e = entry(events)
        val view = CourseView(events, project(events))
        try {
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(420.dp, 880.dp)) {
                    ReviewScreen(e.c, view, { _, _ -> }, onBack = {})
                } } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("今天没有到期的复习。").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("还没有排进复习的题：先学一节或做一组题，答错、猜对的会自动排进来。").assertIsDisplayed()
            }
        } finally { e.close() }
    }
}
