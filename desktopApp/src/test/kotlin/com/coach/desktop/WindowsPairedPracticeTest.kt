package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.client.PairedCredentials
import com.coach.desktop.books.*
import com.coach.protocol.*
import com.coach.domain.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlin.test.*

/** Ordinary startup, optional pairing, formal authenticated pose and HTTP receipt; all data synthetic. */
@OptIn(ExperimentalTestApi::class)
class WindowsPairedPracticeTest {
    private val unavailableNotice = "这本原书的题目已撤回或暂不可用，请核对课程并重新登记原书。"
    private class Entry : AutoCloseable {
        val f = BookPracticeOutboxTest.F()
        val library = TextbookLibrary(f.root.resolve("library"))
        val token = "p".repeat(43)
        val settings = DesktopConnectionSettings(f.root.toRealPath(), environment = {
            when (it) { "COACH_URL" -> f.base; "COACH_CLIENT_DIR" -> f.root.toRealPath().resolve("paired-client").toString(); else -> null }
        }, mac = false, property = { null })
        val credentials = DesktopCredentials(settings.clientDirectory)
        init { library.save(f.book.copy(learning = true, questionMode = QuestionMode.CHOICE)) }
        fun pair(device: String = "phone") {
            runBlocking { f.actor.execute { enroll("phone", token) } }
            credentials.save(f.base, PairedCredentials(device, token))
        }
        fun connection() = WindowsBookPracticeConnection(settings, library)
        fun controller(connection: WindowsBookPracticeConnection) = TextbookController(library, connectPractice = connection::prepare)
        fun commands(): List<Pair<String, String?>> = if (!Files.exists(settings.clientDirectory)) emptyList() else Files.walk(settings.clientDirectory).use { files ->
            files.filter { it.fileName.toString() == "ledger.sqlite" }.toList().flatMap { db ->
                DriverManager.getConnection("jdbc:sqlite:$db").use { c -> c.createStatement().use { s ->
                    s.executeQuery("SELECT command_id,receipt_status FROM outbox").use { r ->
                        buildList { while (r.next()) add(r.getString(1) to r.getString(2)) }
                    }
                } }
            }
        }
        fun assertZero(before: List<com.coach.domain.Event>) {
            assertEquals(before, f.serverEvents())
            assertTrue(commands().isEmpty(), "refusal must not enqueue")
            assertTrue(library.answer(f.book.id, f.lesson.id, QuestionMode.CHOICE).guessed)
            assertFalse(library.reviews().single().completed)
        }
        override fun close() { f.close() }
    }
    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun ComposeUiTest.capture(name: String) {
        onNodeWithTag("practice-sync-status").performScrollTo().assertIsDisplayed()
        val file = Path.of("build/screens/windows-paired-practice", name).toFile()
        file.parentFile.mkdirs()
        assertTrue(ImageIO.write(onNodeWithTag("windows-host").captureToImage().toAwtImage(), "png", file))
    }
    private fun ComposeUiTest.enterCourse() {
        onNodeWithTag("open-settings").performClick()
        onNodeWithTag("book-practice-course").performTextInput("C")
        onNodeWithTag("book-practice-save-course").performScrollTo().performClick()
        awaitText("已保存查找位置，开始学习时核对原题。")
        onNodeWithTag("close-settings").performClick()
    }
    @Test fun ordinaryEntryPosesSyncsOwnGuessAndReopensTheSameReceipt() = Entry().use { e ->
        e.pair()
        assertTrue(e.f.serverEvents().none { it.type == "question_posed" })
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbooksContent(e.library.directory, e.settings) { null } }
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("home-learn").fetchSemanticsNodes().isNotEmpty() }
            enterCourse()
            onNodeWithTag("home-learn").performClick()
            awaitText("原题已备好，可同步本次作答。")
            onAllNodesWithText(unavailableNotice).assertCountEquals(0)
            onNodeWithTag("choice-0").assertExists()
            capture("01-formal-pose.png")
            onNodeWithTag("guess-mark").performScrollTo().performClick()
            // Draft saving must finish before the explicit choice is made.
            waitUntil(timeoutMillis = 30_000) { e.library.answer(e.f.book.id, e.f.lesson.id, QuestionMode.CHOICE).guessed }
            onNodeWithTag("choice-${e.f.practice.options.indexOf(e.f.practice.answer)}").performScrollTo().performClick()
            awaitText("原作答已同步。")
            capture("02-acknowledged.png")
        }
        val command = e.commands().single()
        assertEquals("OK", command.second)
        val events = e.f.serverEvents()
        val pose = events.single { it.type == "question_posed" }
        val answer = events.single { it.type == "answer_submitted" }
        assertEquals(pose.payload.str("attemptId"), answer.payload.str("attemptId"))
        assertEquals(command.first, answer.commandId)
        assertEquals("phone", pose.deviceId); assertEquals("phone", answer.deviceId)
        assertTrue(answer.payload.flag("guessed"))
        assertFalse(e.library.reviews().single().completed, "correct guesses remain in review")
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbooksContent(e.library.directory, e.settings) { null } }
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("home-learn").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("home-learn").performClick()
            awaitText("原作答已同步。")
            capture("03-new-host-same-receipt.png")
        }
        assertEquals(listOf(command), e.commands())
        assertEquals(events, e.f.serverEvents(), "reopening cannot pose or answer twice")
        assertEquals(e.f.practice.answer, TextbookLibrary(e.library.directory).answer(e.f.book.id, e.f.lesson.id, QuestionMode.CHOICE).text)
        println("WINDOWS_ENTRY formalPose=1 answer=1 command=${command.first} receipt=OK guessed=true completed=false reopenedSame=true")
        snapshot(e)
    }

    @Test fun withdrawnMappingReturns409AndHidesPreviouslyReadyQuestion() = Entry().use { e ->
        e.pair()
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbooksContent(e.library.directory, e.settings) { null } }
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("home-learn").fetchSemanticsNodes().isNotEmpty() }
            enterCourse()
            onNodeWithTag("home-learn").performClick()
            awaitText("原题已备好，可同步本次作答。")
            onNodeWithTag("choice-0").assertExists()
            val before = e.f.serverEvents()
            // Model the mapping removal left by withdrawal, with a real authenticated endpoint.
            DriverManager.getConnection("jdbc:sqlite:${e.f.root.resolve("server/coach.sqlite")}").use { db ->
                db.createStatement().use { assertEquals(1, it.executeUpdate("DELETE FROM book_practice_mappings WHERE course='C'")) }
            }
            val response = practiceHttp(e.f.base, "/v1/courses/C/book-practice-pose", e.f.source().toString(), token = e.token)
            assertEquals(409, response.statusCode())
            onNodeWithTag("practice-sync-retry").performScrollTo().performClick()
            awaitText(unavailableNotice)
            onNodeWithTag("practice-sync-status").assertTextEquals(unavailableNotice)
            onAllNodesWithTag("choice-0").assertCountEquals(0)
            onNodeWithTag("practice-sync-retry").assertExists()
            assertEquals(before, e.f.serverEvents())
            assertTrue(e.commands().isEmpty())
            println("WINDOWS_ENTRY http=409 notice=$unavailableNotice oldChoice=hidden retry=available serverDelta=0")
        }
    }

    @Test fun missingPairingOrOwnerMappingKeepsLocalReadingAndAnswerWithoutAnyIncrement() {
        for (paired in listOf(false, true)) Entry().use { e ->
            if (paired) e.pair()
            e.connection().use { connection ->
                if (paired) connection.selectCourse(e.f.book.id, "unregistered-course")
                val controller = e.controller(connection)
                val before = e.f.serverEvents()
                runBlocking {
                    controller.load(); controller.read()
                    assertEquals(BookScreen.READER, controller.state.value.screen)
                    controller.learn()
                    controller.answer(PracticeAnswer(e.f.practice.answer, revealed = true, guessed = true))
                }
                assertEquals(if (paired) unavailableNotice else "同步未备好：缺少配对身份或已核准的书课关联。", controller.state.value.practiceSync)
                assertEquals(paired, controller.state.value.practiceUnavailable)
                e.assertZero(before)
            }
            println("WINDOWS_ENTRY paired=$paired ownerMapping=false outbox=0 serverDelta=0 localRead=true")
        }
    }

    @Test fun wrongDeviceOrRevocationCannotQueuePreviouslyReadyAnswer() {
        for (revoked in listOf(false, true)) Entry().use { e ->
            e.pair(if (revoked) "phone" else "tablet")
            e.connection().use { connection ->
                connection.selectCourse(e.f.book.id, "C")
                val controller = e.controller(connection)
                runBlocking { controller.load(); controller.learn() }
                if (revoked) {
                    assertEquals("原题已备好，可同步本次作答。", controller.state.value.practiceSync)
                    runBlocking { e.f.actor.execute { revokeDevice("phone") } }
                }
                val before = e.f.serverEvents()
                runBlocking { controller.answer(PracticeAnswer(e.f.practice.answer, revealed = true, guessed = true)) }
                e.assertZero(before)
                assertNull(e.credentials.load(e.f.base), "401 retires the same paired identity")
            }
            println("WINDOWS_ENTRY revoked=$revoked wrongDevice=${!revoked} outbox=0 answerDelta=0 credentialsRetired=true")
        }
    }

    @Test fun changedRawBookOrApprovedQuestionFingerprintStaysNotReady() {
        for (rawBook in listOf(true, false)) Entry().use { e ->
            e.pair()
            e.connection().use { connection ->
                connection.selectCourse(e.f.book.id, "C")
                if (rawBook) Files.writeString(e.library.directory.resolve("${e.f.book.id}.${e.f.book.format}"), "changed synthetic source")
                else DriverManager.getConnection("jdbc:sqlite:${e.f.root.resolve("server/coach.sqlite")}").use { db ->
                    db.createStatement().use { it.executeUpdate("UPDATE questions SET fingerprint='synthetic-tampered-fingerprint'") }
                }
                val before = e.f.serverEvents()
                val controller = e.controller(connection)
                runBlocking { controller.load(); controller.learn(); controller.answer(PracticeAnswer(e.f.practice.answer, revealed = true, guessed = true)) }
                assertEquals(if (rawBook) "同步未备好：缺少配对身份或已核准的书课关联。" else unavailableNotice, controller.state.value.practiceSync)
                assertEquals(!rawBook, controller.state.value.practiceUnavailable)
                e.assertZero(before)
            }
            println("WINDOWS_ENTRY changedRawBook=$rawBook changedQuestion=${!rawBook} outbox=0 serverDelta=0")
        }
    }

    private fun snapshot(e: Entry) {
        val target = Path.of("build/screens/windows-paired-practice/sqlite"); Files.createDirectories(target)
        val sources = mutableListOf(e.f.root.resolve("server/coach.sqlite"), e.library.directory.resolve("library.sqlite"))
        Files.walk(e.settings.clientDirectory).use { paths -> sources.addAll(paths.filter { it.fileName.toString() == "ledger.sqlite" }.toList()) }
        sources.forEachIndexed { index, source ->
            if (!Files.exists(source)) return@forEachIndexed
            DriverManager.getConnection("jdbc:sqlite:$source").use { db -> db.createStatement().use { s ->
                s.executeQuery("PRAGMA integrity_check").use { r -> assertTrue(r.next()); assertEquals("ok", r.getString(1)) }
                val out = target.resolve("$index-${source.fileName}")
                Files.deleteIfExists(out)
                s.execute("VACUUM INTO '${out.toAbsolutePath().toString().replace("'", "''")}'")
            } }
        }
    }
}
