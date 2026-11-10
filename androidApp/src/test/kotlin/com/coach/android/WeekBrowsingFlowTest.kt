package com.coach.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.ui.*
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Real SQLite, HTTP and tutor WebSocket; all material is synthetic. Window 933x704dp, density=1, fontScale=1. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeekBrowsingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var driver: AndroidSqliteDriver
    private lateinit var tutor: TutorClient
    private lateinit var server: MockWebServer
    private lateinit var controller: CoachController
    private lateinit var http: CourseHttp
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ledger = CopyOnWriteArrayList<Event>()
    private val commands = CopyOnWriteArrayList<String>()
    private val frames = CopyOnWriteArrayList<String>()
    private val requests = CopyOnWriteArrayList<String>()
    private val now = 1_794_268_800_000L
    private fun add(type: String, payload: JsonObject, thread: String? = null) = Event(ledger.size + 1L, "e${ledger.size}", "SYNTHETIC", type,
        recordedAt = now, occurredAt = now, deviceId = "test", actor = "SYSTEM", threadId = thread, payload = payload).also(ledger::add)
    private val body = json("week" to 3, "items" to JsonArray(listOf("Q1", "Q2").map { id ->
        json("id" to id, "tier" to "setup", "stem" to "Invented question $id", "factIds" to listOf(if (id == "Q1") "F1" else "F2"), "options" to listOf("Invented A", "Invented B"), "status" to "new", "keyState" to "VERIFIED")
    }), "notes" to JsonArray(listOf(json("section_id" to "S1", "title" to "Invented section one", "body" to "Invented paragraph one.", "fact_ids" to listOf("F1")),
        json("section_id" to "S2", "title" to "Invented section two", "body" to (1..30).joinToString("\n\n") { "Invented paragraph $it describes a made up course." }, "fact_ids" to listOf("F2")))))

    private fun course(scoped: Boolean = false, online: Boolean = false, empty: Boolean = false, complete: Boolean = false, sameWeekSection: Boolean = false) {
        val points = if (empty) emptyList() else buildList {
            add(json("id" to "K3", "title" to "【W3】Invented section", "conceptIds" to listOf("F1")))
            add(json("id" to "K4", "title" to "【W4】Invented later section"))
            if (sameWeekSection) add(json("id" to "K3b", "title" to "【W3】Invented second section", "conceptIds" to listOf("F2")))
        }
        add("outline_revised", json("course" to json("name" to "Synthetic course", "examDate" to "2026-12-15"),
            "knowledgePoints" to JsonArray(points)))
        if (scoped) add("study_scope_set", json("week" to 3, "kpIds" to listOf("K3")))
        if (complete) listOf("K3", "K4").forEach { add("kp_overridden", json("kpId" to it, "kind" to "MARK_PASSED")) }
        server = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.path.orEmpty()
                    if (request.path == "/v1/ws") return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) { frames += text }
                    })
                    val result = when {
                        request.path.orEmpty().contains("/mainline/3?") -> body
                        request.path.orEmpty().endsWith("/mainline/3/answer") -> json("itemId" to "Q2", "answer" to 0, "choice" to 1, "status" to "missed", "tier" to "setup", "explanation" to json("correct" to "Invented original feedback"))
                        request.path.orEmpty().endsWith("/sources") -> json("docs" to emptyList<String>())
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody(result.toString()).setHeader("Content-Type", "application/json")
                }
            }
            start()
        }
        val transport = object : LedgerTransport {
            fun hello() = Hello("synthetic", ledger.size.toLong(), 0, 0)
            override suspend fun hello(epoch: String?) = hello()
            override suspend fun snapshot(course: String) = Snapshot(hello(), course, ledger.toList(), project(ledger))
            override suspend fun events(course: String, after: Long) = EventsPage("synthetic", ledger.filter { it.seq > after }, ledger.size.toLong())
            override suspend fun command(command: WireCommand): Receipt {
                val b = LedgerJson.decodeFromString<CommandBody>(command.payloadBytes)
                commands += b.type
                check(b.type == "set_study_scope")
                val week = b.payload.num("week").toInt()
                val ids = CourseView(ledger, project(ledger)).weekKps(week).map { it.id }
                val e = add("study_scope_set", json("week" to week, "kpIds" to ids))
                return Receipt(command.commandId, "OK", listOf(e.seq), epoch = "synthetic", backedUpThroughSeq = 0, result = json("target" to "K$week"))
            }
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No external ledger")
        }
        driver = AndroidSqliteDriver(ClientDatabase.Schema, ApplicationProvider.getApplicationContext(), null)
        val sync = SyncEngine(ClientDatabase(driver), "test", transport, listOf("SYNTHETIC"), { java.util.UUID.randomUUID().toString() }, { "hash" })
        runBlocking { sync.syncOnce() }
        val url = server.url("/").toString().removeSuffix("/")
        tutor = TutorClient(url, "test", "test", TutorModel({ now }))
        http = CourseHttp(url, "test", "test", "SYNTHETIC")
        controller = CoachController("SYNTHETIC", sync, tutor, formatClock = { "12:00" }, nowMillis = { now }, course = http)
        if (online) {
            tutor.start(background)
            runBlocking { withTimeout(10_000) { while (!tutor.model.state.value.connected) delay(10) } }
        }
    }
    @After fun close() {
        compose.activityRule.scenario.close()
        background.cancel(); tutor.close(); http.close(); server.shutdown(); driver.close()
    }
    private fun show(tab: AppTab = AppTab.TODAY) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
            Box(Modifier.requiredSize(933.dp, 704.dp)) { CoachApp(controller, initialTab = tab, dark = false) }
        } }
    }
    private fun waitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun back() { compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
    private fun capture(name: String) {
        if (System.getProperty("roborazzi.test.record").toBoolean()) {
            val out = File(System.getProperty("coach.v7.output"), "ton1446").apply { mkdirs() }
            compose.onRoot().captureRoboImage(File(out, "$name.png").absolutePath)
        }
    }
    private fun browse(online: Boolean) {
        course(scoped = true, online = online)
        val before = ledger.toList()
        show(AppTab.ROUTE)
        compose.onNodeWithTag("browse-week-3").performScrollTo().performClick()
        waitTag("v7-next-week")
        compose.onNodeWithTag("v7-next-week").performClick()
        compose.onNodeWithTag("week-section-K4").assertExists()
        compose.onNodeWithTag("v7-previous-week").performClick()
        compose.onNodeWithTag("week-section-K3").assertExists()
        compose.onNodeWithTag("v7-week-picker").performClick()
        compose.onNodeWithText("第 4 周", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("week-section-K4").assertExists()
        capture(if (online) "browse-online" else "browse-offline")
        compose.runOnIdle {
            assertEquals(before, ledger.toList())
            assertTrue(commands.isEmpty())
            assertTrue(frames.none { it.contains("tutor.") || it.contains("msg.ask") })
            assertTrue(requests.none { it.contains("/answer") })
            assertEquals(before, controller.sync.localEvents("SYNTHETIC"))
        }
        back()
        compose.onNodeWithContentDescription("导航：今天").performClick()
        waitTag("today-continue")
        compose.onNodeWithText("到期复习").assertExists()
    }
    @Test fun onlineWeekDirectoryFooterAndPickerHaveZeroSideEffects() = browse(true)
    @Test fun offlineWeekDirectoryFooterAndPickerRemainAvailable() = browse(false)
    @Test fun existingWeekPickerDoesNotEnqueueAScopeCommand() {
        course(scoped = true, online = true)
        val before = ledger.toList()
        val view = CourseView(before, project(before))
        lateinit var actions: WeekActions
        compose.setContent {
            val scope = rememberCoroutineScope()
            actions = remember { WeekActions(controller, scope) }
            WeekPicker(view, actions, true)
        }
        compose.onNodeWithText("换周", substring = true).performClick()
        compose.onNodeWithText("第 4 周", substring = true).performClick()
        compose.waitUntil(10_000) { !actions.busy }
        compose.runOnIdle {
            println("Existing picker: commands=${commands.toList()} ledgerDelta=${ledger.size - before.size} tutorCalls=${frames.count { it.contains("tutor.") }}")
            assertEquals(before, ledger.toList())
            assertTrue(commands.isEmpty())
            assertEquals(before, controller.sync.localEvents("SYNTHETIC"))
            assertTrue(frames.none { it.contains("tutor.") })
        }
    }
    private fun today(scoped: Boolean) {
        course(scoped = scoped, online = true)
        show()
        waitTag("today-continue")
        compose.onNodeWithText("到期复习").assertExists()
        compose.onNodeWithText("距期末", substring = true).assertExists()
        compose.onNodeWithTag("v7-next-week").assertDoesNotExist()
        capture(if (scoped) "today-scoped" else "today-unscoped")
        compose.onNodeWithTag("today-continue").performClick()
        waitTag("mainline-option-0")
        compose.onNodeWithText("Invented question Q1").assertExists()
        compose.onNodeWithTag("mainline-notes").assertDoesNotExist()
        assertEquals(if (scoped) 0 else 1, commands.count { it == "set_study_scope" })
        assertTrue(frames.none { it.contains("tutor.next") || it.contains("tutor.test") })
        capture("today-first-question-${if (scoped) "scoped" else "unscoped"}")
    }
    @Test fun todayWithoutScopeEntersTheFirstQuestionInOneClick() = today(false)
    @Test fun todayWithScopeHasTheSameEntryAndNoExtraScopeWrite() = today(true)
    @Test fun pausedQuestionRestoresItsExactChoiceAndFeedbackFromToday() {
        course(scoped = true)
        controller.sync.saveMainlineState("SYNTHETIC", json("week" to 3))
        controller.sync.saveMainlineState("SYNTHETIC", json("body" to body, "mode" to "quiz", "item" to "Q2", "choice" to 1,
            "result" to json("itemId" to "Q2", "answer" to 0, "choice" to 1, "status" to "missed", "explanation" to json("correct" to "Invented original feedback"))), "week:3")
        show(); waitTag("today-continue")
        compose.onNodeWithTag("today-continue").performClick()
        waitTag("mainline-option-1")
        compose.onNodeWithText("Invented question Q2").assertExists()
        compose.onNodeWithTag("mainline-option-1").assertIsSelected()
        compose.onNodeWithText("Invented original feedback").assertExists()
        capture("restored-question")
        back(); compose.onNodeWithTag("today-continue").performClick()
        compose.onNodeWithTag("mainline-option-1").assertIsSelected()
        compose.onNodeWithText("Invented original feedback").assertExists()
        assertTrue(commands.isEmpty())
        assertTrue(requests.none { it.contains("/answer") })
    }
    @Test fun deliberatelyOpenedReadingRestoresItsSectionAndParagraphOffline() {
        course(scoped = true)
        controller.sync.saveMainlineState("SYNTHETIC", json("week" to 3))
        controller.sync.saveMainlineState("SYNTHETIC", json("body" to body, "mode" to "notes", "section" to "S2", "readScroll" to 250), "week:3")
        show(); waitTag("today-continue")
        compose.onNodeWithTag("today-continue").performClick()
        waitTag("mainline-notes")
        compose.onNodeWithText("Invented section two").assertExists()
        compose.onNodeWithTag("mainline-option-0").assertDoesNotExist()
        compose.runOnIdle {
            val saved = controller.sync.mainlineState("SYNTHETIC", "week:3")
            assertEquals("S2", saved.str("section"))
            assertEquals(250, saved.num("readScroll").toInt())
        }
        capture("restored-reading")
        assertTrue(commands.isEmpty())
    }
    @Test fun fullyCompletedCourseDoesNotRecommendAnOldQuestion() {
        course(complete = true); show()
        compose.onNodeWithText("今天的都做完了。", substring = true).assertExists()
        compose.onNodeWithTag("today-continue").assertDoesNotExist()
        compose.onNodeWithText("到期复习").assertExists()
    }
    @Test fun oldTutorPendingQuestionWinsOverTheOtherWeekAndKeepsItsChoice() {
        course(online = true)
        add("study_scope_set", json("week" to 4, "kpIds" to listOf("K4")))
        add("kp_opened", json("kpId" to "K3"), "kp:K3")
        add("question_posed", json("attemptId" to "A2", "questionId" to "oldQ2", "groupId" to "G", "kpIds" to listOf("K3"),
            "format" to "SINGLE_CHOICE", "stem" to "Paused original tutor question", "options" to JsonArray(listOf(json("optionId" to "a", "text" to "Original A"), json("optionId" to "b", "text" to "Original B"))),
            "letterMap" to json("a" to "A", "b" to "B")), "kp:K3")
        runBlocking { controller.sync.syncOnce() }
        tutor.model.onFrame(json("t" to "msg.history", "threadId" to "kp:K3", "messages" to emptyList<String>()).toString())
        show(); waitTag("today-continue")
        compose.onNodeWithTag("today-continue").performClick()
        waitTag("study-option-A2-B")
        compose.onNodeWithTag("study-option-A2-B").performScrollTo().performClick()
        capture("restored-legacy-question")
        back(); compose.onNodeWithTag("today-continue").performClick()
        waitTag("study-option-A2-B")
        compose.onNodeWithTag("study-option-A2-B").assertIsSelected()
        assertEquals(1, commands.count { it == "set_study_scope" })
        assertTrue(frames.none { it.contains("tutor.next") || it.contains("tutor.test") })
    }
    @Test fun missingOfflineContentIsAnHonestEmptyState() {
        course(empty = true); show()
        compose.onNodeWithText("还没有同步到课程内容，且 Mac 未连接。").assertExists()
        compose.onNodeWithTag("today-continue").assertDoesNotExist()
    }
    @Test fun explicitSectionStartUsesThatSectionsFirstQuestion() {
        course(online = true, sameWeekSection = true)
        show(AppTab.ROUTE)
        compose.onNodeWithTag("browse-week-3").performScrollTo().performClick()
        compose.onNode(hasText("开始") and hasAnyAncestor(hasTestTag("week-section-K3b"))).performClick()
        waitTag("mainline-option-0")
        compose.onNodeWithText("Invented question Q2").assertExists()
        assertEquals(1, commands.count { it == "set_study_scope" })
    }
    @Test fun browsingAQuestionCatalogWeekKeepsThePausedSessionForToday() {
        course(scoped = true)
        controller.sync.saveMainlineState("SYNTHETIC", json("week" to 3))
        controller.sync.saveMainlineState("SYNTHETIC", json("kind" to "mainline", "week" to 3), "learning")
        controller.sync.saveMainlineState("SYNTHETIC", json("body" to body, "mode" to "quiz", "item" to "Q2", "choice" to 1), "week:3")
        val before = ledger.toList()
        val session = controller.sync.mainlineState("SYNTHETIC", "week:3")
        show(AppTab.REVIEW)
        compose.onNodeWithText("选其他周").performClick()
        waitTag("mainline-week-4")
        compose.onNodeWithTag("mainline-week-4").performClick()
        compose.onNodeWithTag("week-section-K4").assertExists()
        assertEquals(session, controller.sync.mainlineState("SYNTHETIC", "week:3"))
        assertEquals(before, ledger.toList())
        assertTrue(commands.isEmpty())
        compose.onNodeWithContentDescription("导航：今天").performClick()
        waitTag("today-continue")
        compose.onNodeWithTag("today-continue").performClick()
        waitTag("mainline-option-1")
        compose.onNodeWithText("Invented question Q2").assertExists()
        compose.onNodeWithTag("mainline-option-1").assertIsSelected()
    }
}
