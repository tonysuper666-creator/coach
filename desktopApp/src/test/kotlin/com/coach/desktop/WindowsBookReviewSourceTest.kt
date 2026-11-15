package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WindowsBookReviewSourceTest {
    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error); waitForIdle()
    }
    private fun ComposeUiTest.capture(name: String) {
        val file = Path.of("build/screens/persisted-source", name).toFile()
        file.parentFile.mkdirs()
        assertTrue(ImageIO.write(onNodeWithTag("windows-host").captureToImage().toAwtImage(), "png", file))
    }

    @Test fun defaultHostConsumesRegisteredSourceThenNewHostRestoresAndWithdrawalClosesInput() {
        val f = PersistedSourceFixture()
        assertFalse(f.review().canStartVariant)
        f.publish(input = f.input.copy(usedQuestions = setOf(f.input.offers.first().candidate.question)))
        val c = TextbookController(f.library)
        lateinit var attempt: String
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbookHost(c) { null } }
            ready(c) { it.book != null }
            onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText("今天")).performClick()
            onNodeWithTag("host-today-review").assertTextContains("复习 1 题").performClick()
            ready(c) { it.screen == BookScreen.REVIEWS }
            onNodeWithTag("local-review-item-0").performScrollTo().performClick()
            ready(c) { it.screen == BookScreen.REVIEWING }
            onNodeWithTag("review-start-variant").performScrollTo().performClick()
            ready(c) { it.review?.variant != null }
            attempt = c.state.value.review!!.variant!!.allocation.attemptId
            assertEquals("variant-b", c.state.value.review!!.variant!!.allocation.question.id)
            onNodeWithTag("guess-mark").performScrollTo().performClick()
            ready(c) { it.answer.guessed }
            onNodeWithTag("choice-1").performScrollTo().performClick()
            ready(c) { it.answer.revealed && it.answer.guessed }
            waitUntil(timeoutMillis = 20_000) { f.review().answer.revealed }
            assertFalse(f.review().completed)
            capture("01-default-source-guessed.png")
            onNodeWithTag("review-view-original").performScrollTo().performClick()
            onNodeWithTag("review-original-prompt").performScrollTo().assertTextEquals(f.data.original.prompt)
            onNodeWithText("原答：${f.data.wrong}").assertExists()
            capture("02-original-from-source.png")
        }
        f.snapshot("host-answered.sqlite")
        val restored = TextbookController(f.library)
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbookHost(restored) { null } }
            ready(restored) { it.screen == BookScreen.REVIEWING }
            assertEquals(attempt, restored.state.value.review!!.variant!!.allocation.attemptId)
            assertEquals(PracticeAnswer("Supported", true, guessed = true), restored.state.value.answer)
            onNodeWithText("你选择：Supported").performScrollTo().assertExists()
            capture("03-default-new-host.png")
        }
        f.publish(2, null)
        val withdrawn = TextbookController(f.library)
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbookHost(withdrawn) { null } }
            ready(withdrawn) { it.screen == BookScreen.REVIEWING }
            onNodeWithTag("review-variant-state").performScrollTo()
                .assertTextEquals("新变式未备好：缺少明确关联、有效版本或可查来源。")
            onNodeWithTag("review-start-variant").assertDoesNotExist()
            onNodeWithTag("choice-0").assertDoesNotExist()
            onNodeWithText("原答：${f.data.wrong}").assertExists()
            capture("04-source-withdrawn.png")
        }
        assertEquals(1, f.data.rows()); assertEquals("ok", f.data.integrity())
        f.snapshot("host-withdrawn.sqlite")
    }
}
