package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.BookPracticeOutboxTest
import com.coach.desktop.books.QuestionMode
import com.coach.desktop.books.TextbookLibrary
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Properties
import kotlin.test.*

/** Uses the ordinary host and its real text fields, pairing HTTP and course-save button. No native input. */
@OptIn(ExperimentalTestApi::class)
class WindowsProductInputTest {
    private class Entry : AutoCloseable {
        val f = BookPracticeOutboxTest.F()
        val library = TextbookLibrary(f.root.resolve("library"))
        val settings = DesktopConnectionSettings(f.root.toRealPath(), environment = { key ->
            if (key == "COACH_CLIENT_DIR") f.root.resolve("typed-client").toString() else null
        }, mac = false, property = { key -> if (key == "jpackage.app-path") "/synthetic/Coach" else null })
        val routes = library.directory.resolve("practice-course-routes.properties")
        init {
            check(java.net.URI(f.base).port !in setOf(18791, 8791, 8790, 3790, 19540))
            library.save(f.book.copy(learning = true, questionMode = QuestionMode.CHOICE))
        }
        fun savedCourses() = Properties().apply { Files.newInputStream(routes).use { load(it) } }
        override fun close() = f.close()
    }

    private fun ComposeUiTest.awaitText(value: String) = waitUntil(timeoutMillis = 20_000) {
        onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
    }
    private fun ComposeUiTest.openSettings() {
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("open-settings").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("open-settings").performClick()
    }
    private fun ComposeUiTest.openPairing() {
        onNodeWithTag("book-practice-connect").performClick()
        awaitText("连接你的 Mac")
    }
    private fun ComposeUiTest.courseField() = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("course.id")))
    private fun SemanticsNodeInteraction.entered() =
        assertNotNull(fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)).text

    @Test fun ordinaryWindowFieldsPairAndPersistTheTypedCourse() = Entry().use { e ->
        val code = runBlocking { e.f.actor.execute { createPairingCode().code } }
        val before = e.f.serverEvents()
        assertNull(e.settings.serverUrl())
        assertNull(DesktopPairing(e.settings).load())
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbooksContent(e.library.directory, e.settings) { null } }
            openSettings(); openPairing()
            val address = onNodeWithTag("pairing.mac")
            address.performClick(); address.performTextInput(e.f.base)
            assertEquals(e.f.base, address.entered())
            val pairingCode = onNodeWithTag("pairing.code")
            pairingCode.performClick(); pairingCode.performTextInput(code)
            assertEquals("\u2022".repeat(6), pairingCode.entered())
            onNode(hasText("配对") and hasClickAction()).assertIsEnabled().performClick()
            awaitText("已连接，可以返回教材。")
            onNodeWithText("返回教材设置").performClick()
            courseField().performTextInput("C")
            assertEquals("C", courseField().entered())
            // The alias and legacy selector address one editable production field.
            assertEquals(courseField().fetchSemanticsNode().id, onNodeWithTag("book-practice-course").fetchSemanticsNode().id)
            onNodeWithTag("book-practice-save-course").performScrollTo().performClick()
            awaitText("已保存查找位置，开始学习时核对原题。")
            onNodeWithTag("close-settings").performClick()
        }
        val fresh = DesktopConnectionSettings(e.f.root.toRealPath(), environment = { key ->
            if (key == "COACH_CLIENT_DIR") e.settings.clientDirectory.toString() else null
        }, mac = false, property = { key -> if (key == "jpackage.app-path") "/synthetic/Coach" else null })
        val identity = assertNotNull(DesktopPairing(fresh).load())
        assertEquals(e.f.base, fresh.serverUrl())
        assertNotEquals("phone", identity.credentials.deviceId, "The UI must obtain its own pairing")
        assertEquals(listOf("C"), e.savedCourses().values.toList())
        val savedRoutes = Files.readAllBytes(e.routes)
        val savedCredentials = Files.readAllBytes(DesktopCredentials(fresh.clientDirectory).file)
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbooksContent(e.library.directory, fresh) { null } }
            openSettings()
            onNodeWithTag("book-practice-connect").performClick()
            awaitText("已连接，可以返回教材。")
            onNodeWithTag("pairing.code").assertDoesNotExist()
        }
        assertContentEquals(savedRoutes, Files.readAllBytes(e.routes))
        assertContentEquals(savedCredentials, Files.readAllBytes(DesktopCredentials(fresh.clientDirectory).file))
        assertEquals(before, e.f.serverEvents(), "Entering connection settings cannot pose or submit an answer")
        println("PRODUCT_INPUT pairing.mac=persisted pairing.code=consumed-by-pairing-http course.id=persisted reopenedIdentity=same nativeKeys=0")
    }

    @Test fun invalidPairingInputUsesTheRealCodeFilterAndCannotPair() = Entry().use { e ->
        val before = e.f.serverEvents()
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbooksContent(e.library.directory, e.settings) { null } }
            openSettings(); openPairing()
            onNodeWithTag("pairing.mac").performTextInput(e.f.base)
            onNodeWithTag("pairing.code").performTextInput("1x2\uD83D\uDE003")
            assertEquals("\u2022".repeat(3), onNodeWithTag("pairing.code").entered())
            onNode(hasText("配对") and hasClickAction()).assertIsNotEnabled()
        }
        assertNull(DesktopPairing(e.settings).load())
        assertFalse(Files.exists(e.routes))
        assertEquals(before, e.f.serverEvents())
    }

    @Test fun unpairedCourseInputPreservesUnicodeAndCannotInventPairingOrApproval() = Entry().use { e ->
        val before = e.f.serverEvents()
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbooksContent(e.library.directory, e.settings) { null } }
            openSettings()
            val course = "\u5408\u6210-\u8BFE\u7A0B-\uD83D\uDE00"
            courseField().performTextInput(course)
            assertEquals(course, courseField().entered())
            onNodeWithTag("book-practice-save-course").performScrollTo().performClick()
            awaitText("无法保存，请先连接 Mac，并检查课程标识。")
        }
        assertNull(e.settings.serverUrl())
        assertNull(DesktopPairing(e.settings).load())
        assertFalse(Files.exists(e.routes))
        assertEquals(before, e.f.serverEvents())
    }
}
