package com.coach.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

/** Two KPs share one notes section, but their questions must not share feedback. */
@OptIn(ExperimentalTestApi::class)
class MainlineTargetCounterexampleTest {
    @Test fun differentKpWithinSameSectionDoesNotKeepPriorReceipt() = exerciseTargetChange(pending = false)
    @Test fun differentKpWaitsForPriorPendingReceiptBeforeMoving() = exerciseTargetChange(pending = true)
    @Test fun reopenedTargetKeepsItsOwnReceipt() = sameTargetReopenKeepsFeedback()

    private fun exerciseTargetChange(pending: Boolean) = MainlineScreenTest.FakeMac(1_794_052_800_000L, includeNotes = true, splitKps = true).use { mac ->
        val file = File.createTempFile("mainline-target-counterexample-", ".sqlite")
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No ledger network")
            override suspend fun snapshot(course: String): Snapshot = error("No ledger network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No ledger network")
            override suspend fun command(command: WireCommand): Receipt = error("No ledger network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No ledger network")
        }
        val tutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ 1_794_052_800_000L }))
        fun controller(driver: JdbcSqliteDriver) = CoachController("C", SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf("C"), { "id" }, { "hash" }),
            tutor, formatClock = { "12:00" }, nowMillis = { 1_794_052_800_000L }, course = mac.http)
        val events = listOf(Event(1, "synthetic-targets", "C", "outline_revised", recordedAt = 1_794_052_800_000L, occurredAt = 1_794_052_800_000L,
            deviceId = "synthetic", actor = "SYSTEM", payload = json("knowledgePoints" to JsonArray(listOf(
                json("id" to "K", "title" to "Synthetic first target", "week" to 1, "conceptIds" to listOf("F-S1")),
                json("id" to "OTHER", "title" to "Synthetic second target", "week" to 1, "conceptIds" to listOf("F-D1")))))))
        val view = CourseView(events, project(events))
        var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            ClientDatabase.Schema.create(driver)
            var c = controller(driver)
            c.sync.saveMainlineState("C", json("week" to 1, "entry" to "quiz"))
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { MainlineScreen(c, view, {}, {}, {}, {}, directEntry = true, initialWeek = 1) } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic orchard question S1:", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("mainline-option-0").performClick()
                onNodeWithTag("mainline-submit").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                assertEquals("S1", c.sync.mainlineState("C", "week:1").str("item"))
            }
            if (pending) {
                // Simulate a reply lost after commit: retrying this exact payload must not duplicate it.
                val saved = c.sync.mainlineState("C", "week:1")
                c.sync.saveMainlineState("C", JsonObject(saved + mapOf("pending" to mac.answers.single(), "result" to JsonNull)), "week:1")
                mac.offline = true
            }
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            c = controller(driver)
            val active = c.sync.mainlineState("C")
            c.sync.saveMainlineState("C", JsonObject(active + mapOf("week" to JsonPrimitive(1),
                "entry" to JsonPrimitive("quiz"), "targetKp" to JsonPrimitive("OTHER"))))
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { MainlineScreen(c, view, {}, {}, {}, {}, directEntry = true, initialWeek = 1) } } }
                if (pending) {
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic orchard question S1:", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(mac.answers.single(), c.sync.mainlineState("C", "week:1")["pending"])
                    mac.offline = false
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic orchard question D1:", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("mainline-outcome").assertDoesNotExist()
                assertEquals("D1", c.sync.mainlineState("C", "week:1").str("item"))
                if (pending) {
                    assertTrue(c.sync.mainlineState("C", "week:1")["pending"] !is JsonObject)
                    assertTrue(mac.answers.all { it == mac.answers.first() })
                }
                // Any save that follows the target being shown must merge into the stored page, not write the
                // previous item back: an option tap is the smallest such save the learner can trigger.
                onNodeWithTag("mainline-option-0").performClick()
                val afterTap = c.sync.mainlineState("C", "week:1")
                assertEquals("D1", afterTap.str("item"))
                assertEquals(0, (afterTap["choice"] as? JsonPrimitive)?.intOrNull)
                assertEquals("W01-basics", afterTap.str("filter"))
            }
            // Dispose the screen and close the database immediately after the target is visible.
            // A later coroutine must not be required to make the selected item durable.
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            c = controller(driver)
            val reopened = c.sync.mainlineState("C", "week:1")
            assertEquals("D1", reopened.str("item"))
            assertEquals("W01-basics", reopened.str("section"))
            assertEquals("W01-basics", reopened.str("filter"))
            assertEquals("quiz", reopened.str("mode"))
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { MainlineScreen(c, view, {}, {}, {}, {}, directEntry = true, initialWeek = 1) } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic orchard question D1:", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("mainline-outcome").assertDoesNotExist()
            }
        } finally {
            driver.close(); tutor.close(); file.delete()
        }
    }

    /** The opposite side of the counterexample: a target that really was answered keeps its receipt on reentry. */
    private fun sameTargetReopenKeepsFeedback() = MainlineScreenTest.FakeMac(1_794_052_800_000L, includeNotes = true, splitKps = true).use { mac ->
        val file = File.createTempFile("mainline-target-same-", ".sqlite")
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No ledger network")
            override suspend fun snapshot(course: String): Snapshot = error("No ledger network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No ledger network")
            override suspend fun command(command: WireCommand): Receipt = error("No ledger network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No ledger network")
        }
        val tutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ 1_794_052_800_000L }))
        fun controller(driver: JdbcSqliteDriver) = CoachController("C", SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf("C"), { "id" }, { "hash" }),
            tutor, formatClock = { "12:00" }, nowMillis = { 1_794_052_800_000L }, course = mac.http)
        val events = listOf(Event(1, "synthetic-targets", "C", "outline_revised", recordedAt = 1_794_052_800_000L, occurredAt = 1_794_052_800_000L,
            deviceId = "synthetic", actor = "SYSTEM", payload = json("knowledgePoints" to JsonArray(listOf(
                json("id" to "K", "title" to "Synthetic first target", "week" to 1, "conceptIds" to listOf("F-S1")),
                json("id" to "OTHER", "title" to "Synthetic second target", "week" to 1, "conceptIds" to listOf("F-D1")))))))
        val view = CourseView(events, project(events))
        var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            ClientDatabase.Schema.create(driver)
            var c = controller(driver)
            c.sync.saveMainlineState("C", json("week" to 1, "entry" to "quiz", "targetKp" to "OTHER"))
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { MainlineScreen(c, view, {}, {}, {}, {}, directEntry = true, initialWeek = 1) } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic orchard question D1:", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("mainline-option-0").performClick()
                onNodeWithTag("mainline-submit").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                assertEquals("D1", c.sync.mainlineState("C", "week:1").str("item"))
            }
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            c = controller(driver)
            assertEquals("OTHER", c.sync.mainlineState("C").str("targetKp"))
            assertEquals("D1", c.sync.mainlineState("C", "week:1").str("item"))
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { MainlineScreen(c, view, {}, {}, {}, {}, directEntry = true, initialWeek = 1) } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic orchard question D1:", substring = true).fetchSemanticsNodes().isNotEmpty() }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                assertEquals("D1", c.sync.mainlineState("C", "week:1").str("item"))
            }
        } finally {
            driver.close(); tutor.close(); file.delete()
        }
    }
}
