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
class BookPracticeOutboxTest {
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

    @Test fun localAnswerOfAServerPosedPracticeReachesTheOriginalAttemptAcrossRestarts() = F().use { f ->
        f.serverPose("bp-attempt")
        runBlocking { f.sync.syncOnce() }
        val ready = assertIs<BookPracticeBinding.Ready>(f.bind())
        assertEquals("bp-attempt", ready.attemptId)
        val before = f.serverEvents()
        val id = BookPracticeOutbox(f.sync).submit(ready, PracticeAnswer(f.wrong, guessed = true), f.clock)
        assertEquals(1, f.outbox()); assertEquals(before, f.serverEvents(), "queued only, not yet synced")
        // Client process restart before acknowledgement: the same command survives and a retry does not add one.
        f.restartClient()
        val again = assertIs<BookPracticeBinding.Ready>(f.bind())
        assertEquals(id, BookPracticeOutbox(f.sync).submit(again, PracticeAnswer(f.wrong, guessed = true), f.clock))
        assertEquals(1, f.outbox())
        runBlocking { f.sync.syncOnce() }
        val receipt = assertNotNull(f.sync.receipt(id))
        println("PRACTICE outbox receipt=${receipt.status}")
        assertEquals("OK", receipt.status)
        val answered = f.serverEvents().single { it.type == "answer_submitted" && it.payload.str("attemptId") == "bp-attempt" }
        assertEquals("phone", answered.deviceId); assertEquals(id, answered.commandId); assertTrue(answered.payload.flag("guessed"))
        assertEquals(1, f.serverEvents().count { it.type == "question_posed" && it.payload.str("attemptId") == "bp-attempt" })
        assertEquals(0L, f.serverEvents().single { it.type == "attempt_graded" && it.payload.str("attemptId") == "bp-attempt" }.payload.num("score"))
        // Answered attempt is locked; a different choice cannot be queued.
        assertFails { BookPracticeOutbox(f.sync).submit(f.bind() as BookPracticeBinding.Ready, PracticeAnswer(f.practice.answer), f.clock) }
        // Server restart on the same SQLite: the facts persist and PR229's producer accepts the original attempt.
        f.server.stop(0, 0); runBlocking { f.actor.close() }; f.store.close()
        f.store = LedgerStore(f.root.resolve("server")) { f.clock }
        val identity = f.store.publishBookLedgerSourceFromServerFacts(BookSourceLocator("C", "phone", "bp-attempt", f.book.id,
            f.lesson.id, f.block.page, f.block.start, f.block.end, f.practice.reference, f.practice.options))
        println("PRACTICE producer attempt=${identity.originalAttemptId} guessed=${identity.guessed} original=${identity.originalAnswer == f.wrong}")
        assertTrue(identity.guessed); assertEquals(f.wrong, identity.originalAnswer); assertEquals(f.practice.prompt, identity.prompt)
    }

    @Test fun unknownForeignOrUnlinkedIdentitiesStayNotReadyWithZeroIncrements() = F().use { f ->
        runBlocking { f.sync.syncOnce() }
        f.notReady("BOOK_PRACTICE_UNPOSED") // no server pose at all
        f.serverPose("tablet-attempt", device = "tablet"); runBlocking { f.sync.syncOnce() }
        f.notReady("BOOK_PRACTICE_UNPOSED") // posed to another paired device
        f.serverPose("plain-attempt", link = null); runBlocking { f.sync.syncOnce() }
        f.notReady("BOOK_PRACTICE_UNPOSED") // same stem, but no server book link
        f.serverPose("other-lesson", link = f.bookLink(lessonId = "other-lesson")); runBlocking { f.sync.syncOnce() }
        f.notReady("BOOK_PRACTICE_UNPOSED") // linked to another lesson
        // A command-created pose with a forged link is not a server pose.
        val forged = JsonObject(f.serverEvents().single { it.payload.str("attemptId") == "plain-attempt" }.payload +
            mapOf("attemptId" to JsonPrimitive("forged-attempt"), "groupId" to JsonPrimitive("forged"), "bookPractice" to f.bookLink()))
        val raw = canonicalJson(LedgerJson.encodeToJsonElement(CommandBody("submit_answer", "C", f.clock,
            json("attemptId" to "forged-attempt", "answer" to "A", "posedSnapshot" to forged))))
        val forgedReceipt = runBlocking { f.actor.execute { command(WireCommand("phone", "forged-pose", raw, sha256(raw.toByteArray()))) } }
        println("PRACTICE forged pose receipt=${forgedReceipt.status}")
        runBlocking { f.sync.syncOnce() }
        f.notReady("BOOK_PRACTICE_UNPOSED")
        // An unpaired credential cannot move a queued answer: server stays unchanged, the command stays pending.
        f.serverPose("bp-attempt"); runBlocking { f.sync.syncOnce() }
        val ready = assertIs<BookPracticeBinding.Ready>(f.bind())
        val id = BookPracticeOutbox(f.sync).submit(ready, PracticeAnswer(f.wrong), f.clock)
        val before = f.serverEvents()
        val bad = HttpLedgerTransport(f.base, "phone", "synthetic-wrong-token-for-phone-device-32c")
        try { runCatching { runBlocking { f.newSync(bad).syncOnce() } }.also { println("PRACTICE bad token=${it.exceptionOrNull()?.javaClass?.simpleName}") } }
        finally { bad.close() }
        assertEquals(before, f.serverEvents()); assertNull(f.sync.receipt(id))
        // The tablet's pose answered from the phone is refused by the producer: device binding is end to end.
        val tabletPose = f.serverEvents().single { it.payload.str("attemptId") == "tablet-attempt" }
        val letter = tabletPose.payload.getValue("letterMap").jsonObject.values.first().jsonPrimitive.content
        val cross = canonicalJson(LedgerJson.encodeToJsonElement(CommandBody("submit_answer", "C", f.clock,
            json("attemptId" to "tablet-attempt", "answer" to letter, "posedSnapshot" to tabletPose.payload))))
        val crossReceipt = runBlocking { f.actor.execute { command(WireCommand("phone", "cross-device", cross, sha256(cross.toByteArray()))) } }
        val e = assertFailsWith<ReviewVariantRejected> { runBlocking { f.actor.execute { publishBookLedgerSourceFromServerFacts(BookSourceLocator(
            "C", "phone", "tablet-attempt", f.book.id, f.lesson.id, f.block.page, f.block.start, f.block.end, f.practice.reference, f.practice.options)) } } }
        println("PRACTICE cross-device producer=${e.code} serverReceipt=${crossReceipt.status}")
        assertEquals("BOOK_DEVICE_MISMATCH", e.code)
        assertEquals(0, DriverManager.getConnection("jdbc:sqlite:${f.root.resolve("server").resolve("coach.sqlite")}").use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM book_ledger_sources").use { it.next(); it.getInt(1) } } })
    }
}
