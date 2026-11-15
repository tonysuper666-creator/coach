package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import java.nio.file.Files
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.server.ledgerRoutes
import com.coach.store.LedgerStore
import com.coach.store.sha256
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.Properties
import java.util.UUID
import kotlin.random.Random
import kotlin.test.*

/** Can run unchanged on the old tree to expose the missing actual-host state. */
@OptIn(ExperimentalTestApi::class)
class PracticeSyncMountTest {
    @Test fun unpairedPracticeShowsNotReadyInHost() {
        val root = Files.createTempDirectory("practice-sync-unpaired-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("source")))
        library.save(book.copy(learning = true, questionMode = QuestionMode.CHOICE))
        val controller = TextbookController(library)
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbookHost(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            onNodeWithTag("home-learn").performClick()
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.screen == BookScreen.LEARNING }
            onNodeWithTag("practice-sync-status").performScrollTo().assertTextEquals("同步未备好：尚未连接配对课程。")
        }
    }

    @Test fun successfulReceiptUpdatesHostOnRetryAndSurvivesNewController() = ReceiptFixture().use { f ->
        val controller = f.controller()
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbookHost(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            onNodeWithTag("home-learn").performClick()
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.screen == BookScreen.LEARNING }
            onNodeWithTag("guess-mark").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.answer.guessed }
            onNodeWithTag("choice-${f.practice.options.indexOf(f.practice.answer)}").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.practiceSync == "原作答已保存，等待同步。" }
            val command = f.sync.retainedCommands().single()
            runBlocking { f.sync.syncOnce() }
            assertEquals("OK", f.sync.receipt(command.commandId)?.status)
            onNodeWithTag("practice-sync-retry").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy }
            onNodeWithTag("practice-sync-status").assertTextEquals("原作答已同步。")
            assertEquals(listOf(command), f.sync.retainedCommands())
            assertFalse(controller.library.reviews().single().completed, "guessing correctly remains unfinished")
            // A receipt for the frozen answer must not mark a later local edit as synced.
            runBlocking { controller.answer(PracticeAnswer(f.practice.answer, revealed = true, guessed = false)); controller.learn() }
            assertEquals("原作答已锁定，当前本机修改未同步。", controller.state.value.practiceSync)
            runBlocking { controller.answer(PracticeAnswer(f.practice.answer, revealed = true, guessed = true)) }
        }
        f.restart()
        val reopened = f.controller()
        runBlocking { reopened.load(); reopened.learn() }
        assertEquals("原作答已同步。", reopened.state.value.practiceSync)
        assertTrue(reopened.state.value.answer.guessed)
        assertEquals(1, f.sync.retainedCommands().size)
        assertEquals(1, f.events().count { it.type == "answer_submitted" })
        assertFalse(reopened.library.reviews().single().completed)
    }

    @Test fun rejectedReceiptShowsReasonAndKeepsRetryWithoutReplacingTheCommand() = ReceiptFixture(reject = true).use { f ->
        val controller = f.controller()
        runBlocking {
            controller.load(); controller.learn()
            controller.answer(PracticeAnswer(f.practice.answer, revealed = true, guessed = true))
            f.sync.syncOnce()
            controller.retryPracticeSync()
        }
        val command = f.sync.retainedCommands().single()
        assertEquals("REJECTED", f.sync.receipt(command.commandId)?.status)
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbookHost(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy }
            // Host load may return home; entering learning must read the same persisted receipt.
            runBlocking { controller.learn() }
            onNodeWithTag("practice-sync-status").performScrollTo().assertTextEquals("同步被拒绝：SYNTHETIC_POLICY_REJECTED")
            onNodeWithTag("practice-sync-retry").performScrollTo().assertIsEnabled().performClick()
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy }
            onNodeWithTag("practice-sync-status").assertTextEquals("同步被拒绝：SYNTHETIC_POLICY_REJECTED")
        }
        f.restart()
        val reopened = f.controller()
        runBlocking { reopened.load(); reopened.learn(); reopened.retryPracticeSync() }
        assertEquals("同步被拒绝：SYNTHETIC_POLICY_REJECTED", reopened.state.value.practiceSync)
        assertEquals(listOf(command), f.sync.retainedCommands())
        assertEquals(0, f.events().count { it.type == "answer_submitted" })
        assertFalse(reopened.library.reviews().single().completed)
    }

    @Test fun pendingReceiptRemainsWaitingWhenLearningReopensWithoutEnqueueingAgain() = ReceiptFixture().use { f ->
        val controller = f.controller()
        runBlocking {
            controller.load(); controller.learn()
            controller.answer(PracticeAnswer(f.practice.answer, revealed = true, guessed = true))
        }
        val command = f.sync.retainedCommands().single()
        f.restart()
        val reopened = f.controller()
        runBlocking { reopened.load(); reopened.learn() }
        assertEquals("原作答已保存，等待同步。", reopened.state.value.practiceSync)
        assertNull(f.sync.receipt(command.commandId))
        assertEquals(listOf(command), f.sync.retainedCommands())
        assertEquals(0, f.events().count { it.type == "answer_submitted" })
    }

    /** Real HTTP/SQLite answer acknowledgement; the initial server pose is a test fixture, not a production route. */
    private class ReceiptFixture(val reject: Boolean = false) : AutoCloseable {
        val root = Files.createTempDirectory("practice-receipt-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("source")))
        val lesson = book.lessons.first { StudyCoachCourse.practice(book, it, QuestionMode.CHOICE) != null }
        val practice = assertNotNull(StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE))
        val clock = 1_794_052_800_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(root.resolve("server")) { clock }
        val actor = LedgerActor(scope, store)
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8)) }
        private fun driver() = JdbcSqliteDriver("jdbc:sqlite:${root.resolve("client.sqlite")}", Properties(), ClientDatabase.Schema)
        var dbDriver = driver()
        val http: HttpLedgerTransport
        val transport: LedgerTransport
        var sync: SyncEngine
        init {
            library.save(book.copy(learning = true, questionMode = QuestionMode.CHOICE))
            store.enroll("phone", "synthetic-receipt-token-32-characters")
            store.seedContent(ContentSeed(CourseContent(Course("C", "C", "Synthetic"), listOf(Module("M", 1, "M")),
                listOf(KnowledgePoint("K", "M", 1, "K"))), emptyList()))
            val options = practice.options.mapIndexed { n, text -> Option("o$n", text) }
            store.registerQuestion("bp", "C", "", "SINGLE_CHOICE", practice.prompt, "VERIFIED",
                options.single { it.text == practice.answer }.optionId, options, listOf("K"))
            val block = lesson.blocks.first { !it.heading && it.text.contains(practice.reference) }
            store.poseChoiceGroup("C", "receipt-group", listOf(json("attemptId" to "receipt-attempt", "questionId" to "bp",
                "kpIds" to listOf("K"), "purpose" to "PRACTICE", "policyVersion" to 1, "posedAt" to clock,
                "bookPractice" to json("bookSha256" to book.id, "lessonId" to lesson.id, "blockId" to block.id,
                    "reference" to practice.reference))), Random(7), device = "phone")
            server.start(false)
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            http = HttpLedgerTransport("http://127.0.0.1:$port", "phone", "synthetic-receipt-token-32-characters")
            transport = if (!reject) http else object : LedgerTransport by http {
                override suspend fun command(command: WireCommand): Receipt {
                    val hello = http.hello(null)
                    return Receipt(command.commandId, "REJECTED", error = "SYNTHETIC_POLICY_REJECTED",
                        epoch = hello.epoch, backedUpThroughSeq = hello.backedUpThroughSeq)
                }
            }
            sync = newSync()
            runBlocking { sync.syncOnce() }
        }
        private fun newSync() = SyncEngine(ClientDatabase(dbDriver), "phone", transport, listOf("C"), { UUID.randomUUID().toString() }, ::sha256)
        fun controller() = TextbookController(TextbookLibrary(root.resolve("library")), BookPracticeSession(sync, "C") { clock })
        fun restart() { dbDriver.close(); dbDriver = driver(); sync = newSync() }
        fun events() = runBlocking { actor.execute { events("C") } }
        override fun close() {
            http.close(); dbDriver.close(); server.stop(0, 0)
            runBlocking { actor.close() }; scope.cancel(); store.close(); root.toFile().deleteRecursively()
        }
    }
}
