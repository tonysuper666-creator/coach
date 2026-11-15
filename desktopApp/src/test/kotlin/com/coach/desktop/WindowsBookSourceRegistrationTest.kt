package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.client.PairedCredentials
import com.coach.desktop.books.*
import com.coach.domain.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WindowsBookSourceRegistrationTest {
    private class Entry(agent: Boolean = false) : AutoCloseable {
        val f = BookPracticeOutboxTest.F()
        val library = TextbookLibrary(f.root.resolve("library"))
        val token = "r".repeat(43)
        val settings = DesktopConnectionSettings(f.root.toRealPath(), environment = {
            when (it) {
                "COACH_URL" -> f.base
                "COACH_CLIENT_DIR" -> f.root.resolve("paired-client").toString()
                "COACH_ACCEPTANCE_REPLAY" -> if (agent) "1" else null
                "COACH_AGENT_RUN_ID" -> if (agent) "source-registration-agent" else null
                "COACH_TOKEN_FILE" -> f.root.resolve("unused-synthetic-token").toString()
                else -> null
            }
        }, mac = false, property = { null })
        init { library.save(f.book.copy(learning = true, questionMode = QuestionMode.CHOICE)) }
        fun pair(device: String = "phone") {
            runBlocking { f.actor.execute { enroll("phone", token) } }
            DesktopCredentials(settings.clientDirectory).save(f.base, PairedCredentials(device, token))
        }
        fun commands(): List<Pair<String, String?>> = if (!Files.exists(settings.clientDirectory)) emptyList() else
            Files.walk(settings.clientDirectory).use { paths ->
                paths.filter { it.fileName.toString() == "ledger.sqlite" }.toList().flatMap { file ->
                    DriverManager.getConnection("jdbc:sqlite:$file").use { db -> db.createStatement().use { sql ->
                        sql.executeQuery("SELECT command_id,receipt_status FROM outbox").use { rows ->
                            buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) }
                        }
                    } }
                }
            }
        override fun close() = f.close()
    }
    private fun ComposeUiTest.awaitText(text: String) = waitUntil(timeoutMillis = 30_000) {
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun explicitRegistrationUsesApprovedHttpPoseThenRestoresTheSameGuessAndAck() = Entry().use { e ->
        e.pair()
        assertTrue(e.f.serverEvents().none { it.type == "question_posed" })
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbooksContent(e.library.directory, e.settings) { null } }
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("home-learn").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("open-settings").performClick()
            onNodeWithTag("book-practice-course").performTextInput("C")
            onNodeWithTag("book-source-register").performScrollTo().assertIsNotEnabled()
            assertTrue(e.f.serverEvents().none { it.type == "question_posed" }, "The form cannot auto-confirm")
            onNodeWithTag("book-source-confirm").performScrollTo().performClick()
            onNodeWithTag("book-source-register").performScrollTo().performClick()
            awaitText("已核对课程中确认过的原题，可以返回学习继续作答。")
            assertEquals(1, e.f.serverEvents().count { it.type == "question_posed" })
            assertTrue(e.commands().isEmpty(), "Registration does not submit an answer")
            onNodeWithTag("close-settings").performClick()
            onNodeWithTag("home-learn").performClick()
            awaitText("原题已备好，可同步本次作答。")
            onNodeWithTag("guess-mark").performScrollTo().performClick()
            waitUntil(timeoutMillis = 30_000) { e.library.answer(e.f.book.id, e.f.lesson.id, QuestionMode.CHOICE).guessed }
            onNodeWithTag("choice-${e.f.practice.options.indexOf(e.f.practice.answer)}").performScrollTo().performClick()
            awaitText("原作答已同步。")
        }
        val command = e.commands().single()
        assertEquals("OK", command.second)
        val events = e.f.serverEvents()
        val pose = events.single { it.type == "question_posed" }
        val answer = events.single { it.type == "answer_submitted" }
        assertEquals(pose.payload.str("attemptId"), answer.payload.str("attemptId"))
        assertEquals(command.first, answer.commandId)
        assertEquals("phone", answer.deviceId)
        assertTrue(answer.payload.flag("guessed"))
        assertFalse(e.library.reviews().single().completed)
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbooksContent(e.library.directory, e.settings) { null } }
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("home-learn").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("home-learn").performClick()
            awaitText("原作答已同步。")
        }
        assertEquals(listOf(command), e.commands())
        assertEquals(events, e.f.serverEvents())
        assertFalse(TextbookLibrary(e.library.directory).reviews().single().completed)
    }

    @Test fun lackOfConfirmationOrCurrentBookMismatchNeverCallsTheSourcePort() = Entry().use { e ->
        val calls = AtomicInteger()
        val controller = TextbookController(e.library, sourceRegistration = BookSourceRegistrationPort { calls.incrementAndGet(); null })
        runBlocking {
            controller.load()
            controller.registerPracticeSource(e.f.book.id, "C", confirmed = false)
            controller.registerPracticeSource("other-book", "C", confirmed = true)
            controller.registerPracticeSource(e.f.book.id, "", confirmed = true)
        }
        assertEquals(0, calls.get())
        assertTrue(e.commands().isEmpty())
        assertTrue(e.f.serverEvents().none { it.type == "question_posed" })
    }

    @Test fun unapprovedPdfUnpairedWrongDeviceAndTamperingStayClosedWithoutLosingLocalReading() {
        for (reason in listOf("pdf-without-owner-map", "unpaired", "wrong-device", "changed-file", "assistant", "unknown-course"))
            Entry(agent = reason == "assistant").use { e ->
            if (reason != "unpaired") e.pair(if (reason == "wrong-device") "tablet" else "phone")
            val book = if (reason == "pdf-without-owner-map")
                e.library.importBook(syntheticTextbook(e.f.root.resolve("new-pdf"))) else e.f.book
            val lesson = book.lessons.first { StudyCoachCourse.practice(book, it, QuestionMode.CHOICE) != null }
            val practice = assertNotNull(StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE))
            e.library.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE,
                PracticeAnswer(practice.answer, revealed = true, guessed = true))
            if (reason == "changed-file") Files.writeString(e.library.directory.resolve("${book.id}.${book.format}"), "changed synthetic bytes")
            WindowsBookPracticeConnection(e.settings, e.library).use { connection ->
                val controller = TextbookController(e.library, sourceRegistration = pairedBookSourceRegistration(connection))
                val before = e.f.serverEvents()
                runBlocking {
                    controller.load(); controller.select(book.id); controller.read()
                    controller.registerPracticeSource(book.id, if (reason == "unknown-course") "unknown" else "C", confirmed = true)
                }
                assertEquals(BookScreen.READER, controller.state.value.screen)
                assertTrue(controller.state.value.sourceRegistration.orEmpty().startsWith("尚未登记："), reason)
                assertEquals(before, e.f.serverEvents(), reason)
                assertTrue(e.commands().isEmpty(), reason)
                assertTrue(e.library.answer(book.id, lesson.id, QuestionMode.CHOICE).guessed, reason)
                assertFalse(e.library.reviews().single { it.book == book.id }.completed, reason)
            }
        }
    }
}
