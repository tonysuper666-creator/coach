package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertTrue

class LiteratureGoldTest {
    private data class Label(val page: Int, val role: String, val sentence: String)

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
