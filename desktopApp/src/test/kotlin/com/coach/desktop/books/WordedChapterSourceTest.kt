package com.coach.desktop.books

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*
import com.coach.desktop.reader.LocalBooksReaderSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

internal const val WORDED_BODY = "Photosynthesis turns light into stored chemical energy."

internal fun wordedChapterPdf(directory: Path): Path {
    Files.createDirectories(directory)
    val file = directory.resolve("worded-chapters.pdf")
    PDDocument().use { pdf ->
        pdf.documentInformation.title = "Self made field notes"
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        for ((heading, text) in listOf(
            "Chapter One - Light" to WORDED_BODY,
            "Chapter Two - Cells" to "Chlorophyll is the green pigment inside plant cells.",
            "Chapter Three - Energy" to "Glucose is a fuel that stores energy for living cells.",
        )) {
            val page = PDPage(); pdf.addPage(page)
            PDPageContentStream(pdf, page).use {
                it.beginText(); it.setFont(font, 18f); it.newLineAtOffset(50f, 710f); it.showText(heading)
                it.newLineAtOffset(0f, -48f); it.setFont(font, 11f); it.showText(text); it.endText()
            }
        }
        pdf.save(file.toFile())
    }
    return file
}

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class WordedChapterSourceTest {
    @Test fun sourceQuoteRejectsTitlesWithoutDroppingBodyOrGuessingOtherPages() {
        fun lesson(text: String) = BookLesson("existing-owner", "Existing lesson",
            listOf(SourceBlock("existing-block", 1, 0, text.length, false, text)))
        assertEquals(WORDED_BODY, StudyCoachCourse.sourceQuote(lesson("Chapter One - Light\n$WORDED_BODY"), 1))
        assertEquals(WORDED_BODY, StudyCoachCourse.sourceQuote(lesson("SECTION Two: Cells\n$WORDED_BODY"), 1))
        assertNull(StudyCoachCourse.sourceQuote(lesson("Chapter One - Light"), 1))
        val prose = "Chapter one explains how plants store light."
        assertEquals(prose, StudyCoachCourse.sourceQuote(lesson(prose), 1))
        assertNull(StudyCoachCourse.sourceQuote(lesson(WORDED_BODY), 2))
    }

    @Test fun studyUsesExactBodyAndKeepsTheExistingQuestionDefinition() = runBlocking {
        val root = Files.createTempDirectory("worded-study-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(wordedChapterPdf(root))
        val lesson = book.lessons.first()
        val before = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)
        assertEquals("Chlorophyll", before?.answer, "The existing lesson/question must not be re-planned by a reader quote fix")
        val reply = LocalBooksReaderSource(library).study(book.id, 1)
        assertEquals(200, reply.status)
        val question = reply.body!!["questions"]!!.jsonArray.first().jsonObject
        assertEquals(WORDED_BODY, question["anchor"]!!.jsonPrimitive.content)
        assertEquals(lesson.id, question["id"]!!.jsonPrimitive.content)
        assertEquals(before, StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE))
    }

    @Test fun sourceHighlightMustEqualBodySentenceForChapterOne() {
        val root = Files.createTempDirectory("worded-chapter-source-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(wordedChapterPdf(root))
        library.save(book.copy(learning = true, readingPage = 3, questionMode = QuestionMode.NONE))
        val controller = TextbookController(library)
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            onNodeWithTag("home-learn").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING }
            val owner = controller.state.value.book!!.lessons[0].id
            onNodeWithTag("open-lesson-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("remote-highlight").fetchSemanticsNodes().size == 1 }
            val highlight = onNodeWithTag("remote-highlight")
            println("ACTUAL_SOURCE_QUOTE=" + highlight.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
            System.getProperty("heading.evidence")?.let { directory ->
                Files.createDirectories(Path.of(directory))
                ImageIO.write(onNodeWithTag("windows-textbooks").captureToImage().toAwtImage(), "png", Path.of(directory, "compose-source.png").toFile())
            }
            highlight.assertContentDescriptionEquals("原句：$WORDED_BODY")
            onNodeWithText("返回学习").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING }
            assertEquals(owner, controller.state.value.book!!.lessons[0].id)
            assertEquals(3, TextbookLibrary(library.directory).book(book.id).readingPage)
        }
    }
}