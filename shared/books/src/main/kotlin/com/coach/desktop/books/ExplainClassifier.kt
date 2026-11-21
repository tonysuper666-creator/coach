package com.coach.desktop.books

/**
 * 页面内每个原文块的分类。
 * 只有 [BODY] 属于可讲解的正文段落；
 * 目录条目、标题、页眉页脚、单独页码和不足 40 字的短行不作为正文，避免把无意义的碎行发去讲解。
 */
internal enum class ExplainBlockKind {
    /** 正文段落：去掉空白后至少 40 字，且不是目录、标题、页眉页脚或页码。 */
    BODY,
    /** 目录条目：引导点加页码、以页码结尾的短行，或多行均为目录条目。 */
    TOC_ENTRY,
    /** 标题：章/节标题、序号短标题，或目录/前言/版权/附录等独立标题。 */
    HEADING,
    /** 页眉、页脚或版权出版信息。 */
    HEADER_FOOTER,
    /** 单独的页码（阿拉伯数字、罗马数字、横线夹页码、「第 N 页」、导入的页码标记）。 */
    PAGE_NUMBER,
    /** 去掉空白后不到 40 字的短行。 */
    SHORT,
}

/**
 * 整页角色分类：只有 [BODY] 页展示本节导读与「讲讲这一节」，其余页面只显示灰字提示。
 */
internal enum class ExplainPageKind(val notice: String?) {
    BODY(null),
    TOC("这一页是目录，翻到正文再讲。"),
    COPYRIGHT("这一页是版权信息，翻到正文再讲。"),
    HEADINGS_ONLY("这一页只有标题，翻到正文再讲。"),
    BLANK("这一页没有可讲的正文。"),
}

internal data class ExplainPageAnalysis(
    val kind: ExplainPageKind,
    val pageBlocks: List<SourceBlock>,
    val blockKinds: Map<String, ExplainBlockKind>,
    val bodyBlocks: List<SourceBlock>,
)

internal object ExplainClassifier {
    const val MIN_BODY_CHARS = 40

    private val ROMAN_PAGE = Regex(
        """^(?:[-–—·•\[\(（【]\s*)?M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})(?:\s*[-–—·•\]\)）】])?$""",
        RegexOption.IGNORE_CASE,
    )
    private val ARABIC_PAGE = Regex(
        """^(?:[-–—·•.。\[\(（【]\s*)?\d{1,5}(?:\s*/\s*\d{1,5})?(?:\s*[-–—·•.。\]\)）】])?$""",
    )
    private val NAMED_PAGE = Regex(
        """^(?:第\s*[0-9一二三四五六七八九十百千]+\s*页|(?:Page|P\.?)\s*\d{1,5}(?:\s*/\s*\d{1,5})?)$""",
        RegexOption.IGNORE_CASE,
    )

    private val TOC_LEADER_LINE = Regex(
        """^(.{1,120}?)(?:[…⋯]{1,}|[.．·•\-–—_](?:\s*[.．·•\-–—_]){2,})\s*(?:[pP]\.?\s*)?(?:\d{1,5}|[ivxlcdmIVXLCDM]{1,6})\s*$""",
    )
    private val TOC_TRAILING_WIDE_GAP = Regex(
        """^(.{2,80}?)(?:\t|\s{2,})(?:[pP]\.?\s*)?(?:\d{1,5}|[ivxlcdmIVXLCDM]{1,6})\s*$""",
    )
    private val TOC_TRAILING_SINGLE_SPACE = Regex(
        """^(.{2,60}?)\s+(\d{1,4})\s*$""",
    )
    private val TOC_SECTION_PREFIX = Regex(
        """^(?:[一二三四五六七八九十百]+[、.．\s]|[（(][一二三四五六七八九十0-9]+[）)]|第[一二三四五六七八九十百0-9]+[章节篇部单元讲节]|\d+(?:\.\d+)*[\s、.．]|(?:Chapter|Section|Part|Unit|Appendix)\s+\d|(?:前言|序言?|目录|引言|结语|后记|参考文献|附录|索引|致谢))""",
        RegexOption.IGNORE_CASE,
    )
    private val LONE_LABEL_BEFORE_NUM = Regex(
        """^(?:Chapter|Section|Part|Unit|Lesson|Figure|Fig\.?|Table|Appendix|Step|No\.?|图|表|附录|例|题|式|注|第)$""",
        RegexOption.IGNORE_CASE,
    )
    private val TOC_HEADING = Regex(
        """^(?:目\s*录|总\s*目\s*录|简\s*目|Contents|Table\s+of\s+Contents)$""",
        RegexOption.IGNORE_CASE,
    )

    private val HEADING_PATTERN = Regex(
        """^(?:#{1,6}\s+\S|第[一二三四五六七八九十百千0-9]+[章节篇部回讲节单元](?:\s|\S|$)|[一二三四五六七八九十百]+[、.．]\s*\S|[（(][一二三四五六七八九十0-9]+[）)]\s*\S|\d+(?:\.\d+){0,3}[\s、.．]\s*\S|(?:Chapter|Section|Part|Unit|Appendix|Lesson)\s+[0-9IVXLC]+(?:\b|$)|(?:目\s*录|总\s*目\s*录|简\s*目|前\s*言|序\s*言?|引\s*言|编\s*者\s*按|凡\s*例|版权页|参考文献|附\s*录|后\s*记|致\s*谢|索\s*引|Contents|Table\s+of\s+Contents|Preface|Foreword|Introduction|Copyright|References|Bibliography|Appendix|Index)$)""",
        RegexOption.IGNORE_CASE,
    )
    private val SENTENCE_ENDINGS = setOf('。', '！', '？', '.', '!', '?', '；', ';', '，', ',')

    private val COPYRIGHT_PATTERN = Regex(
        """(?:ISBN\s*[:：]?\s*(?:97[89][-\s]?)?\d|图书在版编目|CIP\s*数据|版权所有|翻印必究|All\s+rights\s+reserved|Copyright\s*©|©\s*\d{4}|出版发行\s*[:：]|责任编辑\s*[:：]|开本\s*[:：]|印张\s*[:：]|版次\s*[:：]|印次\s*[:：]|定价\s*[:：]|书号\s*[:：]|未经.{0,16}(?:书面)?许可.{0,10}不得(?:复制|翻印|转载|传播|摘编))""",
        RegexOption.IGNORE_CASE,
    )
    private val COPYRIGHT_DISTINCT_PATTERNS = listOf(
        Regex("""ISBN\s*[:：]?\s*(?:97[89][-\s]?)?\d""", RegexOption.IGNORE_CASE),
        Regex("""图书在版编目|CIP\s*数据""", RegexOption.IGNORE_CASE),
        Regex("""版权所有|翻印必究|All\s+rights\s+reserved""", RegexOption.IGNORE_CASE),
        Regex("""Copyright\s*©|©\s*\d{4}""", RegexOption.IGNORE_CASE),
        Regex("""(?:出版发行|责任编辑|开本|印张|版次|印次|定价|书号)\s*[:：]""", RegexOption.IGNORE_CASE),
        Regex("""未经.{0,16}(?:书面)?许可.{0,10}不得(?:复制|翻印|转载|传播|摘编)""", RegexOption.IGNORE_CASE),
    )
    private val RUNNING_HEADER_PATTERN = Regex(
        """^(?:\d{1,5}\s{2,}\S.{1,40}|.{2,40}\s*[·|｜]\s*\d{1,5}|[-–—]\s*\d{1,5}\s*[-–—]\s+\S.{1,40}|(?:Page|P\.?)\s*\d{1,5}\s*[·|｜\-–—]\s*\S.{1,40})$""",
        RegexOption.IGNORE_CASE,
    )

    /** 去掉所有空白（含全角空格、不换行空格、零宽字符）后的 Unicode 字符数。 */
    fun nonWhitespaceLength(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!isIgnoredSpace(cp)) count++
        }
        return count
    }

    private fun isIgnoredSpace(cp: Int): Boolean =
        Character.isWhitespace(cp) || cp == 0x00A0 || cp == 0x3000 || cp == 0x200B || cp == 0xFEFF

    /**
     * 对单个原文块分类。不传页上下文时也能独立准确识别目录行、标题、页码、版权/页眉页脚和短行；
     * 传入 [positionOnPage] / [pageBlockCount] / [repeatingEdgeKeys] 后，还能识别跨页重复的页眉页脚。
     */
    fun classifyBlock(
        block: SourceBlock,
        positionOnPage: Int = -1,
        pageBlockCount: Int = 0,
        repeatingEdgeKeys: Set<String> = emptySet(),
    ): ExplainBlockKind {
        val trimmed = block.text.trim()
        if (trimmed.isEmpty() || PRINTED_PAGE_MARK.matches(trimmed) || isPageNumber(trimmed)) {
            return ExplainBlockKind.PAGE_NUMBER
        }
        if (isTocBlock(trimmed)) return ExplainBlockKind.TOC_ENTRY
        if (isCopyrightBlock(trimmed)) return ExplainBlockKind.HEADER_FOOTER
        if (isRunningHeaderFooter(trimmed, positionOnPage, pageBlockCount, repeatingEdgeKeys)) {
            return ExplainBlockKind.HEADER_FOOTER
        }
        if (isHeadingBlock(block, trimmed)) return ExplainBlockKind.HEADING
        if (nonWhitespaceLength(trimmed) < MIN_BODY_CHARS) return ExplainBlockKind.SHORT
        return ExplainBlockKind.BODY
    }

    /**
     * 预先提取全书跨页重复的页眉页脚键，供 [analyzePage] 在 O(N) 内完成整本书分页。
     */
    fun precomputeRepeatingEdges(allBlocks: List<SourceBlock>): Set<String> = repeatingEdgeKeys(allBlocks)

    /**
     * 综合整本书的块与当前页，判断这一页的类型以及每一块的分类。
     */
    fun analyzePage(allBlocks: List<SourceBlock>, page: Int): ExplainPageAnalysis {
        val pageBlocks = allBlocks.filter { it.page == page }
        if (pageBlocks.isEmpty()) {
            return ExplainPageAnalysis(ExplainPageKind.BLANK, emptyList(), emptyMap(), emptyList())
        }
        return analyzePage(pageBlocks, repeatingEdgeKeys(allBlocks))
    }

    /** 使用预计算的跨页重复页眉页脚集合分类单页（O(页内块数)）。 */
    fun analyzePage(pageBlocks: List<SourceBlock>, repeatingEdges: Set<String>): ExplainPageAnalysis {
        if (pageBlocks.isEmpty()) {
            return ExplainPageAnalysis(ExplainPageKind.BLANK, emptyList(), emptyMap(), emptyList())
        }
        val visible = pageBlocks.filterNot { PRINTED_PAGE_MARK.matches(it.text.trim()) }
        val kinds = LinkedHashMap<String, ExplainBlockKind>(pageBlocks.size)
        for (block in pageBlocks) {
            val pos = visible.indexOfFirst { it.id == block.id }
            kinds[block.id] = classifyBlock(block, pos, visible.size, repeatingEdges)
        }
        val bodyBlocks = pageBlocks.filter { kinds[it.id] == ExplainBlockKind.BODY }
        val pageKind = classifyPageKind(pageBlocks, kinds, bodyBlocks)
        return ExplainPageAnalysis(pageKind, pageBlocks, kinds, bodyBlocks)
    }

    private fun classifyPageKind(
        pageBlocks: List<SourceBlock>,
        kinds: Map<String, ExplainBlockKind>,
        bodyBlocks: List<SourceBlock>,
    ): ExplainPageKind {
        if (bodyBlocks.isNotEmpty()) return ExplainPageKind.BODY
        val nonPage = pageBlocks.filter { kinds[it.id] != ExplainBlockKind.PAGE_NUMBER }
        if (nonPage.isEmpty()) return ExplainPageKind.BLANK
        val hasTocEntry = nonPage.any { kinds[it.id] == ExplainBlockKind.TOC_ENTRY }
        val hasTocHeading = nonPage.any { isTocHeading(it.text.trim()) }
        if (hasTocEntry || hasTocHeading) return ExplainPageKind.TOC
        val hasCopyright = nonPage.any { isCopyrightBlock(it.text.trim()) }
        if (hasCopyright) return ExplainPageKind.COPYRIGHT
        val nonEdge = nonPage.filter { kinds[it.id] != ExplainBlockKind.HEADER_FOOTER }
        if (nonEdge.isEmpty()) return ExplainPageKind.BLANK
        return ExplainPageKind.HEADINGS_ONLY
    }

    internal fun isPageNumber(trimmed: String): Boolean {
        if ('\n' in trimmed || '\r' in trimmed || trimmed.length > 24) return false
        if (ARABIC_PAGE.matches(trimmed) || NAMED_PAGE.matches(trimmed)) return true
        val core = trimmed.trim('-', '–', '—', '·', '•', '[', ']', '(', ')', '（', '）', '【', '】', ' ')
        return core.isNotEmpty() && core.length <= 7 && ROMAN_PAGE.matches(trimmed)
    }

    internal fun isTocHeading(trimmed: String): Boolean =
        '\n' !in trimmed && '\r' !in trimmed && TOC_HEADING.matches(trimmed)

    internal fun isTocLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        val leader = TOC_LEADER_LINE.matchEntire(trimmed)
        if (leader != null) {
            val prefix = leader.groupValues[1].trim()
            if (prefix.isNotEmpty() && prefix.last() !in setOf('。', '！', '？')) return true
        }
        if (nonWhitespaceLength(trimmed) > 60) return false
        val wide = TOC_TRAILING_WIDE_GAP.matchEntire(trimmed)
        if (wide != null) {
            val prefix = wide.groupValues[1].trim()
            if (prefix.isNotEmpty() && !LONE_LABEL_BEFORE_NUM.matches(prefix) &&
                prefix.last() !in setOf('。', '！', '？', '=', '+', '-', '/', ':', '：')) {
                return true
            }
        }
        val single = TOC_TRAILING_SINGLE_SPACE.matchEntire(trimmed)
        if (single != null) {
            val prefix = single.groupValues[1].trim()
            val number = single.groupValues[2].toIntOrNull() ?: return false
            if (number in 1900..2099) return false
            if (LONE_LABEL_BEFORE_NUM.matches(prefix)) return false
            if (prefix.last() in setOf('。', '！', '？', '=', '+', '-', '/', ':', '：')) return false
            if (TOC_SECTION_PREFIX.containsMatchIn(prefix)) return true
        }
        return false
    }

    private fun isTocBlock(trimmed: String): Boolean {
        val lines = trimmed.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.isEmpty()) return false
        if (lines.size == 1) return isTocLine(lines[0])
        val tocCount = lines.count(::isTocLine)
        if (tocCount == 0) return false
        if (lines.all { isTocLine(it) || isTocHeading(it) || isPageNumber(it) }) return true
        // 一条较长的目录项折成 2–3 行、末行带引导点与页码。
        if (lines.size <= 3 && TOC_LEADER_LINE.matches(lines.last()) &&
            lines.none { it.endsWith('。') || it.endsWith('！') || it.endsWith('？') }) {
            return true
        }
        return tocCount * 10 >= lines.size * 6
    }

    private fun isHeadingBlock(block: SourceBlock, trimmed: String): Boolean {
        if (block.heading) return true
        val lines = trimmed.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.isEmpty() || lines.size > 2) return false
        val len = nonWhitespaceLength(trimmed)
        if (len > 35) return false
        if (trimmed.last() in SENTENCE_ENDINGS) return false
        return HEADING_PATTERN.containsMatchIn(lines.first())
    }

    internal fun copyrightMarkerCount(trimmed: String): Int =
        COPYRIGHT_DISTINCT_PATTERNS.count { it.containsMatchIn(trimmed) }

    internal fun isCopyrightBlock(trimmed: String): Boolean {
        if (!COPYRIGHT_PATTERN.containsMatchIn(trimmed)) return false
        return nonWhitespaceLength(trimmed) <= 450 || copyrightMarkerCount(trimmed) >= 2
    }

    private fun isRunningHeaderFooter(
        trimmed: String,
        positionOnPage: Int,
        pageBlockCount: Int,
        repeatingEdgeKeys: Set<String>,
    ): Boolean {
        if (nonWhitespaceLength(trimmed) >= MIN_BODY_CHARS) return false
        if ('\n' !in trimmed && '\r' !in trimmed && RUNNING_HEADER_PATTERN.matches(trimmed)) return true
        if (pageBlockCount >= 2 && (positionOnPage == 0 || positionOnPage == pageBlockCount - 1)) {
            if (trimmed.last() !in SENTENCE_ENDINGS && edgeKey(trimmed) in repeatingEdgeKeys) return true
        }
        return false
    }

    private fun repeatingEdgeKeys(allBlocks: List<SourceBlock>): Set<String> {
        val pagesByKey = HashMap<String, MutableSet<Int>>()
        for ((page, pageBlocks) in allBlocks.groupBy { it.page }) {
            val visible = pageBlocks.filterNot { PRINTED_PAGE_MARK.matches(it.text.trim()) }
            if (visible.size < 2) continue
            for (edge in listOf(visible.first(), visible.last())) {
                val trimmed = edge.text.trim()
                if (trimmed.isEmpty() || nonWhitespaceLength(trimmed) >= MIN_BODY_CHARS) continue
                if (isPageNumber(trimmed) || trimmed.last() in SENTENCE_ENDINGS) continue
                val key = edgeKey(trimmed)
                if (key.length >= 2) pagesByKey.getOrPut(key) { HashSet() }.add(page)
            }
        }
        return pagesByKey.filterValues { it.size >= 2 }.keys
    }

    private fun edgeKey(trimmed: String): String =
        trimmed.replace(Regex("""[\d\s\-–—·•|｜]+"""), "").lowercase()
}
