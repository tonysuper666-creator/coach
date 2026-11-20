package com.coach.desktop.books

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.coach.desktop.WindowsTextbookHost
import com.coach.desktop.reader.LocalBooksReaderSource
import com.coach.desktop.reader.ReaderDraftScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextReaderParityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun phoneTextReaderTouchTurnAaReflowModeMemoryNotesHighlightsAndReopen() {
        val context = compose.activity.applicationContext
        PDFBoxResourceLoader.init(context)
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "android-text-parity-")
        val libDir = root.resolve("library")
        val sourceFile = root.resolve("Arithmetic.md").also { file ->
            val body = (1..60).joinToString("\n\n") { n ->
                "Paragraph $n. 中文与 English 混合正文，用于验证安卓文字版阅读器的翻页、滚动、字号重排与读位保存。"
            }
            Files.writeString(file, "# 第一章 阅读与证据\n\n$body")
        }
        val library1 = TextbookLibrary(libDir)
        val book = library1.importBook(sourceFile)
        library1.save(book.copy(promptSeen = true))
        val targetPhrase = "Paragraph 2. 中文与 English 混合正文"
        val hlStart = book.pages.first().text.indexOf(targetPhrase)
        assertTrue(hlStart >= 0)
        library1.setHighlight(BookPassage(book.id, 1, hlStart, hlStart + targetPhrase.length, targetPhrase), true)

        val controller1 = TextbookController(library1)
        runBlocking {
            controller1.load()
            controller1.learn(LearningPath.READ)
        }
        val activeController = mutableStateOf<TextbookController?>(controller1)
        compose.setContent {
            activeController.value?.let { controller ->
                Box(Modifier.fillMaxSize()) {
                    WindowsTextbookHost(controller) { null }
                }
            }
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty() }

        fun visibleText(): String = compose.onNodeWithTag("reader-text")
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().text

        fun bodyFontSizeSp(): Float {
            val out = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("reader-text").fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
            return out.single().layoutInput.style.fontSize.value
        }

        // 1. Initial paged view renders book text and highlight; touch swipe left turns page.
        val page0Text = visibleText()
        compose.onNodeWithTag("book-page-turn").performTouchInput {
            down(Offset(width * 0.6f, height * 0.5f))
            moveTo(Offset(width * 0.35f, height * 0.5f))
            moveTo(Offset(width * 0.1f, height * 0.5f))
            up()
        }
        compose.waitUntil(10_000) { visibleText() != page0Text }
        val page1Text = visibleText()
        assertNotEquals(page0Text, page1Text)
        compose.waitUntil(10_000) {
            val saved = library1.savedReadSpot(book.id)
            saved != null && saved.first == 1 && saved.second > 0
        }
        val spotAfterPage1 = library1.savedReadSpot(book.id)!!

        // 2. Tap right edge advances another page; spot persists to SQLite.
        compose.onNodeWithTag("book-page-turn").performTouchInput {
            down(Offset(width * 0.88f, height * 0.5f))
            up()
        }
        compose.waitUntil(10_000) { visibleText() != page1Text }
        val page2Text = visibleText()
        assertNotEquals(page1Text, page2Text)
        compose.waitUntil(10_000) {
            val saved = library1.savedReadSpot(book.id)
            saved != null && saved.first == 1 && saved.second > spotAfterPage1.second
        }
        val savedSpot = library1.savedReadSpot(book.id)!!

        // 3. Aa settings: font size LARGE immediately reflows typography without losing spot, then back to MEDIUM; KAI + DARK persist.
        val mediumFontPx = bodyFontSizeSp()
        compose.onNodeWithTag("v7-type-menu").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reading-size-LARGE").performClick()
        compose.waitUntil(10_000) { bodyFontSizeSp() > mediumFontPx + 1f }
        assertEquals(ReadingSize.LARGE, ReadingSettings(libDir).size)
        compose.onNodeWithTag("reading-font-KAI").performClick()
        compose.onNodeWithTag("reading-paper-DARK").performClick()
        compose.onNodeWithTag("reading-size-MEDIUM").performClick()
        compose.waitUntil(10_000) { kotlin.math.abs(bodyFontSizeSp() - mediumFontPx) < 0.5f }
        assertEquals(page2Text, visibleText())
        assertEquals(savedSpot, library1.savedReadSpot(book.id))

        // Close Aa popup by clicking back on reader text area? Simply toggle read mode next (dismisses popup or re-click).
        compose.onNodeWithTag("v7-type-menu").performClick()
        compose.waitForIdle()

        // 4. Switch to Scroll mode: remembered per book, survives reopen!
        compose.onNodeWithTag("read-mode").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book-scroll-read").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(library1.textScrollMode(book.id))

        // 5. Notes draft persists across reopen (matching Windows 1.1.0 LocalBooksReaderSource contract).
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("books-notes-toggle") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("books-notes-toggle").performClick()
        compose.onNodeWithTag("books-note-new").performScrollTo().performClick()
        compose.onNodeWithTag("books-note-line-0").performScrollTo().performClick()
        compose.onNodeWithTag("note-input").performScrollTo().performTextInput("Android note draft check")
        compose.onNodeWithTag("books-note-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("note-failure-reason").fetchSemanticsNodes().isNotEmpty() }

        // 6. Cold start with fresh TextbookLibrary + TextbookController: restores scroll mode, read spot, Aa settings, highlight, and note draft.
        compose.runOnIdle { activeController.value = null }
        compose.waitForIdle()
        val library2 = TextbookLibrary(libDir)
        assertTrue(library2.textScrollMode(book.id))
        assertEquals(savedSpot, library2.savedReadSpot(book.id))
        assertEquals(ReadingFont.KAI, ReadingSettings(libDir).font)
        assertEquals(PaperColor.DARK, ReadingSettings(libDir).paper)
        assertEquals(1, library2.highlights(book.id, book.pages.first()).size)
        val draftStore = ReaderDraftScope.forSource(LocalBooksReaderSource(library2), libDir)!!
        assertEquals("Android note draft check", draftStore.listFor(book.id, "1", 1).single().note)

        val controller2 = TextbookController(library2)
        runBlocking {
            controller2.load()
            controller2.learn(LearningPath.READ)
        }
        compose.runOnIdle { activeController.value = controller2 }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("book-scroll-read").fetchSemanticsNodes().isNotEmpty() }
        // Switch back to paged mode and verify we are still on the exact saved subpage!
        compose.onNodeWithTag("read-mode").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book-page-turn").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(page2Text, visibleText())
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("books-notes-toggle") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("books-notes-toggle").performClick()
        compose.onNodeWithTag("local-draft-0").assertExists()
    }

    @Test fun foldUnfoldSpreadPreservesNewPageAfterManualTurn() {
        val raw = "# Chapter 1\n\n" + (1..80).joinToString("\n\n") { n ->
            "Paragraph $n. Folding and unfolding must preserve the reader's new page after turning."
        }
        val book = Textbook("fold-spot", "Foldable Book", "epub", listOf(BookPage(1, raw)))
        val state = BookPagingState(BookPageAnchor(1))
        val width = mutableStateOf(900.dp) // unfolded wide -> spread
        compose.setContent {
            com.coach.ui.CoachTheme {
                Box(Modifier.width(width.value).fillMaxSize()) {
                    BookPageView(book, state, emptyMap(), onTurn = { state.move(it) },
                        mode = BookPageMode.Auto, turnStyle = com.coach.ui.pageturn.PageTurnStyle.Flip)
                }
            }
        }
        compose.waitUntil(15_000) { state.pages.size >= 4 }
        // Turn once in wide/unfolded state.
        val moved = compose.runOnIdle { state.move(ReaderTurn.Next) }!!
        compose.waitForIdle()
        assertEquals(moved, state.anchor)
        // Narrow (fold to outer screen), then widen (unfold back to inner screen).
        compose.runOnIdle { width.value = 360.dp }
        compose.waitForIdle()
        assertEquals(moved, state.anchor)
        assertTrue(state.pages[state.index].sources.any {
            it.importPage == moved.importPage && moved.offsetUtf16 in it.startUtf16 until it.endUtf16
        })
        compose.runOnIdle { width.value = 900.dp }
        compose.waitForIdle()
        assertEquals(moved, state.anchor)
        assertTrue(state.pages[state.index].sources.any {
            it.importPage == moved.importPage && moved.offsetUtf16 in it.startUtf16 until it.endUtf16
        })
    }
}
