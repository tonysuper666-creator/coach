package com.coach.desktop.books

/** Reading positions use imported pages and UTF-16 offsets, independent of viewport pagination. */
internal object BookBoundaries {
    private val gutenbergStartLine = Regex("""(?im)^\*{3}\s*START OF (?:THE|THIS) PROJECT GUTENBERG EBOOK\b[^\r\n]*$""")
    private val gutenbergEndLine = Regex("""(?im)^\*{3}\s*END OF (?:THE|THIS) PROJECT GUTENBERG EBOOK\b[^\r\n]*$""")
    fun gutenbergEndOffset(page: String): Int? = gutenbergEndLine.find(page)?.range?.first
    private val gutenbergCreditLine = Regex(
        """(?i)^(?:Produced|Transcribed|Prepared|Scanned|Proofread)\s+by\b.*|^Online Distributed Proofreading\b.*|^Updated editions will replace\b.*""",
    )
    private val gutenbergHeaderTitle = Regex("""(?i)^The Project Gutenberg eBook of\b.*|^\*{3}\s*(?:START|END) OF (?:THE|THIS) PROJECT GUTENBERG EBOOK\b.*""")
    private val orphanHeadingPrefix = Regex("""^(?:[ \t\r\n\u000c]+|#{1,6}[ \t]*(?=\r?\n|$))+""")
    private val indentedHtmlHeadingLine = Regex("""^[ \t]*#{1,6}[ ]{5,}\S""")
    private val markdownPrefixRegex = Regex("""^([ \t]*#{1,6}[ ]?)""")

    /**
     * Detect prose paragraphs mis-wrapped in `<h1>..<h6>` by Ebookmaker / HTML converters
     * (e.g. `<h5 id="...">    走。一路幾乎遇不見人...</h5>` -> `"#     走。..."` or punctuated prose lines).
     */
    fun isBogusMarkdownHeadingLine(rawLine: String): Boolean {
        val line = rawLine.trimEnd('\r', '\n')
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("#")) return false
        if (indentedHtmlHeadingLine.containsMatchIn(line)) return true
        val content = trimmed.trimStart('#').trim()
        if (content.isEmpty()) return true
        if (BookStructurePlanner.inferTextHeadingLevel(content) != null) return false
        if ('。' in content) return true
        val last = content.last()
        if (last in "，；、︰") return true
        if (content.count { it in "，；！？" } >= 2) return true
        if (content.length > 35 && content.any { it in "，；。！？" }) return true
        return false
    }

    /** Length of leading `"# "` prefix to mask as zero-width when [isBogusMarkdownHeadingLine] is true. */
    fun bogusMarkdownPrefixEnd(rawLine: String): Int {
        val m = markdownPrefixRegex.find(rawLine) ?: return 0
        return m.range.last + 1
    }

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

    /** Advance past orphan `#\n` heading prefixes (e.g. `<h3>\nCHAPTER 1\n</h3>`) and Gutenberg header credits. */
    fun snapToCleanBodyOffset(pageText: String, rawOffset: Int, skipGutenbergHeader: Boolean = false): Int {
        if (pageText.isEmpty()) return 0
        var pos = rawOffset.coerceIn(0, pageText.length)
        if (skipGutenbergHeader) {
            val startSep = gutenbergStartLine.find(pageText)
            if (startSep != null && pos <= startSep.range.last + 1) {
                pos = skipGutenbergCredits(pageText, startSep.range.last + 1)
            } else {
                pos = skipGutenbergCredits(pageText, pos)
            }
        }
        val m = orphanHeadingPrefix.find(pageText.substring(pos))
        if (m != null) {
            pos = (pos + m.value.length).coerceAtMost(pageText.length)
        }
        return pos
    }

    internal fun skipGutenbergCredits(text: String, fromIndex: Int): Int {
        var cursor = fromIndex.coerceIn(0, text.length)
        while (cursor < text.length) {
            while (cursor < text.length && text[cursor] in " \t\r\n\u000c") cursor++
            if (cursor >= text.length) break
            val lineEnd = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
            val line = text.substring(cursor, lineEnd).trim().trimStart('#').trim()
            if (line.isEmpty() || gutenbergCreditLine.matches(line)) {
                cursor = if (lineEnd < text.length) lineEnd + 1 else text.length
            } else {
                break
            }
        }
        return cursor.coerceIn(0, text.length)
    }

    fun bodyStart(
        pages: List<BookPage>,
        structure: BookStructure?,
        correctedPage: Int? = null,
    ): BookPageAnchor {
        val first = pages.firstOrNull()?.number ?: 1
        (correctedPage ?: structure?.correctedStart)?.takeIf { target -> pages.any { it.number == target } }?.let { return BookPageAnchor(it) }
        structure?.nativeBodyStart?.let { target ->
            val targetPage = pages.firstOrNull { it.number == target.importPage }
            if (targetPage != null) {
                val cleanOffset = snapToCleanBodyOffset(targetPage.text, target.offsetUtf16, skipGutenbergHeader = true)
                return BookPageAnchor(target.importPage, cleanOffset)
            }
        }
        val nodes = structure?.tocNodes.orEmpty()
        val chapter = openingNode(nodes)
        if (chapter != null) {
            val targetPage = pages.firstOrNull { it.number == chapter.startPage }
            if (targetPage != null) {
                val cleanOffset = snapToCleanBodyOffset(targetPage.text, chapter.startOffset, skipGutenbergHeader = chapter.startOffset == 0)
                return BookPageAnchor(chapter.startPage, cleanOffset)
            }
        }
        val bodyPage = pages.firstOrNull {
            (structure == null || structure.pageRole(it.number) == BookPageRole.BODY) &&
                !looksLikeFrontMatter(it.text)
        }
        if (bodyPage != null) {
            val cleanOffset = snapToCleanBodyOffset(bodyPage.text, 0, skipGutenbergHeader = true)
            return BookPageAnchor(bodyPage.number, cleanOffset)
        }
        val fallbackPage = pages.firstOrNull()
        val fallbackOffset = if (fallbackPage != null) snapToCleanBodyOffset(fallbackPage.text, 0, skipGutenbergHeader = true) else 0
        return BookPageAnchor(first, fallbackOffset)
    }

    /**
     * Resolve the exact TOC node (including FRONT_MATTER, BACK_MATTER, PART, CHAPTER, SECTION, APPENDIX)
     * that owns `(page, offset)`. Never blindly drops front/back matter to book title or previous chapter.
     */
    fun activeTocNode(
        tocNodes: List<BookTocNode>,
        page: Int,
        offset: Int = 0,
        preferredNodeId: String? = null,
        pageText: String? = null,
    ): BookTocNode? {
        if (tocNodes.isEmpty()) return null
        val ordered = tocNodes.sortedWith(compareBy(BookTocNode::startPage, BookTocNode::startOffset, BookTocNode::ordinal))
        if (preferredNodeId != null) {
            val preferred = ordered.firstOrNull { it.id == preferredNodeId }
            if (preferred != null && preferred.startPage == page && preferred.startOffset <= offset) {
                val superseded = ordered.any {
                    it.ordinal > preferred.ordinal &&
                        it.startPage == page &&
                        it.startOffset > preferred.startOffset &&
                        it.startOffset <= offset
                }
                if (!superseded) return preferred
            }
        }
        // If a BACK_MATTER license node begins later on this same page after a Gutenberg END separator,
        // offsets at/after that END separator belong to the BACK_MATTER node rather than the preceding story.
        val samePageBackNode = ordered.firstOrNull {
            it.kind == TocNodeKind.BACK_MATTER && it.startPage == page && it.startOffset > offset
        }
        if (samePageBackNode != null && pageText != null) {
            val prefix = pageText.substring(0, samePageBackNode.startOffset.coerceAtMost(pageText.length))
            val endSep = gutenbergEndLine.find(prefix)
            if (endSep != null && offset >= endSep.range.first) {
                return samePageBackNode
            }
        }
        val normalizedPageText = pageText
        return ordered.lastOrNull { node ->
            if (node.startPage < page) true
            else if (node.startPage > page) false
            else {
                val effectiveStart = if (normalizedPageText != null) {
                    // Match both raw startOffset (e.g. pointing to `#\n`) and snapped heading startOffset.
                    minOf(node.startOffset, snapToCleanBodyOffset(normalizedPageText, node.startOffset))
                } else node.startOffset
                effectiveStart <= offset
            }
        }
    }

    fun effectiveChapterForFooter(
        book: Textbook,
        page: Int,
        offset: Int = 0,
        preferredNodeId: String? = null,
    ): BookChapterSpan? {
        if (book.correctedBodyPage?.let { page < it } == true) return null
        val pageText = book.page(page)?.text
        val node = activeTocNode(book.tocNodes, page, offset, preferredNodeId, pageText)
        if (node != null && node.kind in setOf(TocNodeKind.FRONT_MATTER, TocNodeKind.BACK_MATTER)) {
            return null
        }
        val role = book.structure?.pageRole(page)
        if (role in setOf(BookPageRole.COPYRIGHT, BookPageRole.TOC, BookPageRole.FRONT_MATTER, BookPageRole.INDEX, BookPageRole.REFERENCES) &&
            (node == null || node.kind in setOf(TocNodeKind.FRONT_MATTER, TocNodeKind.BACK_MATTER))
        ) {
            return null
        }
        return BookChapterResolver.chapterForPage(book, page, offset)?.takeUnless { it.tocNodeId == "ch-all" }
    }

    fun looksLikeBoilerplateHeader(title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return true
        if (gutenbergHeaderTitle.matches(trimmed)) return true
        if (trimmed.length >= 25 && trimmed.count { it in "，。；！？" } >= 2) return true
        return false
    }

    /** Keep the section selected in the TOC while honoring a reader's chapter title in the header. */
    fun headerTitleForNode(book: Textbook, node: BookTocNode): String {
        if (book.structure?.source != HeadingSource.MANUAL.name) return node.title
        var ancestor = node
        while (ancestor.kind == TocNodeKind.SECTION || ancestor.kind == TocNodeKind.SUBSECTION) {
            val parent = book.tocNodes.firstOrNull { it.id == ancestor.parentId } ?: break
            if (parent.level >= ancestor.level) break
            ancestor = parent
        }
        return if (ancestor.kind == TocNodeKind.CHAPTER && ancestor.source == HeadingSource.MANUAL) ancestor.title
            else node.title
    }

    /** A publisher's opening notice can share an imported page with the first real TOC entry. */
    fun unlistedFrontMatterTitle(book: Textbook, anchor: BookPageAnchor): String? {
        val page = book.page(anchor.importPage) ?: return null
        val heading = page.text.lineSequence().map { it.trim().trimStart('#').trim() }.firstOrNull { it.isNotEmpty() }
            ?: return null
        if (heading.startsWith("The Project Gutenberg eBook of ", ignoreCase = true)) {
            val separator = gutenbergStartLine.find(page.text)
            if (separator != null && anchor.offsetUtf16 <= separator.range.last) return "授权页"
        }
        val role = book.structure?.pageRole(page.number)
        if (nonBodyHeading.matches(heading)) return heading
        if (role == BookPageRole.FRONT_MATTER && heading.length <= 100 &&
            BookTocWaterfall.classifyOutlineLabel(heading).kind == TocNodeKind.FRONT_MATTER
        ) return heading
        return if (role == BookPageRole.COPYRIGHT) "版权页" else null
    }

    fun runningHeaderTitle(book: Textbook, page: BookLaidPage, preferredNodeId: String? = null): String {
        val anchor = page.anchor
        val pageText = book.page(anchor.importPage)?.text
        if (book.correctedBodyPage?.let { anchor.importPage < it } == true) return ""
        val node = activeTocNode(book.tocNodes, anchor.importPage, anchor.offsetUtf16, preferredNodeId, pageText)
        if (node != null) return headerTitleForNode(book, node)
        val cleanPageTitle = page.title?.takeUnless(::looksLikeBoilerplateHeader)
        return unlistedFrontMatterTitle(book, anchor) ?: cleanPageTitle.orEmpty()
    }

    private val nonBodyHeading = Regex(
        """^(?:Cover|Half\s+Title|Title\s+Page|Copyright(?:\s+Page)?|Brief\s+Contents|Detailed\s+Contents|Contents|Table\s+of\s+Contents|Blank\s+Page|Illustrations|List\s+of\s+(?:Illustrations|Figures|Tables|Boxes)|Preface|Foreword|Introduction to the book|License|Licen[cs]e Agreement|目\s*录|总\s*目\s*录|简\s*目|版权页|授权页|插图目录|封面)[.:]?$""",
        RegexOption.IGNORE_CASE,
    )
    private val chapterHeading = Regex(
        """(?m)^(?:第[一二三四五六七八九十百0-9]+[章节篇]|Chapter\s+[0-9IVXLC]+|1\.\d+\s+)""",
        RegexOption.IGNORE_CASE,
    )

    fun looksLikeFrontMatter(text: String, chapterTitle: String? = null): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        val lines = trimmed.lineSequence().map { it.trim().trimStart('#').trim() }.filter(String::isNotEmpty).toList()
        if (lines.isEmpty()) return true
        if (nonBodyHeading.matches(lines.first()) || ExplainClassifier.copyrightMarkerCount(trimmed) >= 2) return true
        val lower = trimmed.lowercase()
        if (trimmed.length < 1500 && listOf("isbn", "copyright", "all rights reserved", "版权所有", "图书在版编目").any { it in lower }) return true
        if (chapterTitle != null && lines.any { it.equals(chapterTitle, ignoreCase = true) }) return false
        return lines.size <= 4 && trimmed.length < 140 && lines.none { chapterHeading.containsMatchIn(it) } &&
            '。' !in trimmed && trimmed.count { it == '.' } <= 1
    }

    fun remainingChapterScreens(
        book: Textbook,
        chapter: BookChapterSpan?,
        laidPages: List<BookLaidPage>,
        activeSheetIndex: Int,
        visibleColumns: Int = 1,
    ): Int {
        if (chapter == null || laidPages.isEmpty() || activeSheetIndex !in laidPages.indices) return 0
        val cols = visibleColumns.coerceAtLeast(1)
        val lastVisibleIndex = (activeSheetIndex + cols - 1).coerceAtMost(laidPages.lastIndex)
        var lastChapterSheet = lastVisibleIndex
        for (idx in (lastVisibleIndex + 1)..laidPages.lastIndex) {
            val anchor = laidPages[idx].anchor
            if (anchor.importPage > chapter.lastPage) break
            val spanAtSheet = effectiveChapterForFooter(book, anchor.importPage, anchor.offsetUtf16)
            if (spanAtSheet?.tocNodeId == chapter.tocNodeId) {
                lastChapterSheet = idx
            } else if (anchor.importPage > chapter.firstPage || anchor.offsetUtf16 > chapter.firstOffset) {
                break
            }
        }
        val remainingSheets = (lastChapterSheet - lastVisibleIndex).coerceAtLeast(0)
        return if (cols <= 1) remainingSheets else (remainingSheets + cols - 1) / cols
    }

    fun pageStatus(
        page: Int,
        lastPage: Int,
        chapter: BookChapterSpan?,
        subPage: Int = 1,
        subPageCount: Int = 1,
        chapterRemainingScreens: Int = 0,
    ): String {
        val position = "第 $page / $lastPage 页"
        val screenPart = if (subPageCount > 1) " · 第 ${subPage.coerceIn(1, subPageCount)}/$subPageCount 屏" else ""
        val remaining = chapter?.let { it.lastPage - page } ?: return "$position$screenPart"
        val chapterPart = when {
            remaining > 0 -> " · 本章还剩 $remaining 页"
            chapterRemainingScreens > 0 -> " · 本章还剩 $chapterRemainingScreens 屏"
            else -> " · 本章完"
        }
        return "$position$screenPart$chapterPart"
    }
}
