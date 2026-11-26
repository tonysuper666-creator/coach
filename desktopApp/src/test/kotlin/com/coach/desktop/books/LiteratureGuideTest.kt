package com.coach.desktop.books

import kotlinx.serialization.json.*
import kotlin.test.*

class LiteratureGuideTest {
    private val books = (1..3).map { b -> Textbook("paper-$b", "Paper $b", "pdf",
        listOf(BookPage(1, "A unique—and exact—claim about sample $b."))) }
    private val input = LiteratureInput(books, listOf("What changed?", "How did the samples differ?"))
    private val body = LiteratureGuideFixture.reply(input.request())

    @Test fun citationsComeFromTheAddressedLocalPage() {
        val guide = input.verified(body)
        assertEquals(3, guide.papers.size)
        assertEquals(10, guide.quiz.size)
        for (stop in guide.stops) {
            val e = stop.source
            assertEquals(e.book.page(e.anchor.pdfPage)!!.text.substring(e.anchor.startUtf16, e.anchor.endUtf16),
                e.anchor.quoteAnchor)
        }
        val wrongRef = body.getValue("papers").jsonArray.map {
            JsonObject(it.jsonObject + ("ref" to JsonPrimitive(0)))
        }
        assertFails { input.verified(JsonObject(body + ("papers" to JsonArray(wrongRef)))) }
        assertFails { input.verified(JsonObject(body + ("stops" to JsonArray(emptyList())))) }
    }

    @Test fun partialScanUsesLocalOcrWithoutChangingReaderOffsets() {
        val root = java.nio.file.Files.createTempDirectory("coach-guide-ocr-")
        val recognized = "Shade reduced evaporation in this sample."
        var calls = 0
        val library = TextbookLibrary(root.resolve("library"), ocr = BookOcr { _, _ -> calls++; recognized })
        val original = library.importBook(LiteratureGuideFixture.pdf(root, 1, partialLayer = true))
        val recovered = library.literatureBook(original.id)
        assertEquals(1, calls)
        assertEquals(recognized, recovered.page(2)!!.text)
        assertEquals(original.page(2)!!.text, library.book(original.id).page(2)!!.text)
    }

    @Test fun rejectsPendingOcrAndMissingQuestionCoverage() {
        assertFails { LiteratureInput(books.map { it.copy(pages = listOf(BookPage(1, "temporary", ocrPending = true))) },
            input.questions) }
        val uncovered = body.getValue("quiz").jsonArray.map {
            JsonObject(it.jsonObject + ("questionIndex" to JsonPrimitive(1)))
        }
        assertFails { input.verified(JsonObject(body + ("quiz" to JsonArray(uncovered)))) }
        assertFails { input.verified(JsonObject(body + ("quiz" to JsonArray(uncovered.take(9))))) }
    }
}
