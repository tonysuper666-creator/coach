package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.coach.ui.CoachTheme
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class BookNavigationTest {
    @Test
    fun navigationUsesTypedTocFragmentAndBodyLandmark() = withTempBook { epub, library ->
        writeEpub(epub, """
            <nav epub:type="page-list"><ol><li><a href="../body.xhtml#second">Page 2</a></li></ol></nav>
            <nav epub:type="toc"><ol>
                <li><a href="../body.xhtml#first">Chapter I</a></li>
                <li><a href="../body.xhtml#second">Chapter II</a></li>
            </ol></nav>
            <nav epub:type="landmarks"><ol>
                <li><a epub:type="bodymatter" href="../body.xhtml#first">Start</a></li>
            </ol></nav>
        """.trimIndent())
        val book = library.importBook(epub)
        val nodes = book.tocNodes
        assertEquals(listOf("Chapter I", "Chapter II"), nodes.map { it.title })
        assertEquals(listOf(2, 2), nodes.map { it.startPage })
        assertTrue(nodes[1].startOffset > nodes[0].startOffset)
        assertEquals(BookPageAnchor(2, nodes[0].startOffset), book.bodyStart)
        for (node in nodes) {
            val title = BookChapterResolver.chapterForPage(book, node.startPage, node.startOffset)?.title
            assertEquals(node.title, title)
        }
        library.saveBodyStart(book.id, 1)
        val reopened = TextbookLibrary(library.directory).book(book.id)
        assertEquals(1, reopened.correctedBodyPage)
        assertEquals(BookPageAnchor(1), reopened.bodyStart)
        assertEquals(nodes, reopened.tocNodes)
        library.saveBodyStart(book.id, null)
        assertEquals(book.bodyStart, library.book(book.id).bodyStart)
    }

    @Test
    fun ncxDestinationsUseMemberPathsAndGuide() = withTempBook { epub, library ->
        writeEpub(epub, nav = null)
        val book = library.importBook(epub)
        val node = book.tocNodes.single()
        assertEquals("NCX chapter", node.title)
        assertEquals(2, node.startPage)
        assertTrue(node.startOffset > 0)
        assertEquals(BookPageAnchor(2, node.startOffset), book.bodyStart)
    }

    @Test
    fun singlePdfBookmarkWinsOverPrintedAndInferredHeadings() {
        val pages = listOf(BookPage(1, "Copyright. All rights reserved.")) +
            (2..5).map { BookPage(it, "Chapter $it\n\n" + "A normal paragraph describing the subject. ".repeat(30)) }
        val outline = listOf(BookOutlineItem("Main text", 1, 2, HeadingSource.PDF_OUTLINE))
        val structure = BookStructurePlanner.plan("pdf", "Book", "pdf", pages, outline)
        val book = Textbook("pdf", "Book", "pdf", pages, structure = structure)
        assertEquals(HeadingSource.PDF_OUTLINE.name, structure.source)
        assertEquals(listOf("Main text"), book.tocNodes.map { it.title })
        assertEquals(2, book.bodyStart.importPage)
        assertNull(BookChapterResolver.chapterForPage(book, 1))
        assertEquals("第 3 / 5 页 · 本章还剩 2 页", BookBoundaries.pageStatus(3, 5, BookChapterResolver.chapterForPage(book, 3)))
        assertEquals("第 5 / 5 页 · 本章完", BookBoundaries.pageStatus(5, 5, BookChapterResolver.chapterForPage(book, 5)))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tocShowsOriginalChaptersAndOnlyDwellEvidenceLightsDot() = withTempBook { epub, library ->
        writeEpub(epub, """
            <nav epub:type="toc"><ol>
                <li><a href="../body.xhtml#first">Chapter I</a></li>
                <li><a href="../body.xhtml#second">Chapter II</a></li>
            </ol></nav>
        """.trimIndent())
        val book = library.importBook(epub)
        for (dark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(420f, 520f), density = Density(1f)) {
                setContent {
                    CoachTheme(dark) {
                        Box(Modifier.fillMaxSize()) {
                            ImmersiveTocDrawer(
                                visible = true, availableWidth = 420.dp, book = book,
                                currentPage = book.readingPage, currentAnchor = book.bodyStart,
                                currentChapterId = book.tocNodes[0].id, bodyStart = book.bodyStart,
                                bodyStartCorrected = false, bookmarks = emptyList(), busy = false,
                                onJumpPage = {}, onJumpToc = {}, onToggleBookmark = {},
                                onSaveBodyStart = {}, onReorganizeSections = null, onClose = {},
                                readChapterIds = setOf(book.tocNodes[1].id),
                            )
                        }
                    }
                }
                onNodeWithText("共 2 章，正文从第 2 页开始").assertExists()
                onNodeWithTag("toc-read-dot-0", useUnmergedTree = true)
                    .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "未读"))
                onNodeWithTag("toc-read-dot-1", useUnmergedTree = true)
                    .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已读"))
                onNodeWithText("标为已读").assertDoesNotExist()
                val directory = System.getenv("COACH_TON1821_EVIDENCE")?.let(Path::of)
                if (directory != null) {
                    Files.createDirectories(directory)
                    val theme = if (dark) "dark" else "light"
                    ImageIO.write(onNodeWithTag("immersive-toc-drawer").captureToImage().toAwtImage(), "png",
                        directory.resolve("toc-$theme.png").toFile())
                }
                onNodeWithTag("toc-edit-structure").performClick()
                onNodeWithTag("toc-body-start-field").assertExists()
            }
        }
    }

    private fun withTempBook(block: (Path, TextbookLibrary) -> Unit) {
        val root = Files.createTempDirectory("coach-native-navigation-")
        try {
            block(root.resolve("test.epub"), TextbookLibrary(root.resolve("library")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun writeEpub(path: Path, nav: String?) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            fun add(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            add("META-INF/container.xml", "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>")
            val navItem = if (nav == null) "" else "<item id='nav' href='nav/toc.xhtml' properties='nav'/>"
            val guide = if (nav == null) "<guide><reference type='text' href='body.xhtml#second'/></guide>" else ""
            add("OPS/book.opf", """
                <package>
                  <metadata><title>A synthetic book</title></metadata>
                  <manifest>
                    <item id="cover" href="cover.xhtml"/>
                    <item id="body" href="body.xhtml"/>
                    <item id="ncx" href="nav/toc.ncx" media-type="application/x-dtbncx+xml"/>
                    $navItem
                  </manifest>
                  <spine toc="ncx"><itemref idref="cover"/><itemref idref="body"/></spine>
                  $guide
                </package>
            """.trimIndent())
            add("OPS/cover.xhtml", "<html><body><p>Copyright. All rights reserved.</p></body></html>")
            add("OPS/body.xhtml", """
                <html><body>
                  <p>A brief separate prelude.</p>
                  <h2 id="first">Repeated heading</h2>
                  <p>First text with sufficient content to have a source span.</p>
                  <h2 id="second">Repeated heading</h2>
                  <p>Second text with sufficient content to have a source span.</p>
                </body></html>
            """.trimIndent())
            val ncx = """
                <ncx><navMap><navPoint><navLabel><text>NCX chapter</text></navLabel>
                  <content src="../body.xhtml#second"/>
                </navPoint></navMap></ncx>
            """.trimIndent()
            add("OPS/nav/toc.ncx", ncx)
            if (nav != null) add("OPS/nav/toc.xhtml", """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>$nav</body></html>""")
        }
    }
}
