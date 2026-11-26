package com.coach.desktop.books

import kotlinx.serialization.json.*
import java.text.BreakIterator
import java.util.Locale

internal data class GuideExcerpt(val book: Textbook, val section: BookLesson, val anchor: BookCompanionAnchor)
internal data class GuidePaper(val keyword: String, val claim: String, val source: GuideExcerpt)
internal data class GuideStop(val title: String, val source: GuideExcerpt)
internal data class GuideQuiz(val item: BookCompanionQuiz, val source: GuideExcerpt)
internal data class LiteratureGuide(
    val question: String, val thread: String, val papers: List<GuidePaper>, val stops: List<GuideStop>,
    val answers: List<Pair<String, String>>, val quiz: List<GuideQuiz>,
)

internal class LiteratureInput(val books: List<Textbook>, val questions: List<String>) {
    init {
        require(books.size in 3..5 && books.distinctBy { it.id }.size == books.size)
        require(questions.size in 1..7 && questions.all { it.isNotBlank() && it.length <= 400 })
        require(books.none { it.hasPendingOcr }) { "扫描页正在识别，完成后再生成导读。" }
    }

    private val candidatesByBook = books.map { book ->
        val sections = book.lessons.mapNotNull { section ->
            val candidates = section.blocks.filter { it.isBodyParagraph }.flatMap { block ->
                val page = book.page(block.page) ?: return@flatMap emptyList()
                val sentences = BreakIterator.getSentenceInstance(Locale.ROOT).apply { setText(block.text) }
                var start = sentences.first()
                buildList {
                    var end = sentences.next()
                    while (end != BreakIterator.DONE) {
                        val quote = block.text.substring(start, end).trim()
                        val offset = page.text.indexOf(quote, block.start.coerceAtLeast(0))
                        if (quote.length in 6..360 && offset >= 0 && offset + quote.length <= block.end) {
                            val anchor = BookCompanionAnchor(page.number, block.id, offset, offset + quote.length, quote)
                            add(GuideExcerpt(book, section, anchor))
                        }
                        start = end
                        end = sentences.next()
                    }
                }
            }
            candidates.takeIf { it.isNotEmpty() }
        }
        require(sections.isNotEmpty()) { "《${book.title}》没有可核对的原句，请检查文字识别结果。" }
        sections
    }

    private fun List<List<GuideExcerpt>>.flattenEvenly(limit: Int) = flatMap { candidates ->
        val n = minOf(candidates.size, limit)
        List(n) { i -> candidates[if (n == 1) candidates.size / 2 else i * (candidates.size - 1) / (n - 1)] }
    }

    // Keep every section when fitting the bridge's single-batch byte limit.
    private var samplesPerSection = 8
    var excerpts = candidatesByBook.flatMap { it.flattenEvenly(samplesPerSection) }
        private set

    init {
        while (samplesPerSection > 1 && request().toString().toByteArray().size > 79000) {
            samplesPerSection--
            excerpts = candidatesByBook.flatMap { it.flattenEvenly(samplesPerSection) }
        }
        require(request().toString().toByteArray().size <= 79000) { "这些文献的小节过多，请分组生成。" }
    }

    fun request() = buildJsonObject {
        put("kind", "literatureGuide")
        put("questions", JsonArray(questions.map(::JsonPrimitive)))
        put("blocks", buildJsonArray {
            excerpts.forEachIndexed { ref, e -> add(buildJsonObject {
                put("ref", ref); put("bookId", e.book.id); put("title", e.book.title)
                put("sectionId", e.section.id); put("section", e.section.title); put("page", e.anchor.pdfPage); put("text", e.anchor.quoteAnchor)
            }) }
        })
    }

    fun verified(body: JsonObject): LiteratureGuide {
        fun JsonObject.label(key: String, limit: Int) = getValue(key).jsonPrimitive.content.also {
            require(it.isNotBlank() && it.length <= limit)
        }
        fun JsonObject.source(): GuideExcerpt {
            val e = excerpts[getValue("ref").jsonPrimitive.int]
            val a = e.anchor
            require(e.book.page(a.pdfPage)?.text?.substring(a.startUtf16, a.endUtf16) == a.quoteAnchor)
            return e
        }
        fun rows(key: String) = body.getValue(key).jsonArray.map { it.jsonObject }
        val papers = rows("papers").map { GuidePaper(it.label("keyword", 32), it.label("claim", 120), it.source()) }
        require(papers.size == books.size && papers.map { it.source.book.id }.toSet() == books.map { it.id }.toSet())
        val stops = rows("stops").map { GuideStop(it.label("section", 150), it.source()) }
        fun section(e: GuideExcerpt) = e.book.id to e.section.id
        val expected = excerpts.distinctBy(::section).map(::section)
        require(stops.map { section(it.source) }.distinct().toSet() == expected.toSet())
        val answers = body.getValue("answers").jsonArray
        require(answers.size == questions.size)
        val pairs = questions.zip(answers).map { (q, row) ->
            val sentences = row.jsonArray.map { it.jsonPrimitive.content }
            require(sentences.size == 2 && sentences.all { it.isNotBlank() && it.length <= 100 })
            q to sentences.joinToString(" ")
        }
        val items = rows("quiz")
        require(items.size in 10..15)
        val coverage = IntArray(questions.size)
        val quiz = items.map { row ->
            coverage[row.getValue("questionIndex").jsonPrimitive.int]++
            val source = row.source()
            val options = row.getValue("options").jsonArray.map { it.jsonPrimitive.content }
            val reasons = row.getValue("reasons").jsonArray.map { it.jsonPrimitive.content }
            require(options.size in 2..5 && options.distinct().size == options.size && options.none(String::isBlank))
            require(reasons.size == options.size && reasons.none(String::isBlank))
            val explanation = options.zip(reasons).joinToString("\n") { (option, reason) -> "$option · $reason" }
            val questionId = "${source.section.id}#q-guide-" + LiteratureGuideStore.digest(row)
            GuideQuiz(BookCompanionQuiz(questionId, source.section.id, row.label("stem", 500),
                options, options[row.getValue("correct").jsonPrimitive.int], explanation, anchor = source.anchor), source)
        }
        require(coverage.all { it >= 2 } && quiz.distinctBy { it.item.stem }.size == quiz.size)
        return LiteratureGuide(body.label("question", 120), body.label("thread", 180), papers,
            stops.sortedWith(compareBy({ books.indexOf(it.source.book) }, { it.source.anchor.pdfPage }, { it.source.anchor.startUtf16 })),
            pairs, quiz)
    }
}
