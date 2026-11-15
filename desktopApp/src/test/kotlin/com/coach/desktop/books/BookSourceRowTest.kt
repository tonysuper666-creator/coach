package com.coach.desktop.books

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class BookSourceRowTest {
    private val root = Files.createTempDirectory("book-source-row-")
    private val library = TextbookLibrary(root.resolve("library"))
    private val a = library.importBook(syntheticTextbook(root.resolve("a")))
    private val b = library.importBook(otherPdf(root.resolve("b")))
    private val resolver = BookSourceResolver(library)
    private fun passage(book: Textbook, page: Int): BookPassage {
        val blk = book.lessons.flatMap { it.blocks }.last { !it.heading && it.page == page }
        return BookPassage.from(book, blk)
    }
    private val p2 = passage(a, 2)
    private val p3 = passage(a, 3)

    private fun otherPdf(dir: Path): Path {
        Files.createDirectories(dir)
        val f = dir.resolve("other.pdf")
        PDDocument().use { pdf ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for ((h, body) in listOf("Chapter 1 Rocks" to "Granite is an igneous rock formed from cooled magma deep below ground.",
                "Chapter 2 Water" to "Rivers carry sediment toward the sea where it slowly settles in layers.")) {
                val page = PDPage(); pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    out.beginText(); out.setFont(font, 18f); out.newLineAtOffset(50f, 710f); out.showText(h)
                    out.newLineAtOffset(0f, -48f); out.setFont(font, 11f); out.showText(body); out.endText()
                }
            }
            pdf.save(f.toFile())
        }
        return f
    }

    @Test fun statesAndLabels() {
        assertEquals(BookSourceState.Ready, bookSourceState(p2, a, resolver))
        assertIs<BookSourceState.Stale>(bookSourceState(p2.copy(page = 99), a, resolver))
        assertIs<BookSourceState.Stale>(bookSourceState(p2.copy(text = p2.text.dropLast(1) + "X"), a, resolver))
        // Valid passage of book B shown on book A's screen is NOT openable.
        val pb = passage(b, 2)
        assertEquals(BookSourceState.Ready, bookSourceState(pb, b, resolver))
        assertIs<BookSourceState.WrongBook>(bookSourceState(pb, a, resolver))
        val label = bookSourceLabel(p2)
        assertTrue(label.startsWith("第 2 页 · “") && label.contains("…"), label)
        assertFalse(Regex("[a-f0-9]{64}").containsMatchIn(label))
        // Surrogate pair at the cut is never split.
        val emoji = p2.copy(text = "a".repeat(15) + "\uD83D\uDE00" + "tail")
        assertTrue(bookSourceLabel(emoji).contains("\uD83D\uDE00…"))
    }

    @Test fun clickOpensExactTargetStaleAndWrongBookAreReadOnly() = runComposeUiTest {
        val opened = mutableListOf<Pair<Int, BookSourceTarget>>()
        val focus = List(4) { FocusRequester() }
        val sources = listOf(p2, p3.copy(text = p3.text.dropLast(1) + "X"), passage(b, 2), p3)
        setContent { BookSourceRow(sources, a, resolver, { focus[it] }, { i, t -> opened += i to t }) }
        settled(0, 1, 2, 3)
        onNodeWithTag("book-source-0").assertTextEquals(bookSourceLabel(p2)).assertHasClickAction()
        onNodeWithTag("book-source-1").assertDoesNotExist()
        onNodeWithTag("book-source-stale-1").assertExists()
        onNodeWithTag("book-source-wrong-2").assertExists()
        onNodeWithText("书的内容变了，这条出处已过期。").assertExists()
        onNodeWithText("这条出处对不上当前这本书。").assertExists()
        // Tab order: only the openable buttons are focusable.
        assertEquals(2, onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        // No SHA / offsets on screen.
        val texts = onAllNodes(hasText("", substring = true)).fetchSemanticsNodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString("|") { it.text }
        assertFalse(Regex("[a-f0-9]{64}").containsMatchIn(texts)); assertFalse(texts.contains("字符 "))
        onNodeWithTag("book-source-0").performClick()
        waitUntil(timeoutMillis = 5000) { opened.isNotEmpty() }
        val (i, t) = opened.single()
        assertEquals(0, i); assertEquals(a.id, t.book); assertEquals(2, t.pdfPage)
        assertEquals(p2.start, t.start); assertEquals(p2.end, t.end); assertEquals(p2.text, t.quote)
        // Keyboard: focus then Enter opens the second button's target.
        runOnUiThread { focus[3].requestFocus() }
        onNodeWithTag("book-source-3").assertIsFocused().performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        waitUntil(timeoutMillis = 5000) { opened.size == 2 }
        assertEquals(3, opened.last().first); assertEquals(3, opened.last().second.pdfPage)
    }

    @Test fun fileChangedAfterCompositionRefusesClickAndTurnsStale() = runComposeUiTest {
        var opened = 0; val refused = mutableListOf<Int>()
        setContent { BookSourceRow(listOf(p2), a, resolver, { FocusRequester() }, { _, _ -> opened++ }, onRefused = { refused += it }) }
        settled(0)
        onNodeWithTag("book-source-0").assertExists()
        Files.write(library.directory.resolve("${a.id}.${a.format}"), byteArrayOf(9, 9))
        onNodeWithTag("book-source-0").performClick()
        waitUntil(timeoutMillis = 5000) { refused.isNotEmpty() }
        assertEquals(0, opened); assertEquals(listOf(0), refused)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag("book-source-stale-0").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("book-source-stale-0").assertExists()
        onNodeWithTag("book-source-0").assertDoesNotExist()
    }

    @Test fun emptySourcesShowOneHintAndNoButton() = runComposeUiTest {
        setContent { BookSourceRow(emptyList(), a, resolver, { FocusRequester() }, { _, _ -> fail() }) }
        onNodeWithTag("book-source-none").assertTextEquals("这段讲解没有可核对的出处。")
        assertTrue(onAllNodes(hasClickAction()).fetchSemanticsNodes().isEmpty())
    }

    private fun ComposeUiTest.settled(vararg i: Int) = waitUntil(timeoutMillis = 5000) {
        i.all { onAllNodesWithTag("book-source-pending-$it").fetchSemanticsNodes().isEmpty() }
    }

    private class Counting(lib: TextbookLibrary) : BookSourceResolver(lib) {
        val hashes = java.util.concurrent.atomic.AtomicInteger(); val threads = java.util.concurrent.CopyOnWriteArrayList<Thread>()
        override fun fileMatches(book: Textbook): Boolean { hashes.incrementAndGet(); return super.fileMatches(book) }
        override fun resolveAll(requests: List<Pair<OwnedBookCitation, String?>>): List<BookSourceResolution> {
            threads += Thread.currentThread(); return super.resolveAll(requests)
        }
    }

    @Test fun verificationIsOffTheCompositionThreadAndHashesOncePerBatch() = runComposeUiTest {
        val counting = Counting(library); var composition: Thread? = null
        setContent { composition = Thread.currentThread(); BookSourceRow(listOf(p2, p3, p2), a, counting, { FocusRequester() }, { _, _ -> }) }
        settled(0, 1, 2)
        assertEquals(1, counting.hashes.get(), "one file hash for the whole batch")
        assertTrue(counting.threads.isNotEmpty() && counting.threads.none { it == composition }, "never on the composition/UI thread")
        onNodeWithTag("book-source-0").assertExists()
    }

    @Test fun screenReaderDescriptionCarriesTheSnippet() = runComposeUiTest {
        setContent { BookSourceRow(listOf(p2, p3), a, resolver, { FocusRequester() }, { _, _ -> }) }
        settled(0, 1)
        fun desc(i: Int) = onNodeWithTag("book-source-$i").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single()
        assertTrue(desc(0).contains(bookSourceLabel(p2)) && desc(0).contains("《${a.title}》"))
        assertNotEquals(desc(0), desc(1))
    }
}
