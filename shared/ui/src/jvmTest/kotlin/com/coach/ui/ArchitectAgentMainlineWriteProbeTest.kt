package com.coach.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Reviewer probe (not for merge): an agent run answering in the mainline must not rewrite learner week/question state. */
@OptIn(ExperimentalTestApi::class)
class ArchitectAgentMainlineWriteProbeTest {
    @Test fun agentMainlineAnswerLeavesLearnerStateAlone() = MainlineScreenTest.FakeMac(1_794_052_800_000L, includeNotes = true, splitKps = true).use { mac ->
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No ledger network")
            override suspend fun snapshot(course: String): Snapshot = error("No ledger network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No ledger network")
            override suspend fun command(command: WireCommand): Receipt = error("No ledger network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No ledger network")
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val sync = SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf("C"), { "id" }, { "hash" })
        val learnerTutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ 1_794_052_800_000L }))
        val agentTutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ 1_794_052_800_000L }), agentRunId = "run-A")
        fun controller(t: TutorClient) = CoachController("C", sync, t, formatClock = { "12:00" }, nowMillis = { 1_794_052_800_000L }, course = mac.http)
        val events = listOf(Event(1, "synthetic-targets", "C", "outline_revised", recordedAt = 1_794_052_800_000L, occurredAt = 1_794_052_800_000L,
            deviceId = "synthetic", actor = "SYSTEM", payload = json("knowledgePoints" to JsonArray(listOf(
                json("id" to "K", "title" to "Synthetic first target", "week" to 1, "conceptIds" to listOf("F-S1")),
                json("id" to "OTHER", "title" to "Synthetic second target", "week" to 1, "conceptIds" to listOf("F-D1")))))))
        val view = CourseView(events, project(events))
        sync.saveMainlineState("C", json("week" to 1, "entry" to "quiz", "targetKp" to "K", "open" to true))
        val learner = controller(learnerTutor)
        var isAgent by androidx.compose.runtime.mutableStateOf(false)
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { androidx.compose.runtime.key(isAgent) {
                MainlineScreen(if (isAgent) controller(agentTutor) else learner, view, {}, {}, {}, {}, directEntry = true, initialWeek = 1)
            } } } }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic orchard question S1:", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("mainline-option-1").performClick()
            waitForIdle()
            val learnerActive = sync.mainlineState("C")
            val learnerWeek = sync.mainlineState("C", "week:1")
            val learnerSession = sync.mainlineState("C", "learning")
            assertEquals(1, (learnerWeek["choice"] as? JsonPrimitive)?.intOrNull, "learner choice recorded")
            runOnIdle { isAgent = true }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-option-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("mainline-option-0").performClick()
            waitForIdle()
            val after = sync.mainlineState("C", "week:1")
            println("PROBE learnerWeek.before=$learnerWeek")
            println("PROBE learnerWeek.after =$after")
            println("PROBE active.before=$learnerActive after=${sync.mainlineState("C")}")
            println("PROBE learning.before=$learnerSession after=${sync.mainlineState("C", "learning")}")
            assertEquals(learnerWeek, after, "agent mainline tap rewrote learner week:1 question state")
            assertEquals(learnerActive, sync.mainlineState("C"), "agent mainline rewrote learner active state")
        }
        learnerTutor.close(); agentTutor.close(); driver.close()
    }
}
