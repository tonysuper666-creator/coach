package com.coach.desktop.books

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import com.coach.ui.theme.CoachColors
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Reproducible off-screen evidence using only the process theme override and synthetic content. */
@OptIn(ExperimentalTestApi::class)
class BookConsentVisualCaptureTest {
    private fun SkikoComposeUiTest.awaitEnabled(tag: String) {
        waitUntil(timeoutMillis = 20_000) {
            onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun SkikoComposeUiTest.capture(directory: File, name: String, theme: String, tag: String = "windows-host") {
        if (tag == "windows-host") {
            val expected = (if (theme == "dark") CoachColors.dark() else CoachColors.light()).bgCanvas.toArgb()
            val host = onNodeWithTag("windows-host").captureToImage().toPixelMap()[5, 5].toArgb()
            val workspace = onNodeWithTag("windows-textbooks").captureToImage().toPixelMap()[5, 5].toArgb()
            assertEquals(expected, host, "$theme host canvas")
            assertEquals(expected, workspace, "$theme workspace must inherit the host palette")
        }
        directory.mkdirs()
        val image = onNodeWithTag(tag).captureToImage().toAwtImage()
        check(ImageIO.write(image, "png", File(directory, "$name.png")))
        println("CAPTURED theme=$theme state=$name tag=$tag canvas=${image.width}x${image.height}")
    }

    private class Fixture {
        val root = Files.createTempDirectory("synthetic-consent-visual-")
        val source = Files.writeString(root.resolve("Synthetic plants.txt"),
            "Photosynthesis is the process by which plants use light to make sugars.\n\nRoots absorb water and minerals from soil.")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(source)
        val calls = AtomicInteger()
        val support = ConsentedBookLearningSupport(library, OwnedBookConsentStore(library), OwnedBookCliTransport {
            calls.incrementAndGet()
            """{"answer":"Light supplies energy for making stored food.","citations":[0]}"""
        })
        val controller = TextbookController(library)
        init {
            library.save(book.copy(learning = true, questionMode = QuestionMode.SHORT_ANSWER))
            runBlocking { controller.load(); controller.learn(LearningPath.READ_AND_LEARN) }
        }
    }

    private fun captureTheme(theme: String) {
        val previous = System.getProperty("coach.windows.theme")
        System.setProperty("coach.windows.theme", theme)
        try {
            val f = Fixture()
            val directory = File("build/screens/book-consent-visual/$theme")
            runSkikoComposeUiTest(size = Size(1100f, 1000f), density = Density(1f)) {
                setContent {
                    val systemDark = isSystemInDarkTheme()
                    LaunchedEffect(Unit) { println("HOST_SYSTEM_DARK=$systemDark PROCESS_THEME=$theme") }
                    WindowsTextbookHost(f.controller, support = f.support) { null }
                }
                awaitEnabled("book-consent-decline")
                waitForIdle()
                capture(directory, "01-notice", theme)
                assertEquals(0, f.calls.get(), "Opening the notice is not consent")

                onNodeWithTag("book-consent-decline").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000) { !f.support.consentOffer(f.book.id).offerAutomatically }
                onNodeWithTag("book-consent-notice").assertDoesNotExist()
                onNodeWithTag("open-lesson-source").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000) { f.controller.state.value.screen == BookScreen.READER && !f.controller.state.value.busy }
                onNodeWithText("返回学习").performClick()
                waitUntil(timeoutMillis = 20_000) { f.controller.state.value.screen == BookScreen.LEARNING && !f.controller.state.value.busy }
                onNodeWithTag("book-consent-notice").assertDoesNotExist()
                awaitEnabled("book-consent-reopen")
                onNodeWithTag("book-consent-reopen").performScrollTo()
                waitForIdle()
                assertEquals(0, f.calls.get())
                capture(directory, "02-declined-remembered", theme)

                onNodeWithTag("book-consent-reopen").performClick()
                awaitEnabled("book-consent-grant")
                onNodeWithTag("book-consent-grant").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("book-source-0").assertExists()
                waitForIdle()
                assertEquals(1, f.calls.get())
                capture(directory, "03-granted-entry", theme)

                onNodeWithTag("open-lesson-source").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000) { f.controller.state.value.screen == BookScreen.READER && !f.controller.state.value.busy }
                onNodeWithTag("ask-book-passage").performClick()
                onNodeWithTag("ask-paragraph-0").performScrollTo().performClick()
                onNodeWithTag("book-passage-question").performScrollTo().performTextInput("What does light supply?")
                onNodeWithTag("book-passage-send").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-answer").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("book-consent-revoke").performScrollTo().performClick()
                awaitEnabled("book-consent-reopen")
                onNodeWithTag("book-passage-answer").assertDoesNotExist()
                onNodeWithTag("book-passage-send").assertIsNotEnabled()
                waitForIdle()
                assertEquals(2, f.calls.get())
                capture(directory, "04-withdrawn-cleared", theme, "book-passage-ask")
            }
        } finally {
            if (previous == null) System.clearProperty("coach.windows.theme") else System.setProperty("coach.windows.theme", previous)
        }
    }

    @Test fun lightCaptureSet() = captureTheme("light")
    @Test fun darkCaptureSet() = captureTheme("dark")

    @Test fun passageDialogPaletteProbe() {
        val f = Fixture()
        runSkikoComposeUiTest(size = Size(1100f, 1000f), density = Density(1f)) {
            setContent { com.coach.ui.theme.CoachTheme(darkTheme = false) { BookPassageAsk(f.book, f.book.pages.first(), f.support) {} } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-ask").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            val pixel = onNodeWithTag("book-passage-ask").captureToImage().toPixelMap()[2, 2]
            println("DIALOG_PROBE light_composition_surface=${pixel.red},${pixel.green},${pixel.blue},${pixel.alpha}")
        }
    }
}
