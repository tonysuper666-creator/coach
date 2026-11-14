package com.coach.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AgentMainlineAnswerIsolationTest {
    @Test fun learnerAndTwoRunsRestoreTheirOwnAnswersAndTargetsAfterReopen() =
        MainlineScreenTest.FakeMac(1_794_052_800_000L, includeNotes = true, splitKps = true).use { mac ->
            val now = 1_794_052_800_000L
            val file = File.createTempFile("mainline-isolation-", ".sqlite")
            val transport = object : LedgerTransport {
                override suspend fun hello(epoch: String?): Hello = error("No ledger network")
                override suspend fun snapshot(course: String): Snapshot = error("No ledger network")
                override suspend fun events(course: String, after: Long): EventsPage = error("No ledger network")
                override suspend fun command(command: WireCommand): Receipt = error("No ledger network")
                override suspend fun watch(onLedger: suspend () -> Unit) = error("No ledger network")
            }
            val tutors = listOf(null, "run-A", "run-B").map { run ->
                TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ now }), agentRunId = run)
            }
            fun controllers(driver: JdbcSqliteDriver): List<CoachController> {
                val sync = SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf("C"), { "id" }, { "hash" })
                return tutors.map { CoachController("C", sync, it, formatClock = { "12:00" }, nowMillis = { now }, course = mac.http) }
            }
            val events = listOf(Event(1, "synthetic-targets", "C", "outline_revised", recordedAt = now, occurredAt = now,
                deviceId = "synthetic", actor = "SYSTEM", payload = json("knowledgePoints" to JsonArray(listOf(
                    json("id" to "K", "title" to "First target", "week" to 1, "conceptIds" to listOf("F-S1")),
                    json("id" to "OTHER", "title" to "Second target", "week" to 1, "conceptIds" to listOf("F-D1")))))))
            val view = CourseView(events, project(events))
            var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            try {
                ClientDatabase.Schema.create(driver)
                var c = controllers(driver)
                c[0].saveMainlineState(json("week" to 1, "entry" to "quiz", "targetKp" to "K", "future" to "keep"))
                c[1].saveMainlineState(json("week" to 1, "entry" to "quiz", "targetKp" to "K"))
                c[2].saveMainlineState(json("week" to 1, "entry" to "quiz", "targetKp" to "OTHER"))
                var learnerPage = json()
                var learnerActive = json()
                var learnerLearning = json()
                var agentA = json()
                var agentB = json()
                var owner by mutableStateOf(0)
                runComposeUiTest {
                    setContent { CoachTheme(false) { Surface { key(owner) {
                        MainlineScreen(c[owner], view, {}, {}, {}, {}, directEntry = true, initialWeek = 1)
                    } } } }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic orchard question S1:", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("mainline-option-1").performClick()
                    onNodeWithTag("mainline-submit").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                    waitForIdle()
                    learnerPage = c[0].mainlineState("week:1")
                    learnerActive = c[0].mainlineState()
                    learnerLearning = c[0].mainlineState("learning")
                    assertEquals("S1", learnerPage.str("item"))
                    assertNotNull(learnerPage["result"] as? JsonObject)
                    assertEquals("keep", learnerActive.str("future"))
                    assertEquals("K", c[0].learningTarget(view)?.kp?.id)
                    runOnIdle { owner = 1 }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-option-0").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("mainline-option-1").assertIsNotSelected()
                    onNodeWithTag("mainline-option-0").performClick()
                    onNodeWithTag("mainline-submit").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                    waitForIdle()
                    agentA = c[1].mainlineState("week:1")
                    assertEquals("S1", agentA.str("item"))
                    assertEquals(0, agentA["choice"]?.jsonPrimitive?.intOrNull)
                    assertNotNull(agentA["result"] as? JsonObject)
                    runOnIdle { owner = 2 }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic orchard question D1:", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("mainline-option-2").assertIsNotSelected().performClick()
                    waitForIdle()
                    agentB = c[2].mainlineState("week:1")
                    assertEquals("D1", agentB.str("item"))
                    assertEquals(2, agentB["choice"]?.jsonPrimitive?.intOrNull)
                    assertEquals(learnerPage, c[0].mainlineState("week:1"))
                    assertEquals(learnerActive, c[0].mainlineState())
                    assertEquals(learnerLearning, c[0].mainlineState("learning"))
                    assertEquals(agentA, c[1].mainlineState("week:1"))
                    assertEquals("OTHER", c[2].learningTarget(view)?.kp?.id)
                    runOnIdle { owner = 0 }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("mainline-option-1").assertIsSelected()
                }
                // Remounting can refresh the synthetic server body; the answer and target must survive it.
                val afterReturn = c[0].mainlineState("week:1")
                assertEquals(learnerPage["item"], afterReturn["item"])
                assertEquals(learnerPage["choice"], afterReturn["choice"])
                assertEquals(learnerPage["result"], afterReturn["result"])
                learnerPage = afterReturn
                driver.close()
                driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
                c = controllers(driver)
                assertEquals(learnerPage, c[0].mainlineState("week:1"))
                assertEquals(learnerActive, c[0].mainlineState())
                assertEquals(learnerLearning, c[0].mainlineState("learning"))
                assertEquals(agentA, c[1].mainlineState("week:1"))
                assertEquals(agentB, c[2].mainlineState("week:1"))
                owner = 0
                runComposeUiTest {
                    setContent { CoachTheme(false) { Surface { key(owner) {
                        MainlineScreen(c[owner], view, {}, {}, {}, {}, directEntry = true, initialWeek = 1)
                    } } } }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("mainline-option-1").assertIsSelected()
                    runOnIdle { owner = 1 }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("mainline-option-0").assertIsSelected()
                    runOnIdle { owner = 2 }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic orchard question D1:", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("mainline-option-2").assertIsSelected()
                }
            } finally { driver.close(); tutors.forEach { it.close() }; file.delete() }
        }
}
