package com.coach.desktop.books

import kotlinx.serialization.json.*

internal data class GuideExcerpt(val book: Textbook, val section: BookLesson, val anchor: BookCompanionAnchor, val region: GuideRegion)
internal data class GuideAnswerSentence(val text: String, val source: GuideExcerpt)
internal data class GuidePaper(val keyword: String, val claim: String, val source: GuideExcerpt)
internal data class GuideStop(val title: String, val source: GuideExcerpt)
internal data class GuideQuiz(val item: BookCompanionQuiz, val source: GuideExcerpt)
internal data class LiteratureGuide(
    val question: String, val thread: String, val papers: List<GuidePaper>, val stops: List<GuideStop>,
    val answers: List<Pair<String, List<GuideAnswerSentence>>>, val quiz: List<GuideQuiz>,
)

internal class LiteratureInput(val books: List<Textbook>, val questions: List<String>, val revision: Int = 0,
    val annotations: Map<String, LiteratureCitation> = emptyMap(),
) {
    init {
        require(books.size in 3..5 && books.distinctBy { it.id }.size == books.size)
        require(questions.size in 1..7 && questions.all { it.isNotBlank() && it.length <= 400 })
        require(books.none { it.hasPendingOcr }) { "扫描页正在识别，完成后再生成导读。" }
    }

    private val documents = buildJsonArray {
        books.forEach { book -> add(buildJsonObject {
            put("bookId", book.id)
            put("title", book.title)
            put("author", (annotations[book.id]?.author ?: book.guideCitation.author)?.let(::JsonPrimitive) ?: JsonNull)
            book.guideCitation.sourceLabel?.let { put("sourceLabel", it) }
            put("year", (annotations[book.id]?.year ?: book.guideCitation.year)?.let(::JsonPrimitive) ?: JsonNull)
        }) }
    }

    private fun block(ref: Int, e: GuideExcerpt) = buildJsonObject {
        put("ref", ref)
        put("bookId", e.book.id)
        put("sectionId", e.section.id)
        put("section", e.section.title)
        put("page", e.anchor.pdfPage)
        put("text", e.anchor.quoteAnchor)
    }

    private fun payload(blocks: JsonArray) = buildJsonObject {
        put("kind", "literatureGuide")
        put("selectionVersion", 3)
        put("revision", revision)
        put("questions", JsonArray(questions.map(::JsonPrimitive)))
        put("documents", documents)
        put("blocks", blocks)
    }

    val excerpts = buildList {
        val overhead = payload(JsonArray(emptyList())).toString().toByteArray().size
        val quota = (79000 - overhead) / books.size
        for (book in books) {
            val sections = LiteraturePassages.ranked(book, questions, revision)
            val first = size
            var remaining = quota
            for (rank in 0 until sections.maxOf { it.size }) {
                for (section in sections) {
                    if (size - first >= 64) break
                    val excerpt = section.getOrNull(rank) ?: continue
                    val cost = block(size, excerpt).toString().toByteArray().size + 1
                    if (cost > remaining) {
                        require(rank > 0) { "文献分节超过单篇预算，请缩小范围。" }
                        continue
                    }
                    add(excerpt)
                    remaining -= cost
                }
            }
        }
    }

    fun request() = payload(JsonArray(excerpts.mapIndexed(::block)))

    fun verified(body: JsonObject): LiteratureGuide {
        fun JsonObject.label(key: String, limit: Int) = getValue(key).jsonPrimitive.content.also {
            require(it.isNotBlank() && it.length <= limit)
        }
        fun JsonObject.source(): GuideExcerpt {
            val e = excerpts[getValue("ref").jsonPrimitive.int]
            val a = e.anchor
            require(e.book.page(a.pdfPage)?.text?.substring(a.startUtf16, a.endUtf16) == a.quoteAnchor)
            require(e.region.containsSentence(a.quoteAnchor) && LiteraturePassages.bodySentence(a.quoteAnchor))
            return e
        }
        fun rows(key: String) = body.getValue(key).jsonArray.map { it.jsonObject }
        val papers = rows("papers").map { GuidePaper(it.label("keyword", 32), it.label("claim", 120), it.source()) }
        require(papers.all { LiteraturePassages.substantive(it.claim) })
        require(papers.size == books.size && papers.map { it.source.book.id }.toSet() == books.map { it.id }.toSet())
        val stops = rows("stops").map { GuideStop(it.label("section", 150), it.source()) }
        fun section(e: GuideExcerpt) = e.book.id to e.section.id
        val expected = excerpts.distinctBy(::section).map(::section)
        require(stops.size == expected.size && stops.map { section(it.source) }.toSet() == expected.toSet())
        val answers = body.getValue("answers").jsonArray
        require(answers.size == questions.size)
        val pairs = questions.zip(answers).map { (q, row) ->
            val sentences = row.jsonArray.map { value ->
                val sentence = value.jsonObject
                val source = sentence.source()
                val answer = sentence.label("answer", 100)
                val support = sentence.label("evidence", 650)
                require(LiteraturePassages.affirmativeAnswer(answer))
                val phrase = support.count { it.code in 0x3400..0x9fff } >= 5 ||
                    Regex("""\p{L}{2,}""").findAll(support).take(3).count() == 3
                require(phrase && source.region.containsSentence(support) &&
                    GuideRegion(source.anchor.quoteAnchor, null).containsSentence(support))
                GuideAnswerSentence(answer, source)
            }
            require(sentences.size == 2)
            q to sentences
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
            val stem = row.label("stem", 500)
            require(LiteraturePassages.contentQuestion(stem))
            GuideQuiz(BookCompanionQuiz(questionId, source.section.id, stem,
                options, options[row.getValue("correct").jsonPrimitive.int], explanation, anchor = source.anchor), source)
        }
        require(coverage.all { it >= 2 } && quiz.distinctBy { it.item.stem }.size == quiz.size)
        return LiteratureGuide(body.label("question", 120), body.label("thread", 180), papers,
            stops.sortedWith(compareBy({ books.indexOf(it.source.book) }, { it.source.anchor.pdfPage }, { it.source.anchor.startUtf16 })),
            pairs, quiz)
    }
}
