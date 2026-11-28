package com.coach.desktop.books

import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class LiteratureGuideTest {
    private val exactOcr = LiteratureOcrVerifier { book, pdfPage -> book.page(pdfPage)?.text.orEmpty() }

    private val books = (1..3).map { b ->
        val text = "A unique—and exact—claim about sample $b. Second sentence confirms growth in plot $b."
        Textbook(
            id = "paper-$b",
            title = "Paper &amp; Study $b",
            format = "pdf",
            pages = listOf(BookPage(1, text, guideRegions = listOf(GuideRegion(text)))),
            literatureMeta = LiteraturePaperMeta("paper-$b", "Paper & Study $b", "Author$b", "${2000 + b}"),
        )
    }
    private val input = LiteratureInput(books, listOf("What changed?", "How did the samples differ?"))
    private val body = LiteratureGuideFixture.reply(input.request())

    @Test fun requestOmitsStopsQuizAndSectionsAndCarriesAuthorYear() {
        val req = input.request()
        assertEquals("literatureGuide", req["kind"]?.jsonPrimitive?.content)
        assertNull(req["stops"])
        assertNull(req["quiz"])
        val papers = req.getValue("papers").jsonArray.map { it.jsonObject }
        assertEquals(3, papers.size)
        assertEquals("Author1", papers[0]["author"]?.jsonPrimitive?.content)
        assertEquals("2001", papers[0]["year"]?.jsonPrimitive?.content)
        assertEquals("Paper & Study 1", papers[0]["title"]?.jsonPrimitive?.content)
        val blocks = req.getValue("blocks").jsonArray.map { it.jsonObject }
        assertTrue(blocks.isNotEmpty())
        assertTrue(blocks.all { "sectionId" !in it && "section" !in it })
        assertTrue(blocks.all { "author" in it && "year" in it })
    }

    @Test fun verifiedMatchesByBookIdIgnoresExtrasAndRequiresEvidenceRefs() {
        val withExtra = LiteratureGuideFixture.reply(input.request(), includeExtraPaper = true)
        val first = input.verify(withExtra, exactOcr)
        val guide = assertNotNull(first.toComplete(books, input.questions))
        assertEquals(3, guide.papers.size)
        assertEquals(books.map { it.id }, guide.papers.map { it.bookId })
        assertEquals(2, guide.answers.size)
        assertTrue(guide.answers.all { a -> a.sentences.size == 2 && a.sentences.all { it.sources.isNotEmpty() } })

        // Missing ref on an answer sentence fails only that answer item (not the whole guide).
        val badAnswers = buildJsonArray {
            add(withExtra.getValue("answers").jsonArray[0])
            add(buildJsonObject {
                put("questionIndex", 1)
                put("answerable", true)
                put("sentences", buildJsonArray {
                    add(buildJsonObject { put("text", "没有引用的第一句。"); put("refs", buildJsonArray {}) })
                    add(buildJsonObject { put("text", "第二句。"); put("refs", buildJsonArray { add(0) }) })
                })
            })
        }
        val partial = input.verify(JsonObject(withExtra + ("answers" to badAnswers)), exactOcr)
        assertNull(partial.toComplete(books, input.questions))
        assertEquals(3, partial.papers.size)
        assertTrue(0 in partial.answers)
        assertFalse(1 in partial.answers)
        assertEquals(listOf("answer:1"), partial.failures.map { it.itemKey })
        assertEquals("missing_ref", partial.failures.single().rule)
    }

    @Test fun partialScanUsesLocalOcrWithoutChangingReaderOffsets() {
        val root = Files.createTempDirectory("coach-guide-ocr-")
        try {
            val recognized = "Shade reduced evaporation in this sample."
            var calls = 0
            val library = TextbookLibrary(root.resolve("library"), ocr = BookOcr { _, _ -> calls++; recognized })
            val original = library.importBook(LiteratureGuideFixture.pdf(root, 1, partialLayer = true))
            val recovered = library.literatureBook(original.id)
            assertEquals(1, calls)
            assertEquals(recognized, recovered.page(2)!!.text)
            assertEquals(original.page(2)!!.text, library.book(original.id).page(2)!!.text)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun rejectsPendingOcrAndControlChars() {
        assertFails {
            LiteratureInput(
                books.map { it.copy(pages = listOf(BookPage(1, "temporary", ocrPending = true))) },
                input.questions,
            )
        }
        assertFalse(LiteraturePassages.bodySentence("The archive recorded dif\u0000erent observations clearly."))
        assertFalse(LiteraturePassages.bodySentence("The archive recorded dif\uFFFDrent observations clearly."))
        assertFalse(LiteraturePassages.bodySentence("The archive recorded control\u0007 characters clearly."))
        assertTrue(LiteraturePassages.bodySentence("The archive recorded different observations clearly."))
    }

    @Test fun sharedCandidateFilterDropsEndnotesFooterNotesBannerAndHalfSentences() {
        fun body(n: Int): BookPage {
            val text = "The archive recorded distinct field observation number $n across the whole season."
            return BookPage(n, text, guideRegions = listOf(GuideRegion(text)))
        }
        val hunterBody = listOf(
            "The repository kept a public record and later writers returned to that record.",
            "Visitors described the cabinets in enough detail to reconstruct the early arrangement.",
            "Minutes from the weekly meeting still name the keeper who opened the doors.",
            "",
            "57",
            "Later writers judged the rooms by a standard that did not yet exist for this collection.",
        ).joinToString("\n")
        val hunterNote = "57\nLater writers judged the rooms by a standard that did not yet exist for this collection."
        val hunter = Textbook(
            "hunter-note",
            "Note Shape",
            "pdf",
            (1..20).map(::body) + BookPage(
                21,
                hunterBody,
                guideRegions = listOf(GuideRegion(hunterBody), GuideRegion(hunterNote)),
            ),
        )
        val hunterExcerpts = LiteraturePassages.extractCandidates(hunter)
        assertTrue(hunterExcerpts.any { it.anchor.pdfPage == 21 && "public record" in it.anchor.quoteAnchor })
        assertTrue(hunterExcerpts.none { "did not yet exist" in it.anchor.quoteAnchor })

        val fontes = Textbook(
            "fontes-notes",
            "Endnote Shape",
            "pdf",
            (1..15).map(::body) + (16..20).map { n ->
                val notes = """
                    $n. The cataloguer copied a title from an earlier list and added a short remark about it.
                    The note continues as a full sentence after the marker and must stay out of the pool.
                    ${n + 40}. Another end note repeats a bibliographic aside without stating the paper claim.
                    ${n + 41}. A third numbered remark sits in the endnote region and looks like a body sentence.
                    ${n + 42}. A fourth numbered remark also looks like an ordinary sentence about the collection.
                """.trimIndent()
                BookPage(n, notes, guideRegions = listOf(GuideRegion(notes)))
            },
        )
        val fontesExcerpts = LiteraturePassages.extractCandidates(fontes)
        assertTrue(fontesExcerpts.any { it.anchor.pdfPage < 16 })
        assertTrue(fontesExcerpts.none { it.anchor.pdfPage >= 16 })
        assertTrue(fontesExcerpts.none { "endnote region" in it.anchor.quoteAnchor })

        val heading = """
            The register still held a public sentence before the notes heading on this page.
            Notes
            57. Later writers judged the rooms by a standard that did not yet exist for this collection.
        """.trimIndent()
        val headedQuotes = LiteraturePassages.extractCandidates(
            Textbook(
                "notes-heading",
                "Heading Shape",
                "pdf",
                listOf(body(1), BookPage(2, heading, guideRegions = listOf(GuideRegion(heading)))),
            ),
        ).map { it.anchor.quoteAnchor }
        assertTrue(headedQuotes.any { "before the notes heading" in it })
        assertTrue(headedQuotes.none { "did not yet exist" in it })

        val page1 = "The study places the public repository in a longer argument that runs from the work of"
        val page2 = """
            Downloaded from the public archive on 3 June 2006.

            Niles Carter to the recorded century as a whole. The society kept a public repository for visiting scholars during that decade.

            The argument stops halfway through the public catalog and then

            Niles Carter to the recorded century as a whole. Later writers compared the rooms with a standard written down in the register.

            Visitors left the description unfinished just before the name and

            Ada Quentin to the recorded century as a whole. Shade reduced evaporation in this sample because the keepers closed the south windows.
        """.trimIndent()
        val odnbBook = Textbook(
            "odnb-halves",
            "Half Shape",
            "pdf",
            listOf(
                BookPage(1, page1, guideRegions = listOf(GuideRegion(page1))),
                BookPage(2, page2, guideRegions = listOf(GuideRegion(page2))),
            ),
        )
        val odnbExcerpts = LiteraturePassages.extractCandidates(odnbBook)
        val odnbQuotes = odnbExcerpts.map { it.anchor.quoteAnchor }
        assertTrue(odnbQuotes.none { "Downloaded from" in it }, odnbQuotes.joinToString("\n"))
        assertTrue(odnbQuotes.none { it.trim().startsWith("Niles Carter") }, odnbQuotes.joinToString("\n"))
        assertTrue(odnbQuotes.any { "visiting scholars" in it })
        assertTrue(odnbQuotes.any { "written down in the register" in it })
        assertTrue(odnbQuotes.any { "south windows" in it })
        assertTrue(odnbQuotes.any { "catalog and then" in it && "as a whole" in it }, odnbQuotes.joinToString("\n"))
        val joined = odnbExcerpts.first { "catalog and then" in it.anchor.quoteAnchor }
        val joinedPage = odnbBook.page(joined.anchor.pdfPage)!!.text
        assertEquals(joined.anchor.quoteAnchor, joinedPage.substring(joined.anchor.startUtf16, joined.anchor.endUtf16))
        assertTrue("\n\n" in joined.anchor.quoteAnchor)

        val halvesOnly = """
            Downloaded from the public archive on 3 June 2006.

            Niles Carter to the recorded century as a whole.

            The argument stops halfway through the public catalog and then

            Ada Quentin to the recorded century as a whole.
        """.trimIndent()
        val halfQuotes = LiteraturePassages.extractCandidates(
            Textbook(
                "halves-only",
                "Halves Only",
                "pdf",
                listOf(
                    BookPage(1, page1, guideRegions = listOf(GuideRegion(page1))),
                    BookPage(2, halvesOnly, guideRegions = listOf(GuideRegion(halvesOnly))),
                ),
            ),
        ).map { it.anchor.quoteAnchor }
        assertTrue(halfQuotes.any { "catalog and then" in it && "as a whole" in it }, halfQuotes.joinToString("\n"))
        assertTrue(halfQuotes.none { "Downloaded from" in it || it.trim().startsWith("Niles Carter") }, halfQuotes.joinToString("\n"))
    }

    @Test fun joinsDoubleSpacedLinesKeepsAbbreviationsAndDropsFooterShapes() {
        fun quotesOf(text: String) = LiteraturePassages.extractCandidates(
            Textbook("shape", "Shape", "pdf", listOf(BookPage(1, text, guideRegions = listOf(GuideRegion(text))))),
        ).map { it.anchor.quoteAnchor }

        val spaced = """
            The society kept a public

            repository for visiting scholars

            during that decade. The keepers

            closed the south windows.
        """.trimIndent()
        val spacedQuotes = quotesOf(spaced)
        assertTrue(spacedQuotes.any { "public" in it && "repository" in it && "visiting scholars" in it && "during that decade." in it }, spacedQuotes.joinToString("\n"))
        assertTrue(spacedQuotes.any { "keepers" in it && "south windows" in it }, spacedQuotes.joinToString("\n"))
        assertTrue(spacedQuotes.any { "\n\n" in it })

        val abbrev = "The society printed the list in Ph.Tr. and the keepers compared those notes with the field book."
        val abbrevQuotes = quotesOf(abbrev)
        assertEquals(1, abbrevQuotes.size, abbrevQuotes.joinToString("\n"))
        assertTrue("Ph.Tr." in abbrevQuotes.single() && "field book" in abbrevQuotes.single())

        val abbrevBlank = "The society printed the list in Ph.Tr.\n\nand the keepers compared those notes with the field book."
        val blankQuotes = quotesOf(abbrevBlank)
        assertEquals(1, blankQuotes.size, blankQuotes.joinToString("\n"))
        assertTrue("Ph.Tr." in blankQuotes.single() && "field book" in blankQuotes.single())

        val ratio = "The measured ratio was 3.14 and the keepers recorded it in the field book."
        assertEquals(1, quotesOf(ratio).size)

        val body = "The repository kept a public record and later writers returned to that record."
        val pageCite = "The implication is that the keeper moved the cabinets into one smaller room (Hunter, 1989), p. 21."
        val seeVolume = "For the importance of the early lists of the same plants see V."
        val onWhom = "Several apprentices learned the same beds (on whom see Hunter, The Gardeners, 1989)."
        val mixed = listOf(
            body,
            "Visitors described the cabinets in enough detail to reconstruct the early arrangement.",
            "Minutes from the weekly meeting still name the keeper who opened the doors.",
            "Shade reduced evaporation in this sample because the keepers closed the south windows.",
            pageCite,
            seeVolume,
            onWhom,
        ).joinToString("\n\n")
        val mixedQuotes = quotesOf(mixed)
        assertTrue(mixedQuotes.any { "public record" in it }, mixedQuotes.joinToString("\n"))
        assertTrue(mixedQuotes.none { "p. 21" in it || "see V." in it || "on whom see" in it }, mixedQuotes.joinToString("\n"))

        val before = (1..8).joinToString("\n") { n ->
            "The repository kept a public record number $n across the whole season."
        }
        val tail = (1..14).joinToString("\n") { n ->
            "Visitors described cabinet number $n in enough detail to reconstruct the arrangement."
        }
        val noteSentence = "The later note says the keeper left the public cabinets open for visitors."
        val noted = "$before\n56\n$noteSentence\n$tail"
        val notedQuotes = quotesOf(noted)
        assertTrue(notedQuotes.any { "public record number 1" in it }, notedQuotes.joinToString("\n"))
        assertTrue(notedQuotes.none { "public cabinets open" in it }, notedQuotes.joinToString("\n"))
        val noteStart = noted.indexOf(noteSentence)
        val noteBook = Textbook("shape", "Shape", "pdf", listOf(BookPage(1, noted, guideRegions = listOf(GuideRegion(noted)))))
        val (noteHit, noteRule) = LiteratureCitationCheck.check(
            listOf(GuideExcerpt(noteBook, BookCompanionAnchor(1, "n56", noteStart, noteStart + noteSentence.length, noteSentence), GuideRegion(noted))),
            0,
            "shape",
            exactOcr,
        )
        assertNull(noteHit)
        assertTrue(noteRule.startsWith("footer_note:"))
        assertTrue(noteRule.substringAfter(':') != "footer_note")

        val folioQuotes = quotesOf("21\n$before")
        assertEquals(8, folioQuotes.size, folioQuotes.joinToString("\n"))
        assertTrue(folioQuotes.any { "public record number 1" in it })

        val attached = "$before\nThe repository kept a public record of the weekly meeting.2\n" +
            "The repository therefore kept a public record for the visitors who came that season."
        val attachedQuotes = quotesOf(attached)
        assertTrue(attachedQuotes.any { "weekly meeting" in it }, attachedQuotes.joinToString("\n"))
        assertTrue(attachedQuotes.any { "visitors who came" in it }, attachedQuotes.joinToString("\n"))
        val bodyAfterNote = "The repository therefore kept a public record for the visitors who came that season."
        val bodyStart = attached.indexOf(bodyAfterNote)
        val bodyBook = Textbook("shape", "Shape", "pdf", listOf(BookPage(1, attached, guideRegions = listOf(GuideRegion(attached)))))
        val (bodyHit, bodyRule) = LiteratureCitationCheck.check(
            listOf(GuideExcerpt(bodyBook, BookCompanionAnchor(1, "body", bodyStart, bodyStart + bodyAfterNote.length, bodyAfterNote), GuideRegion(attached))),
            0,
            "shape",
            exactOcr,
        )
        assertNotNull(bodyHit)
        assertEquals("ok", bodyRule)

        val carried = "The archive closed the public cabinet for the season. 19 The society kept a public repository for visiting scholars during that decade."
        val carriedQuotes = quotesOf(carried)
        assertEquals(2, carriedQuotes.size, carriedQuotes.joinToString("\n"))
        assertTrue(carriedQuotes.all { it in carried })
        assertTrue(carriedQuotes.none { it.trim().startsWith("19") })
        assertTrue(carriedQuotes.any { "visiting scholars" in it })

        val sig = "The plate is marked Sig. A later keeper described the specimen in the field book."
        val sigQuotes = quotesOf(sig)
        assertEquals(1, sigQuotes.size, sigQuotes.joinToString("\n"))
        assertTrue("Sig. A later keeper" in sigQuotes.single())

        val phil = "The list ends with the item published Phil. Records, after the manner of the Ph. Transactions. A later keeper was not the secretary of that society."
        val philQuotes = quotesOf(phil)
        assertTrue(philQuotes.none { it.startsWith("Records,") }, philQuotes.joinToString("\n"))
        assertTrue(philQuotes.any { "published Phil. Records" in it && "Ph. Transactions." in it }, philQuotes.joinToString("\n"))
        assertTrue(philQuotes.any { "later keeper was not the secretary" in it }, philQuotes.joinToString("\n"))

        val glued = "The label read approx.more notes in the field book before the season closed."
        val gluedQuotes = quotesOf(glued)
        assertEquals(1, gluedQuotes.size, gluedQuotes.joinToString("\n"))
        assertTrue("approx.more" in gluedQuotes.single())

        val quoted = "The list ends with one item published Phil. Records, after the manner of the Ph. Transactions.' A later keeper was not the secretary of that society."
        val quotedQuotes = quotesOf(quoted)
        assertEquals(1, quotedQuotes.size, quotedQuotes.joinToString("\n"))
        assertTrue("published Phil." in quotedQuotes.single() && "later keeper" in quotedQuotes.single())
    }

    @Test fun layoutFootnoteZoneDropsTheBottomNoteAndKeepsTheBodyAfterACallout() {
        val body = "The repository kept a public record and later writers returned to that record."
        val afterCallout = "The repository therefore kept a public record for the visitors who came that season."
        val proseNote = "It remains useful to record that the same cabinets stayed in the smaller room."
        val text = "$body" + "8\n$afterCallout\n\n$proseNote"
        val noteStart = text.indexOf(proseNote)
        val region = GuideRegion(text, footnoteRanges = listOf(noteStart until noteStart + proseNote.length))
        val book = Textbook("shape", "Shape", "pdf", listOf(BookPage(1, text, guideRegions = listOf(region))))
        val quotes = LiteraturePassages.extractCandidates(book).map { it.anchor.quoteAnchor }
        assertTrue(quotes.any { "visitors who came" in it }, quotes.joinToString("\n"))
        assertTrue(quotes.none { it.trim().startsWith("8") || "smaller room" in it }, quotes.joinToString("\n"))
        val noteExcerpt = GuideExcerpt(
            book,
            BookCompanionAnchor(1, "note", noteStart, noteStart + proseNote.length, proseNote),
            region,
        )
        val (noteHit, noteRule) = LiteratureCitationCheck.check(listOf(noteExcerpt), 0, "shape", exactOcr)
        assertNull(noteHit)
        assertTrue(noteRule.startsWith("footer_note:"), noteRule)
        assertTrue("页脚" in noteRule, noteRule)
        val bodyQuote = quotes.first { "visitors who came" in it }
        val bodyStart = text.indexOf(bodyQuote)
        assertTrue(bodyStart >= 0, quotes.joinToString("\n"))
        val bodyExcerpt = GuideExcerpt(
            book,
            BookCompanionAnchor(1, "body", bodyStart, bodyStart + bodyQuote.length, bodyQuote),
            region,
        )
        val (bodyHit, bodyRule) = LiteratureCitationCheck.check(listOf(bodyExcerpt), 0, "shape", exactOcr)
        assertNotNull(bodyHit)
        assertEquals("ok", bodyRule)
    }

    @Test fun layoutFootnoteRangesNeedASeparatorAndSmallerTypeBelowIt() {
        val body = (0 until 8).map { i ->
            LiteraturePdfLayout.FootnoteLine(y = 40f + i * 12f, size = 10f, start = i * 20, end = i * 20 + 18)
        }
        val note = LiteraturePdfLayout.FootnoteLine(y = 156f, size = 8f, start = 200, end = 240)
        val noteNext = LiteraturePdfLayout.FootnoteLine(y = 166f, size = 8f, start = 241, end = 280)
        // A gap under the body is not a separator. The same lines stay body until a rule is present.
        assertTrue(LiteraturePdfLayout.layoutFootnoteRanges(body + note + noteNext, bodyHeight = 10f).isEmpty())
        val ruledNotes = LiteraturePdfLayout.layoutFootnoteRanges(body + note + noteNext, 10f, listOf(146f))
        assertTrue(ruledNotes.any { 200 in it && 239 in it }, ruledNotes.toString())
        assertTrue(ruledNotes.any { 241 in it }, ruledNotes.toString())
        assertTrue(ruledNotes.none { it.first < 200 }, ruledNotes.toString())

        val sameSize = LiteraturePdfLayout.FootnoteLine(y = 156f, size = 10f, start = 200, end = 240)
        assertTrue(LiteraturePdfLayout.layoutFootnoteRanges(body + sameSize, 10f, listOf(146f)).isEmpty())

        val tight = LiteraturePdfLayout.FootnoteLine(y = 136f, size = 8f, start = 200, end = 240)
        assertTrue(LiteraturePdfLayout.layoutFootnoteRanges(body + tight, 10f).isEmpty())

        val ruled = LiteraturePdfLayout.FootnoteLine(y = 124f, size = 8f, start = 200, end = 240)
        val byRule = LiteraturePdfLayout.layoutFootnoteRanges(body.dropLast(1) + ruled, 10f, listOf(118f))
        assertTrue(byRule.any { 200 in it }, byRule.toString())
        assertTrue(byRule.none { it.first < 200 }, byRule.toString())

        val ruleLine = LiteraturePdfLayout.FootnoteLine(y = 118f, size = 10f, start = 180, end = 190, rule = true)
        val afterRule = LiteraturePdfLayout.FootnoteLine(y = 126f, size = 8f, start = 200, end = 240)
        val byTextRule = LiteraturePdfLayout.layoutFootnoteRanges(body.dropLast(1) + ruleLine + afterRule, 10f)
        assertTrue(byTextRule.any { 200 in it }, byTextRule.toString())
        assertTrue(byTextRule.none { 180 in it }, byTextRule.toString())

        // Smaller lines above the rule stay body. The rule is the line between that block and the notes.
        val quote = (0 until 3).map { i ->
            LiteraturePdfLayout.FootnoteLine(y = 136f + i * 10f, size = 9f, start = 700 + i * 30, end = 724 + i * 30)
        }
        val belowQuote = listOf(
            LiteraturePdfLayout.FootnoteLine(y = 180f, size = 8f, start = 200, end = 240),
            LiteraturePdfLayout.FootnoteLine(y = 190f, size = 8f, start = 241, end = 280),
        )
        val quoteRanges = LiteraturePdfLayout.layoutFootnoteRanges(body + quote + belowQuote, 10f, listOf(170f))
        assertTrue(quoteRanges.any { 200 in it } && quoteRanges.any { 241 in it }, quoteRanges.toString())
        assertTrue(quoteRanges.none { range -> quote.any { it.start in range } }, quoteRanges.toString())

        val heading = (0 until 2).map { i ->
            LiteraturePdfLayout.FootnoteLine(y = 16f + i * 16f, size = 14f, start = i * 12, end = i * 12 + 8)
        }
        val underHeading = LiteraturePdfLayout.layoutFootnoteRanges(heading + body, 10f, listOf(48f))
        assertTrue(underHeading.isEmpty(), underHeading.toString())

        // A note block that starts above the old 55% cut is still a note when the rule is above it.
        val longNotes = (0 until 10).map { i ->
            LiteraturePdfLayout.FootnoteLine(y = 150f + i * 12f, size = 8f, start = 1000 + i * 40, end = 1030 + i * 40)
        }
        val longPage = body + longNotes
        val longSpan = longPage.maxOf { it.y } - longPage.minOf { it.y }
        val oldBottomCut = longPage.minOf { it.y } + longSpan * 0.55f
        assertTrue(longNotes.first().y < oldBottomCut, "note block starts above the old bottom cut")
        assertTrue(LiteraturePdfLayout.layoutFootnoteRanges(longPage, 10f).isEmpty())
        val longRanges = LiteraturePdfLayout.layoutFootnoteRanges(longPage, 10f, listOf(140f))
        assertTrue(longRanges.any { longNotes.first().start in it }, longRanges.toString())
        assertTrue(longRanges.any { longNotes.last().start in it }, longRanges.toString())
        assertTrue(longRanges.none { it.first < 1000 }, longRanges.toString())

        val middle = LiteraturePdfLayout.FootnoteLine(y = 80f, size = 8f, start = 400, end = 440)
        val afterMiddle = (0 until 4).map { i ->
            LiteraturePdfLayout.FootnoteLine(y = 100f + i * 12f, size = 10f, start = 500 + i * 20, end = 518 + i * 20)
        }
        assertTrue(LiteraturePdfLayout.layoutFootnoteRanges(body.take(3) + middle + afterMiddle, 10f, listOf(74f)).isEmpty())

        val folio = LiteraturePdfLayout.FootnoteLine(y = 280f, size = 10f, start = 1400, end = 1403)
        val withFolio = LiteraturePdfLayout.layoutFootnoteRanges(longPage + folio, 10f, listOf(140f))
        assertTrue(withFolio.any { longNotes.first().start in it }, withFolio.toString())
        assertTrue(withFolio.none { 1400 in it }, withFolio.toString())

        val glyphs = buildList {
            repeat(40) { add((it * 12f) to 10f) }
            repeat(80) { add((520f + it * 10f) to 8f) }
        }
        assertEquals(10f, LiteraturePdfLayout.proseBodyHeight(glyphs, columnMedian = 8f))
        assertEquals(10f, LiteraturePdfLayout.proseBodyHeight(List(40) { (it * 12f) to 10f }, columnMedian = 10f))
    }

    @Test fun abbreviationSentencesStayWholeWhenANoteMarkFollowsTheClosingQuote() {
        fun quotesOf(text: String) = LiteraturePassages.extractCandidates(
            Textbook("shape", "Shape", "pdf", listOf(BookPage(1, text, guideRegions = listOf(GuideRegion(text))))),
        ).map { it.anchor.quoteAnchor }

        val intro = "The catalog lists three later notes for the same cabinet."
        val second = "The second note was printed in the public record after the manner of the Ph. Transactions."
        val third = "The keeper was not the clerk of the R. Society when the list cited Ph.Tr. N.143 before the public business of that society.\u201912"
        val after = "Visitors described the cabinets in enough detail to reconstruct the early arrangement."
        val page = listOf(intro, second, third, after).joinToString("\n\n")
        val quotes = quotesOf(page)
        assertTrue(quotes.any { "Ph. Transactions." in it && "N.143" !in it }, quotes.joinToString("\n"))
        val cited = quotes.single { "N.143" in it }
        assertTrue("clerk of the R. Society" in cited, cited)
        assertFalse(cited.last().isDigit(), cited)
        assertTrue(cited.endsWith("\u2019"), cited)
        assertFalse("Visitors described" in cited, cited)
        assertTrue(quotes.any { "Visitors described" in it && "N.143" !in it }, quotes.joinToString("\n"))
        assertFalse(LiteraturePassages.isFootnoteSentence(cited))

        val prefix = "The plate in the public catalog is marked Sig. Maren Solano "
        val longBody = "recorded the same specimen in the field book "
        val signature = prefix + longBody.repeat(18) + "before the season closed."
        assertTrue(signature.length > 650, "length=${signature.length}")
        assertTrue(signature.length <= 1300, "length=${signature.length}")
        val signatureQuotes = quotesOf(signature)
        assertEquals(1, signatureQuotes.size, signatureQuotes.joinToString("\n"))
        assertTrue("marked Sig. Maren Solano" in signatureQuotes.single(), signatureQuotes.single())
        assertTrue(signatureQuotes.single().endsWith("season closed."))

        val plain = "The repository kept a public record " + "for the visitors who came ".repeat(28) + "before the season closed."
        assertTrue(plain.length > 650, "length=${plain.length}")
        assertTrue(quotesOf(plain).isEmpty(), quotesOf(plain).joinToString("\n"))

        // The closing quote can sit before the period, and the note digits can be their own line.
        val quoteThenPeriod = "The keeper was not the clerk of the R. Society when the list cited Ph.Tr. N.143 before the public business of that society\u2019.12"
        val periodQuotes = quotesOf(quoteThenPeriod)
        assertEquals(1, periodQuotes.size, periodQuotes.joinToString("\n"))
        assertTrue("N.143" in periodQuotes.single() && periodQuotes.single().endsWith("\u2019."))
        assertFalse(periodQuotes.single().last().isDigit())

        val head = "The keeper was not the clerk of the R."
        val tail = "Society when the list cited Ph.Tr. N.143 before the public business of that society\u2019."
        val following = "Visitors described the cabinets in enough detail to reconstruct the early arrangement."
        val split = "$head\n58\n$tail\n\n$following"
        val splitQuotes = quotesOf(split)
        val citedSplit = splitQuotes.single { "N.143" in it }
        assertTrue("clerk of the R." in citedSplit && "Society when" in citedSplit, citedSplit)
        assertTrue(citedSplit.endsWith("\u2019."), citedSplit)
        assertFalse("Visitors described" in citedSplit, citedSplit)
        assertTrue(splitQuotes.any { "Visitors described" in it && "N.143" !in it }, splitQuotes.joinToString("\n"))
    }

    @Test fun equalByteBudgetGuaranteesAtLeastFifteenSentencesPerPaper() {
        val smallPages = (1..2).map { n ->
            val lines = (1..10).joinToString("\n") { i ->
                "Marples observed curious specimen number ${n * 10 + i} because patrons recorded provenance."
            }
            BookPage(n, lines, guideRegions = listOf(GuideRegion(lines)))
        }
        val hugePages = (1..40).map { n ->
            val lines = (1..12).joinToString("\n") { i ->
                "Fontes documented extensive institutional archives on folio ${n * 100 + i} with detailed committee testimony and historical context across several decades."
            }
            BookPage(n, lines, guideRegions = listOf(GuideRegion(lines)))
        }
        val balancedBooks = listOf(
            Textbook("marples", "Marples Study", "pdf", smallPages, literatureMeta = LiteraturePaperMeta("marples", "Marples Study", "Marples", "2019")),
            Textbook("fontes-a", "Fontes A", "pdf", hugePages, literatureMeta = LiteraturePaperMeta("fontes-a", "Fontes A", "Fontes", "2002")),
            Textbook("fontes-b", "Fontes B", "pdf", hugePages, literatureMeta = LiteraturePaperMeta("fontes-b", "Fontes B", "Hunter", "1989")),
        )
        val selected = LiteratureInput(balancedBooks, listOf("What did Marples argue about provenance?"))
        assertTrue(selected.request().toString().toByteArray().size <= LiteratureInput.REQUEST_BYTE_BUDGET)
        val counts = selected.excerpts.groupingBy { it.book.id }.eachCount()
        assertTrue(counts.getValue("marples") >= 15, "Expected >= 15 sentences for Marples, got $counts")
        assertTrue(counts.getValue("fontes-a") >= 15, "Expected >= 15 sentences for Fontes A, got $counts")
        assertTrue(counts.getValue("fontes-b") >= 15, "Expected >= 15 sentences for Fontes B, got $counts")
    }

    @Test fun syntheticFacingPagesAndMarginaliaFilterWideGapsAndVerifyWithRealTesseractOcr() {
        val root = Files.createTempDirectory("coach-guide-facing-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val p1 = library.importBook(LiteratureGuideFixture.facingAndMarginaliaPdf(root))
            val p2 = library.importBook(LiteratureGuideFixture.pdf(root, 2))
            val p3 = library.importBook(LiteratureGuideFixture.pdf(root, 3))
            val litBooks = listOf(p1, p2, p3).map { library.literatureBook(it.id) }
            val guideInput = LiteratureInput(litBooks, listOf("How did provenance survive?"))
            val facingQuotes = guideInput.excerpts.filter { it.book.id == p1.id }.map { it.anchor.quoteAnchor }

            // Wide-gap inline marginalia sentence MUST be dropped in its entirety (not stitched without the middle line).
            assertTrue(facingQuotes.none { "margin note" in it || "early keeper organized" in it || "visiting naturalists" in it })
            // Smaller-font marginalia column on page 2 MUST also be dropped.
            assertTrue(facingQuotes.none { "marginal note" in it || "another hand" in it })
            // Clean body sentences on both pages MUST survive.
            assertTrue(facingQuotes.any { "provenance stayed intact" in it })
            assertTrue(facingQuotes.any { "transferred the records" in it.replace('\n', ' ') })

            // Real 300 dpi Tesseract OCR independent check (runs natively on Mac & Windows).
            val page1Ocr = library.ocrPdfPageForGuide(p1.id, 1)
            val goodQuote = "Therefore the catalog proved that provenance stayed intact across decades."
            val goodSim = LiteratureTextNorm.bestContiguousSimilarity(goodQuote, page1Ocr)
            assertTrue(goodSim >= LiteratureTextNorm.OCR_SIMILARITY_THRESHOLD, "Expected >= 0.98 OCR similarity, got $goodSim")

            val interleavedQuote = "The early keeper organized the repository and catalogued rare specimens See margin note 7. from visiting naturalists."
            val interleavedSim = LiteratureTextNorm.bestContiguousSimilarity(interleavedQuote, page1Ocr)
            // Even if an interleaved facing/margin sentence ever reached verification, OCR similarity must fail < 0.98 or be rejected by wide-gap.
            val fabricatedQuote = "Therefore the catalog proved that foreign facing margin column interleaved across decades."
            val fabSim = LiteratureTextNorm.bestContiguousSimilarity(fabricatedQuote, page1Ocr)
            assertTrue(fabSim < LiteratureTextNorm.OCR_SIMILARITY_THRESHOLD, "Expected < 0.98 for interleaved quote, got $fabSim")
            assertTrue(interleavedSim >= 0.0)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun syntheticCopyrightCoverAndBibliographicFootnotesAreFilteredOut() {
        val root = Files.createTempDirectory("coach-guide-cover-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val coverBook = library.importBook(LiteratureGuideFixture.copyrightCoverPdf(root))
            val lit = library.literatureBook(coverBook.id)
            val candidates = LiteraturePassages.extractCandidates(lit)
            val quotes = candidates.map { it.anchor.quoteAnchor }

            // Entire page 1 copyright cover (including the ordinary remark on page 1) must be excluded.
            assertTrue(candidates.all { it.anchor.pdfPage == 2 })
            assertTrue(quotes.none { "Copyright" in it || "syllabus schedule" in it || "CLA" in it })
            // Bibliographic footnote lines on page 2 must be excluded.
            assertTrue(quotes.none { "Woodbridge" in it || "pp. 12-15" in it || "Ibid." in it })
            // Clean body sentences on page 2 survive.
            assertTrue(quotes.any { "inventory system transformed" in it })
            // Author and year extracted from cover page; title must NOT capture ISBN/copyright boilerplate.
            assertEquals("E. Mercer", lit.literatureMeta?.author)
            assertEquals("1995", lit.literatureMeta?.year)
            assertFalse("ISBN" in lit.literatureMeta!!.title, "Title must not be copyright line: ${lit.literatureMeta?.title}")
            assertFalse("Fair dealing" in lit.literatureMeta!!.title, "Title must not be copyright line: ${lit.literatureMeta?.title}")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun syntheticJstorWrongPageLabelsDecodeEntitiesAndUsePdfPageOnly() {
        val root = Files.createTempDirectory("coach-guide-jstor-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val jstor = library.importBook(LiteratureGuideFixture.jstorWrongPageLabelsPdf(root))
            val lit = library.literatureBook(jstor.id)

            // HTML entities decoded in title; author & year extracted from JSTOR cover.
            val meta = assertNotNull(lit.literatureMeta)
            assertFalse("&amp;" in meta.title || "&#8217;" in meta.title, "HTML entities not decoded: ${meta.title}")
            assertTrue("Curiosity & Collecting" in meta.title, "Unexpected title: ${meta.title}")
            assertEquals("Palmira Fontes da Costa", meta.author)
            assertEquals("2002", meta.year)

            // JSTOR cover page (page 1) skipped; all excerpts point to PDF page 2 with printedPage == null.
            val candidates = LiteraturePassages.extractCandidates(lit)
            assertTrue(candidates.isNotEmpty())
            assertTrue(candidates.all { it.anchor.pdfPage == 2 })
            assertTrue(candidates.all { it.region.printedPage == null })
            assertTrue(candidates.none { "JSTOR" in it.anchor.quoteAnchor || "Stable URL" in it.anchor.quoteAnchor })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun answerableFalseTriggersSingleTargetedRepairAndSucceeds() {
        val root = Files.createTempDirectory("coach-guide-repair-ok-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val imported = (1..3).map { library.importBook(LiteratureGuideFixture.pdf(root, it)) }
            val litBooks = imported.map { library.literatureBook(it.id) }
            val questions = listOf("样地植物生长有何规律？", "光照条件如何影响不同样地？")
            val guideInput = LiteratureInput(litBooks, questions)

            val ocr = CachingOcrVerifier { book, pdfPage -> library.ocrPdfPageForGuide(book.id, pdfPage) }
            // Pass 1: Q2 returns answerable=false, plus an extra paper card that must be ignored.
            val pass1Reply = LiteratureGuideFixture.reply(guideInput.request(), unanswerableIndex = 1, includeExtraPaper = true)
            val pass1Check = guideInput.verify(pass1Reply, ocr)
            assertNull(pass1Check.toComplete(litBooks, questions))
            assertEquals(3, pass1Check.papers.size)
            assertTrue(0 in pass1Check.answers)
            assertFalse(1 in pass1Check.answers)
            assertEquals(listOf("answer:1"), pass1Check.failures.map { it.itemKey })
            assertEquals("answerable_false", pass1Check.failures.single().rule)

            // Pass 2: targeted repair requests ONLY Q2, with <= 30 top relevant blocks across all books.
            val repairInput = guideInput.buildRepair(pass1Check)
            assertFalse(repairInput.needQuestion)
            assertFalse(repairInput.needThread)
            assertTrue(repairInput.missingBookIds.isEmpty())
            assertEquals(listOf(1), repairInput.failedQuestionIndices)
            assertTrue(repairInput.excerpts.size in 1..LiteratureInput.REPAIR_TOP_PER_ITEM)

            val pass2Reply = LiteratureGuideFixture.repairReply(repairInput.request(), unanswerable = false)
            val (pass2Check, merged) = repairInput.verifyAndMerge(pass1Check, pass2Reply, ocr)
            val complete = assertNotNull(merged.toComplete(litBooks, questions))
            assertEquals(3, complete.papers.size)
            assertEquals(2, complete.answers.size)
            assertTrue(pass2Check.failures.isEmpty())

            // Diagnostics file records both passes and raw replies.
            val diagPath = LiteratureDiagnostics.write(
                libraryDir = library.directory,
                courseId = "course-1",
                digest = LiteratureGuideStore.digest(guideInput.request()),
                passes = listOf(
                    GuidePassDiagnostic(1, "initial", 1000, guideInput.excerpts.size, pass1Reply, pass1Check),
                    GuidePassDiagnostic(2, "repair", 500, repairInput.excerpts.size, pass2Reply, pass2Check),
                ),
                finalVerification = merged,
                complete = true,
            )
            val diagJson = Json.parseToJsonElement(Files.readString(diagPath)).jsonObject
            assertEquals("complete", diagJson["status"]?.jsonPrimitive?.content)
            assertEquals(2, diagJson["calls"]?.jsonPrimitive?.int)
            assertEquals(2, diagJson.getValue("passes").jsonArray.size)
            assertEquals("answerable_false", diagJson.getValue("passes").jsonArray[0].jsonObject
                .getValue("failures").jsonArray[0].jsonObject["rule"]?.jsonPrimitive?.content)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun failedRepairDoesNotPersistCompleteGuideAndWritesDiagnostics() {
        val root = Files.createTempDirectory("coach-guide-repair-fail-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val imported = (1..3).map { library.importBook(LiteratureGuideFixture.pdf(root, it)) }
            val courseId = library.withCourseDatabase { db ->
                CourseStore.createCourse(db, "测试课程", CourseExamProfile(), imported.map { it.id }).first
            }
            val litBooks = imported.map { library.literatureBook(it.id) }
            val questions = listOf("第一题？", "第二题？")
            val guideInput = LiteratureInput(litBooks, questions)
            val key = LiteratureGuideStore.digest(guideInput.request())
            library.withCourseDatabase { LiteratureGuideStore.saveSelection(it, courseId, key, guideInput) }

            // Pass 1 fails Q2 (answerable=false); Pass 2 ALSO fails Q2 (answerable=false).
            val pass1Reply = LiteratureGuideFixture.reply(guideInput.request(), unanswerableIndex = 1)
            val pass1Check = guideInput.verify(pass1Reply, exactOcr)
            val repairInput = guideInput.buildRepair(pass1Check)
            val pass2Reply = LiteratureGuideFixture.repairReply(repairInput.request(), unanswerable = true)
            val (pass2Check, merged) = repairInput.verifyAndMerge(pass1Check, pass2Reply, exactOcr)

            assertNull(merged.toComplete(litBooks, questions))
            assertEquals(listOf("answer:1"), merged.failures.map { it.itemKey })
            // Never persisted as a completed guide!
            val cached = library.withCourseDatabase { LiteratureGuideStore.cached(it, courseId, key) }
            assertNull(cached, "Incomplete guide must NOT be stored as completed result")

            val diagPath = LiteratureDiagnostics.write(
                libraryDir = library.directory,
                courseId = courseId,
                digest = key,
                passes = listOf(
                    GuidePassDiagnostic(1, "initial", 1000, guideInput.excerpts.size, pass1Reply, pass1Check),
                    GuidePassDiagnostic(2, "repair", 500, repairInput.excerpts.size, pass2Reply, pass2Check),
                ),
                finalVerification = merged,
                complete = false,
            )
            val diagJson = Json.parseToJsonElement(Files.readString(diagPath)).jsonObject
            assertEquals("incomplete", diagJson["status"]?.jsonPrimitive?.content)
            assertEquals(1, diagJson.getValue("finalFailures").jsonArray.size)
            assertEquals("answer:1", diagJson.getValue("finalFailures").jsonArray[0].jsonObject["itemKey"]?.jsonPrimitive?.content)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun lowOcrSimilarityInvalidatesCitationAndEntersRepair() {
        val badOcr = LiteratureOcrVerifier { _, _ -> "Completely different unrelated scanned margin text on this page." }
        val pass1 = input.verify(body, badOcr)
        assertNull(pass1.toComplete(books, input.questions))
        assertTrue(pass1.ocrChecks.isNotEmpty())
        assertTrue(pass1.ocrChecks.all { !it.passed && it.similarity < LiteratureTextNorm.OCR_SIMILARITY_THRESHOLD })
        assertTrue(pass1.failures.any { it.rule == "ocr_similarity_low" })
        assertTrue(pass1.rejectedQuotes.isNotEmpty())
    }

    @Test fun jbig2AndJpeg2000DecodersRenderScannedPagesAndBlankRenderReportsOcrUnavailable() {
        javax.imageio.ImageIO.scanForPlugins()
        assertTrue(javax.imageio.ImageIO.getImageReadersByFormatName("JBIG2").hasNext(), "JBIG2 ImageIO reader must be registered")
        assertTrue(javax.imageio.ImageIO.getImageReadersByFormatName("JPEG2000").hasNext(), "JPEG 2000 ImageIO reader must be registered")

        val root = Files.createTempDirectory("coach-guide-codecs-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val jbig2Sentence = "Curiosity operated as a central category in early science."
            val jpxSentence = "Provenance records preserved custody across generations."

            val jbig2Pdf = LiteratureGuideFixture.jbig2ScannedPdf(root, jbig2Sentence)
            val jpxPdf = LiteratureGuideFixture.jpeg2000ScannedPdf(root, jpxSentence)
            val jbig2Book = library.importBook(jbig2Pdf)
            val jpxBook = library.importBook(jpxPdf)

            // Real 300 dpi render + real Tesseract OCR on JBIG2 and JPEG 2000 scanned PDFs!
            val jbig2Ocr = library.ocrPdfPageForGuide(jbig2Book.id, 1)
            val jbig2Sim = LiteratureTextNorm.bestContiguousSimilarity(jbig2Sentence, jbig2Ocr)
            assertTrue(jbig2Sim >= LiteratureTextNorm.OCR_SIMILARITY_THRESHOLD, "JBIG2 OCR similarity too low: $jbig2Sim (ocr=$jbig2Ocr)")

            val jpxOcr = library.ocrPdfPageForGuide(jpxBook.id, 1)
            val jpxSim = LiteratureTextNorm.bestContiguousSimilarity(jpxSentence, jpxOcr)
            assertTrue(jpxSim >= LiteratureTextNorm.OCR_SIMILARITY_THRESHOLD, "JPEG 2000 OCR similarity too low: $jpxSim (ocr=$jpxOcr)")

            // Blank PDF page must throw LiteratureOcrUnavailableException and be recorded as ocr_unavailable (not ocr_similarity_low).
            val blankPath = root.resolve("blank.pdf")
            org.apache.pdfbox.pdmodel.PDDocument().use { pdf ->
                pdf.addPage(org.apache.pdfbox.pdmodel.PDPage())
                pdf.save(blankPath.toFile())
            }
            val blankLibrary = TextbookLibrary(root.resolve("blank-lib"), ocr = BookOcr { _, _ -> "unused" })
            val blankBook = blankLibrary.importBook(blankPath)
            assertFailsWith<LiteratureOcrUnavailableException> {
                blankLibrary.ocrPdfPageForGuide(blankBook.id, 1)
            }
            val unavailableCheck = input.verify(body) { _, _ ->
                throw LiteratureOcrUnavailableException("PDF 第 1 页渲染结果为空白，OCR 不可用。")
            }
            assertTrue(unavailableCheck.failures.isNotEmpty())
            assertTrue(unavailableCheck.failures.all { it.rule == "ocr_unavailable" })
            assertTrue(unavailableCheck.rejectedQuotes.isEmpty(), "OCR unavailable must not reject valid quotes")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun croppedFacingPageScanAndRotatedPagesExcludeFacingSliverAtCandidateStage() {
        val root = Files.createTempDirectory("coach-guide-cropbox-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val imported = library.importBook(LiteratureGuideFixture.croppedFacingScanPdf(root))
            val lit = library.literatureBook(imported.id)
            val candidates = LiteraturePassages.extractCandidates(lit)
            val quotes = candidates.map { it.anchor.quoteAnchor }

            // Facing-page slivers outside CropBox (both rot=0 and rot=180) and uncropped edge slivers MUST be excluded!
            assertTrue(quotes.none { "sliver" in it || "interleave" in it || "truncated facing" in it || "hidden rotated" in it },
                "Facing-page sliver leaked into candidates: $quotes")
            // Clean body sentences from all 3 pages MUST survive!
            assertTrue(quotes.any { "universal collection of nature" in it })
            assertTrue(quotes.any { "authentic arguments about custody" in it })
            assertTrue(quotes.any { "catalogues outlived specimens" in it })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun answerWithMoreThanTwoSentencesKeepsFirstTwoVerifiedSentences() {
        val req = input.request()
        val base = LiteratureGuideFixture.reply(req)
        val threeAndFiveAnswers = buildJsonArray {
            // Q0: 3 valid sentences -> keeps first 2.
            add(buildJsonObject {
                put("questionIndex", 0)
                put("answerable", true)
                put("sentences", buildJsonArray {
                    add(buildJsonObject { put("text", "第一句有效实答。"); put("refs", buildJsonArray { add(0) }) })
                    add(buildJsonObject { put("text", "第二句有效实答。"); put("refs", buildJsonArray { add(1) }) })
                    add(buildJsonObject { put("text", "第三句多余补充。"); put("refs", buildJsonArray { add(2) }) })
                })
            })
            // Q1: 5 sentences where sentence 2 fails fallback regex -> takes first 2 verified sentences (1 & 3).
            add(buildJsonObject {
                put("questionIndex", 1)
                put("answerable", true)
                put("sentences", buildJsonArray {
                    add(buildJsonObject { put("text", "第一句有证据的结论。"); put("refs", buildJsonArray { add(0) }) })
                    add(buildJsonObject { put("text", "片段未涉及这部分内容。"); put("refs", buildJsonArray { add(1) }) })
                    add(buildJsonObject { put("text", "第三句有证据的补充。"); put("refs", buildJsonArray { add(2) }) })
                    add(buildJsonObject { put("text", "第四句多余补充。"); put("refs", buildJsonArray { add(0) }) })
                    add(buildJsonObject { put("text", "第五句多余补充。"); put("refs", buildJsonArray { add(1) }) })
                })
            })
        }
        val checked = input.verify(JsonObject(base + ("answers" to threeAndFiveAnswers)), exactOcr)
        val guide = assertNotNull(checked.toComplete(books, input.questions), "Expected pass, failures=${checked.failures}")
        assertEquals(2, guide.answers[0].sentences.size)
        assertEquals(listOf("第一句有效实答。", "第二句有效实答。"), guide.answers[0].sentences.map { it.text })
        assertEquals(2, guide.answers[1].sentences.size)
        assertEquals(listOf("第一句有证据的结论。", "第三句有证据的补充。"), guide.answers[1].sentences.map { it.text })
    }

    @Test fun metadataExtractsMultilineCoverPlainHeaderSlugAuthorAndConsensusYear() {
        // 1) Multiline coursepack cover (like Hunter 1989)
        val coverText = """
            Book title:
            Establishing the new archive: experiments in collection
            Book author(s):
            Mercer, E
            ISBN: 9780000000000
            Pub. year: 1989
        """.trimIndent()
        val m1 = LiteratureMetadata.extract(
            pdf = null,
            bookId = "b1",
            storedTitle = "Mercer-Between-Cabinet-1989-pp10-40",
            sourceFilename = "Mercer-Between-Cabinet-1989-pp10-40.pdf",
            firstPagesText = listOf(coverText),
        )
        assertEquals("Mercer, E", m1.author)
        assertEquals("1989", m1.year)
        assertTrue("Establishing the new archive" in m1.title, "Unexpected title: ${m1.title}")

        // 2) Plain header author above university affiliation + HTML entities (like Marples 2019)
        val articleText = """
            Scientific administration in the early century
            Clara Vance
            University of Bristol
            Abstract
            This study examines administrative practices.
        """.trimIndent()
        val m2 = LiteratureMetadata.extract(
            pdf = null,
            bookId = "b2",
            storedTitle = "Scientific administration: reinterpreting the archive&#x2019;s records",
            sourceFilename = "Vance-Scientific-Administration-2019.pdf",
            firstPagesText = listOf(articleText),
        )
        assertEquals("Clara Vance", m2.author)
        assertEquals("2019", m2.year)
        assertTrue("archive’s" in m2.title, "Expected decoded HTML entity in title: ${m2.title}")

        // 3) Reference article with earlier publisher launch year 2004 vs consensus article/filename year 2006 (like ODNB)
        val refText = """
            Dictionary of Historical Biography
            (In Progress)
            William Editor (editor in chief)
            Published: 23 September 2004 - Online ISSN: 0000-0000
            REFERENCE ARTICLE
            Founder members of the experimental guild
            Thomas Ashford
            Published in print: 2006
            Downloaded 2026.
        """.trimIndent()
        val m3 = LiteratureMetadata.extract(
            pdf = null,
            bookId = "b3",
            storedTitle = "Ashford-Founder-Members-2006",
            sourceFilename = "Ashford-Founder-Members-2006.pdf",
            firstPagesText = listOf(refText),
        )
        assertEquals("Thomas Ashford", m3.author)
        assertEquals("2006", m3.year, "Consensus year between filename and page (2006) must override launch year 2004")
        assertEquals("Founder members of the experimental guild", m3.title)
    }

    @Test fun placeholderPdfMetadataSomeAuthorSomeTitleFallsBackToFilenameAndFrontPage() {
        val root = Files.createTempDirectory("coach-guide-placeholder-meta-")
        try {
            val pdfPath = root.resolve("Aldridge-Cataloguing-Curiosities-2012.pdf")
            org.apache.pdfbox.pdmodel.PDDocument().use { pdf ->
                pdf.documentInformation.author = "someAuthor"
                pdf.documentInformation.title = "someTitle"
                val page = org.apache.pdfbox.pdmodel.PDPage()
                pdf.addPage(page)
                val font = org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA)
                org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page).use { stream ->
                    fun line(y: Float, text: String) {
                        stream.beginText(); stream.setFont(font, 12f); stream.newLineAtOffset(45f, y); stream.showText(text); stream.endText()
                    }
                    line(740f, "Cataloguing Natural Curiosities in Early Archives")
                    line(720f, "Martin Aldridge")
                    line(700f, "University of Leeds")
                    line(680f, "Abstract")
                    line(660f, "We argue that printed catalogues organized natural specimens across collections.")
                }
                pdf.save(pdfPath.toFile())
            }
            val library = TextbookLibrary(root.resolve("library"))
            val imported = library.importBook(pdfPath)
            val meta = library.literatureBook(imported.id).literatureMeta!!
            assertNotEquals("someAuthor", meta.author)
            assertNotEquals("someTitle", meta.title)
            assertEquals("Martin Aldridge", meta.author)
            assertEquals("2012", meta.year)
            assertFalse("someTitle" in meta.title)

            // Verify lowerCamelCase placeholders (someAuthor/someTitle/bookTitle) are rejected while real surnames pass.
            val m2 = LiteratureMetadata.extract(
                pdf = null,
                bookId = "b4",
                storedTitle = "someTitle",
                sourceFilename = "MacGregor-Cabinet-History-1994.pdf",
                firstPagesText = listOf("Author: someAuthor\nTitle: bookTitle\nA study of historical collections."),
            )
            assertEquals("MacGregor", m2.author)
            assertEquals("1994", m2.year)
            assertFalse(m2.title == "someTitle" || m2.title == "bookTitle")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun ocrCropFallbackEnglishModeAndTypographyNormRescueGenuineBodyQuotes() {
        // Historical long-s, em-dash, line-wrap hyphen, full-width comma, and stray space before punctuation normalize cleanly.
        val quote = "The collec- ting of rare specimens—catalogued by patrons, endured."
        val ocrWithQuirks = "The collecting of rare ſpecimens - catalogued by patrons ， endured ."
        val sim = LiteratureTextNorm.bestContiguousSimilarity(quote, ocrWithQuirks)
        assertTrue(sim >= LiteratureTextNorm.OCR_SIMILARITY_THRESHOLD, "Expected >= 0.98 after typography norm, got $sim")

        // Real 300 dpi cropped-region Tesseract OCR rescues a genuine quote when full-page OCR is degraded,
        // while still rejecting a fabricated quote!
        val root = Files.createTempDirectory("coach-guide-ocr-crop-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val imported = (1..3).map { library.importBook(LiteratureGuideFixture.pdf(root, it)) }
            val litBooks = imported.map { library.literatureBook(it.id) }
            val guideInput = LiteratureInput(litBooks, listOf("What did the plots show?"))
            val reply = LiteratureGuideFixture.reply(guideInput.request())

            // Simulate degraded full-page OCR (< 0.98) paired with real 300 dpi cropped-region Tesseract OCR.
            val cropOnlyVerifier = object : LiteratureOcrVerifier {
                override fun ocrPageText(book: Textbook, pdfPage: Int): String = "Noisy degraded full page scan header."
                override fun ocrQuoteCropText(book: Textbook, pdfPage: Int, quote: String): String? =
                    library.ocrPdfQuoteCropForGuide(book.id, pdfPage, quote)
            }
            val rescued = guideInput.verify(reply, cropOnlyVerifier)
            assertNotNull(rescued.toComplete(litBooks, guideInput.questions), "Expected crop OCR rescue, failures=${rescued.failures}")

            // English-only PDF must invoke BookOcr with englishOnly=true.
            var sawEnglishOnly = false
            val trackingOcr = object : BookOcr {
                override fun recognize(image: BookBitmap, control: BookImportControl): String = ""
                override fun recognize(image: BookBitmap, control: BookImportControl, englishOnly: Boolean, singleBlock: Boolean): String {
                    if (englishOnly) sawEnglishOnly = true
                    return "Plot 1 contained flowering plants. Sample 1 showed faster growth in shade."
                }
            }
            val trackedLib = TextbookLibrary(root.resolve("tracked-lib"), ocr = trackingOcr)
            val b1 = trackedLib.importBook(LiteratureGuideFixture.pdf(root, 1))
            trackedLib.ocrPdfPageForGuide(b1.id, 1)
            assertTrue(sawEnglishOnly, "Expected English-only PDF to use pure eng Tesseract model")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun evasiveAnswersAndLeakedRefNumbersAreRejectedByGate() {
        // Leaked ref / block numbers in prose must be rejected.
        assertTrue(LiteraturePassages.hasLeakedRef("根据 ref 12 可知标本登记维系了可信度。"))
        assertTrue(LiteraturePassages.hasLeakedRef("片段 4 说明早期收藏依赖捐赠者。"))
        assertTrue(LiteraturePassages.hasLeakedRef("如 [3] 所示，编目工作持续多年。"))
        // 4-digit historical years in brackets must NOT be flagged as leaked refs.
        assertFalse(LiteraturePassages.hasLeakedRef("[1660] 年的会议记录证明了早期制度。"))

        // Evasive / deflective sentences must be rejected by substantive().
        val evasions = listOf(
            "现有片段仅提及收藏地点，未涉及具体分类标准。",
            "所给材料只提到早期捐赠者，缺乏相关论述。",
            "当前文本着墨有限，难以全面说明其历史背景。",
            "需要结合完整原文进一步核实该观点。",
            "材料中并未讨论该作者对标本分类的立场。",
        )
        for (e in evasions) {
            assertFalse(LiteraturePassages.substantive(e), "Expected evasion rejected: $e")
        }
        // Legitimate historical answers describing actors' inability must NOT be rejected.
        assertTrue(LiteraturePassages.substantive("当时的会员未能维持日常管理，自愿团体无法承担繁重的编目与保存工作。"))

        // End-to-end verify() records leaked_ref and fallback_unanswerable failure rules cleanly.
        val req = input.request()
        val base = LiteratureGuideFixture.reply(req)
        val badAnswers = buildJsonArray {
            add(buildJsonObject {
                put("questionIndex", 0)
                put("answerable", true)
                put("sentences", buildJsonArray {
                    add(buildJsonObject { put("text", "根据 ref 0，样地观察显示差异。"); put("refs", buildJsonArray { add(0) }) })
                    add(buildJsonObject { put("text", "第二句有效总结。"); put("refs", buildJsonArray { add(1) }) })
                })
            })
            add(buildJsonObject {
                put("questionIndex", 1)
                put("answerable", true)
                put("sentences", buildJsonArray {
                    add(buildJsonObject { put("text", "现有片段仅提及样地位置，未讨论具体原因。"); put("refs", buildJsonArray { add(0) }) })
                    add(buildJsonObject { put("text", "缺乏相关论述以正面回应。"); put("refs", buildJsonArray { add(1) }) })
                })
            })
        }
        val checked = input.verify(JsonObject(base + ("answers" to badAnswers)), exactOcr)
        assertNull(checked.toComplete(books, input.questions))
        assertEquals(mapOf("answer:0" to "leaked_ref", "answer:1" to "fallback_unanswerable"),
            checked.failures.associate { it.itemKey to it.rule })
    }
}
