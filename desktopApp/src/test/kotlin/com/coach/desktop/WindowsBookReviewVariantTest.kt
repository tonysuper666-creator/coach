package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WindowsBookReviewVariantTest {
    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }
    private fun ComposeUiTest.capture(name: String) {
        val dir = Path.of("build/screens/book-variants")
        Files.createDirectories(dir)
        assertTrue(ImageIO.write(onNodeWithTag("windows-host").captureToImage().toAwtImage(), "png", dir.resolve(name).toFile()))
    }
    @Test fun realHostConsumesLinkedVariantViewsOriginalAndRestoresGuessedAttempt() {
        val f = VariantFixture()
        val c = TextbookController(f.library)
        lateinit var attempt: String
        val evidence = Path.of("build/screens/book-variants")
        Files.createDirectories(evidence)
        Files.copy(f.library.directory.resolve("library.sqlite"), evidence.resolve("host-before.sqlite"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbookHost(c) { null } }
            ready(c) { it.book != null }
            onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText("今天")).performClick()
            onNodeWithTag("host-today-review").assertTextContains("复习 1 题").performClick()
            ready(c) { it.screen == BookScreen.REVIEWS }
            onNodeWithTag("local-review-item-0").performScrollTo().performClick()
            ready(c) { it.screen == BookScreen.REVIEWING }
            onNodeWithText(f.original.prompt).assertExists()
            onNodeWithTag("review-start-variant").performScrollTo().performClick()
            ready(c) { it.review?.variant != null }
            attempt = c.state.value.review!!.variant!!.allocation.attemptId
            assertEquals("variant-a", c.state.value.review!!.variant!!.allocation.question.id)
            onNodeWithText("Synthetic variant-a: choose the supported statement").assertExists()
            onNodeWithTag("guess-mark").performScrollTo().performClick()
            ready(c) { it.answer.guessed }
            onNodeWithTag("choice-1").performScrollTo().performClick()
            ready(c) { it.answer.revealed && it.answer.guessed }
            waitUntil(timeoutMillis = 20_000) { f.review().answer.revealed }
            assertFalse(f.review().completed)
            capture("01-variant-guessed.png")
            onNodeWithTag("review-view-original").performScrollTo().performClick()
            onNodeWithTag("review-original-prompt").performScrollTo().assertTextEquals(f.original.prompt)
            onNodeWithText("原答：${f.wrong}").assertExists()
            capture("02-original-and-answer.png")
        }
        val restored = TextbookController(f.reopen())
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbookHost(restored) { null } }
            ready(restored) { it.screen == BookScreen.REVIEWING }
            assertEquals(attempt, restored.state.value.review!!.variant!!.allocation.attemptId)
            assertEquals(PracticeAnswer("Supported", true, guessed = true), restored.state.value.answer)
            onNodeWithText("你选择：Supported").performScrollTo().assertExists()
            capture("03-new-host-restored.png")
        }
        assertEquals(1, f.rows()); assertEquals(1, f.reopen().reviews().size)
        assertEquals("ok", f.integrity())
        Files.copy(f.library.directory.resolve("library.sqlite"), Path.of("build/screens/book-variants/host-reopened.sqlite"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    @Test fun withdrawnAssociationShowsOriginalReadOnlyInNewHost() {
        val f = VariantFixture()
        kotlinx.coroutines.runBlocking {
            val c = TextbookController(f.library)
            c.load(); c.openReview(f.review()); c.startReviewVariant()
        }
        f.input = null
        val reopened = TextbookController(f.reopen())
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbookHost(reopened) { null } }
            ready(reopened) { it.screen == BookScreen.REVIEWING }
            onNodeWithTag("review-variant-state").performScrollTo()
                .assertTextEquals("新变式未备好：缺少明确关联、有效版本或可查来源。")
            onNodeWithTag("review-start-variant").assertDoesNotExist()
            onNodeWithTag("choice-0").assertDoesNotExist()
            onNodeWithText(f.original.prompt).assertExists()
            onNodeWithText("原答：${f.wrong}").assertExists()
            capture("04-withdrawn-read-only.png")
        }
        assertEquals(1, f.rows()); assertEquals("ok", f.integrity())
    }

}
