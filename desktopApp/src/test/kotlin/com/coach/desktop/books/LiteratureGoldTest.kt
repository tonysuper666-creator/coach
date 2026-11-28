package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertTrue

class LiteratureGoldTest {
    private data class Label(val page: Int, val role: String, val sentence: String)

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
            for ((name, count) in mapOf("ocr-bands" to 6, "covered-ocr" to 7, "offset-crop" to 4, "printed-notes" to 12)) {
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
}
