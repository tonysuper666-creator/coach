package com.coach.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.server.*
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

private val syntheticFullEssay = listOf(
    "Opening: This invented example explains why one counter and another counter form a pair. No course material is used.",
    "Definition: A pair contains two distinct objects. A label describes the grouping; it does not create another object.",
    "Method: Put the first counter on an empty table. Put the second counter beside it. Count each counter exactly once.",
    "Worked example: Name the counters red and blue. Red contributes one and blue contributes one. Together they contribute two.",
    "Check: Counting blue first and red second leaves the total unchanged. Changing order changes neither the objects nor their number.",
    "Boundary: A single counter has no second member and is not a pair. Adding a third counter produces a group of three rather than a pair.",
    "Counterexample: Naming the same counter twice does not make two objects. Distinctness matters when explaining the grouping.",
    "Conclusion: Two distinct counters form a pair. The reference answer explains both the number and the grouping."
).joinToString("\n\n")

/** Actual authenticated Ktor routes, CourseHttp, mainline screen and a real SQLite close/reopen. */
@OptIn(ExperimentalTestApi::class)
class MainlineEssayFlowTest {
    @Test fun fullEssayReturnsAsksRestoresAndRefusesRevokedAccount() {
        walk(412, 915, false)
        walk(933, 704, true)
    }

    private fun walk(width: Int, height: Int, dark: Boolean) {
        val root = Files.createTempDirectory("synthetic-essay-ui")
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(Files.createDirectories(root.resolve("ledger")))
        val token = "synthetic-essay-ui-token-32-characters"
        store.enroll("phone", token)
        val actor = LedgerActor(serviceScope, store)
        val raw = LedgerJson.parseToJsonElement(javaClass.getResource("/question-ladder/synthetic-runtime-identity-v1.json")!!.readText()).jsonObject
        fun answerSource(answer: JsonElement): SyntheticLadderRuntime {
            val questions = raw["questions"]!!.jsonArray.map { q -> if (q.jsonObject.str("id") == "SYN-Q2")
                JsonObject(q.jsonObject + ("model_answer" to answer)) else q }
            return SyntheticLadderRuntime.parse(JsonObject(raw + ("questions" to JsonArray(questions))))
        }
        val runtime = answerSource(JsonPrimitive(syntheticFullEssay))
        val currentRuntime = AtomicReference(runtime)
        val mainline = Mainline(MockLibrary(Files.createDirectories(root.resolve("papers"))),
            syntheticCapabilities = setOf("SYN-add"), syntheticRuntimeProvider = { course -> currentRuntime.get().takeIf { it.courseId == course } })
        val pushes = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ledgerRoutes(actor, pushes); mainlineRoutes(actor, pushes, mainline) }
        server.start(false)
        val port = runBlocking { server.engine.resolvedConnectors().single().port }
        val http = CourseHttp("http://127.0.0.1:$port", "phone", token, "SYN-COURSE-A")
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No ledger network")
            override suspend fun snapshot(course: String): Snapshot = error("No ledger network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No ledger network")
            override suspend fun command(command: WireCommand): Receipt = error("No ledger network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No ledger network")
        }
        val file = root.resolve("client.sqlite")
        var driver = JdbcSqliteDriver("jdbc:sqlite:$file")
        val sha: (ByteArray) -> String = { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
        fun controller() = CoachController("SYN-COURSE-A", SyncEngine(ClientDatabase(driver), "phone", transport,
            listOf("SYN-COURSE-A"), { "id" }, sha),
            TutorClient("http://127.0.0.1:1", "phone", token, TutorModel({ 1793188800000L })),
            formatClock = { "12:00" }, nowMillis = { 1793188800000L }, course = http)
        var c: CoachController
        var answer: JsonObject? = null
        var readerOffset = 0L
        var quizOffset = 0L
        try {
            ClientDatabase.Schema.create(driver)
            c = controller()
            c.sync.saveMainlineState("SYN-COURSE-A", json("week" to 1, "entry" to "quiz"))
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat()), density = Density(1f)) {
                setContent { CoachTheme(dark) { Surface(Modifier.fillMaxSize()) { MainlineScreen(c, CourseView(emptyList(), project(emptyList())), {}, {}, {}, {}) } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-submit").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("How many objects are one and one?").assertExists()
                onNodeWithText("Opening:", substring = true).assertDoesNotExist()
                onNodeWithTag("mainline-option-1").performScrollTo().performClick()
                onNodeWithTag("mainline-submit").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-read-essay").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("ladder-read-essay").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-essay-full-text").fetchSemanticsNodes().isNotEmpty() }
                answer = c.sync.mainlineState("SYN-COURSE-A", "week:1")["result"]!!.jsonObject
                quizOffset = c.sync.mainlineState("SYN-COURSE-A", "week:1").num("quizScroll")
                assertFalse(answer!!.flag("independentCorrect")); assertTrue(answer!!.flag("syntheticOnly"))
                onNodeWithText(syntheticFullEssay).assertExists()
                onNodeWithText("SYN-ANSWER-2", substring = true).assertDoesNotExist()
                captureLessonEvidence("essay-reader-open-${width}x$height", width, height)
                onNodeWithText("Explains the grouping.").performScrollTo().assertIsDisplayed()
                waitUntil(timeoutMillis = 10_000) { ((http.readerState(c.sync, 1)["essayReader"] as? JsonObject)?.num("scroll") ?: 0) > 0 }
                readerOffset = http.readerState(c.sync, 1)["essayReader"]!!.jsonObject.num("scroll")
                captureLessonEvidence("essay-reader-rubric-${width}x$height", width, height)
                onNodeWithTag("mainline-essay-ask").performClick()
                onNodeWithTag("ladder-question").performTextInput("Why are two counters a pair?")
                onNodeWithText("发送").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("课程依据服务暂不可用，无法确认。").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("mainline-essay-ask").performClick()
                onNodeWithText("Explains the grouping.").assertIsDisplayed()
                assertEquals(readerOffset, http.readerState(c.sync, 1)["essayReader"]!!.jsonObject.num("scroll"))
                onNodeWithTag("mainline-essay-close").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-essay-reader").fetchSemanticsNodes().isEmpty() }
                assertEquals(answer, c.sync.mainlineState("SYN-COURSE-A", "week:1")["result"])
                assertEquals(quizOffset, c.sync.mainlineState("SYN-COURSE-A", "week:1").num("quizScroll"))
                onNodeWithTag("ladder-read-essay").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-essay-full-text").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Explains the grouping.").assertIsDisplayed()
            }
            val saved = c.sync.mainlineState("SYN-COURSE-A", "week:1")
            assertFalse(saved.toString().contains("Opening:"))
            assertFalse(http.readerState(c.sync, 1).toString().contains("Opening:"))
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:$file")
            c = controller()
            assertEquals(saved, c.sync.mainlineState("SYN-COURSE-A", "week:1"))
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat()), density = Density(1f)) {
                setContent { CoachTheme(dark) { Surface(Modifier.fillMaxSize()) { MainlineScreen(c, CourseView(emptyList(), project(emptyList())), {}, {}, {}, {}) } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-essay-full-text").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Explains the grouping.").assertIsDisplayed()
                assertEquals(answer, c.sync.mainlineState("SYN-COURSE-A", "week:1")["result"])
                assertEquals(readerOffset, http.readerState(c.sync, 1)["essayReader"]!!.jsonObject.num("scroll"))
                captureLessonEvidence("essay-reader-restored-${width}x$height", width, height)
                onNodeWithTag("mainline-essay-close").performClick()
                currentRuntime.set(answerSource(JsonNull))
                onNodeWithTag("ladder-read-essay").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("全文读取尚未接通，示范全文与评分点仍待核。").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("mainline-essay-full-text").assertDoesNotExist()
                captureLessonEvidence("essay-reader-unavailable-${width}x$height", width, height)
                currentRuntime.set(answerSource(JsonPrimitive("A changed resource under the same version.")))
                onNodeWithTag("mainline-essay-retry").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("示范资料身份或版本已改变，请返回题目后重试。").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("mainline-essay-full-text").assertDoesNotExist()
                captureLessonEvidence("essay-reader-wrong-version-${width}x$height", width, height)
                currentRuntime.set(runtime)
                onNodeWithTag("mainline-essay-retry").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-essay-full-text").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Explains the grouping.").assertIsDisplayed()
                onNodeWithTag("mainline-essay-close").performClick()
                runBlocking { actor.execute { revokeDevice("phone") } }
                onNodeWithTag("ladder-read-essay").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("当前设备无权读取这份示范，请重新确认课程访问权限。").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("mainline-essay-full-text").assertDoesNotExist()
                assertEquals(answer, c.sync.mainlineState("SYN-COURSE-A", "week:1")["result"])
                captureLessonEvidence("essay-reader-revoked-${width}x$height", width, height)
                onNodeWithTag("mainline-essay-close").performClick()
                onNodeWithTag("mainline-next").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-checkpoint").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("合成学习范围：4 个考点；5 项能力。不计掌握。").assertExists()
                onNodeWithTag("ladder-scope-point-3").performScrollTo().assertIsDisplayed()
                onNodeWithText("考点 4 · 教学题待补，进度保留；可回本周查看其他可学内容。").assertExists()
                captureLessonEvidence("essay-scope-missing-${width}x$height", width, height)
            }
            currentRuntime.set(SyntheticLadderRuntime.parse(JsonObject(raw + mapOf(
                "questions" to JsonArray(emptyList()), "prerequisite_aliases" to JsonArray(emptyList())))))
            runBlocking { actor.execute { enroll("phone", token) } }
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${root.resolve("empty-client.sqlite")}")
            ClientDatabase.Schema.create(driver)
            c = controller()
            c.sync.saveMainlineState("SYN-COURSE-A", json("week" to 1, "entry" to "quiz"))
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat()), density = Density(1f)) {
                setContent { CoachTheme(dark) { Surface(Modifier.fillMaxSize()) { MainlineScreen(c, CourseView(emptyList(), project(emptyList())), {}, {}, {}, {}) } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-checkpoint").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("合成学习范围：4 个考点；5 项能力。不计掌握。").assertExists()
                onNodeWithTag("mainline-submit").assertDoesNotExist()
                onNodeWithTag("ladder-scope-point-3").performScrollTo().assertIsDisplayed()
                onNodeWithText("这轮做了 0 题。看过讲解和答对是两回事，学习状态按真实作答保留。").assertExists()
                val headings = onAllNodesWithText("第 1 周 · 选择题", useUnmergedTree = true).fetchSemanticsNodes()
                val paragraphs = onAllNodesWithText("教学题", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
                assertTrue(headings.all { heading -> paragraphs.none { it.boundsInRoot.overlaps(heading.boundsInRoot) } },
                    "A question heading must not paint over the scope or missing-candidate text")
                captureLessonEvidence("essay-scope-empty-${width}x$height", width, height)
                onNodeWithText("回本周").performScrollTo().assertIsEnabled().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-checkpoint").fetchSemanticsNodes().isEmpty() }
            }
            assertTrue(runBlocking { actor.execute { events("SYN-COURSE-A").isEmpty() } })
        } finally {
            driver.close(); http.close(); server.stop(0, 500); runBlocking { actor.close() }; serviceScope.cancel(); root.toFile().deleteRecursively()
        }
    }
}
