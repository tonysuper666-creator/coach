package com.coach.desktop.books

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
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
    @Test fun kaiStylesOnlyChineseBodyRunsAtTheirOriginalOffsets() {
        val raw = "# 章名\r\n\r\n中文，English 27，𠮷\uDB40\uDD00字。\r\n\r\n图 1：图注"
        val model = bookParagraphs(raw, pdf = false)
        val text = buildAnnotatedString {
            append(raw)
            applyReadingFont(ReadingFont.KAI, raw, model)
        }
        assertEquals(raw, text.text)
        assertEquals(listOf("中文，", "，𠮷\uDB40\uDD00字。"), text.spanStyles.map { raw.substring(it.start, it.end) })
        assertTrue(text.spanStyles.all { it.item == wenKaiBody })
    }

    @Test fun existingLibraryUsesSongForMissingOrUnknownFontAndKeepsOtherPreferences() {
        val root = Files.createTempDirectory("coach-reading-font-")
        try {
            val initial = ReadingSettings(root)
            initial.chooseSize(ReadingSize.LARGE)
            initial.choosePaper(PaperColor.DARK)
            assertEquals(ReadingFont.SONG, ReadingSettings(root).font)
            initial.chooseFont(ReadingFont.KAI)
            assertEquals(ReadingFont.KAI, ReadingSettings(root).font)
            java.sql.DriverManager.getConnection("jdbc:sqlite:${root.resolve("library.sqlite")}").use { db ->
                db.createStatement().use { it.executeUpdate("UPDATE reading_settings SET value='UNKNOWN' WHERE name='font'") }
            }
            val recovered = ReadingSettings(root)
            assertEquals(ReadingFont.SONG, recovered.font)
            assertEquals(ReadingSize.LARGE, recovered.size)
            assertEquals(PaperColor.DARK, recovered.paper)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun everySizeFontAndPaperPreservesSourceOffsetsAtBothWindowSizes() {
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
                            CompositionLocalProvider(LocalDeskTypeControl provides panel, LocalReadingFont provides prefs.font) {
                                Desk(crumb = book.title, hasMargin = false) {
                                    BookPageView(book, paging, emptyMap(), {})
                                }
                            }
                        }
                    }
                    onNodeWithTag("v7-type-menu").performClick()
                    for (paper in PaperColor.entries) for (size in ReadingSize.entries) for (font in ReadingFont.entries) {
                        onNodeWithTag("reading-paper-${paper.name}").performClick()
                        onNodeWithTag("reading-size-${size.name}").performClick()
                        onNodeWithTag("reading-font-${font.name}").performClick()
                        waitForIdle()
                        val saved = ReadingSettings(root)
                        assertEquals(size, saved.size)
                        assertEquals(font, saved.font)
                        assertEquals(paper, saved.paper)
                        val layout = mutableListOf<TextLayoutResult>()
                        onNodeWithTag("reader-text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
                        assertEquals(18f * size.scale, layout.single().layoutInput.style.fontSize.value, .01f)
                        val text = layout.single().layoutInput.text
                        val kai = text.spanStyles.filter { it.item == wenKaiBody }
                        assertEquals(font == ReadingFont.SONG, kai.isEmpty())
                        if (font == ReadingFont.KAI) {
                            val mixed = text.text.indexOf("记录𠮷字和 English")
                            assertTrue(mixed >= 0)
                            assertTrue(kai.any { it.start <= mixed && it.end >= mixed + "记录𠮷字和".length })
                            for (sample in listOf("观察", "English")) {
                                val start = text.text.indexOf(sample)
                                assertTrue(start >= 0)
                                assertFalse(kai.any { it.start < start + sample.length && it.end > start })
                            }
                        }
                        assertEquals(raw.length, paging.pages.sumOf { it.text.length })
                        val display = paging.pages.joinToString("") { it.text.text }
                        for (i in raw.indices) if (!raw[i].isWhitespace()) assertEquals(raw[i], display[i])
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
                    onNodeWithTag("reading-font-KAI").assertIsNotEnabled()
                    onNodeWithTag("original-mode-$mode").performClick()
                    assertEquals(mode == "scroll", library.pdfScrollMode(controller.state.value.book!!.id))
                    onNodeWithTag("reading-settings-panel").assertDoesNotExist()
                }
                onNodeWithTag("book-original-toggle").performClick()
                onNodeWithTag("v7-type-menu").performClick()
                onNodeWithTag("reading-size-LARGE").assertIsEnabled().performClick()
                onNodeWithTag("reading-font-KAI").assertIsEnabled().performClick()
                onNodeWithTag("original-mode-scroll").assertDoesNotExist()
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
