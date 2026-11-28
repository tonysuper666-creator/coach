package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path

/** Local PDF acceptance. Expected spans are annotated independently on literatureBook's page text. */
object LiteratureGoldAudit {
    private data class Span(val page: Int, val start: Int, val end: Int)
    private data class Label(val role: String, val span: Span)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size >= 2) { "Expected a label directory followed by one or more local PDFs" }
        if (args[0] == "--layout") {
            require(args.size == 3) { "Expected comma-separated PDF pages followed by one local PDF" }
            val pages = args[1].split(',').map(String::toInt).distinct()
            BookPdfLoader.read(Path.of(args[2]).toFile()) { pdf ->
                require(pages.all { it in 1..pdf.numberOfPages }) { "Requested page outside the PDF" }
                println("page\troute\tglyphs\thidden\timage_permille\tchecked_columns\trender\tadded_note_words")
                for (page in pages) LiteraturePdfLayout.extract(pdf, page) { trace ->
                    println(listOf(page, trace.route, trace.glyphs, trace.hiddenGlyphs, trace.imagePermille,
                        trace.checkedColumns, trace.render, trace.addedNoteWords).joinToString("\t"))
                }
            }
            return
        }
        val root = Files.createTempDirectory("literature-audit")
        try {
            System.setProperty("user.home", root.toString())
            val library = TextbookLibrary(root.resolve("library"))
            if (args[0] == "--explain") {
                require(args.size >= 3 && args.size % 2 == 1) { "Expected pairs of comma-separated pages and a PDF" }
                for ((numbers, file) in args.drop(1).chunked(2)) {
                    val pages = numbers.split(',').map { it.toInt() }.toSet()
                    val imported = library.importBook(Path.of(file))
                    require(imported.format == "pdf")
                    explain(library.literatureBook(imported.id), pages)
                }
                return
            }
            val labels = Path.of(args[0])
            var total = 0
            var hits = 0
            var leaks = 0
            var unexpected = 0
            val seen = mutableSetOf<String>()
            for (input in args.drop(1)) {
                val imported = library.importBook(Path.of(input))
                require(imported.format == "pdf" && seen.add(imported.id)) { "Expected distinct PDFs" }
                val book = library.literatureBook(imported.id)
                val rows = Files.readAllLines(labels.resolve(imported.id + ".tsv")).mapIndexed { index, line ->
                    val cells = line.split('\t')
                    require(cells.size == 5) { "Expected role, page, start, end and SHA-256 at row ${index + 1}" }
                    val (role, page, start, end, hash) = cells
                    require(role in setOf("body", "note", "caption", "margin", "bibliography"))
                    val span = Span(page.toInt(), start.toInt(), end.toInt())
                    val text = book.page(span.page)?.text
                    require(text != null && span.start >= 0 && span.end in (span.start + 1)..text.length &&
                        BookIllustrationAssets.sha256(text.substring(span.start, span.end).toByteArray()) == hash) {
                        "Stale label for ${book.id.take(12)} page $page offsets $start..$end"
                    }
                    Label(role, span)
                }
                require(rows.isNotEmpty() && rows.map { it.span }.distinct().size == rows.size) { "Empty or duplicate labels" }
                val pages = rows.groupBy { it.span.page }
                val bodies = rows.filter { it.role == "body" }.map { it.span }.toSet()
                val found = mutableSetOf<Span>()
                for (candidate in LiteraturePassages.extractCandidates(book)) {
                    val anchor = candidate.anchor
                    val pageLabels = pages[anchor.pdfPage] ?: continue
                    val span = Span(anchor.pdfPage, anchor.startUtf16, anchor.endUtf16)
                    if (span in bodies) found += span else unexpected++
                    if (pageLabels.any { it.role == "note" && span.start < it.span.end && it.span.start < span.end }) leaks++
                }
                total += bodies.size
                hits += found.size
                for (missing in bodies - found) {
                    println("Missing body ${book.id.take(12)} page ${missing.page} offsets ${missing.start}..${missing.end}")
                }
            }
            println("Body $hits/$total; note leaks $leaks; unexpected candidates $unexpected")
            check(total > 0 && hits * 100L >= total * 99L && leaks == 0 && unexpected == 0)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun explain(book: Textbook, pages: Set<Int>) {
        require(pages.all { book.page(it) != null }) { "Requested page outside the book" }
        val reached = pages.associateWith { mutableListOf<IntRange>() }
        fun report(page: BookPage, start: Int, end: Int, stage: String) {
            if (page.number !in pages || end <= start) return
            if (stage != "region") reached.getValue(page.number).add(start until end)
            val bytes = page.text.substring(start, end).toByteArray(Charsets.UTF_8)
            println(listOf(book.id.take(12), page.number, start, end, stage,
                BookIllustrationAssets.sha256(bytes).take(12)).joinToString("\t"))
        }
        for (number in pages.sorted()) {
            val page = checkNotNull(book.page(number))
            var base = 0
            for (region in page.guideRegions) {
                report(page, base, base + region.text.length, "region")
                base += region.text.length + 2
            }
        }
        for (excerpt in LiteraturePassages.extractCandidates(book, ::report)) {
            val anchor = excerpt.anchor
            if (anchor.pdfPage in pages) report(checkNotNull(book.page(anchor.pdfPage)),
                anchor.startUtf16, anchor.endUtf16, "candidate")
        }
        for (number in pages.sorted()) {
            val page = checkNotNull(book.page(number))
            var end = 0
            // Headings, isolated marks and other spans that never reached a sentence gate.
            for (range in reached.getValue(number).sortedBy { it.first } + listOf(page.text.length until page.text.length)) {
                if (range.first > end && page.text.substring(end, range.first).isNotBlank()) {
                    report(page, end, range.first, "unassembled")
                }
                end = maxOf(end, range.last + 1)
            }
        }
    }
}
