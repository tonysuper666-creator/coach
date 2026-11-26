package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.coach.ui.CoachTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Home selects the first page when a pointer click never reaches the glyphs.
 * The word has to sit inside a body paragraph so an explain request can cite it.
 */
@OptIn(ExperimentalTestApi::class)
class ReaderSelectKeyTest {
    @Test fun firstBodyWordStaysInsideThePageParagraph() {
        val text = "记忆与理解 Memory and understanding\n\n学习不是把一页文字抄进脑中，而是建立可以解释的联系。"
        val book = Textbook("select-key", "阅读", "txt", listOf(BookPage(1, text), BookPage(2, "第二页另外一句。")))
        val word = assertNotNull(book.firstBodyWord(1))
        assertEquals(1, word.page)
        assertEquals(text.substring(word.start, word.end), word.text)
        assertTrue(word.text.any { it.isLetterOrDigit() })
        val block = book.sourceBlocks().first { it.page == 1 && it.isBodyParagraph && word.text in it.text }
        assertTrue(word.start >= block.start && word.end <= block.end, "word '${word.text}' left its paragraph")
        val second = assertNotNull(book.firstBodyWord(2))
        assertEquals(2, second.page)
        assertTrue(second.text in "第二页另外一句。")
        assertNull(Textbook("blank", "阅读", "txt", listOf(BookPage(1, " \n\t"))).firstBodyWord(1))
    }

    @Test fun pinnedCenterTapExplainsThenAsks() {
        assertEquals(PinnedCenterTap.Ignore, pinnedCenterTap(pinned = false, companionOpen = false, explainStarted = false))
        assertEquals(PinnedCenterTap.Ignore, pinnedCenterTap(pinned = true, companionOpen = true, explainStarted = false))
        assertEquals(PinnedCenterTap.Explain, pinnedCenterTap(pinned = true, companionOpen = false, explainStarted = false))
        assertEquals(PinnedCenterTap.AskField, pinnedCenterTap(pinned = true, companionOpen = false, explainStarted = true))
        assertEquals(PinnedCenterTap.Ignore, pinnedCenterTap(pinned = true, companionOpen = true, explainStarted = true))
    }

    @Test fun homeRaisesTheSelectionToolbarForTheOpenPage() {
        val root = Files.createTempDirectory("coach-select-key-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(root.resolve("Reading.txt").also {
                Files.writeString(it, "Memory and understanding starts with active recall rather than rereading.")
            })
            runSkikoComposeUiTest(size = Size(960f, 720f), density = Density(1f)) {
                setContent {
                    val focus = remember { FocusRequester() }
                    CoachTheme(dark = false) {
                        CompositionLocalProvider(LocalReadingAutoGuide provides false) {
                            Box(Modifier.fillMaxSize()) {
                                TextbookReader(
                                    ui = LibraryUi(library.catalog(), book, screen = BookScreen.READER, page = 1),
                                    library = library,
                                    support = UnconnectedBookLearningSupport,
                                    practiceSource = null,
                                    pageFocus = focus,
                                    onNoteSession = {},
                                    onPage = {},
                                    onBack = {},
                                    onLearn = {},
                                )
                            }
                        }
                    }
                }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("reader-page").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("reader-page").performKeyInput { pressKey(Key.MoveHome) }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("selection-explain-toolbar").fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue(onAllNodesWithTag("selection-action-explain").fetchSemanticsNodes().isNotEmpty())
            }
        } finally {
            HomeSelectRequest.clear()
            root.toFile().deleteRecursively()
        }
    }

    @Test fun homeWordStaysWhenTheReaderIsRecreated() {
        val root = Files.createTempDirectory("coach-select-key-recreate-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(root.resolve("Reading.txt").also {
                Files.writeString(it, "Memory and understanding starts with active recall rather than rereading.")
            })
            HomeSelectRequest.clear()
            runSkikoComposeUiTest(size = Size(960f, 720f), density = Density(1f)) {
                val generation = mutableIntStateOf(0)
                setContent {
                    val focus = remember { FocusRequester() }
                    CoachTheme(dark = false) {
                        CompositionLocalProvider(LocalReadingAutoGuide provides false) {
                            key(generation.intValue) {
                                Box(Modifier.fillMaxSize()) {
                                    TextbookReader(
                                        ui = LibraryUi(library.catalog(), book, screen = BookScreen.READER, page = 1),
                                        library = library,
                                        support = UnconnectedBookLearningSupport,
                                        practiceSource = null,
                                        pageFocus = focus,
                                        onNoteSession = {},
                                        onPage = {},
                                        onBack = {},
                                        onLearn = {},
                                    )
                                }
                            }
                        }
                    }
                }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("reader-page").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("reader-page").performKeyInput { pressKey(Key.MoveHome) }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("selection-explain-toolbar").fetchSemanticsNodes().isNotEmpty()
                }
                generation.intValue = generation.intValue + 1
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("selection-explain-toolbar").fetchSemanticsNodes().isNotEmpty()
                }
            }
        } finally {
            HomeSelectRequest.clear()
            root.toFile().deleteRecursively()
        }
    }
}
