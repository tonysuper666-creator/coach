package com.coach.desktop.books

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.coach.domain.reader.ReaderRect
import com.coach.domain.reader.ReaderTextRange
import com.coach.ui.theme.CoachTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class OriginalPdfTouchSmoke {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun repeatedTextCanBeReselectedAndItsHighlightSurvivesReopen() {
        val context = compose.activity.applicationContext
        PDFBoxResourceLoader.init(context)
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "original-pdf-touch-")
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
        val support = object : BookLearningSupport by UnconnectedBookLearningSupport {
            override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
                assertEquals(BookPassage(book.id, 1, whole.startUtf16, whole.endUtf16, Sentence), request.passage)
                return BookLearningReply.Text("A test reply about the selected sentence.", listOf(request.passage))
            }
        }
        compose.setContent {
            CoachTheme(darkTheme = dark.value) {
                if (open.value) TextbookReader(LibraryUi(book = book, page = 1, screen = BookScreen.READER), library,
                    support, null, remember { FocusRequester() },
                    onNoteSession = {}, onPage = {}, onBack = {}, onLearn = {})
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
        awaitPage()
        selectWord()
        extendToEnd()
        compose.onNodeWithTag("ask-book-passage").performClick()
        compose.onNodeWithTag("book-passage-question").performTextInput("What does this say?")
        compose.onNodeWithTag("book-passage-send").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("book-passage-citation").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("book-passage-citation").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("remote-highlight").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("remote-highlight").assertIsDisplayed()
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

    @Test fun nativeRasterUsesTheSamePageFrameAsPdfBox() {
        val context = compose.activity.applicationContext
        PDFBoxResourceLoader.init(context)
        val file = fixture(Files.createTempDirectory(context.cacheDir.toPath(), "original-pdf-raster-")).toFile()
        BookPdfLoader.load(file).use { pdf ->
            val frame = pdf.getPage(0).cropBox
            val native = renderNativeBookPdfPage(file, 0, frame.width, frame.height, 1f)
            assertNotNull(native)
            val raster = checkNotNull(native)
            val pdfBox = BookPdfRenderer(pdf).renderImage(0, 1f)
            try {
                assertEquals(pdfBox.width, raster.width)
                assertEquals(pdfBox.height, raster.height)
                val unit = PdfPageTextExtraction.extract(pdf, 1).unit
                val offset = checkNotNull(unit.text).lastIndexOf(Sentence)
                val glyph = unit.spans.first { it.range.startUtf16 == offset }.geometry.displayBoxes().single()
                assertTrue(hasInk(glyph, raster))
                assertTrue(hasInk(glyph, pdfBox))
            } finally {
                raster.recycle()
                pdfBox.recycle()
            }
        }
    }

    private fun hasInk(box: ReaderRect, raster: BookBitmap): Boolean {
        val left = (box.x * raster.width).toInt()
        val right = ((box.x + box.width) * raster.width).toInt()
        val top = (box.y * raster.height).toInt()
        val bottom = ((box.y + box.height) * raster.height).toInt()
        return (top until bottom).any { y ->
            (left until right).any { x -> android.graphics.Color.red(raster.getPixel(x, y)) < 192 }
        }
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
