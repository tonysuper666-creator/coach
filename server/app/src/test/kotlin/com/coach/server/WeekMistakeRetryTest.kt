package com.coach.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.store.LedgerStore
import com.coach.store.sha256
import io.ktor.http.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/** Real SQLite ledger and HTTP transport; faults deliberately straddle the durable commit. */
class WeekMistakeRetryTest {
    @Test fun beforeCommit500RetainsBytesAndLetsAnotherQuestionThrough() = exerciseFault(false)
    @Test fun afterCommit503ReplaysAsDuplicateAfterClientAndServerRestart() = exerciseFault(true)

    private fun exerciseFault(afterCommit: Boolean) = runBlocking {
        val root = Files.createTempDirectory("coach-retry-http-")
        var store = LedgerStore(root.resolve("server"))
        seed(store)
        var fail = true
        val received = mutableListOf<WireCommand>()
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            routing {
                get("/v1/hello") { call.respondText(LedgerJson.encodeToString(store.hello()), ContentType.Application.Json) }
                get("/v1/courses/C/snapshot") { call.respondText(LedgerJson.encodeToString(store.snapshot("C")), ContentType.Application.Json) }
                get("/v1/courses/C/events") {
                    val events = store.events("C", call.request.queryParameters["after"]!!.toLong())
                    call.respondText(LedgerJson.encodeToString(EventsPage(store.hello().epoch, events, store.hello().lastSeq)), ContentType.Application.Json)
                }
                post("/v1/commands") {
                    val command = LedgerJson.decodeFromString<WireCommand>(call.receiveText())
                    received += command
                    val body = LedgerJson.decodeFromString<CommandBody>(command.payloadBytes)
                    if (fail && body.payload.str("attemptId") == "old4") {
                        if (afterCommit) store.command(command)
                        call.respondText("injected failure", status = if (afterCommit) HttpStatusCode.ServiceUnavailable else HttpStatusCode.InternalServerError)
                    } else call.respondText(LedgerJson.encodeToString(store.command(command)), ContentType.Application.Json)
                }
            }
        }.apply { start(wait = false) }
        val port = server.engine.resolvedConnectors().first().port
        val transport = HttpLedgerTransport("http://127.0.0.1:$port", "phone", "synthetic-token")
        fun openDb(): Pair<JdbcSqliteDriver, ClientDatabase> {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${root.resolve("client.sqlite")}")
            return driver to ClientDatabase(driver)
        }
        var (driver, db) = openDb()
        ClientDatabase.Schema.create(driver)
        fun sync() = SyncEngine(db, "phone", transport, listOf("C"), { UUID.randomUUID().toString() }, ::sha256)
        var client = sync()
        try {
            client.syncOnce()
            val original = client.enqueue(retry("old4"))
            val next = client.enqueue(retry("old5"))
            store.append("C", "note_saved", json("noteId" to "mac", "text" to "Synthetic remote note"))
            client.syncOnce()
            assertNull(client.receipt(original.commandId), "An uncertain command must not be acknowledged")
            assertEquals("OK", client.receipt(next.commandId)?.status)
            assertTrue(client.localEvents("C").any { it.type == "note_saved" && it.payload.str("noteId") == "mac" })
            assertEquals(1 + if (afterCommit) 1 else 0, store.events("C").count { it.type == "question_posed" && it.payload.str("questionId") == "q4" })
            // Both processes restart while the first command's outcome remains unknown.
            driver.close(); store.close()
            store = LedgerStore(root.resolve("server"))
            openDb().also { driver = it.first; db = it.second }
            client = sync()
            client.syncOnce()
            assertNull(client.receipt(original.commandId))
            assertEquals(listOf(original, original), received.filter { it.commandId == original.commandId })
            assertEquals(1, received.count { it.commandId == next.commandId })
            fail = false
            client.syncOnce()
            assertEquals(if (afterCommit) "DUPLICATE" else "OK", client.receipt(original.commandId)?.status)
            assertEquals(listOf(original, original, original), received.filter { it.commandId == original.commandId })
            val events = store.events("C")
            assertEquals(2, events.count { it.type == "question_posed" && it.payload.str("questionId") == "q4" })
            assertEquals(2, events.count { it.type == "question_posed" && it.payload.str("questionId") == "q5" })
            assertEquals(2, events.count { it.type == "answer_submitted" }, "Opening retries must not fabricate answers")
            assertEquals(setOf("old4", "old5"), weekMistakes(events, 1).map { it.payload.str("attemptId") }.toSet())
        } finally { transport.close(); server.stop(0, 500); driver.close(); store.close(); root.toFile().deleteRecursively() }
    }

    @Test fun sameQuestionAndDependentCommandsStayOrderedWhileAnotherCourseCanSync() = runBlocking {
        val root = Files.createTempDirectory("coach-retry-order-")
        val store = LedgerStore(root)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val sent = mutableListOf<WireCommand>()
        var fail = true
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?) = store.hello()
            override suspend fun snapshot(course: String) = store.snapshot(course)
            override suspend fun events(course: String, after: Long) = EventsPage(store.hello().epoch, store.events(course, after), store.hello().lastSeq)
            override suspend fun command(command: WireCommand): Receipt {
                sent += command
                if (fail && LedgerJson.decodeFromString<CommandBody>(command.payloadBytes).payload.str("attemptId") == "old4") throw CommandUploadDeferred(500)
                return store.command(command)
            }
            override suspend fun watch(onLedger: suspend () -> Unit) = awaitCancellation()
        }
        val sync = SyncEngine(ClientDatabase(driver), "phone", transport, listOf("C", "other"), { UUID.randomUUID().toString() }, ::sha256)
        try {
            seed(store); sync.syncOnce()
            val first = sync.enqueue(retry("old4"))
            val same = sync.enqueue(retry("old4"))
            val dependent = sync.enqueue(CommandBody("submit_answer", "C", 1, json("attemptId" to "not-yet-posed", "answer" to "A")))
            val next = sync.enqueue(retry("old5"))
            val other = sync.enqueue(CommandBody("save_note", "other", 1, json("noteId" to "n", "text" to "Synthetic")))
            sync.syncOnce()
            assertEquals(listOf(first, other), sent)
            listOf(first, same, dependent, next).forEach { assertNull(sync.receipt(it.commandId)) }
            fail = false; sync.syncOnce()
            assertEquals(listOf(first, other, first, same, dependent, next), sent)
            assertEquals("OK", sync.receipt(next.commandId)?.status)
        } finally { driver.close(); store.close(); root.toFile().deleteRecursively() }
    }

    private fun retry(attempt: String) = CommandBody("retry_week_mistake", "C", 1, json("week" to 1, "attemptId" to attempt))

    private fun seed(store: LedgerStore) {
        store.append("C", "outline_revised", json("ops" to JsonArray(listOf(json("kpId" to "k", "title" to "【W1】Synthetic")))))
        for (n in 4..5) {
            store.registerQuestion("q$n", "C", "", "SINGLE_CHOICE", "Synthetic S$n", "VERIFIED", "yes",
                listOf(Option("yes", "Right"), Option("no", "Wrong")), listOf("k"))
            store.poseChoiceGroup("C", "g$n", listOf(json("attemptId" to "old$n", "questionId" to "q$n", "kpIds" to listOf("k"), "purpose" to "PRACTICE", "policyVersion" to 1)))
            store.append("C", "answer_submitted", json("attemptId" to "old$n", "answer" to "synthetic wrong answer"))
            store.append("C", "attempt_graded", json("attemptId" to "old$n", "score" to 0, "max" to 1))
        }
    }
}
