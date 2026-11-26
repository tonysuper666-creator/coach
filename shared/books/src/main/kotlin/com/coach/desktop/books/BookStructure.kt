package com.coach.desktop.books

import com.coach.domain.LedgerJson
import kotlinx.serialization.json.*
import org.w3c.dom.Element
import java.nio.file.Path
import java.sql.Connection

/** 结构来源按可信度由高到低排列。 */
internal enum class HeadingSource {
    MANUAL,
    PDF_OUTLINE,
    EPUB_TOC,
    WORD_STYLE,
    TOC_PAGE,
    HEADING_RULE,
    TYPOGRAPHY,
    MODEL_HAIKU,
    FALLBACK_LENGTH,
}

internal data class ModelSectionBoundary(
    val startBlockId: String,
    val title: String,
    val page: Int = 0,
)

internal sealed interface ModelSectionResult {
    data class Done(val boundaries: List<ModelSectionBoundary>) : ModelSectionResult
    data class Failed(val message: String) : ModelSectionResult
    data object Deferred : ModelSectionResult
}

internal fun interface BookStructureProvider {
    suspend fun sectionUnstructuredBook(book: Textbook, candidates: List<SourceBlock>, bookPages: Int): ModelSectionResult
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
    val startOffset: Int = 0,
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
    val modelAttempted: Boolean = false,
    val tocNodes: List<BookTocNode> = emptyList(),
    val studyChunks: List<BookStudyChunk> = emptyList(),
    val confidence: Double = 1.0,
    val nativeBodyStart: BookPageAnchor? = null,
    val correctedStart: Int? = null,
    val editRevision: String? = null,
) {
    /** 无结构或低置信度长书尚未尝试过模型分节时为 true；一旦尝试或回退后按书缓存不再重复调用。 */
    val needsModelSectioning: Boolean
        get() = !modelAttempted &&
            (source == HeadingSource.FALLBACK_LENGTH.name || confidence < 0.85) &&
            resolvedLessons.sumOf { ExplainClassifier.nonWhitespaceLength(it.text) } > BookStructurePlanner.TARGET_MIN_CHARS
    /** 将缓存的节结构映射为当前书页的 [BookLesson] 列表，供阅读模式和「继续学」共用。 */
    fun toLessons(
        bookId: String,
        pages: List<BookPage>,
        precomputedBlocks: List<SourceBlock>? = null,
    ): List<BookLesson> {
        val blocks = precomputedBlocks ?: StudyCoachCourse.blocks(bookId, pages, snapSentences = true)
        val byId = HashMap<String, SourceBlock>(blocks.size * 2)
        for (block in blocks) byId[block.id] = block
        val firstBodyAnchor = correctedStart?.let { BookPageAnchor(it) } ?: nativeBodyStart
            ?: BookBoundaries.openingNode(tocNodes)?.let { open ->
                val hasPrecedingFrontMatter = tocNodes.any { node ->
                    node.kind == TocNodeKind.FRONT_MATTER &&
                        (node.startPage < open.startPage || (node.startPage == open.startPage && node.startOffset < open.startOffset))
                }
                val pageText = pages.firstOrNull { it.number == open.startPage }?.text.orEmpty()
                val prefix = if (open.startOffset in 1..pageText.length) pageText.substring(0, open.startOffset) else ""
                val hasLeadingBoilerplate = prefix.isNotEmpty() && (
                    ExplainClassifier.copyrightMarkerCount(prefix) >= 1 ||
                        BookBoundaries.snapToCleanBodyOffset(pageText, 0, skipGutenbergHeader = true) > 0
                    )
                if (hasPrecedingFrontMatter || hasLeadingBoilerplate) BookPageAnchor(open.startPage, open.startOffset) else null
            }
        val pageTextByNum = if (firstBodyAnchor != null && firstBodyAnchor.offsetUtf16 > 0) {
            pages.associate { it.number to it.text }
        } else emptyMap()
        return sections.mapNotNull { section ->
            val resolved = section.blockIds.mapNotNull { bid ->
                val rawBlock = byId[bid] ?: return@mapNotNull null
                val block = if (firstBodyAnchor != null && rawBlock.page == firstBodyAnchor.importPage && firstBodyAnchor.offsetUtf16 > 0) {
                    if (rawBlock.end <= firstBodyAnchor.offsetUtf16) return@mapNotNull null
                    if (rawBlock.start < firstBodyAnchor.offsetUtf16) {
                        val pageText = pageTextByNum[rawBlock.page]
                        val sliceStart = firstBodyAnchor.offsetUtf16
                        if (pageText != null && sliceStart < rawBlock.end && rawBlock.end <= pageText.length) {
                            val sliced = pageText.substring(sliceStart, rawBlock.end)
                            if (sliced.isBlank()) return@mapNotNull null
                            rawBlock.copy(start = sliceStart, text = sliced)
                        } else rawBlock
                    } else rawBlock
                } else rawBlock
                if (!block.heading && block.text.length <= 150 && '\n' !in block.text && '\r' !in block.text &&
                    BookStructurePlanner.matchHeadingRule(block.text.trim(), false) != null
                ) {
                    block.copy(heading = true)
                } else block
            }
            if (resolved.any { !it.heading && it.text.isNotBlank() }) {
                BookLesson(section.id, section.title, resolved, section.breadcrumb)
            } else null
        }
    }

    @Volatile private var lessonsResolver: (() -> List<BookLesson>)? = null
    @Volatile private var cachedLessons: List<BookLesson> = emptyList()

    /** 根据当前书页解析所有节；供 [Textbook.lessons] 使用。 */
    internal var resolvedLessons: List<BookLesson>
        get() {
            val resolver = lessonsResolver ?: return cachedLessons
            return synchronized(this) {
                val active = lessonsResolver ?: return@synchronized cachedLessons
                active().also {
                    cachedLessons = it
                    lessonsResolver = null
                }
            }
        }
        set(value) {
            synchronized(this) {
                lessonsResolver = null
                cachedLessons = value
            }
        }

    internal fun deferResolvedLessons(resolver: () -> List<BookLesson>) {
        synchronized(this) {
            lessonsResolver = resolver
        }
    }

    fun toLessons(): List<BookLesson> = resolvedLessons

    fun pageRole(page: Int): BookPageRole {
        val detected = pageRoles[page] ?: BookPageRole.BODY
        val start = correctedStart ?: return detected
        return when {
            page < start && detected == BookPageRole.BODY -> BookPageRole.FRONT_MATTER
            page == start || page > start && detected == BookPageRole.FRONT_MATTER -> BookPageRole.BODY
            else -> detected
        }
    }

    fun sectionIndexForPage(page: Int): Int =
        resolvedLessons.indexOfFirst { page in it.firstPage..it.lastPage }

    companion object {
        const val CURRENT_VERSION = 2
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

    private fun isPotentialHeadingStart(c: Char): Boolean =
        c == '#' || c == '第' || c == '（' || c == '(' || c == '§' || c in '0'..'9' ||
            c == 'P' || c == 'p' || c == 'C' || c == 'c' || c == 'U' || c == 'u' ||
            c == 'S' || c == 's' || c == 'L' || c == 'l' ||
            c in "一二三四五六七八九十百"

    /** 判断单行文本是否命中扩充标题规则并返回层级和清洗后的标题。 */
    fun matchHeadingRule(line: String, alreadyHeading: Boolean = false): HeadingMatch? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || '\n' in trimmed || '\r' in trimmed) return null
        if (!alreadyHeading && !isPotentialHeadingStart(trimmed[0])) return null
        if (!alreadyHeading && BookTocWaterfall.isRejectedHeadingCandidate(trimmed)) return null
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

    /** 识别整本书每一页的角色（O(N)：正文 / 目录 / 版权 / 前言 / 索引 / 参考文献 / 空白）。 */
    fun classifyPages(allBlocks: List<SourceBlock>, pages: List<BookPage>): Map<Int, BookPageRole> =
        BookTocWaterfall.classifyPagesFast(allBlocks, pages, ExplainClassifier.precomputeRepeatingEdges(allBlocks))

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
     * 执行完整分节：返回可持久化的 [BookStructure]（已填充 [BookStructure.tocNodes]、[BookStructure.studyChunks] 与 [BookStructure.resolvedLessons]）。
     */
    fun plan(
        bookId: String,
        title: String,
        format: String,
        pages: List<BookPage>,
        externalOutline: List<BookOutlineItem> = emptyList(),
        typographyLines: Map<Int, List<PageLineTypography>> = emptyMap(),
        nativeBodyStart: BookPageAnchor? = null,
    ): BookStructure {
        val outcome = BookTocWaterfall.plan(bookId, title, format, pages, externalOutline, typographyLines, nativeBodyStart)
        return BookStructure(
            version = BookStructure.CURRENT_VERSION,
            pageRoles = outcome.pageRoles,
            sections = outcome.sections,
            source = outcome.source,
            modelAttempted = false,
            tocNodes = outcome.tocNodes,
            studyChunks = outcome.studyChunks,
            confidence = outcome.confidence,
            nativeBodyStart = nativeBodyStart,
        ).also { it.resolvedLessons = it.toLessons(bookId, pages, outcome.allBlocks) }
    }

    /** 仅提取每页前几行与候选标题（保持原块 id 与 exact prefix 偏移，供无结构书单次分节使用）。 */
    fun extractUnstructuredCandidates(
        bookId: String,
        pages: List<BookPage>,
        pageRoles: Map<Int, BookPageRole> = emptyMap(),
    ): List<SourceBlock> {
        val allBlocks = StudyCoachCourse.blocks(bookId, pages, snapSentences = true)
        val roles = pageRoles.ifEmpty { classifyPages(allBlocks, pages) }
        val bodyPages = roles.filterValues { it == BookPageRole.BODY }.keys
        val activePages = if (bodyPages.isNotEmpty()) bodyPages
            else roles.filterValues { it != BookPageRole.BLANK && it != BookPageRole.TOC && it != BookPageRole.COPYRIGHT }.keys
                .ifEmpty { pages.map { it.number }.toSet() }
        val activeBlocks = allBlocks.filter { block ->
            if (block.page !in activePages) return@filter false
            val trimmed = block.text.trim()
            if (trimmed.isEmpty() || PRINTED_PAGE_MARK.matches(trimmed)) return@filter false
            val kind = ExplainClassifier.classifyBlock(block)
            kind != ExplainBlockKind.PAGE_NUMBER && kind != ExplainBlockKind.HEADER_FOOTER
        }
        if (activeBlocks.isEmpty()) return emptyList()
        val byPage = activeBlocks.groupBy { it.page }
        val fewPages = byPage.size <= 4
        fun isCandidateHeading(block: SourceBlock): Boolean {
            val trimmed = block.text.trim()
            if (trimmed.isEmpty()) return false
            val len = ExplainClassifier.nonWhitespaceLength(trimmed)
            if (len in 2..60 && trimmed.last() !in SENTENCE_ENDS) return true
            val firstLine = trimmed.lineSequence().firstOrNull()?.trim().orEmpty()
            return '\n' in trimmed && firstLine.length in 2..36 && firstLine.last() !in SENTENCE_ENDS
        }
        val primaryIds = LinkedHashSet<String>()
        val secondaryIds = LinkedHashSet<String>()
        for (pageNo in byPage.keys.sorted()) {
            val pageBlocks = byPage[pageNo].orEmpty()
            pageBlocks.firstOrNull()?.let { primaryIds += it.id }
            pageBlocks.drop(1).firstOrNull()?.let { secondaryIds += it.id }
            for (b in pageBlocks) {
                if (isCandidateHeading(b)) primaryIds += b.id
                else if (fewPages) secondaryIds += b.id
            }
        }
        val selected = buildList {
            val combinedIds = primaryIds + secondaryIds
            val chosen = if (combinedIds.size <= 128) {
                activeBlocks.filter { it.id in combinedIds }
            } else if (primaryIds.size <= 128) {
                val room = 128 - primaryIds.size
                val extra = activeBlocks.filter { it.id in secondaryIds && it.id !in primaryIds }.take(room).map { it.id }.toSet()
                activeBlocks.filter { it.id in primaryIds || it.id in extra }
            } else {
                val primaries = activeBlocks.filter { it.id in primaryIds }
                val step = maxOf(1, (primaries.size + 127) / 128)
                primaries.filterIndexed { i, _ -> i % step == 0 }.take(128)
            }
            var totalChars = 0
            val perBlockMax = if (fewPages) 120 else 140
            for (b in chosen) {
                val snippet = previewSnippet(b, maxLines = if (fewPages) 1 else 2, maxChars = perBlockMax)
                if (snippet.text.isBlank()) continue
                if (totalChars + snippet.text.length > 20_000) break
                totalChars += snippet.text.length
                add(snippet)
            }
        }
        return selected
    }

    private fun previewSnippet(block: SourceBlock, maxLines: Int = 2, maxChars: Int = 140): SourceBlock {
        val raw = block.text
        var lines = 0
        var end = 0
        while (end < raw.length && end < maxChars) {
            if (raw[end] == '\n') {
                lines++
                if (lines >= maxLines) break
            }
            end++
        }
        if (end < raw.length && end > 0 && raw[end - 1].isHighSurrogate()) end--
        var snippet = raw.substring(0, end)
        if (snippet.isBlank()) {
            val fallbackEnd = minOf(raw.length, maxChars).let { e ->
                if (e < raw.length && e > 0 && raw[e - 1].isHighSurrogate()) e - 1 else e
            }
            snippet = raw.substring(0, fallbackEnd)
        }
        return block.copy(end = block.start + snippet.length, heading = false, text = snippet)
    }

    /** 校验模型返回的无结构分节边界：必须存在于当前书块中且顺序严格单调递增；失败退回段落边界切分。 */
    fun applyModelBoundaries(
        bookId: String,
        title: String,
        format: String,
        pages: List<BookPage>,
        boundaries: List<ModelSectionBoundary>,
        fallback: BookStructure? = null,
    ): BookStructure {
        val baseFallback = fallback ?: plan(bookId, title, format, pages)
        if (boundaries.isEmpty()) return baseFallback.copy(modelAttempted = true).also { it.resolvedLessons = baseFallback.resolvedLessons }
        val allBlocks = StudyCoachCourse.blocks(bookId, pages, snapSentences = true)
        val pageRoles = baseFallback.pageRoles.ifEmpty { classifyPages(allBlocks, pages) }
        val bodyPages = pageRoles.filterValues { it == BookPageRole.BODY }.keys
        val activePages = if (bodyPages.isNotEmpty()) bodyPages
            else pageRoles.filterValues { it != BookPageRole.BLANK && it != BookPageRole.TOC && it != BookPageRole.COPYRIGHT }.keys
                .ifEmpty { pages.map { it.number }.toSet() }
        val activeBlocks = allBlocks.filter { block ->
            if (block.page !in activePages) return@filter false
            val trimmed = block.text.trim()
            if (trimmed.isEmpty() || PRINTED_PAGE_MARK.matches(trimmed)) return@filter false
            val kind = ExplainClassifier.classifyBlock(block)
            kind != ExplainBlockKind.PAGE_NUMBER && kind != ExplainBlockKind.HEADER_FOOTER
        }
        val blockIndex = activeBlocks.withIndex().associate { it.value.id to it.index }
        var prevIdx = -1
        val resolvedIndices = mutableListOf<Pair<Int, String>>()
        for (b in boundaries) {
            val idx = blockIndex[b.startBlockId]
            val cleanTitle = b.title.trim()
            if (idx == null || idx <= prevIdx || cleanTitle.isEmpty()) {
                return baseFallback.copy(modelAttempted = true).also { it.resolvedLessons = baseFallback.resolvedLessons }
            }
            prevIdx = idx
            resolvedIndices += idx to cleanTitle
        }
        val ranges = mutableListOf<Triple<Int, Int, String>>()
        val firstStart = resolvedIndices.first().first
        if (firstStart > 0 && activeBlocks.subList(0, firstStart).any { !it.heading && it.text.isNotBlank() }) {
            ranges += Triple(0, firstStart, title)
        }
        for (i in resolvedIndices.indices) {
            val start = resolvedIndices[i].first
            val end = if (i + 1 < resolvedIndices.size) resolvedIndices[i + 1].first else activeBlocks.size
            val slice = activeBlocks.subList(start, end)
            if (slice.any { !it.heading && it.text.isNotBlank() }) {
                ranges += Triple(start, end, resolvedIndices[i].second)
            }
        }
        if (ranges.isEmpty()) {
            return baseFallback.copy(modelAttempted = true).also { it.resolvedLessons = baseFallback.resolvedLessons }
        }
        val tocNodes = mutableListOf<BookTocNode>()
        val studyChunks = mutableListOf<BookStudyChunk>()
        val finalSections = mutableListOf<BookSection>()
        var chunkOrdinal = 0
        ranges.forEachIndexed { nodeOrdinal, (start, end, secTitle) ->
            val slice = activeBlocks.subList(start, end)
            val nodeId = "model-${slice.first().id}"
            tocNodes += BookTocNode(
                id = nodeId,
                parentId = null,
                ordinal = nodeOrdinal,
                level = 1,
                kind = TocNodeKind.CHAPTER,
                numberLabel = null,
                title = secTitle,
                startPage = slice.first().page,
                endPage = slice.last().page,
                printedPage = null,
                source = HeadingSource.MODEL_HAIKU,
                confidence = 0.90,
            )
            val chunks = splitOversized(slice)
            chunks.forEachIndexed { idx, chunkBlocks ->
                val chunkTitle = if (chunks.size == 1) secTitle else "$secTitle（${idx + 1}/${chunks.size}）"
                val breadcrumb = if (chunkTitle == title) title else "$title › $chunkTitle"
                val id = chunkBlocks.first().id
                val cStart = chunkBlocks.first().page
                val cEnd = chunkBlocks.last().page
                val bIds = chunkBlocks.map { it.id }
                studyChunks += BookStudyChunk(
                    id = id,
                    tocNodeId = nodeId,
                    ordinal = chunkOrdinal++,
                    title = chunkTitle,
                    breadcrumb = breadcrumb,
                    level = 1,
                    startPage = cStart,
                    endPage = cEnd,
                    charCount = bodyChars(chunkBlocks),
                    blockIds = bIds,
                )
                finalSections += BookSection(
                    id = id,
                    title = chunkTitle,
                    breadcrumb = breadcrumb,
                    level = 1,
                    startPage = cStart,
                    endPage = cEnd,
                    blockIds = bIds,
                )
            }
        }
        return BookStructure(
            version = BookStructure.CURRENT_VERSION,
            pageRoles = pageRoles,
            sections = finalSections,
            tocNodes = tocNodes,
            studyChunks = studyChunks,
            source = HeadingSource.MODEL_HAIKU.name,
            confidence = 0.90,
            modelAttempted = true,
        ).also { it.resolvedLessons = it.toLessons(bookId, pages, allBlocks) }
    }

    internal fun bodyCharsPublic(blocks: List<SourceBlock>): Int = bodyChars(blocks)

    internal fun splitOversizedPublic(blocks: List<SourceBlock>): List<List<SourceBlock>> = splitOversized(blocks)

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
    fun extractEpubOutline(path: Path, pages: List<BookPage>): List<BookOutlineItem> =
        TextbookExtraction.epubNavigation(path, pages)?.outline.orEmpty()

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

/** 书的结构与每节导读/前情提要/难点标注/节末小结的本地 SQLite 缓存。 */
internal data class CachedHardPoint(
    val blockId: String,
    val anchor: String,
    val page: Int,
    val label: String,
    val explanation: String,
)

internal data class CachedSectionGuide(
    val brief: List<String>,
    val text: String,
    val citations: List<CachedCitation>,
    val recap: String,
    val hardPoints: List<CachedHardPoint> = emptyList(),
    val summary: List<String> = emptyList(),
    val version: Int = CURRENT_VERSION,
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

internal data class CachedCitation(val anchor: String, val blockId: String, val page: Int)

internal object BookStructureStore {
    fun migrate(db: Connection) {
        db.createStatement().use { sql ->
            sql.execute("CREATE TABLE IF NOT EXISTS book_structures(book TEXT PRIMARY KEY, version INTEGER NOT NULL, payload TEXT NOT NULL)")
            sql.execute("CREATE TABLE IF NOT EXISTS book_body_start(book TEXT PRIMARY KEY REFERENCES books(id) ON DELETE CASCADE, page INTEGER NOT NULL)")
            sql.execute("""CREATE TABLE IF NOT EXISTS book_section_cache(
                book TEXT NOT NULL, section_id TEXT NOT NULL, kind TEXT NOT NULL,
                payload TEXT NOT NULL, updated_at INTEGER NOT NULL,
                PRIMARY KEY(book, section_id, kind))""")
        }
    }

    fun saveStructure(db: Connection, bookId: String, structure: BookStructure) {
        val sectionsFromChunks = structure.studyChunks.isNotEmpty() &&
            BookSchemaMigration.tableExists(db, "book_study_chunks") &&
            structure.sections.size == structure.studyChunks.size &&
            structure.sections.indices.all { i ->
                val s = structure.sections[i]
                val c = structure.studyChunks[i]
                s.id == c.id && s.title == c.title && s.breadcrumb == c.breadcrumb &&
                    s.level == c.level && s.startPage == c.startPage && s.endPage == c.endPage &&
                    s.blockIds == c.blockIds
            }
        val json = buildJsonObject {
            put("version", structure.version)
            put("source", structure.source)
            structure.correctedStart?.let { put("correctedStart", it) }
            structure.editRevision?.let { put("editRevision", it) }
            put("confidence", structure.confidence)
            put("modelAttempted", structure.modelAttempted)
            structure.nativeBodyStart?.let {
                put("bodyPage", it.importPage)
                put("bodyOffset", it.offsetUtf16)
            }
            put("tocOffsets", buildJsonObject {
                structure.tocNodes.filter { it.startOffset > 0 }.forEach { put(it.id, it.startOffset) }
            })
            put("pageRoles", buildJsonObject {
                structure.pageRoles.forEach { (p, role) -> put(p.toString(), role.name) }
            })
            if (sectionsFromChunks) {
                put("sectionsFromChunks", true)
                put("sections", buildJsonArray {})
            } else {
                put("sections", buildJsonArray {
                    for (s in structure.sections) add(buildJsonObject {
                        put("id", s.id); put("title", s.title); put("breadcrumb", s.breadcrumb)
                        put("level", s.level); put("startPage", s.startPage); put("endPage", s.endPage)
                        put("blockIds", buildJsonArray { s.blockIds.forEach { add(it) } })
                    })
                })
            }
        }
        db.prepareStatement("INSERT OR REPLACE INTO book_structures(book, version, payload) VALUES(?,?,?)").use { sql ->
            sql.setString(1, bookId)
            sql.setInt(2, structure.version)
            sql.setString(3, json.toString())
            sql.executeUpdate()
        }
        BookTocStore.saveInTransaction(db, bookId, structure.tocNodes, structure.studyChunks)
    }

    fun loadStructure(
        db: Connection,
        bookId: String,
        pages: List<BookPage>,
        sharedBlocks: (() -> List<SourceBlock>)? = null,
    ): BookStructure? =
        db.prepareStatement("SELECT payload FROM book_structures WHERE book=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { row ->
                if (!row.next()) null
                else runCatching {
                    val obj = LedgerJson.parseToJsonElement(row.getString(1)).jsonObject
                    val version = obj["version"]?.jsonPrimitive?.intOrNull ?: BookStructure.CURRENT_VERSION
                    val source = obj["source"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val confidence = obj["confidence"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 1.0
                    val modelAttempted = obj["modelAttempted"]?.jsonPrimitive?.booleanOrNull ?: false
                    val roleByName = BookPageRole.entries.associateBy { it.name }
                    val rawRoles = obj["pageRoles"]?.jsonObject.orEmpty()
                    val roles = LinkedHashMap<Int, BookPageRole>(rawRoles.size * 2)
                    for ((k, v) in rawRoles) {
                        val p = k.toIntOrNull() ?: continue
                        val r = roleByName[v.jsonPrimitive.contentOrNull] ?: continue
                        roles[p] = r
                    }
                    val tocOffsets = obj["tocOffsets"]?.jsonObject.orEmpty()
                    val tocNodes = BookTocStore.loadTocNodes(db, bookId).map { node ->
                        val offset = tocOffsets[node.id]?.jsonPrimitive?.intOrNull ?: 0
                        node.copy(startOffset = offset)
                    }
                    val studyChunks = BookTocStore.loadStudyChunks(db, bookId)
                    val sectionsFromChunks = obj["sectionsFromChunks"]?.jsonPrimitive?.booleanOrNull == true
                    val rawSections = obj["sections"]?.jsonArray
                    val sections = if ((sectionsFromChunks || rawSections.isNullOrEmpty()) && studyChunks.isNotEmpty()) {
                        studyChunks.map { c ->
                            BookSection(c.id, c.title, c.breadcrumb, c.level, c.startPage, c.endPage, c.blockIds)
                        }
                    } else {
                        val sectionEls = rawSections.orEmpty()
                        ArrayList<BookSection>(sectionEls.size).apply {
                            for (el in sectionEls) {
                                val s = el.jsonObject
                                val idsArr = s.getValue("blockIds").jsonArray
                                val blockIds = ArrayList<String>(idsArr.size)
                                for (idEl in idsArr) blockIds += idEl.jsonPrimitive.content
                                add(
                                    BookSection(
                                        id = s.getValue("id").jsonPrimitive.content,
                                        title = s.getValue("title").jsonPrimitive.content,
                                        breadcrumb = s.getValue("breadcrumb").jsonPrimitive.content,
                                        level = s.getValue("level").jsonPrimitive.int,
                                        startPage = s.getValue("startPage").jsonPrimitive.int,
                                        endPage = s.getValue("endPage").jsonPrimitive.int,
                                        blockIds = blockIds,
                                    ),
                                )
                            }
                        }
                    }
                    BookStructure(
                        version = version,
                        correctedStart = obj["correctedStart"]?.jsonPrimitive?.intOrNull,
                        editRevision = obj["editRevision"]?.jsonPrimitive?.contentOrNull,
                        pageRoles = roles,
                        sections = sections,
                        source = source,
                        modelAttempted = modelAttempted,
                        tocNodes = tocNodes,
                        studyChunks = studyChunks,
                        confidence = confidence,
                        nativeBodyStart = obj["bodyPage"]?.jsonPrimitive?.intOrNull?.let {
                            BookPageAnchor(it, obj["bodyOffset"]?.jsonPrimitive?.intOrNull ?: 0)
                        },
                    ).also {
                        it.deferResolvedLessons { it.toLessons(bookId, pages, sharedBlocks?.invoke()) }
                    }
                }.getOrNull()
            }
        }

    fun saveGuide(db: Connection, bookId: String, sectionId: String, guide: CachedSectionGuide) {
        val json = buildJsonObject {
            put("version", guide.version)
            put("brief", buildJsonArray { guide.brief.forEach { add(it) } })
            put("text", guide.text)
            put("recap", guide.recap)
            put("summary", buildJsonArray { guide.summary.forEach { add(it) } })
            put("hardPoints", buildJsonArray {
                guide.hardPoints.forEach { hp ->
                    add(buildJsonObject {
                        put("blockId", hp.blockId)
                        put("anchor", hp.anchor)
                        put("page", hp.page)
                        put("label", hp.label)
                        put("explanation", hp.explanation)
                    })
                }
            })
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
                    val version = obj["version"]?.jsonPrimitive?.intOrNull ?: 1
                    val hardPointsEl = obj["hardPoints"] as? JsonArray
                    val summaryEl = obj["summary"] as? JsonArray
                    // Reject 1.1.1 cached guides lacking version/hardPoints/summary so both reader and panel regenerate once on upgrade.
                    if (version < CachedSectionGuide.CURRENT_VERSION || hardPointsEl == null || summaryEl == null) {
                        null
                    } else {
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
                            hardPoints = hardPointsEl.map { el ->
                                val hp = el.jsonObject
                                CachedHardPoint(
                                    blockId = hp.getValue("blockId").jsonPrimitive.content,
                                    anchor = hp["anchor"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    page = hp.getValue("page").jsonPrimitive.int,
                                    label = hp["label"]?.jsonPrimitive?.contentOrNull ?: "难点",
                                    explanation = hp.getValue("explanation").jsonPrimitive.content,
                                )
                            },
                            summary = summaryEl.map { it.jsonPrimitive.content },
                            version = version,
                        )
                    }
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
