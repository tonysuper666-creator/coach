package com.coach.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
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
import com.coach.ui.v7.deskWindow
import com.coach.ui.v7.DeskWindow
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual production pages with entirely synthetic course data; no server, bridge, corpus or emulator. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1224dp-h924dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V7ScreenshotTest(private val device: String, private val width: Int, private val height: Int, private val scene: String, private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var driver: AndroidSqliteDriver
    private lateinit var tutor: TutorClient
    private lateinit var http: CourseHttp
    private lateinit var mac: MockWebServer
    private lateinit var controller: CoachController
    private val now = 1_794_052_800_000L

    companion object {
        @JvmStatic @Parameters(name = "{0}-{3}-{4}") fun cases(): List<Array<Any>> = buildList {
            for ((name, dimensions) in listOf("inner" to (1224 to 924), "outerL" to (986 to 624), "outer" to (624 to 986))) {
                for (scene in listOf("week", "read", "quiz")) for (dark in listOf(false, true))
                    add(arrayOf(name, dimensions.first, dimensions.second, scene, dark))
            }
            for (dark in listOf(false, true)) add(arrayOf("inner", 1224, 924, "focus", dark))
        }
    }

    private val titles = listOf("道德风险是什么", "事前道德风险", "事后道德风险", "共付和起付线", "供方诱导需求", "实验证据怎么读", "政策含义")
    private val prose = """
        保险把看病时自己掏的钱压低了，于是同一个人在**价格变低后会多买医疗服务**。这不是骗保，而是对价格的正常反应★¹：需求曲线向右下倾斜，自付价格从全价降到共付比例，需求量就沿曲线往右走。

        多出来的那一段消费，**边际收益低于社会承担的边际成本**，中间那块三角形就是福利损失²。共付和起付线的作用，是把个人面对的价格往回拉一些，用一点风险分担换回一部分效率。

        所以考试里看到“保险越全越好”这类说法，先问两件事：**需求对价格有多敏感**★；多出来的医疗到底值不值它的成本。

        反过来，事前道德风险说的是投保之后人会少做预防³。两者都来自“有人替你付钱”，但政策工具不同：事后靠共付，事前靠把保费和行为挂钩。

        [^1]: 示意讲义 · 价格变化与需求。此处是界面合成数据，非课程材料。
        [^2]: 示意教材 p.214 · 图 8-2：需求曲线与福利损失。
        [^3]: 示意批注：一个在投保后，一个在投保前。
    """.trimIndent()

    private fun event(seq: Long, type: String, payload: JsonObject) = Event(seq, "fixture-$seq", "SYNTHETIC-V7", type,
        recordedAt = now, occurredAt = now, deviceId = "screenshot", actor = "SYSTEM", payload = payload)

    private fun courseView(): CourseView {
        val outline = event(1, "outline_revised", json(
            "modules" to JsonArray(listOf(json("id" to "M", "title" to "道德风险"))),
            "knowledgePoints" to JsonArray(titles.mapIndexed { i, title -> json("id" to "K$i", "title" to "【W8】$title", "moduleId" to "M",
                "sourceAnchor" to json("docId" to "synthetic-textbook", "pdfPage" to 214, "bookPage" to 214)) })))
        val active = event(2, "study_scope_set", json("week" to 8, "kpIds" to titles.indices.map { "K$it" }))
        val events = listOf(outline, active)
        val projection = project(events)
        return CourseView(events, projection.copy(
            position = Position(current = "K2"),
            studyScope = projection.studyScope?.copy(cursor = 2),
            mastery = mapOf("K0" to Mastery(passed = true), "K1" to Mastery(passed = true), "K2" to Mastery(),
                "K3" to Mastery(needsRepair = true), "K4" to Mastery(needsRepair = true))))
    }

    @Before fun fixture() {
        RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-xhdpi")
        driver = AndroidSqliteDriver(ClientDatabase.Schema, ApplicationProvider.getApplicationContext(), null)
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No ledger network")
            override suspend fun snapshot(course: String): Snapshot = error("No ledger network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No ledger network")
            override suspend fun command(command: WireCommand): Receipt = error("No ledger writes")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No ledger network")
        }
        tutor = TutorClient("http://127.0.0.1:1", "synthetic", "synthetic", TutorModel({ now }))
        mac = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = when {
                        request.path.orEmpty().substringBefore("?").endsWith("/answer") -> """{"answer":1,"choice":2,"status":"missed","tier":"detail","kpId":"K2","next":"Q2","coverage":{"facts":7,"factsWithQuestions":7,"factsMastered":3,"questions":18,"answered":6,"mastered":3},"explanation":{"correct":"自付价格从 0 升到 20%，病人沿需求曲线往左走，多用的那部分医疗减少，福利损失三角形跟着缩小。","options":{"0":"价格变了，沿需求曲线移动；不能假设需求量完全不变。","2":"逆向选择发生在投保之前，是信息不对称；共付改变的是投保之后的行为。第 7 周要回炉的正是这里。"},"background":"示意内容，非真题。","source":"教材 p.214 · 图 8-2    ·    课上 ★ 14:32"}}"""
                        request.path.orEmpty().substringBefore("?").endsWith("/mainline/8") -> """{"week":8,"title":"道德风险","coverage":{"facts":7,"factsWithQuestions":7,"factsMastered":2,"questions":18,"answered":5,"mastered":2},"items":[{"id":"Q1","tier":"detail","stem":"共付比例从 0 提到 20%，下面哪一项最可能发生？","options":["医疗需求量不变","需求量下降，福利损失变小","逆向选择问题随之消失"],"status":"new","keyState":"VERIFIED"},{"id":"Q2","tier":"detail","stem":"示意后续题","options":["甲","乙","丙"],"status":"new"},{"id":"Q3","tier":"bridge","stem":"示意桥接题","options":["甲","乙","丙"],"status":"new"}]}"""
                        else -> """{"weeks":[{"week":8,"title":"道德风险","coverage":{"facts":7,"factsWithQuestions":7,"factsMastered":2,"questions":18,"answered":5,"mastered":2}}],"invalid":[]}"""
                    }
                    return MockResponse().setBody(body).setHeader("Content-Type", "application/json")
                }
            }
            start()
        }
        http = CourseHttp(mac.url("/").toString().removeSuffix("/"), "synthetic", "synthetic", "SYNTHETIC-V7")
        controller = CoachController("SYNTHETIC-V7", SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf("SYNTHETIC-V7"), { "id" }, { "hash" }),
            tutor, formatClock = { "14:32" }, nowMillis = { now }, course = http)
    }

    @After fun close() { scope.cancel(); tutor.close(); http.close(); mac.shutdown(); driver.close() }

    @Test fun productionPage() {
        val view = courseView()
        val kp = view.kp("K2")!!
        val message = TutorMessage("synthetic-reading", "", false, prose, status = "complete")
        val ui = TutorUiState(connected = true, threads = mapOf(view.tutorThreadId(kp.id) to ThreadView(items = listOf(ChatItem.Tutor(message)), historyLoaded = true)))
        compose.setContent { CoachTheme(dark) { CompositionLocalProvider(LocalV7PageDetails provides fixtureDetails(dark), LocalV7ReducedMotion provides true, com.coach.ui.v7.LocalDeskNavigation provides { vertical -> DeskNavigation(if (scene == "quiz") AppTab.REVIEW else if (scene == "read") AppTab.ROUTE else AppTab.TODAY, vertical) {} }) { Surface { Box(Modifier.requiredSize(width.dp, height.dp)) {
            when (scene) {
                "week" -> TodayScreen(controller, view, ui, null, {}, { _, _ -> }, WeekActions(controller, scope))
                "read", "focus" -> StudyScreen(controller, kp, view, ui, false, {}, onOpenReference = {}, initialFocus = scene == "focus")
                "quiz" -> MainlineScreen(controller, view, {}, {}, {}, {})
            }
        } } } } }
        if (scene == "quiz") {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("mainline-week-8").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("mainline-week-8").performClick()
            // Opening a week now reads its notes first; keep the real route into the same quiz.
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("mainline-skip").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("mainline-skip").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("mainline-option-1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("mainline-option-2").performScrollTo().performClick()
            compose.onNodeWithTag("mainline-submit").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("mainline-heading").performScrollTo()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("v7-paper").assertExists()
        val expected = if (device == "inner") DeskWindow.Inner else if (device == "outerL") DeskWindow.OuterLandscape else DeskWindow.Outer
        assertEquals(expected, deskWindow(width.toFloat(), height.toFloat()))
        if (scene == "focus") {
            compose.onNodeWithTag("v7-directory").assertDoesNotExist()
            compose.onNodeWithTag("v7-margin").assertDoesNotExist()
        } else if (device == "inner") {
            compose.onNodeWithTag("v7-directory").assertExists()
            compose.onNodeWithTag("v7-margin").assertExists()
        } else {
            compose.onNodeWithTag("v7-directory").assertDoesNotExist()
            compose.onNodeWithTag("v7-margin").assertDoesNotExist()
        }
        if (scene == "week") {
            assertEquals(2, compose.onAllNodesWithText("✓ 已通过").fetchSemanticsNodes().size)
        }
        if (scene == "quiz") {
            compose.onNodeWithText("正确").assertExists()
            compose.onNodeWithText("你选的").assertExists()
            val first = compose.onNodeWithTag("mainline-option-0").getUnclippedBoundsInRoot()
            val second = compose.onNodeWithTag("mainline-option-1").getUnclippedBoundsInRoot()
            assertEquals("Reference option gap", 10f, (second.top - first.bottom).value, .5f)
        }
        if (System.getProperty("roborazzi.test.record").toBoolean()) {
            val directory = File(System.getProperty("coach.v7.output", "build/screens/v7")).apply { mkdirs() }
            val name = "$device-$scene-${if (dark) "dark" else "light"}.png"
            compose.onNodeWithTag("v7-desk").captureRoboImage(File(directory, name).absolutePath)
        }
    }
}
