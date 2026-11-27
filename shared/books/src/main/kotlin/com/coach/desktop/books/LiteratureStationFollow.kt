package com.coach.desktop.books

/** One stop on the station follow route: a body sentence the reader can open on its page. */
internal data class LiteratureStation(
    val bookId: String,
    val bookTitle: String,
    val title: String,
    val page: Int,
    val quote: String,
)

/**
 * Stations for 逐站跟读. Sentences come from the same filtered pool as the one-page guide.
 * Chapter titles are used when the book already has them; otherwise stops follow the reading order.
 */
internal object LiteratureStationFollow {
    fun stations(book: Textbook): List<LiteratureStation> {
        val candidates = LiteraturePassages.extractCandidates(book)
        if (candidates.isEmpty()) return emptyList()
        val grouped = groupByChapter(book, candidates)
        val groups = grouped.ifEmpty { chunkInOrder(candidates) }
        return groups.map { (title, sentences) ->
            val stop = sentences.first()
            LiteratureStation(
                bookId = book.id,
                bookTitle = book.title,
                title = title.ifBlank { "第 ${stop.anchor.pdfPage} 页" },
                page = stop.anchor.pdfPage,
                quote = stop.anchor.quoteAnchor,
            )
        }
    }

    fun load(library: TextbookLibrary, bookIds: List<String>): List<LiteratureStation> =
        bookIds.flatMap { id ->
            val book = runCatching { library.literatureBook(id) }.getOrNull() ?: return@flatMap emptyList()
            stations(book)
        }

    private fun groupByChapter(book: Textbook, candidates: List<GuideExcerpt>): List<Pair<String, List<GuideExcerpt>>> {
        val nodes = book.tocNodes
            .filter { it.kind == TocNodeKind.CHAPTER || it.kind == TocNodeKind.SECTION }
            .filter { it.title.isNotBlank() }
            .sortedWith(compareBy(BookTocNode::startPage, BookTocNode::ordinal))
        if (nodes.size < 2) return emptyList()
        val grouped = nodes.mapNotNull { node ->
            val end = if (node.endPage >= node.startPage) node.endPage else node.startPage
            val inNode = candidates.filter { it.anchor.pdfPage in node.startPage..end }
            if (inNode.isEmpty()) null else node.title to inNode
        }
        return if (grouped.isEmpty()) emptyList() else grouped
    }

    private fun chunkInOrder(candidates: List<GuideExcerpt>): List<Pair<String, List<GuideExcerpt>>> {
        val groups = mutableListOf<MutableList<GuideExcerpt>>()
        for (candidate in candidates) {
            val last = groups.lastOrNull()
            val previousPage = last?.lastOrNull()?.anchor?.pdfPage
            val page = candidate.anchor.pdfPage
            if (last == null || last.size >= 6 || (previousPage != null && page - previousPage > 2)) {
                groups += mutableListOf(candidate)
            } else {
                last += candidate
            }
        }
        return groups.map { group ->
            val page = group.first().anchor.pdfPage
            "第 $page 页" to group
        }
    }
}
