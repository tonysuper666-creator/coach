package com.coach.desktop.books

import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.coach.ui.CoachTheme
import org.junit.Assume.assumeTrue
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 真窗口里走到一次「库里只有这一本讲到」。合成书，不读本机教材。 */
@OptIn(ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class ConceptSayingsWindowTest {
    @Test fun onlyThisBookLineShowsInRealWindow() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        if (System.getProperty("os.name")?.startsWith("Windows") == true && System.getProperty("skiko.renderApi") == null) {
            System.setProperty("skiko.renderApi", System.getenv("SKIKO_RENDER_API") ?: "SOFTWARE_FAST")
        }
        val root = Files.createTempDirectory("coach-sayings-window-")
        val window = arrayOfNulls<ComposeWindow>(1)
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val bookA = library.importBook(root.resolve("a.md").also {
                Files.writeString(it, "# Chapter 1 Synthetic\n\nAlpha theory says private signals raise the premium.\n")
            })
            val bookB = library.importBook(root.resolve("b.md").also {
                Files.writeString(it, "# Chapter 1 Synthetic\n\nBeta evidence says informed buyers push the premium down.\n")
            })
            val conceptId = "concept-only"
            val chapterA = BookChapterResolver.chapters(bookA).first()
            val chapterB = BookChapterResolver.chapters(bookB).first()
            library.setChapterManualReadState(bookA.id, chapterA.tocNodeId, chapterA.pagesTotal, true)
            library.upsertCollection(MaterialCollectionRecord("list-window", CollectionKind.BOOKLIST, "synthetic window"))
            library.saveAlignedConcepts(
                "list-window",
                listOf(ConceptEntityRecord(conceptId, "list-window", "premium signal")),
                listOf(
                    mention(bookA, conceptId, "m-a", chapterA.tocNodeId, "Alpha theory says private signals raise the premium."),
                    mention(bookB, conceptId, "m-b", chapterB.tocNodeId, "Beta evidence says informed buyers push the premium down."),
                ),
                listOf(
                    ConceptLinkRecord(
                        linkId = "link-window",
                        collectionId = "list-window",
                        conceptId = conceptId,
                        fromMentionId = "m-a",
                        toMentionId = "m-b",
                        relationType = ConceptRelationType.OPPOSING_VIEW,
                        diffSummary = "synthetic",
                        confidence = 0.92,
                    ),
                ),
            )
            val board = library.conceptSayings(conceptId, bookA.id)
            assertNotNull(board)
            assertTrue(board.onlyThisBook)
            window[0] = edt {
                ComposeWindow().apply {
                    title = "concept sayings only this book"
                    setSize(900, 480)
                    setContent {
                        CoachTheme(false) {
                            CrossBookCompanionSection(
                                cards = emptyList(),
                                board = board,
                                onJumpToBookPage = { _, _, _, _ -> },
                                onMuteConcept = {},
                            )
                        }
                    }
                    isVisible = true
                }
            }
            val shown = window[0]!!
            waitFor(shown) { nodeText(shown, "concept-sayings-only-one").isNotEmpty() }
            assertEquals(ConceptSayingsBoard.ONLY_THIS_BOOK, nodeText(shown, "concept-sayings-only-one"))
            assertEquals("别的书怎么说", nodeText(shown, "concept-sayings-title"))
        } finally {
            window[0]?.let { edt { it.dispose() } }
            root.toFile().deleteRecursively()
        }
    }

    private fun mention(
        book: Textbook,
        conceptId: String,
        mentionId: String,
        tocNodeId: String,
        quote: String,
    ): ConceptMentionRecord {
        val page = book.pages.first { quote in it.text }
        return ConceptMentionRecord(
            mentionId = mentionId,
            conceptId = conceptId,
            bookId = book.id,
            tocNodeId = tocNodeId,
            page = page.number,
            offsetUtf16 = page.text.indexOf(quote),
            quoteAnchor = quote,
            claimSummary = "synthetic claim",
        )
    }

    private fun nodes(window: ComposeWindow): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return window.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }

    private fun nodeText(window: ComposeWindow, tag: String): String = edt {
        nodes(window)
            .filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .joinToString("\n") { it.text }
    }

    private fun waitFor(window: ComposeWindow, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (true) {
            edt { runCatching { window.renderImmediately() } }
            if (condition()) return
            check(System.nanoTime() < deadline) { "timed out waiting for the only-this-book line" }
            Thread.sleep(40)
        }
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
