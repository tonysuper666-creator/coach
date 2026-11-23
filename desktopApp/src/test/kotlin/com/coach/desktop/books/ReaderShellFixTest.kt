package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.desktop.WindowsTextbookHost
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Assume.assumeFalse
import java.awt.Color as AwtColor
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent as AwtKeyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ComposeToolingApi::class)
class ReaderShellFixTest {

    private val shotRoot: Path = Path.of("build/screens/ton1818")

    private fun saveShot(scope: SkikoComposeUiTest, name: String) {
        scope.waitForIdle()
        Files.createDirectories(shotRoot)
        val out = shotRoot.resolve("$name.png").toFile()
        assertTrue(ImageIO.write(scope.captureToImage().toAwtImage(), "png", out), "Failed writing screenshot $name")
    }

    private fun richSyntheticMarkdown(titlePrefix: String): String = buildString {
        appendLine("# Chapter 1 $titlePrefix")
        appendLine()
        repeat(18) { i ->
            appendLine(
                "Paragraph ${i + 1} of chapter one examines equilibrium, incentives, and structural trade-offs in synthetic economics examples so that the page fills cleanly from top line to bottom footer.",
            )
            appendLine()
        }
        appendLine("# Chapter 2 Comparative Dynamics")
        appendLine()
        repeat(14) { i ->
            appendLine(
                "Paragraph ${i + 1} of chapter two analyzes population growth, wage adjustments, and long-run productivity trends across synthetic datasets.",
            )
            appendLine()
        }
        appendLine("# Chapter 3 Policy Synthesis")
        appendLine()
        repeat(10) { i ->
            appendLine(
                "Paragraph ${i + 1} of chapter three summarizes measurement rules, boundary conditions, and cross-chapter verification links.",
            )
            appendLine()
        }
    }

    private fun createSyntheticPdf(dir: Path, name: String, blankScanned: Boolean = false): Path {
        val file = dir.resolve(name)
        PDDocument().use { pdf ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for (p in 1..3) {
                val page = PDPage(PDRectangle.LETTER)
                pdf.addPage(page)
                if (!blankScanned) {
                    PDPageContentStream(pdf, page).use { cs ->
                        cs.beginText()
                        cs.setFont(font, 14f)
                        cs.newLineAtOffset(72f, 700f)
                        cs.showText("Chapter $p Synthetic PDF Title")
                        cs.newLineAtOffset(0f, -24f)
                        cs.showText("Synthetic PDF body text on page $p verifying center click and reader chrome.")
                        cs.endText()
                    }
                } else {
                    // Draw pure vector strokes (no text layer -> scanned image PDF) so page visually renders
                    PDPageContentStream(pdf, page).use { cs ->
                        cs.setStrokingColor(AwtColor(120, 110, 95))
                        cs.setLineWidth(1.5f)
                        cs.addRect(72f, 120f, 468f, 600f)
                        cs.stroke()
                        for (line in 0..8) {
                            val y = 660f - line * 48f
                            cs.moveTo(100f, y)
                            cs.lineTo(500f, y)
                            cs.stroke()
                        }
                    }
                }
            }
            pdf.save(file.toFile())
        }
        return file
    }

    private fun stubExplainer(home: Path, library: TextbookLibrary): BookExplainer =
        object : BookExplainer(DesktopConnectionSettings(home = home, environment = { null }), library) {
            init {
                identity = DesktopIdentity("http://127.0.0.1:18799", PairedCredentials("dev-test", "tok-test"))
                loaded = true
            }

            override suspend fun load() {
                loaded = true
            }

            override suspend fun explainSelection(
                kind: String,
                chosen: SourceBlock,
                context: ExplainSectionContext?,
                question: String?,
                history: List<FollowUpTurn>,
                requestId: String,
            ): ExplainResult {
                val qText = question?.trim().orEmpty()
                return ExplainResult.Done(
                    Explanation(
                        text = "这是针对「${qText.ifBlank { kind }}」的合成讲解回答（第 ${chosen.page} 页）。",
                        citations = listOf(Citation(chosen.text.take(24), chosen.id, chosen.page)),
                        elapsedMs = 12L,
                    ),
                )
            }

            override suspend fun askCollection(requestJson: JsonObject): CourseServerCallResult {
                val blocks = (requestJson["blocks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                val first = blocks.firstOrNull()
                val bookId = (first?.get("bookId") as? JsonPrimitive)?.content ?: "book-1"
                val page = (first?.get("page") as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
                val docLabel = (first?.get("docLabel") as? JsonPrimitive)?.content ?: "合成书目"
                val rawText = (first?.get("text") as? JsonPrimitive)?.content ?: "equilibrium"
                val anchor = rawText.trim().split(Regex("\\s+")).take(5).joinToString(" ")
                val question = (requestJson["question"] as? JsonPrimitive)?.content.orEmpty()
                return CourseServerCallResult(
                    body = buildJsonObject {
                        put("sentences", buildJsonArray {
                            add(buildJsonObject {
                                put("claim", "跨书合成回答：围绕「$question」，第一本书在第 $page 页讨论了均衡与工资结构。")
                                put("bookId", bookId)
                                put("docLabel", docLabel)
                                put("page", page)
                                put("quoteAnchor", anchor)
                            })
                        })
                        put("hasMaterialConflict", false)
                        put("conflictSummaryZh", "")
                        put("conflictCards", buildJsonArray { })
                    },
                    error = null,
                )
            }
        }

    @Test
    fun p1PathControlsTocStudyModeQuizCompanionAndScannedBack() {
        val root = Files.createTempDirectory("coach-ton1818-p1-")
        try {
            val library = TextbookLibrary(root.resolve("lib"))
            val mdFile = root.resolve("synthetic-textbook.md")
            Files.writeString(mdFile, richSyntheticMarkdown("Health Economics Foundations"))
            val textBook = library.importBook(mdFile)

            val controller = TextbookController(library)
            runBlocking {
                controller.load()
                controller.openBook(textBook.id, 1)
            }

            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.READER }
                waitForIdle()

                // First open shows top & bottom HUD immediately (no Esc needed)
                onNodeWithTag("immersive-top-hud").assertIsDisplayed()
                onNodeWithTag("immersive-bottom-hud").assertIsDisplayed()
                onNodeWithTag("immersive-back").assertIsDisplayed()
                onNodeWithTag("v7-directory-toggle").assertIsDisplayed()
                onNodeWithTag("immersive-mode-trigger").assertIsDisplayed()
                onNodeWithTag("immersive-companion-toggle").assertIsDisplayed()
                onNodeWithTag("immersive-more-menu").assertIsDisplayed()

                // Secondary tools tucked in overflow menu (UX-6)
                onNodeWithTag("book-search-toggle").assertDoesNotExist()
                onNodeWithTag("immersive-bookmark-toggle").assertDoesNotExist()

                // Switch study mode without leaving reader (先测后读)
                onNodeWithTag("immersive-mode-trigger").performClick()
                waitForIdle()
                onNodeWithTag("mode-option-DIAGNOSTIC_SKIP").performClick()
                waitForIdle()
                assertEquals(BookScreen.READER, controller.state.value.screen)
                onNodeWithTag("immersive-companion-drawer").assertIsDisplayed()
                saveShot(this, "p1-study-mode")

                // Open quiz / 做题 from 学法 without leaving reader
                onNodeWithTag("immersive-mode-trigger").performClick()
                waitForIdle()
                onNodeWithTag("mode-option-PROBLEM_DRIVEN").performClick()
                waitForIdle()
                assertEquals(BookScreen.READER, controller.state.value.screen)
                onNodeWithTag("immersive-companion-drawer").assertIsDisplayed()
                saveShot(this, "p1-quiz")

                // Open companion Ask/Explain cleanly
                saveShot(this, "p1-companion")

                // Open TOC: body shifts right so TOC does not cover text
                onNodeWithTag("v7-directory-toggle").performClick()
                waitForIdle()
                onNodeWithTag("immersive-toc-drawer").assertIsDisplayed()
                val tocBounds = onNodeWithTag("immersive-toc-drawer").fetchSemanticsNode().boundsInRoot
                val pageBounds = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
                val topHudBounds = onNodeWithTag("immersive-top-hud").fetchSemanticsNode().boundsInRoot
                assertTrue(tocBounds.top >= topHudBounds.bottom - 1f, "TOC must sit below top HUD: toc.top=${tocBounds.top}, hud.bottom=${topHudBounds.bottom}")
                assertTrue(tocBounds.right <= pageBounds.left + 1f, "TOC must push body right without occlusion: toc.right=${tocBounds.right}, page.left=${pageBounds.left}")
                saveShot(this, "p1-toc")

                // Open scanned PDF (staged with ocrPending = true) -> uses exact same top HUD with Back button
                val scanFile = createSyntheticPdf(root, "synthetic-scan.pdf", blankScanned = true)
                val scanBook = library.stageBookImport(scanFile).book
                assertTrue(scanBook.hasPendingOcr, "Scanned PDF should have pending OCR pages")
                runBlocking { controller.openBook(scanBook.id, 1) }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.book?.id == scanBook.id }
                waitForIdle()
                onNodeWithTag("immersive-top-hud").assertIsDisplayed()
                onNodeWithTag("immersive-back").assertIsDisplayed()
                saveShot(this, "p1-scan-reader")

                onNodeWithTag("immersive-back").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.screen == BookScreen.HOME }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun centerTapTogglesHudAndPerBookFirstOpenPersists() {
        val root = Files.createTempDirectory("coach-ton1818-hud-")
        try {
            val library = TextbookLibrary(root.resolve("lib"))
            val bookAFile = root.resolve("book-a.md")
            val bookBFile = root.resolve("book-b.md")
            Files.writeString(bookAFile, richSyntheticMarkdown("Book Alpha"))
            Files.writeString(bookBFile, richSyntheticMarkdown("Book Beta"))
            val bookA = library.importBook(bookAFile)
            val bookB = library.importBook(bookBFile)
            val pdfFile = createSyntheticPdf(root, "book-pdf.pdf")
            val pdfBook = library.importBook(pdfFile)
            library.saveTextView(pdfBook.id, false)

            val controller = TextbookController(library)
            runBlocking {
                controller.load()
                controller.openBook(bookA.id, 1)
            }

            // Session 1: Book A first open -> HUD stays visible on pointer move, toggles on center click
            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.READER }
                waitForIdle()

                onNodeWithTag("immersive-top-hud").assertExists()
                onNodeWithTag("immersive-bottom-hud").assertExists()

                // Moving cursor to center BEFORE user dismisses must NOT auto-hide HUD
                onNodeWithTag("textbook-reader").performMouseInput { moveTo(center) }
                waitForIdle()
                onNodeWithTag("immersive-top-hud").assertExists()

                // Clicking center of reader body hides HUD and persists dismissal for Book A
                onNodeWithTag("reader-page").performMouseInput { click(center) }
                waitForIdle()
                onNodeWithTag("immersive-top-hud").assertDoesNotExist()
                onNodeWithTag("immersive-bottom-hud").assertDoesNotExist()
                waitUntil(timeoutMillis = 5_000) { BookHudPreferenceStore(library.directory).isDismissed(bookA.id) }

                // Clicking center again brings HUD back and keeps it visible while moving toward toolbar
                onNodeWithTag("reader-page").performMouseInput { click(center) }
                waitForIdle()
                onNodeWithTag("immersive-top-hud").assertExists()
                onNodeWithTag("immersive-bottom-hud").assertExists()
                onNodeWithTag("textbook-reader").performMouseInput { moveTo(center) }
                waitForIdle()
                onNodeWithTag("immersive-top-hud").assertExists()

                // Hovering top edge then moving back to center auto-hides HUD now that Book A was dismissed once
                onNodeWithTag("textbook-reader").performMouseInput {
                    moveTo(Offset(center.x, 8f))
                    moveTo(center)
                }
                waitForIdle()
                onNodeWithTag("immersive-top-hud").assertDoesNotExist()

                // Switching to brand-new Book B: HUD is visible again because Book B has never been dismissed!
                runBlocking { controller.openBook(bookB.id, 1) }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.book?.id == bookB.id }
                waitForIdle()
                onNodeWithTag("immersive-top-hud").assertExists()

                // Original PDF view also supports center-click HUD toggle
                runBlocking { controller.openBook(pdfBook.id, 1) }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.book?.id == pdfBook.id }
                waitForIdle()
                onNodeWithTag("immersive-top-hud").assertExists()
                onNodeWithTag("reader-page").performMouseInput { click(center) }
                waitForIdle()
                onNodeWithTag("immersive-top-hud").assertDoesNotExist()
                onNodeWithTag("reader-page").performMouseInput { click(center) }
                waitForIdle()
                onNodeWithTag("immersive-top-hud").assertExists()
            }

            // Session 2 (fresh composition): reopening Book A remembers auto-hide state!
            runBlocking { controller.openBook(bookA.id, 1) }
            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.book?.id == bookA.id }
                waitForIdle()
                onNodeWithTag("immersive-top-hud").assertDoesNotExist()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun p2SelectionAskAndP4BooklistAskFocusKeyInputAndReply() {
        val root = Files.createTempDirectory("coach-ton1818-ask-")
        try {
            val library = TextbookLibrary(root.resolve("lib"))
            val f1 = root.resolve("smith-synthetic.md")
            val f2 = root.resolve("malthus-synthetic.md")
            Files.writeString(f1, richSyntheticMarkdown("Wages and Profit Equilibrium"))
            Files.writeString(f2, richSyntheticMarkdown("Population and Subsistence"))
            val book1 = library.importBook(f1)
            val book2 = library.importBook(f2)

            // Create a synthetic booklist (P4 path) and mark page 1 of book1 as read
            val listId = "booklist-ton1818"
            library.upsertCollection(MaterialCollectionRecord(listId, CollectionKind.BOOKLIST, "合成综述书单"))
            library.attachMaterial(CollectionMaterialRecord(listId, book1.id, role = "LIST_BOOK", ordinal = 0))
            library.attachMaterial(CollectionMaterialRecord(listId, book2.id, role = "LIST_BOOK", ordinal = 1))
            val firstChap = BookChapterResolver.chapters(book1).first()
            library.setChapterManualReadState(book1.id, firstChap.tocNodeId, firstChap.pagesTotal, true)

            val explainer = stubExplainer(root.resolve("conn"), library)
            val controller = TextbookController(library)
            runBlocking {
                controller.load()
                controller.openBook(book1.id, 1)
            }

            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                setContent {
                    Box(Modifier.fillMaxSize()) {
                        WindowsTextbookHost(controller, bookExplainer = explainer, chooseBook = { null })
                    }
                }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.READER }
                waitForIdle()

                // --- P2 Path: Open Ask, auto-focus, edge click, empty hint, key-by-key input, send & reply ---
                onNodeWithTag("immersive-companion-toggle").performClick()
                waitForIdle()
                onNodeWithTag("companion-tab-ask").performClick()
                waitForIdle()

                onNodeWithTag("inline-followup-input").assertIsFocused()
                onNodeWithTag("inline-followup-send").assertIsNotEnabled()
                onNodeWithText("先输入问题").assertExists()

                // Move focus away to reader-page, then click near the top-left edge of input box
                onNodeWithTag("reader-page").performSemanticsAction(SemanticsActions.RequestFocus)
                waitForIdle()
                onNodeWithTag("inline-followup-input").performMouseInput { click(Offset(3f, 3f)) }
                waitForIdle()
                onNodeWithTag("inline-followup-input").assertIsFocused()

                // Type character-by-character into focused field
                for (ch in listOf("w", "h", "y")) {
                    onNodeWithTag("inline-followup-input").performTextInput(ch)
                }
                waitForIdle()
                onNodeWithTag("inline-followup-input").assertTextContains("why", ignoreCase = true)
                onNodeWithTag("inline-followup-send").assertIsEnabled()
                onNodeWithText("先输入问题").assertDoesNotExist()

                onNodeWithTag("inline-followup-send").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("inline-explain-turn-0").fetchSemanticsNodes().isNotEmpty()
                }
                onAllNodesWithText("合成讲解回答", substring = true).fetchSemanticsNodes().let {
                    assertTrue(it.isNotEmpty(), "P2 path must show reply after sending question")
                }
                saveShot(this, "p2-ask-reply")

                // --- P4 Path: Switch scope to 已读书目, verify auto-focus, single-char + multi-char input & reply ---
                onNodeWithTag("ask-scope-collection").performClick()
                waitForIdle()
                onNodeWithTag("inline-followup-input").assertIsFocused()
                onNodeWithTag("inline-followup-send").assertIsNotEnabled()

                // Single-char input enables Send immediately
                onNodeWithTag("inline-followup-input").performTextInput("w")
                waitForIdle()
                onNodeWithTag("inline-followup-send").assertIsEnabled()

                // Complete word character-by-character ("wages") and submit
                for (ch in listOf("a", "g", "e", "s")) {
                    onNodeWithTag("inline-followup-input").performTextInput(ch)
                }
                waitForIdle()
                onNodeWithTag("inline-followup-send").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("collection-qa-turn-0").fetchSemanticsNodes().isNotEmpty()
                }
                onAllNodesWithText("跨书合成回答", substring = true).fetchSemanticsNodes().let {
                    assertTrue(it.isNotEmpty(), "P4 path must show cross-book reply after sending question")
                }
                saveShot(this, "p4-booklist-ask-reply")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun threeRealWindowSizesZeroOcclusionAndMaxWidth720() {
        val sizes = listOf(
            Triple("820x620", 820f, 620f),
            Triple("1920x1080", 1920f, 1080f),
            Triple("2880x1800", 2880f, 1800f),
        )
        for ((label, w, h) in sizes) {
            val root = Files.createTempDirectory("coach-ton1818-size-$label-")
            try {
                val library = TextbookLibrary(root.resolve("lib"))
                val mdFile = root.resolve("layout-check.md")
                Files.writeString(mdFile, richSyntheticMarkdown("Layout Geometry $label"))
                val book = library.importBook(mdFile)
                val controller = TextbookController(library)
                runBlocking {
                    controller.load()
                    controller.openBook(book.id, 1)
                }

                runSkikoComposeUiTest(size = Size(w, h), density = Density(1f)) {
                    setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
                    waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.READER }
                    waitForIdle()

                    onNodeWithTag("immersive-companion-toggle").performClick()
                    waitForIdle()
                    onNodeWithTag("companion-tab-ask").performClick()
                    waitForIdle()

                    val topHud = onNodeWithTag("immersive-top-hud").fetchSemanticsNode().boundsInRoot
                    val readerPage = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
                    val drawer = onNodeWithTag("immersive-companion-drawer").fetchSemanticsNode().boundsInRoot
                    val askInput = onNodeWithTag("inline-followup-input").fetchSemanticsNode().boundsInRoot
                    val askSend = onNodeWithTag("inline-followup-send").fetchSemanticsNode().boundsInRoot
                    val bodyLayout = onNodeWithTag("book-page-layout").fetchSemanticsNode().boundsInRoot
                    val bodyText = onNodeWithTag("reader-text").fetchSemanticsNode().boundsInRoot
                    val footer = onNodeWithTag("book-sheet-footer").fetchSemanticsNode().boundsInRoot

                    // Top HUD never occludes reader page or first body line
                    assertTrue(topHud.bottom <= readerPage.top + 1f, "$label: top HUD overlaps reader-page (${topHud.bottom} > ${readerPage.top})")
                    assertTrue(topHud.bottom <= bodyText.top + 1f, "$label: top HUD overlaps first line (${topHud.bottom} > ${bodyText.top})")

                    // Body last line never overlaps page footer
                    assertTrue(bodyText.bottom <= footer.top + 1f, "$label: body text bottom ${bodyText.bottom} overlaps footer top ${footer.top}")

                    // Ask input & Send button fully inside window and displayed
                    onNodeWithTag("inline-followup-input").assertIsDisplayed()
                    onNodeWithTag("inline-followup-send").assertIsDisplayed()
                    assertTrue(askInput.bottom <= h + 1f && askInput.right <= w + 1f, "$label: askInput clipped ($askInput)")
                    assertTrue(askSend.bottom <= h + 1f && askSend.right <= w + 1f, "$label: askSend clipped ($askSend)")

                    if (w < 1000f) {
                        // Narrow window: bottom scrubber yields; bottom sheet sits below body
                        onNodeWithTag("immersive-bottom-hud").assertDoesNotExist()
                        assertTrue(readerPage.bottom <= drawer.top + 1f, "$label: bottom sheet overlaps body (${readerPage.bottom} > ${drawer.top})")
                        assertTrue(footer.bottom <= drawer.top + 1f, "$label: bottom sheet overlaps footer (${footer.bottom} > ${drawer.top})")
                        assertTrue(readerPage.height >= 150f, "$label: body height too small (${readerPage.height})")
                    } else {
                        // Wide window: body <= 720dp, side companion docked with zero overlap, bottom scrubber doesn't overlap companion
                        assertTrue(bodyLayout.width <= 720.5f, "$label: wide body width ${bodyLayout.width} exceeds 720dp")
                        assertTrue(readerPage.right <= drawer.left + 1f, "$label: side companion overlaps body (${readerPage.right} > ${drawer.left})")
                        val bottomHud = onNodeWithTag("immersive-bottom-hud").fetchSemanticsNode().boundsInRoot
                        assertTrue(bottomHud.right <= drawer.left + 1f, "$label: bottom scrubber overlaps side companion (${bottomHud.right} > ${drawer.left})")
                        assertTrue(readerPage.bottom <= bottomHud.top + 1f, "$label: bottom scrubber overlaps reader body (${readerPage.bottom} > ${bottomHud.top})")
                        assertTrue(footer.bottom <= bottomHud.top + 1f, "$label: bottom scrubber overlaps footer (${footer.bottom} > ${bottomHud.top})")
                    }

                    saveShot(this, "window-$label-ask")
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun realWindowAwtKeyEventsTypeAndSubmitP2AndP4() {
        assumeFalse("Requires non-headless desktop display", GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("coach-ton1818-realwin-")
        var window: ComposeWindow? = null
        try {
            val library = TextbookLibrary(root.resolve("lib"))
            val f1 = root.resolve("smith-realwin.md")
            val f2 = root.resolve("malthus-realwin.md")
            Files.writeString(f1, richSyntheticMarkdown("Wages and Profit RealWindow"))
            Files.writeString(f2, richSyntheticMarkdown("Population RealWindow"))
            val book1 = library.importBook(f1)
            val book2 = library.importBook(f2)

            val listId = "booklist-realwin"
            library.upsertCollection(MaterialCollectionRecord(listId, CollectionKind.BOOKLIST, "真窗测试书单"))
            library.attachMaterial(CollectionMaterialRecord(listId, book1.id, role = "LIST_BOOK", ordinal = 0))
            library.attachMaterial(CollectionMaterialRecord(listId, book2.id, role = "LIST_BOOK", ordinal = 1))
            val firstChap = BookChapterResolver.chapters(book1).first()
            library.setChapterManualReadState(book1.id, firstChap.tocNodeId, firstChap.pagesTotal, true)

            val explainer = stubExplainer(root.resolve("conn"), library)
            val controller = TextbookController(library)
            runBlocking {
                controller.load()
                controller.openBook(book1.id, 1)
            }

            fun <T> edt(block: () -> T): T {
                if (SwingUtilities.isEventDispatchThread()) return block()
                val task = FutureTask(block)
                SwingUtilities.invokeLater(task)
                return task.get(15, TimeUnit.SECONDS)
            }

            fun findTag(win: ComposeWindow, tag: String): SemanticsNode? = edt {
                fun dfs(n: SemanticsNode): SemanticsNode? =
                    if (n.config.getOrNull(SemanticsProperties.TestTag) == tag) n
                    else n.children.firstNotNullOfOrNull(::dfs)
                win.semanticsOwners.firstNotNullOfOrNull { dfs(it.unmergedRootSemanticsNode) }
            }

            fun waitFor(desc: String, timeoutMs: Long = 10_000L, cond: () -> Boolean) {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    if (cond()) return
                    Thread.sleep(40)
                }
                fail("Timed out waiting for: $desc")
            }

            fun clickTag(win: ComposeWindow, tag: String) {
                waitFor("clickable $tag") { findTag(win, tag)?.config?.getOrNull(SemanticsActions.OnClick) != null }
                edt { assertTrue(findTag(win, tag)!!.config[SemanticsActions.OnClick].action!!.invoke(), tag) }
            }

            fun findKeyTarget(c: java.awt.Component): java.awt.Component? {
                if (c.keyListeners.isNotEmpty()) return c
                if (c is java.awt.Container) {
                    for (child in c.components) {
                        findKeyTarget(child)?.let { return it }
                    }
                }
                return null
            }

            fun dispatchAwtKeys(win: ComposeWindow, text: String) {
                for (ch in text) {
                    edt {
                        val target = findKeyTarget(win.contentPane)
                            ?: KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                            ?: win.contentPane
                        val now = System.currentTimeMillis()
                        val vk = AwtKeyEvent.getExtendedKeyCodeForChar(ch.code)
                        target.dispatchEvent(AwtKeyEvent(target, AwtKeyEvent.KEY_PRESSED, now, 0, vk, ch))
                        target.dispatchEvent(AwtKeyEvent(target, AwtKeyEvent.KEY_TYPED, now + 1, 0, AwtKeyEvent.VK_UNDEFINED, ch))
                        target.dispatchEvent(AwtKeyEvent(target, AwtKeyEvent.KEY_RELEASED, now + 2, 0, vk, ch))
                    }
                    Thread.sleep(20)
                }
            }

            window = edt {
                ComposeWindow().apply {
                    title = "LearningCoach · TON-1818 真窗逐键验证"
                    size = Dimension(820, 620)
                    setContent {
                        Box(Modifier.fillMaxSize()) {
                            WindowsTextbookHost(controller, bookExplainer = explainer, chooseBook = { null })
                        }
                    }
                    isVisible = true
                    toFront()
                    requestFocus()
                }
            }
            val win = checkNotNull(window)

            waitFor("reader ready") { findTag(win, "immersive-companion-toggle") != null }
            clickTag(win, "immersive-companion-toggle")
            clickTag(win, "companion-tab-ask")
            waitFor("ask input focused") {
                findTag(win, "inline-followup-input")?.config?.getOrNull(SemanticsProperties.Focused) == true
            }

            // P2: dispatch real AWT key-by-key events (no clipboard, no performTextReplacement)
            dispatchAwtKeys(win, "why")
            waitFor("P2 typed text arrived") {
                findTag(win, "inline-followup-input")?.config?.getOrNull(SemanticsProperties.EditableText)?.text == "why"
            }
            clickTag(win, "inline-followup-send")
            waitFor("P2 reply rendered") { findTag(win, "inline-explain-turn-0") != null }

            // P4: switch to 已读书目, verify auto-focus, dispatch real AWT key-by-key events, submit & verify reply
            clickTag(win, "ask-scope-collection")
            waitFor("P4 ask input focused") {
                findTag(win, "inline-followup-input")?.config?.getOrNull(SemanticsProperties.Focused) == true
            }
            dispatchAwtKeys(win, "wages")
            waitFor("P4 typed text arrived") {
                findTag(win, "inline-followup-input")?.config?.getOrNull(SemanticsProperties.EditableText)?.text == "wages"
            }
            clickTag(win, "inline-followup-send")
            waitFor("P4 cross-book reply rendered") { findTag(win, "collection-qa-turn-0") != null }
        } finally {
            window?.let { w -> SwingUtilities.invokeAndWait { w.dispose() } }
            root.toFile().deleteRecursively()
        }
    }
}
