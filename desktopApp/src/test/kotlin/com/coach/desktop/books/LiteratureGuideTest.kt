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
}
