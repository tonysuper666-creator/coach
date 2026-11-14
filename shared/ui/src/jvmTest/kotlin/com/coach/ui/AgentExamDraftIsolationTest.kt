package com.coach.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.json
import com.coach.protocol.*
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.*

class AgentExamDraftIsolationTest {
    private fun verify(essay: Boolean) {
        val now = 1_794_052_800_000L
        val file = File.createTempFile("exam-draft-isolation-", ".sqlite")
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No network")
            override suspend fun snapshot(course: String): Snapshot = error("No network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No network")
            override suspend fun command(command: WireCommand): Receipt = error("No network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No network")
        }
        val tutors = listOf(null, "run-A", "run-B").map { run ->
            TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ now }), agentRunId = run)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        fun controller(index: Int): CoachController {
            val sync = SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf("C"), { "id" }, { "hash" })
            return CoachController("C", sync, tutors[index], formatClock = { "12:00" }, nowMillis = { now }, essayClock = scope)
        }
        fun read(c: CoachController): String? = if (essay) c.essay?.text else c.mockDrafts.value["same-mock"]?.get("answer")?.toString()?.trim('"')
        fun write(c: CoachController, value: String) {
            if (essay) c.startEssay(EssaySession("attempt-$value", json("stem" to "synthetic"), now + 600_000).also { it.text = value })
            else { c.mockDrafts.value = mapOf("same-mock" to json("answer" to value)); c.persistMockDrafts() }
        }
        try {
            ClientDatabase.Schema.create(driver)
            val learner = controller(0)
            write(learner, "learner")
            val a = controller(1)
            assertNull(read(a), "an empty agent must not read the learner draft")
            write(a, "agent-A")
            val b = controller(2)
            assertNull(read(b), "a second run must not read another draft")
            write(b, "agent-B")
            assertEquals("learner", read(controller(0)))
            assertEquals("agent-A", read(controller(1)))
            assertEquals("agent-B", read(controller(2)))
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            assertEquals("learner", read(controller(0)))
            assertEquals("agent-A", read(controller(1)))
            assertEquals("agent-B", read(controller(2)))
        } finally {
            scope.cancel()
            driver.close()
            tutors.forEach { it.close() }
            file.delete()
        }
    }

    @Test fun mockDraftsArePrivateToLearnerAndEachRun() = verify(false)
    @Test fun essayDraftsArePrivateToLearnerAndEachRun() = verify(true)
}
