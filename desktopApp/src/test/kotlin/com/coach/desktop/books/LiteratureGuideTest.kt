package com.coach.desktop.books

import kotlinx.serialization.json.*
import kotlin.test.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts

class LiteratureGuideTest {
    private val books = (1..3).map { b -> Textbook("paper-$b", "Paper $b", "pdf",
        "A unique—and exact—claim about sample $b.".let { listOf(BookPage(1, it, guideRegions = listOf(GuideRegion(it, "148")))) }) }
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
        assertEquals(2, calls)
        assertEquals(recognized, recovered.page(2)!!.guideRegions.single().text)
        assertEquals(original.page(2)!!.text, recovered.page(2)!!.text)
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

    @Test fun stagesAndQuestionsChooseBodyEvidenceAcrossTwentyPages() {
        val pages = (1..20).map { n ->
            val text = buildString {
                appendLine("Society Proceedings")
                if (n == 1) appendLine("We argue that the original charter defined the collection.")
                repeat(6) { i -> appendLine("The committee recorded observation number " + (n * 10 + i) + " in the register.") }
                if (n == 9) appendLine("Provenance established custody because witnesses signed the register.")
                if (n == 20) appendLine("Therefore the closing report connects custody with evidence.")
                appendLine("1. A bibliographic annotation about the register.")
                append("Downloaded from JSTOR on Tuesday.")
            }
            BookPage(n, text, guideRegions = listOf(GuideRegion(text, (147 + n).toString())))
        }
        val papers = books.map { it.copy(pages = pages) }
        val prompt = listOf("How did provenance establish custody?")
        val selected = LiteratureInput(papers, prompt)
        val sample = selected.excerpts.filter { it.book.id == papers.first().id }
        assertTrue(sample.distinctBy { it.section.id }.size in 3..8)
        val quotes = sample.map { it.anchor.quoteAnchor }
        assertTrue(quotes.any { it.startsWith("Provenance established") })
        assertTrue(quotes.any { it.startsWith("We argue") })
        assertTrue(quotes.any { it.startsWith("Therefore") })
        assertFalse(quotes.any { "JSTOR" in it || "Proceedings" in it || "annotation" in it })
        val retry = LiteratureInput(papers, prompt, 1).excerpts.map { it.anchor.quoteAnchor }.toSet()
        assertTrue((retry - quotes.toSet()).isNotEmpty())
        val reply = LiteratureGuideFixture.reply(selected.request())
        val failed = buildJsonArray { add(buildJsonArray { add("片段未涉及。"); add("无法回答。") }) }
        assertFails { selected.verified(JsonObject(reply + ("answers" to failed))) }
    }

    @Test fun realHeadingsAndUntrustedOffsetsAreHandledBeforeSelection() {
        val text = "Introduction\nWe argue that the methods produce consistent estimates.\nDiscussion\nThe later experiments support the estimates independently.\nReferences\nA citation should never appear as evidence."
        val real = books.map { it.copy(pages = listOf(BookPage(1, text, guideRegions = listOf(GuideRegion(text, "iv"))))) }
        val selected = LiteratureInput(real, input.questions)
        assertEquals(setOf("Introduction", "Discussion"), selected.excerpts.map { it.section.title }.toSet())
        assertTrue(selected.excerpts.none { "citation" in it.anchor.quoteAnchor })
        val wrongOrder = "The archive consulted another register. A fabricated line from the margin."
        val mixed = real.map { it.copy(pages = listOf(BookPage(1, text, guideRegions = listOf(GuideRegion(text, "iv", wrongOrder))))) }
        assertFails { LiteratureInput(mixed, input.questions) }
        assertFails { LiteratureInput(real.map { it.copy(pages = listOf(BookPage(1, text))) }, input.questions) }
    }

    @Test fun offsetColumnsRetainOriginalSentenceAndFolio() {
        PDDocument().use { pdf ->
            val page = PDPage()
            pdf.addPage(page)
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            PDPageContentStream(pdf, page).use { stream ->
                fun line(x: Float, y: Float, size: Float, text: String) {
                    stream.beginText()
                    stream.setFont(font, size)
                    stream.newLineAtOffset(x, y)
                    stream.showText(text)
                    stream.endText()
                }
                line(42f, 750f, 11f, "148")
                line(42f, 720f, 12f, "The keeper transferred the records")
                line(42f, 705f, 12f, "to the archive because witnesses")
                line(42f, 690f, 12f, "confirmed the provenance.")
                line(42f, 675f, 12f, "Other registers survived as well.")
                line(350f, 717f, 9f, "A marginal note")
                line(350f, 704f, 9f, "from another hand")
                line(350f, 691f, 9f, "appears beside the text.")
                line(350f, 678f, 9f, "More commentary here.")
            }
            val image = org.apache.pdfbox.rendering.PDFRenderer(pdf).renderImageWithDPI(0, 180f)
            val regions = try {
                LiteraturePdfLayout.read(image, literatureOcr(BundledBookOcr), BookImportControl(), LiteraturePdfLayout.gutterHint(pdf, 1))
            } finally { image.flush() }
            val original = "The keeper transferred the records to the archive because witnesses confirmed the provenance."
            val text = original + "\nOther registers survived as well."
            val papers = books.map { it.copy(pages = listOf(BookPage(1, text, guideRegions = regions))) }
            val selected = LiteratureInput(papers, input.questions)
            val e = selected.excerpts.first { it.anchor.quoteAnchor.replace('\n', ' ') == original }
            assertEquals("148", e.region.printedPage)
            assertEquals(1, e.anchor.pdfPage)
            assertFalse(selected.excerpts.any { "marginal" in it.anchor.quoteAnchor })
            val crossed = "The keeper transferred the records A marginal note to the archive because witnesses."
            val interleaved = papers.map { it.copy(pages = listOf(BookPage(1,
                crossed + "\nOther registers survived as well.", guideRegions = regions))) }
            assertFails { LiteratureInput(interleaved, input.questions) }
            val good = "An intact page has its own evidence."
            val recovered = interleaved.map { book -> book.copy(pages = book.pages +
                BookPage(2, good, guideRegions = listOf(GuideRegion(good, "149")))) }
            assertTrue(LiteratureInput(recovered, input.questions).excerpts.all { it.anchor.pdfPage == 2 })
        }
    }

    @Test fun everyAnswerNeedsItsOwnBodyEvidence() {
        val answers = body.getValue("answers").jsonArray
        val first = answers.first().jsonArray
        val sentence = first.first().jsonObject
        fun changed(fields: JsonObject) = JsonObject(body + ("answers" to JsonArray(
            listOf(JsonArray(listOf(fields, first.last()))) + answers.drop(1))))
        for (phrase in listOf("未直接说明。", "无法直接验证。", "材料中没有这个观点。")) {
            assertFails { input.verified(changed(JsonObject(sentence + ("answer" to JsonPrimitive(phrase))))) }
        }
        assertFails { input.verified(changed(JsonObject(sentence - "ref"))) }
        assertFails { input.verified(changed(JsonObject(sentence + ("ref" to JsonPrimitive(10000))))) }
        assertFails { input.verified(changed(JsonObject(sentence + ("evidence" to JsonPrimitive("Words from a different paper."))))) }
        val valid = input.verified(body).answers.first().second
        assertEquals(2, valid.size)
        assertNotEquals(valid.first().source.book.id, valid.last().source.book.id)
    }

    @Test fun physicalFoliosOverrideAnOffsetPublisherSequence() {
        val pages = listOf(null, "147", null, "149", "150").mapIndexed { i, printed ->
            BookPage(i + 1, "body", guideRegions = listOf(GuideRegion("body", printed)))
        }
        val labels = arrayOf("147", "148", "149", "150", "151")
        val resolved = LiteratureFolios.resolve(pages, labels).map { it.guideRegions.single().printedPage }
        assertEquals("147", resolved[1])
        assertEquals("148", resolved[2])
        assertEquals("150", resolved[4])
        assertNull(LiteratureFolios.resolve(pages.take(1), labels).single().guideRegions.single().printedPage)
    }

    @Test fun courseLicenceAndBibliographicNotesCannotSupplyEvidence() {
        val cover = "Course materials copyright notice.\nYou may print this item for personal use only."
        val ocr = "Introduction\nThe office kept the original official records.\nLane, A. The Printed Record, vol. 3, pp. 9–10."
        val raw = "Reader prefix.\nThe office kept the orig-\ninal  ofﬁcial records.\nLane, A. The Printed Record, vol. 3, pp. 9–10."
        val sources = books.map { it.copy(pages = listOf(BookPage(1, cover, guideRegions = listOf(GuideRegion(cover, null))),
            BookPage(2, raw, guideRegions = listOf(GuideRegion(ocr, null))))) }
        val result = LiteratureInput(sources, input.questions)
        assertEquals(3, result.excerpts.size)
        result.excerpts.forEach { e ->
            assertEquals(2, e.anchor.pdfPage)
            assertEquals(raw.indexOf("The office"), e.anchor.startUtf16)
            assertEquals(raw.substring(e.anchor.startUtf16, e.anchor.endUtf16), e.anchor.quoteAnchor)
        }
    }

    @Test fun perPaperBudgetIncludesBibliographicIdentityOnce() {
        val root = java.nio.file.Files.createTempDirectory("guide-ref-")
        val pdf = LiteratureGuideFixture.pdf(root, 7)
        val named = root.resolve("Ada Lane (1997) - Field notes.pdf")
        org.apache.pdfbox.Loader.loadPDF(pdf.toFile()).use { doc ->
            doc.documentInformation.title = "Field &amp; garden"
            doc.save(named.toFile())
        }
        val library = TextbookLibrary(root.resolve("library"), ocr = BookOcr { _, _ -> "The office kept the original official records." })
        val paper = library.literatureBook(library.importBook(named).id)
        assertEquals("Field & garden", paper.title)
        assertEquals("Ada Lane", paper.guideCitation.author)
        assertEquals("1997", paper.guideCitation.year)
        val sources = books.mapIndexed { i, b ->
            val pages = (0 until (12 + i * 3)).map { n ->
                val text = (0..9).joinToString(" ") { "Specimen number " + (n * 10 + it) + " survived the winter in the sheltered field." }
                BookPage(n + 1, text, guideRegions = listOf(GuideRegion(text, null)))
            }
            b.copy(pages = pages, guideCitation = paper.guideCitation)
        }
        val fourth = sources.last().copy(id = "fourth-paper")
        val four = sources + fourth
        val prompts = (1..5).map { "How did experiment " + it + " compare?" }
        val annotation = mapOf(fourth.id to LiteratureCitation("A. Lane", "1998"))
        val selected = LiteratureInput(four, prompts, annotations = annotation)
        val request = selected.request()
        val guide = selected.verified(LiteratureGuideFixture.reply(request))
        assertEquals(4, guide.papers.size)
        assertEquals(10, guide.answers.sumOf { it.second.size })
        val manual = request.getValue("documents").jsonArray.last().jsonObject
        assertEquals("1998", manual.getValue("year").jsonPrimitive.content)
        library.withCourseDatabase { db ->
            val course = CourseStore.createCourse(db, "Synthetic", CourseExamProfile(), listOf(paper.id)).first
            LiteratureGuideStore.save(db, course, LiteratureGuideStore.digest(request), selected, null)
            assertEquals(annotation, LiteratureGuideStore.latest(db, course)?.annotations)
        }
        assertEquals(4, request.getValue("documents").jsonArray.size)
        assertEquals("Ada Lane", request.getValue("documents").jsonArray.first().jsonObject["author"]?.jsonPrimitive?.content)
        assertTrue(request.toString().toByteArray().size <= 79000)
        val pools = request.getValue("blocks").jsonArray.groupBy { it.jsonObject["bookId"] }
        assertTrue(pools.values.all { rows -> rows.sumOf { it.toString().toByteArray().size + 1 } <= 79000 / 4 })
    }
}
