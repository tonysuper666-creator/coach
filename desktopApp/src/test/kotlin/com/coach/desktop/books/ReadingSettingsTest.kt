package com.coach.desktop.books

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import com.coach.ui.CoachTheme
import com.coach.ui.v7.Desk
import com.coach.ui.v7.DeskTypeControl
import com.coach.ui.v7.LocalDeskTypeControl
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ReadingSettingsTest {
    @Test fun everySizeAndPaperPreservesSourceOffsetsAtBothWindowSizes() {
        val root = Files.createTempDirectory("coach-reading-combinations-")
        try {
            val prefs = ReadingSettings(root)
            val raw = "第一章 观察\r\n\r\n" + "在树荫下读书，记录𠮷字和 English words。\r\n\r\n".repeat(90)
            val book = Textbook("sample", "读本", "txt", listOf(BookPage(1, raw)))
            for (window in listOf(Size(1280f, 800f), Size(2560f, 1440f))) {
                val paging = BookPagingState(BookPageAnchor(1))
                runSkikoComposeUiTest(size = window, density = Density(1f)) {
                    setContent {
                        CoachTheme(prefs.paper.dark ?: false) {
                            val panel: DeskTypeControl = { extra -> ReadingSettingsPanel(prefs, { false }, extra) }
                            CompositionLocalProvider(LocalDeskTypeControl provides panel) {
                                Desk(crumb = book.title, hasMargin = false) {
                                    BookPageView(book, paging, emptyMap(), {})
                                }
                            }
                        }
                    }
                    onNodeWithTag("v7-type-menu").performClick()
                    for (paper in PaperColor.entries) for (size in ReadingSize.entries) {
                        onNodeWithTag("reading-paper-${paper.name}").performClick()
                        onNodeWithTag("reading-size-${size.name}").performClick()
                        waitForIdle()
                        val saved = ReadingSettings(root)
                        assertEquals(size, saved.size)
                        assertEquals(paper, saved.paper)
                        val layout = mutableListOf<TextLayoutResult>()
                        onNodeWithTag("reader-text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
                        assertEquals(18f * size.scale, layout.single().layoutInput.style.fontSize.value, .01f)
                        assertEquals(raw, paging.pages.joinToString("") { originalBookSelection(it.text) })
                        assertEquals(raw, paging.pages.flatMap { it.sources }.joinToString("") {
                            raw.substring(it.startUtf16, it.endUtf16)
                        })
                    }
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun originalPdfKeepsBothModesAndOnlyTextViewEnablesSize() {
        val root = Files.createTempDirectory("coach-reading-pdf-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val controller = TextbookController(library)
            runBlocking {
                controller.importBook(wordedChapterPdf(root))
                controller.learn(LearningPath.READ)
            }
            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                setContent { WindowsTextbookHost(controller) { null } }
                for (mode in listOf("paged", "scroll")) {
                    onNodeWithTag("v7-type-menu").performClick()
                    onNodeWithTag("reading-size-LARGE").assertIsNotEnabled()
                    onNodeWithTag("original-mode-$mode").performClick()
                    assertEquals(mode == "scroll", library.pdfScrollMode(controller.state.value.book!!.id))
                    onNodeWithTag("reading-settings-panel").assertDoesNotExist()
                }
                onNodeWithTag("book-original-toggle").performClick()
                onNodeWithTag("v7-type-menu").performClick()
                onNodeWithTag("reading-size-LARGE").assertIsEnabled().performClick()
                onNodeWithTag("original-mode-scroll").assertDoesNotExist()
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
