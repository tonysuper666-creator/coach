package com.coach.desktop.books

import kotlinx.coroutines.runBlocking
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
        assertEquals(listOf(147, 148, null, 150), LiteraturePrintedPages.resolve(
            listOf(listOf(147), emptyList(), listOf(999), listOf(150)),
            emptyList(),
        ))
        assertEquals(listOf(147, 148, 149, 150), LiteraturePrintedPages.resolve(
            listOf(listOf(147), emptyList(), emptyList(), listOf(150)),
            listOf(100, 101, 102, 103),
        ))
        assertEquals(listOf(147), LiteraturePrintedPages.headerCandidates("The Historical Journal 147\nThe body starts here."))
        assertEquals(emptyList(), LiteraturePrintedPages.headerCandidates("The Historical Journal 2002\nThe body starts here."))
        assertEquals("第 20 页", LiteraturePrintedPages.display(2, 20))
        assertEquals("PDF 第 2 页", LiteraturePrintedPages.display(2, null))
        assertFalse("印刷第" in LiteraturePrintedPages.display(2, 20))
        assertFalse("印刷页码" in LiteraturePrintedPages.display(2, null))
        assertNull(LiteraturePrintedPages.parseFolio("xiv"))
        assertNull(LiteraturePrintedPages.parseFolio("HUNTER 1989"))
        assertEquals(20, LiteraturePrintedPages.parseFolio("p. 20"))
        assertEquals(147, LiteraturePrintedPages.rangeStart("Notes Rec. R. Soc. Lond. 56 (2) 147-166"))
        assertEquals(147, LiteraturePrintedPages.rangeStart("147\u2013163"))
        assertNull(LiteraturePrintedPages.rangeStart("Vol. 56, No. 2 (May, 2002)"))
        assertEquals(
            listOf(147),
            LiteraturePrintedPages.headerCandidates("Notes Rec. R. Soc. Lond. 147-166\nThe body starts here."),
        )
        assertEquals(
            emptyList(),
            LiteraturePrintedPages.headerCandidates(
                """
                Journal cover
                Author(s): A Keeper
                Source: Notes and Records, pp. 147-163
                Published by: Royal Society
                Stable URL: https://example.test/stable
                Downloaded from the archive on Tuesday.
                """.trimIndent(),
            ),
        )
        // The page before a folio chain can take its own label. A cover further back stays empty.
        assertEquals(
            listOf(null, 147, 148, 149),
            LiteraturePrintedPages.resolve(
                listOf(emptyList(), emptyList(), listOf(148), listOf(149)),
                listOf(999, 147, 148, 149),
            ),
        )
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

    @Test fun captionsNotesAndSplitHeadersAreNotSections() {
        val book = paper(
            """
            The Historical Journal
            Fig. 1 The cabinet
            The keeper argued that the repository preserved provenance across decades.
            Witnesses confirmed that written evidence stayed intact during inspections.
            """.trimIndent(),
            """
            The Historical Journal
            confirmed the provenance12
            However the archive argued that custody changed after the second inspection.
            Patrons recorded the missing labels before the committee adjourned for summer.
            """.trimIndent(),
            """
            T h e H i s t o r i c a l
            The society retained duplicate minutes so later readers could check the chain.
            Therefore the register claimed that evidence confirmed provenance beyond doubt.
            """.trimIndent(),
            """
            The Historical Journal
            Notes
            Smith, Title (London, 1989), pp. 12-14.
            The keeper argued that the repository preserved provenance across the last decade.
            """.trimIndent(),
        )
        val candidates = LiteraturePassages.extractCandidates(book)
        val sections = LiteratureSections.build(book, candidates)
        val titles = sections.map { it.title }
        assertTrue(sections.isNotEmpty(), titles.toString())
        assertTrue(titles.none { title ->
            val lower = title.lowercase()
            "fig" in lower || "notes" in lower || "historical" in lower
        }, titles.toString())
        assertTrue(sections.none { section -> section.sentences.any { "pp." in it.quote } }, titles.toString())
        assertTrue(titles.all { it == "提出问题" || it == "转折" || it == "收束" || it.startsWith("第 ") }, titles.toString())
    }

    @Test fun aSentenceContinuedOnTheNextPageIsNotAStop() {
        val book = paper(
            "The keeper argued that the repository preserved provenance across the opening inspection without a stop",
            """
            Preserved provenance across the later inspections and stayed intact.
            The keeper argued that the repository preserved provenance across decades.
            However the archive argued that custody changed after the second inspection.
            Therefore the register claimed that evidence confirmed provenance beyond doubt.
            Witnesses confirmed that written evidence stayed intact during inspections.
            Readers still cite the register when they reconstruct that custody.
            """.trimIndent(),
        )
        val candidates = LiteraturePassages.extractCandidates(book)
        val sections = LiteratureSections.build(book, candidates)
        val quotes = sections.flatMap { it.sentences }.map { it.quote }
        assertTrue(quotes.isNotEmpty())
        assertTrue(quotes.none { it.startsWith("Preserved provenance") })
        assertTrue(quotes.any { it.startsWith("The keeper argued") })
    }

    @Test fun onlyAnAbstractHeadingStagesTheRest() {
        val book = paper(
            """
            The Historical Journal
            Abstract
            The keeper argued that the repository preserved provenance across decades.
            Witnesses confirmed that written evidence stayed intact during inspections.
            """.trimIndent(),
            """
            The Historical Journal
            Confirmed The Provenance12
            However the archive argued that custody changed after the second inspection.
            Patrons recorded the missing labels before the committee adjourned for summer.
            Members compared each specimen with the custody ledger before the annual meeting.
            """.trimIndent(),
            """
            The Historical Journal
            Therefore the register claimed that evidence confirmed provenance beyond doubt.
            The society retained duplicate minutes so later readers could check the chain.
            Clerks copied the accession numbers into a second volume that winter.
            Scholars still cite that inventory when they reconstruct the collection.
            """.trimIndent(),
        )
        val sections = LiteratureSections.build(book, LiteraturePassages.extractCandidates(book))
        val titles = sections.map { it.title }
        assertEquals("Abstract", titles.first(), titles.toString())
        assertTrue(titles.none { "Provenance" in it || "12" in it }, titles.toString())
        assertTrue(titles.drop(1).all { it == "提出问题" || it == "转折" || it == "收束" || it.startsWith("第 ") }, titles.toString())
        assertTrue(sections.first().sentences.none { it.quote.startsWith("However") })
    }

    @Test fun catalogLineIsNotASectionTitle() {
        val book = paper(
            """
            Published in print: 23 September 2004
            This version: 2006
            The keeper argued that the repository preserved provenance across decades.
            Witnesses confirmed that written evidence stayed intact during inspections.
            However the archive argued that custody changed after the second inspection.
            Therefore the register claimed that evidence confirmed provenance beyond doubt.
            Readers still cite the register when they reconstruct that custody.
            Scholars can still trace each object back to the original shelf mark.
            """.trimIndent(),
        )
        val sections = LiteratureSections.build(book, LiteraturePassages.extractCandidates(book))
        val titles = sections.map { it.title }
        assertTrue(titles.none { "published" in it.lowercase() || "version" in it.lowercase() }, titles.toString())
        assertTrue(titles.all { it == "提出问题" || it == "转折" || it == "收束" || it.startsWith("第 ") }, titles.toString())
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
            val headerLine = library.importBook(headerLinePdf(root))
            assertEquals(listOf(20, 21, null, 23), library.literaturePageNumbers(headerLine.id))

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

    @Test fun clippedWordBesideAFigureIsNotASection() {
        val book = paper(
            """
            The Historical Journal
            Introduction
            The keeper argued that the repository preserved provenance across decades.
            Witnesses confirmed that written evidence stayed intact during inspections.
            """.trimIndent(),
            """
            The Historical Journal
            Display
            The theatre opened its doors for the visiting public that morning.
            The surgeon continued the demonstration after the theatre opened for visitors

            Cheselde
            n stayed beside the plate while the audience watched the demonstration.

            Figure 1. The plate shows the theatre from the gallery.
            Patrons recorded the missing labels before the committee adjourned for summer.
            """.trimIndent(),
            """
            The Historical Journal
            Sociability
            Members compared each specimen with the custody ledger before the annual meeting.
            However the archive argued that custody changed after the second inspection.
            """.trimIndent(),
            """
            The Historical Journal
            The surgeon William
            Cheselde
            The keeper argued that the repository preserved provenance across decades.
            Witnesses confirmed that written evidence stayed intact during inspections.
            """.trimIndent(),
        )
        val sections = LiteratureSections.build(book, LiteraturePassages.extractCandidates(book))
        val titles = sections.map { it.title }
        assertEquals(listOf("Introduction", "Display", "Sociability"), titles)
        val display = sections.first { it.title == "Display" }
        assertTrue(display.sentences.size >= 2, display.sentences.map { it.quote }.toString())
        assertTrue(display.sentences.any { "The theatre opened" in it.quote }, display.sentences.map { it.quote }.toString())
        assertTrue(display.sentences.any { "Patrons recorded" in it.quote }, display.sentences.map { it.quote }.toString())
    }

    @Test fun splitHeaderAndIllustrationGarbageStayOutOfSectionTitles() {
        val book = paper(
            """
            ESTABLISHING THE NEW SCIENCE
            The keeper argued that the repository preserved provenance across the first decade.
            Witnesses confirmed that written evidence stayed intact during the opening inspection.
            Curators separated verified artifacts from hearsay with a bound register.
            """.trimIndent(),
            """
            XQRT PLMN BVCD WKLP
            Members compared each specimen with the custody ledger before the annual meeting.
            However the archive argued that custody changed after the second inspection.
            Patrons recorded the missing labels before the committee adjourned for summer.
            """.trimIndent(),
            """
            ESTAB LIS HING THE NEW SCIENCE
            The society retained duplicate minutes so later readers could check the chain.
            Clerks copied the accession numbers into a second volume that winter.
            Therefore the register claimed that evidence confirmed provenance beyond doubt.
            """.trimIndent(),
            """
            ESTABLISHING THE NEW SCIENCE
            Visitors described the cabinets without altering the original shelf marks.
            The final inventory listed every accepted object in the west gallery.
            Scholars still cite that inventory when they reconstruct the collection.
            """.trimIndent(),
        )
        val sections = LiteratureSections.build(book, LiteraturePassages.extractCandidates(book))
        val titles = sections.map { it.title }
        assertTrue(sections.size in 3..8, titles.toString())
        assertEquals(listOf("提出问题", "转折", "收束"), titles)
        assertTrue(titles.none { "ESTAB" in it || "XQRT" in it || "SCIENCE" in it }, titles.toString())
        assertEquals(12, sections.sumOf { it.sentences.size })
    }

    @Test fun authorAndAffiliationDoNotLetTheAbstractSwallowThePaper() {
        val swallowed = paper(
            """
            Alice Marples
            University of Bristol
            Abstract
            The keeper argued that the repository preserved provenance across decades.
            Witnesses confirmed that written evidence stayed intact during inspections.
            """.trimIndent(),
            *noHeadingPages().drop(1).toTypedArray(),
        )
        val sections = LiteratureSections.build(swallowed, LiteraturePassages.extractCandidates(swallowed))
        val titles = sections.map { it.title }
        assertEquals("Abstract", titles.first(), titles.toString())
        assertTrue(titles.none { "Marples" in it || "University" in it || "Bristol" in it }, titles.toString())
        assertTrue(titles.drop(1).all { it == "提出问题" || it == "转折" || it == "收束" || it.startsWith("第 ") }, titles.toString())
        assertTrue(sections.first().sentences.all { it.pdfPage == 1 }, sections.first().sentences.map { it.pdfPage }.toString())
        assertTrue(sections.first().sentences.none { it.quote.startsWith("However") })
        assertTrue(sections.drop(1).sumOf { it.sentences.size } > sections.first().sentences.size)

        val outlined = paper(
            """
            Abstract
            The keeper argued that the repository preserved provenance across decades.
            Witnesses confirmed that written evidence stayed intact during inspections.
            """.trimIndent(),
            """
            Introduction
            However the archive argued that custody changed after the second inspection.
            Patrons recorded the missing labels before the committee adjourned for summer.
            """.trimIndent(),
            """
            The Economy of Curiosity
            Therefore the register claimed that evidence confirmed provenance beyond doubt.
            Scholars still cite that inventory when they reconstruct the collection.
            """.trimIndent(),
        )
        assertEquals(
            listOf("Abstract", "Introduction", "The Economy of Curiosity"),
            LiteratureSections.build(outlined, LiteraturePassages.extractCandidates(outlined)).map { it.title },
        )
    }

    @Test fun catalogLinesAndAuthorBylineAreNotSectionTitles() {
        val pages = noHeadingPages().toMutableList()
        pages[0] = """
            REFERENCE ARTICLE
            Michael Hunter
            Subject: Biographical Studies
            The keeper argued that the repository preserved provenance across the first decade.
            Witnesses confirmed that written evidence stayed intact during the opening inspection.
            Curators separated verified artifacts from hearsay with a bound register.
        """.trimIndent()
        val sections = LiteratureSections.build(paper(*pages.toTypedArray()), LiteraturePassages.extractCandidates(paper(*pages.toTypedArray())))
        val titles = sections.map { it.title }
        assertTrue(titles.none {
            val lower = it.lowercase()
            "subject" in lower || "reference" in lower || "hunter" in lower || "biographical" in lower
        }, titles.toString())
        assertTrue(titles.all { it == "提出问题" || it == "转折" || it == "收束" || it.startsWith("第 ") }, titles.toString())
        assertTrue(sections.size in 3..8, titles.toString())
    }

    @Test fun blankLineInsideAPageDoesNotMakeAStopFromHalfASentence() {
        val book = paper(
            """
            The keeper argued that the repository preserved provenance across the opening inspection without a stop

            Michael Hunter continued the same sentence after the blank line in the middle of the page.
            The keeper argued that the repository preserved provenance across decades.
            However the archive argued that custody changed after the second inspection.
            Therefore the register claimed that evidence confirmed provenance beyond doubt.
            Witnesses confirmed that written evidence stayed intact during inspections.
            Readers still cite the register when they reconstruct that custody.
            """.trimIndent(),
        )
        val sections = LiteratureSections.build(book, LiteraturePassages.extractCandidates(book))
        val quotes = sections.flatMap { it.sentences }.map { it.quote }
        assertTrue(quotes.none { it.startsWith("Michael Hunter") }, quotes.toString())
        assertTrue(quotes.any { it.startsWith("The keeper argued that the repository preserved provenance across decades") })
        val stop = LiteratureSections.pickStop(sections.flatMap { it.sentences }) { true }
        assertNotNull(stop)
        assertFalse(stop.quote.startsWith("Michael Hunter"))
        assertTrue(stop.quote.trim().last() == '.')
    }

    @Test fun displayTitleDecodesEntitiesAndPrefersPaperTitleOverFileSlug() {
        val entity = paper("Abstract\nThe keeper argued that the repository preserved provenance across decades.").copy(
            title = "Society&#x2019;s Curiosity",
        )
        val decoded = literatureDisplayTitle(entity)
        assertTrue("\u2019" in decoded, decoded)
        assertFalse("&#x" in decoded, decoded)
        val slug = entity.copy(
            title = "Hunter-Between-Cabinet-1989",
            literatureMeta = LiteraturePaperMeta("paper", "Establishing the New Science", "Michael Hunter", "1989"),
        )
        assertEquals("Establishing the New Science", literatureDisplayTitle(slug))
        assertTrue(LiteratureMetadata.isFileSlug("Hunter-Between-Cabinet-1989"))
        assertFalse(LiteratureMetadata.isFileSlug("Society&#x2019;s Curiosity"))
    }

    @Test fun fileSlugPdfUsesTheFrontPageTitle() {
        val root = Files.createTempDirectory("coach-follow-slug-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val imported = library.importBook(slugTitlePdf(root))
            val paper = LiteratureFollow.load(library, imported.id)
            assertEquals("Establishing the New Science", paper.title)
            assertFalse("Hunter-Establishing" in paper.title)
            val second = imported.copy(title = "Marples-Society-Curiosity-2019")
            val shown = literatureDisplayTitle(
                second.copy(literatureMeta = LiteraturePaperMeta(second.id, "Society&#x2019;s Curiosity")),
            )
            assertEquals("Society\u2019s Curiosity", shown)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun articleRangeAndBuriedFooterBecomePrintedPages() {
        val root = Files.createTempDirectory("coach-follow-folio-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val ranged = library.importBook(articleRangePdf(root))
            assertEquals(listOf(null, 147, 148, 149), library.literaturePageNumbers(ranged.id))
            val buried = library.importBook(buriedFolioPdf(root))
            assertEquals(listOf(147, 148, 149), library.literaturePageNumbers(buried.id))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun quoteMissClearsWhenThePageOrTheBookChanges() {
        val root = Files.createTempDirectory("coach-follow-miss-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val first = library.importBook(folioPdf(root))
            val second = library.importBook(plainPdf(root))
            val courseId = library.withCourseDatabase { db ->
                CourseStore.createCourse(db, "历史文献", CourseExamProfile(), listOf(first.id, second.id)).first
            }
            val controller = TextbookController(library)
            val missing = "This sentence is not printed on the page at all."
            runBlocking {
                controller.jumpToLiteratureQuote(courseId, first.id, 1, missing)
                assertEquals(LITERATURE_QUOTE_MISS, controller.state.value.literatureQuoteMiss)
                controller.page(1)
                assertEquals(LITERATURE_QUOTE_MISS, controller.state.value.literatureQuoteMiss)
                controller.page(2)
                assertNull(controller.state.value.literatureQuoteMiss)
                controller.jumpToLiteratureQuote(courseId, first.id, 1, missing)
                assertEquals(1, controller.state.value.page)
                assertEquals(LITERATURE_QUOTE_MISS, controller.state.value.literatureQuoteMiss)
                controller.openBook(second.id)
                assertNull(controller.state.value.literatureQuoteMiss)
                assertEquals(second.id, controller.state.value.book?.id)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
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

    private fun headerLinePdf(root: Path): Path = pdf(root, "Header_Line.pdf", "Header Line", labelStart = 90) { _ ->
        (0 until 4).map { index ->
            val printed = if (index == 2) 99 else 20 + index
            listOf(
                "The Historical Journal $printed" to 740f,
                "The keeper argued that the repository preserved provenance across decades." to 680f,
                "Witnesses confirmed that written evidence stayed intact during inspections." to 656f,
            )
        }
    }

    private fun slugTitlePdf(root: Path): Path = pdf(root, "Hunter-Establishing-Science-1989.pdf", "Hunter-Establishing-Science-1989") { _ ->
        listOf(
            listOf(
                "Establishing the New Science" to 740f,
                "Michael Hunter" to 710f,
                "University of Cambridge" to 680f,
                "Abstract" to 650f,
                "The keeper argued that the repository preserved provenance across decades." to 620f,
            ),
        )
    }

    private fun articleRangePdf(root: Path): Path = pdf(root, "Article_Range.pdf", "Article Range", labelStart = 200) { _ ->
        listOf(
            listOf(
                "Journal cover" to 740f,
                "Author(s): A Keeper" to 710f,
                "Source: Notes and Records pp. 147-163" to 680f,
                "Published by: Royal Society" to 650f,
                "Stable URL: https://example.test/stable" to 620f,
                "Downloaded from the archive on Tuesday." to 590f,
            ),
            listOf(
                "Notes Rec. R. Soc. Lond. 147-166" to 740f,
                "The keeper argued that the repository preserved provenance across decades." to 680f,
                "Witnesses confirmed that written evidence stayed intact during inspections." to 650f,
            ),
            listOf(
                "The keeper argued that the repository preserved provenance across decades." to 400f,
                "148" to 36f,
            ),
            listOf(
                "The keeper argued that the repository preserved provenance across decades." to 400f,
                "149" to 36f,
            ),
        )
    }

    private fun buriedFolioPdf(root: Path): Path = pdf(root, "Buried_Folio.pdf", "Buried Folio", labelStart = 200) { _ ->
        listOf(
            listOf(
                "Journal footer line without digits." to 18f,
                "Downloaded from the archive on Tuesday." to 40f,
                "147" to 64f,
                "The keeper argued that the repository preserved provenance across decades." to 400f,
                "Witnesses confirmed that written evidence stayed intact during inspections." to 430f,
                "Notes Rec header without a number." to 740f,
            ),
            listOf(
                "The keeper argued that the repository preserved provenance across decades." to 400f,
                "148" to 36f,
            ),
            listOf(
                "The keeper argued that the repository preserved provenance across decades." to 400f,
                "149" to 36f,
            ),
        )
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
