package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.ui.theme.CoachTheme
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * TON-1659 卡末登记项：选段就问回答下方的「出处」和原文弹窗，和讲解卡写同一种位置。
 * 无页码的文本导入写「本章第 N 段」，不拿导入时编的页号冒充页码；有原件页码时写「第 X 页 · 第 M 段」。
 * 两处写的是提问的那一段，点开弹窗读到的也是它。
 */
@OptIn(ExperimentalTestApi::class)
class PassageCitationLabelTest {
    @Test fun textWithoutPageNumbersNamesTheAskedParagraphInItsChapter() {
        val root = Files.createTempDirectory("passage-label-txt-")
        val book = TextbookLibrary(root.resolve("library")).importBook(Files.writeString(root.resolve("two.txt"), TXT))
        assertEquals("txt", book.format, "用的是真的文本导入，不是拼出来的书")
        val page = book.pages.single()
        assertEquals("第 1 页（1 Light）", book.pageLabel(page.number), "章内位置由标题说明，书里没有页码")

        assertAskedParagraph(root, book, page, "Beta water.", "ask-citation-chapter-light",
            link = "出处 · 本章第 2 段", title = "原文 · 本章第 2 段")
    }

    @Test fun printedPageNumbersNameTheAskedParagraphOnItsPage() {
        val root = Files.createTempDirectory("passage-label-md-")
        val book = TextbookLibrary(root.resolve("library")).importBook(Files.writeString(root.resolve("marked.md"), MARKED))
        val page = book.pages.single()
        assertEquals(5, page.number, "页号取自导入时带的原件页标记")

        assertAskedParagraph(root, book, page, "Beta water.", "ask-citation-page-light",
            link = "出处 · 第 5 页 · 第 2 段", title = "原文 · 第 5 页 · 第 2 段")
    }

    /** 点第二段、提问，核对回答下方的出处和弹窗标题，再点开看是不是刚才问的那一段。 */
    private fun assertAskedParagraph(
        root: java.nio.file.Path, book: Textbook, page: BookPage, asked: String, shot: String, link: String, title: String,
    ) {
        val support = AnsweringSupport()
        runSkikoComposeUiTest(size = Size(1000f, 900f), density = Density(1f)) {
            setContent { CoachTheme(false) { BookPassageAsk(book, page, support) {} } }
            onNodeWithTag("ask-paragraph-1").performClick()
            onNodeWithTag("book-passage-question").performTextInput("这一段在说什么？")
            onNodeWithTag("book-passage-send").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithTag("book-passage-reply").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(asked, support.asked?.text, "发出去的是读者点的那一段")
            onNodeWithTag("book-passage-citation").performScrollTo().assertTextEquals(link)
            // 浅色留一张：回答下方的「出处」写的是哪一段。
            val file = SHOTS.resolve("$shot.png")
            Files.createDirectories(file.parent)
            assertTrue(ImageIO.write(onNodeWithTag("book-passage-dialog").captureToImage().toAwtImage(), "png", file.toFile()))
            onNodeWithTag("book-passage-citation").performClick()
            onNodeWithTag("book-passage-source-title").assertTextEquals(title)
            onNodeWithTag("book-passage-source").assertTextEquals(asked)
        }
        root.toFile().deleteRecursively()
    }

    /** 只回这一段自己的出处：链接和弹窗对的必须是提问的那一段。 */
    private class AnsweringSupport : BookLearningSupport {
        var asked: BookPassage? = null
        override suspend fun explain(request: BookExplanationRequest) =
            BookLearningReply.Unavailable("这张卡只问选段。")
        override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
            asked = request.passage
            return BookLearningReply.Text("这一段在说水。【S1】", listOf(request.passage))
        }
    }

    private companion object {
        val SHOTS: java.nio.file.Path = java.nio.file.Path.of("build/screens/ton1659")
        const val MARK = "<!-- PDF 第 5 页 · 书第 1 页 -->"
        val TXT = """
            # 1 Light

            Alpha light.

            Beta water.
        """.trimIndent()
        val MARKED = """
            $MARK
            # 1 Light

            Alpha light.

            Beta water.
        """.trimIndent()
    }
}
