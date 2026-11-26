package com.coach.desktop.books

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDPageLabelRange
import org.apache.pdfbox.pdmodel.common.PDPageLabels
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiteratureFollowTest {
    @Test fun printedChainBeatsWrongLabelsAndRejectsALoneFolio() {
        assertEquals(listOf(20, 21, 22, 23), LiteraturePrintedPages.resolve(
            listOf(listOf(20), listOf(21), listOf(22), listOf(23)),
            listOf(21, 22, 23, 24),
        ))
        assertEquals(listOf(20, 21, null, 23, 24), LiteraturePrintedPages.resolve(
            listOf(listOf(20), listOf(21), listOf(99), listOf(23), listOf(24)),
            emptyList(),
        ))
        assertEquals(listOf(20, 21, 22), LiteraturePrintedPages.resolve(
            listOf(listOf(20), emptyList(), listOf(22)),
            emptyList(),
        ))
        assertEquals(listOf(null, null), LiteraturePrintedPages.resolve(
            listOf(emptyList(), emptyList()),
            listOf(147, 148),
        ))
        assertEquals(listOf(null), LiteraturePrintedPages.resolve(listOf(listOf(55)), listOf(55)))
        assertEquals(listOf(20, 21, 22, 23), LiteraturePrintedPages.resolve(
            listOf(listOf(20), listOf(21), emptyList(), emptyList()),
            listOf(20, 21, 22, 23),
        ))
        assertEquals("第 20 页", LiteraturePrintedPages.display(2, 20))
        assertEquals("PDF 第 2 页", LiteraturePrintedPages.display(2, null))
        assertFalse("印刷第" in LiteraturePrintedPages.display(2, 20))
        assertFalse("印刷页码" in LiteraturePrintedPages.display(2, null))
        assertNull(LiteraturePrintedPages.parseFolio("xiv"))
        assertNull(LiteraturePrintedPages.parseFolio("HUNTER 1989"))
        assertEquals(20, LiteraturePrintedPages.parseFolio("p. 20"))
    }

    @Test fun runningHeaderIsNotASectionTitle() {
        val book = paper(
            """
            The Historical Journal
            Introduction
            The keeper argued that the repository preserved provenance across decades.
            Witnesses confirmed that written evidence stayed intact during inspections.
            """.trimIndent(),
            """
            The Historical Journal
            The Argument
            However the catalog argued that evidence confirmed a narrower claim about custody.
            Readers still cite the register when they reconstruct that custody.
            """.trimIndent(),
            """
            The Historical Journal
            Conclusion
            The final inventory claimed that the collection remained intact after the move.
            Scholars can still trace each object back to the original shelf mark.
            """.trimIndent(),
        )
        val sections = LiteratureSections.build(book, LiteraturePassages.extractCandidates(book))
        assertEquals(listOf("Introduction", "The Argument", "Conclusion"), sections.map { it.title })
        assertTrue(sections.all { "Journal" !in it.title && "HUNTER" !in it.title })
        assertTrue(sections.all { it.sentences.isNotEmpty() })
    }

    @Test fun paperWithoutHeadingsSplitsIntoArgumentStages() {
        val pages = noHeadingPages()
        val book = paper(*pages.toTypedArray())
        val candidates = LiteraturePassages.extractCandidates(book)
        assertEquals(12, candidates.size)
        val sections = LiteratureSections.build(book, candidates)
        assertTrue(sections.size in 3..8, "Expected 3..8 stages, got ${sections.size}")
        assertTrue(sections.size != pages.size, "Stages must not be one section per page")
        assertEquals(listOf("提出问题", "转折", "收束"), sections.map { it.title })
        assertTrue(sections.all { "Journal" !in it.title })
        assertEquals(candidates.size, sections.sumOf { it.sentences.size })
    }

    @Test fun quoteMapMatchesNormalizerAndRejectsADifferentSentence() {
        val samples = listOf(
            "trans-\nformed the archive.",
            "The \uFB01le was \u201Cquoted\u201D.",
            "hello   world .",
            "a - b stayed intact.",
            "soft\u00ADhyphen word.",
            "long \u017F was used.",
            "  spaced   out  ",
            "well-known result.",
            "a\u2013b range.",
            "full\uFF0Cwidth\uFF0E",
        )
        for (sample in samples) {
            assertEquals(LiteratureTextNorm.normalize(sample), LiteratureQuoteMap.normalize(sample), sample)
        }
        val hyphenated = BookPage(1, "The record was trans-\nformed by witnesses today.")
        val hyphenQuote = "The record was transformed by witnesses today."
        val hyphenHit = assertNotNull(LiteratureQuoteMap.toPassage("b", hyphenated, hyphenQuote))
        assertEquals(hyphenated.text.substring(hyphenHit.start, hyphenHit.end), hyphenHit.text)
        assertEquals(hyphenQuote, LiteratureTextNorm.normalize(hyphenHit.text))

        val spaced = BookPage(1, "The keeper   argued that provenance stayed intact.")
        val spacedQuote = "The keeper argued that provenance stayed intact."
        val spacedHit = assertNotNull(LiteratureQuoteMap.toPassage("b", spaced, spacedQuote))
        assertEquals(spaced.text.substring(spacedHit.start, spacedHit.end), spacedHit.text)
        assertEquals(spacedQuote, LiteratureTextNorm.normalize(spacedHit.text))

        val page = BookPage(1, "The keeper argued that provenance stayed intact across decades.")
        assertNull(LiteratureQuoteMap.toPassage("b", page, "The keeper denied that provenance stayed intact across decades."))
    }

    @Test fun syntheticPdfsKeepPrintedPagesQuotesAndStops() {
        val root = Files.createTempDirectory("coach-follow-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val headed = library.importBook(headingPdf(root))
            val plain = library.importBook(plainPdf(root))
            val folio = library.importBook(folioPdf(root))
            val jstor = library.importBook(LiteratureGuideFixture.jstorWrongPageLabelsPdf(root))
            val facing = library.importBook(LiteratureGuideFixture.facingAndMarginaliaPdf(root))

            val headedPaper = LiteratureFollow.load(library, headed.id)
            assertEquals(listOf("Introduction", "The Argument", "Conclusion"), headedPaper.sections.map { it.title })
            assertTrue(headedPaper.sections.all { "Journal" !in it.title })

            val plainPaper = LiteratureFollow.load(library, plain.id)
            assertTrue(plainPaper.sections.size in 3..8)
            assertTrue(plainPaper.sections.size != 4)
            assertEquals(listOf("提出问题", "转折", "收束"), plainPaper.sections.map { it.title })

            val literatureFolio = library.literatureBook(folio.id)
            assertTrue(literatureFolio.pages.all { page -> page.guideRegions.all { it.printedPage == null } })
            assertEquals(listOf(20, 21, 22, 23), library.literaturePageNumbers(folio.id))
            assertEquals(listOf(null, null), library.literaturePageNumbers(jstor.id))

            val facingPaper = LiteratureFollow.load(library, facing.id)
            assertTrue(facingPaper.sections.all { section ->
                "margin" !in section.title.lowercase() && section.sentences.none { "margin" in it.quote.lowercase() }
            })
            val stop = LiteratureFollow.verifiedStop(
                library,
                facing.id,
                facingPaper.sections.flatMap { it.sentences },
            )
            assertNotNull(stop)
            assertTrue(LiteraturePassages.bodySentence(stop.quote))
            assertFalse("margin" in stop.quote.lowercase())

            for (book in listOf(headed, plain, folio, jstor)) {
                assertQuotesMapOntoReader(library, book.id)
            }
            val facingReader = library.book(facing.id)
            val stopPage = requireNotNull(facingReader.page(stop.pdfPage))
            val stopPassage = assertNotNull(LiteratureQuoteMap.toPassage(facing.id, stopPage, stop.quote))
            assertEquals(stopPage.text.substring(stopPassage.start, stopPassage.end), stopPassage.text)
            assertEquals(LiteratureTextNorm.normalize(stop.quote), LiteratureTextNorm.normalize(stopPassage.text))
            for (candidate in LiteraturePassages.extractCandidates(library.literatureBook(facing.id))) {
                val readerPage = requireNotNull(facingReader.page(candidate.anchor.pdfPage))
                val passage = LiteratureQuoteMap.toPassage(facing.id, readerPage, candidate.anchor.quoteAnchor)
                if (passage == null) {
                    val quoteNorm = LiteratureTextNorm.normalize(candidate.anchor.quoteAnchor)
                    assertFalse(
                        quoteNorm in LiteratureTextNorm.normalize(readerPage.text),
                        "Contiguous reader text should highlight: ${candidate.anchor.quoteAnchor}",
                    )
                } else {
                    assertEquals(readerPage.text.substring(passage.start, passage.end), passage.text)
                    assertFalse("margin" in passage.text.lowercase())
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun stopAcceptsTheArgumentSentenceFromAFakeOcrCheck() {
        val weak = FollowSentence("b", 1, "The plants grew in the shade and stayed green today.")
        val strong = FollowSentence(
            "b",
            1,
            "Therefore the keeper argued that evidence confirmed the claim about provenance.",
        )
        val picked = LiteratureSections.pickStop(listOf(weak, strong)) { true }
        assertEquals(strong.quote, picked?.quote)
        var checks = 0
        val gated = LiteratureSections.pickStop(listOf(weak, strong)) { sentence ->
            checks++
            sentence.quote.startsWith("Therefore")
        }
        assertEquals(strong.quote, gated?.quote)
        assertEquals(1, checks)
    }

    private fun assertQuotesMapOntoReader(library: TextbookLibrary, bookId: String) {
        val literature = library.literatureBook(bookId)
        val reader = library.book(bookId)
        val candidates = LiteraturePassages.extractCandidates(literature)
        assertTrue(candidates.isNotEmpty())
        for (candidate in candidates) {
            val readerPage = requireNotNull(reader.page(candidate.anchor.pdfPage))
            val passage = assertNotNull(
                LiteratureQuoteMap.toPassage(bookId, readerPage, candidate.anchor.quoteAnchor),
                candidate.anchor.quoteAnchor,
            )
            assertEquals(readerPage.text.substring(passage.start, passage.end), passage.text)
            assertEquals(
                LiteratureTextNorm.normalize(candidate.anchor.quoteAnchor),
                LiteratureTextNorm.normalize(passage.text),
            )
        }
    }

    private fun paper(vararg pages: String): Textbook = Textbook(
        id = "paper",
        title = "Paper",
        format = "pdf",
        pages = pages.mapIndexed { index, text -> BookPage(index + 1, text, guideRegions = listOf(GuideRegion(text))) },
    )

    private fun noHeadingPages(): List<String> = listOf(
        block(
            "The keeper argued that the repository preserved provenance across the first decade.",
            "Witnesses confirmed that written evidence stayed intact during the opening inspection.",
            "Curators separated verified artifacts from hearsay with a bound register.",
        ),
        block(
            "Members compared each specimen with the custody ledger before the annual meeting.",
            "However the archive argued that custody changed after the second inspection.",
            "Patrons recorded the missing labels before the committee adjourned for summer.",
        ),
        block(
            "The society retained duplicate minutes so later readers could check the chain.",
            "Clerks copied the accession numbers into a second volume that winter.",
            "Therefore the register claimed that evidence confirmed provenance beyond doubt.",
        ),
        block(
            "Visitors described the cabinets without altering the original shelf marks.",
            "The final inventory listed every accepted object in the west gallery.",
            "Scholars still cite that inventory when they reconstruct the collection.",
        ),
    )

    private fun block(vararg sentences: String) = (listOf("The Historical Journal") + sentences).joinToString("\n")

    private fun headingPdf(root: Path): Path = pdf(root, "Heading_Study.pdf", "Heading Study") { _ ->
        val titles = listOf("Introduction", "The Argument", "Conclusion")
        val bodies = listOf(
            listOf(
                "The keeper argued that the repository preserved provenance across decades.",
                "Witnesses confirmed that written evidence stayed intact during inspections.",
            ),
            listOf(
                "However the catalog argued that evidence confirmed a narrower claim about custody.",
                "Readers still cite the register when they reconstruct that custody.",
            ),
            listOf(
                "The final inventory claimed that the collection remained intact after the move.",
                "Scholars can still trace each object back to the original shelf mark.",
            ),
        )
        titles.indices.map { index ->
            listOf(
                "The Historical Journal" to 740f,
                titles[index] to 700f,
                bodies[index][0] to 660f,
                bodies[index][1] to 636f,
            )
        }
    }

    private fun plainPdf(root: Path): Path = pdf(root, "Plain_Study.pdf", "Plain Study") { _ ->
        val sentences = noHeadingPages().map { page ->
            page.lines().filter { it != "The Historical Journal" }
        }
        sentences.map { lines ->
            listOf("The Historical Journal" to 740f) + lines.mapIndexed { index, line -> line to (680f - index * 28f) }
        }
    }

    private fun folioPdf(root: Path): Path = pdf(root, "Folio_Study.pdf", "Folio Study", labelStart = 21) { _ ->
        (0 until 4).map { index ->
            listOf(
                "The keeper argued that the repository preserved provenance across decades." to 400f,
                "${20 + index}" to 36f,
            )
        }
    }

    private fun pdf(
        root: Path,
        name: String,
        title: String,
        labelStart: Int? = null,
        pages: (PDDocument) -> List<List<Pair<String, Float>>>,
    ): Path {
        val path = root.resolve(name)
        PDDocument().use { document ->
            document.documentInformation.title = title
            document.documentInformation.author = "A. Keeper"
            val sheets = pages(document)
            sheets.forEach { lines ->
                val sheet = PDPage()
                document.addPage(sheet)
                val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
                PDPageContentStream(document, sheet).use { stream ->
                    for ((text, y) in lines) {
                        stream.beginText()
                        stream.setFont(font, 12f)
                        stream.newLineAtOffset(72f, y)
                        stream.showText(text)
                        stream.endText()
                    }
                }
            }
            if (labelStart != null) {
                val labels = PDPageLabels(document)
                labels.setLabelItem(0, PDPageLabelRange().apply {
                    style = PDPageLabelRange.STYLE_DECIMAL
                    start = labelStart
                })
                document.documentCatalog.pageLabels = labels
            }
            document.save(path.toFile())
        }
        return path
    }
}
