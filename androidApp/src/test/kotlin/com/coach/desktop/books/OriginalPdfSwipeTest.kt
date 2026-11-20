package com.coach.desktop.books

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.coach.domain.reader.ReaderTextRange
import com.coach.ui.theme.CoachTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w704dp-h932dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OriginalPdfSwipeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var library: TextbookLibrary
    private lateinit var book: Textbook
    private val visible = mutableIntStateOf(1)
    private val layer get() = compose.onNodeWithTag("original-pdf-text-layer", useUnmergedTree = true)

    @Before fun makePdf() {
        val context = compose.activity.applicationContext
        PDFBoxResourceLoader.init(context)
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "pdf-turn-")
        val pdf = root.resolve("pages.pdf")
        PDDocument().use { document ->
            repeat(3) { index ->
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 18f)
                    content.newLineAtOffset(80f, 398f)
                    content.showText("Read this sentence and turn to page ${index + 1}.")
                    content.endText()
                }
            }
            document.save(pdf.toFile())
        }
        library = TextbookLibrary(root.resolve("library"))
        book = library.importBook(pdf)
    }

    private fun open(scrolling: Boolean = true) {
        if (!scrolling) library.savePdfScrollMode(book.id, false)
        compose.setContent {
            CoachTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    TextbookReader(LibraryUi(book = book, page = visible.intValue, screen = BookScreen.READER), library,
                        UnconnectedBookLearningSupport, null, remember { FocusRequester() },
                        onNoteSession = {}, onPage = { visible.intValue = it }, onBack = {}, onLearn = {})
                }
            }
        }
        awaitPage(1)
    }

    @Test fun v4SwipeTurnsInTheDefaultScrollingReader() {
        open()
        val reader = compose.onNodeWithTag("original-pdf-scroll")
        reader.performTouchInput { swipe(Offset(width * .8f, center.y), Offset(width * .2f, center.y), 300) }
        awaitPage(2)
        reader.performTouchInput { swipe(Offset(width * .2f, center.y), Offset(width * .8f, center.y), 300) }
        awaitPage(1)
        compose.onNodeWithText("下一页").performClick()
        awaitPage(2)
    }

    @Test fun pagedReaderKeepsAnEdgeTapAndStopsAtTheLastPage() {
        open(scrolling = false)
        val sheet = compose.onNodeWithTag("original-pdf-paged")
        sheet.performTouchInput { swipe(Offset(width * .9f, height * .25f), Offset(width * .88f, height * .75f), 300) }
        compose.runOnIdle { assertEquals(1, visible.intValue) }
        sheet.performTouchInput { swipe(Offset(width * .8f, center.y), Offset(width * .2f, center.y), 300) }
        awaitPage(2)
        sheet.performTouchInput { click(Offset(width * .96f, center.y)) }
        awaitPage(3)
        sheet.performTouchInput { swipe(Offset(width * .8f, center.y), Offset(width * .2f, center.y), 300) }
        compose.runOnIdle { assertEquals(3, visible.intValue) }
    }

    @Test fun verticalScrollSelectionHandleAndPinchKeepThePage() {
        open()
        val reader = compose.onNodeWithTag("original-pdf-scroll")
        val initialTop = layer.fetchSemanticsNode().positionInRoot.y
        reader.performTouchInput { swipe(Offset(width * .9f, height * .60f), Offset(width * .88f, height * .48f), 500) }
        compose.runOnIdle { assertEquals(1, visible.intValue) }
        assertTrue(layer.fetchSemanticsNode().positionInRoot.y < initialTop)
        reader.performTouchInput { swipe(Offset(width * .9f, height * .40f), Offset(width * .9f, height * .75f), 500) }
        compose.waitUntil(5_000) { layer.fetchSemanticsNode().positionInRoot.y == initialTop }
        val page = book.pages.first()
        val unit = checkNotNull(library.pdfTextLayer(book, page))
        val first = page.text.indexOf("Read")
        val word = ReaderTextRange(first, first + "Read".length)
        val phrase = ReaderTextRange(first, first + "Read this".length)
        val size = layer.fetchSemanticsNode().size
        val wordBoxes = PdfTextSelection.boxesForVerifiedRange(unit, page.text, word)
        val firstGlyph = wordBoxes.first()
        layer.performTouchInput {
            longClick(Offset((firstGlyph.x + firstGlyph.width / 2).toFloat() * size.width,
                (firstGlyph.y + firstGlyph.height / 2).toFloat() * size.height))
        }
        assertEquals(word, layer.fetchSemanticsNode().config.getOrNull(OriginalPdfSelectionRange))
        val radius = with(compose.density) { 5.dp.toPx() }
        val handle = pdfSelectionHandles(wordBoxes, size.width.toFloat(), size.height.toFloat(), radius).last()
        val lastGlyph = PdfTextSelection.boxesForVerifiedRange(unit, page.text, phrase).last()
        val end = Offset((lastGlyph.x + lastGlyph.width * .7).toFloat() * size.width,
            (lastGlyph.y + lastGlyph.height * .7).toFloat() * size.height)
        layer.performTouchInput { swipe(handle.center, handle.center + (end - handle.anchor), 300) }
        assertEquals(phrase, layer.fetchSemanticsNode().config.getOrNull(OriginalPdfSelectionRange))

        val bitmap = compose.onNodeWithTag("remote-image", useUnmergedTree = true)
        val bitmapWidth = bitmap.fetchSemanticsNode().config[OriginalPageBitmapWidth]
        layer.performTouchInput {
            down(0, center - Offset(35f, 0f))
            down(1, center + Offset(35f, 0f))
            updatePointerTo(0, center - Offset(65f, 0f))
            updatePointerTo(1, center + Offset(65f, 0f))
            move()
            up(1)
            up(0)
        }
        compose.waitUntil(10_000) { bitmap.fetchSemanticsNode().config[OriginalPageBitmapWidth] > bitmapWidth }
        reader.performTouchInput { swipe(Offset(width * .8f, center.y), Offset(width * .2f, center.y), 300) }
        compose.runOnIdle { assertEquals(1, visible.intValue) }
        assertEquals(phrase, layer.fetchSemanticsNode().config.getOrNull(OriginalPdfSelectionRange))
    }

    private fun awaitPage(expected: Int) {
        compose.waitUntil(10_000) {
            visible.intValue == expected &&
                compose.onAllNodes(hasTestTag("original-pdf-text-layer"), useUnmergedTree = true)
                    .fetchSemanticsNodes().size == 1
        }
        assertEquals(expected, visible.intValue)
    }
}
