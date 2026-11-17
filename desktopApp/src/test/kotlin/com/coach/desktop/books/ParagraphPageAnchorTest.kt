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
            val controller = TextbookController(library)
            runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
                setContent { WindowsTextbookHost(controller) { null } }
                waitUntil(20_000) { controller.state.value.book != null && !controller.state.value.busy }
                onNodeWithTag("home-read").performClick()
                waitUntil(20_000) { controller.state.value.screen == BookScreen.READER }
                waitForIdle()
                onNodeWithTag("reader-page-number").assertTextEquals("第 5–6 页")
                onNodeWithTag("reader-page-number").performClick()
                onNodeWithTag("page-jump").performTextReplacement("6")
                onNodeWithTag("page-jump").performKeyInput { pressKey(Key.Enter) }
                waitForIdle()
                // Retain the existing mixed-sheet bookmark contract.
                assertEquals(5, controller.state.value.page)
                onNodeWithTag("book-explain-toggle").performClick()
                waitForIdle()
                onNode(hasAnyAncestor(hasTestTag("book-explain")) and hasText("第六页可信选段", substring = true)).assertExists()
                onNode(hasAnyAncestor(hasTestTag("book-explain")) and hasText("第五页独立正文", substring = true)).assertDoesNotExist()
                onNodeWithTag("ask-book-passage").performClick()
                waitForIdle()
                onNodeWithTag("ask-paragraph-0").assertTextContains("第六页可信选段", substring = true)
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
