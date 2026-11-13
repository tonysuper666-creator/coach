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
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.domain.reviewqueue.QueueAvailability
import com.coach.domain.reviewqueue.QueueQuestion
import com.coach.domain.reviewqueue.VariantCandidate
import com.coach.domain.reviewqueue.VariantCandidatePort
import com.coach.protocol.*
import com.coach.server.ledgerRoutes
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.*

/**
 * 真实复习入口的行为：账本里答错、猜对和到期日程合成一队，未核答案只提醒不计分，
 * 退出重进仍然接得上。全部用合成账本，不碰内容、服务器或设备。
 */
@OptIn(ExperimentalTestApi::class)
class ReviewQueueEntryTest {
    @Test fun absentExactPracticeHostNeverFallsBackToAnArbitraryKpQuestion() {
        val port = VariantCandidatePort { _, _, kp ->
            if (kp == "K1") listOf(VariantCandidate("C", kp, QueueQuestion("exact-variant", "v2", "fp2"),
                keyVerified = true)) else emptyList()
        }
        val e = entry(mixedFacts())
        val view = CourseView(e.events, project(e.events))
        val opened = mutableListOf<String>()
        try {
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(420.dp, 880.dp)) {
                    ReviewScreen(e.c, view, { kp, _ -> opened += kp }, onBack = null, variantCandidates = port)
                } } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("已备 1 道变式", substring = true).fetchSemanticsNodes().isNotEmpty() }
                val board = reviewBoard("C", view, day, port)
                val index = board.items.indexOfFirst { it.variantAttemptId != null }
                onAllNodesWithText("开始")[index].performScrollTo().performClick()
                runOnIdle { assertTrue(opened.isEmpty(), "No exact practice host: a KP callback must never start a different question") }
                onNodeWithText("这道题的练习入口尚未接通，尚未开始。", substring = true).assertIsDisplayed()
            }
        } finally { e.close() }
    }

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

    private class Entry(private val driver: JdbcSqliteDriver, private val tutor: TutorClient, val c: CoachController, val events: List<Event>, private val course: CourseHttp? = null) : AutoCloseable {
        override fun close() { course?.close(); tutor.close(); driver.close() }
    }

    private fun entry(events: List<Event>, course: CourseHttp? = null): Entry {
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
        val c = CoachController("C", sync, tutor, formatClock = { "12:00" }, nowMillis = { now }, course = course)
        return Entry(driver, tutor, c, events, course)
    }

    @Test fun realEntryKeepsUnknownDueIdentityAndDoesNotStartWithoutAReadyVariant() {
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
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("4 项复习").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("4 项复习").assertIsDisplayed()
                // 未核答案报数量，但不计分；它仍按投影器的口径提醒「再看一次」。
                onNodeWithText("另有 1 道题答案还没核，只提醒再看一次，不计分").assertIsDisplayed()

                // 到期日程本身没有题号：它不能并进上面那道错题，也不许显示成「答错 · 猜对 · 到期」。
                onNodeWithText(
                    "答错 · 猜对 · 原题 q1 · 待生成变式 · 先修：极限基础 · 考纲权重 3",
                    substring = true,
                ).performScrollTo().assertIsDisplayed()
                onAllNodesWithText("到期 · 原题 q1", substring = true).assertCountEquals(0)
                // 错题那一行和未核那一行都只是「再看一次」，但只有前者带答错/猜对来源。
                onAllNodesWithText("再看一次 · 今天到期").assertCountEquals(2)
                // 纯到期日程、该知识点的重练日、未核答案都没有可追的原题，就不假装它是某道错题。
                onAllNodesWithText("原题未关联 · 练同知识点变式", substring = true).assertCountEquals(3)

                onAllNodesWithText("开始").onFirst().performClick()
                assertTrue(opened.isEmpty(), "缺题不能退回任意考点题")
                assertNull(reviewCursor(e.c.sync, "C"), "缺题不覆盖已有选择")
                onNodeWithText("这项复习还没有可用的已核变式", substring = true).assertIsDisplayed()

                // 重新组合（等价于离开复习页再回来）：光标从本机存储读回，不靠界面内状态。
                generation += 1
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("4 项复习").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("上次练到这里").assertDoesNotExist()
                assertNull(reviewCursor(e.c.sync, "C"), "重进不能把未开始的题冒充续练")
                onNodeWithText("4 项复习").assertIsDisplayed()
            }
        } finally { e.close() }
    }

    @Test fun aPreparedVariantFromThePoolTurnsExactlyOneGapIntoAReadyRow() {
        // 合成题库端口：只给 K1 一道已核键的新题，其余考点如实没有候选。
        val port = VariantCandidatePort { _, _, kp ->
            if (kp == "K1") listOf(VariantCandidate("C", "K1", QueueQuestion("q-synthetic-variant"),
                availability = QueueAvailability.VALID, keyVerified = true,
                variantGroupId = "synthetic-group", agentRunId = null)) else emptyList()
        }
        val e = entry(mixedFacts())
        val view = CourseView(e.events, project(e.events))
        val opened = mutableListOf<Pair<String, Boolean>>()
        try {
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(420.dp, 880.dp)) {
                    ReviewScreen(e.c, view, { _, _ -> fail("Legacy KP callback must not run") }, onBack = {},
                        variantCandidates = port, onOpenPractice = { target ->
                            assertEquals("q-synthetic-variant", target.question.id)
                            assertEquals("C", target.courseId)
                            opened += target.kpId to true
                        })
                } } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("4 项复习").fetchSemanticsNodes().isNotEmpty() }
                // 补上变式不改变队列条目的数量，也不把缺题说成可练。
                onNodeWithText("4 项复习").assertIsDisplayed()
                onAllNodesWithText("已备 1 道变式", substring = true).assertCountEquals(1)
                onAllNodesWithText("已备 2 道变式", substring = true).assertCountEquals(0)

                val board = reviewBoard("C", view, day, port)
                val ready = board.items.single { it.variantCount > 0 }
                val expectedAttempt = assertNotNull(ready.variantAttemptId)
                assertEquals(1, board.items.count { it.variantCount > 0 }, "一道题不能被两行同时占用")

                // 点开这一行：本机记下的必须是这道变式的作答身份，而不是空或另一道题。
                // 这里只证到「身份已保存在本机」；练习路由还没有读取它，所以不断言练习真的开始了这一道。
                onAllNodesWithText("开始")[board.items.indexOf(ready)].performScrollTo().performClick()
                assertEquals(listOf("K1" to true), opened)
                assertEquals(expectedAttempt, reviewCursorAttempt(e.c.sync, "C"))
            }
        } finally { e.close() }
    }

    @Test fun theRealPoolOverHttpTurnsExactlyOneGapIntoAPreparedRow() {
        // 真实隔离服务端：同一个自建 SQLite 题库 + 真鉴权 + 真 /pool，不是内存里拼一个列表。
        val dir = Files.createTempDirectory("review-pool-entry")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(dir).also { it.enroll("phone", "synthetic-private-token-32-characters") }
        val actor = LedgerActor(scope, store)
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8))
        }
        server.start(false)
        val port = runBlocking { server.engine.resolvedConnectors().single().port }
        val http = CourseHttp("http://127.0.0.1:$port",
            "phone", "synthetic-private-token-32-characters", "C")
        // K1 一道活题、一道未核键；K2 一道别的考点的活题。都没有出过题。
        runBlocking {
            actor.execute {
                val options = listOf(Option("o1", "synthetic one"), Option("o2", "synthetic two"))
                registerQuestion("q-pool-live", "C", "fp-live", "SINGLE_CHOICE", "Synthetic pool live", "VERIFIED", "o1", options, listOf("K1"))
                registerQuestion("q-pool-unverified", "C", "fp-unv", "SINGLE_CHOICE", "Synthetic pool unverified", "UNREVIEWED", "o1", options, listOf("K1"))
                registerQuestion("q-pool-other", "C", "fp-other", "SINGLE_CHOICE", "Synthetic pool other point", "VERIFIED", "o1", options, listOf("K2"))
            }
        }
        val e = entry(mixedFacts(), http)
        val view = CourseView(e.events, project(e.events))
        val opened = mutableListOf<Pair<String, Boolean>>()
        try {
            // 期望值也来自同一次真实响应：未核键和别的考点都进不来，只有这道活题能练。
            val fetched = runBlocking { assertNotNull(http.reviewVariantCandidates("K1")) }
            assertEquals(listOf("q-pool-live"), fetched.map { it.question.id })
            val expected = reviewBoard("C", view, day, VariantCandidatePort { _, _, kp -> if (kp == "K1") fetched else emptyList() })
            val ready = expected.items.single { it.variantCount > 0 }
            val attempt = assertNotNull(ready.variantAttemptId)
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(420.dp, 880.dp)) {
                    // 不注入端口：入口只靠 CoachController.course 走真实 /pool。
                    ReviewScreen(e.c, view, { _, _ -> fail("Legacy KP callback must not run") }, onBack = {},
                        onOpenPractice = { target ->
                            assertEquals("q-pool-live", target.question.id)
                            assertEquals(attempt, target.attemptId)
                            opened += target.kpId to true
                        })
                } } } }
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithText("已备 1 道变式", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                // 拉到一道题不改变队列条目的数量，也不把原题身份换成变式身份。
                onNodeWithText("4 项复习").assertIsDisplayed()
                onAllNodesWithText("已备 1 道变式", substring = true).assertCountEquals(1)
                onAllNodesWithText("已备 2 道变式", substring = true).assertCountEquals(0)
                // 拿到题的那一行显示的身份，就是域层按真实候选算出来的那一个；其余行仍是缺口。
                onAllNodesWithText(ready.identityLabel, substring = true).assertCountEquals(1)
                onAllNodesWithText("已备", substring = true).assertCountEquals(1)
                onAllNodesWithText("开始")[expected.items.indexOf(ready)].performScrollTo().performClick()
                assertEquals(listOf("K1" to true), opened)
                assertEquals(attempt, reviewCursorAttempt(e.c.sync, "C"))
            }
        } finally {
            e.close(); server.stop(0, 0); scope.cancel(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun unknownLedgerFactsAskForSnapshotInsteadOfShowingAnEmptyDoneState() {
        // 这一版读不懂的账本事实：入口不能崩，也不能显示成「今天没有到期的复习」。
        val events = listOf(event("future_synthetic_fact_v99", json()))
        val e = entry(events)
        val view = CourseView(events, project(events))
        try {
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(420.dp, 880.dp)) {
                    ReviewScreen(e.c, view, { _, _ -> }, onBack = {})
                } } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("今天复习什么还确认不了。").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("今天复习什么还确认不了。").assertIsDisplayed()
                onNodeWithText("要先取服务端的权威快照", substring = true).assertIsDisplayed()
                onNodeWithText("本机已经做过的练习还在", substring = true).assertIsDisplayed()
                onNodeWithText("今天没有到期的复习。").assertDoesNotExist()
                onNodeWithText("开始").assertDoesNotExist()
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
