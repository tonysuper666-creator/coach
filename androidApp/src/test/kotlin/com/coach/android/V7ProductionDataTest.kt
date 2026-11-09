package com.coach.android

import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.ui.*
import com.coach.ui.v7.*
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Full app → SQLite replica → projection, tutor history and the existing source HTTP client. No PageDetails injection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1224dp-h924dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V7ProductionDataTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var driver: AndroidSqliteDriver
    private lateinit var tutor: TutorClient
    private lateinit var http: CourseHttp
    private lateinit var server: MockWebServer
    private lateinit var controller: CoachController
    private val ledger = mutableListOf<Event>()
    private val selectedWeeks = java.util.concurrent.CopyOnWriteArrayList<Int>()
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val now = 1_794_052_800_000L
    private val prose = """
        保险把看病时自己掏的钱压低了，于是同一个人在**价格变低后会多买医疗服务**。这不是骗保，而是对价格的正常反应★[^1]：需求曲线向右下倾斜，自付价格从全价降到共付比例，需求量就沿曲线往右走。

        多出来的那一段消费，**边际收益低于社会承担的边际成本**，中间那块三角形就是福利损失。共付和起付线的作用，是把个人面对的价格往回拉一些，用一点风险分担换回一部分效率。

        所以考试里看到“保险越全越好”这类说法，先问两件事：需求对价格有多敏感；多出来的医疗到底值不值它的成本。

        反过来，事前道德风险说的是投保之后人会少做预防。两者都来自“有人替你付钱”，但政策工具不同。

        [^1]: 课上 · 合成录音 14:32：共付把价格信号还给病人。
    """.trimIndent()
    private fun add(type: String, payload: JsonObject, thread: String? = null): Event = Event(
        ledger.size + 1L, "synthetic-${ledger.size}", "SYNTHETIC-V7", type, recordedAt = now,
        occurredAt = now, deviceId = "synthetic", actor = "SYSTEM", threadId = thread, payload = payload).also(ledger::add)

    @Before fun loadThroughClients() {
        val titles = listOf("道德风险是什么", "事前道德风险", "事后道德风险", "共付和起付线", "供方诱导需求", "实验证据怎么读", "政策含义")
        add("outline_revised", json("course" to json("name" to "健康经济学"), "modules" to JsonArray(listOf(json("id" to "M", "title" to "道德风险"))),
            "knowledgePoints" to JsonArray(titles.mapIndexed { i, title -> json("id" to "K$i", "title" to "【W8】$title", "moduleId" to "M",
                "sourceAnchor" to json("docId" to "synthetic-textbook", "pdfPage" to 214, "bookPage" to 214)) } +
                listOf(json("id" to "previous", "title" to "【W6】保险基础"), json("id" to "next", "title" to "【W9】成本效果")))))
        add("study_scope_set", json("week" to 8, "kpIds" to titles.indices.map { "K$it" }))
        add("explanation_delivered", json("kpId" to "K0"))
        add("explanation_delivered", json("kpId" to "K1"))
        add("explanation_delivered", json("kpId" to "K2"))
        add("kp_overridden", json("kpId" to "K0", "kind" to "MARK_PASSED"))
        add("kp_overridden", json("kpId" to "K1", "kind" to "MARK_PASSED"))
        add("question_posed", json("attemptId" to "A1", "questionId" to "Q1", "groupId" to "G1", "kpIds" to listOf("K2"),
            "format" to "SINGLE_CHOICE", "keyState" to "UNREVIEWED", "stem" to "共付提高后，需求沿曲线怎样变化？",
            "options" to JsonArray(listOf(json("optionId" to "a", "text" to "沿曲线向左"), json("optionId" to "b", "text" to "需求完全不变"))),
            "letterMap" to json("a" to "A", "b" to "B")), "kp:K2")
        add("answer_submitted", json("attemptId" to "A1", "answer" to "B"), "kp:K2")
        add("attempt_graded", json("attemptId" to "A1", "score" to 0, "max" to 1, "status" to "GRADED",
            "feedback" to "价格升高，需求沿曲线向左移动。这是合成练习的讲解。"), "kp:K2")
        add("note_saved", json("noteId" to "N1", "kpId" to "K2", "content" to "> 价格变低后会多买医疗服务\n\n别把投保之后的行为和投保之前的信息不对称搞混。"))
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.path.orEmpty()
                    val body = when {
                        request.path.orEmpty().endsWith("/mainline/8/answer") -> json("answer" to 0, "choice" to 1, "status" to "missed", "tier" to "detail", "kpId" to "K2",
                            "explanation" to json("correct" to "合成讲解：自付价格提高，需求沿曲线向左移动。", "source" to "合成教材 p.214"))
                        request.path.orEmpty().startsWith("/v1/courses/SYNTHETIC-V7/mainline/8?") -> json("week" to 8,
                            "items" to JsonArray(listOf(json("id" to "Q1", "tier" to "detail", "stem" to "合成选择题：共付提高会怎样？", "options" to listOf("需求下降", "需求不变"), "status" to "new", "keyState" to "VERIFIED"))))
                        request.path.orEmpty().endsWith("/mainline") -> json("weeks" to JsonArray(listOf(json("week" to 8, "title" to "道德风险", "coverage" to json("facts" to 1, "questions" to 1)))))
                        request.path.orEmpty().endsWith("/sources") -> json("docs" to JsonArray(listOf(json("docId" to "synthetic-textbook", "title" to "合成教材 · 健康经济学",
                            "firstPdfPage" to 214, "lastPdfPage" to 214, "firstBookPage" to 214, "lastBookPage" to 214))))
                        request.path.orEmpty().endsWith("/pages/214") -> json("docId" to "synthetic-textbook", "pdfPage" to 214, "bookPage" to 214,
                            "text" to "SYNTHETIC TEXTBOOK — p.214\n\nCopayment and demand\n\nA higher copayment raises the price paid by the patient. Quantity demanded moves left along the demand curve.\n\nThis page is synthetic UI evidence, not private course material.")
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody(body.toString()).setHeader("Content-Type", "application/json")
                }
            }
            start()
        }
        driver = AndroidSqliteDriver(ClientDatabase.Schema, ApplicationProvider.getApplicationContext(), null)
        val transport = object : LedgerTransport {
            private fun hello() = Hello("synthetic", ledger.size.toLong(), 0, 0)
            override suspend fun hello(epoch: String?) = hello()
            override suspend fun snapshot(course: String) = Snapshot(hello(), course, ledger.toList(), project(ledger))
            override suspend fun events(course: String, after: Long) = EventsPage("synthetic", ledger.filter { it.seq > after }, ledger.size.toLong())
            override suspend fun command(command: WireCommand): Receipt {
                val body = LedgerJson.decodeFromString<CommandBody>(command.payloadBytes)
                val event = if (body.type == "save_note") add("note_saved", body.payload) else {
                    check(body.type == "set_study_scope")
                    val week = (body.payload["week"] as JsonPrimitive).int
                    selectedWeeks += week
                    val ids = CourseView(ledger, project(ledger)).weekKps(week).map { it.id }
                    add("study_scope_set", json("week" to week, "kpIds" to ids))
                }
                return Receipt(command.commandId, "OK", listOf(event.seq), epoch = "synthetic", backedUpThroughSeq = 0, result = json("target" to ""))
            }
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No external ledger")
        }
        val sync = SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf("SYNTHETIC-V7"), { java.util.UUID.randomUUID().toString() }, { "hash" })
        runBlocking { sync.syncOnce() }
        tutor = TutorClient("http://127.0.0.1:1", "synthetic", "synthetic", TutorModel({ now }))
        tutor.model.onConnection(true)
        tutor.model.onFrame(json("t" to "msg.snapshot", "threadId" to "kp:K2", "msg" to "reading", "text" to prose, "status" to "complete").toString())
        tutor.model.onFrame(json("t" to "msg.history", "threadId" to "kp:K2", "messages" to emptyList<String>()).toString())
        http = CourseHttp(server.url("/").toString().removeSuffix("/"), "synthetic", "synthetic", "SYNTHETIC-V7")
        controller = CoachController("SYNTHETIC-V7", sync, tutor, formatClock = { "14:32" }, nowMillis = { now }, course = http)
    }

    @After fun close() { tutor.close(); http.close(); server.shutdown(); driver.close() }

    private fun show(reading: Boolean, outer: Boolean = false) {
        val width = if (outer) 624 else 1224
        val height = if (outer) 986 else 924
        RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-xhdpi")
        compose.setContent { Box(Modifier.requiredSize(width.dp, height.dp)) { CoachApp(controller, initialKp = if (reading) "K2" else null, dark = false) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("v7-paper").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { requests.any { it.endsWith("/pages/214") } &&
            if (reading) compose.onAllNodesWithTag("v7-source-thumbnail").fetchSemanticsNodes().isNotEmpty()
            else outer || compose.onAllNodesWithText("合成教材 · 健康经济学").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun capture(name: String) {
        if (System.getProperty("roborazzi.test.record").toBoolean()) {
            val dir = File(System.getProperty("coach.v7.output"), "production").apply { mkdirs() }
            compose.onNodeWithTag("v7-desk").captureRoboImage(File(dir, name).absolutePath)
        }
    }

    @Test fun innerReadingUsesLoadedSourceAndLedgerQuestion() {
        show(reading = true)
        compose.onNodeWithText("★  1  课上强调").assertExists()
        compose.onNodeWithText("共付提高后，需求沿曲线怎样变化？").assertExists()
        compose.onNodeWithText("我的批注", substring = true).assertExists()
        compose.onNodeWithTag("v7-reading-paragraph-1").performTextInputSelection(androidx.compose.ui.text.TextRange(0, 15))
        compose.onNodeWithText("选中这一句").assertExists()
        capture("inner-read-light.png")
        compose.onNodeWithText("打开原页 →").performClick()
        compose.onNodeWithTag("source-page-label").assertTextContains("书页 p.214", substring = true)
        compose.onNodeWithTag("source-back").performClick()
        compose.onNodeWithTag("v7-reading-paragraph-1").performTextInputSelection(androidx.compose.ui.text.TextRange(0, 15))
        compose.onNodeWithText("一句批注").performClick()
        compose.onNodeWithTag("v7-note-input").performTextInput("新增的合成批注")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("新增的合成批注", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("v7-reading-paragraph-1").performTextInputSelection(androidx.compose.ui.text.TextRange(0, 15))
        compose.onNodeWithText("再讲一遍").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("请再讲一遍：", substring = true)
    }

    @Test fun innerWeekUsesProgressAndSourceShelf() { show(false); compose.onNodeWithText("构答").assertDoesNotExist(); capture("inner-week-light.png") }

    @Test fun outerWeekCanChangeWeekAndOpenRecords() {
        show(false, true)
        capture("outer-week-light.png")
        compose.onNodeWithTag("v7-week-picker").performClick()
        compose.onNodeWithText("第 6 周").performClick()
        compose.waitUntil(10_000) { selectedWeeks.contains(6) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("v7-next-week") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("v7-next-week").performClick()
        compose.waitUntil(10_000) { selectedWeeks.lastOrNull() == 8 }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("v7-previous-week") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("v7-previous-week").performClick()
        compose.waitUntil(10_000) { selectedWeeks.lastOrNull() == 6 }
        compose.onNodeWithText("Coach").performClick()
        compose.onNodeWithText("记录").performClick()
        compose.onNodeWithTag("v7-paper").assertDoesNotExist()
    }

    @Test fun outerQuizLoadsTheAnsweredKpsSourceAtFullColumnWidth() {
        RuntimeEnvironment.setQualifiers("w624dp-h986dp-xhdpi")
        compose.setContent { Box(Modifier.requiredSize(624.dp, 986.dp)) { CoachApp(controller, dark = false, initialTab = AppTab.REVIEW) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("open-mainline").fetchSemanticsNodes().isNotEmpty() }
        // PR117 made the card a container with separate continue/read/week-picker controls.
        compose.onNodeWithText("选其他周").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("mainline-week-8").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mainline-week-8").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("mainline-skip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mainline-skip").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("mainline-option-1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("v7-source-card").assertDoesNotExist()
        compose.onNodeWithTag("mainline-option-1").performScrollTo().performClick()
        compose.onNodeWithTag("mainline-submit").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("v7-source-card").fetchSemanticsNodes().isNotEmpty() }
        val optionWidth = compose.onNodeWithTag("mainline-option-0").getUnclippedBoundsInRoot().let { (it.right - it.left).value }
        val sourceWidth = compose.onNodeWithTag("v7-source-card").getUnclippedBoundsInRoot().let { (it.right - it.left).value }
        assertEquals(optionWidth, sourceWidth, .5f)
        compose.onNodeWithText("打开原页 →").performScrollTo().performClick()
        compose.onNodeWithTag("source-page-label").assertTextContains("书页 p.214", substring = true)
        compose.onNodeWithTag("source-back").performClick()
        compose.onNodeWithTag("mainline-option-1").assertExists()
    }

    @Test fun systemReducedMotionReachesTheThemeAndRespondsToChanges() {
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.setContent { CoachTheme(false) { Text(if (LocalV7ReducedMotion.current) "动态效果已减弱" else "动态效果正常") } }
        compose.onNodeWithText("动态效果已减弱").assertExists()
        compose.runOnIdle { Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("动态效果正常").fetchSemanticsNodes().isNotEmpty() }
    }
}
