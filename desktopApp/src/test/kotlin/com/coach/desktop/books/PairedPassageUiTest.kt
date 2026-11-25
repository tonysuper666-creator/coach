package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.ui.theme.CoachTheme
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** Synthetic text only: consent and source navigation, not real-book acceptance. */
@OptIn(ExperimentalTestApi::class)
class PairedPassageUiTest {
    @Test fun lightConsentAndCitedAnswer() = exercise(false)
    @Test fun darkConsentAndCitedAnswer() = exercise(true)

    private fun exercise(dark: Boolean) {
        val root = Files.createTempDirectory("paired-passage-ui-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val block = book.lessons.first().blocks.first { !it.heading }
        var calls = 0
        ReadingSettings(library.directory).chooseAiExplain(false)
        val paired = PairedBookLearningSupport(library) { sent, question ->
            calls++
            assertEquals(block, sent)
            assertEquals("Why does the plant need light?", question)
            ExplainResult.Done(Explanation("Light supplies energy for the plant.【S1】", listOf(Citation("S1", sent.id, sent.page)), 1))
        }
        // Before and after use identical synthetic book, question and V7 theme.
        for ((name, support) in listOf("before" to UnconnectedBookLearningSupport, "after" to paired)) {
            runSkikoComposeUiTest(size = Size(1100f, 850f), density = Density(1f)) {
                setContent { CoachTheme(darkTheme = dark) { BookPassageAsk(book, book.pages.first(), support) {} } }
                onNodeWithTag("ask-paragraph-0").performClick()
                onNodeWithTag("book-passage-question").performTextInput("Why does the plant need light?")
                onNodeWithTag("book-passage-send").performScrollTo().performClick()
                if (name == "after") {
                    onNodeWithTag("ask-consent-deny").performClick()
                    assertEquals(0, calls)
                    onNodeWithTag("book-passage-send").performScrollTo().performClick()
                    onNodeWithTag("ask-consent-allow").performClick()
                    waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-reply").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(1, calls)
                } else {
                    waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-unavailable").fetchSemanticsNodes().isNotEmpty() }
                }
                val dir = Path.of("build/screens/paired-passage")
                Files.createDirectories(dir)
                assertTrue(ImageIO.write(onNodeWithTag("book-passage-dialog").captureToImage().toAwtImage(),
                    "png", dir.resolve("$name-${if (dark) "dark" else "light"}.png").toFile()))
                if (name == "after") {
                    onNodeWithTag("book-passage-citation").performScrollTo().performClick()
                    onNodeWithTag("book-passage-source").assertTextEquals(block.text)
                    onNodeWithText("回到问题").performClick()
                    onNodeWithTag("book-selected-passage").assertTextEquals(block.text)
                    assertFalse(paired.consentRequired(book.id))
                }
            }
        }
    }
}
