package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.ui.theme.CoachTheme
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * TON-1659：带原件页标记的文本导入，标记只说明这页是书里第几页，本身不是一段正文。
 * 同一段在「选段就问」的行号和讲解出处里的段号必须读到同一个数——QA 在 #455 上先读到 3、再读到 2。
 */
@OptIn(ExperimentalTestApi::class)
class MarkedPageParagraphTest {
    @Test fun thePageMarkIsNotAParagraphInTheChooserOrTheCitation() {
        val root = Files.createTempDirectory("marked-page-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(Files.writeString(root.resolve("marked.md"), TEXT))
        val page = book.pages.single()
        assertEquals(5, page.number, "页号取自标记里的书页号")
        assertEquals(MARK, page.text.lineSequence().first().trim())

        val blocks = StudyCoachCourse.blocks(book.id, book.pages)
        val paragraphs = blocks.filter { it.isBodyParagraph }
        assertEquals(listOf("Alpha light.", "Beta water."), paragraphs.map { it.text }, "标记和章标题都不是正文段")
        assertTrue(blocks.any { it.text.trim() == MARK }, "标记仍然在块里，只是不数进段号")
        // 讲解出处：第二段是这一页的第 2 段，不是第 3 段。
        assertEquals("第 5 页 · 第 2 段", bookCitationLabel(book, blocks, Citation("S1", paragraphs[1].id, 5)))

        runSkikoComposeUiTest(size = Size(900f, 800f), density = Density(1f)) {
            setContent { CoachTheme(false) { BookPassageAsk(book, page, UnconnectedBookLearningSupport) {} } }
            // 选段行只有两段正文；标记不占一行，也没有第三行。
            onNodeWithTag("ask-paragraph-1").assertExists()
            onNodeWithTag("ask-paragraph-2").assertDoesNotExist()
            // 行首依次是 1、2，和讲解出处里的段号同源。
            onNodeWithTag("ask-paragraph-0").performClick()
            onNodeWithTag("book-selected-passage-number").assertTextEquals("1")
            onNodeWithTag("book-selected-passage").assertTextEquals("Alpha light.")
            onNodeWithTag("ask-paragraph-1").performClick()
            onNodeWithTag("book-selected-passage-number").assertTextEquals("2")
            onNodeWithTag("book-selected-passage").assertTextEquals("Beta water.")
            // 浅色真渲染留一张，交付时看选中行的段号。
            val file = OUT.resolve("chooser-marked-page-light.png")
            Files.createDirectories(file.parent)
            assertTrue(ImageIO.write(captureToImage().toAwtImage(), "png", file.toFile()))
        }
        root.toFile().deleteRecursively()
    }

    private companion object {
        val OUT: Path = Path.of("build/screens/ton1659")
        const val MARK = "<!-- PDF 第 5 页 · 书第 1 页 -->"
        val TEXT = """
            $MARK
            # 1 Light

            Alpha light.

            Beta water.
        """.trimIndent()
    }
}
