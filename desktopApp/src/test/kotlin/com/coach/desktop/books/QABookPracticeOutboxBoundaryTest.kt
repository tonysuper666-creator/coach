package com.coach.desktop.books

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.server.ledgerRoutes
import com.coach.store.BookSourceLocator
import com.coach.store.LedgerStore
import com.coach.store.ReviewVariantRejected
import com.coach.store.sha256
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Properties
import java.util.UUID
import kotlin.random.Random
import kotlin.test.*

/**
 * Local Practice answer -> client outbox -> real HTTP submit_answer -> the server's original attempt, on
 * isolated SQLite for both client and server, with client and server restarts.
 *
 * The server pose is a stand-in: production has no route that registers and poses a book Practice yet
 * (server registration write domain). The fixture calls LedgerStore directly with the contract
 * BookPracticeOutbox documents; nothing here goes through a client-supplied identity.
 */
class QABookPracticeOutboxBoundaryTest {
    private val md = "<!-- PDF 第 1 页 · 书第 1 页 -->\n# Chapter 1 Light\n\n" +
        "Photosynthesis is the process by which plants use light to make sugars.\n\n# Chapter 2 Cells\n\n" +
        "Chlorophyll is a green pigment that absorbs light in plant cells.\n\n# Chapter 3 Energy\n\n" +
        "Respiration is the process that releases energy from stored sugars.\n"

    private inner class F : AutoCloseable {
        val root = Files.createTempDirectory("book-practice-outbox-")
        var clock = 1_794_052_800_000L - 86_400_000
        val token = "synthetic-book-practice-private-token-32c"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var store = LedgerStore(root.resolve("server")) { clock }
        val actor = LedgerActor(scope, store)
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8)) }
        val book: Textbook
        val lesson: BookLesson
        val practice: Practice
        val wrong: String
        val options: List<Option>
        val base: String
        private fun openDriver() = JdbcSqliteDriver("jdbc:sqlite:${root.resolve("client.sqlite")}", Properties(), ClientDatabase.Schema)
        var driver = openDriver()
        val transport: HttpLedgerTransport
        var sync: SyncEngine
        init {
            val file = root.resolve("source").also { Files.createDirectories(it) }.resolve("chapter.md")
            Files.writeString(file, md)
            book = TextbookLibrary(root.resolve("library")).importBook(file)
            lesson = book.lessons.first { StudyCoachCourse.practice(book, it, QuestionMode.CHOICE) != null }
            practice = assertNotNull(StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE))
            wrong = practice.options.first { it != practice.answer }
            options = practice.options.mapIndexed { n, t -> Option("o$n", t) }
            val correct = options.single { it.text == practice.answer }.optionId
            store.enroll("phone", token)
            store.enroll("tablet", "synthetic-book-practice-tablet-token-32c!")
            val m = Regex("<!--\\s*PDF 第 (\\d+) 页 · 书第 (.*?) 页\\s*-->").find(md)!!
            store.seedContent(ContentSeed(CourseContent(Course("C", "C", "Synthetic"), listOf(Module("M", 1, "M")),
                listOf(KnowledgePoint("K", "M", 1, "K", anchors = listOf(SourceAnchor("D", 1))))),
                listOf(TextbookDocument("D", "Synthetic", md, listOf(TextbookPage(m.groupValues[1].toInt(), 1, md))))))
            store.registerQuestion("bp", "C", "", "SINGLE_CHOICE", practice.prompt, "VERIFIED", correct, options, listOf("K"))
            server.start(false)
            base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"
            transport = HttpLedgerTransport(base, "phone", token)
            sync = newSync()
        }
        val block get() = lesson.blocks.first { !it.heading && it.text.contains(practice.reference) }
        fun bookLink(lessonId: String = lesson.id, blockId: String = block.id) = json("bookSha256" to book.id,
            "lessonId" to lessonId, "blockId" to blockId, "reference" to practice.reference)
        /** Stand-in for the missing server route: a server-appended pose for one paired device. */
        fun serverPose(attempt: String, device: String = "phone", link: JsonObject? = bookLink()) = runBlocking { actor.execute {
            poseChoiceGroup("C", "book-practice:$attempt", listOf(JsonObject(json("attemptId" to attempt, "questionId" to "bp",
                "kpIds" to listOf("K"), "purpose" to "PRACTICE", "policyVersion" to 1, "posedAt" to clock) +
                (link?.let { mapOf("bookPractice" to it) } ?: emptyMap()))), Random(7), device = device).single()
        } }
        fun newSync(t: LedgerTransport = transport) = SyncEngine(ClientDatabase(driver), "phone", t, listOf("C"), { UUID.randomUUID().toString() }, ::sha256)
        fun restartClient() { driver.close(); driver = openDriver(); sync = newSync() }
        fun outbox() = sync.retainedCommands().size
        fun serverEvents() = runBlocking { actor.execute { events("C") } }
        fun bind() = BookPracticeOutbox(sync).bind("C", book, lesson, practice)
        fun notReady(expected: String) {
            val before = serverEvents(); val queued = outbox()
            val b = bind()
            assertEquals(BookPracticeBinding.NotReady(expected), b)
            runBlocking { sync.syncOnce() }
            assertEquals(queued, outbox(), "no outbox increment"); assertEquals(before, serverEvents(), "no server increment")
        }
        override fun close() {
            runCatching { transport.close() }; runCatching { driver.close() }; runCatching { server.stop(0, 0) }
            runCatching { runBlocking { actor.close() } }; scope.cancel(); runCatching { store.close() }; root.toFile().deleteRecursively()
        }
    }

    @Test fun aVoidBeforeBindingIsRefusedWithoutAnyIncrement() = F().use { f ->
        f.serverPose("bp-attempt")
        runBlocking { f.actor.execute { append("C", "attempt_voided", json("attemptId" to "bp-attempt", "reason" to "synthetic-invalid")) }; f.sync.syncOnce() }
        f.notReady("BOOK_PRACTICE_VOIDED")
    }

    @Test fun anAlreadyBoundAttemptCannotBeQueuedAfterItsVoidArrives() = F().use { f ->
        f.serverPose("bp-attempt"); runBlocking { f.sync.syncOnce() }
        val outbox = BookPracticeOutbox(f.sync)
        val ready = assertIs<BookPracticeBinding.Ready>(f.bind())
        runBlocking { f.actor.execute { append("C", "attempt_voided", json("attemptId" to "bp-attempt", "reason" to "synthetic-invalid")) }; f.sync.syncOnce() }
        assertEquals(BookPracticeBinding.NotReady("BOOK_PRACTICE_VOIDED"), f.bind())
        val before = f.serverEvents()
        val result = runCatching { outbox.submit(ready, PracticeAnswer(f.wrong, guessed = true), f.clock) }
        val queued = f.outbox()
        runBlocking { f.sync.syncOnce() }
        val added = f.serverEvents().drop(before.size)
        println("QA stale void: rejected=${result.isFailure} outboxDelta=$queued serverDelta=${added.size} types=${added.map { it.type }} receipt=${result.getOrNull()?.let { f.sync.receipt(it)?.status }}")
        assertTrue(result.isFailure, "submit must revalidate an attempt whose void is already in this replica")
        assertEquals(0, queued); assertEquals(before, f.serverEvents())
    }

    @Test fun anAssistantSessionCannotBindAndQueueTheLearnersOriginalAttempt() = F().use { f ->
        f.serverPose("bp-attempt"); runBlocking { f.sync.syncOnce() }
        val agentSync = SyncEngine(ClientDatabase(f.driver), "phone", f.transport, listOf("C"), { UUID.randomUUID().toString() }, ::sha256, agentRunId = "qa-isolated-run")
        val before = f.serverEvents()
        val outbox = BookPracticeOutbox(agentSync)
        val binding = outbox.bind("C", f.book, f.lesson, f.practice)
        val id = (binding as? BookPracticeBinding.Ready)?.let { outbox.submit(it, PracticeAnswer(f.wrong, guessed = true), f.clock) }
        val queued = f.outbox()
        runBlocking { agentSync.syncOnce() }
        println("QA agent scope: binding=${binding.javaClass.simpleName} outboxDelta=$queued serverDelta=${f.serverEvents().size-before.size} receipt=${id?.let { agentSync.receipt(it)?.status }} error=${id?.let { agentSync.receipt(it)?.error }}")
        assertIs<BookPracticeBinding.NotReady>(binding, "assistant must not consume a learner pose")
        assertEquals(0, queued); assertEquals(before, f.serverEvents())
    }
}
