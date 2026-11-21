package com.coach.desktop.books

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

@OptIn(ExperimentalTestApi::class)
class ProgressivePdfImportTest {
    @Test
    fun tier0OpensArbitraryPageAndTocImmediatelyThenTier1AndTier2CompleteCleanly() = runBlocking {
        val root = Files.createTempDirectory("coach-tier0-import-")
        try {
            val pdfPath = buildThirtyPagePdf(root.resolve("handbook.pdf"), withDiagramOnPage = 5)
            val library = TextbookLibrary(root.resolve("library"), streamPdfExtras = true, lazyPdfExtras = true)

            val staged = library.stageBookImportTier0(pdfPath)
            assertFalse(staged.alreadyExisted)
            val tier0 = staged.book
            assertEquals("TIER0", tier0.indexState)
            assertEquals(30, tier0.pages.size)
            assertTrue("Tier 0 must populate canonical TOC from PDF outline immediately", tier0.tocNodes.size >= 4)
            assertEquals(30, library.pdfPageAspects(tier0).size)

            // Arbitrary unindexed page still renders immediately via PDFBox original file.
            val bitmap25 = library.renderPdf(tier0, 25, pixelWidth = 600)
            assertTrue(bitmap25.width > 0 && bitmap25.height > 0)

            // Tier 0 search already covers the opening pages before Tier 1 finishes.
            val earlyHits = library.searchBook(tier0.id, "Opening Anchor")
            assertEquals(1, earlyHits.hits.size)
            assertEquals(1, earlyHits.hits.single().page)

            // Simulate user reading on page 17 before Tier 1 finishes.
            library.save(tier0.copy(readingPage = 17))

            val ready = library.indexBookTier1(tier0.id)
            assertEquals("READY", ready.indexState)
            assertEquals("Tier 1 must never overwrite reading_page", 17, ready.readingPage)

            val fullHits = library.searchBook(ready.id, "Deep Anchor TwentyFive")
            assertEquals(1, fullHits.hits.size)
            assertEquals(25, fullHits.hits.single().page)
            val shortFallback = library.searchBook(ready.id, "Q9")
            assertTrue(shortFallback.hits.isNotEmpty())

            // Tier 2 lazy visual extraction only extracts requested pages and stays idempotent.
            assertTrue(ready.page(5)!!.illustrations.isEmpty())
            val extractedFirst = library.ensurePdfVisualsForPages(ready.id, listOf(5, 6))
            assertTrue(extractedFirst)
            val afterVisuals = library.book(ready.id)
            assertTrue("Expected lazy visual on page 5", afterVisuals.page(5)!!.illustrations.isNotEmpty())
            assertFalse(library.ensurePdfVisualsForPages(ready.id, listOf(5, 6)))

            // LRU text layer cache and clean book deletion across all v2 tables.
            assertNotNull(library.pdfTextLayer(afterVisuals, afterVisuals.page(1)!!))
            library.deleteBook(ready.id)
            assertTrue(library.catalog().isEmpty())
            assertTrue(library.searchBook(ready.id, "Opening Anchor").hits.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun controllerRecoversInterruptedTier0OnRestartAndNeverFlashesEmptyShelf() {
        val root = Files.createTempDirectory("coach-tier0-restart-")
        try {
            val pdfPath = buildThirtyPagePdf(root.resolve("resumable.pdf"), withDiagramOnPage = 5)
            val libDir = root.resolve("library")
            val library1 = TextbookLibrary(libDir, streamPdfExtras = true, lazyPdfExtras = true)
            val staged = library1.stageBookImportTier0(pdfPath)
            assertEquals("TIER0", staged.book.indexState)

            // Fresh library/controller simulates app restart before Tier 1 completed.
            val library2 = TextbookLibrary(libDir, streamPdfExtras = true, lazyPdfExtras = true)
            val controller2 = TextbookController(library2)
            runBlocking { controller2.load() }

            val deadline = System.nanoTime() + 15_000_000_000L
            while (controller2.state.value.book?.indexState != "READY") {
                assertTrue("Timed out resuming Tier 1 after restart", System.nanoTime() < deadline)
                Thread.sleep(50)
            }
            assertTrue(library2.searchBook(staged.book.id, "Deep Anchor TwentyFive").hits.isNotEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun workspaceShowsLoadingPlaceholderInsteadOfEmptyShelfWhileLoadIsPending() = runComposeUiTest {
        val root = Files.createTempDirectory("coach-no-flash-")
        try {
            val libDir = root.resolve("library")
            val library = TextbookLibrary(libDir, streamPdfExtras = true, lazyPdfExtras = true)
            val controller = TextbookController(library)
            val lockConn = DriverManager.getConnection("jdbc:sqlite:${libDir.resolve("library.sqlite")}")
            lockConn.createStatement().use { it.execute("BEGIN EXCLUSIVE") }
            try {
                setContent {
                    TextbookWorkspace(
                        controller = controller,
                        loadOnMount = true,
                        chooseBook = { null },
                    )
                }
                waitForIdle()
                assertEquals(1, onAllNodesWithTag("library-loading").fetchSemanticsNodes().size)
                assertEquals(0, onAllNodesWithText("书架还是空的", substring = true).fetchSemanticsNodes().size)
            } finally {
                runCatching { lockConn.createStatement().use { it.execute("COMMIT") } }
                lockConn.close()
            }
            waitUntil(timeoutMillis = 5_000) { controller.state.value.initialized }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun windowsHostShowsLoadingPlaceholderAndZeroEmptyShelfFlashBeforeInitialLoadFinishes() = runComposeUiTest {
        val root = Files.createTempDirectory("coach-host-no-flash-")
        try {
            val libDir = root.resolve("library")
            val library = TextbookLibrary(libDir, streamPdfExtras = true, lazyPdfExtras = true)
            val controller = TextbookController(library)
            setContent {
                TextbookWorkspace(
                    controller = controller,
                    loadOnMount = false,
                    chooseBook = { null },
                )
            }
            waitForIdle()
            assertEquals(1, onAllNodesWithTag("library-loading").fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithText("书架还是空的", substring = true).fetchSemanticsNodes().size)
            runBlocking { controller.load() }
            waitUntil(timeoutMillis = 5_000) { controller.state.value.initialized }
            assertEquals(0, onAllNodesWithTag("library-loading").fetchSemanticsNodes().size)
            assertEquals(1, onAllNodesWithText("书架还是空的", substring = true).fetchSemanticsNodes().size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun buildThirtyPagePdf(target: Path, withDiagramOnPage: Int): Path {
        PDDocument().use { pdf ->
            pdf.documentInformation.title = "Progressive Import Sample"
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
            val outline = PDDocumentOutline()
            pdf.documentCatalog.documentOutline = outline

            val diagramImage = BufferedImage(360, 220, BufferedImage.TYPE_INT_RGB).apply {
                val g = createGraphics()
                g.color = Color.WHITE
                g.fillRect(0, 0, width, height)
                g.color = Color.BLUE
                g.drawRect(20, 20, 320, 180)
                g.drawLine(30, 180, 300, 40)
                g.dispose()
            }
            val pdImage = LosslessFactory.createFromImage(pdf, diagramImage)

            val chapterStarts = mapOf(
                1 to "1 Foundations of Economics",
                8 to "2 Supply and Demand",
                17 to "3 Market Equilibrium",
                24 to "4 Welfare and Efficiency",
            )
            var currentChapterItem: PDOutlineItem? = null

            for (p in 1..30) {
                val page = PDPage()
                pdf.addPage(page)
                chapterStarts[p]?.let { chTitle ->
                    val dest = PDPageFitDestination().apply { this.page = page }
                    val item = PDOutlineItem().apply { title = chTitle; destination = dest }
                    outline.addLast(item)
                    currentChapterItem = item
                }
                if (p == 5) {
                    val dest = PDPageFitDestination().apply { this.page = page }
                    currentChapterItem?.addLast(PDOutlineItem().apply { title = "1.2 Diagram Example"; destination = dest })
                }

                PDPageContentStream(pdf, page).use { out ->
                    out.beginText()
                    out.setFont(bold, 15f)
                    out.newLineAtOffset(54f, 730f)
                    out.showText(chapterStarts[p] ?: "Section on page $p")
                    out.newLineAtOffset(0f, -26f)
                    out.setFont(font, 11f)
                    when (p) {
                        1 -> out.showText("Opening Anchor explains fundamental scarcity and incentives.")
                        25 -> out.showText("Deep Anchor TwentyFive verifies background Tier 1 full-book FTS5 indexing Q9.")
                        else -> out.showText("Standard textbook prose on page $p covering equilibrium analysis.")
                    }
                    out.endText()
                    if (p == withDiagramOnPage) {
                        out.drawImage(pdImage, 72f, 360f, 360f, 220f)
                    }
                }
            }
            pdf.save(target.toFile())
        }
        return target
    }
}
