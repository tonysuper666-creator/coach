package com.coach.desktop

import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.awt.ComposeWindow
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Assume.assumeTrue
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class QaGuideQuestionCrossReviewTest {
    @Test
    fun savingGuideQuestionWithDroppedPdfThenChangingTypeToPastPaper() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("qa-guide-question-loss-")
        try {
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
            ReadingSettings(library.directory).chooseAiExplain(true)
            val refs = listOf("Textbook", "Slides").map { role ->
                val md = root.resolve("$role.md").also {
                    Files.writeString(it, "## Axial tilt\nA synthetic $role explanation of Axial tilt.")
                }
                library.stageBookImport(md).book.id
            }
            val pdf1 = library.importBook(LiteratureGuideFixture.pdf(root, 1))
            val pdf2 = library.importBook(LiteratureGuideFixture.pdf(root, 2))
            val courseId = library.withCourseDatabase { db ->
                val (id, _) = CourseStore.createCourse(
                    db,
                    "Astronomy",
                    CourseExamProfile(examEnabled = true),
                    refs + listOf(pdf1.id, pdf2.id),
                )
                CourseAutoAligner.alignLocally(db, id)
                require(CoursePastPaper.hasExamPoints(db, id)) { "Course has no exam points" }
                id
            }

            val quizPdf = root.resolve("Week 2 quiz.pdf")
            PDDocument().use { pdf ->
                val page = PDPage()
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use {
                    it.beginText()
                    it.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    it.newLineAtOffset(45f, 700f)
                    it.showText("1. Compare Axial tilt.")
                    it.newLineAtOffset(0f, -20f)
                    it.showText("Axial tilt determines how strongly solar insolation varies between summer and winter hemispheres.")
                    it.endText()
                }
                pdf.save(quizPdf.toFile())
            }

            val controller = TextbookController(library)
            val offlineExplainer = object : BookExplainer(library = library) {
                override suspend fun load() { loaded = true }
                override suspend fun courseTopicPack(input: JsonObject) =
                    CourseServerCallResult(null, "offline test stop after saveSelection")
            }
            runBlocking {
                controller.load()
                controller.openCourse(courseId)
                controller.addMaterialsToCourse(courseId, listOf(quizPdf)).join()
            }
            val intakeItem = assertNotNull(controller.state.value.courseIntake).items.single()
            assertNotNull(intakeItem.role)
            val droppedBookId = assertNotNull(intakeItem.bookId)

            val window = edt {
                ComposeWindow().apply {
                    title = "QA Cross Review"
                    setSize(1100, 800)
                    setContent { WindowsTextbookHost(controller, bookExplainer = offlineExplainer) { null } }
                    isVisible = true
                }
            }
            try {
                waitFor("course page") { node(window, "course-literature-guide") != null }
                click(window, "course-literature-guide")

                fun editAction(n: SemanticsNode): ((AnnotatedString) -> Boolean)? =
                    n.config.getOrNull(SemanticsActions.SetText)?.action ?: n.children.firstNotNullOfOrNull(::editAction)

                waitFor("guide-questions ready") {
                    edt { node(window, "guide-questions")?.let(::editAction) != null }
                }
                val writtenQuestion = "Why does Axial tilt change seasonal insolation?"
                edt {
                    assertTrue(editAction(node(window, "guide-questions")!!)!!.invoke(AnnotatedString(writtenQuestion)))
                }
                waitFor("guide-generate enabled (notice=${runCatching { text(window, "guide-notice") }.getOrNull()})") {
                    edt {
                        val n = node(window, "guide-generate")
                        n != null && !n.config.contains(SemanticsProperties.Disabled)
                    }
                }
                click(window, "guide-generate")
                waitFor("saveSelection persisted (notice=${runCatching { text(window, "guide-notice") }.getOrNull()})") {
                    library.withCourseDatabase { db ->
                        LiteratureGuideStore.latest(db, courseId)?.questions == listOf(writtenQuestion)
                    }
                }
                val rowsBefore = library.withCourseDatabase { db ->
                    db.createStatement().use { st ->
                        st.executeQuery("SELECT COUNT(*) FROM literature_guides WHERE course='$courseId'").use {
                            if (it.next()) it.getInt(1) else 0
                        }
                    }
                }

                // Go back to course and change dropped PDF type to PAST_PAPER
                val backBtn = edt {
                    fun hasText(n: SemanticsNode, needle: String): Boolean =
                        n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { needle in it.text } ||
                            n.children.any { hasText(it, needle) }
                    nodes(window).firstOrNull { n ->
                        n.config.getOrNull(SemanticsActions.OnClick) != null && hasText(n, "← 课程")
                    }
                }
                assertNotNull(backBtn, "back to course button not found")
                edt { backBtn.config[SemanticsActions.OnClick].action!!.invoke() }
                waitFor("intake receipt visible") { node(window, "course-intake-type-Week 2 quiz.pdf") != null }

                click(window, "course-intake-type-Week 2 quiz.pdf")
                waitFor("choice-PAST_PAPER visible") { node(window, "course-intake-choice-PAST_PAPER") != null }
                click(window, "course-intake-choice-PAST_PAPER")
                waitFor("type changed to PAST_PAPER (notice=${controller.state.value.courseNotice})") {
                    !controller.state.value.busy && text(window, "course-intake-type-Week 2 quiz.pdf") == "往年题 ▾"
                }

                val rowsAfter = library.withCourseDatabase { db ->
                    db.createStatement().use { st ->
                        st.executeQuery("SELECT COUNT(*) FROM literature_guides WHERE course='$courseId'").use {
                            if (it.next()) it.getInt(1) else 0
                        }
                    }
                }
                val latestAfter = library.withCourseDatabase { db -> LiteratureGuideStore.latest(db, courseId) }

                // Re-open literature guide and check whether the written question is still present
                click(window, "course-literature-guide")
                waitFor("guide-questions visible again") { node(window, "guide-questions") != null }
                Thread.sleep(400)
                val uiQuestionsAfter = text(window, "guide-questions")
                val dbDeleted = rowsBefore == 1 && rowsAfter == 0 && latestAfter == null
                val uiPreserved = writtenQuestion in uiQuestionsAfter
                println(
                    "CROSS_REVIEW droppedBookId=$droppedBookId rowsBefore=$rowsBefore rowsAfter=$rowsAfter " +
                        "latestQuestionsAfter=${latestAfter?.questions} uiPreserved=$uiPreserved uiQuestionsAfter='$uiQuestionsAfter'",
                )
                assertFalse(dbDeleted, "Expected question preserved, but literature_guides row was deleted")
                assertTrue(uiPreserved, "Expected written question preserved in UI, got '$uiQuestionsAfter'")
            } finally {
                edt { window.dispose() }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun nodes(window: ComposeWindow): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return window.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun node(window: ComposeWindow, tag: String) =
        edt { nodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag } }
    private fun text(window: ComposeWindow, tag: String): String = edt {
        fun collect(n: SemanticsNode): List<String> =
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                n.config.getOrNull(SemanticsProperties.EditableText)?.text?.let(::listOf).orEmpty() +
                n.children.flatMap(::collect)
        collect(requireNotNull(nodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }) { tag })
            .joinToString(" ")
    }
    private fun click(window: ComposeWindow, tag: String) {
        waitFor(tag) { node(window, tag) != null }
        edt { assertTrue(node(window, tag)!!.config[SemanticsActions.OnClick].action!!.invoke(), tag) }
        Thread.sleep(250)
    }
    private fun waitFor(label: String, timeoutSeconds: Long = 15, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        while (!runCatching(condition).getOrDefault(false)) {
            check(System.nanoTime() < deadline) { "Timed out waiting for: $label" }
            Thread.sleep(50)
        }
    }
    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var res: Result<T>? = null
        SwingUtilities.invokeAndWait { res = runCatching(block) }
        return res!!.getOrThrow()
    }
}
