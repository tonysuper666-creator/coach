package com.coach.desktop.books

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
internal fun SkikoComposeUiTest.toggleReaderMode() {
    val scrolling = onAllNodesWithTag("book-scroll-read").fetchSemanticsNodes().isNotEmpty()
    if (onAllNodesWithTag("immersive-more-menu").fetchSemanticsNodes().isEmpty()) {
        onNodeWithTag("reader-page").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("reader-page").performKeyInput { pressKey(Key.Tab) }
    }
    openReaderTypeSettings()
    val option = if (scrolling) "text-mode-paged" else "text-mode-scroll"
    onNodeWithTag(option).performSemanticsAction(SemanticsActions.OnClick)
    closeReaderOverflow()
    mainClock.advanceTimeBy(850)
    waitForIdle()
}

@OptIn(ExperimentalTestApi::class)
class ReadingSpotQa436 {
    data class Sentence(val id: String, val y: Float, val side: String, val rootX: Float = 0f, val rootY: Float = 0f)

    private fun SkikoComposeUiTest.sentences(): List<Sentence> {
        val viewport = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
        return listOf("reader-text", "reader-text-right").flatMap { side ->
            onAllNodesWithTag(side).fetchSemanticsNodes().flatMap { node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
                val layout = results.single()
                Regex("句[0-9]{4}。").findAll(layout.layoutInput.text.text).mapNotNull { match ->
                    val line = layout.getLineForOffset(match.range.first)
                    val y = node.positionInRoot.y + layout.getLineTop(line)
                    val bottom = node.positionInRoot.y + layout.getLineBottom(line)
                    if (y >= viewport.top && bottom <= viewport.bottom) Sentence(match.value, y - viewport.top, side,
                        node.positionInRoot.x + node.size.width / 2f, (y + bottom) / 2f) else null
                }.toList()
            }
        }
    }

    private fun withBook(width: Float, check: SkikoComposeUiTest.() -> Unit) {
        val dir = Files.createTempDirectory("qa-reader-436")
        try {
            val library = TextbookLibrary(dir.resolve("library"))
            val input = dir.resolve("numbered.md")
            Files.writeString(input, "# 阅读位置\n\n" + (1..520).joinToString("") { "句" + it.toString().padStart(4, '0') + "。读者找到这句话，再接着读下一句。" })
            val book = library.importBook(input)
            val controller = TextbookController(library)

            runSkikoComposeUiTest(size = Size(width, 1440f), density = Density(1.5f)) {
                setContent { WindowsTextbookHost(controller) { null } }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.initialized && !controller.state.value.busy }
                onNodeWithTag("home-read").performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty() }
                check()
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    private fun SkikoComposeUiTest.report(stage: String, seen: List<Sentence>, id: String) {
        println("QA436 " + stage + " visible=" + seen.firstOrNull()?.id + ".." + seen.lastOrNull()?.id +
            " target=" + id + " yFromViewportTopPx=" + seen.find { it.id == id }?.y)
    }

    @Test fun rightHandSentenceSurvivesFirstSwitch() = withBook(2560f) {
        onNodeWithTag("next-page").performClick()
        mainClock.advanceTimeBy(850); waitForIdle()
        val before = sentences()
        val right = before.filter { it.side == "reader-text-right" }
        assertTrue(right.size >= 3, "spread fixture should have a visible right page")
        val clicked = right[right.size / 2]
        val target = clicked.id
        // architect ruling: a click inside the right page makes it the reading page
        val pageBounds = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("reader-page").performTouchInput { click(androidx.compose.ui.geometry.Offset(clicked.rootX - pageBounds.left, clicked.rootY - pageBounds.top)) }
        mainClock.advanceTimeBy(300); waitForIdle()
        report("spread-before", sentences(), target)
        toggleReaderMode()
        onNodeWithTag("book-scroll-read").assertExists()
        val scrolling = sentences()
        report("first-scroll", scrolling, target)
        toggleReaderMode()
        val restored = sentences()
        report("back-to-spread", restored, target)
        assertTrue(scrolling.any { it.id == target } && restored.any { it.id == target }, "same sentence must remain visible on both switches")
    }

    private fun SkikoComposeUiTest.dragSelect(from: Sentence, to: Sentence) {
        val b = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("reader-page").performMouseInput {
            moveTo(androidx.compose.ui.geometry.Offset(from.rootX - b.left, from.rootY - b.top))
            press()
            moveTo(androidx.compose.ui.geometry.Offset((from.rootX + to.rootX) / 2f - b.left, (from.rootY + to.rootY) / 2f - b.top))
            moveTo(androidx.compose.ui.geometry.Offset(to.rootX - b.left, to.rootY - b.top))
            release()
        }
        mainClock.advanceTimeBy(300); waitForIdle()
    }

    @Test fun selectingOnTheRightPageKeepsItsFirstSentenceVisibleAcrossSwitches() = withBook(2560f) {
        onNodeWithTag("next-page").performClick()
        mainClock.advanceTimeBy(850); waitForIdle()
        val right = sentences().filter { it.side == "reader-text-right" }
        assertTrue(right.size >= 6)
        val first = right[right.size / 3]
        val last = right[right.size * 2 / 3]
        dragSelect(first, last)
        toggleReaderMode()
        onNodeWithTag("book-scroll-read").assertExists()
        val inScroll = sentences().map { it.id }
        report("select-first-scroll", sentences(), first.id)
        assertTrue(first.id in inScroll, "first selected sentence ${first.id} must be visible in scroll: $inScroll")
        toggleReaderMode()
        assertTrue(first.id in sentences().map { it.id }, "and back in the spread")
    }

    @Test fun selectingFromTheLeftPageIntoTheRightKeepsTheLeftFirstSentence() = withBook(2560f) {
        onNodeWithTag("next-page").performClick()
        mainClock.advanceTimeBy(850); waitForIdle()
        val all = sentences()
        val left = all.filter { it.side == "reader-text" }
        val right = all.filter { it.side == "reader-text-right" }
        val first = left[left.size / 2]
        dragSelect(first, right[right.size / 3])
        toggleReaderMode()
        assertTrue(first.id in sentences().map { it.id }, "left-page first sentence ${first.id} must be visible in scroll")
        toggleReaderMode()
        assertTrue(first.id in sentences().map { it.id }, "and back in the spread")
    }

    @Test fun leftPageSentencesSurviveFirstSwitchWithoutAnyClick() = withBook(2560f) {
        onNodeWithTag("next-page").performClick()
        mainClock.advanceTimeBy(850); waitForIdle()
        val before = sentences()
        val left = before.filter { it.side == "reader-text" }
        assertTrue(left.size >= 3)
        val first = left.first().id
        val later = left[left.size / 2].id
        toggleReaderMode()
        onNodeWithTag("book-scroll-read").assertExists()
        val scrolling = sentences().map { it.id }
        assertTrue(first in scrolling && later in scrolling, "left-page sentences $first and $later must be visible in scroll: $scrolling")
        toggleReaderMode()
        val back = sentences().map { it.id }
        assertTrue(first in back && later in back, "and again in the spread: $back")
    }

    @Test fun partialScrollRetainsActualReadingLineAcrossRoundTrip() = withBook(1440f) {
        val paged = sentences()
        assertTrue(paged.size >= 4 && paged.none { it.side == "reader-text-right" })
        toggleReaderMode()
        val initial = sentences()
        val viewport = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
        onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy) and hasAnyAncestor(hasTestTag("book-scroll-read")))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, viewport.height * 0.74f) }
        mainClock.advanceTimeBy(1000); waitForIdle()
        val afterScroll = sentences()
        val target = afterScroll.minByOrNull { abs(it.y - viewport.height * 0.56f) }!!
        assertTrue(afterScroll.first().id != initial.first().id, "input must move the viewport")
        report("single-partially-scrolled", afterScroll, target.id)
        toggleReaderMode()
        val back = sentences()
        report("back-to-single", back, target.id)
        toggleReaderMode()
        val reopened = sentences()
        report("scroll-again", reopened, target.id)
        assertTrue(back.any { it.id == target.id } && reopened.any { it.id == target.id }, "same sentence must remain visible")
        assertEquals(target.y, reopened.single { it.id == target.id }.y, 55f, "reading line moved by more than one line")
    }
}
