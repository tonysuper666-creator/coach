package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ParagraphPageAnchorTest {
    @Test fun paragraphActionsKeepTheJumpedSourceWhenOneSheetContainsTwoPhysicalPages() {
        val root = Files.createTempDirectory("paragraph-page-anchor-")
        try {
            val input = root.resolve("book.md")
            Files.writeString(input,
                "<!-- PDF 第 5 页 · 书第 1 页 -->\n第五页独立正文\n" +
                    "<!-- PDF 第 6 页 · 书第 2 页 -->\n第六页可信选段\n")
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(input)
            library.save(book.copy(promptSeen = true))
            BookHudPreferenceStore(library.directory).setPinned(true)
            // Exercise the page-scoped explanation. Read-through can legitimately explain the whole section.
            StudyModeStore.save(library, BookStudyPreference(book.id, BookStudyMode.BOOK_MAP))
            val controller = TextbookController(library)
            runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
                setContent { WindowsTextbookHost(controller) { null } }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.book != null && !controller.state.value.busy }
                onNodeWithTag("home-read").performClick()
                waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER }
                waitForIdle()
                val firstChapter = BookBoundaries.effectiveChapterForFooter(book, 5)
                onNodeWithTag("reader-page-number").assertTextEquals(BookBoundaries.pageStatus(5, 6, firstChapter))
                val visible = onAllNodes(SemanticsMatcher.keyIsDefined(BookPageSources)).fetchSemanticsNodes()
                assertEquals(setOf(5, 6), visible.flatMap { it.config[BookPageSources] }.map { it.importPage }.toSet())
                onNodeWithTag("reader-page-number").performClick()
                onNodeWithTag("page-jump").performTextReplacement("6")
                onNodeWithTag("page-jump").performKeyInput { pressKey(Key.Enter) }
                waitForIdle()
                // TON-1724 publishes the explicitly jumped import page, even on a mixed sheet.
                assertEquals(6, controller.state.value.page)
                assertEquals(6, library.book(book.id).readingPage)
                onNodeWithTag("immersive-companion-toggle").performClick()
                onNodeWithTag("companion-tab-explain").performClick()
                val source = onNodeWithTag("explain-fallback-page-0")
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("explain-fallback-page-0").fetchSemanticsNodes().isNotEmpty() }
                source.assertTextEquals("第 6 页")
                onNodeWithTag("companion-tab-ask").performClick()
                onNodeWithTag("companion-pick-paragraph").performClick()
                val paragraph = onNodeWithTag("ask-passage-block-0")
                paragraph.assertTextContains("第六页可信选段", substring = true)
                paragraph.performClick()
                onNodeWithTag("companion-ask-quote").assertTextEquals("引文 · “第六页可信选段”")
                onNode(hasAnyAncestor(hasTestTag("immersive-companion-drawer")) and hasText("第五页独立正文", substring = true)).assertDoesNotExist()
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
