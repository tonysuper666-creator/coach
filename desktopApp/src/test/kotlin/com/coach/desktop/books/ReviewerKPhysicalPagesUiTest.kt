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

    /**
     * TON-1790：主窗口去掉了左侧「书架 / 课程」导航栏，只剩顶栏。宿主里真实的来回一趟改为：
     * 阅读器左上角「← 返回书架」回到主窗口书架，再点这本书的书卡重新打开（书卡直接进阅读器）。
     */
    private fun ComposeUiTest.backToShelfAndReopen(controller: TextbookController, bookId: String) {
        onNodeWithTag("immersive-back").assertTextContains("书架", substring = true).performClick()
        ready(controller) { it.screen == BookScreen.HOME }
        onNodeWithTag("host-bar").assertExists()
        onNodeWithTag("book-$bookId").performClick()
    }

    /**
     * 2.0：重开窗口时上次停在阅读器就直接恢复到阅读器（TextbookController.load 按 openSession 恢复）；
     * 停在书架时点书卡直接打开阅读器（TextbookController.openBook），不再有书首页的「继续」。
     */
    private fun ComposeUiTest.openFromShelf(controller: TextbookController, bookId: String) {
        ready(controller) {
            (it.screen == BookScreen.READER && it.book?.id == bookId) ||
                (it.screen == BookScreen.HOME && it.catalog.any { entry -> entry.id == bookId })
        }
        if (controller.state.value.screen == BookScreen.HOME) onNodeWithTag("book-$bookId").performClick()
    }

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
            // TON-1784（#570）：导入完成直接进阅读器，不再有「导入后 / 读完」两道提示。
            ready(controller) { it.screen == BookScreen.READER && it.prompt == null && it.page == 5 }
            bookId = controller.state.value.book!!.id
            assertEquals(listOf(5, 6), controller.state.value.book!!.pages.map { it.number })
            onNodeWithTag("reader-text").assertTextContains("第五页独立正文", substring = true)
            onNodeWithTag("reader-text").assertTextContains("第六页独立正文", substring = true)
            // TON-1821（#603）：页码统一为「第 当前 / 末页 页」，一屏两页时显示读位所在的页。
            onNodeWithTag("reader-page-number").assertTextEquals("第 5 / 6 页")
            val sources = onNodeWithTag("reader-text").fetchSemanticsNode().config[BookPageSources]
            assertEquals(listOf(5, 6), sources.map { it.importPage })
            assertEquals(controller.state.value.book!!.pages.joinToString("") { it.text },
                onNodeWithTag("reader-text").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text)
            onNodeWithTag("previous-page").assertIsNotEnabled()
            onNodeWithTag("next-page").assertIsNotEnabled()
            onNodeWithTag("reader-page-number").performClick()
            for (number in listOf("1", "4", "7")) {
                onNodeWithTag("page-jump").performTextReplacement(number)
                onNodeWithTag("page-jump").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
                waitForIdle()
                onNodeWithTag("page-jump").assertExists()
                assertEquals(5, controller.state.value.page, "missing physical page rejected")
            }
            // 2.0 读位规则（BookPageView：读者跳页即移动锚点并写读位）：跳到导入页 6 时锚点落在第 6 页，
            // 书签记第 6 页；这一屏仍是 5、6 两页同屏，页码显示锚点所在的第 6 页。
            onNodeWithTag("page-jump").performTextReplacement("6")
            onNodeWithTag("page-jump").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
            ready(controller) { it.page == 6 && it.book?.readingPage == 6 }
            onNodeWithTag("page-jump").assertDoesNotExist()
            onNodeWithTag("reader-page-number").assertTextEquals("第 6 / 6 页")
            assertEquals(listOf(5, 6), onNodeWithTag("reader-text").fetchSemanticsNode().config[BookPageSources].map { it.importPage })

            backToShelfAndReopen(controller, bookId)
            ready(controller) { it.screen == BookScreen.READER && it.page == 6 }
            assertEquals(bookId, controller.state.value.book!!.id)
            onNodeWithTag("reader-text").assertTextContains("第五页独立正文", substring = true)
            onNodeWithTag("next-page").assertIsNotEnabled()
        }
        assertEquals(6, TextbookLibrary(root.resolve("library")).book(bookId).readingPage)

        val reopened = TextbookController(TextbookLibrary(root.resolve("library")))
        runSkikoComposeUiTest(size = Size(2880f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(reopened) { null } }
            openFromShelf(reopened, bookId)
            ready(reopened) { it.screen == BookScreen.READER && it.page == 6 && it.prompt == null }
            assertEquals(bookId, reopened.state.value.book!!.id)
            onNodeWithTag("reader-text").assertTextContains("第六页独立正文", substring = true)
            onNodeWithTag("next-page").assertIsNotEnabled()
        }
        println("PASS real host: pages=[5,6], label=5/6, jump=6, rejected=[1,4,7], bookmark=6, sameBook=$bookId")
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
            openFromShelf(controller, imported.id)
            ready(controller) { it.screen == BookScreen.READER && it.page == 5 && it.prompt == null }
            onNodeWithTag("reader-text").assertTextContains("第五页独立正文", substring = true)
            onNodeWithTag("previous-page").assertIsNotEnabled()
            turnUntil(controller, "next-page") { it.page == 6 && it.book?.readingPage == 6 }
            onNodeWithTag("reader-text").assertTextContains("第六页独立正文", substring = true)
            onNodeWithTag("next-page").assertIsNotEnabled()
            turnUntil(controller, "previous-page") { it.page == 5 }
            onNodeWithTag("reader-page-number").performClick()
            onNodeWithTag("page-jump").performTextReplacement("6")
            onNodeWithTag("page-jump").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
            ready(controller) { it.page == 6 && it.book?.readingPage == 6 }
            backToShelfAndReopen(controller, imported.id)
            ready(controller) { it.screen == BookScreen.READER && it.page == 6 }
            assertEquals(imported.id, controller.state.value.book!!.id)
            onNodeWithTag("reader-text").assertTextContains("第六页独立正文", substring = true)
        }
        assertEquals(6, TextbookLibrary(library.directory).book(imported.id).readingPage)
        val reopened = TextbookController(TextbookLibrary(library.directory))
        // TON-1854（#639）：≥1080dp 宽窗沉浸文字排两栏、按屏翻；这里要逐页翻，窗口取 1000dp 宽（单栏）。
        runSkikoComposeUiTest(size = Size(2000f, 1800f), density = Density(2f)) {
            setContent { WindowsTextbookHost(reopened) { null } }
            openFromShelf(reopened, imported.id)
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
            runBlocking { controller.learn() }
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
            onNodeWithTag("immersive-back").assertTextContains("返回学习", substring = true).performClick()
            ready(controller) { it.screen == BookScreen.LEARNING && it.source == null }
            assertEquals(0, controller.state.value.book!!.lessonIndex)
            assertEquals(6, controller.state.value.book!!.readingPage)
            onNodeWithTag("short-answer").assertTextContains("保留这段同书学习草稿")
        }
        println("PASS citation: sourcePage=5, readingBookmark=6, lesson=0, draft preserved")
    }
}
