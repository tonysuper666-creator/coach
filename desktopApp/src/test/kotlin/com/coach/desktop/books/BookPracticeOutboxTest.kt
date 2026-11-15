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
import com.coach.desktop.WindowsTextbookHost
import androidx.compose.ui.test.*
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
 * The positive path now uses the authenticated book-practice-pose route. Direct legacy poses below
 * are adversarial fixtures only, proving that a link alone cannot authorize an answer.
 */
class BookPracticeOutboxTest {
    internal class F : AutoCloseable {
        private val md = "<!-- PDF 第 1 页 · 书第 1 页 -->\n# Chapter 1 Light\n\n" +
        "Photosynthesis is the process by which plants use light to make sugars.\n\n# Chapter 2 Cells\n\n" +
        "Chlorophyll is a green pigment that absorbs light in plant cells.\n\n# Chapter 3 Energy\n\n" +
        "Respiration is the process that releases energy from stored sugars.\n"

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
        val seed: ContentSeed
        val questionId: String
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
            store.enroll("phone", token)
            store.enroll("tablet", "synthetic-book-practice-tablet-token-32c!")
            val m = Regex("<!--\\s*PDF 第 (\\d+) 页 · 书第 (.*?) 页\\s*-->").find(md)!!
            seed = ContentSeed(CourseContent(Course("C", "C", "Synthetic"), listOf(Module("M", 1, "M")),
                listOf(KnowledgePoint("K", "M", 1, "K", anchors = listOf(SourceAnchor("D", 1, quote = practice.reference))))),
                listOf(TextbookDocument("D", "Synthetic", md, listOf(TextbookPage(m.groupValues[1].toInt(), 1, md)))))
            questionId = installPracticeMapping(store, root, seed, book, lesson, practice, options)
            server.start(false)
            base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"
            transport = HttpLedgerTransport(base, "phone", token)
            sync = newSync()
        }
        val block get() = lesson.blocks.first { !it.heading && it.text.contains(practice.reference) }
        fun source() = practiceSource(book, lesson, block, practice)
        fun actualPose() = practiceHttp(base, "/v1/courses/C/book-practice-pose", source().toString())
        fun bookLink(lessonId: String = lesson.id, blockId: String = block.id) = json("bookSha256" to book.id,
            "lessonId" to lessonId, "blockId" to blockId, "reference" to practice.reference)
        /** Legacy/unregistered server link, used only by rejection tests. */
        fun serverPose(attempt: String, device: String = "phone", link: JsonObject? = bookLink()) = runBlocking { actor.execute {
            poseChoiceGroup("C", "book-practice:$attempt", listOf(JsonObject(json("attemptId" to attempt, "questionId" to questionId,
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
        val response = f.actualPose()
        assertEquals(200, response.statusCode(), response.body())
        runBlocking { f.sync.syncOnce() }
        val ready = assertIs<BookPracticeBinding.Ready>(f.bind())
        val attempt = ready.attemptId
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
        val answered = f.serverEvents().single { it.type == "answer_submitted" && it.payload.str("attemptId") == attempt }
        assertEquals("phone", answered.deviceId); assertEquals(id, answered.commandId); assertTrue(answered.payload.flag("guessed"))
        assertEquals(1, f.serverEvents().count { it.type == "question_posed" && it.payload.str("attemptId") == attempt })
        assertEquals(0L, f.serverEvents().single { it.type == "attempt_graded" && it.payload.str("attemptId") == attempt }.payload.num("score"))
        // Answered attempt is locked; a different choice cannot be queued.
        assertFails { BookPracticeOutbox(f.sync).submit(f.bind() as BookPracticeBinding.Ready, PracticeAnswer(f.practice.answer), f.clock) }
        // Server restart on the same SQLite: the facts persist and PR229's producer accepts the original attempt.
        f.server.stop(0, 0); runBlocking { f.actor.close() }; f.store.close()
        f.store = LedgerStore(f.root.resolve("server")) { f.clock }
        val identity = f.store.publishBookLedgerSourceFromServerFacts(BookSourceLocator("C", "phone", attempt, f.book.id,
            f.lesson.id, f.block.page, f.block.start, f.block.end, f.practice.reference, f.practice.options))
        println("PRACTICE producer attempt=${identity.originalAttemptId} guessed=${identity.guessed} original=${identity.originalAnswer == f.wrong}")
        assertTrue(identity.guessed); assertEquals(f.wrong, identity.originalAnswer); assertEquals(f.practice.prompt, identity.prompt)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun hostSubmitsGuessToOriginalAttemptAndReopensTheSameCommand() = F().use { f ->
        val library = TextbookLibrary(f.root.resolve("library"))
        library.save(f.book.copy(learning = true, questionMode = QuestionMode.CHOICE))
        f.serverPose("host-attempt")
        runBlocking { f.sync.syncOnce() }
        val controller = TextbookController(library, BookPracticeSession(f.sync, "C") { f.clock })
        runComposeUiTest {
            setContent { WindowsTextbookHost(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.book?.id == f.book.id && !controller.state.value.busy }
            onNodeWithTag("home-learn").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            onNodeWithTag("practice-sync-status").assertTextEquals("原题已备好，可同步本次作答。")
            onNodeWithTag("guess-mark").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.answer.guessed }
            onNodeWithTag("choice-${f.practice.options.indexOf(f.practice.answer)}").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.practiceSync == "原作答已保存，等待同步。" }
            assertEquals(1, f.outbox())
            // A later local edit cannot silently replace the frozen original command.
            runBlocking { controller.answer(PracticeAnswer(f.wrong, revealed = true, guessed = true)) }
            assertEquals("原作答已锁定，当前本机修改未同步。", controller.state.value.practiceSync)
            assertEquals(1, f.outbox())
            runBlocking { controller.answer(PracticeAnswer(f.practice.answer, revealed = true, guessed = true)) }
        }
        val command = f.sync.retainedCommands().single().commandId
        f.restartClient()
        val reopened = TextbookController(TextbookLibrary(f.root.resolve("library")), BookPracticeSession(f.sync, "C") { f.clock })
        runBlocking { reopened.load(); reopened.learn(); reopened.retryPracticeSync() }
        assertTrue(reopened.state.value.answer.guessed)
        assertEquals(command, f.sync.retainedCommands().single().commandId)
        runBlocking { f.sync.syncOnce() }
        assertEquals("OK", f.sync.receipt(command)?.status)
        val submitted = f.serverEvents().single { it.type == "answer_submitted" && it.payload.str("attemptId") == "host-attempt" }
        assertEquals(command, submitted.commandId)
        assertEquals("phone", submitted.deviceId)
        assertTrue(submitted.payload.flag("guessed"))
        val pose = f.serverEvents().single { it.type == "question_posed" && it.payload.str("attemptId") == "host-attempt" }
        val correctId = f.options.single { it.text == f.practice.answer }.optionId
        assertEquals(pose.payload.getValue("letterMap").jsonObject.getValue(correctId).jsonPrimitive.content, submitted.payload.str("answer"))
        assertFalse(reopened.library.reviews().single().completed)
        println("PRACTICE_HOST attempt=host-attempt device=phone command=$command guessed=true correct=true receipt=OK reviewCompleted=false")
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
        assertEquals(200, f.actualPose().statusCode()); runBlocking { f.sync.syncOnce() }
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
        assertEquals("REJECTED", crossReceipt.status)
        assertEquals("BOOK_ANSWER_MISMATCH", e.code)
        assertEquals(0, DriverManager.getConnection("jdbc:sqlite:${f.root.resolve("server").resolve("coach.sqlite")}").use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM book_ledger_sources").use { it.next(); it.getInt(1) } } })
    }

    @Test fun alteredPosedFingerprintCannotQueueTheLocalAnswer() = F().use { f ->
        val library = TextbookLibrary(f.root.resolve("library"))
        library.save(f.book.copy(learning = true, questionMode = QuestionMode.CHOICE))
        f.serverPose("fingerprint-attempt")
        runBlocking { f.sync.syncOnce() }
        val original = f.sync.localEvents("C").single { it.type == "question_posed" }
        val altered = original.copy(payload = JsonObject(original.payload + ("fingerprint" to JsonPrimitive("synthetic-altered-fingerprint"))))
        // Simulate a changed replica, leaving the authenticated server pose untouched.
        DriverManager.getConnection("jdbc:sqlite:${f.root.resolve("client.sqlite")}").use { db ->
            db.prepareStatement("UPDATE replica SET event_json=? WHERE seq=?").use { statement ->
                statement.setString(1, LedgerJson.encodeToString(Event.serializer(), altered))
                statement.setLong(2, original.seq)
                assertEquals(1, statement.executeUpdate())
            }
        }
        val before = f.serverEvents()
        val controller = TextbookController(library, BookPracticeSession(f.sync, "C") { f.clock })
        runBlocking {
            controller.load()
            controller.learn()
            controller.answer(PracticeAnswer(f.practice.answer, revealed = true, guessed = true))
            controller.retryPracticeSync()
            f.sync.syncOnce()
        }
        assertEquals("同步未备好：原题身份尚未确认。", controller.state.value.practiceSync)
        assertEquals(0, f.outbox())
        assertEquals(before, f.serverEvents())
        assertTrue(library.answer(f.book.id, f.lesson.id, QuestionMode.CHOICE).guessed)
        f.restartClient()
        assertEquals(0, f.outbox())
        assertEquals(before, f.serverEvents())
        println("PRACTICE_FINGERPRINT changed=true outbox=0 serverDelta=0 guessedPreserved=true")
    }

    @Test fun aLearnerReadyHandedToAnAssistantSessionIsRefusedWithoutAnyIncrement() = F().use { f ->
        assertEquals(200, f.actualPose().statusCode()); runBlocking { f.sync.syncOnce() }
        val ready = assertIs<BookPracticeBinding.Ready>(f.bind())
        val agent = SyncEngine(ClientDatabase(f.driver), "phone", f.transport, listOf("C"), { UUID.randomUUID().toString() },
            ::sha256, agentRunId = "synthetic-agent-run")
        val before = f.serverEvents()
        val e = assertFailsWith<IllegalStateException> {
            BookPracticeOutbox(agent).submit(ready, PracticeAnswer(f.wrong, guessed = true), f.clock) }
        println("PRACTICE agent handed learner ready=${e.message}")
        assertEquals("BOOK_PRACTICE_AGENT_SCOPE", e.message)
        runBlocking { agent.syncOnce(); f.sync.syncOnce() }
        assertEquals(0, f.outbox()); assertEquals(before, f.serverEvents())
        // The learner session still owns its attempt.
        assertEquals("OK", BookPracticeOutbox(f.sync).submit(ready, PracticeAnswer(f.wrong), f.clock)
            .also { runBlocking { f.sync.syncOnce() } }.let { assertNotNull(f.sync.receipt(it)).status })
    }
}
