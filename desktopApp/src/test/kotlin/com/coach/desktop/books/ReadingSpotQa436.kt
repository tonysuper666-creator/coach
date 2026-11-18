package com.coach.desktop.books

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.coach.ui.CoachTheme
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ReadingSpotQa436 {
    data class Sentence(val id: String, val y: Float, val side: String)

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
                    if (y >= viewport.top && bottom <= viewport.bottom) Sentence(match.value, y - viewport.top, side) else null
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
            var ui by mutableStateOf(LibraryUi(book = book, screen = BookScreen.READER, page = book.pages.first().number))
            runSkikoComposeUiTest(size = Size(width, 1440f), density = Density(1.5f)) {
                val focus = FocusRequester()
                setContent { CoachTheme(false) {
                    TextbookReader(ui, library, UnconnectedBookLearningSupport, null, focus,
                        onNoteSession = {}, onPage = { ui = ui.copy(page = it) }, onBack = {}, onLearn = {})
                } }
                waitForIdle()
                check()
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    private fun SkikoComposeUiTest.toggle() {
        onNodeWithTag("read-mode").performClick()
        mainClock.advanceTimeBy(850)
        waitForIdle()
    }

    private fun SkikoComposeUiTest.report(stage: String, seen: List<Sentence>, id: String) {
        println("QA436 " + stage + " visible=" + seen.firstOrNull()?.id + ".." + seen.lastOrNull()?.id +
            " target=" + id + " yFromViewportTopPx=" + seen.find { it.id == id }?.y)
    }

    @Test fun rightHandSentenceSurvivesFirstSwitch() = withBook(2560f) {
        onNodeWithText("下一页").performClick()
        mainClock.advanceTimeBy(850); waitForIdle()
        val before = sentences()
        val right = before.filter { it.side == "reader-text-right" }
        assertTrue(right.size >= 3, "spread fixture should have a visible right page")
        val target = right[right.size / 2].id
        report("spread-before", before, target)
        toggle()
        onNodeWithTag("book-scroll-read").assertExists()
        val scrolling = sentences()
        report("first-scroll", scrolling, target)
        toggle()
        val restored = sentences()
        report("back-to-spread", restored, target)
        assertTrue(scrolling.any { it.id == target } && restored.any { it.id == target }, "same sentence must remain visible on both switches")
    }

    @Test fun partialScrollRetainsActualReadingLineAcrossRoundTrip() = withBook(1440f) {
        val paged = sentences()
        assertTrue(paged.size >= 4 && paged.none { it.side == "reader-text-right" })
        toggle()
        val initial = sentences()
        val viewport = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
        onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy) and hasAnyAncestor(hasTestTag("book-scroll-read")))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, viewport.height * 0.74f) }
        mainClock.advanceTimeBy(1000); waitForIdle()
        val afterScroll = sentences()
        val target = afterScroll.minByOrNull { abs(it.y - viewport.height * 0.56f) }!!
        assertTrue(afterScroll.first().id != initial.first().id, "input must move the viewport")
        report("single-partially-scrolled", afterScroll, target.id)
        toggle()
        val back = sentences()
        report("back-to-single", back, target.id)
        toggle()
        val reopened = sentences()
        report("scroll-again", reopened, target.id)
        assertTrue(back.any { it.id == target.id } && reopened.any { it.id == target.id }, "same sentence must remain visible")
        assertEquals(target.y, reopened.single { it.id == target.id }.y, 55f, "reading line moved by more than one line")
    }
}
