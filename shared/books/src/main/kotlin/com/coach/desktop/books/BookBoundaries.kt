package com.coach.desktop.books

/** Reading positions use imported pages and UTF-16 offsets, independent of viewport pagination. */
internal object BookBoundaries {
    fun firstChapter(nodes: List<BookTocNode>): BookTocNode? =
        nodes.firstOrNull { it.kind == TocNodeKind.CHAPTER }
            ?: nodes.firstOrNull { it.kind in setOf(TocNodeKind.SECTION, TocNodeKind.SUBSECTION, TocNodeKind.APPENDIX) }

    private val substantiveIntroduction = Regex("""(?i)^Introduction(?:\s+and\s+.+)?[.:]?$""")

    fun openingNode(nodes: List<BookTocNode>): BookTocNode? {
        val firstChapter = firstChapter(nodes)
        return nodes.takeWhile { it.id != firstChapter?.id }.firstOrNull {
            it.source == HeadingSource.EPUB_TOC && substantiveIntroduction.matches(it.title)
        } ?: firstChapter
    }

    fun bodyStart(
        pages: List<BookPage>,
        structure: BookStructure?,
        correctedPage: Int? = null,
    ): BookPageAnchor {
        val first = pages.firstOrNull()?.number ?: 1
        correctedPage?.takeIf { target -> pages.any { it.number == target } }?.let { return BookPageAnchor(it) }
        structure?.nativeBodyStart?.takeIf { target -> pages.any { it.number == target.importPage } }?.let { return it }
        val nodes = structure?.tocNodes.orEmpty()
        val chapter = openingNode(nodes)
        if (chapter != null && pages.any { it.number == chapter.startPage }) {
            return BookPageAnchor(chapter.startPage, chapter.startOffset)
        }
        return BookPageAnchor(pages.firstOrNull {
            (structure == null || structure.pageRole(it.number) == BookPageRole.BODY) &&
                !looksLikeFrontMatter(it.text)
        }?.number ?: first)
    }

    private val nonBodyHeading = Regex(
        """^(?:Cover|Half\s+Title|Title\s+Page|Copyright(?:\s+Page)?|Brief\s+Contents|Detailed\s+Contents|Contents|Table\s+of\s+Contents|Blank\s+Page|Illustrations|List\s+of\s+(?:Illustrations|Figures|Tables|Boxes)|Preface|Foreword|Introduction to the book|License|Licen[cs]e Agreement|目\s*录|总\s*目\s*录|简\s*目|版权页|授权页|插图目录|封面)[.:]?$""",
        RegexOption.IGNORE_CASE,
    )
    private val chapterHeading = Regex(
        """(?m)^(?:第[一二三四五六七八九十百0-9]+[章节篇]|Chapter\s+[0-9IVXLC]+|1\.\d+\s+)""",
        RegexOption.IGNORE_CASE,
    )

    fun looksLikeFrontMatter(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        val lines = trimmed.lineSequence().map { it.trim().trimStart('#').trim() }.filter(String::isNotEmpty).toList()
        if (lines.isEmpty()) return true
        if (nonBodyHeading.matches(lines.first()) || ExplainClassifier.copyrightMarkerCount(trimmed) >= 2) return true
        val lower = trimmed.lowercase()
        if (trimmed.length < 1500 && listOf("isbn", "copyright", "all rights reserved", "版权所有", "图书在版编目").any { it in lower }) return true
        return lines.size <= 4 && trimmed.length < 140 && lines.none { chapterHeading.containsMatchIn(it) } &&
            '。' !in trimmed && trimmed.count { it == '.' } <= 1
    }

    fun pageStatus(page: Int, lastPage: Int, chapter: BookChapterSpan?): String {
        val position = "第 $page / $lastPage 页"
        val remaining = chapter?.let { it.lastPage - page } ?: return position
        return if (remaining <= 0) "$position · 本章完" else "$position · 本章还剩 $remaining 页"
    }
}
