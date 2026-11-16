package com.coach.desktop.books

/** The passage composer uses the same paired, source-authorized service as the explain panel. */
internal class PairedBookLearningSupport(
    private val library: TextbookLibrary,
    private val consent: ExplainConsent = ExplainConsent(library.directory),
    private val send: suspend (SourceBlock, String) -> ExplainResult = { block, question ->
        BookExplainer().run { load(); ask(block, question) }
    },
) : BookLearningSupport {
    override fun consentRequired(book: String) = !consent.allowed(book)
    override fun allow(book: String) = consent.allow(book)
    override suspend fun explain(request: BookExplanationRequest) = UnconnectedBookLearningSupport.explain(request)
    override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
        var citedText: String? = null
        val verified = OwnedBookLearningProvider(library) { prompt ->
            val excerpt = prompt.excerpts.single()
            require(consent.allowed(excerpt.citation.book))
            val c = excerpt.citation
            val block = SourceBlock("${c.book}:p${c.page}:${c.start}", c.page, c.start, c.end, false, excerpt.text)
            when (val result = send(block, checkNotNull(prompt.question))) {
                is ExplainResult.Refused -> throw IllegalStateException(result.message)
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
            is BookLearningReply.Unavailable -> reply
        }
    }
}
