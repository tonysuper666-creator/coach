package com.coach.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

/** 助手的阅读器状态（标注、主题、专注）落在自己的键里，学习者的阅读器读不到，也不被改写。 */
class AgentReaderStateIsolationTest {
    private val base = "http://127.0.0.1:19291"
    private val device = "synthetic"
    private val token = "synthetic-account"
    private fun digest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val offline = object : LedgerTransport {
        override suspend fun hello(epoch: String?): Hello = error("No network")
        override suspend fun snapshot(course: String): Snapshot = error("No network")
        override suspend fun events(course: String, after: Long): EventsPage = error("No network")
        override suspend fun command(command: WireCommand): Receipt = error("No network")
        override suspend fun watch(onLedger: suspend () -> Unit) = error("No network")
    }
    private val learnerState = json("marks" to JsonArray(listOf(
        json("id" to "learner-mark", "section" to "S1", "quote" to "教材原句", "note" to "learner note"))),
        "theme" to "light")
    private val runAState = json("marks" to JsonArray(listOf(
        json("id" to "run-a-mark", "section" to "S1", "quote" to "助手选中的句子", "note" to "run A note"))),
        "theme" to "dark", "focus" to true)
    private val runBState = json("marks" to JsonArray(emptyList()), "theme" to "light", "focus" to false)

    @Test fun learnerAndEachAgentRunReadBackTheirOwnReaderStateAfterReopen() {
        val file = File.createTempFile("reader-runs", ".db")
        var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        ClientDatabase.Schema.create(driver)
        val http = CourseHttp(base, device, token, "C")
        fun engine(run: String? = null) = SyncEngine(ClientDatabase(driver), device, offline, listOf("C"),
            { "id" }, ::digest, agentRunId = run)
        try {
            http.saveReaderState(engine(), 1, learnerState)
            http.saveReaderState(engine("run-A"), 1, runAState)
            http.saveReaderState(engine("run-B"), 1, runBState)

            // Process restart: a new connection over the same file must see the same three scopes.
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")

            assertEquals(learnerState, http.readerState(engine(), 1))
            assertEquals(runAState, http.readerState(engine("run-A"), 1))
            assertEquals(runBState, http.readerState(engine("run-B"), 1))
            assertFalse(http.readerState(engine(), 1).toString().contains("run A note"))
            assertFalse(http.readerState(engine("run-A"), 1).toString().contains("learner note"))
            assertTrue(http.readerState(engine(), 2).isEmpty())
        } finally {
            driver.close()
            http.close()
            file.delete()
        }
    }

    @Test fun learnerKeepsTheHistoricalKeySoOlderReaderStateStillReads() {
        val file = File.createTempFile("reader-legacy", ".db")
        var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        ClientDatabase.Schema.create(driver)
        val http = CourseHttp(base, device, token, "C")
        fun engine(run: String? = null) = SyncEngine(ClientDatabase(driver), device, offline, listOf("C"),
            { "id" }, ::digest, agentRunId = run)
        val identity = "$base\u0000$device\u0000$token"
        val legacy = json("marks" to JsonArray(listOf(json("id" to "old-mark", "note" to "写得早的一句"))), "theme" to "dark")
        try {
            // Exactly the page name the previous release wrote for the paired learner.
            ClientDatabase(driver).ledgerQueries.putMeta(
                "mainline:v1:C:reader:${digest(identity.encodeToByteArray())}:1", legacy.toString())

            assertEquals(legacy, http.readerState(engine(), 1))
            assertTrue(http.readerState(engine("run-A"), 1).isEmpty())
            http.saveReaderState(engine("run-A"), 1, runAState)
            assertEquals(legacy, http.readerState(engine(), 1))
        } finally {
            driver.close()
            http.close()
            file.delete()
        }
    }
}
