package com.coach.desktop.books

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
 * 简答的依据是整节正文（StudyCoachCourse.practice 给短答传 lesson.text）。
 * 一节的正文常常分成多个 SourceBlock，只有把这些块按 lesson.text 的同一方式拼起来才能定位；
 * 要求整段依据挤进单个块会 0 命中，读者只能停在 firstPage 并误报“未找到”。
 */
@OptIn(ExperimentalTestApi::class)
class PracticeSourceMultiBlockTest {
    @Test fun shortAnswerSourceAnchorsTheBodyBlockInsteadOfTheFirstPage() {
        val root = Files.createTempDirectory("coach-practice-source-")
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(multiBlockTextbook(root.resolve("synthetic"), listOf(
            "Chlorophyll is a green pigment that absorbs light in plant cells.",
            "Water moves through the leaf along the xylem and out through the stomata.",
        )))
        library.save(imported.copy(readingPage = 1, lessonIndex = 0, learning = true, questionMode = QuestionMode.SHORT_ANSWER))
        val controller = TextbookController(library)
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            onNodeWithTag("home-learn").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }

            val book = controller.state.value.book!!
            val lesson = book.lessons[book.lessonIndex]
            val practice = StudyCoachCourse.practice(book, lesson, QuestionMode.SHORT_ANSWER)!!
            assertEquals(lesson.text, practice.reference, "简答依据是整节正文")
            val body = lesson.blocks.filterNot { it.heading }
            assertTrue(body.size >= 2, "本节必须有多块正文才能复现这个缺陷")
            val targetPage = body.single { it.text.startsWith("Chlorophyll") }.page
            assertNotEquals(lesson.firstPage, targetPage, "目标正文不在首块页，才能区分“回首页”")

            onNodeWithTag("short-answer").performScrollTo().performTextInput("我的草稿 mid")
            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && !controller.state.value.busy }

            waitUntil(timeoutMillis = 5_000) { controller.state.value.page == targetPage }
            assertEquals(targetPage, controller.state.value.page, "应落在目标正文所在页")
            onAllNodesWithTag("source-anchor-status").assertCountEquals(0)
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithContentDescription("${book.title} 第 $targetPage 页").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("返回学习").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            assertEquals(lesson.id, controller.state.value.book!!.lessons[controller.state.value.book!!.lessonIndex].id, "仍是同一题")
            onNodeWithTag("short-answer").assertTextContains("我的草稿 mid")
            onNodeWithText(practice.prompt).assertExists()
            assertEquals(1, library.book(book.id).readingPage, "出处浏览不改读书书签")
        }
    }

    @Test fun repeatedReferenceStaysUnconfirmedInsteadOfGuessingABlock() {
        val repeated = "Cells keep water inside the central vacuole."
        val root = Files.createTempDirectory("coach-practice-source-")
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(multiBlockTextbook(root.resolve("synthetic"), listOf(repeated, repeated)))
        library.save(imported.copy(readingPage = 1, lessonIndex = 0, learning = true, questionMode = QuestionMode.CHOICE))
        val controller = TextbookController(library)
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            onNodeWithTag("home-learn").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }

            val book = controller.state.value.book!!
            val lesson = book.lessons[book.lessonIndex]
            assertEquals(2, lesson.blocks.count { it.text == repeated }, "同一句必须真的出现两次")
            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && !controller.state.value.busy }

            assertEquals(lesson.firstPage, controller.state.value.page, "重复句不猜块、不跳页")
            onNodeWithTag("source-anchor-status").assertTextEquals("无法确认原句：本题依据出现多次。")
        }
    }
}

private fun multiBlockTextbook(directory: Path, body: List<String>): Path {
    Files.createDirectories(directory)
    val file = directory.resolve("A multi-block biology textbook.pdf")
    PDDocument().use { pdf ->
        pdf.documentInformation.title = "A multi-block biology textbook"
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        fun page(heading: String?, paragraphs: List<String>) {
            val page = PDPage(); pdf.addPage(page)
            PDPageContentStream(pdf, page).use { out ->
                var y = 710f
                if (heading != null) {
                    out.beginText(); out.setFont(font, 18f); out.newLineAtOffset(50f, y); out.showText(heading); out.endText()
                    y -= 48f
                }
                for (paragraph in paragraphs) {
                    out.beginText(); out.setFont(font, 11f); out.newLineAtOffset(50f, y); out.showText(paragraph); out.endText()
                    y -= 100f
                }
            }
        }
        page("Chapter 1 Cells", emptyList())
        page(null, body)
        page("Chapter 2 Water", listOf("Transpiration pulls water from the roots up to the leaves."))
        pdf.save(file.toFile())
    }
    return file
}
