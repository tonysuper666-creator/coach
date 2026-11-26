package com.coach.desktop.books

import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.coach.desktop.WindowsTextbookHost
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class LiteratureFollowWindowTest {
    @Test fun followJumpHighlightsTheSentenceInLight() = verify(PaperColor.LIGHT)

    @Test fun followJumpHighlightsTheSentenceInDark() = verify(PaperColor.DARK)

    private fun verify(paper: PaperColor) {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("coach-follow-window-")
        val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
        val book = library.importBook(headingPdf(root))
        val course = library.withCourseDatabase { db ->
            CourseStore.createCourse(db, "历史文献", CourseExamProfile(), listOf(book.id)).first
        }
        val controller = TextbookController(library)
        ReadingSettings(library.directory).choosePaper(paper)
        val explainer = object : BookExplainer(library = library) {
            override suspend fun load() { loaded = true }
        }
        val window = edt {
            ComposeWindow().apply {
                title = "Coach · 文献跟读"
                setSize(1080, 780)
                setContent { WindowsTextbookHost(controller, bookExplainer = explainer) { null } }
                isVisible = true
            }
        }
        try {
            waitFor("library loaded", window) { controller.state.value.catalog.size == 1 }
            runBlocking { controller.openCourse(course) }
            click(window, "course-section-follow")
            waitFor("follow sections ready", window) { node(window, "follow-ready") != null }
            val rendered = edt {
                nodes(window).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            }
            assertTrue(rendered.any { "Introduction" in it })
            assertTrue(rendered.none { "印刷第" in it || "印刷页码" in it })
            val literature = library.literatureBook(book.id)
            val sentences = LiteratureSections.build(literature, LiteraturePassages.extractCandidates(literature))
                .first().sentences
            click(window, "follow-section-${book.id}-0")
            waitFor("reader highlights the section sentence", window) {
                val state = controller.state.value
                state.screen == BookScreen.READER && state.literatureQuoteHighlight != null
            }
            val highlight = assertNotNull(controller.state.value.literatureQuoteHighlight)
            val readerPage = requireNotNull(library.book(book.id).page(highlight.page))
            assertEquals(readerPage.text.substring(highlight.start, highlight.end), highlight.text)
            val acceptable = sentences.map { LiteratureTextNorm.normalize(it.quote) }.toSet()
            assertTrue(LiteratureTextNorm.normalize(highlight.text) in acceptable)
        } finally {
            edt { window.dispose() }
            root.toFile().deleteRecursively()
        }
    }

    private fun headingPdf(root: java.nio.file.Path): java.nio.file.Path {
        val path = root.resolve("Heading_Window.pdf")
        org.apache.pdfbox.pdmodel.PDDocument().use { document ->
            document.documentInformation.title = "Heading Study"
            val titles = listOf("Introduction", "The Argument", "Conclusion")
            val bodies = listOf(
                "The keeper argued that the repository preserved provenance across decades.",
                "However the catalog argued that evidence confirmed a narrower claim about custody.",
                "The final inventory claimed that the collection remained intact after the move.",
            )
            titles.indices.forEach { index ->
                val sheet = org.apache.pdfbox.pdmodel.PDPage()
                document.addPage(sheet)
                val font = org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA)
                org.apache.pdfbox.pdmodel.PDPageContentStream(document, sheet).use { stream ->
                    listOf("The Historical Journal" to 740f, titles[index] to 700f, bodies[index] to 660f).forEach { (text, y) ->
                        stream.beginText()
                        stream.setFont(font, 12f)
                        stream.newLineAtOffset(72f, y)
                        stream.showText(text)
                        stream.endText()
                    }
                }
            }
            document.save(path.toFile())
        }
        return path
    }

    private fun nodes(window: ComposeWindow): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        val owners = window.semanticsOwners + ownedDialogs(window).flatMap { it.semanticsOwners }
        return owners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }

    private fun ownedDialogs(window: java.awt.Window): List<ComposeDialog> =
        window.ownedWindows.filter { it.isVisible }.flatMap { listOfNotNull(it as? ComposeDialog) + ownedDialogs(it) }

    private fun node(window: ComposeWindow, tag: String): SemanticsNode? =
        edt { nodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag } }

    private fun click(window: ComposeWindow, tag: String) {
        waitFor(tag, window) { node(window, tag) != null }
        edt {
            val target = requireNotNull(node(window, tag)) { "Missing node $tag" }
            assertTrue(target.config[SemanticsActions.OnClick].action!!.invoke(), "Click failed on $tag")
        }
        Thread.sleep(40)
    }

    private fun waitFor(label: String, window: ComposeWindow, timeoutSeconds: Long = 20, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        while (true) {
            edt { runCatching { window.renderImmediately() } }
            if (condition()) return
            check(System.nanoTime() < deadline) {
                val tags = runCatching {
                    edt { nodes(window).mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.distinct().take(40) }
                }.getOrNull()
                "Timed out waiting for: $label; tags=$tags"
            }
            Thread.sleep(40)
        }
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
