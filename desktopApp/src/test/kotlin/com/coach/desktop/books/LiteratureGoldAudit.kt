package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path

/** Local PDF acceptance. Expected spans are annotated independently on literatureBook's page text. */
object LiteratureGoldAudit {
    private data class Span(val page: Int, val start: Int, val end: Int)
    private data class Label(val role: String, val span: Span)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size >= 2) { "Expected a label directory followed by one or more local PDFs" }
        val labels = Path.of(args[0])
        val root = Files.createTempDirectory("literature-audit")
        try {
            System.setProperty("user.home", root.toString())
            val library = TextbookLibrary(root.resolve("library"))
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
}
