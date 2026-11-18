package com.coach.desktop.books

/** Lesson and passage requests use the paired, source-authorized service. */
internal class PairedBookLearningSupport(
    private val library: TextbookLibrary,
    private val consent: ExplainConsent = ExplainConsent(library.directory),
    private val sendLesson: suspend (List<SourceBlock>, Int) -> ExplainResult = { blocks, pages ->
        BookExplainer(library = library).run { load(); explainLesson(blocks, pages) }
    },
    private val send: suspend (SourceBlock, String) -> ExplainResult = { block, question ->
        BookExplainer(library = library).run { load(); ask(block, question) }
    },
) : BookLearningSupport {
    override fun consentRequired(book: String) = !consent.allowed(book)
    override fun allow(book: String) = consent.allow(book)
    override suspend fun explain(request: BookExplanationRequest): BookLearningReply {
        var answer: BookLearningReply.Text? = null
        var refused: ExplainResult.Refused? = null
        val verified = OwnedBookLearningProvider(library) { prompt ->
            require(consent.allowed(request.book))
            val blocks = request.blocks.filterNot { it.heading }
            if (!BookLessonWire.fits(blocks)) {
                refused = ExplainResult.Refused(BookLessonWire.TOO_LONG)
                throw IllegalArgumentException(BookLessonWire.TOO_LONG)
            }
            val bookPages = library.book(request.book).pages.size
            when (val result = sendLesson(blocks, bookPages)) {
                is ExplainResult.Refused -> { refused = result; throw IllegalStateException("refused") }
                is ExplainResult.Done -> {
                    val byId = blocks.zip(prompt.excerpts).associate { (block, excerpt) -> block.id to excerpt }
                    val cited = result.explanation.citations.map { citation ->
                        val excerpt = requireNotNull(byId[citation.blockId])
                        require(citation.page == excerpt.citation.page)
                        excerpt
                    }.distinct()
                    require(cited.isNotEmpty() && consent.allowed(request.book))
                    val readable = ReadableText.withoutCitationMarks(result.explanation.text)
                    answer = BookLearningReply.Text(readable, cited.map {
                        val c = it.citation
                        BookPassage(c.book, c.page, c.start, c.end, it.text)
                    })
                    OwnedBookAnswer(readable, cited.map { it.citation })
                }
            }
        }
        return when (val reply = verified.explain(request)) {
            is BookLearningReply.Text -> checkNotNull(answer)
            is BookLearningReply.Unavailable -> refused?.let { BookLearningReply.Unavailable(it.message, it.trouble) } ?: reply
        }
    }
    override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
        var citedText: String? = null
        // The provider turns any failure into its own generic line; keep the server's sentence and kind here instead.
        var refused: ExplainResult.Refused? = null
        val verified = OwnedBookLearningProvider(library) { prompt ->
            val excerpt = prompt.excerpts.single()
            require(consent.allowed(excerpt.citation.book))
            val c = excerpt.citation
            val block = SourceBlock("${c.book}:p${c.page}:${c.start}", c.page, c.start, c.end, false, excerpt.text)
            when (val result = send(block, checkNotNull(prompt.question))) {
                is ExplainResult.Refused -> { refused = result; throw IllegalStateException("refused") }
                is ExplainResult.Done -> {
                    require(result.explanation.citations.isNotEmpty() && result.explanation.citations.all {
                        it.blockId == block.id && it.page == c.page
                    })
                    citedText = result.explanation.text.trim()
                    OwnedBookAnswer(result.explanation.text, listOf(c))
                }
            }
        }
        return when (val reply = verified.ask(request)) {
            is BookLearningReply.Text -> reply.copy(text = checkNotNull(citedText), sources = listOf(request.passage))
            is BookLearningReply.Unavailable -> refused?.let { BookLearningReply.Unavailable(it.message, it.trouble) } ?: reply
        }
    }
}
