package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import com.coach.desktop.reader.LocalBooksReaderSource
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ReviewerKPhysicalPagesUiTest {
    @Test fun physicalCitationAndLocalReaderRejectMissingPages() = runBlocking {
        val root = Files.createTempDirectory("physical-pages-source-")
        val input = root.resolve("book.md")
        Files.writeString(input, "<!-- PDF 第 5 页 · 书第 1 页 -->\n第五页内容\n<!-- PDF 第 7 页 · 书第 3 页 -->\n第七页内容\n")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(input)
        val page = book.page(7)!!
        val start = page.text.indexOf("第七页内容")
        val citation = OwnedBookCitation(book.id, 7, start, start + "第七页内容".length)
        val resolution = assertIs<BookSourceResolution.Resolved>(BookSourceResolver(library).resolve(citation, "第七页内容"))
        assertEquals(7, resolution.target.pdfPage)
        assertEquals(book.id, resolution.target.book)
        assertEquals(BookSourceRefusal.PAGE_MISSING, assertIs<BookSourceResolution.Refused>(BookSourceResolver(library).resolve(citation.copy(page = 6))).reason)
        val source = LocalBooksReaderSource(library)
        assertEquals(200, source.page(book.id, 5).status)
        assertEquals(200, source.page(book.id, 7).status)
        assertEquals(409, source.page(book.id, 6).status)
        library.save(book.copy(readingPage = 7))
        assertEquals(7, TextbookLibrary(library.directory).book(book.id).readingPage)
    }

    private fun ComposeUiTest.ready(controller: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && condition(controller.state.value) }
        assertNull(controller.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.nav(label: String) =
        onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText(label))

    @Test fun physicalPagesSurviveRealHostNavigationAndReload() {
        val root = Files.createTempDirectory("reviewer-k-physical-host-")
        val input = root.resolve("book.md")
        Files.writeString(input, "<!-- PDF 第 5 页 · 书第 1 页 -->\n第五页独立正文\n<!-- PDF 第 6 页 · 书第 2 页 -->\n第六页独立正文\n")
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        var bookId = ""
        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(controller) { input } }
            ready(controller) { it.catalog.isEmpty() && it.book == null }
            onNodeWithTag("upload-textbook").performClick()
            ready(controller) { it.prompt == BookPrompt.IMPORTED }
            bookId = controller.state.value.book!!.id
            assertEquals(listOf(5, 6), controller.state.value.book!!.pages.map { it.number })
            onNodeWithTag("read-only").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.prompt == BookPrompt.AFTER_READING }
            onNodeWithTag("read-only").performClick()
            ready(controller) { it.prompt == null && it.page == 5 }
            onNodeWithTag("reader-text").assertTextContains("第五页独立正文", substring = true)
            onNodeWithTag("reader-text").assertTextContains("第六页独立正文", substring = true)
            onNodeWithTag("reader-page-number").assertTextEquals("第 5–6 页")
            val sources = onNodeWithTag("reader-text").fetchSemanticsNode().config[BookPageSources]
            assertEquals(listOf(5, 6), sources.map { it.importPage })
            assertEquals(controller.state.value.book!!.pages.joinToString("") { it.text },
                onNodeWithTag("reader-text").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text)
            onNodeWithText("上一页").assertIsNotEnabled()
            onNodeWithText("下一页").assertIsNotEnabled()
            onNodeWithTag("reader-page-number").performClick()
            for (number in listOf("1", "4", "7")) {
                onNodeWithTag("page-jump").performTextReplacement(number)
                onNodeWithTag("page-jump").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
                waitForIdle()
                onNodeWithTag("page-jump").assertExists()
                assertEquals(5, controller.state.value.page, "missing physical page rejected")
            }
            // Import page 6 belongs to the same laid-out page, whose first source is 5.
            onNodeWithTag("page-jump").performTextReplacement("6")
            onNodeWithTag("page-jump").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
            ready(controller) { it.page == 5 && it.book?.readingPage == 5 }
            onNodeWithTag("page-jump").assertDoesNotExist()
            onNodeWithTag("reader-page-number").assertTextEquals("第 5–6 页")

            nav("课程").performClick()
            onNodeWithTag("host-course-book").assertExists()
            nav("书架").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.page == 5 }
            assertEquals(bookId, controller.state.value.book!!.id)
            onNodeWithTag("reader-text").assertTextContains("第五页独立正文", substring = true)
            onNodeWithText("下一页").assertIsNotEnabled()
        }
        assertEquals(5, TextbookLibrary(root.resolve("library")).book(bookId).readingPage)

        val reopened = TextbookController(TextbookLibrary(root.resolve("library")))
        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(reopened) { null } }
            ready(reopened) { it.book?.id == bookId }
            onNodeWithTag("home-read").performClick()
            ready(reopened) { it.screen == BookScreen.READER && it.page == 5 && it.prompt == null }
            assertEquals(bookId, reopened.state.value.book!!.id)
            onNodeWithTag("reader-text").assertTextContains("第六页独立正文", substring = true)
            onNodeWithText("下一页").assertIsNotEnabled()
        }
        println("PASS real host: pages=[5,6], range=5–6, jump=6, rejected=[1,4,7], bookmark=5, sameBook=$bookId")
    }

    @Test fun chapterPageTurnsKeepTheirPhysicalIdentityAcrossHostNavigationAndReload() {
        val root = Files.createTempDirectory("reviewer-k-chapter-host-")
        val input = root.resolve("book.md")
        Files.writeString(input,
            "<!-- PDF 第 5 页 · 书第 1 页 -->\n# Chapter 1 Light\n\n第五页独立正文\n" +
                "<!-- PDF 第 6 页 · 书第 2 页 -->\n# Chapter 2 Cells\n\n第六页独立正文\n")
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(input)
        library.save(imported.copy(promptSeen = true))
        val controller = TextbookController(library)
        // TON-1854（#639）：≥1080dp 宽窗沉浸文字排两栏、按屏翻；这里要逐页翻，窗口取 1000dp 宽（单栏）。
        runSkikoComposeUiTest(size = Size(2000f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(controller) { null } }
            ready(controller) { it.book?.id == imported.id }
            onNodeWithTag("home-read").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.page == 5 && it.prompt == null }
            onNodeWithTag("reader-text").assertTextContains("第五页独立正文", substring = true)
            onNodeWithText("上一页").assertIsNotEnabled()
            onNodeWithText("下一页").performClick()
            ready(controller) { it.page == 6 && it.book?.readingPage == 6 }
            onNodeWithTag("reader-text").assertTextContains("第六页独立正文", substring = true)
            onNodeWithText("下一页").assertIsNotEnabled()
            onNodeWithText("上一页").performClick()
            ready(controller) { it.page == 5 }
            onNodeWithTag("reader-page-number").performClick()
            onNodeWithTag("page-jump").performTextReplacement("6")
            onNodeWithTag("page-jump").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
            ready(controller) { it.page == 6 && it.book?.readingPage == 6 }
            nav("课程").performClick()
            onNodeWithTag("host-course-book").assertExists()
            nav("书架").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.page == 6 }
            assertEquals(imported.id, controller.state.value.book!!.id)
            onNodeWithTag("reader-text").assertTextContains("第六页独立正文", substring = true)
        }
        assertEquals(6, TextbookLibrary(library.directory).book(imported.id).readingPage)
        val reopened = TextbookController(TextbookLibrary(library.directory))
        // TON-1854（#639）：≥1080dp 宽窗沉浸文字排两栏、按屏翻；这里要逐页翻，窗口取 1000dp 宽（单栏）。
        runSkikoComposeUiTest(size = Size(2000f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(reopened) { null } }
            ready(reopened) { it.book?.id == imported.id }
            onNodeWithTag("home-read").performClick()
            ready(reopened) { it.screen == BookScreen.READER && it.page == 6 && it.prompt == null }
            onNodeWithTag("reader-text").assertTextContains("第六页独立正文", substring = true)
            assertEquals(listOf(6), onNodeWithTag("reader-text").fetchSemanticsNode().config[BookPageSources].map { it.importPage })
        }
    }

    @Test fun practiceCitationAndDraftKeepThePhysicalPageAndReadingBookmark() {
        val root = Files.createTempDirectory("reviewer-k-physical-source-")
        val input = root.resolve("book.md")
        Files.writeString(input,
            "<!-- PDF 第 5 页 · 书第 1 页 -->\n# Chapter 1 Light\n\n" +
                "Photosynthesis is the process by which plants use light to make sugars.\n\n" +
                "<!-- PDF 第 6 页 · 书第 2 页 -->\n# Chapter 2 Cells\n\n" +
                "Chlorophyll is a green pigment that absorbs light in plant cells.\n")
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(input)
        library.save(imported.copy(readingPage = 6, learning = true, promptSeen = true, questionMode = QuestionMode.SHORT_ANSWER))
        val controller = TextbookController(library)
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            ready(controller) { it.book?.id == imported.id }
            onNodeWithTag("home-learn").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING }
            onNodeWithTag("short-answer").performScrollTo().performTextInput("保留这段同书学习草稿")
            ready(controller) { it.answer.text == "保留这段同书学习草稿" }
            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.READER && it.source != null && it.page == 5 }
            assertEquals(imported.lessons[0].id, controller.state.value.source!!.id)
            assertEquals(6, controller.state.value.book!!.readingPage)
            assertEquals(6, library.book(imported.id).readingPage)
            onNodeWithTag("reader-text").assertTextContains("Photosynthesis is the process", substring = true)
            onAllNodesWithTag("source-anchor-status").assertCountEquals(0)
            onNodeWithText("返回学习").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING && it.source == null }
            assertEquals(0, controller.state.value.book!!.lessonIndex)
            assertEquals(6, controller.state.value.book!!.readingPage)
            onNodeWithTag("short-answer").assertTextContains("保留这段同书学习草稿")
        }
        println("PASS citation: sourcePage=5, readingBookmark=6, lesson=0, draft preserved")
    }
}
