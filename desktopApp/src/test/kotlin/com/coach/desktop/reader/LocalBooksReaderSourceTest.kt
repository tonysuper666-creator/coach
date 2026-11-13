package com.coach.desktop.reader

import com.coach.desktop.books.TextbookLibrary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class LocalBooksReaderSourceTest {

    private fun generateSyntheticPdf(outputFile: File) {
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            fun page(title: String, text: String) {
                val p = PDPage(PDRectangle.LETTER)
                doc.addPage(p)
                PDPageContentStream(doc, p).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 18f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText(title)
                    cs.setFont(font, 13f)
                    cs.newLineAtOffset(0f, -30f)
                    cs.showText(text)
                    cs.endText()
                }
            }
            page("Chapter 1 Light", "Photosynthesis is the process by which plants use light to make sugars.")
            page("Chapter 2 Cells", "Chlorophyll is a green pigment that absorbs light in plant cells.")
            page("Chapter 3 Energy", "Respiration is the process that releases energy from stored sugars.")
            doc.save(outputFile)
        }
    }

    @Test
    fun testRealPdfEntersLocalBooksReaderSource() = runBlocking {
        val tempDir = Files.createTempDirectory("local-books-test")
        try {
            val fixture = System.getProperty("qa.fixture")?.let(::File)?.takeIf { it.isFile }
                ?: File("../qa_evidence/qa-same-file.pdf").takeIf { it.isFile }
                ?: File(tempDir.toFile(), "synthetic-fixture.pdf").also { generateSyntheticPdf(it) }
            assertTrue(fixture.isFile, "fixture PDF must exist")

            val bytes = fixture.readBytes()
            val expectedSha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

            val library = TextbookLibrary(tempDir)
            val source = LocalBooksReaderSource(library)

            // Initially catalog is empty
            val emptyCat = source.catalog().body!!["documents"] as JsonArray
            assertEquals(0, emptyCat.size)

            // Import book
            val book = library.importBook(fixture.toPath())
            assertEquals(expectedSha, book.id)
            assertEquals(3, book.pages.size)

            // Catalog now contains the document with identical id and pages
            val catReply = source.catalog()
            assertTrue(catReply.ok)
            val docs = catReply.body!!["documents"] as JsonArray
            assertEquals(1, docs.size)
            assertEquals(expectedSha, docs[0].jsonObject["id"]?.jsonPrimitive?.content)
            assertEquals(3, docs[0].jsonObject["pages"]?.jsonPrimitive?.content?.toInt())

            // State reflects document id, pages, and initial readingPage = 1
            val stateReply = source.state(expectedSha)
            assertTrue(stateReply.ok)
            val stateDoc = stateReply.body!!["document"]!!.jsonObject
            assertEquals(expectedSha, stateDoc["id"]?.jsonPrimitive?.content)
            assertEquals(3, stateDoc["pages"]?.jsonPrimitive?.content?.toInt())
            val progress = stateReply.body!!["progress"]!!.jsonObject
            assertEquals(1, progress["pdfPage"]?.jsonPrimitive?.content?.toInt())

            // Page 2 text extraction
            val p2 = source.page(expectedSha, 2)
            assertTrue(p2.ok)
            val p2Text = p2.body!!["text"]!!.jsonPrimitive.content
            assertTrue(p2Text.contains("Chapter 2 Cells"))
            assertTrue(p2Text.contains("Chlorophyll"))

            // Page 2 image rendering
            val imgBytes = source.image(expectedSha, 2, "1")
            assertNotNull(imgBytes)
            assertTrue(imgBytes.isNotEmpty())

            // Page 2 boxes extraction from PDFBox
            val boxesReply = source.boxes(expectedSha, 2, "1")
            assertTrue(boxesReply.ok)
            val boxesBody = boxesReply.body!!
            val words = boxesBody["words"] as JsonArray
            assertTrue(words.isNotEmpty(), "Word boxes should be extracted from PDF text layer")
            val wordTexts = words.map { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
            assertTrue(wordTexts.contains("Chapter"))
            assertTrue(wordTexts.contains("Cells"))
            assertTrue(wordTexts.contains("Chlorophyll"))

            // QuoteLocator locates the sentence in page 2
            val remote = ReaderRemote(source)
            remote.loadCatalog()
            remote.open(expectedSha)
            remote.show(2)

            val readyBoxes = (remote.boxes as? BoxesState.Ready)?.boxes
            assertNotNull(readyBoxes)
            val quote = "Chlorophyll is a green pigment that absorbs light in plant cells."
            val located = QuoteLocator.locate(readyBoxes, quote)
            assertIs<Located.Found>(located, "Sentence should be accurately located in PDFBox word boxes")
            assertTrue(located.rects.isNotEmpty())

            // Progress save and state persistence
            val saveReply = source.saveProgress(expectedSha, "1", 3, 1L, "mut-1")
            assertTrue(saveReply.ok)

            val updatedState = source.state(expectedSha)
            val updatedProgress = updatedState.body!!["progress"]!!.jsonObject
            assertEquals(3, updatedProgress["pdfPage"]?.jsonPrimitive?.content?.toInt())
            assertEquals(3, library.book(expectedSha).readingPage)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
