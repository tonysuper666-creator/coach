package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlinx.serialization.json.*
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WindowsSourceRegistrationTest {
    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error); waitForIdle()
    }
    private fun ComposeUiTest.capture(name: String) {
        val out = Path.of("build/screens/source-registration", name).toFile(); out.parentFile.mkdirs()
        assertTrue(ImageIO.write(onNodeWithTag("windows-host").captureToImage().toAwtImage(), "png", out))
    }
    private fun ComposeUiTest.openReview(c: TextbookController) {
        ready(c) { it.book != null }
        onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText("今天")).performClick()
        onNodeWithTag("host-today-review").assertTextContains("复习 1 题").performClick()
        ready(c) { it.screen == BookScreen.REVIEWS }
        onNodeWithTag("local-review-item-0").performScrollTo().performClick()
        ready(c) { it.screen == BookScreen.REVIEWING }
    }

    @Test fun actualReviewEntryImportsMaterialReceiptAnswersAndRestoresThroughDefaultHost() {
        val f = SourceReceiptFixture()
        val file = f.file()
        var picked = 0
        val c = TextbookController(f.library)
        lateinit var attempt: String
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbookHost(c) { picked++; file } }
            openReview(c)
            onNodeWithTag("review-variant-state").performScrollTo().assertTextEquals("新变式未备好：缺少明确关联、有效版本或可查来源。")
            onNodeWithTag("review-start-variant").assertDoesNotExist()
            capture("01-no-source.png")
            onNodeWithTag("review-import-source").performScrollTo().performClick()
            ready(c) { it.review?.canStartVariant == true }
            assertEquals(1, picked)
            f.snapshot("registered.sqlite")
            onNodeWithTag("review-start-variant").performScrollTo().performClick()
            ready(c) { it.review?.variant != null }
            val variant = c.state.value.review!!.variant!!
            attempt = variant.allocation.attemptId
            assertEquals(f.input.origin, variant.origin)
            assertEquals("variant-b", variant.allocation.question.id)
            onNodeWithTag("guess-mark").performScrollTo().performClick()
            ready(c) { it.answer.guessed }
            onNodeWithTag("choice-1").performScrollTo().performClick()
            ready(c) { it.answer.revealed && it.answer.guessed }
            waitUntil(timeoutMillis = 20_000) { f.review().answer.revealed }
            assertFalse(f.review().completed)
            capture("02-imported-source-answered.png")
            onNodeWithTag("review-view-original").performScrollTo().performClick()
            onNodeWithTag("review-original-prompt").performScrollTo().assertTextEquals(f.data.original.prompt)
            onNodeWithText("原答：${f.data.wrong}").performScrollTo().assertIsDisplayed()
            capture("03-original.png")
        }
        f.snapshot("answered.sqlite")
        val reopened = TextbookController(f.library)
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbookHost(reopened) { error("Reopen must not ask for source again") } }
            ready(reopened) { it.screen == BookScreen.REVIEWING }
            assertEquals(attempt, reopened.state.value.review!!.variant!!.allocation.attemptId)
            assertEquals(PracticeAnswer("Supported", true, guessed = true), reopened.state.value.answer)
            onNodeWithText("你选择：Supported").performScrollTo().assertExists()
            capture("04-reopened.png")
        }
        assertEquals(1, f.library.reviews().size); assertEquals(1, f.data.rows())
        assertEquals(f.data.wrong, f.review().originalAnswer); assertEquals("ok", f.data.integrity())
        f.snapshot("reopened.sqlite")
    }

    @Test fun unrelatedFileShowsErrorAndCancelKeepsNotReadyWithoutCreatingSupply() {
        val f = SourceReceiptFixture()
        val wrong = f.file(JsonObject(f.receipt() + ("bookSha256" to JsonPrimitive("different-book"))))
        var choose: Path? = wrong
        var picked = 0
        val c = TextbookController(f.library)
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbookHost(c) { picked++; choose } }
            openReview(c)
            onNodeWithTag("review-import-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { c.state.value.error != null && !c.state.value.busy }
            onNodeWithTag("book-error").assertTextEquals("来源登记未保存：文件缺少完整身份或与当前题不一致。")
            onNodeWithTag("review-start-variant").assertDoesNotExist()
            onNodeWithTag("review-variant-state").performScrollTo().assertTextEquals("新变式未备好：缺少明确关联、有效版本或可查来源。")
            capture("05-unrelated-rejected.png")
            choose = null
            onNodeWithTag("review-import-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { picked == 2 }; waitForIdle()
            assertFalse(f.review().canStartVariant); assertEquals(0, f.data.rows())
            assertEquals(f.data.wrong, f.review().originalAnswer)
        }
        assertEquals("ok", f.data.integrity())
    }
}
