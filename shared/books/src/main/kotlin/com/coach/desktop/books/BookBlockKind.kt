package com.coach.desktop.books

/**
 * 原文块是哪一种。讲解面板只给 [Body]（正文段落）挂「讲解这段」：目录条目、标题、页眉页脚、
 * 单独的页码和很短的行讲出来只会是「这一行是目录条目……」这种没用的话（TON-1766）。
 * 只决定给不给讲解入口，不改块本身，也不改「第几段」的数法（那个仍由 [isBodyParagraph] 决定）。
 */
internal enum class BookBlockKind { Body, Toc, Heading, PageMark, PageNumber, Running, Short }

/** 这一页整体是什么。只有 [Body] 页才给「讲解这一页」，其余给一句灰字。 */
internal enum class BookPageKind(val note: String?) {
    Body(null),
    Toc("这一页是目录，翻到正文再讲。"),
    Copyright("这一页是版权页，翻到正文再讲。"),
    Blank("这一页没有文字，翻到正文再讲。"),
    TitleOnly("这一页只有标题，翻到正文再讲。"),
}

internal object BookBlockKinds {
    /** 去掉空白后不到这么多字的块不给讲解入口。 */
    const val MIN_BODY_CHARS = 40

    private const val PAGE = "(?:[0-9０-９]{1,4}|[ivxlcdmIVXLCDM]{1,7})"
    /** 目录的引导点（……、....、·····、____、—— 或一串空格）后面跟页码。 */
    private val leaderLine = Regex("""^\S.{0,160}?(?:[.．·•…⋯。_\-—–]{2,}|[\s　]{3,})[\s　]*$PAGE$""")
    /** 没有引导点、以页码结尾的短行：「第一章 总论 1」「Introduction  3」。只认阿拉伯数字，免得把以 mild、civil 收尾的英文短句当成目录。 */
    private val trailingPage = Regex("""^(\S.*?)[\s　]+[0-9０-９]{1,4}$""")
    private val sentenceEnd = Regex("""[。！？!?；;，,：:、]$""")
    private val sentenceInside = Regex("""[，。；！？]""")
    private val pageNumber = Regex(
        """^[-–—~\s]*(?:第\s*)?[0-9０-９]{1,4}(?:\s*页)?(?:\s*[/／]\s*[0-9０-９]{1,4})?(?:\s*共\s*[0-9０-９]{1,4}\s*页)?[-–—~\s]*$""" +
            """|^(?:page|p\.)\s*[0-9]{1,4}(?:\s*(?:of|/)\s*[0-9]{1,4})?$|^[ivxlcdm]{1,7}$""",
        RegexOption.IGNORE_CASE)
    /** 编号小标题：「一、课程性质」「（二）学段目标」「2.3 实验」，不以句读结尾。 */
    private val numberedHeading = Regex("""^(?:[一二三四五六七八九十百]+[、.．]|[（(][一二三四五六七八九十百0-9]+[)）]|[0-9]+(?:\.[0-9]+)*[、.．\s]|第[一二三四五六七八九十百0-9]+部分).+""")
    private val tocTitle = Regex("""^(?:目\s*录|目\s*次|contents|table of contents)$""", RegexOption.IGNORE_CASE)
    private val isbn = Regex("""ISBN[\s:：-]*[0-9][0-9Xx\s-]{8,20}""", RegexOption.IGNORE_CASE)

    private fun compact(text: String) = text.filterNot(Char::isWhitespace)
    private fun lines(text: String) = text.lines().map(String::trim).filter(String::isNotEmpty)
    /** 页眉页脚按「去掉数字和空白后的样子」比较，「第 3 页」和「第 4 页」算同一行。 */
    private fun runningKey(text: String) = compact(text).replace(Regex("[0-9０-９]+"), "#")

    /** 一行是不是目录条目。 */
    fun tocLine(line: String): Boolean {
        val text = line.trim()
        if (leaderLine.matches(text)) return true
        val head = trailingPage.matchEntire(text)?.groupValues?.get(1) ?: return false
        // 目录条目里不会有句中的逗号、句号：「……参考答案见第 245」这种以数字收尾的正文不算。
        return compact(text).length <= 50 && !sentenceEnd.containsMatchIn(head.trim()) && !sentenceInside.containsMatchIn(head)
    }

    /**
     * 在三页以上重复出现的短行（页眉页脚：书名、章名、「第 3 页 共 20 页」）。
     * 版式信息拿不到，只能靠「每页都有」来认。
     */
    fun running(blocks: List<SourceBlock>): Set<String> = blocks.asSequence()
        .filter { lines(it.text).size == 1 && compact(it.text).length in 1..80 }
        .groupBy({ runningKey(it.text) }, { it.page })
        .filterValues { it.distinct().size >= 3 }.keys

    fun of(block: SourceBlock, running: Set<String> = emptySet()): BookBlockKind {
        val text = block.text.trim()
        val rows = lines(text)
        val size = compact(text).length
        return when {
            PRINTED_PAGE_MARK.matches(text) -> BookBlockKind.PageMark
            // 「第一章 总论 …… 1」也会被切成章标题块，带页码的仍算目录。
            block.heading -> if (tocLine(text)) BookBlockKind.Toc else BookBlockKind.Heading
            rows.isEmpty() -> BookBlockKind.Short
            rows.size == 1 && pageNumber.matches(text) -> BookBlockKind.PageNumber
            rows.size == 1 && runningKey(text) in running -> BookBlockKind.Running
            // 多行的目录块：大半行都是目录条目就算目录（中间夹着的章名行没有页码）。
            rows.count(::tocLine) * 5 >= rows.size * 3 -> BookBlockKind.Toc
            rows.size == 1 && size < 60 && numberedHeading.matches(text) && !sentenceEnd.containsMatchIn(text) -> BookBlockKind.Heading
            size < MIN_BODY_CHARS -> BookBlockKind.Short
            else -> BookBlockKind.Body
        }
    }

    fun explainable(block: SourceBlock, running: Set<String> = emptySet()) = of(block, running) == BookBlockKind.Body

    /** [onPage] 是这一页的全部块（含标题），按书里的先后。 */
    fun page(onPage: List<SourceBlock>, running: Set<String> = emptySet()): BookPageKind {
        if (onPage.none { compact(it.text).isNotEmpty() && !PRINTED_PAGE_MARK.matches(it.text.trim()) }) return BookPageKind.Blank
        val all = onPage.joinToString("\n") { it.text }
        val total = compact(all).length
        if (isbn.containsMatchIn(all) || "图书在版编目" in all || Regex("CIP\\s*数据").containsMatchIn(all) ||
            ("版权所有" in all && total < 600) || (all.contains("All rights reserved", ignoreCase = true) && total < 1_200))
            return BookPageKind.Copyright
        val kinds = onPage.map { of(it, running) }
        val body = kinds.count { it == BookBlockKind.Body }
        val toc = kinds.count { it == BookBlockKind.Toc }
        val titled = onPage.any { b -> lines(b.text).any { tocTitle.matches(it) } }
        return when {
            (toc > 0 || titled) && toc >= body -> BookPageKind.Toc
            body > 0 -> BookPageKind.Body
            else -> BookPageKind.TitleOnly
        }
    }
}
