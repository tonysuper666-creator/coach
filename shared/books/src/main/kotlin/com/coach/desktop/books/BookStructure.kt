package com.coach.desktop.books

import com.coach.domain.LedgerJson
import kotlinx.serialization.json.*
import org.w3c.dom.Element
import java.nio.file.Path
import java.sql.Connection
import java.util.zip.ZipFile

/** 结构来源按可信度由高到低排列。 */
internal enum class HeadingSource {
    PDF_OUTLINE,
    EPUB_TOC,
    WORD_STYLE,
    TOC_PAGE,
    HEADING_RULE,
    TYPOGRAPHY,
}

/** 整页在全书中的角色：只有 [BODY] 参与章节划分与讲解。 */
internal enum class BookPageRole(val notice: String?) {
    BODY(null),
    TOC("这一页是目录，翻到正文再讲。"),
    COPYRIGHT("这一页是版权信息，翻到正文再讲。"),
    FRONT_MATTER("这一页是前言或扉页，翻到正文再讲。"),
    INDEX("这一页是索引，翻到正文再讲。"),
    REFERENCES("这一页是参考文献，翻到正文再讲。"),
    BLANK("这一页没有可讲的正文。"),
}

internal data class BookOutlineItem(
    val title: String,
    val level: Int,
    val page: Int,
    val source: HeadingSource,
)

internal data class TocParsedEntry(
    val title: String,
    val printedPage: Int,
    val level: Int,
)

internal data class BookSection(
    val id: String,
    val title: String,
    val breadcrumb: String,
    val level: Int,
    val startPage: Int,
    val endPage: Int,
    val blockIds: List<String>,
)

internal data class BookStructure(
    val version: Int = CURRENT_VERSION,
    val pageRoles: Map<Int, BookPageRole>,
    val sections: List<BookSection>,
    val source: String,
) {
    /** 将缓存的节结构映射为当前书页的 [BookLesson] 列表，供阅读模式和「继续学」共用。 */
    fun toLessons(bookId: String, pages: List<BookPage>): List<BookLesson> {
        val byId = StudyCoachCourse.blocks(bookId, pages, snapSentences = true).associateBy { it.id }
        return sections.mapNotNull { section ->
            val resolved = section.blockIds.mapNotNull(byId::get).map { block ->
                if (!block.heading && BookStructurePlanner.matchHeadingRule(block.text.trim(), false) != null) {
                    block.copy(heading = true)
                } else block
            }
            if (resolved.any { !it.heading && it.text.isNotBlank() }) {
                BookLesson(section.id, section.title, resolved, section.breadcrumb)
            } else null
        }
    }

    /** 根据当前书页解析所有节；供 [Textbook.lessons] 使用。 */
    internal var resolvedLessons: List<BookLesson> = emptyList()

    fun toLessons(): List<BookLesson> = resolvedLessons

    fun pageRole(page: Int): BookPageRole = pageRoles[page] ?: BookPageRole.BODY

    fun sectionIndexForPage(page: Int): Int =
        resolvedLessons.indexOfFirst { page in it.firstPage..it.lastPage }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * 书的结构识别与按节划分（TON-1766 第一期）：
 * 1. 先按页识别目录页、版权页、前言页码、索引、参考文献、空白页，排除出讲解；
 * 2. 按可信度汇总结构标题：PDF 书签 > EPUB 目录 > Word 标题样式 > 书内目录页解析 > 扩充标题规则 > 字号/版式；
 * 3. 取最细一级小节（800–4000 字）：同章下不到 400 字的相邻小节合并；超 4000 字在下一级标题或段落边界切开，绝不切在句中。
 */
internal object BookStructurePlanner {
    const val MIN_SUBSECTION_CHARS = 400
    const val TARGET_MIN_CHARS = 800
    const val MAX_SECTION_CHARS = 4000

    private val INDEX_HEADING = Regex("""^(?:索\s*引|主题索引|人名索引|关键词索引|Index|Subject\s+Index)$""", RegexOption.IGNORE_CASE)
    private val INDEX_ENTRY = Regex("""^.{2,60}?[,，、]\s*(?:p\.?\s*)?\d{1,5}(?:\s*[-–—,，、]\s*\d{1,5})*\s*$""", RegexOption.IGNORE_CASE)
    private val REFERENCES_HEADING = Regex("""^(?:参\s*考\s*文\s*献|引用文献|References|Bibliography|Works\s+Cited)$""", RegexOption.IGNORE_CASE)
    private val REFERENCE_ENTRY = Regex("""^(?:\[\d{1,4}\]|\d{1,3}[.．]\s+.{10,}(?:\d{4}|出版社|Press|Journal|Vol\.|pp\.))""")
    private val FRONT_MATTER_HEADING = Regex("""^(?:前\s*言|序\s*言?|自\s*序|编\s*者\s*按|凡\s*例|致\s*谢|Preface|Foreword|Acknowledgments?)$""", RegexOption.IGNORE_CASE)
    private val ROMAN_ONLY_PAGE = Regex("""^(?:[-–—]\s*)?[ivxlcdm]+(?:\s*[-–—])?$""", RegexOption.IGNORE_CASE)

    // 扩充后的标题规则（带层级推断）
    private val MD_HEADING = Regex("""^(#{1,6})\s+(.+)$""")
    private val CN_CHAPTER = Regex("""^第[一二三四五六七八九十百千0-9]+[篇部编章](?:[\s：:·\-—]+.+|$)""")
    private val CN_SECTION = Regex("""^第[一二三四五六七八九十百千0-9]+[节讲回课单元](?:[\s：:·\-—]+.+|$)""")
    private val CN_NUM_L1 = Regex("""^[一二三四五六七八九十百]+[、.．]\s*\S.*$""")
    private val CN_NUM_L2 = Regex("""^[（(][一二三四五六七八九十0-9]+[）)]\s*\S.*$""")
    private val EN_CHAPTER = Regex("""^(?:Part|Chapter|Unit)\s+[0-9IVXLC]+(?:\b.*)?$""", RegexOption.IGNORE_CASE)
    private val EN_SECTION = Regex("""^(?:Section|Lesson)\s+[0-9]+(?:\.[0-9]+)*(?:\b.*)?$""", RegexOption.IGNORE_CASE)
    private val SECTION_SIGN = Regex("""^§\s*\d+(?:\.\d+)*\s*\S.*$""")
    private val MULTI_NUM = Regex("""^(\d+(?:\.\d+)+)\s+\S.*$""")
    private val SINGLE_NUM = Regex("""^\d{1,2}[.．、]\s*\S.*$""")
    private val SENTENCE_ENDS = setOf('。', '！', '？', '.', '!', '?', '；', ';', '，', ',')

    private val TOC_SPLIT_LEADER = Regex(
        """^(.{1,120}?)(?:[…⋯]{1,}|[.．·•\-–—_](?:\s*[.．·•\-–—_]){2,})\s*(?:[pP]\.?\s*)?(\d{1,5})\s*$""",
    )
    private val TOC_SPLIT_GAP = Regex(
        """^(.{2,80}?)(?:\t|\s{2,})(?:[pP]\.?\s*)?(\d{1,5})\s*$""",
    )
    private val TOC_SPLIT_SINGLE = Regex(
        """^(.{2,60}?)\s+(\d{1,4})\s*$""",
    )

    internal data class HeadingMatch(val level: Int, val title: String, val source: HeadingSource)

    /** 判断单行文本是否命中扩充标题规则并返回层级和清洗后的标题。 */
    fun matchHeadingRule(line: String, alreadyHeading: Boolean = false): HeadingMatch? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || '\n' in trimmed || '\r' in trimmed) return null
        if (ExplainClassifier.isPageNumber(trimmed) || ExplainClassifier.isTocLine(trimmed) || ExplainClassifier.isCopyrightBlock(trimmed)) return null

        MD_HEADING.matchEntire(trimmed)?.let { m ->
            val title = m.groupValues[2].trim()
            if (title.isNotEmpty()) {
                val inferred = inferTextHeadingLevel(title)
                val mdLevel = m.groupValues[1].length
                return HeadingMatch(inferred ?: mdLevel, title, HeadingSource.HEADING_RULE)
            }
        }
        val len = ExplainClassifier.nonWhitespaceLength(trimmed)
        if (len > 45) return null
        if (!alreadyHeading && trimmed.last() in SENTENCE_ENDS) return null

        val level = inferTextHeadingLevel(trimmed)
        if (level != null) return HeadingMatch(level, trimmed, HeadingSource.HEADING_RULE)
        if (alreadyHeading) return HeadingMatch(1, trimmed.trimStart('#').trim(), HeadingSource.HEADING_RULE)
        return null
    }

    internal fun inferTextHeadingLevel(trimmed: String): Int? = when {
        CN_CHAPTER.matches(trimmed) || EN_CHAPTER.matches(trimmed) -> 1
        CN_SECTION.matches(trimmed) || EN_SECTION.matches(trimmed) -> 2
        CN_NUM_L1.matches(trimmed) -> 1
        CN_NUM_L2.matches(trimmed) -> 2
        SECTION_SIGN.matches(trimmed) -> if ('.' in trimmed) 2 else 1
        MULTI_NUM.matches(trimmed) -> {
            val dots = MULTI_NUM.matchEntire(trimmed)!!.groupValues[1].count { it == '.' }
            (dots + 1).coerceIn(2, 4)
        }
        SINGLE_NUM.matches(trimmed) -> 3
        else -> null
    }

    /** 从目录页文本中解析「标题 + 印刷页码」条目。 */
    fun parseTocEntries(pageBlocks: List<SourceBlock>): List<TocParsedEntry> = buildList {
        for (block in pageBlocks) {
            for (rawLine in block.text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || ExplainClassifier.isTocHeading(line) || ExplainClassifier.isPageNumber(line)) continue
                val match = TOC_SPLIT_LEADER.matchEntire(line)
                    ?: TOC_SPLIT_GAP.matchEntire(line)
                    ?: TOC_SPLIT_SINGLE.matchEntire(line)?.takeIf {
                        val n = it.groupValues[2].toIntOrNull() ?: 0
                        n !in 1900..2099 && ExplainClassifier.isTocLine(line)
                    }
                    ?: continue
                val title = match.groupValues[1].trim().trimEnd('.', '·', '…', '⋯', '-', '—', ' ')
                val page = match.groupValues[2].toIntOrNull() ?: continue
                if (title.length >= 2 && page in 1..10_000) {
                    val level = inferTextHeadingLevel(title) ?: 1
                    add(TocParsedEntry(title, page, level))
                }
            }
        }
    }

    /** 识别整本书每一页的角色（正文 / 目录 / 版权 / 前言 / 索引 / 参考文献 / 空白）。 */
    fun classifyPages(allBlocks: List<SourceBlock>, pages: List<BookPage>): Map<Int, BookPageRole> {
        val result = LinkedHashMap<Int, BookPageRole>(pages.size)
        val totalPages = pages.size
        val byPage = allBlocks.groupBy { it.page }
        var inBackSection: BookPageRole? = null

        for ((idx, page) in pages.withIndex()) {
            val pageBlocks = byPage[page.number].orEmpty()
            val analysis = ExplainClassifier.analyzePage(allBlocks, page.number)
            val nonPage = pageBlocks.filter { analysis.blockKinds[it.id] != ExplainBlockKind.PAGE_NUMBER }
            val hasRomanNumber = pageBlocks.any {
                analysis.blockKinds[it.id] == ExplainBlockKind.PAGE_NUMBER && ROMAN_ONLY_PAGE.matches(it.text.trim())
            }
            val firstHeading = nonPage.firstOrNull()?.text?.trim().orEmpty()
            val hasIndexHeading = nonPage.any { INDEX_HEADING.matches(it.text.trim().trimStart('#').trim()) }
            val hasRefHeading = nonPage.any { REFERENCES_HEADING.matches(it.text.trim().trimStart('#').trim()) }
            val hasFrontHeading = nonPage.any { FRONT_MATTER_HEADING.matches(it.text.trim().trimStart('#').trim()) }

            val role = when {
                nonPage.isEmpty() -> BookPageRole.BLANK
                analysis.kind == ExplainPageKind.TOC -> BookPageRole.TOC
                analysis.kind == ExplainPageKind.COPYRIGHT -> BookPageRole.COPYRIGHT
                hasIndexHeading || isIndexPage(nonPage) -> { inBackSection = BookPageRole.INDEX; BookPageRole.INDEX }
                hasRefHeading || isReferencesPage(nonPage) -> { inBackSection = BookPageRole.REFERENCES; BookPageRole.REFERENCES }
                inBackSection != null && idx >= totalPages / 2 && !startsNewMainChapter(firstHeading) -> inBackSection
                hasFrontHeading && (hasRomanNumber || idx < minOf(6, totalPages / 3 + 1)) && analysis.bodyBlocks.size <= 3 -> {
                    BookPageRole.FRONT_MATTER
                }
                analysis.kind == ExplainPageKind.HEADINGS_ONLY && (hasRomanNumber || (idx == 0 && totalPages >= 3)) -> {
                    BookPageRole.FRONT_MATTER
                }
                analysis.kind == ExplainPageKind.BLANK -> BookPageRole.BLANK
                else -> {
                    inBackSection = null
                    BookPageRole.BODY
                }
            }
            result[page.number] = role
        }
        return result
    }

    private fun isIndexPage(blocks: List<SourceBlock>): Boolean {
        val lines = blocks.flatMap { it.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList() }
        if (lines.size < 4) return false
        val hits = lines.count { INDEX_ENTRY.matches(it) }
        return hits >= 4 && hits * 10 >= lines.size * 5
    }

    private fun isReferencesPage(blocks: List<SourceBlock>): Boolean {
        val lines = blocks.flatMap { it.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList() }
        if (lines.size < 3) return false
        val hits = lines.count { REFERENCE_ENTRY.containsMatchIn(it) }
        return hits >= 3 && hits * 10 >= lines.size * 5
    }

    private fun startsNewMainChapter(line: String): Boolean {
        val clean = line.trimStart('#').trim()
        return CN_CHAPTER.matches(clean) || EN_CHAPTER.matches(clean) || CN_NUM_L1.matches(clean)
    }

    /** 根据 PDF 文字几何框高度辅助发现大字号独立标题行。 */
    fun extractTypographyHeadings(pages: List<BookPage>): Map<Int, Set<String>> {
        val lineHeights = mutableListOf<Double>()
        val candidatesByPage = HashMap<Int, MutableList<Pair<String, Double>>>()
        for (page in pages) {
            val layout = page.textLayout ?: continue
            if (layout.boxes.isEmpty() || page.text.isBlank()) continue
            var offset = 0
            for (rawLine in page.text.split('\n')) {
                val end = offset + rawLine.length
                val trimmed = rawLine.trim()
                if (trimmed.isNotEmpty()) {
                    val heights = layout.boxes.filter { it.startOffset >= offset && it.endOffset <= end && it.bounds.height > 0 }
                        .map { it.bounds.height * layout.pageHeight }
                    if (heights.isNotEmpty()) {
                        val median = heights.sorted()[heights.size / 2]
                        lineHeights += median
                        val len = ExplainClassifier.nonWhitespaceLength(trimmed)
                        if (len in 2..40 && trimmed.last() !in SENTENCE_ENDS && !ExplainClassifier.isPageNumber(trimmed)) {
                            candidatesByPage.getOrPut(page.number) { mutableListOf() }.add(trimmed to median)
                        }
                    }
                }
                offset = end + 1
            }
        }
        if (lineHeights.size < 6) return emptyMap()
        val bodyMedian = lineHeights.sorted()[lineHeights.size / 2]
        if (bodyMedian <= 0.0) return emptyMap()
        val threshold = bodyMedian * 1.22
        return candidatesByPage.mapValues { (_, list) ->
            list.filter { it.second >= threshold }.map { it.first }.toSet()
        }.filterValues { it.isNotEmpty() }
    }

    /**
     * 执行完整分节：返回可持久化的 [BookStructure]（已填充 [BookStructure.resolvedLessons]）。
     */
    fun plan(
        bookId: String,
        title: String,
        format: String,
        pages: List<BookPage>,
        externalOutline: List<BookOutlineItem> = emptyList(),
    ): BookStructure {
        val allBlocks = StudyCoachCourse.blocks(bookId, pages, snapSentences = true)
        val pageRoles = classifyPages(allBlocks, pages)
        val bodyPageNumbers = pageRoles.filterValues { it == BookPageRole.BODY }.keys
        // 如果整本书没有任何页被判定为 BODY（例如极短自造样例或仅有标题），退回保留非空白页以免书无法学习。
        val activePages = if (bodyPageNumbers.isNotEmpty()) bodyPageNumbers
            else pageRoles.filterValues { it != BookPageRole.BLANK && it != BookPageRole.TOC && it != BookPageRole.COPYRIGHT }.keys
                .ifEmpty { pages.map { it.number }.toSet() }

        val byPage = allBlocks.groupBy { it.page }
        val tocEntries = pageRoles.filterValues { it == BookPageRole.TOC }.keys.sorted()
            .flatMap { p -> parseTocEntries(byPage[p].orEmpty()) }
        val tocOutline = resolveTocEntriesToPages(tocEntries, activePages, byPage)
        val typographyHeadings = extractTypographyHeadings(pages)

        val chosenOutline = when {
            externalOutline.size >= 2 -> externalOutline
            tocOutline.size >= 2 -> tocOutline
            externalOutline.isNotEmpty() -> externalOutline
            tocOutline.isNotEmpty() -> tocOutline
            else -> emptyList()
        }
        val primarySource = chosenOutline.firstOrNull()?.source?.name
            ?: if (format == "docx") HeadingSource.WORD_STYLE.name
            else if (typographyHeadings.isNotEmpty()) HeadingSource.TYPOGRAPHY.name
            else HeadingSource.HEADING_RULE.name

        // 1) 在正文页块流中标记出所有标题及其层级
        val outlineByPage = chosenOutline.groupBy { it.page }
        val activeBlocks = allBlocks.filter { it.page in activePages }

        data class RawSection(
            val title: String,
            val level: Int,
            val parentTitle: String?,
            val breadcrumb: String,
            val blocks: MutableList<SourceBlock>,
        )

        val stack = ArrayDeque<Pair<Int, String>>() // (level, title)
        val rawSections = mutableListOf<RawSection>()
        var current: RawSection? = null
        val consumedOutline = HashSet<Pair<Int, String>>()

        fun pushHeading(level: Int, headingTitle: String): RawSection {
            while (stack.isNotEmpty() && stack.last().first >= level) stack.removeLast()
            val parentTitle = stack.lastOrNull()?.second
            val path = buildList {
                add(title)
                stack.forEach { add(it.second) }
                add(headingTitle)
            }.distinct().joinToString(" › ")
            stack.addLast(level to headingTitle)
            val created = RawSection(headingTitle, level, parentTitle, path, mutableListOf())
            rawSections += created
            current = created
            return created
        }

        fun ensureSection(): RawSection = current ?: RawSection(title, 1, null, title, mutableListOf()).also {
            rawSections += it
            current = it
        }

        var lastPage = -1
        for (block in activeBlocks) {
            val trimmed = block.text.trim()
            if (trimmed.isEmpty() || PRINTED_PAGE_MARK.matches(trimmed)) continue
            val blockKind = ExplainClassifier.classifyBlock(block)
            if (blockKind == ExplainBlockKind.PAGE_NUMBER || blockKind == ExplainBlockKind.HEADER_FOOTER) continue

            // 如果翻到了有书签/目录指向的新页，且页首没有显式同名标题块，则按书签起节
            if (block.page != lastPage) {
                lastPage = block.page
                val pageItems = outlineByPage[block.page].orEmpty()
                for (item in pageItems) {
                    val key = item.page to normalizeTitle(item.title)
                    if (key in consumedOutline) continue
                    val appearsInPage = byPage[block.page].orEmpty().any { titlesMatch(it.text.trim(), item.title) }
                    if (!appearsInPage) {
                        consumedOutline += key
                        pushHeading(item.level, item.title)
                    }
                }
            }

            // 匹配当前块是否为标题：优先匹配外部大纲/目录页条目，再匹配扩充标题规则，再匹配 PDF 字号辅助标题
            val matchedOutline = outlineByPage[block.page].orEmpty().firstOrNull { item ->
                val key = item.page to normalizeTitle(item.title)
                key !in consumedOutline && titlesMatch(trimmed, item.title)
            }
            val matchedRule = if (matchedOutline == null) matchHeadingRule(trimmed, block.heading) else null
            val matchedTypo = if (matchedOutline == null && matchedRule == null &&
                trimmed in typographyHeadings[block.page].orEmpty() &&
                ExplainClassifier.nonWhitespaceLength(trimmed) <= 40) {
                HeadingMatch(2, trimmed, HeadingSource.TYPOGRAPHY)
            } else null

            when {
                matchedOutline != null -> {
                    consumedOutline += matchedOutline.page to normalizeTitle(matchedOutline.title)
                    val sec = pushHeading(matchedOutline.level, matchedOutline.title)
                    sec.blocks += if (block.heading) block else block.copy(heading = true)
                }
                matchedRule != null -> {
                    val sec = pushHeading(matchedRule.level, matchedRule.title)
                    sec.blocks += if (block.heading) block else block.copy(heading = true)
                }
                matchedTypo != null -> {
                    val sec = pushHeading(matchedTypo.level, matchedTypo.title)
                    sec.blocks += if (block.heading) block else block.copy(heading = true)
                }
                else -> {
                    ensureSection().blocks += block
                }
            }
        }

        // 丢弃完全没有正文的纯章壳（保留它对子节的 breadcrumb 影响，已经记录在子节的 parentTitle/breadcrumb 里）
        val nonEmpty = rawSections.filter { sec -> sec.blocks.any { !it.heading && it.text.isNotBlank() } }
        val base = nonEmpty.ifEmpty {
            val fallbackBlocks = activeBlocks.filter { it.isBodyParagraph && it.text.isNotBlank() }
            if (fallbackBlocks.isEmpty()) emptyList()
            else listOf(RawSection(title, 1, null, title, fallbackBlocks.toMutableList()))
        }

        // 2) 合并：同章下不到 400 字的相邻小节合并（顶层章不跨章合并）
        val merged = mutableListOf<RawSection>()
        for (sec in base) {
            val bodyLen = bodyChars(sec.blocks)
            val prev = merged.lastOrNull()
            val canMergeIntoPrev = prev != null &&
                prev.parentTitle != null &&
                prev.parentTitle == sec.parentTitle &&
                (bodyChars(prev.blocks) < MIN_SUBSECTION_CHARS || bodyLen < MIN_SUBSECTION_CHARS) &&
                bodyChars(prev.blocks) + bodyLen <= MAX_SECTION_CHARS
            if (canMergeIntoPrev && prev != null) {
                prev.blocks.addAll(sec.blocks)
                val combinedTitle = if (sec.title in prev.title) prev.title else "${prev.title} · ${sec.title}"
                val combinedBreadcrumb = prev.breadcrumb.substringBeforeLast(" › ", "")
                    .let { prefix -> if (prefix.isEmpty()) combinedTitle else "$prefix › $combinedTitle" }
                merged[merged.lastIndex] = prev.copy(title = combinedTitle, breadcrumb = combinedBreadcrumb)
            } else {
                merged += sec
            }
        }

        // 3) 切分：超过 4000 字的节按段落边界切开，绝不切在句子中间
        val finalSections = mutableListOf<BookSection>()
        val finalLessons = mutableListOf<BookLesson>()
        for (sec in merged) {
            val chunks = splitOversized(sec.blocks)
            chunks.forEachIndexed { idx, chunkBlocks ->
                val chunkTitle = if (chunks.size == 1) sec.title else "${sec.title}（${idx + 1}/${chunks.size}）"
                val chunkBreadcrumb = if (chunks.size == 1) sec.breadcrumb else "${sec.breadcrumb}（${idx + 1}/${chunks.size}）"
                val id = chunkBlocks.first().id
                finalSections += BookSection(
                    id = id,
                    title = chunkTitle,
                    breadcrumb = chunkBreadcrumb,
                    level = sec.level,
                    startPage = chunkBlocks.first().page,
                    endPage = chunkBlocks.last().page,
                    blockIds = chunkBlocks.map { it.id },
                )
                finalLessons += BookLesson(id, chunkTitle, chunkBlocks, chunkBreadcrumb)
            }
        }

        return BookStructure(
            version = BookStructure.CURRENT_VERSION,
            pageRoles = pageRoles,
            sections = finalSections,
            source = primarySource,
        ).also { it.resolvedLessons = finalLessons }
    }

    private fun bodyChars(blocks: List<SourceBlock>): Int =
        blocks.filterNot { it.heading }.sumOf { ExplainClassifier.nonWhitespaceLength(it.text) }

    /** 按段落边界切分超过 [MAX_SECTION_CHARS] 的长节，保证不落在句子中间。 */
    private fun splitOversized(blocks: List<SourceBlock>): List<List<SourceBlock>> {
        if (bodyChars(blocks) <= MAX_SECTION_CHARS) return listOf(blocks)
        val result = mutableListOf<List<SourceBlock>>()
        var current = mutableListOf<SourceBlock>()
        var currentChars = 0
        for (block in blocks) {
            val len = if (block.heading) 0 else ExplainClassifier.nonWhitespaceLength(block.text)
            if (current.any { !it.heading } && currentChars + len > MAX_SECTION_CHARS) {
                result += current.toList()
                current = mutableListOf()
                currentChars = 0
            }
            current += block
            currentChars += len
        }
        if (current.any { !it.heading }) result += current.toList()
        else if (current.isNotEmpty() && result.isNotEmpty()) {
            // 尾随孤立标题并回最后一段，避免产生空节
            result[result.lastIndex] = result.last() + current
        }
        // 若最后一段太短（< 400 字）且可以无痛并入倒数第二段则合并，减少碎尾
        if (result.size >= 2) {
            val last = result.last()
            val prev = result[result.size - 2]
            if (bodyChars(last) < MIN_SUBSECTION_CHARS && bodyChars(prev) + bodyChars(last) <= MAX_SECTION_CHARS + 400) {
                result[result.size - 2] = prev + last
                result.removeAt(result.lastIndex)
            }
        }
        return result
    }

    /** 将目录页解析出的印刷页码校准到实际 PDF 页码（按正文标题匹配求中位偏移）。 */
    private fun resolveTocEntriesToPages(
        entries: List<TocParsedEntry>,
        activePages: Set<Int>,
        byPage: Map<Int, List<SourceBlock>>,
    ): List<BookOutlineItem> {
        if (entries.isEmpty() || activePages.isEmpty()) return emptyList()
        val sortedActive = activePages.sorted()
        val offsets = mutableListOf<Int>()
        val directMatches = HashMap<Int, Int>() // entry index -> actual page
        for ((idx, entry) in entries.withIndex()) {
            val matchedPage = sortedActive.firstOrNull { p ->
                byPage[p].orEmpty().any { titlesMatch(it.text.trim(), entry.title) }
            }
            if (matchedPage != null) {
                directMatches[idx] = matchedPage
                offsets += matchedPage - entry.printedPage
            }
        }
        val medianOffset = if (offsets.isNotEmpty()) offsets.sorted()[offsets.size / 2] else 0
        val maxPage = sortedActive.last()
        val minPage = sortedActive.first()
        return entries.mapIndexedNotNull { idx, entry ->
            val targetPage = directMatches[idx] ?: (entry.printedPage + medianOffset)
            if (targetPage in minPage..maxPage) {
                BookOutlineItem(entry.title, entry.level, targetPage, HeadingSource.TOC_PAGE)
            } else null
        }
    }

    private fun normalizeTitle(text: String): String =
        text.trim().trimStart('#').replace(Regex("""[\s　·.．、:：\-–—]+"""), "").lowercase()

    private fun titlesMatch(blockText: String, targetTitle: String): Boolean {
        val firstLine = blockText.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.isEmpty() || ExplainClassifier.nonWhitespaceLength(firstLine) > 60) return false
        val a = normalizeTitle(firstLine)
        val b = normalizeTitle(targetTitle)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || (b.length >= 4 && a.startsWith(b) && a.length <= b.length + 6)
    }

    /** 从 EPUB（nav.xhtml 或 toc.ncx）提取真实目录大纲。 */
    fun extractEpubOutline(path: Path, pages: List<BookPage>): List<BookOutlineItem> = runCatching {
        ZipFile(path.toFile()).use { zip ->
            fun read(name: String): ByteArray? = zip.getEntry(name)?.let { e ->
                zip.getInputStream(e).use { it.readNBytes(2 * 1024 * 1024) }
            }
            fun Element.all(name: String): List<Element> =
                getElementsByTagNameNS("*", name).let { list -> (0 until list.length).map { list.item(it) as Element } }

            val container = read("META-INF/container.xml")?.let(EpubXml::parse) ?: return@use emptyList()
            val packagePath = container.all("rootfile").firstOrNull()?.getAttribute("full-path") ?: return@use emptyList()
            val opf = read(packagePath)?.let(EpubXml::parse) ?: return@use emptyList()
            val items = opf.all("item")
            val root = java.net.URI("/$packagePath")

            fun findPageForTitle(title: String): Int? =
                pages.firstOrNull { p -> p.text.lineSequence().any { titlesMatch(it.trim(), title) } }?.number

            // 1) EPUB3 nav.xhtml
            val navItem = items.firstOrNull { "nav" in it.getAttribute("properties").split(Regex("\\s+")) }
            if (navItem != null) {
                val member = root.resolve(navItem.getAttribute("href")).normalize().path.removePrefix("/")
                val doc = read(member)?.let(EpubXml::parse)
                if (doc != null) {
                    val result = mutableListOf<BookOutlineItem>()
                    fun walkOl(el: Element, level: Int) {
                        for (i in 0 until el.childNodes.length) {
                            val child = el.childNodes.item(i) as? Element ?: continue
                            when (child.localName?.lowercase()) {
                                "li" -> {
                                    val anchor = (0 until child.childNodes.length).mapNotNull { child.childNodes.item(it) as? Element }
                                        .firstOrNull { it.localName?.lowercase() in setOf("a", "span") }
                                    val label = anchor?.textContent?.trim().orEmpty()
                                    if (label.isNotEmpty()) {
                                        val page = findPageForTitle(label)
                                        if (page != null) result += BookOutlineItem(label, level.coerceIn(1, 6), page, HeadingSource.EPUB_TOC)
                                    }
                                    for (j in 0 until child.childNodes.length) {
                                        val sub = child.childNodes.item(j) as? Element ?: continue
                                        if (sub.localName?.lowercase() == "ol") walkOl(sub, level + 1)
                                    }
                                }
                                "ol", "nav" -> walkOl(child, level)
                            }
                        }
                    }
                    doc.all("nav").firstOrNull()?.let { walkOl(it, 1) }
                    if (result.isNotEmpty()) return@use result
                }
            }

            // 2) EPUB2 toc.ncx
            val ncxItem = items.firstOrNull { it.getAttribute("media-type") == "application/x-dtbncx+xml" || it.getAttribute("href").endsWith(".ncx", true) }
            if (ncxItem != null) {
                val member = root.resolve(ncxItem.getAttribute("href")).normalize().path.removePrefix("/")
                val doc = read(member)?.let(EpubXml::parse)
                if (doc != null) {
                    val result = mutableListOf<BookOutlineItem>()
                    fun walkNavPoint(el: Element, level: Int) {
                        val label = el.all("text").firstOrNull()?.textContent?.trim().orEmpty()
                        if (label.isNotEmpty()) {
                            val page = findPageForTitle(label)
                            if (page != null) result += BookOutlineItem(label, level.coerceIn(1, 6), page, HeadingSource.EPUB_TOC)
                        }
                        for (i in 0 until el.childNodes.length) {
                            val child = el.childNodes.item(i) as? Element ?: continue
                            if (child.localName?.lowercase() == "navpoint") walkNavPoint(child, level + 1)
                        }
                    }
                    val navMap = doc.all("navMap").firstOrNull()
                    if (navMap != null) {
                        for (i in 0 until navMap.childNodes.length) {
                            val child = navMap.childNodes.item(i) as? Element ?: continue
                            if (child.localName?.lowercase() == "navpoint") walkNavPoint(child, 1)
                        }
                    }
                    if (result.isNotEmpty()) return@use result
                }
            }
            emptyList()
        }
    }.getOrDefault(emptyList())

    /** 从 Word（.docx）样式表与正文提取带层级的标题大纲。 */
    fun extractWordOutline(path: Path, pages: List<BookPage>): List<BookOutlineItem> = runCatching {
        SafeBookArchive(path).use { zip ->
            if (!zip.contains("word/document.xml")) return@use emptyList()
            fun parseXml(name: String) = EpubXml.parse(zip.read(name))
            fun Element.all(name: String): List<Element> =
                getElementsByTagNameNS("*", name).let { list -> (0 until list.length).map { list.item(it) as Element } }

            val styleLevels = HashMap<String, Int>()
            if (zip.contains("word/styles.xml")) {
                for (style in parseXml("word/styles.xml").all("style")) {
                    val id = style.getAttribute("w:styleId").ifEmpty { style.getAttribute("styleId") }
                    val outlineLvl = style.all("outlineLvl").firstOrNull()?.let {
                        (it.getAttribute("w:val").ifEmpty { it.getAttribute("val") }).toIntOrNull()?.plus(1)
                    }
                    val byName = style.all("name").firstNotNullOfOrNull {
                        Regex("(?i)heading\\s*(\\d+)").matchEntire(it.getAttribute("w:val").ifEmpty { it.getAttribute("val") })
                            ?.groupValues?.get(1)?.toIntOrNull()
                    }
                    val lvl = outlineLvl ?: byName
                    if (id.isNotEmpty() && lvl != null) styleLevels[id] = lvl.coerceIn(1, 6)
                }
            }
            val result = mutableListOf<BookOutlineItem>()
            for (p in parseXml("word/document.xml").all("p")) {
                val styleId = p.all("pStyle").firstOrNull()?.let { it.getAttribute("w:val").ifEmpty { it.getAttribute("val") } }.orEmpty()
                val lvl = styleLevels[styleId] ?: Regex("(?i)heading([1-9])").matchEntire(styleId)?.groupValues?.get(1)?.toIntOrNull()
                if (lvl != null) {
                    val text = p.all("t").joinToString("") { it.textContent }.trim()
                    if (text.isNotEmpty()) {
                        val page = pages.firstOrNull { pg -> pg.text.lineSequence().any { titlesMatch(it.trim(), text) } }?.number
                        if (page != null) result += BookOutlineItem(text, lvl.coerceIn(1, 6), page, HeadingSource.WORD_STYLE)
                    }
                }
            }
            result
        }
    }.getOrDefault(emptyList())
}

/** 书的结构与每节导读/前情提要的本地 SQLite 缓存。 */
internal data class CachedSectionGuide(
    val brief: List<String>,
    val text: String,
    val citations: List<CachedCitation>,
    val recap: String,
)

internal data class CachedCitation(val anchor: String, val blockId: String, val page: Int)

internal object BookStructureStore {
    fun migrate(db: Connection) {
        db.createStatement().use { sql ->
            sql.execute("CREATE TABLE IF NOT EXISTS book_structures(book TEXT PRIMARY KEY, version INTEGER NOT NULL, payload TEXT NOT NULL)")
            sql.execute("""CREATE TABLE IF NOT EXISTS book_section_cache(
                book TEXT NOT NULL, section_id TEXT NOT NULL, kind TEXT NOT NULL,
                payload TEXT NOT NULL, updated_at INTEGER NOT NULL,
                PRIMARY KEY(book, section_id, kind))""")
        }
    }

    fun saveStructure(db: Connection, bookId: String, structure: BookStructure) {
        val json = buildJsonObject {
            put("version", structure.version)
            put("source", structure.source)
            put("pageRoles", buildJsonObject {
                structure.pageRoles.forEach { (p, role) -> put(p.toString(), role.name) }
            })
            put("sections", buildJsonArray {
                for (s in structure.sections) add(buildJsonObject {
                    put("id", s.id); put("title", s.title); put("breadcrumb", s.breadcrumb)
                    put("level", s.level); put("startPage", s.startPage); put("endPage", s.endPage)
                    put("blockIds", buildJsonArray { s.blockIds.forEach { add(it) } })
                })
            })
        }
        db.prepareStatement("INSERT OR REPLACE INTO book_structures(book, version, payload) VALUES(?,?,?)").use { sql ->
            sql.setString(1, bookId)
            sql.setInt(2, structure.version)
            sql.setString(3, json.toString())
            sql.executeUpdate()
        }
    }

    fun loadStructure(db: Connection, bookId: String, pages: List<BookPage>): BookStructure? =
        db.prepareStatement("SELECT payload FROM book_structures WHERE book=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { row ->
                if (!row.next()) null
                else runCatching {
                    val obj = LedgerJson.parseToJsonElement(row.getString(1)).jsonObject
                    val version = obj["version"]?.jsonPrimitive?.intOrNull ?: BookStructure.CURRENT_VERSION
                    val source = obj["source"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val roles = obj["pageRoles"]?.jsonObject.orEmpty().mapNotNull { (k, v) ->
                        val p = k.toIntOrNull() ?: return@mapNotNull null
                        val r = BookPageRole.entries.firstOrNull { it.name == v.jsonPrimitive.contentOrNull } ?: return@mapNotNull null
                        p to r
                    }.toMap()
                    val sections = obj["sections"]?.jsonArray.orEmpty().map { el ->
                        val s = el.jsonObject
                        BookSection(
                            id = s.getValue("id").jsonPrimitive.content,
                            title = s.getValue("title").jsonPrimitive.content,
                            breadcrumb = s.getValue("breadcrumb").jsonPrimitive.content,
                            level = s.getValue("level").jsonPrimitive.int,
                            startPage = s.getValue("startPage").jsonPrimitive.int,
                            endPage = s.getValue("endPage").jsonPrimitive.int,
                            blockIds = s.getValue("blockIds").jsonArray.map { it.jsonPrimitive.content },
                        )
                    }
                    BookStructure(version, roles, sections, source).also {
                        it.resolvedLessons = it.toLessons(bookId, pages)
                    }
                }.getOrNull()
            }
        }

    fun saveGuide(db: Connection, bookId: String, sectionId: String, guide: CachedSectionGuide) {
        val json = buildJsonObject {
            put("brief", buildJsonArray { guide.brief.forEach { add(it) } })
            put("text", guide.text)
            put("recap", guide.recap)
            put("citations", buildJsonArray {
                guide.citations.forEach { c ->
                    add(buildJsonObject { put("anchor", c.anchor); put("blockId", c.blockId); put("page", c.page) })
                }
            })
        }
        db.prepareStatement("INSERT OR REPLACE INTO book_section_cache(book, section_id, kind, payload, updated_at) VALUES(?,?,?,?,?)").use { sql ->
            sql.setString(1, bookId)
            sql.setString(2, sectionId)
            sql.setString(3, "guide")
            sql.setString(4, json.toString())
            sql.setLong(5, System.currentTimeMillis())
            sql.executeUpdate()
        }
    }

    fun loadGuide(db: Connection, bookId: String, sectionId: String): CachedSectionGuide? =
        db.prepareStatement("SELECT payload FROM book_section_cache WHERE book=? AND section_id=? AND kind='guide'").use { sql ->
            sql.setString(1, bookId)
            sql.setString(2, sectionId)
            sql.executeQuery().use { row ->
                if (!row.next()) null
                else runCatching {
                    val obj = LedgerJson.parseToJsonElement(row.getString(1)).jsonObject
                    CachedSectionGuide(
                        brief = obj["brief"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
                        text = obj.getValue("text").jsonPrimitive.content,
                        citations = obj["citations"]?.jsonArray.orEmpty().map { el ->
                            val c = el.jsonObject
                            CachedCitation(
                                c.getValue("anchor").jsonPrimitive.content,
                                c.getValue("blockId").jsonPrimitive.content,
                                c.getValue("page").jsonPrimitive.int,
                            )
                        },
                        recap = obj["recap"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }.getOrNull()
            }
        }

    fun clearGuides(db: Connection, bookId: String) {
        db.prepareStatement("DELETE FROM book_section_cache WHERE book=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeUpdate()
        }
    }
}
