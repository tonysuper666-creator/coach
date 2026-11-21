package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.coach.desktop.WindowsTextbookHost
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ImmersiveReaderV2Test {

    private fun createTempBookLibrary(): Pair<TextbookLibrary, Textbook> {
        val dir = Files.createTempDirectory("coach-v2-reader-test")
        val library = TextbookLibrary(dir)
        val md = buildString {
            appendLine("# Chapter 1 Thermal Conduction")
            appendLine()
            appendLine("Thermal conduction transfers internal energy through microscopic collisions of particles across a temperature gradient.")
            appendLine()
            repeat(6) { idx ->
                appendLine("Paragraph $idx of chapter one explains conductivity, boundary layers, and steady-state heat flux in detail.")
                appendLine()
            }
            appendLine("# Chapter 2 Wave Optics")
            appendLine()
            appendLine("Interference and diffraction patterns emerge whenever coherent waves pass through narrow apertures.")
            appendLine()
            repeat(6) { idx ->
                appendLine("Section 2.$idx discusses phase shifts, path length differences, gratings, and resolving power.")
                appendLine()
            }
            appendLine("# Chapter 3 Fluid Dynamics")
            appendLine()
            appendLine("Conservation of mass and momentum governs laminar and turbulent flow through tapered pipes.")
        }
        val file = dir.resolve("sample-physics.md")
        Files.writeString(file, md)
        val book = library.importBook(file)
        return library to book
    }

    @Test
    fun bookmarkStorePersistsAcrossInstances() {
        val dir = Files.createTempDirectory("coach-bookmarks-test")
        val store1 = BookBookmarkStore(dir)
        assertTrue(store1.list("book-a").isEmpty())
        store1.add("book-a", 34, "1.1 Thermal Conduction")
        store1.add("book-a", 173, "8.1 Fluid Dynamics")
        assertEquals(listOf(34, 173), store1.list("book-a").map { it.page })

        val store2 = BookBookmarkStore(dir)
        assertEquals(listOf(34, 173), store2.list("book-a").map { it.page })
        assertEquals("8.1 Fluid Dynamics", store2.list("book-a")[1].note)

        assertFalse(store2.toggle("book-a", 34))
        assertEquals(listOf(173), store2.list("book-a").map { it.page })
        assertTrue(store2.toggle("book-a", 42, "Chapter 2"))
        assertEquals(listOf(42, 173), store2.list("book-a").map { it.page })
    }

    @Test
    fun bitmapCacheBoundsSizeAndPrefetchesNextBeforePrev() {
        val cache = OriginalPdfBitmapCache(maxEntries = 3, maxBytes = 1024L * 1024L * 16L)
        fun bmp(w: Int = 100, h: Int = 100) = ImageBitmap(w, h)
        cache.put(PdfBitmapCacheKey("b", 1, 800), bmp())
        cache.put(PdfBitmapCacheKey("b", 2, 800), bmp())
        cache.put(PdfBitmapCacheKey("b", 3, 800), bmp())
        assertEquals(3, cache.size())
        // Access page 1 so page 2 becomes LRU
        assertNotNull(cache.get(PdfBitmapCacheKey("b", 1, 800)))
        cache.put(PdfBitmapCacheKey("b", 4, 800), bmp())
        assertEquals(3, cache.size())
        assertNull(cache.get(PdfBitmapCacheKey("b", 2, 800)))
        assertNotNull(cache.get(PdfBitmapCacheKey("b", 1, 800)))
        assertNotNull(cache.get(PdfBitmapCacheKey("b", 4, 800)))

        val sheets = listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6), listOf(7, 8))
        assertEquals(
            listOf(5, 6, 1, 2),
            adjacentPdfPrefetchPages((1..8).toList(), sheets, currentPage = 3, scrolling = false),
        )
        assertEquals(
            listOf(4, 2),
            adjacentPdfPrefetchPages((1..8).toList(), sheets, currentPage = 3, scrolling = true),
        )
    }

    @Test
    fun companionPresentationMatchesThresholds() {
        assertEquals(CompanionPresentation.WIDE_SPLIT, companionPresentationForWidth(1920f))
        assertEquals(CompanionPresentation.WIDE_SPLIT, companionPresentationForWidth(1280f))
        assertEquals(CompanionPresentation.NARROW_OVERLAY, companionPresentationForWidth(1279f))
        assertEquals(CompanionPresentation.NARROW_OVERLAY, companionPresentationForWidth(1000f))
        assertEquals(CompanionPresentation.BOTTOM_SHEET, companionPresentationForWidth(999f))
        assertEquals(CompanionPresentation.BOTTOM_SHEET, companionPresentationForWidth(752f))
        assertEquals(CompanionPresentation.BOTTOM_SHEET, companionPresentationForWidth(390f))
    }

    @Test
    fun importEntersReaderDirectlyWithoutPromptAndRestartRestoresAnchor() = runBlocking {
        val dir = Files.createTempDirectory("coach-v2-controller-test")
        val library = TextbookLibrary(dir)
        val controller = TextbookController(library)
        assertFalse(controller.state.value.initialized)
        controller.load()
        assertTrue(controller.state.value.initialized)

        val md = (1..5).joinToString("\n\n") { i -> "# Chapter $i\n\nBody paragraph for chapter $i with enough words to be readable." }
        val file = dir.resolve("book.md")
        Files.writeString(file, md)
        controller.importBook(file)

        val afterImport = controller.state.value
        assertEquals(BookScreen.READER, afterImport.screen)
        assertNull(afterImport.prompt, "2.0 removes BookPrompt.IMPORTED three-choice dialog")
        val book = assertNotNull(afterImport.book)
        val targetPage = book.pages.last().number
        controller.page(targetPage)
        assertEquals(targetPage, controller.state.value.page)
        assertEquals(targetPage, controller.state.value.book?.readingPage)
        assertFalse(controller.state.value.busy, "Page jump must not lock UI in busy state")
        controller.flushPendingSaves()

        // Relaunch with fresh controller on same SQLite directory
        val relaunched = TextbookController(TextbookLibrary(dir))
        assertFalse(relaunched.state.value.initialized)
        relaunched.load()
        assertTrue(relaunched.state.value.initialized)
        assertEquals(BookScreen.READER, relaunched.state.value.screen)
        assertEquals(targetPage, relaunched.state.value.page)
        assertNull(relaunched.state.value.prompt)
    }

    @Test
    fun wideScreenSplitPaneHasZeroOcclusionAndSmallWindowUsesBottomSheet() = runSkikoComposeUiTest(
        size = Size(1440f, 900f),
        density = Density(1f),
    ) {
        val (library, book) = createTempBookLibrary()
        val controller = TextbookController(library)
        runBlocking {
            controller.load()
            controller.read()
        }
        setContent {
            Box(Modifier.fillMaxSize()) {
                WindowsTextbookHost(controller, chooseBook = { null })
            }
        }
        waitForIdle()
        // Pure-read: left rail hidden, Desk v7-paper absent, viewport >= 95%
        onNodeWithTag("host-navigation").assertDoesNotExist()
        onNodeWithTag("v7-paper").assertDoesNotExist()
        onNodeWithTag("immersive-progress-hairline").assertExists()

        val readerBounds = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
        val hostBounds = onNodeWithTag("windows-host").fetchSemanticsNode().boundsInRoot
        val viewportRatio = (readerBounds.width * readerBounds.height) / (hostBounds.width * hostBounds.height)
        assertTrue(viewportRatio >= 0.95f, "Pure-read viewport ratio $viewportRatio must be >= 0.95")

        // Press L to open companion drawer -> docked split-pane on >=1280dp, 0px overlap
        onNodeWithTag("textbook-reader").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.L) }
        waitForIdle()
        onNodeWithTag("immersive-companion-drawer").assertExists()
        val pageAfterDock = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
        val drawerBounds = onNodeWithTag("immersive-companion-drawer").fetchSemanticsNode().boundsInRoot
        assertTrue(pageAfterDock.right <= drawerBounds.left + 1f, "Split-pane must not occlude reader body: page.right=${pageAfterDock.right}, drawer.left=${drawerBounds.left}")
        val bodyShare = (pageAfterDock.width * pageAfterDock.height) / (hostBounds.width * hostBounds.height)
        assertTrue(bodyShare >= 0.65f, "Split-pane body share $bodyShare must be >= 0.65")

        // Switch to Ask tab: input box & send button immediately visible without scrolling
        onNodeWithTag("companion-tab-ask").performClick()
        waitForIdle()
        onNodeWithTag("inline-followup-input").assertIsDisplayed()
        onNodeWithTag("inline-followup-send").assertIsDisplayed()

        // Press T to open TOC drawer: non-empty & clickable
        onNodeWithTag("textbook-reader").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.T) }
        waitForIdle()
        onNodeWithTag("v7-directory-drawer").assertExists()
        onNodeWithTag("toc-item-0").assertExists()
    }

    @Test
    fun fiveConsecutivePdfTextSwitchesPreserveAnchorAndSyncHeader() = runSkikoComposeUiTest(
        size = Size(1440f, 900f),
        density = Density(1f),
    ) {
        val dir = Files.createTempDirectory("coach-v2-pdf-switch")
        val pdfFile = dir.resolve("synthetic-6p.pdf")
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for (p in 1..6) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 14f)
                    cs.newLineAtOffset(60f, 700f)
                    cs.showText("Chapter $p Synthetic Topic on Page $p")
                    cs.newLineAtOffset(0f, -24f)
                    cs.showText("Synthetic physics page $p body text explaining conduction and equilibrium.")
                    cs.endText()
                }
            }
            doc.save(pdfFile.toFile())
        }
        val library = TextbookLibrary(dir.resolve("lib"))
        val controller = TextbookController(library)
        runBlocking {
            controller.load()
            controller.importBook(pdfFile)
            controller.page(4)
        }
        setContent {
            Box(Modifier.fillMaxSize()) {
                WindowsTextbookHost(controller, chooseBook = { null })
            }
        }
        waitForIdle()
        assertEquals(4, controller.state.value.page)
        // Toggle V (原页 <-> 文字) 5 times in a row without snapping back to page 1
        repeat(5) { step ->
            onNodeWithTag("textbook-reader").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.V) }
            waitForIdle()
            assertEquals(4, controller.state.value.page, "View switch #$step snapped away from page 4")
        }
    }

    @Test
    fun smallWindow752x552BottomSheetKeepsBodyVisibleAndInputPinned() = runSkikoComposeUiTest(
        size = Size(752f, 552f),
        density = Density(1f),
    ) {
        val (library, _) = createTempBookLibrary()
        val controller = TextbookController(library)
        runBlocking {
            controller.load()
            controller.read()
        }
        setContent {
            Box(Modifier.fillMaxSize()) {
                WindowsTextbookHost(controller, chooseBook = { null })
            }
        }
        waitForIdle()
        onNodeWithTag("textbook-reader").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.L) }
        waitForIdle()
        val pageBounds = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
        val drawerBounds = onNodeWithTag("immersive-companion-drawer").fetchSemanticsNode().boundsInRoot
        assertTrue(pageBounds.height >= 150f, "Small window body height ${pageBounds.height} must not collapse to zero")
        assertTrue(pageBounds.bottom <= drawerBounds.top + 1f, "Bottom drawer must dock below body: page.bottom=${pageBounds.bottom}, drawer.top=${drawerBounds.top}")

        onNodeWithTag("companion-tab-ask").performClick()
        waitForIdle()
        onNodeWithTag("inline-followup-input").assertIsDisplayed()
        onNodeWithTag("inline-followup-send").assertIsDisplayed()
    }
}
