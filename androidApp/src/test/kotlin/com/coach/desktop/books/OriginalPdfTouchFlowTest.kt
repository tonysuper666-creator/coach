package com.coach.desktop.books

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.coach.domain.reader.ReaderTextRange
import com.coach.ui.theme.CoachTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OriginalPdfTouchFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun phoneOriginalPdfSelectViewToggleExplainHighlightAndDarkModeFlow() {
        val context = compose.activity.applicationContext
        PDFBoxResourceLoader.init(context)
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "original-pdf-flow-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(fixture(root))
        val page = book.pages.single()
        val start = page.text.lastIndexOf(Sentence)
        assertTrue(start > page.text.indexOf(Sentence))
        val word = ReaderTextRange(start, start + "The".length)
        val whole = ReaderTextRange(start, start + Sentence.length)
        val unit = checkNotNull(library.pdfTextLayer(book, page))
        val glyphs = PdfTextSelection.boxesForVerifiedRange(unit, page.text, whole)
        val open = mutableStateOf(true)
        val dark = mutableStateOf(false)
        val currentPage = mutableIntStateOf(1)
        val practiceSource = mutableStateOf<PracticeSource?>(null)
        val lesson = BookLesson("l1", "第一章", StudyCoachCourse.blocks(book.id, book.pages))
        compose.setContent {
            CoachTheme(darkTheme = dark.value) {
                Box(Modifier.fillMaxSize()) {
                    if (open.value) TextbookReader(
                        LibraryUi(
                            book = book,
                            page = currentPage.intValue,
                            screen = BookScreen.READER,
                            source = if (practiceSource.value != null) lesson else null,
                        ),
                        library,
                        UnconnectedBookLearningSupport,
                        practiceSource.value,
                        remember { FocusRequester() },
                        onNoteSession = {},
                        onPage = { currentPage.intValue = it },
                        onBack = {},
                        onLearn = {},
                    )
                }
            }
        }
        val layerTag = "original-pdf-text-layer"
        val layer = compose.onNodeWithTag(layerTag)
        fun awaitPage() {
            compose.waitUntil(30_000) { compose.onAllNodesWithTag(layerTag).fetchSemanticsNodes().isNotEmpty() }
        }
        fun selectWord() {
            val first = glyphs.first()
            val pageSize = layer.fetchSemanticsNode().size
            layer.performTouchInput {
                longClick(Offset((first.x + first.width / 2).toFloat() * pageSize.width,
                    (first.y + first.height / 2).toFloat() * pageSize.height))
            }
            assertEquals(word, layer.fetchSemanticsNode().config[OriginalPdfSelectionRange])
        }
        fun extendToEnd() {
            val size = layer.fetchSemanticsNode().size
            val handleRadius = with(compose.density) { 5.dp.toPx() }
            val lastLetter = PdfTextSelection.boxesForVerifiedRange(unit, page.text, word).last()
            val end = glyphs.last()
            val from = Offset((lastLetter.x + lastLetter.width).toFloat() * size.width,
                (lastLetter.y + lastLetter.height).toFloat() * size.height + handleRadius - 1)
            val to = Offset((end.x + end.width * .7).toFloat() * size.width,
                (end.y + end.height * .7).toFloat() * size.height + handleRadius)
            layer.performTouchInput { swipe(from, to, durationMillis = 350) }
            assertEquals(whole, layer.fetchSemanticsNode().config[OriginalPdfSelectionRange])
        }

        // 1. Default original PDF opens, long-press + handle drag selects exact repeated occurrence.
        awaitPage()
        selectWord()
        extendToEnd()

        // 2. Toggle to text view and back to original view preserves exact passage anchor and persists view preference.
        compose.onNodeWithTag("book-original-toggle").performClick()
        compose.waitUntil(5_000) { library.preferredTextView(book.id) == true }
        compose.onNodeWithTag("book-original-toggle").performClick()
        awaitPage()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("remote-highlight").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("remote-highlight").assertIsDisplayed()
        assertEquals(false, library.preferredTextView(book.id))

        // 3. Re-select sentence, enter explain-selection panel with that exact selection, then return to original page.
        selectWord()
        extendToEnd()
        compose.onNodeWithTag("book-explain-toggle").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("explain-block-${book.id}:p1:${whole.startUtf16}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("book-explain-toggle").performClick()
        awaitPage()

        // 4. Citation jump highlights exact UTF-16 range on original PDF.
        compose.runOnIdle {
            practiceSource.value = PracticeSource(
                page = 1,
                quote = Sentence,
                warning = null,
                target = BookSourceTarget(book.id, book.title, 1, whole.startUtf16, whole.endUtf16, Sentence, ""),
            )
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("remote-highlight").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("remote-highlight").assertIsDisplayed()
        compose.runOnIdle { practiceSource.value = null }

        // 5. Returning from explain keeps the selection; tapping margin clears it, then re-selecting & pinching works.
        assertEquals(whole, layer.fetchSemanticsNode().config[OriginalPdfSelectionRange])
        layer.performTouchInput {
            down(Offset(width * .5f, height * .05f))
            up()
        }
        compose.waitUntil(5_000) {
            layer.fetchSemanticsNode().config.getOrNull(OriginalPdfSelectionRange) == null
        }
        selectWord()
        extendToEnd()
        val image = compose.onNodeWithTag("remote-image")
        val originalWidth = image.fetchSemanticsNode().config[OriginalPageBitmapWidth]
        layer.performTouchInput {
            down(0, center - Offset(30f, 0f))
            down(1, center + Offset(30f, 0f))
            updatePointerTo(0, center - Offset(65f, 0f))
            updatePointerTo(1, center + Offset(65f, 0f))
            move()
            up(1)
            up(0)
        }
        compose.waitUntil(10_000) { image.fetchSemanticsNode().config[OriginalPageBitmapWidth] > originalWidth }
        assertEquals(whole, layer.fetchSemanticsNode().config[OriginalPdfSelectionRange])

        // 6. Highlight persists across close & reopen, then switch to dark mode, re-select, and clear highlight.
        compose.onNodeWithTag("original-pdf-highlight").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { library.highlights(book.id, page) == listOf(whole) }
        compose.runOnIdle { open.value = false }
        compose.waitForIdle()
        assertEquals(listOf(whole), TextbookLibrary(library.directory).highlights(book.id, page))

        compose.runOnIdle { dark.value = true; open.value = true }
        awaitPage()
        selectWord()
        extendToEnd()
        compose.onNodeWithTag("original-pdf-highlight").performClick()
        compose.waitUntil(5_000) { library.highlights(book.id, page).isEmpty() }
    }

    private fun fixture(root: Path): Path = root.resolve("Repeated lines.pdf").also { file ->
        BookPdfDocument().use { pdf ->
            val page = PDPage(BookPdfRectangle(420f, 480f))
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { content ->
                for (y in listOf(410f, 315f)) {
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 18f)
                    content.newLineAtOffset(36f, y)
                    content.showText(Sentence)
                    content.endText()
                }
            }
            pdf.save(file.toFile())
        }
    }

    private companion object {
        const val Sentence = "The same phrase appears here."
    }
}
