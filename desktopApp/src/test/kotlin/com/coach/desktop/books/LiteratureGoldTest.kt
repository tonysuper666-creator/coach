package com.coach.desktop.books

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.util.Matrix
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiteratureGoldTest {
    private data class Label(val page: Int, val role: String, val sentence: String)

    @Test fun pageImagesWithOrdinaryTextUseThePrintedFooterCheckInEitherPaintOrder() {
        val path = Path.of(checkNotNull(javaClass.getResource("/literature-gold/image-text.pdf")).toURI())
        BookPdfLoader.read(path.toFile()) { pdf ->
            // All six pages have identical native text. The illustration control supplies the reader offsets.
            val controlText = LiteraturePdfLayout.extract(pdf, 4).map { it.text }
            for (page in 1..pdf.numberOfPages) {
                val traces = mutableListOf<LiteraturePdfLayout.FootnoteLayoutTrace>()
                val regions = LiteraturePdfLayout.extract(pdf, page) { traces += it }
                assertEquals(controlText, regions.map { it.text }, "Native text and offsets on page $page")
                val trace = traces.single()
                assertEquals(0, trace.hiddenGlyphs)
                if (page <= 3) {
                    assertEquals("image_text", trace.route)
                    assertTrue(trace.checkedColumns > 0 && trace.addedNoteWords > 0, "Page $page: $trace")
                    assertEquals("available", trace.render)
                } else {
                    assertEquals("text_only", trace.route)
                    assertEquals(0, trace.checkedColumns)
                    assertEquals("not_requested", trace.render)
                }
            }
            // Changing the same page's display rotation must preserve the reading-coordinate spans.
            fun spans() = LiteraturePdfLayout.extract(pdf, 1).map { it.text to it.footnoteRanges }
            val original = spans()
            for (turn in listOf(90, 180, 270)) {
                pdf.getPage(0).rotation = turn
                assertEquals(original, spans(), "Rotation $turn")
            }
        }
    }

    @Test fun quoteCropCoversTheVisibleLineOnBothCroppedScans() {
        val path = Path.of(checkNotNull(javaClass.getResource("/literature-gold/offset-crop.pdf")).toURI())
        org.apache.pdfbox.Loader.loadPDF(path.toFile()).use { pdf ->
            for (page in 1..2) {
                val rect = kotlin.test.assertNotNull(LiteraturePdfLayout.locateQuoteCropRect(
                    pdf, page, "The engineers marked the highest level on the bridge."
                ), "Missing quote crop on page $page")
                // Both facsimiles display this line at x=24, baseline y=440 in a 420 × 604 crop.
                assertTrue(rect.left <= 24f / 420 && rect.right >= 270f / 420, "Page $page: $rect")
                assertTrue(rect.top in (402f / 604)..(432f / 604) &&
                    rect.bottom in (440f / 604)..(458f / 604), "Page $page: $rect")
            }
        }
    }

    @Test fun forcedCitationsToBothPdfFootersFailBeforeOcr() {
        val root = Files.createTempDirectory("literature-footer")
        try {
            val path = Path.of(checkNotNull(javaClass.getResource("/literature-gold/shape.pdf")).toURI())
            val library = TextbookLibrary(root)
            val book = library.literatureBook(library.importBook(path).id)
            val quote = "A separate inventory records the same transfer in the following year."
            for (page in book.pages) {
                val region = page.guideRegions.single { quote in it.text }
                val start = page.text.indexOf(quote)
                val anchor = BookCompanionAnchor(page.number, "probe", start, start + quote.length, quote)
                val forced = GuideExcerpt(book, anchor, region)
                val (_, reason) = LiteratureCitationCheck.check(listOf(forced), 0, book.id) { _, _ ->
                    error("A note reached OCR")
                }
                kotlin.test.assertEquals(LiteraturePassages.FOOTNOTE_REJECTION, reason)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun scanFootnoteWithOrdinaryProseIsRejectedBeforeOcr() {
        val root = Files.createTempDirectory("literature-scan-footer")
        try {
            val path = Path.of(checkNotNull(javaClass.getResource("/literature-gold/pollock.pdf")).toURI())
            val library = TextbookLibrary(root)
            val book = library.literatureBook(library.importBook(path).id)
            val page = book.pages.first()
            val quote = "But these dates are uncertain."
            val region = page.guideRegions.single { quote in it.text }
            val start = page.text.indexOf(quote)
            val anchor = BookCompanionAnchor(page.number, "scan-probe", start, start + quote.length, quote)
            val (_, reason) = LiteratureCitationCheck.check(listOf(GuideExcerpt(book, anchor, region)), 0, book.id) { _, _ ->
                error("Scan footnote reached OCR")
            }
            kotlin.test.assertEquals(LiteraturePassages.FOOTNOTE_REJECTION, reason)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun noteBandsRejectForcedCitationsAtTheirPageOffsets() {
        val root = Files.createTempDirectory("literature-note-bands")
        try {
            val labels = Path.of(checkNotNull(javaClass.getResource("/literature-gold/sentences.tsv")).toURI())
            val rows = Files.readAllLines(labels).map { it.split('\t', limit = 4) }
            val library = TextbookLibrary(root)
            for ((name, count) in mapOf("ocr-bands" to 6, "covered-ocr" to 7, "offset-crop" to 4,
                    "printed-notes" to 12, "image-text" to 12)) {
                val book = library.literatureBook(library.importBook(labels.resolveSibling("$name.pdf")).id)
                val notes = rows.filter { it[0] == name && it[2] == "note" }
                kotlin.test.assertEquals(count, notes.size)
                for ((_, number, _, quote) in notes) {
                    val page = checkNotNull(book.page(number.toInt()))
                    val region = page.guideRegions.single { quote in it.text }
                    val start = page.text.indexOf(quote)
                    val anchor = BookCompanionAnchor(page.number, "probe", start, start + quote.length, quote)
                    val (_, reason) = LiteratureCitationCheck.check(listOf(GuideExcerpt(book, anchor, region)), 0, book.id) { _, _ ->
                        error("Note reached OCR")
                    }
                    kotlin.test.assertEquals(LiteraturePassages.FOOTNOTE_REJECTION, reason)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // Keep punctuation and every word: a truncated or joined body sentence is a miss.
    private fun key(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("""(?<=\p{L})-\s*\n\s*(?=\p{Ll})"""), "")
        .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
        .replace('—', '-').replace('–', '-').replace(Regex("""\s+"""), " ").trim()

    @Test fun candidateSentencesMatchTheAnnotatedPages() {
        val root = Files.createTempDirectory("literature-gold")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val fixtures = Path.of(checkNotNull(javaClass.getResource("/literature-gold/sentences.tsv")).toURI()).parent
            val labels = Files.readAllLines(fixtures.resolve("sentences.tsv")).map { row ->
                val (file, page, role, sentence) = row.split('\t', limit = 4)
                file to Label(page.toInt(), role, key(sentence))
            }.groupBy({ it.first }, { it.second })
            val misses = mutableListOf<String>()
            val extras = mutableListOf<String>()
            var totalBody = 0
            var leaks = 0
            for ((file, rows) in labels) {
                val extension = if (file == "liberty") "epub" else "pdf"
                val imported = library.importBook(fixtures.resolve("$file.$extension"))
                val book = library.literatureBook(imported.id)
                val pool = LiteraturePassages.extractCandidates(book)
                val candidates = pool.map { it.anchor.pdfPage to key(it.anchor.quoteAnchor) }.toMutableList()
                val bodies = rows.filter { it.role == "body" }
                totalBody += bodies.size
                val previousMisses = misses.size
                val previousLeaks = leaks
                for (body in bodies) {
                    if (!candidates.remove(body.page to body.sentence)) misses += "$file p${body.page} ${body.sentence}"
                }
                for ((page, text) in candidates) {
                    val words = text.split(' ')
                    // Also fail on *every* unmatched candidate, including short fragments.
                    if (rows.any { it.page == page && it.role == "note" &&
                            (it.sentence.contains(text) || words.windowed(4).any { w -> w.joinToString(" ") in it.sentence }) }) leaks++
                    extras += "$file p$page $text"
                }
                val recovered = bodies.size - (misses.size - previousMisses)
                println("$file body $recovered/${bodies.size}; note leaks ${leaks - previousLeaks}; unexpected ${candidates.size}")
                for ((i, excerpt) in pool.withIndex()) {
                    val (_, reason) = LiteratureCitationCheck.check(pool, i, book.id) { b, p -> b.page(p)!!.text }
                    assertTrue(reason == "ok", "$file ${excerpt.anchor.pdfPage} $reason")
                }
            }
            val hits = totalBody - misses.size
            println("Body $hits/$totalBody; note leaks $leaks; unexpected candidates ${extras.size}")
            assertTrue(hits * 100 >= totalBody * 99 && leaks == 0 && extras.isEmpty(),
                "Body $hits/$totalBody; note leaks $leaks\nMissing:\n${misses.joinToString("\n")}\nUnexpected:\n${extras.joinToString("\n")}")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun buildTightScanPdf(
        path: Path,
        rotate180: Boolean = false,
        includeBottomCaption: Boolean = false,
        includeMidPageQuote: Boolean = false,
        includeBottomQuoteAboveNotes: Boolean = false,
        lastBodyLineShortLowercase: Boolean = false,
        singleFootnote: Boolean = false,
    ) {
        val pageW = 420f
        val pageH = 620f
        val dpiScale = 4
        val img = BufferedImage((pageW * dpiScale).toInt(), (pageH * dpiScale).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, img.width, img.height)
            g.color = Color.BLACK
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val bodyFont = Font("Serif", Font.PLAIN, (10.5f * dpiScale).toInt())
            val noteFont = Font("Serif", Font.PLAIN, (8.8f * dpiScale).toInt())
            val supFont = Font("Serif", Font.PLAIN, (6.0f * dpiScale).toInt())
            val pdfFont = PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN)

            data class DrawWord(val x: Float, val width: Float, val pt: Float, val text: String)
            data class DrawLine(
                val y: Float,
                val words: List<DrawWord>,
                val visualFont: Font,
                val supFirst: Boolean = false,
            )

            fun measureVisual(font: Font, text: String): Float =
                g.getFontMetrics(font).stringWidth(text).toFloat() / dpiScale

            val drawLines = mutableListOf<DrawLine>()
            fun bodyRow(y: Float, text: String) {
                var x = 40f
                val ws = text.split(' ').map { w ->
                    val width = measureVisual(bodyFont, w)
                    val dw = DrawWord(x, width, 10.5f, w)
                    x += width + 3.2f
                    dw
                }
                drawLines += DrawLine(y, ws, bodyFont)
            }
            fun noteRow(y: Float, text: String, marker: String? = null) {
                val ws = mutableListOf<DrawWord>()
                var x = 40f
                if (marker != null) {
                    val mw = measureVisual(supFont, marker)
                    ws += DrawWord(x, mw, 6.0f, marker)
                    x = 52f
                }
                for (w in text.split(' ')) {
                    val width = measureVisual(noteFont, w)
                    ws += DrawWord(x, width, 10.5f, w) // Uniform OCR point size matching body
                    x += width + 2.8f
                }
                drawLines += DrawLine(y, ws, noteFont, supFirst = marker != null)
            }

            // Running header + upper body
            bodyRow(40f, "Survey of Early Collections")
            var y = 64f
            for (i in 1..14) {
                bodyRow(y, "The curators preserved specimen batch $i inside wooden cabinets for regular study.")
                y += 11.4f
            }
            if (includeMidPageQuote) {
                y += 14f
                for (i in 1..3) {
                    var x = 56f
                    val text = "Quoted ledger entry $i remained legible inside the central chapter narrative."
                    val ws = text.split(' ').map { w ->
                        val width = measureVisual(noteFont, w)
                        val dw = DrawWord(x, width, 10.5f, w)
                        x += width + 2.8f
                        dw
                    }
                    drawLines += DrawLine(y, ws, noteFont)
                    y += 10.0f
                }
                y += 14f
                for (i in 15..24) {
                    bodyRow(y, "Later observers compared ledger record $i against surviving museum inventories.")
                    y += 11.4f
                }
            } else {
                for (i in 15..21) {
                    bodyRow(y, "Later observers compared ledger record $i against surviving museum inventories.")
                    y += 11.4f
                }
                if (lastBodyLineShortLowercase) {
                    // Normal-width body line whose words lack ascenders (short measured ink height)
                    // and whose first word is a short lowercase word aligned with note markers below,
                    // even with a slight upward OCR baseline jitter on that first word.
                    bodyRow(y, "Later observers compared ledger record 22 against surviving museum inventories,")
                    y += 11.4f
                    val ws = mutableListOf<DrawWord>()
                    val firstW = measureVisual(bodyFont, "in")
                    ws += DrawWord(40f, firstW, 10.5f, "in")
                    var x = 52f
                    for (w in listOf("common", "rooms", "some", "more", "summer", "coins", "came", "soon.")) {
                        val width = measureVisual(bodyFont, w)
                        ws += DrawWord(x, width, 10.5f, w)
                        x += width + 3.2f
                    }
                    drawLines += DrawLine(y, ws, bodyFont)
                    y += 11.4f
                } else {
                    bodyRow(y, "Later observers compared ledger record 22 against surviving museum inventories.")
                    y += 11.4f
                }
                if (includeBottomQuoteAboveNotes) {
                    y += 12f
                    for (i in 1..4) {
                        var x = 52f
                        val text = "Bottom extract line $i preserves an authentic primary source passage."
                        val ws = text.split(' ').map { w ->
                            val width = measureVisual(noteFont, w)
                            val dw = DrawWord(x, width, 10.5f, w)
                            x += width + 2.8f
                            dw
                        }
                        drawLines += DrawLine(y, ws, noteFont)
                        y += 10.0f
                    }
                }
            }

            // Tightly-leaded footnotes (dy=10.0 < nominal 10.5pt) after a vertical gap, NO horizontal rule.
            y += 28f
            noteRow(y, "Archive register volume forty records the first transfer.", marker = "58")
            y += 10.0f
            noteRow(y, "This flush left continuation belongs to the first footnote.")
            y += 10.0f
            noteRow(y, "Another flush left line completes the first note entry.")
            if (!singleFootnote) {
                y += 10.0f
                noteRow(y, "Catalog appendix twelve confirms the second footnote reference.", marker = "59")
                y += 10.0f
                noteRow(y, "Flush left detail continues the second footnote quietly.")
            }

            if (includeBottomCaption) {
                y += 26f
                g.fillRect((40f * dpiScale).toInt(), ((y - 12f) * dpiScale).toInt(), (180f * dpiScale).toInt(), 2)
                bodyRow(y, "Plate 7. Engraved diagram showing the gallery arrangement.")
                y += 11.4f
                bodyRow(y, "Scale bar indicates original cabinet dimensions.")
            }

            for (dl in drawLines) {
                for ((idx, dw) in dl.words.withIndex()) {
                    val isSup = dl.supFirst && idx == 0
                    g.font = if (isSup) supFont else dl.visualFont
                    val wy = if (isSup) dl.y - 2.6f else dl.y
                    g.drawString(dw.text, (dw.x * dpiScale).toInt(), (wy * dpiScale).toInt())
                }
            }

            val finalImg = if (rotate180) {
                BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB).also { rot ->
                    val rg = rot.createGraphics()
                    rg.rotate(Math.PI, img.width / 2.0, img.height / 2.0)
                    rg.drawImage(img, 0, 0, null)
                    rg.dispose()
                }
            } else img

            PDDocument().use { doc ->
                val page = PDPage(PDRectangle(pageW, pageH))
                if (rotate180) page.rotation = 180
                doc.addPage(page)
                val ximg = LosslessFactory.createFromImage(doc, finalImg)
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(ximg, 0f, 0f, pageW, pageH)
                    cs.setRenderingMode(RenderingMode.NEITHER)
                    for (dl in drawLines) {
                        for ((idx, dw) in dl.words.withIndex()) {
                            val isSup = dl.supFirst && idx == 0
                            // Simulate a 1.5pt OCR baseline jitter on a short lowercase first word of the last body line.
                            val jitter = lastBodyLineShortLowercase && idx == 0 && dw.text == "in" && !dl.supFirst
                            val wy = when {
                                isSup -> dl.y - 2.6f
                                jitter -> dl.y - 1.5f
                                else -> dl.y
                            }
                            val rawPdfW = pdfFont.getStringWidth(dw.text) / 1000f * dw.pt
                            val sx = if (rawPdfW > 0f) dw.pt * (dw.width / rawPdfW) else dw.pt
                            cs.beginText()
                            cs.setFont(pdfFont, 1f)
                            if (rotate180) {
                                // Counter-rotated 180° text matrix has negative yScale, matching real scanned PDFs.
                                cs.setTextMatrix(Matrix(-sx, 0f, 0f, -dw.pt, pageW - dw.x, wy))
                            } else {
                                cs.setTextMatrix(Matrix(sx, 0f, 0f, dw.pt, dw.x, pageH - wy))
                            }
                            cs.showText(dw.text)
                            cs.endText()
                        }
                    }
                }
                doc.save(path.toFile())
            }
            if (finalImg !== img) finalImg.flush()
        } finally {
            g.dispose()
            img.flush()
        }
    }

    @Test fun tightlyLeadedScanFootnotesBeforeCaptionAndRotated180PagesWork() {
        val root = Files.createTempDirectory("literature-tight-scan")
        try {
            val library = TextbookLibrary(root.resolve("library"))

            // 1. Tightly-leaded footnotes (dy < nominal pt) with flush-left continuations + bottom caption below rule.
            val p1 = root.resolve("tight-caption.pdf")
            buildTightScanPdf(p1, rotate180 = false, includeBottomCaption = true)
            val book1 = library.literatureBook(library.importBook(p1).id)
            val cands1 = LiteraturePassages.extractCandidates(book1).map { it.anchor.quoteAnchor }
            assertTrue(cands1.any { "specimen batch 5" in it }, "Expected body sentence in $cands1")
            assertFalse(cands1.any { "Archive register" in it || "flush left" in it || "Catalog appendix" in it },
                "Footnotes leaked: $cands1")

            // 2. Single footnote at bottom + mid-page smaller quote followed by resuming body prose.
            val p2 = root.resolve("single-note-mid-quote.pdf")
            buildTightScanPdf(p2, rotate180 = false, includeMidPageQuote = true, singleFootnote = true)
            val book2 = library.literatureBook(library.importBook(p2).id)
            val cands2 = LiteraturePassages.extractCandidates(book2).map { it.anchor.quoteAnchor }
            assertTrue(cands2.any { "Quoted ledger entry 2" in it }, "Mid-page quote must stay body: $cands2")
            assertTrue(cands2.any { "ledger record 20" in it }, "Resuming body must stay body: $cands2")
            assertFalse(cands2.any { "Archive register" in it || "flush left" in it },
                "Single bottom footnote leaked: $cands2")

            // 3. 180°-rotated scan with negative yScale must not mark body spaces as wide gaps.
            val p3 = root.resolve("rotated-180.pdf")
            buildTightScanPdf(p3, rotate180 = true, includeBottomCaption = false)
            val book3 = library.literatureBook(library.importBook(p3).id)
            val cands3 = LiteraturePassages.extractCandidates(book3).map { it.anchor.quoteAnchor }
            assertTrue(cands3.any { "specimen batch 5" in it }, "180-deg rotated body lost: $cands3")
            assertFalse(cands3.any { "Archive register" in it || "Catalog appendix" in it },
                "180-deg rotated footnote leaked: $cands3")

            // 4. Small-type bottom block quote directly above footnotes must stay body (gap breaks block).
            val p4 = root.resolve("bottom-quote-above-notes.pdf")
            buildTightScanPdf(
                p4,
                rotate180 = false,
                includeBottomQuoteAboveNotes = true,
                lastBodyLineShortLowercase = true,
            )
            val book4 = library.literatureBook(library.importBook(p4).id)
            val cands4 = LiteraturePassages.extractCandidates(book4).map { it.anchor.quoteAnchor }
            assertTrue(cands4.any { "summer coins came soon." in it }, "Body line before bottom quote lost: $cands4")
            assertTrue(cands4.any { "Bottom extract line 2" in it }, "Bottom quote above footnotes lost: $cands4")
            assertFalse(cands4.any { "Archive register" in it || "Catalog appendix" in it },
                "Footnotes below bottom quote leaked: $cands4")

            // 5. Last body line starting with a short lowercase word (even with low ink height & OCR y-jitter)
            //    must never be treated as a footnote marker or dragged into the footnote block below the gap.
            val p5 = root.resolve("short-lowercase-last-body.pdf")
            buildTightScanPdf(p5, rotate180 = false, lastBodyLineShortLowercase = true)
            val book5 = library.literatureBook(library.importBook(p5).id)
            val cands5 = LiteraturePassages.extractCandidates(book5).map { it.anchor.quoteAnchor }
            assertTrue(cands5.any { "summer coins came soon." in it },
                "Last body line starting with lowercase word was falsely marked as footnote: $cands5")
            assertFalse(cands5.any { "Archive register" in it || "Catalog appendix" in it },
                "Footnotes leaked on lowercase-lead page: $cands5")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
