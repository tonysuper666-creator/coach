package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * 书架「直接开始读」和题目「查看对应原文」必须落在同一个阅读器、同一本书和同一页上。
 *
 * 这两个入口在状态上只差一项：书架阅读没有当前节（`source == null`），出处阅读带着当前节；
 * 页面本身由同一个 `TextbookReader`、同一个 `LocalBooksReaderSource` 按 `book.id` 与 1 起页码渲染。
 * 这里用同一份合成教材先走书架再走题目出处，比较文档、页码与这一页的正文，
 * 并锁住回题后已选选项和未提交草稿都还在。
 */
@OptIn(ExperimentalTestApi::class)
class ReaderEntryParityTest {
    @Test fun mixedPdfWithOcrCoverOpensTheOriginalFromTheImportPrompt() {
        val root = Files.createTempDirectory("coach-mixed-cover-")
        try {
            val file = readerParityTextbook(root)
            PDDocument().use { cover ->
                cover.addPage(PDPage())
                org.apache.pdfbox.Loader.loadPDF(file.toFile()).use { body ->
                    cover.importPage(body.getPage(1))
                    cover.save(root.resolve("mixed.pdf").toFile())
                }
            }
            val library = TextbookLibrary(root.resolve("library"), ocr = BookOcr { _, _ -> "Cover" })
            val controller = TextbookController(library)
            kotlinx.coroutines.runBlocking { controller.importBook(root.resolve("mixed.pdf")) }
            assertTrue(controller.state.value.book!!.pages.first().ocr)
            // TON-1784（#570）：导入后不再弹「只读 / 读完」提示，直接进阅读器；切文字的入口挪进了「更多」里的「原页 / 文字」。
            assertEquals(BookScreen.READER, controller.state.value.screen)
            assertNull(controller.state.value.prompt)
            runComposeUiTest {
                setContent { TextbookWorkspace(controller) { null } }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithContentDescription("mixed 第 1 页").fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithTag("reader-text").assertCountEquals(0)
                revealReaderHud()
                onNodeWithTag("immersive-more-menu").performClick()
                onNodeWithTag("view-mode-text").assertExists()
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun shelfAndPracticeSourceOpenTheSameReaderOnTheSamePage() {
        val root = Files.createTempDirectory("coach-reader-parity-")
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(readerParityTextbook(root.resolve("synthetic")))
        library.save(imported.copy(readingPage = 1, lessonIndex = 0, learning = true, questionMode = QuestionMode.CHOICE))
        val controller = TextbookController(library)
        val gate = generatedChoiceGate(root)
        runComposeUiTest {
            // 2.0 宽窗原页并排两页、文字一屏多栏，逐页点「上一页 / 下一页」要在一屏一页的竖窄窗里测（SinglePageReaderWindow）。
            setContent { Box(SinglePageReaderWindow) { TextbookWorkspace(controller, answerExplain = gate) { null } } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }

            // 书架入口：从第 1 页读到第 2 页，读到的这一页就是后面题目出处要落的那一页。
            onNodeWithTag("home-read").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && !controller.state.value.busy }
            val book = controller.state.value.book!!
            assertEquals(1, controller.state.value.page, "书架从保存的阅读位置打开")
            assertNull(controller.state.value.source, "书架阅读不属于某一节")
            // 2.0 宽屏按屏翻（一屏多栏），「下一页」不保证落在第 2 页；用页码跳转精确到第 2 页。
            jumpToPage(controller, 2)
            onAllNodesWithTag("windows-textbooks").assertCountEquals(1)
            onAllNodesWithTag("textbook-reader").assertCountEquals(1)
            onAllNodesWithTag("reader-page").assertCountEquals(1)
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithContentDescription("${book.title} 第 2 页").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("immersive-more-menu").performClick(); onNodeWithTag("view-mode-text").performClick()
            val shelfText = onNodeWithTag("reader-text").fetchSemanticsNode()
                .config[SemanticsProperties.Text].single().text
            val shelfSources = onNodeWithTag("reader-text").fetchSemanticsNode().config[BookPageSources]
            assertTrue(shelfSources.any { it.importPage == 2 && it.startUtf16 == 0 })
            assertEquals(shelfSources.joinToString("") { book.page(it.importPage)!!.text.substring(it.startUtf16, it.endUtf16) }, shelfText)
            // 2.0 文字视图一屏可能从上一导入页的尾巴排起（这里第 1 页只有标题，和第 2 页排在同一屏）。
            // 读位跟读者自己的锚点走、重排和切视图不改读位（BookPageView「读位只在读者自己动了锚点时写」），
            // 所以书签仍是刚跳到的第 2 页，不再是这一屏首字的来源页。
            waitUntil(timeoutMillis = 20_000) { controller.state.value.page == 2 && !controller.state.value.busy }
            // TON-1790：阅读器左上角的返回键是 immersive-back，书架阅读时写「← 返回书架」。
            onNodeWithTag("immersive-back").assertTextContains("书架", substring = true).performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.HOME && !controller.state.value.busy }
            val readingBookmark = 2
            assertEquals(readingBookmark, library.book(book.id).readingPage, "书签保存读者所在的导入页")

            // 题目出处入口：同一本书、同一页、同一个阅读器，只多一个当前节。
            // TON-1784（#570）：书架上的「做题学」按钮已去掉；看书已保存为当前走法，题目出处走显式的「先做题」走法。
            runBlocking { controller.learn(LearningPath.QUESTION_FIRST) }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            val lesson = controller.state.value.book!!.lessons[controller.state.value.book!!.lessonIndex]
            // TON-1784（#570）：选择题由服务按本节第一段够长的正文生成，作答只留在本窗口（generatedChoiceGate）。
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("generated-practice").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, lesson.blocks.single { !it.heading }.page, "这一节正文在第 2 页")
            assertEquals(lesson.blocks.single { !it.heading }.text, generatedChoiceBlock(lesson).text, "题目依据就是这一段")
            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && !controller.state.value.busy }

            assertEquals(book.id, controller.state.value.book!!.id, "出处打开的是书架上同一本书")
            assertEquals(2, controller.state.value.page, "出处落在书架读到的那一页")
            assertEquals(lesson.id, controller.state.value.source?.id, "出处阅读带当前节，但仍由同一个阅读器呈现")
            onAllNodesWithTag("windows-textbooks").assertCountEquals(1)
            onAllNodesWithTag("textbook-reader").assertCountEquals(1)
            onAllNodesWithTag("reader-page").assertCountEquals(1)
            // 书架已为这本书选了文字版。出处重开沿用它，仍可切回同一张原页。
            assertEquals(true, library.savedTextView(book.id))
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("immersive-more-menu").performClick(); onNodeWithTag("view-mode-original").performClick()
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithContentDescription("${book.title} 第 2 页").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("immersive-more-menu").performClick(); onNodeWithTag("view-mode-text").performClick()
            val sourceText = onNodeWithTag("reader-text").fetchSemanticsNode()
                .config[SemanticsProperties.Text].single().text
            assertEquals(shelfText, sourceText, "两个入口渲染的是同一页正文")
            assertEquals(shelfSources, onNodeWithTag("reader-text").fetchSemanticsNode().config[BookPageSources])
            assertEquals(readingBookmark, library.book(book.id).readingPage, "出处重排不移动看书书签")
            // TON-1784（#570）：出处阅读的顶栏节名改为「出处 · 节名」。
            onNodeWithText("出处 · ${lesson.title}").assertExists()
            onNodeWithTag("immersive-back").assertTextContains("返回学习", substring = true).performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
        }
    }

    @Test fun selectedChoiceSurvivesThePracticeSourceRoundTrip() {
        val root = Files.createTempDirectory("coach-reader-choice-")
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(readerParityTextbook(root.resolve("synthetic")))
        library.save(imported.copy(readingPage = 2, lessonIndex = 0, learning = true, questionMode = QuestionMode.CHOICE))
        val controller = TextbookController(library)
        val gate = generatedChoiceGate(root)
        runComposeUiTest {
            setContent { TextbookWorkspace(controller, answerExplain = gate) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            runBlocking { controller.learn() }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }

            val book = controller.state.value.book!!
            val lesson = book.lessons[book.lessonIndex]
            // TON-1784（#570）：选择题由服务按本节第一段够长的正文生成，作答只留在本窗口（generatedChoiceGate）。
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("choice-1").fetchSemanticsNodes().isNotEmpty() }
            val selected = GENERATED_CHOICE_OPTIONS[1]
            val index = 1
            onNodeWithTag("choice-$index").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { gate.answerFor(book, lesson) == PracticeAnswer(selected, revealed = true) }

            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && !controller.state.value.busy }
            onNodeWithTag("immersive-back").assertTextContains("返回学习", substring = true).performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }

            assertEquals(PracticeAnswer(selected, revealed = true), gate.answerFor(book, lesson), "回题后仍保留已选项")
            onNodeWithText("你选择：$selected").assertExists()
            onNodeWithTag("choice-$index").assertIsNotEnabled()
            assertEquals(lesson.id, controller.state.value.book!!.lessons[controller.state.value.book!!.lessonIndex].id)
        }
    }

    @Test fun unsubmittedShortAnswerDraftSurvivesThePracticeSourceRoundTrip() {
        val root = Files.createTempDirectory("coach-reader-draft-")
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(readerParityTextbook(root.resolve("synthetic")))
        library.save(imported.copy(readingPage = 2, lessonIndex = 0, learning = true, questionMode = QuestionMode.SHORT_ANSWER))
        val controller = TextbookController(library)
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            runBlocking { controller.learn() }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }

            // 不等落盘就点出处：草稿只在本机的待写队列里，回来时也必须还在。
            onNodeWithTag("short-answer").performScrollTo().performTextInput("还没提交的草稿")
            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && !controller.state.value.busy }
            onNodeWithTag("immersive-back").assertTextContains("返回学习", substring = true).performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.answer.text == "还没提交的草稿" }
            assertFalse(controller.state.value.answer.revealed, "草稿未提交")

            assertEquals(PracticeAnswer("还没提交的草稿"), controller.state.value.answer, "回题后草稿仍是未提交状态")
            onNodeWithTag("short-answer").assertTextContains("还没提交的草稿")
            onNodeWithTag("reveal-reference").assertIsEnabled()
        }
    }
}

/** 一节正文只占第 2 页，书架读到的页和题目出处要落的页可以严格比较。 */
private fun readerParityTextbook(directory: Path): Path {
    Files.createDirectories(directory)
    val file = directory.resolve("A shared reader biology textbook.pdf")
    PDDocument().use { pdf ->
        pdf.documentInformation.title = "A shared reader biology textbook"
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        fun page(heading: String?, paragraph: String?) {
            val page = PDPage(); pdf.addPage(page)
            PDPageContentStream(pdf, page).use { out ->
                var y = 710f
                if (heading != null) {
                    out.beginText(); out.setFont(font, 18f); out.newLineAtOffset(50f, y); out.showText(heading); out.endText()
                    y -= 48f
                }
                if (paragraph != null) {
                    out.beginText(); out.setFont(font, 11f); out.newLineAtOffset(50f, y); out.showText(paragraph); out.endText()
                }
            }
        }
        page("Chapter 1 Cells", null)
        page(null, "Chlorophyll is a green pigment that absorbs light in plant cells.")
        page("Chapter 2 Water", "Transpiration pulls water from the roots up to the leaves.")
        pdf.save(file.toFile())
    }
    return file
}
