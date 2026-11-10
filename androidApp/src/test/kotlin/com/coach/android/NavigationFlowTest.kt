package com.coach.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.ui.*
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
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

/** Actual app, SQLite and HTTP clients; all course material is invented here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var driver: AndroidSqliteDriver
    private lateinit var tutor: TutorClient
    private lateinit var http: CourseHttp
    private lateinit var server: MockWebServer
    private lateinit var controller: CoachController

    private fun course(id: String) {
        val now = 1_794_052_800_000L
        val events = listOf(Event(1, "outline", id, "outline_revised", recordedAt = now, occurredAt = now,
            deviceId = "synthetic", actor = "SYSTEM", payload = json("course" to json("name" to id),
                "modules" to JsonArray(listOf(json("id" to "M", "title" to "合成课程"))),
                "knowledgePoints" to JsonArray(listOf(json("id" to "K", "title" to "【W3】3.2 合成节", "moduleId" to "M",
                    "sourceAnchor" to json("docId" to "invented", "pdfPage" to 1)))))))
        val transport = object : LedgerTransport {
            private fun hello() = Hello("synthetic", 1, 0, 0)
            override suspend fun hello(epoch: String?) = hello()
            override suspend fun snapshot(course: String) = Snapshot(hello(), course, events, project(events))
            override suspend fun events(course: String, after: Long) = EventsPage("synthetic", emptyList(), 1)
            override suspend fun command(command: WireCommand) = error("Navigation must not write the ledger")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No external ledger")
        }
        driver = AndroidSqliteDriver(ClientDatabase.Schema, ApplicationProvider.getApplicationContext(), null)
        val sync = SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf(id), { java.util.UUID.randomUUID().toString() }, { "hash" })
        runBlocking { sync.syncOnce() }
        tutor = TutorClient("http://127.0.0.1:1", "synthetic", "synthetic", TutorModel({ now }))
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = when {
                        request.path.orEmpty().endsWith("/mainline/3/answer") -> json("itemId" to "Q2", "answer" to 0, "choice" to 1, "status" to "missed", "tier" to "bridge", "kpId" to "K",
                            "explanation" to json("correct" to "Invented feedback survives", "source" to "Invented page 1", "options" to json("1" to "Invented reason")))
                        request.path.orEmpty().contains("/mainline/3?") -> json("week" to 3, "items" to JsonArray(listOf("Q1", "Q2").map { q ->
                            json("id" to q, "tier" to "bridge", "stem" to "Invented question $q", "options" to listOf("Invented option A", "Invented option B"), "status" to "new", "keyState" to "VERIFIED") }))
                        request.path.orEmpty().endsWith("/mainline") -> json("weeks" to JsonArray(listOf(json("week" to 3, "title" to "Invented week"))))
                        request.path.orEmpty().endsWith("/mocks") -> json("papers" to emptyList<String>(), "mocks" to emptyList<String>(), "serverNow" to now)
                        request.path.orEmpty().endsWith("/sources") -> json("docs" to JsonArray(listOf(json("docId" to "invented", "title" to "合成原文", "firstPdfPage" to 1, "lastPdfPage" to 1))))
                        request.path.orEmpty().endsWith("/pages/1") -> json("docId" to "invented", "pdfPage" to 1, "text" to "Invented source for navigation testing.")
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody(body.toString()).setHeader("Content-Type", "application/json")
                }
            }
            start()
        }
        http = CourseHttp(server.url("/").toString().removeSuffix("/"), "synthetic", "synthetic", id)
        controller = CoachController(id, sync, tutor, formatClock = { "12:00" }, nowMillis = { now }, course = http)
    }

    @After fun close() {
        // Dispose page effects before closing SQLite: a final overview response must not outlive its screen.
        compose.activityRule.scenario.close()
        tutor.close(); http.close(); server.shutdown(); driver.close()
    }
    private fun back() { compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }

    @Test fun systemBackReturnsOneLevelAndRestoresTheStudyDraft() {
        course("SYNTHETIC-A")
        val restore = StateRestorationTester(compose)
        restore.setContent { Box(Modifier.requiredSize(411.dp, 900.dp)) { CoachApp(controller, initialKp = "K", initialDraft = "draft survives") } }
        compose.onNodeWithTag("open-source").performClick()
        compose.onNodeWithTag("source-back").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("source-back").assertIsDisplayed()
        back()
        compose.onNodeWithTag("source-back").assertDoesNotExist()
        compose.onNodeWithTag("composer").assertTextContains("draft survives")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("composer").assertTextContains("draft survives")
        back()
        compose.onNodeWithTag("composer").assertDoesNotExist()
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun everyWindowHasOneFiveItemNavigationWithTheRealSource() {
        course("SYNTHETIC-B")
        var width by mutableStateOf(411)
        var height by mutableStateOf(900)
        var dark by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
            Box(Modifier.requiredSize(width.dp, height.dp)) { CoachApp(controller, initialKp = "K", initialTab = AppTab.RECORDS, dark = dark) }
        } }
        val output = File(System.getProperty("coach.v7.output"), "navigation").apply { mkdirs() }
        for ((w, h) in listOf(411 to 900, 704 to 933, 933 to 704, 1280 to 900)) {
            for (night in listOf(false, true)) for (scale in listOf(1f, 1.15f)) {
                compose.runOnIdle { width = w; height = h; dark = night; fontScale = scale }
                compose.waitForIdle()
                compose.onAllNodesWithTag("app-navigation").assertCountEquals(1)
                AppTab.entries.forEach { compose.onNodeWithContentDescription("导航：${it.label}").assertExists() }
                compose.onNodeWithContentDescription("导航：记录").assertIsSelected()
                compose.onRoot().captureRoboImage(File(output, "${w}x${h}-${if (night) "dark" else "light"}-font$scale.png").absolutePath)
            }
        }
    }

    @Test fun quizPositionChoiceAndFeedbackSurviveSourceTasksAndRecreation() {
        course("SYNTHETIC-C")
        var dimensions by mutableStateOf(933 to 704)
        // Start at question two as if returning from an earlier, locally saved session.
        controller.sync.saveMainlineState(controller.courseId, json("item" to "Q2", "mode" to "quiz"), "week:3")
        val restore = StateRestorationTester(compose)
        restore.setContent { Box(Modifier.requiredSize(dimensions.first.dp, dimensions.second.dp)) { CoachApp(controller, initialTab = AppTab.REVIEW) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("mainline-continue").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mainline-continue").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("mainline-option-1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mainline-option-1").performClick()
        compose.onNodeWithTag("mainline-submit").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Invented feedback survives").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("private-citation").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("private-citation-failed").fetchSemanticsNodes().isNotEmpty() }
        back()
        compose.onNodeWithTag("private-citation-failed").assertDoesNotExist()
        compose.onNodeWithText("Invented feedback survives").assertExists()
        for (window in listOf(704 to 933, 411 to 900, 933 to 704)) {
            compose.runOnIdle { dimensions = window }
            compose.onNodeWithText("Invented question Q2").assertExists()
            compose.onNodeWithTag("mainline-option-1").assertIsSelected()
            compose.onNodeWithText("Invented feedback survives").assertExists()
        }
        compose.onNodeWithTag("mainline-steps").performScrollTo().performClick()
        back()
        compose.onNodeWithText("Invented question Q2").assertExists()
        compose.onNodeWithTag("mainline-option-1").assertIsSelected()
        compose.onNodeWithText("Invented feedback survives").assertExists()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Invented question Q2").assertExists()
        compose.onNodeWithTag("mainline-option-1").assertIsSelected()
        compose.onNodeWithText("Invented feedback survives").assertExists()
        back()
        compose.onNodeWithTag("mainline-notes").assertExists()
        back()
        compose.onNodeWithTag("mainline-week-3").assertExists()
        back()
        compose.onNodeWithTag("open-mainline").assertExists()
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        back()
        compose.onNodeWithTag("open-mainline").assertExists()
    }

    @Test fun essayTemporarilyLeavesAndRestoresItsDraftAndOriginalDeadline() {
        course("SYNTHETIC-D")
        controller.sync.saveMainlineState(controller.courseId, json("attempt" to "invented-essay", "deadline" to controller.nowMillis() + 60_000,
            "posed" to json("stem" to "Invented essay prompt", "wordTarget" to 200), "text" to "saved words", "handedIn" to false), "essay-draft")
        fun restoredController() = CoachController(controller.courseId, controller.sync, tutor, formatClock = { "12:00" }, nowMillis = controller.nowMillis, course = http)
        controller = restoredController()
        var shown by mutableStateOf(controller)
        compose.setContent { Box(Modifier.requiredSize(704.dp, 933.dp)) { CoachApp(shown, initialTab = AppTab.REVIEW) } }
        compose.onNodeWithTag("essay-entry").performClick()
        compose.onNodeWithTag("essay-text").assertTextContains("saved words")
            .performTextInputSelection(androidx.compose.ui.text.TextRange("saved words".length))
        compose.onNodeWithTag("essay-text").performTextInput(" and more")
        compose.onNodeWithTag("essay-text").assertTextContains("saved words and more")
        compose.onNodeWithTag("essay-countdown").assertTextEquals("1:00")
        back()
        compose.runOnIdle { controller = restoredController(); shown = controller }
        compose.onNodeWithTag("essay-entry").performClick()
        compose.onNodeWithTag("essay-text").assertTextContains("saved words and more")
        compose.onNodeWithTag("essay-countdown").assertTextEquals("1:00")
        back()
        compose.onNodeWithTag("essay-entry").assertExists()
    }
}
