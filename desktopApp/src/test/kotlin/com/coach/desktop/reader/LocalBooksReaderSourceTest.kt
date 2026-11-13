package com.coach.desktop.reader

import com.coach.desktop.books.TextbookLibrary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class LocalBooksReaderSourceTest {

    @Test
    fun testRealPdfEntersLocalBooksReaderSource() = runBlocking {
        val fixture = File("src/test/resources/qa-same-file.pdf").takeIf { it.isFile }
            ?: File("../qa_evidence/qa-same-file.pdf")
        assertTrue(fixture.isFile, "fixture qa-same-file.pdf must exist")

        val bytes = fixture.readBytes()
        val expectedSha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("f4324769c5d277eb33c5165e54cf65265c732cb1e3beb71ccc6692ad027860dd", expectedSha)

        val tempDir = Files.createTempDirectory("local-books-test")
        try {
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
