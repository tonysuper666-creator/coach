package com.coach.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.serialization.json.JsonArray
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AgentStudyReadingIsolationTest {
    private fun verify(reading: Boolean) {
        val now = 1_794_052_800_000L
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No network")
            override suspend fun snapshot(course: String): Snapshot = error("No network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No network")
            override suspend fun command(command: WireCommand): Receipt = error("No network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No network")
        }
        val sync = SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf("C"), { "id" }, { "hash" })
        val learnerTutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ now }))
        val agentTutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ now }), agentRunId = "run-A")
        val learner = CoachController("C", sync, learnerTutor, formatClock = { "12:00" }, nowMillis = { now })
        val agent = CoachController("C", sync, agentTutor, formatClock = { "12:00" }, nowMillis = { now })
        val events = listOf(Event(1, "targets", "C", "outline_revised", recordedAt = now, occurredAt = now,
            deviceId = "synthetic", actor = "SYSTEM", payload = json("knowledgePoints" to JsonArray(listOf(
                json("id" to "A", "title" to "Learner target", "week" to 1),
                json("id" to "B", "title" to "Agent target", "week" to 1))))))
        val view = CourseView(events, project(events))
        val agentView = CourseView(events, project(events), "run-A")
        val original = json("kind" to "reading", "kpId" to "A", "future" to "preserve")
        val scroll = json("offset" to 99, "future" to "preserve")
        sync.saveMainlineState("C", original, "learning")
        sync.saveMainlineState("C", scroll, "reading:B")
        try {
            assertEquals("A", learner.learningTarget(view)?.kp?.id)
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface {
                    if (reading) com.coach.ui.v7.ReadingPage(agent, agentView.kp("B")!!, agentView,
                        "Synthetic reading content.", false, {}, {}, {}, {}, {}, null, {})
                    else StudyScreen(agent, agentView.kp("B")!!, agentView, TutorUiState(), false, {})
                } } }
                waitForIdle()
                assertEquals(original, sync.mainlineState("C", "learning"))
                assertEquals("A", learner.learningTarget(view)?.kp?.id)
                assertEquals(scroll, sync.mainlineState("C", "reading:B"))
                assertEquals("B", agent.mainlineState("learning").str("kpId"))
                assertEquals(if (reading) "reading" else "tutor", agent.mainlineState("learning").str("kind"))
            }
        } finally { learnerTutor.close(); agentTutor.close(); driver.close() }
    }
    @Test fun agentStudyKeepsLearnerContinuation() = verify(false)
    @Test fun agentReadingKeepsLearnerContinuationAndScroll() = verify(true)
}
