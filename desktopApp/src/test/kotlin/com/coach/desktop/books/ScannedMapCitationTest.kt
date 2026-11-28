package com.coach.desktop.books

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.runSkikoComposeUiTest
import com.coach.desktop.reader.Located
import com.coach.ui.theme.CoachTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

@OptIn(ExperimentalTestApi::class)
class ScannedMapCitationTest {
    @Test fun rotatedRasterWordsKeepTheirDistinctUtf16Offsets() {
        val hocr = """<span class="ocr_line"><span class="ocrx_word" title="bbox 10 20 30 40">🧪</span></span>
            <span class="ocr_line"><span class="ocrx_word" title="bbox 40 60 60 80">🧪</span></span>"""
        val boxes = OcrWordGeometry.boxes("🧪\n🧪", hocr, 100, 100, rotation = 90)
        assertEquals(listOf(0, 3), boxes.map { it.startOffset })
        assertEquals(listOf(2, 5), boxes.map { it.endOffset })
        assertEquals(0.6, boxes.last().bounds.x)
        assertEquals(0.4, boxes.last().bounds.y)
        assertTrue(OcrWordGeometry.boxes("🧪 extra 🧪", hocr, 100, 100, 0).isEmpty())
    }

    @Test fun importedScanHasMeasuredBoxesForMapSentencesAfterReopening() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("scan-map-")
        try {
            val sample = System.getenv("COACH_MAP_SCAN_SAMPLE")?.let { Path.of(it) }
                ?: root.resolve("sample.pdf").also { file ->
                    checkNotNull(javaClass.getResourceAsStream("/books/ocr/three-scanned-pages.pdf")).use {
                        Files.copy(it, file)
                    }
                }
            val library = TextbookLibrary(root.resolve("library"))
            val imported = library.importBook(sample)
            val reopened = TextbookLibrary(library.directory)
            // Simulate a pre-geometry import for one page. Remaining pages keep their measured import boxes.
            val firstScan = imported.pages.first { it.ocr && it.text.isNotBlank() }
            assertNotNull(library.pageTextLayout(imported.id, firstScan.number))
            library.updateRecognizedPage(imported.id, firstScan.number, firstScan.text)
            val book = reopened.book(imported.id)
            val legacy = checkNotNull(book.page(firstScan.number))
            assertNotNull(reopened.pdfTextLayer(book, legacy))
            assertNotNull(reopened.pageTextLayout(book.id, legacy.number))
            val refs = BookMapWire.buildBatchRequest(book).refMap.values
                .filter { book.page(it.pdfPage)?.ocr == true }.shuffled(kotlin.random.Random(1889)).take(10)
            assertTrue(refs.isNotEmpty())
            for (ref in refs) {
                val page = checkNotNull(book.page(ref.pdfPage))
                assertEquals(ref.text, page.text.substring(ref.startUtf16, ref.endUtf16))
                val unit = assertNotNull(reopened.pdfTextLayer(book, page))
                val measured = PdfPageText(unit.spans.first().geometry.frame, unit, emptyList())
                val located = locateCitedPdfRange(measured, page.text, ref.startUtf16 until ref.endUtf16)
                assertIs<Located.Found>(located.located)
            }
            runSkikoComposeUiTest {
                val current = mutableIntStateOf(refs.first().pdfPage)
                val pending = mutableStateOf<BookPassage?>(null)
                setContent {
                    CoachTheme(darkTheme = false) {
                        TextbookReader(LibraryUi(book = book, screen = BookScreen.READER, page = current.intValue),
                            reopened, UnconnectedBookLearningSupport, null, remember { FocusRequester() },
                            onNoteSession = {}, onPage = { current.intValue = it }, onBack = {}, onLearn = {},
                            pendingCourseJump = pending.value, onConsumePendingCourseJump = { pending.value = null })
                    }
                }
                for (ref in refs) {
                    runOnIdle { pending.value = BookPassage(book.id, ref.pdfPage, ref.startUtf16, ref.endUtf16, ref.text) }
                    val highlight = hasTestTag("remote-highlight") and hasContentDescription("原句：${ref.text}")
                    waitUntil(timeoutMillis = 30_000) { onAllNodes(highlight).fetchSemanticsNodes().size == 1 }
                }
            }
            println("scan-map rendered citations=${refs.size}")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
