package com.coach.desktop.books

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The caller supplies both transport and authorization; the default denies all private-book output. */
internal fun windowsBookLearningSupport(
    library: TextbookLibrary,
    transport: OwnedBookCliTransport,
    policy: OwnedBookOutboundPolicy = DenyOwnedBookOutbound,
): BookLearningSupport = WindowsCitedBookSupport(library, OwnedBookCliExecutor(transport, policy))

/** Preserve the executor's typed citations after the provider has validated its complete reply. */
private class WindowsCitedBookSupport(
    private val library: TextbookLibrary,
    private val executor: OwnedBookExecutor,
) : BookLearningSupport {
    override suspend fun explain(request: BookExplanationRequest): BookLearningReply =
        answer { it.explain(request) }

    override suspend fun ask(request: BookPassageQuestion): BookLearningReply =
        answer { it.ask(request) }

    private suspend fun answer(call: suspend (OwnedBookLearningProvider) -> BookLearningReply): BookLearningReply {
        var captured: Pair<OwnedBookPrompt, OwnedBookAnswer>? = null
        val provider = OwnedBookLearningProvider(library, OwnedBookExecutor { prompt ->
            executor.answer(prompt).also { captured = prompt to it }
        })
        val result = call(provider)
        if (result !is BookLearningReply.Text) return result
        val (prompt, reply) = captured ?: return unavailable()
        val sources = withContext(Dispatchers.IO) {
            val resolver = BookSourceResolver(library)
            reply.citations.distinct().map { citation ->
                val excerpt = prompt.excerpts.singleOrNull { it.citation == citation } ?: return@withContext null
                val resolved = resolver.resolve(citation, excerpt.text) as? BookSourceResolution.Resolved
                    ?: return@withContext null
                if (!resolver.stillValid(resolved.target)) return@withContext null
                BookPassage(citation.book, citation.page, citation.start, citation.end, excerpt.text)
            }
        } ?: return unavailable()
        if (sources.isEmpty()) return unavailable()
        return BookLearningReply.Text(reply.text.trim(), sources)
    }

    private fun unavailable() = BookLearningReply.Unavailable("讲解暂时不可用，请检查书籍和来源后重试。")
}
