package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
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

    @Test
    fun askOpensFocusedAndSendTracksNonBlankText() = runSkikoComposeUiTest(
        size = Size(820f, 620f), density = Density(1f),
    ) {
        val (library, _) = createTempBookLibrary()
        val controller = TextbookController(library)
        runBlocking { controller.load(); controller.read() }
        setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
        waitForIdle()
        onNodeWithTag("immersive-companion-toggle").performClick()
        onNodeWithTag("companion-tab-ask").performClick()
        waitForIdle()
        onNodeWithTag("inline-followup-input").assertIsFocused()
        onNodeWithTag("inline-followup-send").assertIsNotEnabled()
        onNodeWithText("先输入问题").assertExists()
        onNodeWithTag("reader-page").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("inline-followup-input").performMouseInput { click(Offset(3f, 3f)) }
        onNodeWithTag("inline-followup-input").assertIsFocused()
        onNodeWithTag("inline-followup-input").performTextInput("问")
        onNodeWithTag("inline-followup-send").assertIsEnabled()
    }

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
        // New books first show the controls. Enter the original pure-read state
        // explicitly before checking its existing viewport and hairline guarantees.
        onNodeWithTag("immersive-top-hud").assertExists()
        onNodeWithTag("textbook-reader").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Tab) }
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

        // Ask now takes keyboard focus. Return focus to the page before using its T shortcut.
        onNodeWithTag("reader-page").performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
        // Press T to open TOC drawer: non-empty & clickable
        onNodeWithTag("textbook-reader").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.T) }
        waitForIdle()
        onNodeWithTag("v7-directory-drawer").assertExists()
        onNodeWithTag("toc-item-0").assertExists()

        // Secondary actions (bookmark, pin HUD) are tucked inside the overflow menu, not cluttering the HUD row
        onNodeWithTag("textbook-reader").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Tab) }
        waitForIdle()
        onNodeWithTag("immersive-bookmark-toggle").assertDoesNotExist()
        onNodeWithTag("immersive-more-menu").performClick()
        waitForIdle()
        onNodeWithTag("immersive-bookmark-toggle").assertExists()
        onNodeWithTag("immersive-pin-hud").assertExists()
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

    @Test
    fun closingCompanionAfterPickingAskPassageRestoresArrowPagingKeys() = runSkikoComposeUiTest(
        size = Size(752f, 552f),
        density = Density(1f),
    ) {
        val dir = Files.createTempDirectory("coach-v2-ask-arrow-repro")
        val pdfFile = dir.resolve("synthetic-4p.pdf")
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for (p in 1..4) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 14f)
                    cs.newLineAtOffset(60f, 700f)
                    cs.showText("Chapter $p Synthetic Topic on Page $p")
                    cs.newLineAtOffset(0f, -24f)
                    cs.showText("Synthetic physics paragraph on page $p explaining thermal conductivity and boundary layers.")
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
        }
        setContent {
            Box(Modifier.fillMaxSize()) {
                WindowsTextbookHost(controller, chooseBook = { null })
            }
        }
        waitForIdle()
        assertEquals(1, controller.state.value.page)

        // Exact Reviewer-Kimi repro: 伴读 -> 提问 -> 选本页段落 -> 关闭伴读 -> 方向右键 (预期 1 -> 2)
        onNodeWithTag("textbook-reader").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.L) }
        waitForIdle()
        onNodeWithTag("companion-tab-ask").performClick()
        waitForIdle()
        onNodeWithTag("companion-pick-paragraph").performClick()
        waitForIdle()
        onNodeWithTag("ask-passage-block-0").performClick()
        waitForIdle()

        // While the input field itself IS focused, Right arrow stays inside the editor and does not flip the page.
        onNodeWithTag("inline-followup-input").performClick()
        waitForIdle()
        onNodeWithTag("inline-followup-input").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
        waitForIdle()
        assertEquals(1, controller.state.value.page, "Focused Ask input must keep arrow keys for text editing")

        // Close companion drawer -> reader regains focus automatically and Right arrow on root immediately flips forward.
        onNodeWithTag("companion-close").performClick()
        waitForIdle()
        onNodeWithTag("immersive-companion-drawer").assertDoesNotExist()
        onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
        waitForIdle()
        assertTrue(controller.state.value.page in 2..3, "Closing companion after picking ask passage must restore Right arrow paging (1 -> 2 or 3, got ${controller.state.value.page})")

        // Left arrow flips back to page 1 cleanly.
        onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionLeft) }
        waitForIdle()
        assertEquals(1, controller.state.value.page)
    }

    @Test
    fun pureReadWideTextFillsViewportAcross720DpColumnsWithSingleLayer() {
        val dir = Files.createTempDirectory("coach-v2-pure-wide-text")
        try {
            val mdFile = dir.resolve("long-book.md")
            val body = (1..260).joinToString("\n\n") { idx ->
                if (idx % 90 == 1) "# Chapter ${(idx / 90) + 1} Synthetic Topic\n\nParagraph $idx explains equilibrium, supply, demand, and empirical bounds cleanly across columns."
                else "Paragraph $idx explains equilibrium, supply, demand, and empirical bounds cleanly across columns."
            }
            Files.writeString(mdFile, body)
            val library = TextbookLibrary(dir.resolve("lib"))
            val book = library.importBook(mdFile)
            library.saveTextView(book.id, true)
            for ((label, w, h, d) in listOf(
                listOf("1920x1080", 1920f, 1080f, 1f),
                listOf("2880x1800", 2880f, 1800f, 1.5f),
            )) {
                val widthPx = w as Float
                val heightPx = h as Float
                val densityVal = d as Float
                val controller = TextbookController(library)
                runBlocking {
                    controller.load()
                    controller.openBook(book.id, 1)
                }
                runSkikoComposeUiTest(size = Size(widthPx, heightPx), density = Density(densityVal)) {
                    setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
                    waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.READER }
                    waitForIdle()
                    if (onAllNodesWithTag("immersive-top-hud").fetchSemanticsNodes().isNotEmpty()) {
                        onNodeWithTag("textbook-reader").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Tab) }
                        waitUntil(timeoutMillis = 5_000) {
                            onAllNodesWithTag("immersive-top-hud").fetchSemanticsNodes().isEmpty() &&
                                onAllNodesWithTag("immersive-bottom-hud").fetchSemanticsNodes().isEmpty()
                        }
                        waitForIdle()
                    }
                    onNodeWithTag("immersive-progress-hairline").assertExists()
                    val readerRect = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
                    val turnRect = onNodeWithTag("book-page-turn").fetchSemanticsNode().boundsInRoot
                    assertTrue(
                        turnRect.width >= readerRect.width - 4f,
                        "$label: book-page-turn width ${turnRect.width} must match reader-page width ${readerRect.width} for 1-layer pure read",
                    )
                    val sheets = listOf("book-sheet-0", "book-sheet-1", "book-sheet-2").map { tag ->
                        onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                    }
                    sheets.forEachIndexed { idx, rect ->
                        assertTrue(rect.width / densityVal <= 720.5f, "$label sheet-$idx exceeds 720dp: ${rect.width / densityVal}")
                    }
                    val textArea = listOf("reader-text", "reader-text-right", "reader-text-col-2")
                        .flatMap { onAllNodesWithTag(it).fetchSemanticsNodes() }
                        .sumOf { n -> (n.boundsInRoot.width * n.boundsInRoot.height).toDouble() }
                    val ratio = textArea / (widthPx * heightPx)
                    assertTrue(ratio >= 0.90, "$label pure-read text body ratio $ratio must be >= 0.90")

                    val beforeText = onNodeWithTag("reader-text").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
                    onNodeWithTag("reader-page").performSemanticsAction(SemanticsActions.RequestFocus)
                    onNodeWithTag("reader-page").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
                    waitForIdle()
                    val afterText = onNodeWithTag("reader-text").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
                    assertNotEquals(beforeText, afterText, "$label: DirectionRight should advance 3-column spread")
                    onNodeWithTag("reader-page").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionLeft) }
                    waitForIdle()
                    val backText = onNodeWithTag("reader-text").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
                    assertEquals(beforeText, backText, "$label: DirectionLeft should return to initial spread")
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun pureReadLaptopTextWindowsUseSingleLayerAndFillViewport() {
        val dir = Files.createTempDirectory("coach-v2-pure-laptop-text")
        try {
            val mdFile = dir.resolve("long-book.md")
            val body = (1..260).joinToString("\n\n") { idx ->
                if (idx % 90 == 1) "# Chapter ${(idx / 90) + 1} Synthetic Topic\n\nParagraph $idx explains equilibrium, supply, demand, and empirical bounds cleanly across columns."
                else "Paragraph $idx explains equilibrium, supply, demand, and empirical bounds cleanly across columns."
            }
            Files.writeString(mdFile, body)
            val library = TextbookLibrary(dir.resolve("lib"))
            val book = library.importBook(mdFile)
            library.saveTextView(book.id, true)
            // Laptop client areas matching 1128x828@150%, 1366x768@100%, 1536x864@125%, 1920x1080@150%
            val cases = listOf(
                Triple("1128x828-s150", Size(1107f, 773f), 1.5f to 1),
                Triple("1366x768-s100", Size(1350f, 729f), 1.0f to 2),
                Triple("1536x864-s125", Size(1516f, 816f), 1.25f to 2),
                Triple("1920x1080-s150", Size(1899f, 1025f), 1.5f to 2),
            )
            for ((label, sz, densityAndCols) in cases) {
                val (densityVal, expectedCols) = densityAndCols
                val controller = TextbookController(library)
                runBlocking {
                    controller.load()
                    controller.openBook(book.id, 1)
                }
                runSkikoComposeUiTest(size = sz, density = Density(densityVal)) {
                    setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
                    waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.READER }
                    waitForIdle()
                    if (onAllNodesWithTag("immersive-top-hud").fetchSemanticsNodes().isNotEmpty()) {
                        onNodeWithTag("textbook-reader").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Tab) }
                        waitUntil(timeoutMillis = 5_000) {
                            onAllNodesWithTag("immersive-top-hud").fetchSemanticsNodes().isEmpty() &&
                                onAllNodesWithTag("immersive-bottom-hud").fetchSemanticsNodes().isEmpty()
                        }
                        waitForIdle()
                    }
                    val readerRect = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
                    val turnRect = onNodeWithTag("book-page-turn").fetchSemanticsNode().boundsInRoot
                    assertTrue(
                        turnRect.width >= readerRect.width - 4f,
                        "$label: book-page-turn width ${turnRect.width} must match reader-page width ${readerRect.width} (1 layer)",
                    )
                    for (idx in 0 until expectedCols) {
                        val rect = onNodeWithTag("book-sheet-$idx").fetchSemanticsNode().boundsInRoot
                        if (expectedCols > 1) {
                            assertTrue(rect.width / densityVal <= 720.5f, "$label sheet-$idx exceeds 720dp: ${rect.width / densityVal}")
                        }
                    }
                    val textTags = listOf("reader-text", "reader-text-right", "reader-text-col-2").take(expectedCols)
                    val textArea = textTags
                        .flatMap { onAllNodesWithTag(it).fetchSemanticsNodes() }
                        .sumOf { n -> (n.boundsInRoot.width * n.boundsInRoot.height).toDouble() }
                    val clientRatio = textArea / (sz.width * sz.height)
                    println("LAPTOP_TEXT_CLIENT_RATIO $label cols=$expectedCols ratio=${"%.4f".format(clientRatio)}")
                    assertTrue(clientRatio >= 0.85, "$label pure-read text client ratio $clientRatio must be >= 0.85")
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
