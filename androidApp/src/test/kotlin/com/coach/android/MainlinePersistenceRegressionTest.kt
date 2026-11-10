package com.coach.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
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
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

/** Mac's two new failures reproduced with synthetic HTTP and a real file-backed Android database.
 * Window 933x704dp; density=1; fontScale=1. No corpus, devices or tutor calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainlinePersistenceRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val databaseName = "ton1446-${UUID.randomUUID()}.db"
    private lateinit var driver: AndroidSqliteDriver
    private lateinit var tutor: TutorClient
    private lateinit var http: CourseHttp
    private lateinit var c: CoachController
    private lateinit var server: MockWebServer
    private val view = CourseView(emptyList(), project(emptyList()))
    private var offline = false
    private var mounted by mutableStateOf(true)
    private var generation by mutableStateOf(0)
    private val body = json("week" to 1, "items" to JsonArray(listOf("Q1", "Q2").map { id ->
        json("id" to id, "tier" to if (id == "Q2") "bridge" else "setup", "stem" to "Invented question $id",
            "factIds" to listOf("F$id"), "options" to listOf("Invented A", "Invented B"), "status" to "new", "keyState" to "VERIFIED")
    }), "notes" to JsonArray(listOf("S1", "S2").mapIndexed { i, id ->
        json("section_id" to id, "title" to "Invented notes $id", "body" to "Invented paragraph $id.", "fact_ids" to listOf("FQ${i + 1}"))
    }))

    @Before fun prepare() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (offline) return MockResponse().setResponseCode(503)
                    val response = when {
                        request.path.orEmpty().contains("/mainline/1?") -> body
                        request.path.orEmpty().endsWith("/mainline/1/answer") -> json("itemId" to "Q2", "answer" to 1, "choice" to 1,
                            "guessed" to true, "status" to "guessed", "tier" to "bridge", "explanation" to json("correct" to "Invented retained feedback"))
                        request.path.orEmpty().endsWith("/mainline") -> json("weeks" to JsonArray(listOf(json("week" to 1, "title" to "Invented week",
                            "coverage" to json("facts" to 2, "factsMastered" to 0, "questions" to 2)))))
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody(response.toString()).setHeader("Content-Type", "application/json")
                }
            }
            start()
        }
        val url = server.url("/").toString().removeSuffix("/")
        tutor = TutorClient(url, "test", "test", TutorModel({ 1_794_268_800_000L }))
        http = CourseHttp(url, "test", "test", "C")
        openDatabase()
    }
    private fun openDatabase() {
        driver = AndroidSqliteDriver(ClientDatabase.Schema, ApplicationProvider.getApplicationContext(), databaseName)
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No ledger network")
            override suspend fun snapshot(course: String): Snapshot = error("No ledger network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No ledger network")
            override suspend fun command(command: WireCommand): Receipt = error("No ledger commands")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No ledger network")
        }
        c = CoachController("C", SyncEngine(ClientDatabase(driver), "test", transport, listOf("C"), { UUID.randomUUID().toString() }, { "hash" }),
            tutor, formatClock = { "12:00" }, nowMillis = { 1_794_268_800_000L }, course = http)
    }
    @After fun close() {
        compose.activityRule.scenario.close()
        tutor.close(); http.close(); server.shutdown(); driver.close()
    }
    private fun show(review: Boolean = false, opened: (Int?, Boolean) -> Unit = { _, _ -> }) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
            CoachTheme(false) { Surface(Modifier.requiredSize(933.dp, 704.dp)) {
                if (mounted) key(generation) {
                    if (review) ReviewScreen(c, view, { _, _ -> }, null, onOpenMainline = opened)
                    else MainlineScreen(c, view, {}, {}, {}, {})
                }
            } }
        } }
    }
    private fun waitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun reopen() {
        compose.runOnIdle { mounted = false }; compose.waitForIdle()
        driver.close(); openDatabase()
        compose.runOnIdle { generation++; mounted = true }; compose.waitForIdle()
    }
    private fun capture(name: String) {
        if (System.getProperty("roborazzi.test.record").toBoolean()) {
            val out = File(System.getProperty("coach.v7.output"), "ton1446").apply { mkdirs() }
            compose.onRoot().captureRoboImage(File(out, "$name.png").absolutePath)
        }
    }

    @Test fun reviewEntryShowsTheCurrentWeekOnceTheCourseAnswers() {
        var opened: Pair<Int?, Boolean>? = null
        show(review = true) { number, start -> opened = number to start }
        waitTag("mainline-continue")
        compose.onNodeWithText("继续第 1 周").assertIsDisplayed()
        compose.onNodeWithText("正在连接…").assertDoesNotExist()
        compose.onNodeWithTag("mainline-continue").performClick()
        assertEquals(1 to true, opened)
        compose.onNodeWithText("看本周要点").assertExists()
        assertTrue(c.sync.localEvents("C").isEmpty())
        capture("server-only-review-entry")
    }

    @Test fun notesFilterAndExactAnsweredQuestionSurviveAnActualDatabaseReopen() {
        show()
        waitTag("mainline-week-1")
        compose.onNodeWithTag("mainline-week-1").performClick()
        waitTag("mainline-notes")
        compose.onNodeWithText("○ Invented notes S2").performScrollTo().performClick()
        compose.onNodeWithTag("mainline-section-quiz").performScrollTo().performClick()
        compose.onNodeWithText("综合题 · 第 1/1 题").assertExists()
        compose.onNodeWithTag("mainline-option-1").performScrollTo().performClick()
        compose.onNodeWithTag("mainline-guess").performScrollTo().performClick()
        waitTag("mainline-outcome")
        compose.onNodeWithText("猜对：不算会。").performScrollTo().assertIsDisplayed()
        val saved = c.sync.mainlineState("C", "week:1")
        assertEquals("Q2", saved.str("item")); assertEquals("S2", saved.str("filter"))
        assertNotNull(saved["result"])
        reopen()
        assertEquals(saved, c.sync.mainlineState("C", "week:1"))
        waitTag("mainline-outcome")
        compose.onNodeWithTag("mainline-option-1").assertIsSelected()
        compose.onNodeWithTag("mainline-return-section").performScrollTo().performClick()
        compose.onNodeWithText("Invented paragraph S2.").performScrollTo().assertIsDisplayed()
        assertEquals("notes", c.sync.mainlineState("C", "week:1").str("mode"))
        offline = true
        reopen()
        waitTag("mainline-notes")
        compose.onNodeWithText("Invented paragraph S2.").performScrollTo().assertIsDisplayed()
        capture("reopened-notes")
        compose.onNodeWithTag("mainline-section-quiz").performScrollTo().performClick()
        waitTag("mainline-outcome")
        compose.onNodeWithText("综合题 · 第 1/1 题").assertExists()
        compose.onNodeWithTag("mainline-option-1").assertIsSelected()
        compose.onNodeWithText("猜对：不算会。").performScrollTo().assertIsDisplayed()
        val restored = c.sync.mainlineState("C", "week:1")
        assertEquals(saved["choice"], restored["choice"])
        assertEquals(saved["result"], restored["result"])
        assertEquals("Q2", restored.str("item")); assertEquals("S2", restored.str("filter"))
        assertTrue(c.sync.localEvents("C").isEmpty())
        capture("reopened-filtered-answer")
    }
}
