package com.coach.desktop.books

/**
 * Canonical TOC node for human navigation (`book_toc_nodes`), completely decoupled from
 * LLM chunk splitting (`book_study_chunks`). Never holds artificial `(k/n)` splits.
 */
internal enum class TocNodeKind {
    PART,
    CHAPTER,
    SECTION,
    SUBSECTION,
    APPENDIX,
    FRONT_MATTER,
    BACK_MATTER,
}

internal data class BookTocNode(
    val id: String,
    val parentId: String?,
    val ordinal: Int,
    val level: Int,
    val kind: TocNodeKind,
    val numberLabel: String?,
    val title: String,
    val startPage: Int,
    val endPage: Int,
    val printedPage: String? = null,
    val source: HeadingSource,
    val confidence: Double = 1.0,
    val startOffset: Int = 0,
)

private val BookTocNode.usesSourceOffset: Boolean
    get() = source == HeadingSource.EPUB_TOC || source == HeadingSource.MANUAL || source == HeadingSource.TEXT_LAYOUT ||
        (startOffset > 0 && (source == HeadingSource.PDF_OUTLINE || source == HeadingSource.TYPOGRAPHY))

/**
 * AI/study chunk (`book_study_chunks`) mapped onto a canonical TOC leaf node.
 * Short sibling sections (<400 chars) may merge and >4000-char sections split cleanly at sentence boundaries.
 */
internal data class BookStudyChunk(
    val id: String,
    val tocNodeId: String?,
    val ordinal: Int,
    val title: String,
    val breadcrumb: String,
    val level: Int,
    val startPage: Int,
    val endPage: Int,
    val charCount: Int,
    val blockIds: List<String>,
)

/**
 * Four-stage TOC waterfall (Stage A Outline -> Stage B Extended TOC Pages -> Stage C Typography + Negative Filters
 * -> Stage D low-confidence signal for model fallback) and decoupled study-chunk builder.
 */
internal object BookTocWaterfall {
    private val FRONT_SCAFFOLD_NOISE = Regex(
        """^(?:Cover|Half\s+Title|Title\s*Page|Imprint|Copyright(?:\s+Page)?|Brief\s+Contents|Detailed\s+Contents|Contents|Table\s+of\s+Contents|Blank\s+Page|List\s+of\s+(?:Figures|Tables|Boxes)|目\s*录|总\s*目\s*录|简\s*目|版权页|封面)$""",
        RegexOption.IGNORE_CASE,
    )
    private val FRONT_MATTER_TITLE = Regex(
        """^(?:Preface|Foreword|About\s+the\s+Authors?|Authors?[’']?\s+Acknowledgments?|Publisher[’']?s?\s+Acknowledgments?|Acknowledg(?:e)?ments?|Suggested\s+Pathways.*|How\s+to\s+Use\s+This\s+Book|Introduction\s+to\s+the\s+Book|(?:《[^》]{1,30}》\s*)?(?:作者\s*|著者\s*|译者\s*|譯者\s*|编者\s*|編者\s*)?(?:前\s*言|序\s*言?|自\s*序|代\s*序|导\s*言|導\s*言|引\s*言|编\s*者\s*按|凡\s*例|致\s*谢))$""",
        RegexOption.IGNORE_CASE,
    )
    private val BACK_MATTER_TITLE = Regex(
        """^(?:Bibliography|References|Solutions|Answer\s+Key|Colophon|Uncopyright|Conflicts?\s+of\s+Interests?(?:\s+Statement)?|Works\s+Cited|(?:Author|Subject|Name|General)\s+Index|Index|Glossary|Endnotes|参考文献|引用文献|索\s*引|主题索引|人名索引|后\s*记|跋|[\p{IsHan}]{1,6}跋|經典釋文|[\p{IsHan}]{2,10}音義|注\s*釋|註\s*釋)$""",
        RegexOption.IGNORE_CASE,
    )
    private val APPENDIX_TITLE = Regex(
        """^(?:Appendix(?:\s+([A-Z0-9]+))?|附\s*录(?:\s*([A-Z0-9一二三四五六七八九十]+))?)(?:[:：\.\s\-–—]+(.*))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val PART_PREFIX = Regex(
        """^(?:Part\s+([IVXLC]+|\d+)(?:[:：\.\s\-–—]+(.*))?|([IVXLC]+)\s*[:：]\s*(.+)|第([一二三四五六七八九十百千0-9]+)[编篇部](?:[\s：:·\-—]+(.*))?|(?:.{1,18}?《[^》]{1,20}》\s*)?([上中下][篇卷]))$""",
        RegexOption.IGNORE_CASE,
    )
    private val BOOK_PREFIX = Regex("""(?i)^Book\s+([IVXLC]+|\d+)[.:]\s+.+$""")
    private val ROMAN_CHAPTER = Regex("""^([IVXLC]+)\s+([A-Z][A-Z\s’'—-]{5,})$""")
    private val FRONT_EXTRA = Regex("""(?i)^(?:Illustrations|List of Illustrations|Introductory Note|\d{4}|\p{L}+[ ]*copyright)[.:]?$""")
    private val LICENSE_TITLE = Regex("""(?i)^(?:The Full )?Project Gutenberg.*License.*$""")
    private val BARE_PART_ROMAN_WITH_TITLE = Regex(
        """^([IVXLC]{1,5})(?:\s{2,}|\s+[-–—:]\s*)(.{3,90})$""",
    )
    private val BARE_ROMAN_ONLY = Regex("""^[IVXLC]{1,5}$""")
    private val SUBSECTION_3 = Regex(
        """^(?:Section\s+|§\s*)?(\d{1,2}\.\d{1,2}\.\d{1,2})[.:：]?\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val SECTION_2 = Regex(
        """^(?:Section\s+|§\s*)?(\d{1,2}\.\d{1,2})[.:：]?\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val CHAPTER_EXPLICIT = Regex(
        """^(?:Chapter|Unit|Lesson)\s+(\d{1,2}|[IVXLC]+)(?:[:：\.\s\-–—]+(.*))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val CHAPTER_LEADING_NUM = Regex(
        """^(\d{1,2})[.:：]?\s+(.+)$""",
    )
    private val CN_CHAPTER_PREFIX = Regex("""^(第?[零〇一二三四五六七八九十百千0-9]+章)(?:[\s：:·\-—]*(.*))?$""")
    private val CN_EXPLICIT_CHAPTER = Regex("""^(第[零〇一二三四五六七八九十百千0-9]+章|[零〇一二三四五六七八九十百千]+章)$""")
    private val CN_COLLATION_SUBSECTION = Regex("""^(?:校勘記|校勘记)$""")
    private val CN_SECTION_PREFIX = Regex("""^(第[一二三四五六七八九十百千0-9]+[节讲课单元])(?:[\s：:·\-—]+(.*))?$""")
    private val CN_NUM_L1 = Regex("""^([一二三四五六七八九十百]+)[、.．]\s*(.+)$""")
    private val CN_NUM_L2 = Regex("""^([（(][一二三四五六七八九十0-9]+[）)])\s*(.+)$""")

    // Strict negative filters preventing false positives from cross-references, formulas, and addresses.
    private val CROSS_REF_PHRASE = Regex(
        """^(?:Chapter|Section|Table|Figure|Fig\.?|Appendix|Box|Equation|Eq\.?|Theorem|Lemma|Proposition|Definition)\s+\d+(?:\.\d+)*(?:\s*[.),:;]|\s+(?:for|shows?|presents?|discusses?|summarizes?|reports?|compares?|contains?|provides?|illustrates?|is|are|was|were|in|on|of|above|below|we|the|to|from)\b)""",
        RegexOption.IGNORE_CASE,
    )
    private val FORMULA_OR_METRIC_CHARS = Regex("""[=<>∑∫±÷×≤≥≈≠∞%$€£]""")
    private val STREET_OR_ORG_NUMBER = Regex(
        """^\d+\s+(?:Massachusetts|Memorial|Longwood|Huntington|Cambridge|Oxford|Street|Avenue|Ave|Road|Rd|Drive|Dr|Blvd|Boulevard|Lane|Ln|Square|Sq|Hall|Floor|Room|Suite)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val UNIT_STARTERS = setOf(
        "year", "years", "yr", "yrs", "day", "days", "month", "months", "week", "weeks", "hour", "hours",
        "minute", "minutes", "second", "seconds", "percent", "percentage", "mg", "kg", "g", "ml", "l",
        "mm", "cm", "km", "m", "ft", "in", "lb", "lbs", "million", "billion", "thousand", "hundred",
        "times", "fold", "per", "vs", "and", "or", "to", "of", "for", "from", "with", "at", "by", "on", "in",
    )
    private val SENTENCE_ENDINGS = setOf('。', '！', '？', '.', '!', '?', '；', ';', '，', ',')

    // TOC line matching (Stage B)
    private val TOC_TITLE_PAGE_LEADER = Regex(
        """^(.{2,140}?)(?:\s*[…⋯]{1,}|\s*(?:[.．·•\-–—_]\s*){2,})\s*(\d{1,5}|[ivxlcdmIVXLCDM]{1,6})\s*$""",
    )
    private val TOC_TITLE_PAGE_GAP = Regex(
        """^(.{2,140}?)(?:\t|\s{2,})(\d{1,5}|[ivxlcdmIVXLCDM]{1,6})\s*$""",
    )
    private val TOC_TITLE_PAGE_SINGLE = Regex(
        """^(.{2,120}?)\s+(\d{1,4}|[ivxlcdm]{1,6})\s*$""",
    )
    private val TOC_STANDALONE_PAGE_NUM = Regex("""^(?:\d{1,4}|[ivxlcdm]{1,6})$""")
    private val TOC_PREFIX_NO_PAGE = Regex(
        """^(?:\d{1,2}\.\d{1,2}(?:\.\d{1,2})?\s+\S.*|\d{1,2}\s+[A-Z\p{IsHan}]\S.*|(?:Chapter|Part|Section|Appendix)\s+[0-9IVXLC]+\b.*|第[一二三四五六七八九十百千0-9]+[编章节].*|[一二三四五六七八九十百]+[、.．]\s*\S.*|[（(][一二三四五六七八九十0-9]+[）)]\s*\S.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val TOC_BRIEF_OR_DETAILED_HEADER = Regex(
        """^(?:Brief\s+Contents|Detailed\s+Contents|Contents|Table\s+of\s+Contents|目\s*录|总\s*目\s*录|简\s*目)$""",
        RegexOption.IGNORE_CASE,
    )
    private val ACADEMIC_NAMED_HEADINGS = setOf(
        "abstract", "introduction", "background", "literature review", "related work",
        "institutional background", "data", "data and methods", "methodology", "methods",
        "theoretical framework", "model", "empirical strategy", "identification",
        "results", "main results", "findings", "discussion", "robustness",
        "heterogeneity", "mechanisms", "policy implications", "conclusion", "conclusions",
        "summary", "references", "bibliography", "appendix", "acknowledgments", "acknowledgements",
    )

    internal data class ClassifiedOutline(
        val kind: TocNodeKind,
        val numberLabel: String?,
        val title: String,
        val isScaffoldNoise: Boolean = false,
        val chapterNumberHint: Int? = null,
    )

    internal data class WaterfallOutcome(
        val tocNodes: List<BookTocNode>,
        val studyChunks: List<BookStudyChunk>,
        val sections: List<BookSection>,
        val lessons: List<BookLesson>,
        val pageRoles: Map<Int, BookPageRole>,
        val source: String,
        val confidence: Double,
        val allBlocks: List<SourceBlock>,
    )

    private data class RawCandidate(
        val rawTitle: String,
        val rawLevel: Int,
        val page: Int,
        val printedPage: String?,
        val source: HeadingSource,
        val explicitKind: TocNodeKind? = null,
        val startOffset: Int = 0,
    )

    private fun expandLigaturesKeepGaps(raw: String): String =
        raw.replace("\uFB00", "ff")
            .replace("\uFB01", "fi")
            .replace("\uFB02", "fl")
            .replace("\uFB03", "ffi")
            .replace("\uFB04", "ffl")
            .replace("\uFB05", "ft")
            .replace("\uFB06", "st")
            .replace('\u00A0', ' ')
            .replace('\u3000', ' ')
            .replace(Regex("""[\r\n]+"""), " ")
            .trim()

    fun cleanTocTitle(raw: String): String {
        val expanded = raw
            .replace("\uFB00", "ff")
            .replace("\uFB01", "fi")
            .replace("\uFB02", "fl")
            .replace("\uFB03", "ffi")
            .replace("\uFB04", "ffl")
            .replace("\uFB05", "ft")
            .replace("\uFB06", "st")
            .replace('\u00A0', ' ')
            .replace('\u3000', ' ')
        return expanded.replace(Regex("""[\r\n\t]+"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trimStart('#')
            .trim()
    }

    fun isRejectedHeadingCandidate(rawLine: String, repeatingEdges: Set<String> = emptySet(), boldOrEnlarged: Boolean = false): Boolean {
        val line = cleanTocTitle(rawLine)
        if (line.isEmpty() || line.length > 100) return true
        if (ExplainClassifier.isPageNumber(line) || ExplainClassifier.isTocLine(line) || ExplainClassifier.isCopyrightBlock(line)) return true
        if (PRINTED_PAGE_MARK.matches(line)) return true
        if (repeatingEdges.isNotEmpty() && line.replace(Regex("""[\d\s\-–—·•|｜]+"""), "").lowercase() in repeatingEdges) return true
        if (CROSS_REF_PHRASE.containsMatchIn(line)) return true
        if (FORMULA_OR_METRIC_CHARS.containsMatchIn(line)) return true
        if (STREET_OR_ORG_NUMBER.containsMatchIn(line)) return true
        if (line.startsWith("0.") || line.startsWith("0,")) return true
        if (line.matches(Regex("""^\d+(?:\.\d+)*(?:\s+\d+(?:\.\d+)*)+$"""))) return true
        if (line.matches(Regex("""^(?:19|20)\d{2}(?:\b.*)?$""")) && !boldOrEnlarged) return true
        if (line.contains('@') || line.contains("http://", true) || line.contains("https://", true) || line.contains("www.", true)) return true
        if (line.last() in SENTENCE_ENDINGS) {
            // Only allow trailing period on very short explicit headings if enlarged/bold, never comma/semicolon/Chinese sentence end.
            if (!boldOrEnlarged || line.last() in setOf('。', '！', '？', '，', ',', ';', '；', '!', '?')) return true
        }
        // Reject numeric data rows like "1.07 Smokers – oldest" or "3.92 days" or "21.073 years"
        val decimalLead = Regex("""^(\d+)(?:\.(\d+))+\s+(.+)$""").matchEntire(line)
        if (decimalLead != null) {
            val major = decimalLead.groupValues[1].toIntOrNull() ?: 0
            val minor = decimalLead.groupValues[2].toIntOrNull() ?: 0
            val rest = decimalLead.groupValues[3].trim()
            val firstWord = rest.substringBefore(' ').lowercase().trimEnd('.', ',', ':', ';', ')')
            if (major == 0 || major > 99 || minor > 60) return true
            if (firstWord in UNIT_STARTERS) return true
            if (!boldOrEnlarged && rest.firstOrNull()?.let { it.isLowerCase() || it.isDigit() } == true) return true
        }
        val intLead = CHAPTER_LEADING_NUM.matchEntire(line)
        if (intLead != null) {
            val num = intLead.groupValues[1].toIntOrNull() ?: 0
            val rest = intLead.groupValues[2].trim()
            val firstWord = rest.substringBefore(' ').lowercase().trimEnd('.', ',', ':', ';', ')')
            if (num == 0 || num > 99) return true
            if (firstWord in UNIT_STARTERS) return true
            if (!boldOrEnlarged && rest.firstOrNull()?.let { it.isLowerCase() || it.isDigit() } == true) return true
        }
        return false
    }

    fun classifyOutlineLabel(raw: String, rawLevel: Int = 1): ClassifiedOutline {
        val cleaned = cleanTocTitle(raw)
        val comparable = cleaned.trimEnd('.', ':')
        if (cleaned.isEmpty() || FRONT_SCAFFOLD_NOISE.matches(comparable)) {
            return ClassifiedOutline(TocNodeKind.FRONT_MATTER, null, cleaned, isScaffoldNoise = true)
        }
        if (FRONT_MATTER_TITLE.matches(comparable) || FRONT_EXTRA.matches(cleaned)) {
            return ClassifiedOutline(TocNodeKind.FRONT_MATTER, null, cleaned)
        }
        if (BACK_MATTER_TITLE.matches(comparable) || LICENSE_TITLE.matches(cleaned)) {
            return ClassifiedOutline(TocNodeKind.BACK_MATTER, null, cleaned)
        }
        APPENDIX_TITLE.matchEntire(cleaned)?.let { m ->
            val label = (m.groupValues[1].ifEmpty { m.groupValues[2] }).ifEmpty { null }
            return ClassifiedOutline(TocNodeKind.APPENDIX, label, cleaned)
        }
        BOOK_PREFIX.matchEntire(cleaned)?.let { return ClassifiedOutline(TocNodeKind.PART, it.groupValues[1], cleaned) }
        PART_PREFIX.matchEntire(cleaned)?.let { m ->
            val label = listOf(m.groupValues[1], m.groupValues[3], m.groupValues[5], m.groupValues[6]).firstOrNull { it.isNotEmpty() }
            return ClassifiedOutline(TocNodeKind.PART, label, cleaned)
        }
        BARE_PART_ROMAN_WITH_TITLE.matchEntire(cleaned)?.let { m ->
            val roman = m.groupValues[1]
            if (roman in ROMAN_PARTS) {
                return ClassifiedOutline(TocNodeKind.PART, roman, "$roman: ${m.groupValues[2].trim()}")
            }
        }
        SUBSECTION_3.matchEntire(cleaned)?.let { m ->
            val label = m.groupValues[1]
            val ch = label.substringBefore('.').toIntOrNull()
            return ClassifiedOutline(TocNodeKind.SUBSECTION, label, cleaned, chapterNumberHint = ch)
        }
        SECTION_2.matchEntire(cleaned)?.let { m ->
            val label = m.groupValues[1]
            val ch = label.substringBefore('.').toIntOrNull()
            return ClassifiedOutline(TocNodeKind.SECTION, label, cleaned, chapterNumberHint = ch)
        }
        CN_SECTION_PREFIX.matchEntire(cleaned)?.let { m ->
            return ClassifiedOutline(TocNodeKind.SECTION, m.groupValues[1], cleaned)
        }
        CN_NUM_L2.matchEntire(cleaned)?.let { m ->
            return ClassifiedOutline(TocNodeKind.SECTION, m.groupValues[1], cleaned)
        }
        CHAPTER_EXPLICIT.matchEntire(cleaned.replace(Regex("""(?i)^(Chapter\s+[IVXLC]+)\*(?=\b|\W|$)"""), "$1"))?.let { m ->
            val chNum = m.groupValues[1].toIntOrNull()
            return ClassifiedOutline(TocNodeKind.CHAPTER, m.groupValues[1], cleaned, chapterNumberHint = chNum)
        }
        CHAPTER_LEADING_NUM.matchEntire(cleaned)?.let { m ->
            val chNum = m.groupValues[1].toIntOrNull()
            if (chNum != null && chNum in 1..99) {
                return ClassifiedOutline(TocNodeKind.CHAPTER, m.groupValues[1], cleaned, chapterNumberHint = chNum)
            }
        }
        if (CN_COLLATION_SUBSECTION.matches(cleaned) && rawLevel >= 2) {
            return ClassifiedOutline(TocNodeKind.SUBSECTION, null, cleaned)
        }
        CN_CHAPTER_PREFIX.matchEntire(cleaned)?.let { m ->
            val kind = if (rawLevel >= 2 && !CN_EXPLICIT_CHAPTER.matches(m.groupValues[1])) TocNodeKind.SECTION else TocNodeKind.CHAPTER
            return ClassifiedOutline(kind, m.groupValues[1], cleaned)
        }
        CN_NUM_L1.matchEntire(cleaned)?.let { m ->
            return ClassifiedOutline(TocNodeKind.CHAPTER, m.groupValues[1], cleaned)
        }
        val kind = when {
            rawLevel <= 1 -> TocNodeKind.CHAPTER
            rawLevel == 2 -> TocNodeKind.SECTION
            else -> TocNodeKind.SUBSECTION
        }
        return ClassifiedOutline(kind, null, cleaned)
    }

    private val ROMAN_PARTS = setOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")

    /** Build canonical Tier 0 TOC immediately from PDF/EPUB/Word outline without requiring full-book text. */
    fun buildTier0Structure(
        bookId: String,
        title: String,
        totalPages: Int,
        externalOutline: List<BookOutlineItem>,
    ): BookStructure {
        val raw = externalOutline.map {
            RawCandidate(it.title, it.level, it.page.coerceIn(1, totalPages.coerceAtLeast(1)), null, it.source, startOffset = it.startOffset)
        }
        val tocNodes = buildCanonicalNodes(bookId, totalPages.coerceAtLeast(1), raw, confidence = if (raw.isNotEmpty()) 1.0 else 0.0, bookTitle = title)
        val firstBodyPage = BookBoundaries.firstChapter(tocNodes)?.startPage
        val pageRoles = buildMap {
            for (p in 1..totalPages.coerceAtLeast(1)) {
                put(p, if (firstBodyPage != null && p < firstBodyPage) BookPageRole.FRONT_MATTER else BookPageRole.BODY)
            }
        }
        val source = externalOutline.firstOrNull()?.source?.name ?: HeadingSource.FALLBACK_LENGTH.name
        return BookStructure(
            version = BookStructure.CURRENT_VERSION,
            pageRoles = pageRoles,
            sections = emptyList(),
            source = source,
            modelAttempted = false,
            tocNodes = tocNodes,
            studyChunks = emptyList(),
            confidence = if (tocNodes.isNotEmpty()) 1.0 else 0.0,
        )
    }

    private val BODY_NODE_KINDS = setOf(TocNodeKind.PART, TocNodeKind.CHAPTER, TocNodeKind.SECTION, TocNodeKind.SUBSECTION, TocNodeKind.APPENDIX)

    /** Fast check matching Stage A acceptance thresholds in [plan] so Tier 1 can skip per-glyph typography extraction. */
    fun willStageASucceed(externalOutline: List<BookOutlineItem>, totalPages: Int): Boolean =
        externalOutline.any { it.title.isNotBlank() && it.page in 1..totalPages }

    fun plan(
        bookId: String,
        bookTitle: String,
        format: String,
        pages: List<BookPage>,
        externalOutline: List<BookOutlineItem> = emptyList(),
        typographyLines: Map<Int, List<PageLineTypography>> = emptyMap(),
        nativeBodyStart: BookPageAnchor? = null,
    ): WaterfallOutcome {
        val totalPages = pages.maxOfOrNull { it.number } ?: pages.size.coerceAtLeast(1)
        val allBlocks = StudyCoachCourse.blocks(bookId, pages, snapSentences = true)
        val repeatingEdges = ExplainClassifier.precomputeRepeatingEdges(allBlocks)
        val initialRoles = classifyPagesFast(allBlocks, pages, repeatingEdges, format)
        val byPage = allBlocks.groupBy { it.page }

        // Stage A: External outline (PDF Outline / EPUB TOC / Word styles)
        val pageByNumber = pages.associateBy { it.number }
        val stageARaw = externalOutline.filter { it.page in 1..totalPages }.map {
            val offset = if (it.source == HeadingSource.PDF_OUTLINE && it.startOffset == 0)
                pdfHeadingOffset(pageByNumber[it.page]?.text.orEmpty(), it.title) else it.startOffset
            RawCandidate(it.title, it.level, it.page, null, it.source, startOffset = offset)
        }
        val stageANodes = buildCanonicalNodes(bookId, totalPages, stageARaw, confidence = 1.0, bookTitle = bookTitle)
        if (stageARaw.isNotEmpty()) {
            val refinedRoles = refinePageRolesWithToc(pages, initialRoles, stageANodes, byPage, nativeBodyStart = nativeBodyStart)
            val (chunks, sections, lessons) = buildStudyChunks(bookId, bookTitle, pages, refinedRoles, stageANodes, byPage, nativeBodyStart)
            return WaterfallOutcome(stageANodes, chunks, sections, lessons, refinedRoles, stageARaw.first().source.name, 1.0, allBlocks)
        }

        val effectiveTypo = typographyLines.ifEmpty { deriveTypographyFromLayouts(pages) }
        val openings = if (format == "pdf") PrintedChapterOpenings.detect(pages, effectiveTypo) else emptyList()
        // Stage B: Extended leading printed TOC parser (for PDFs without outline)
        val stageBResult = runStageB(bookId, pages, typographyLines, byPage, totalPages)
        if (stageBResult != null && stageBResult.first.count { it.kind in BODY_NODE_KINDS } >= 3) {
            val (tocNodes, conf) = stageBResult
            // A printed contents page often lists details at chapter depth. Prefer the
            // physical openings when the inferred major destinations largely disagree.
            val chapterStarts = tocNodes.filter { it.kind == TocNodeKind.CHAPTER }.map { it.startPage }
            val unmatched = openings.mapTo(mutableSetOf()) { it.page }
            val supported = openings.isEmpty() || chapterStarts.count { start ->
                unmatched.remove(start) || unmatched.remove(start - 1) || unmatched.remove(start + 1)
            } * 2 > chapterStarts.size
            if (conf >= 0.85 && supported) {
                val refinedRoles = refinePageRolesWithToc(pages, initialRoles, tocNodes, byPage, nativeBodyStart = nativeBodyStart)
                val (chunks, sections, lessons) = buildStudyChunks(bookId, bookTitle, pages, refinedRoles, tocNodes, byPage, nativeBodyStart)
                return WaterfallOutcome(tocNodes, chunks, sections, lessons, refinedRoles, HeadingSource.TOC_PAGE.name, conf, allBlocks)
            }
        }

        // Stage C: Typography clustering + strict negative filters (slides, academic papers, scanned OCR)
        val stageC = runStageC(bookId, bookTitle, pages, initialRoles, byPage, effectiveTypo, repeatingEdges, totalPages, format, openings)
        val finalNodes = stageC.nodes
        val finalConf = stageC.confidence
        val finalSource = stageC.source
        val refinedRoles = refinePageRolesWithToc(pages, initialRoles, finalNodes, byPage, authoritativeFrontMatter = false, nativeBodyStart = nativeBodyStart)
        val (chunks, sections, lessons) = buildStudyChunks(bookId, bookTitle, pages, refinedRoles, finalNodes, byPage, nativeBodyStart)
        return WaterfallOutcome(finalNodes, chunks, sections, lessons, refinedRoles, finalSource.name, finalConf, allBlocks)
    }

    internal fun classifyPagesFast(
        allBlocks: List<SourceBlock>,
        pages: List<BookPage>,
        repeatingEdges: Set<String>,
        format: String? = null,
    ): Map<Int, BookPageRole> {
        val result = LinkedHashMap<Int, BookPageRole>(pages.size)
        val totalPages = pages.size
        val byPage = allBlocks.groupBy { it.page }
        var inBackSection: BookPageRole? = null
        val indexHeading = Regex("""^(?:索\s*引|主题索引|人名索引|关键词索引|Index|Subject\s+Index|Author\s+Index)$""", RegexOption.IGNORE_CASE)
        val indexEntry = Regex("""^.{2,60}?[,，、]\s*(?:p\.?\s*)?\d{1,5}(?:\s*[-–—,，、]\s*\d{1,5})*\s*$""", RegexOption.IGNORE_CASE)
        val refHeading = Regex("""^(?:参\s*考\s*文\s*献|引用文献|References|Bibliography|Works\s+Cited)$""", RegexOption.IGNORE_CASE)
        val refEntry = Regex("""^(?:\[\d{1,4}\]|\d{1,3}[.．]\s+.{10,}(?:\d{4}|出版社|Press|Journal|Vol\.|pp\.))""")
        val frontHeading = Regex("""^(?:前\s*言|序\s*言?|自\s*序|编\s*者\s*按|凡\s*例|致\s*谢|Preface|Foreword|About\s+the\s+Authors?|Acknowledgments?)$""", RegexOption.IGNORE_CASE)
        val romanOnly = Regex("""^(?:[-–—]\s*)?[ivxlcdm]+(?:\s*[-–—])?$""", RegexOption.IGNORE_CASE)

        fun isIndex(blocks: List<SourceBlock>): Boolean {
            val lines = blocks.flatMap { it.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList() }
            if (lines.size < 4) return false
            val hits = lines.count { indexEntry.matches(it) }
            return hits >= 4 && hits * 10 >= lines.size * 5
        }
        fun isRefs(blocks: List<SourceBlock>): Boolean {
            val lines = blocks.flatMap { it.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList() }
            if (lines.size < 3) return false
            val hits = lines.count { refEntry.containsMatchIn(it) }
            return hits >= 3 && hits * 10 >= lines.size * 5
        }

        for ((idx, page) in pages.withIndex()) {
            val pageBlocks = byPage[page.number].orEmpty()
            val analysis = ExplainClassifier.analyzePage(pageBlocks, repeatingEdges)
            val nonPage = pageBlocks.filter { analysis.blockKinds[it.id] != ExplainBlockKind.PAGE_NUMBER }
            val hasRoman = pageBlocks.any {
                analysis.blockKinds[it.id] == ExplainBlockKind.PAGE_NUMBER && romanOnly.matches(it.text.trim())
            }
            val firstHeading = nonPage.firstOrNull()?.text?.trim().orEmpty().trimStart('#').trim()
            val hasIdxH = nonPage.any { indexHeading.matches(it.text.trim().trimStart('#').trim()) }
            val hasRefH = nonPage.any { refHeading.matches(it.text.trim().trimStart('#').trim()) }
            val hasFrontH = nonPage.any { frontHeading.matches(it.text.trim().trimStart('#').trim()) }
            val startsMain = BookStructurePlanner.matchHeadingRule(firstHeading, false)?.level == 1

            // Slide titles, bullet points and speaker notes may all be shorter than a prose paragraph.
            val hasSlideNotes = format == "pptx" && page.text.substringAfter("\n\n备注\n\n", "").isNotBlank()
            val role = when {
                nonPage.isEmpty() -> BookPageRole.BLANK
                format == "pdf" && PrintedChapterOpenings.isContents(page.text.lines()) -> BookPageRole.TOC
                analysis.kind == ExplainPageKind.TOC -> BookPageRole.TOC
                analysis.kind == ExplainPageKind.COPYRIGHT -> BookPageRole.COPYRIGHT
                hasIdxH || isIndex(nonPage) -> { inBackSection = BookPageRole.INDEX; BookPageRole.INDEX }
                hasRefH || isRefs(nonPage) -> { inBackSection = BookPageRole.REFERENCES; BookPageRole.REFERENCES }
                inBackSection != null && idx >= totalPages / 2 && !startsMain -> inBackSection
                hasFrontH && (hasRoman || idx < minOf(6, totalPages / 3 + 1)) && analysis.bodyBlocks.size <= 3 -> BookPageRole.FRONT_MATTER
                analysis.kind == ExplainPageKind.HEADINGS_ONLY && !hasSlideNotes && !startsMain && (hasRoman || (idx == 0 && totalPages >= 3)) -> BookPageRole.FRONT_MATTER
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

    private fun refinePageRolesWithToc(
        pages: List<BookPage>,
        initial: Map<Int, BookPageRole>,
        tocNodes: List<BookTocNode>,
        byPage: Map<Int, List<SourceBlock>>,
        authoritativeFrontMatter: Boolean = true,
        nativeBodyStart: BookPageAnchor? = null,
    ): Map<Int, BookPageRole> {
        if (tocNodes.isEmpty() && nativeBodyStart == null) return initial
        val firstBodyPage = nativeBodyStart?.importPage ?: BookBoundaries.openingNode(tocNodes)?.startPage
        val lastChapterPage = tocNodes.lastOrNull { it.kind == TocNodeKind.CHAPTER }?.startPage ?: 0
        val firstBackNode = tocNodes.firstOrNull { it.kind == TocNodeKind.BACK_MATTER && it.startPage > lastChapterPage }
        val firstBackPage = firstBackNode?.startPage
        val hasExplicitFront = tocNodes.any { it.kind == TocNodeKind.FRONT_MATTER && (firstBodyPage == null || it.startPage < firstBodyPage) }
        val out = LinkedHashMap(initial)
        for (page in pages) {
            val p = page.number
            val current = out[p] ?: BookPageRole.BODY
            if (current == BookPageRole.BLANK) continue
            val nonWs = ExplainClassifier.nonWhitespaceLength(page.text)
            if (firstBodyPage != null && p < firstBodyPage) {
                if ((authoritativeFrontMatter || hasExplicitFront || current != BookPageRole.BODY) &&
                    current != BookPageRole.TOC && current != BookPageRole.COPYRIGHT
                ) {
                    out[p] = BookPageRole.FRONT_MATTER
                }
                continue
            }
            if (firstBackPage != null && p >= firstBackPage) {
                val activeBack = tocNodes.lastOrNull { it.kind == TocNodeKind.BACK_MATTER && it.startPage <= p } ?: firstBackNode
                val backRole = if (activeBack?.title?.contains("index", ignoreCase = true) == true ||
                    activeBack?.title?.contains("索引") == true
                ) BookPageRole.INDEX else BookPageRole.REFERENCES
                out[p] = backRole
                continue
            }
            if ((current == BookPageRole.COPYRIGHT || current == BookPageRole.FRONT_MATTER) && nonWs >= 400) {
                val firstBodyAnchor = nativeBodyStart
                    ?: BookBoundaries.openingNode(tocNodes)?.let { BookPageAnchor(it.startPage, it.startOffset) }
                val bodyOffset = firstBodyAnchor?.takeIf { it.importPage == p && it.offsetUtf16 > 0 }?.offsetUtf16 ?: 0
                if (bodyOffset > 0 && bodyOffset < page.text.length) {
                    val tail = page.text.substring(bodyOffset)
                    if (ExplainClassifier.nonWhitespaceLength(tail) >= 200 && ExplainClassifier.copyrightMarkerCount(tail) < 2) {
                        out[p] = BookPageRole.BODY
                        continue
                    }
                }
                val maxMarkers = byPage[p].orEmpty().maxOfOrNull { ExplainClassifier.copyrightMarkerCount(it.text.trim()) } ?: 0
                if (maxMarkers < 2) out[p] = BookPageRole.BODY
            }
        }
        return out
    }

    /**
     * Stage A2 + common canonical hierarchy builder:
     * Fixes Part/Chapter/Section/Subsection nesting, ensures FRONT_MATTER/BACK_MATTER never nest under a Part/Chapter,
     * drops scaffold noise, and calculates accurate `startPage..endPage` bounds.
     */
    private fun buildCanonicalNodes(
        bookId: String,
        totalPages: Int,
        candidates: List<RawCandidate>,
        confidence: Double,
        bookTitle: String = "",
    ): List<BookTocNode> {
        if (candidates.isEmpty()) return emptyList()
        data class ClassifiedItem(
            val cand: RawCandidate,
            val info: ClassifiedOutline,
        )
        val epub = candidates.first().source == HeadingSource.EPUB_TOC
        val chroniclePartMode = candidates.count { it.rawTitle.startsWith("史記 ") } >= 5
        val normTitleCompare = normalizeComparable(bookTitle.replace(Regex("""[（(][^）)]*[）)]"""), ""))
        val firstCand = candidates.firstOrNull()
        val bodyFollowing = candidates.drop(1).filter { !BACK_MATTER_TITLE.matches(cleanTocTitle(it.rawTitle)) }
        val soleLeadingH1Rule = !epub && firstCand != null && firstCand.rawLevel == 1 &&
            firstCand.source == HeadingSource.HEADING_RULE &&
            bodyFollowing.size >= 3 &&
            bodyFollowing.all { it.rawLevel >= 2 } &&
            cleanTocTitle(firstCand.rawTitle).let { t ->
                !CHAPTER_EXPLICIT.matches(t) && !CN_CHAPTER_PREFIX.matches(t) && !PART_PREFIX.matches(t)
            }
        val matchesBookTitleH1 = !epub && normTitleCompare.isNotEmpty() && firstCand?.let { first ->
            first.rawLevel == 1 &&
                (normalizeComparable(first.rawTitle) == normTitleCompare ||
                    normalizeComparable(first.rawTitle.replace(Regex("""[（(][^）)]*[）)]"""), "")) == normTitleCompare)
        } == true && candidates.drop(1).any { it.rawLevel >= 2 }
        val hasH1BookTitle = matchesBookTitleH1 || soleLeadingH1Rule
        val minSubLevel = candidates.drop(if (hasH1BookTitle) 1 else 0)
            .filter { !BACK_MATTER_TITLE.matches(cleanTocTitle(it.rawTitle)) }
            .minOfOrNull { it.rawLevel } ?: 1
        val shiftHierarchy = !epub && (hasH1BookTitle || (minSubLevel >= 2 && candidates.all { it.source == HeadingSource.HEADING_RULE }))
        val effectiveCandidates = if (shiftHierarchy) {
            val shift = (minSubLevel - 1).coerceAtLeast(1)
            candidates.mapIndexed { idx, cand ->
                if (hasH1BookTitle && idx == 0) cand.copy(explicitKind = TocNodeKind.FRONT_MATTER)
                else cand.copy(rawLevel = (cand.rawLevel - shift).coerceAtLeast(1))
            }
        } else candidates

        val firstExplicitChapter = if (epub) effectiveCandidates.indexOfFirst { cand ->
            classifyOutlineLabel(cand.rawTitle, cand.rawLevel).let { info ->
                info.kind == TocNodeKind.CHAPTER && info.numberLabel != null
            } || (cand.rawLevel <= 3 && ROMAN_CHAPTER.matches(cand.rawTitle))
        } else -1
        val lastExplicitChapter = if (firstExplicitChapter >= 0) effectiveCandidates.indexOfLast {
            (classifyOutlineLabel(it.rawTitle, it.rawLevel).let { info ->
                info.kind == TocNodeKind.CHAPTER && info.numberLabel != null
            }) || (it.rawLevel <= 3 && ROMAN_CHAPTER.matches(it.rawTitle))
        } else -1
        var seenChapter = false
        var inBackMatter = false
        // Preserve the source tree while classifying labels. A numbered bookmark inside
        // "Solutions" or "Preface" indexes that material, even when it says "Chapter 1".
        val matterAncestors = ArrayDeque<Pair<Int, TocNodeKind>>()
        val filtered = effectiveCandidates.mapIndexedNotNull { index, cand ->
            val classified = classifyOutlineLabel(cand.rawTitle, cand.rawLevel)
            val nativeOutline = epub || cand.source == HeadingSource.PDF_OUTLINE
            while (matterAncestors.isNotEmpty() && matterAncestors.last().first >= cand.rawLevel) {
                matterAncestors.removeLast()
            }
            val inherited = matterAncestors.lastOrNull()?.second
            if (nativeOutline && classified.kind in setOf(TocNodeKind.FRONT_MATTER, TocNodeKind.BACK_MATTER) &&
                !classified.isScaffoldNoise) {
                matterAncestors.addLast(cand.rawLevel to (inherited ?: classified.kind))
            }
            val info = when {
                cand.source == HeadingSource.PDF_OUTLINE && classified.title.endsWith(".pdf", ignoreCase = true) ->
                    classified.copy(isScaffoldNoise = true)
                inherited != null -> classified.copy(kind = inherited)
                chroniclePartMode && ClassicalAnnotationLayer.isShijiPartTitle(classified.title) ->
                    classified.copy(kind = TocNodeKind.PART)
                chroniclePartMode && classified.title.startsWith("史記 ") ->
                    classified.copy(kind = TocNodeKind.CHAPTER)
                !epub && inBackMatter && classified.kind != TocNodeKind.BACK_MATTER ->
                    classified.copy(kind = TocNodeKind.BACK_MATTER)
                !epub && seenChapter && cand.rawLevel == 1 && classified.kind == TocNodeKind.FRONT_MATTER &&
                    cand.source == HeadingSource.HEADING_RULE ->
                    classified.copy(kind = TocNodeKind.CHAPTER)
                !epub -> classified
                classified.title == "正文" -> classified.copy(kind = TocNodeKind.SECTION)
                cand.rawLevel <= 3 && ROMAN_CHAPTER.matches(cand.rawTitle) ->
                    classified.copy(kind = TocNodeKind.CHAPTER, numberLabel = cand.rawTitle.substringBefore(' '))
                firstExplicitChapter >= 0 && classified.kind == TocNodeKind.CHAPTER && classified.numberLabel == null ->
                    classified.copy(kind = if (index < firstExplicitChapter) TocNodeKind.FRONT_MATTER
                        else if (index > lastExplicitChapter) TocNodeKind.BACK_MATTER else TocNodeKind.SECTION)
                else -> classified
            }
            val finalInfo = if (cand.explicitKind != null) info.copy(kind = cand.explicitKind) else info
            if (finalInfo.kind == TocNodeKind.CHAPTER) seenChapter = true
            if (finalInfo.kind == TocNodeKind.BACK_MATTER) inBackMatter = true
            if (finalInfo.isScaffoldNoise || finalInfo.title.isEmpty()) null
            else ClassifiedItem(cand, finalInfo)
        }
        if (filtered.isEmpty()) return emptyList()

        val hasParts = filtered.any { it.info.kind == TocNodeKind.PART }
        var currentPartId: String? = null
        var currentChapterId: String? = null
        var currentSectionId: String? = null
        val chapterByNum = HashMap<Int, String>()

        val draft = ArrayList<BookTocNode>(filtered.size)
        for ((idx, item) in filtered.withIndex()) {
            val info = item.info
            val nodeId = "$bookId:toc:$idx"
            val (level, parentId) = when (info.kind) {
                TocNodeKind.FRONT_MATTER, TocNodeKind.BACK_MATTER -> {
                    currentSectionId = null
                    1 to null
                }
                TocNodeKind.PART -> {
                    currentPartId = nodeId
                    currentChapterId = null
                    currentSectionId = null
                    1 to null
                }
                TocNodeKind.CHAPTER, TocNodeKind.APPENDIX -> {
                    currentChapterId = nodeId
                    currentSectionId = null
                    info.chapterNumberHint?.let { chapterByNum[it] = nodeId }
                    (if (hasParts) 2 else 1) to (if (hasParts) currentPartId else null)
                }
                TocNodeKind.SECTION -> {
                    currentSectionId = nodeId
                    val chId = info.chapterNumberHint?.let { chapterByNum[it] } ?: currentChapterId
                    (if (hasParts) 3 else 2) to (chId ?: currentPartId)
                }
                TocNodeKind.SUBSECTION -> {
                    val secParent = currentSectionId ?: info.chapterNumberHint?.let { chapterByNum[it] } ?: currentChapterId
                    (if (hasParts) 4 else 3) to secParent
                }
            }
            draft += BookTocNode(
                id = nodeId,
                parentId = parentId,
                ordinal = idx,
                level = level,
                kind = info.kind,
                numberLabel = info.numberLabel,
                title = info.title,
                startPage = item.cand.page.coerceIn(1, totalPages),
                endPage = totalPages,
                printedPage = item.cand.printedPage,
                source = item.cand.source,
                confidence = confidence,
                startOffset = item.cand.startOffset,
            )
        }

        return withEndPages(draft, totalPages)
    }

    internal fun withEndPages(draft: List<BookTocNode>, totalPages: Int): List<BookTocNode> {
        // Compute endPage for every node from the next peer/ancestor boundary.
        return draft.mapIndexed { i, node ->
            var nextBoundaryPage: Int? = null
            for (j in (i + 1) until draft.size) {
                val other = draft[j]
                if (other.level <= node.level) {
                    nextBoundaryPage = other.startPage
                    break
                }
            }
            val computedEnd = if (nextBoundaryPage == null) {
                totalPages
            } else if (nextBoundaryPage > node.startPage) {
                nextBoundaryPage - 1
            } else {
                node.startPage
            }
            node.copy(ordinal = i, endPage = computedEnd.coerceIn(node.startPage, totalPages))
        }
    }

    // ---- Stage B: Extended Leading Printed TOC Parser ----

    private data class TocLineEntry(
        val title: String,
        val printedPage: Int,
        val explicitPart: Boolean = false,
        val explicitFrontOrBack: TocNodeKind? = null,
    )

    private data class TocBlockSpan(
        val startPage: Int,
        val endPage: Int,
        val isBrief: Boolean,
        val entries: List<TocLineEntry>,
    )

    private fun runStageB(
        bookId: String,
        pages: List<BookPage>,
        typographyLines: Map<Int, List<PageLineTypography>>,
        byPage: Map<Int, List<SourceBlock>>,
        totalPages: Int,
    ): Pair<List<BookTocNode>, Double>? {
        val scanLimitPage = maxOf(35, (pages.size * 8) / 100).coerceAtMost(50)
        val candidatePages = pages.filter { it.number <= scanLimitPage }
        if (candidatePages.isEmpty()) return null

        val spans = mutableListOf<TocBlockSpan>()
        var currentEntries = mutableListOf<TocLineEntry>()
        var spanStart = -1
        var spanEnd = -1
        var spanBrief = false
        var nonTocProseStreak = 0

        fun flushSpan() {
            if (currentEntries.isNotEmpty() && spanStart > 0) {
                spans += TocBlockSpan(spanStart, spanEnd, spanBrief, currentEntries.toList())
            }
            currentEntries = mutableListOf()
            spanStart = -1
            spanEnd = -1
            spanBrief = false
        }

        for (page in candidatePages) {
            val lines = tocSourceLinesForPage(page, typographyLines[page.number].orEmpty())
            val hasExplicitTocHeader = lines.any { TOC_BRIEF_OR_DETAILED_HEADER.matches(it) }
            val hasBriefHeader = lines.any { it.equals("Brief Contents", true) || it.equals("简目") || it.equals("简 目") }
            val parsed = parseTocLinesOnPage(lines)
            val nonWs = ExplainClassifier.nonWhitespaceLength(page.text)
            val isTocPage = hasExplicitTocHeader || parsed.size >= 4 ||
                (currentEntries.isNotEmpty() && parsed.size >= 2 && nonWs < 2600)

            if (isTocPage) {
                nonTocProseStreak = 0
                if (hasExplicitTocHeader && currentEntries.isNotEmpty() && hasBriefHeader != spanBrief) {
                    // Switching from Brief Contents to Detailed Contents starts a distinct TOC block so we can deduplicate!
                    flushSpan()
                }
                if (spanStart < 0) {
                    spanStart = page.number
                    spanBrief = hasBriefHeader
                }
                spanEnd = page.number
                currentEntries.addAll(parsed)
            } else {
                flushSpan()
                if (spans.isNotEmpty() && nonWs > 1200) {
                    nonTocProseStreak++
                    if (nonTocProseStreak >= 2) break
                }
            }
        }
        flushSpan()

        if (spans.isEmpty()) return null
        // Choose Detailed Contents over Brief Contents when both are present.
        val chosenEntries = pickBestTocSpan(spans)
        if (chosenEntries.size < 3) return null

        val lastTocPage = spans.maxOf { it.endPage }
        val searchPages = pages.filter { it.number > lastTocPage }
        if (searchPages.isEmpty()) return null

        // Calibrate printed page -> physical PDF page offset using anchor hits across the book.
        val bodyCandidates = chosenEntries.filter {
            it.explicitFrontOrBack != TocNodeKind.FRONT_MATTER && !it.explicitPart
        }
        val probeSample = if (bodyCandidates.size <= 18) bodyCandidates else {
            val step = maxOf(1, bodyCandidates.size / 18)
            bodyCandidates.filterIndexed { i, _ -> i % step == 0 }.take(18)
        }
        val deltas = mutableListOf<Int>()
        val directMatches = HashMap<Int, Int>()
        for (entry in chosenEntries) {
            // Only probe a bounded sample or chapters for speed.
            val isChapterLike = classifyOutlineLabel(entry.title).kind in setOf(TocNodeKind.CHAPTER, TocNodeKind.BACK_MATTER)
            if (!isChapterLike && entry !in probeSample) continue
            val matchedPage = findAnchorPage(entry.title, searchPages, byPage)
            if (matchedPage != null) {
                deltas += matchedPage - entry.printedPage
                directMatches[chosenEntries.indexOf(entry)] = matchedPage
            }
        }
        if (deltas.isEmpty()) return null
        val sortedDeltas = deltas.sorted()
        val medianDelta = sortedDeltas[sortedDeltas.size / 2]
        val consistentAnchors = deltas.count { kotlin.math.abs(it - medianDelta) <= 3 }
        if (consistentAnchors < 2 && consistentAnchors * 2 < deltas.size) return null

        var prevPhysicalPage = lastTocPage + 1
        val raw = mutableListOf<RawCandidate>()
        for ((idx, entry) in chosenEntries.withIndex()) {
            if (entry.explicitFrontOrBack == TocNodeKind.FRONT_MATTER) {
                val p = (entry.printedPage).coerceIn(1, lastTocPage + 20)
                raw += RawCandidate(entry.title, 1, p, entry.printedPage.toString(), HeadingSource.TOC_PAGE, TocNodeKind.FRONT_MATTER)
                continue
            }
            val calibrated = (entry.printedPage + medianDelta).coerceIn(lastTocPage + 1, totalPages)
            val direct = directMatches[idx]?.takeIf { kotlin.math.abs(it - calibrated) <= 3 }
            val physical = (direct ?: calibrated).coerceAtLeast(prevPhysicalPage).coerceIn(1, totalPages)
            prevPhysicalPage = physical
            raw += RawCandidate(
                rawTitle = entry.title,
                rawLevel = 1,
                page = physical,
                printedPage = entry.printedPage.toString(),
                source = HeadingSource.TOC_PAGE,
                explicitKind = entry.explicitFrontOrBack ?: if (entry.explicitPart) TocNodeKind.PART else null,
            )
        }
        val nodes = buildCanonicalNodes(bookId, totalPages, raw, confidence = 0.95)
        val anchorRatio = consistentAnchors.toDouble() / deltas.size.coerceAtLeast(1)
        val conf = if (nodes.count { it.kind in BODY_NODE_KINDS } >= 4 && anchorRatio >= 0.6) 0.95 else 0.80
        return nodes to conf
    }

    private fun tocSourceLinesForPage(page: BookPage, typoLines: List<PageLineTypography>): List<String> {
        if (typoLines.isNotEmpty()) {
            val rowLines = typoLines.mapNotNull { expandLigaturesKeepGaps(it.rowText).takeIf(String::isNotEmpty) }
            if (rowLines.isNotEmpty()) return rowLines
        }
        return page.text.lineSequence().map { expandLigaturesKeepGaps(it) }.filter(String::isNotEmpty).toList()
    }

    private fun parseTocLinesOnPage(lines: List<String>): List<TocLineEntry> {
        val cleaned = lines.filterNot { line ->
            ExplainClassifier.isTocHeading(line) ||
                TOC_BRIEF_OR_DETAILED_HEADER.matches(line) ||
                line.equals("CONTENTS", true) ||
                line.equals("BRIEF CONTENTS", true) ||
                ExplainClassifier.isPageNumber(line) ||
                PRINTED_PAGE_MARK.matches(line)
        }
        val entries = mutableListOf<TocLineEntry>()
        val pendingPartLabels = ArrayDeque<String>()
        var i = 0
        while (i < cleaned.size) {
            val line = cleaned[i]
            // Standalone Roman Part line or "Part I ..." without page number: hold to pair with its title or next chapter.
            if (BARE_ROMAN_ONLY.matches(line) && line in ROMAN_PARTS) {
                pendingPartLabels.addLast(line)
                i++
                continue
            }
            val directPart = PART_PREFIX.matchEntire(line) ?: BARE_PART_ROMAN_WITH_TITLE.matchEntire(line)
            if (directPart != null && parseTocSingleLine(line) == null) {
                // Attach Part entry to next line's printed page once known (or peek next entry).
                pendingPartLabels.addLast(line)
                i++
                continue
            }

            // Try 1-line TOC entry first.
            var parsed = parseTocSingleLine(line)
            var advance = 1

            // Try 2-line wrapped TOC entry (e.g. a section title wrapped across two visual lines before the page number).
            if (parsed == null && i + 1 < cleaned.size && TOC_PREFIX_NO_PAGE.matches(line)) {
                val nextLine = cleaned[i + 1]
                if (!TOC_PREFIX_NO_PAGE.matches(nextLine) && !BARE_ROMAN_ONLY.matches(nextLine)) {
                    val combined = "$line $nextLine"
                    val combinedParsed = parseTocSingleLine(combined)
                    if (combinedParsed != null) {
                        parsed = combinedParsed
                        advance = 2
                    }
                }
            }

            if (parsed != null) {
                while (pendingPartLabels.isNotEmpty()) {
                    val partRaw = pendingPartLabels.removeFirst()
                    val formattedPart = if (partRaw in ROMAN_PARTS) "Part $partRaw" else partRaw
                    entries += TocLineEntry(formattedPart, parsed.printedPage, explicitPart = true)
                }
                val info = classifyOutlineLabel(parsed.title)
                if (!info.isScaffoldNoise) {
                    val frontOrBack = when (info.kind) {
                        TocNodeKind.FRONT_MATTER -> TocNodeKind.FRONT_MATTER
                        TocNodeKind.BACK_MATTER -> TocNodeKind.BACK_MATTER
                        else -> null
                    }
                    entries += parsed.copy(explicitFrontOrBack = frontOrBack)
                }
            } else if (pendingPartLabels.isNotEmpty() && line.length in 4..80 && !TOC_STANDALONE_PAGE_NUM.matches(line)) {
                // e.g. "VI" on line i-1 followed by "Public sector analysis" on line i without page number.
                val partRoman = pendingPartLabels.removeFirst()
                if (partRoman in ROMAN_PARTS) {
                    pendingPartLabels.addLast("$partRoman: $line")
                }
            }
            i += advance
        }

        // Secondary fallback when PDFTextStripper emitted a column of titles followed by a column of page numbers.
        if (entries.size < 3) {
            val columnParsed = parseColumnarTocBlock(cleaned)
            if (columnParsed.size > entries.size) return columnParsed
        }
        return entries
    }

    private fun parseTocSingleLine(line: String): TocLineEntry? {
        if (line.length < 3 || line.endsWith('.') || line.endsWith(',')) return null
        val match = TOC_TITLE_PAGE_LEADER.matchEntire(line)
            ?: TOC_TITLE_PAGE_GAP.matchEntire(line)
            ?: TOC_TITLE_PAGE_SINGLE.matchEntire(line)?.takeIf { m ->
                val prefix = m.groupValues[1].trim()
                val rawPage = m.groupValues[2].trim()
                val num = rawPage.toIntOrNull()
                if (num != null && num in 1900..2099) return@takeIf false
                prefix.lastOrNull() !in setOf('=', '+', '-', '/', ':', '：', ',') &&
                    (TOC_PREFIX_NO_PAGE.matches(prefix) ||
                        FRONT_MATTER_TITLE.matches(prefix) ||
                        BACK_MATTER_TITLE.matches(prefix))
            }
            ?: return null
        val rawTitle = cleanTocTitle(match.groupValues[1].trim().trimEnd('.', '·', '…', '⋯', '-', '—', ' '))
        val pageToken = match.groupValues[2].trim()
        val pageNum = pageToken.toIntOrNull() ?: parseSmallRoman(pageToken) ?: return null
        if (rawTitle.length < 2 || pageNum !in 1..5000) return null
        if (CROSS_REF_PHRASE.containsMatchIn(rawTitle)) return null
        // Handle leading Roman Part prefix inline like "I Demand for health 8" -> split only if it's actually a chapter/part.
        return TocLineEntry(rawTitle, pageNum)
    }

    private fun parseColumnarTocBlock(lines: List<String>): List<TocLineEntry> {
        val titles = mutableListOf<String>()
        val pages = mutableListOf<Int>()
        for (line in lines) {
            if (TOC_STANDALONE_PAGE_NUM.matches(line)) {
                val n = line.toIntOrNull() ?: parseSmallRoman(line)
                if (n != null && n in 1..5000) pages += n
            } else if (TOC_PREFIX_NO_PAGE.matches(line) || FRONT_MATTER_TITLE.matches(line) || BACK_MATTER_TITLE.matches(line)) {
                titles += line
            }
        }
        if (titles.size < 4 || titles.size != pages.size) return emptyList()
        return titles.zip(pages).map { (t, p) -> TocLineEntry(t, p) }
    }

    private fun pickBestTocSpan(spans: List<TocBlockSpan>): List<TocLineEntry> {
        if (spans.size == 1) return spans.single().entries
        val nonBrief = spans.filter { !it.isBrief }
        val pool = if (nonBrief.isNotEmpty()) nonBrief else spans
        return pool.maxByOrNull { it.entries.size }?.entries.orEmpty()
    }

    private fun parseSmallRoman(token: String): Int? {
        val lower = token.lowercase()
        if (!lower.matches(Regex("^[ivxlcdm]{1,6}$"))) return null
        var total = 0
        var prev = 0
        for (c in lower.reversed()) {
            val v = when (c) {
                'i' -> 1; 'v' -> 5; 'x' -> 10; 'l' -> 50; 'c' -> 100; 'd' -> 500; 'm' -> 1000
                else -> return null
            }
            if (v < prev) total -= v else { total += v; prev = v }
        }
        return total.takeIf { it in 1..200 }
    }

    private fun findAnchorPage(
        title: String,
        searchPages: List<BookPage>,
        byPage: Map<Int, List<SourceBlock>>,
    ): Int? {
        val normTarget = normalizeComparable(title)
        if (normTarget.length < 3) return null
        val strippedNumTarget = normalizeComparable(title.replace(Regex("""^(?:Chapter|Section|\d+(?:\.\d+)*)[.:\s]+""", RegexOption.IGNORE_CASE), ""))
        for (page in searchPages) {
            val blocks = byPage[page.number].orEmpty()
            // Inspect top 5 blocks or first 8 lines of page for heading match.
            val topLines = page.text.lineSequence().take(10).map(::cleanTocTitle).filter(String::isNotEmpty).toList()
            if (topLines.any { line ->
                    val n = normalizeComparable(line)
                    n == normTarget || (normTarget.length >= 6 && n.startsWith(normTarget) && n.length <= normTarget.length + 8)
                }
            ) {
                return page.number
            }
            if (blocks.take(4).any { b ->
                    val first = cleanTocTitle(b.text.lineSequence().firstOrNull().orEmpty())
                    val n = normalizeComparable(first)
                    n == normTarget || (strippedNumTarget.length >= 8 && n == strippedNumTarget)
                }
            ) {
                return page.number
            }
        }
        return null
    }

    // ---- Stage C: Typography Clustering + Slide/Academic/OCR Heuristics ----

    private data class StageCResult(
        val nodes: List<BookTocNode>,
        val source: HeadingSource,
        val confidence: Double,
    )

    private fun deriveTypographyFromLayouts(pages: List<BookPage>): Map<Int, List<PageLineTypography>> = buildMap {
        for (page in pages) {
            val layout = page.textLayout ?: continue
            if (layout.boxes.isEmpty() || page.text.isBlank()) continue
            val out = mutableListOf<PageLineTypography>()
            var offset = 0
            for (rawLine in page.text.split('\n')) {
                val end = offset + rawLine.length
                val trimmed = rawLine.trim()
                if (trimmed.isNotEmpty()) {
                    val boxes = layout.boxes.filter { it.startOffset >= offset && it.endOffset <= end && it.bounds.height > 0 }
                    if (boxes.isNotEmpty()) {
                        val heights = boxes.map { it.bounds.height * layout.pageHeight }.sorted()
                        val medianPt = heights[heights.size / 2]
                        val yNorm = boxes.map { it.bounds.y }.average().coerceIn(0.0, 1.0)
                        out += PageLineTypography(page.number, trimmed, trimmed, medianPt, false, yNorm)
                    }
                }
                offset = end + 1
            }
            if (out.isNotEmpty()) put(page.number, out)
        }
    }

    // In prose collections the unnumbered works can contain numbered chapters of their own.
    // Require isolated titles separated by sustained prose; dense lists and verse are ambiguous.
    private val isolatedHanTitle = Regex(
        """(?:\A|\n[\t \p{Zs}]*\n)[\t \p{Zs}]*([\p{IsHan}a-zA-Z]{1,24})[\t \p{Zs}]*(?=\n[\t \p{Zs}]*\n|\n?\z)""",
    )
    private val subdivisionNumber = Regex("""[零〇一二三四五六七八九十百千两兩]+""")
    private val localNoteLabel = setOf("注", "注释", "注釋", "注解", "註", "註釋", "附注", "附註")

    private fun textCollectionHeadings(pages: List<BookPage>): List<RawCandidate> {
        val found = mutableListOf<RawCandidate>()
        val titles = mutableSetOf<String>()
        var pageStart = 0
        var lastTitle: Int? = null
        var numberedChapterOwner: Int? = null
        for (page in pages) {
            val endOfWork = BookBoundaries.gutenbergEndOffset(page.text)
            val body = endOfWork?.let { page.text.substring(0, it) } ?: page.text
            val nestedHeadings = mutableSetOf<String>()
            for (match in isolatedHanTitle.findAll(body)) {
                val group = match.groups[1] ?: continue
                val title = group.value
                if (title.all { it.code < 128 } || title in localNoteLabel) continue
                val label = classifyOutlineLabel(title)
                if (label.kind != TocNodeKind.CHAPTER || label.isScaffoldNoise) continue
                val numbered = label.numberLabel != null || subdivisionNumber.matches(title)
                if (numbered) {
                    if (titles.isEmpty()) return emptyList()
                    if (!subdivisionNumber.matches(title)) {
                        if (numberedChapterOwner != null && numberedChapterOwner != titles.size) return emptyList()
                        numberedChapterOwner = titles.size
                        nestedHeadings += title
                    }
                } else {
                    val position = pageStart + group.range.first
                    if ((lastTitle != null && position - lastTitle < 400) || !titles.add(title)) return emptyList()
                    lastTitle = position
                }
                val kind = if (numbered) TocNodeKind.SECTION else TocNodeKind.CHAPTER
                found += RawCandidate(title, if (numbered) 2 else 1, page.number, null,
                    HeadingSource.TEXT_LAYOUT, kind, group.range.first)
            }
            if (BookText.explicitHeadings(body).any { it.value.trim() !in nestedHeadings }) return emptyList()
            if (endOfWork != null) {
                found += RawCandidate("License", 1, page.number, null,
                    HeadingSource.TEXT_LAYOUT, TocNodeKind.BACK_MATTER, endOfWork)
                break
            }
            pageStart += page.text.length
        }
        return if (titles.size >= 3) found else emptyList()
    }

    private val isolatedPrefixedVolumeTitle = Regex(
        """(?:\A|\n[\t \p{Zs}]*\n)[\t \p{Zs}]*(史記\s+[\p{IsHan}]{2,18}|本紀|表|書|世家|列傳)[\t \p{Zs}]*(?=\n[\t \p{Zs}]*\n|\n?\z)""",
    )

    private fun textChronicleVolumeHeadings(pages: List<BookPage>): List<RawCandidate> {
        val found = mutableListOf<RawCandidate>()
        var volumeCount = 0
        for (page in pages) {
            val endOfWork = BookBoundaries.gutenbergEndOffset(page.text)
            val body = endOfWork?.let { page.text.substring(0, it) } ?: page.text
            for (match in isolatedPrefixedVolumeTitle.findAll(body)) {
                val group = match.groups[1] ?: continue
                val title = group.value.trim()
                if (Regex("""^史記\s+[\p{IsHan}]{2,6}[著撰]$""").matches(title)) continue
                val isPart = ClassicalAnnotationLayer.isShijiPartTitle(title)
                if (!isPart) volumeCount++
                found += RawCandidate(
                    rawTitle = title,
                    rawLevel = if (isPart) 1 else 2,
                    page = page.number,
                    printedPage = null,
                    source = HeadingSource.TEXT_LAYOUT,
                    explicitKind = if (isPart) TocNodeKind.PART else TocNodeKind.CHAPTER,
                    startOffset = group.range.first,
                )
            }
            if (endOfWork != null) {
                found += RawCandidate(
                    "License", 1, page.number, null,
                    HeadingSource.TEXT_LAYOUT, TocNodeKind.BACK_MATTER, endOfWork,
                )
                break
            }
        }
        return if (volumeCount >= 5) found else emptyList()
    }

    private fun runStageC(
        bookId: String,
        bookTitle: String,
        pages: List<BookPage>,
        pageRoles: Map<Int, BookPageRole>,
        byPage: Map<Int, List<SourceBlock>>,
        typographyLines: Map<Int, List<PageLineTypography>>,
        repeatingEdges: Set<String>,
        totalPages: Int,
        format: String,
        openings: List<BookOutlineItem>,
    ): StageCResult {
        if (format == "txt") {
            val headings = textCollectionHeadings(pages).ifEmpty { textChronicleVolumeHeadings(pages) }
            if (headings.isNotEmpty()) {
                return StageCResult(buildCanonicalNodes(bookId, totalPages, headings, 0.9, bookTitle), HeadingSource.TEXT_LAYOUT, 0.9)
            }
        }
        if (openings.isNotEmpty()) {
            val raw = openings.map { RawCandidate(it.title, 1, it.page, null, it.source, TocNodeKind.CHAPTER) }
            return StageCResult(buildCanonicalNodes(bookId, totalPages, raw, 0.90), HeadingSource.HEADING_RULE, 0.90)
        }
        val bodyPages = pages.filter { (pageRoles[it.number] ?: BookPageRole.BODY) == BookPageRole.BODY }
        val activePages = bodyPages.ifEmpty { pages.filter { pageRoles[it.number] != BookPageRole.TOC } }
        if (activePages.isEmpty()) return StageCResult(emptyList(), HeadingSource.FALLBACK_LENGTH, 0.0)

        // Check Lecture Slides / Handout mode first when typography exists.
        if (typographyLines.isNotEmpty() && activePages.size >= 5) {
            val slideResult = tryDetectSlideDeck(bookId, activePages, typographyLines, repeatingEdges, totalPages)
            if (slideResult != null) return slideResult
        }

        // Academic / Monograph mode (typography + negative-filtered rules)
        val allTypoLines = activePages.flatMap { typographyLines[it.number].orEmpty() }
        val bodyFontSizes = allTypoLines
            .filter { ExplainClassifier.nonWhitespaceLength(it.text) >= 40 }
            .map { it.fontSizePt }
            .sorted()
        val bodyMedianPt = if (bodyFontSizes.size >= 6) bodyFontSizes[bodyFontSizes.size / 2] else 0.0

        val candidates = mutableListOf<RawCandidate>()
        var usedTypo = false

        val referenceHeadings = setOf("references", "bibliography", "works cited", "author contributions", "参考文献")
        val headingPages = if (format == "pdf" && bodyPages.isNotEmpty()) pages.filter {
            val role = pageRoles[it.number] ?: BookPageRole.BODY
            role == BookPageRole.BODY || role == BookPageRole.REFERENCES
        } else activePages
        for (page in headingPages) {
            val lines = typographyLines[page.number]
            if (!lines.isNullOrEmpty()) {
                val abstractIndex = if (format == "pdf" && page.number == 1)
                    lines.indexOfFirst { it.text.equals("Abstract", ignoreCase = true) } else -1
                for (idx in lines.indices) {
                    val line = lines[idx]
                    val cleaned = cleanTocTitle(line.text)
                    if (cleaned.isEmpty() || idx < abstractIndex) continue
                    if (format == "pdf" && cleaned.none(Char::isLetter)) continue
                    if (pageRoles[page.number] == BookPageRole.REFERENCES && cleaned.lowercase() !in referenceHeadings) continue
                    val enlarged = bodyMedianPt > 0.0 && line.fontSizePt >= bodyMedianPt * 1.18
                    val slightlyEnlarged = bodyMedianPt > 0.0 && line.fontSizePt >= bodyMedianPt * 1.05
                    val prominent = enlarged || (line.bold && (bodyMedianPt <= 0.0 || line.fontSizePt >= bodyMedianPt * 0.98))
                    val edgeKeys = if (format != "pdf" || line.yNorm < 0.10 || line.yNorm > 0.90) repeatingEdges else emptySet()
                    if (isRejectedHeadingCandidate(cleaned, edgeKeys, prominent)) continue
                    if (page.number == 1 && !prominent && !isAcademicOrNumberedHeading(cleaned)) continue

                    val matchedRule = BookStructurePlanner.matchHeadingRule(cleaned, false)
                    val isNumbered = isAcademicOrNumberedHeading(cleaned)
                    val isAcademicName = cleaned.lowercase().trimEnd(':') in ACADEMIC_NAMED_HEADINGS

                    // Skip page-1 title/author/affiliation clutter if it's before Abstract or numbered section 1 unless it's clearly a real section heading.
                    val accepted = when {
                        isNumbered && (bodyMedianPt <= 0.0 || line.fontSizePt >= bodyMedianPt * 0.96 || line.bold) -> true
                        isAcademicName && (prominent || idx <= 4 || cleaned.length <= 30) -> true
                        enlarged && ExplainClassifier.nonWhitespaceLength(cleaned) in 3..75 &&
                            !(page.number == 1 && idx <= 5 && !isNumbered && !isAcademicName) -> true
                        matchedRule != null && (slightlyEnlarged || line.bold || bodyMedianPt <= 0.0) -> true
                        else -> false
                    }
                    if (accepted) {
                        if (enlarged || line.bold) usedTypo = true
                        val level = matchedRule?.level ?: inferAcademicLevel(cleaned, if (enlarged) 1 else 2)
                        candidates += RawCandidate(cleaned, level, page.number, null,
                            if (usedTypo) HeadingSource.TYPOGRAPHY else HeadingSource.HEADING_RULE,
                            startOffset = if (format == "pdf") pdfHeadingOffset(page.text, line.text) else 0)
                    }
                }
            } else if (pageRoles[page.number] != BookPageRole.REFERENCES) {
                // No PDF font info (OCR / synthetic text): use strict negative-filtered block headings.
                for (block in byPage[page.number].orEmpty()) {
                    val rawFirstLine = block.text.lineSequence().firstOrNull().orEmpty().trim()
                    val firstLine = cleanTocTitle(rawFirstLine)
                    if (firstLine.isEmpty()) continue
                    if (!block.heading && isRejectedHeadingCandidate(firstLine, repeatingEdges, false)) continue
                    val matched = BookStructurePlanner.matchHeadingRule(rawFirstLine, block.heading)
                    val isAcademicName = firstLine.lowercase().trimEnd(':') in ACADEMIC_NAMED_HEADINGS
                    if (matched != null) {
                        candidates += RawCandidate(matched.title, matched.level, page.number, null, HeadingSource.HEADING_RULE, startOffset = block.start)
                    } else if (isAcademicName && ExplainClassifier.nonWhitespaceLength(block.text) <= 60) {
                        candidates += RawCandidate(firstLine, 1, page.number, null, HeadingSource.HEADING_RULE, startOffset = block.start)
                    }
                }
            }
        }

        val filtered = filterMonotonicNumberedCandidates(candidates)
        if (filtered.isEmpty()) {
            val fallbackSource = if (format == "docx") HeadingSource.WORD_STYLE else HeadingSource.FALLBACK_LENGTH
            return StageCResult(emptyList(), fallbackSource, 0.0)
        }
        val nodes = buildCanonicalNodes(bookId, totalPages, filtered, confidence = 0.90, bookTitle = bookTitle)
        val coveredBodyPages = activePages.count { p -> nodes.any { p.number in it.startPage..it.endPage } }
        val coverage = coveredBodyPages.toDouble() / activePages.size.coerceAtLeast(1)
        val bodyNodesCount = nodes.count { it.kind in BODY_NODE_KINDS }
        val densityOk = nodes.size <= maxOf(4, activePages.size * 2)
        val conf = if (bodyNodesCount >= 3 && coverage >= 0.50 && densityOk) 0.90 else 0.65
        val source = if (usedTypo) HeadingSource.TYPOGRAPHY else HeadingSource.HEADING_RULE
        return StageCResult(nodes.map { it.copy(confidence = conf) }, source, conf)
    }

    private fun tryDetectSlideDeck(
        bookId: String,
        activePages: List<BookPage>,
        typographyLines: Map<Int, List<PageLineTypography>>,
        repeatingEdges: Set<String>,
        totalPages: Int,
    ): StageCResult? {
        val nonWsCounts = activePages.map { ExplainClassifier.nonWhitespaceLength(it.text) }.sorted()
        val medianChars = nonWsCounts[nonWsCounts.size / 2]
        if (medianChars > 850) return null

        val allBodyPts = activePages.flatMap { typographyLines[it.number].orEmpty() }
            .filter { it.text.length >= 15 }
            .map { it.fontSizePt }
            .sorted()
        val medianPt = if (allBodyPts.isNotEmpty()) allBodyPts[allBodyPts.size / 2] else 12.0

        val slideTitles = mutableListOf<Pair<Int, String>>()
        for (page in activePages) {
            val lines = typographyLines[page.number].orEmpty()
            if (lines.isEmpty()) continue
            val topLines = lines.filter { it.yNorm <= 0.34 && it.text.length in 3..90 }
            val candidate = topLines
                .filterNot { isRejectedHeadingCandidate(it.text, repeatingEdges, boldOrEnlarged = true) }
                .filterNot { it.text.equals("Outline", true) && page.number > 3 }
                .maxByOrNull { line ->
                    (if (line.fontSizePt >= medianPt * 1.15) line.fontSizePt * 2.0 else line.fontSizePt) +
                        (if (line.bold) 4.0 else 0.0) - line.yNorm * 10.0
                }
            if (candidate != null && (candidate.fontSizePt >= medianPt * 1.15 || candidate.bold || lines.size <= 12)) {
                val clean = normalizeSlideTitle(candidate.text)
                if (clean.length >= 3) slideTitles += page.number to clean
            }
        }
        if (slideTitles.size * 2 < activePages.size) return null

        // Merge consecutive slides sharing the same topic title so slides group into logical sections cleanly.
        val grouped = mutableListOf<RawCandidate>()
        var lastKey = ""
        for ((pageNum, title) in slideTitles) {
            val key = normalizeComparable(title)
            if (key.isEmpty() || key == lastKey) continue
            lastKey = key
            grouped += RawCandidate(title, 1, pageNum, null, HeadingSource.TYPOGRAPHY, TocNodeKind.CHAPTER)
        }
        if (grouped.size < 3) return null
        val nodes = buildCanonicalNodes(bookId, totalPages, grouped, confidence = 0.92)
        return StageCResult(nodes, HeadingSource.TYPOGRAPHY, 0.92)
    }

    private fun normalizeSlideTitle(raw: String): String =
        cleanTocTitle(raw)
            .replace(Regex("""\s*[\(\[（【](?:cont\.?|continued|\d+/\d+)[\)\]）】]\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(\d+\)\s*$"""), "")
            .trim()

    private fun pdfHeadingOffset(text: String, title: String): Int {
        if (title.isBlank() || title.length > 300) return 0
        val words = title.trim().split(Regex("""\s+""")).joinToString("""\s+""", transform = Regex::escape)
        // The bookmark may omit the printed section number. Only trust a complete heading line.
        val heading = Regex("""(?im)^[\t ]*(?:\d+(?:\.\d+)*\.?[\t ]+)?$words[\t ]*\r?$""")
        return heading.findAll(text).take(2).singleOrNull()?.range?.first ?: 0
    }

    private fun isAcademicOrNumberedHeading(line: String): Boolean {
        if (SUBSECTION_3.matches(line) || SECTION_2.matches(line) || APPENDIX_TITLE.matches(line)) return true
        if (CHAPTER_EXPLICIT.matches(line) || CN_CHAPTER_PREFIX.matches(line) || CN_SECTION_PREFIX.matches(line)) return true
        if (CN_NUM_L1.matches(line) || CN_NUM_L2.matches(line)) return true
        if (line.matches(Regex("""^\d{1,2}\.\s+[A-Z\p{IsHan}].{1,75}$"""))) return true
        if (line.matches(Regex("""^[IVXLC]{1,4}\.\s+[A-Z].{2,75}$"""))) return true
        return false
    }

    private fun inferAcademicLevel(line: String, defaultLevel: Int): Int = when {
        SUBSECTION_3.matches(line) -> 3
        SECTION_2.matches(line) || CN_SECTION_PREFIX.matches(line) || CN_NUM_L2.matches(line) -> 2
        CHAPTER_EXPLICIT.matches(line) || CHAPTER_LEADING_NUM.matches(line) || CN_CHAPTER_PREFIX.matches(line) ||
            CN_NUM_L1.matches(line) || APPENDIX_TITLE.matches(line) -> 1
        else -> defaultLevel
    }

    private fun filterMonotonicNumberedCandidates(candidates: List<RawCandidate>): List<RawCandidate> {
        if (candidates.size < 3) return candidates
        var lastMajor = 0
        val out = ArrayList<RawCandidate>(candidates.size)
        for (cand in candidates) {
            val numMatch = Regex("""^(\d{1,2})(?:\.(\d{1,2}))?""").find(cand.rawTitle)
            if (numMatch != null) {
                val major = numMatch.groupValues[1].toIntOrNull() ?: 0
                if (major in 1..99) {
                    // Reject isolated backward jumps or sudden wild jumps > +6 when we already have a sequential series.
                    if (lastMajor > 0 && (major < lastMajor - 1 || major > lastMajor + 8)) {
                        continue
                    }
                    lastMajor = major
                }
            }
            out += cand
        }
        return out
    }

    // ---- Decoupled AI Study Chunks Builder (400-char merge + 4000-char sentence split) ----

    internal fun buildStudyChunks(
        bookId: String,
        bookTitle: String,
        pages: List<BookPage>,
        pageRoles: Map<Int, BookPageRole>,
        tocNodes: List<BookTocNode>,
        byPage: Map<Int, List<SourceBlock>>,
        nativeBodyStart: BookPageAnchor? = null,
        edited: Boolean = false,
    ): Triple<List<BookStudyChunk>, List<BookSection>, List<BookLesson>> {
        val bodyPages = pageRoles.filterValues { it == BookPageRole.BODY }.keys
        val activePages = if (bodyPages.isNotEmpty()) bodyPages
        else pageRoles.filterValues { it != BookPageRole.BLANK && it != BookPageRole.TOC && it != BookPageRole.COPYRIGHT }.keys
            .ifEmpty { pages.map { it.number }.toSet() }

        val pageTextByNum = pages.associate { it.number to it.text }
        val firstBodyAnchor = nativeBodyStart
            ?: BookBoundaries.openingNode(tocNodes)?.let { open ->
                val hasPrecedingFrontMatter = tocNodes.any { node ->
                    node.kind == TocNodeKind.FRONT_MATTER &&
                        (node.startPage < open.startPage || (node.startPage == open.startPage && node.startOffset < open.startOffset))
                }
                val pageText = pageTextByNum[open.startPage].orEmpty()
                val prefix = if (open.startOffset in 1..pageText.length) pageText.substring(0, open.startOffset) else ""
                val hasLeadingBoilerplate = prefix.isNotEmpty() && (
                    ExplainClassifier.copyrightMarkerCount(prefix) >= 1 ||
                        BookBoundaries.snapToCleanBodyOffset(pageText, 0, skipGutenbergHeader = true) > 0
                    )
                if (hasPrecedingFrontMatter || hasLeadingBoilerplate) BookPageAnchor(open.startPage, open.startOffset) else null
            }

        val activeBlocks = pages.asSequence()
            .filter { it.number in activePages }
            .flatMap { byPage[it.number].orEmpty().asSequence() }
            .mapNotNull { rawBlock ->
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
                val trimmed = block.text.trim()
                if (trimmed.isEmpty() || PRINTED_PAGE_MARK.matches(trimmed)) return@mapNotNull null
                val kind = ExplainClassifier.classifyBlock(block)
                if (kind != ExplainBlockKind.PAGE_NUMBER && kind != ExplainBlockKind.HEADER_FOOTER && !ExplainClassifier.isCopyrightBlock(trimmed)) {
                    block
                } else null
            }
            .toList()

        if (activeBlocks.isEmpty()) return Triple(emptyList(), emptyList(), emptyList())

        val bodyNodes = tocNodes.filter { it.kind in BODY_NODE_KINDS }
        val nodeById = tocNodes.associateBy { it.id }

        fun breadcrumbFor(node: BookTocNode): String {
            val chain = ArrayDeque<String>()
            var cur: BookTocNode? = node
            val guard = HashSet<String>()
            while (cur != null && guard.add(cur.id)) {
                chain.addFirst(cur.title)
                cur = cur.parentId?.let(nodeById::get)
            }
            val list = buildList {
                add(bookTitle)
                for (item in chain) if (lastOrNull() != item) add(item)
            }
            return list.joinToString(" › ")
        }

        data class RawGroup(
            val tocNodeId: String?,
            val title: String,
            val parentId: String?,
            val breadcrumb: String,
            val level: Int,
            val blocks: MutableList<SourceBlock>,
        )

        val rawGroups = mutableListOf<RawGroup>()
        if (bodyNodes.isNotEmpty()) {
            val nodesByPage = bodyNodes.groupBy { it.startPage }
            var currentNodeIdx = -1
            var currentGroup: RawGroup? = null
            val consumedNodes = HashSet<String>()
            var lastPage = -1

            fun activateNode(node: BookTocNode): RawGroup {
                consumedNodes += node.id
                val g = RawGroup(
                    tocNodeId = node.id,
                    title = node.title,
                    parentId = node.parentId,
                    breadcrumb = breadcrumbFor(node),
                    level = node.level,
                    blocks = mutableListOf(),
                )
                rawGroups += g
                currentGroup = g
                return g
            }

            fun ensureFallbackGroup(): RawGroup = currentGroup ?: RawGroup(
                tocNodeId = null,
                title = bookTitle,
                parentId = null,
                breadcrumb = bookTitle,
                level = 1,
                blocks = mutableListOf(),
            ).also { rawGroups += it; currentGroup = it }

            if (edited) {
                val firstPage = activeBlocks.first().page
                val preceding = bodyNodes.indexOfLast { it.startPage < firstPage && it.endPage >= firstPage }
                if (preceding >= 0) {
                    currentNodeIdx = preceding
                    activateNode(bodyNodes[preceding])
                }
            }
            for (block in activeBlocks) {
                if (block.page != lastPage) {
                    lastPage = block.page
                    // If a TOC node begins on this page without a verbatim matching heading block, still start it cleanly.
                    val pageNodes = nodesByPage[block.page].orEmpty()
                    for (node in pageNodes) {
                        if (node.id in consumedNodes || node.usesSourceOffset) continue
                        val appearsOnPage = byPage[block.page].orEmpty().any { b ->
                            titlesMatch(cleanTocTitle(b.text), node.title)
                        }
                        if (!appearsOnPage) {
                            currentNodeIdx = bodyNodes.indexOf(node)
                            activateNode(node)
                        }
                    }
                }
                val pageNodes = nodesByPage[block.page].orEmpty()
                val anchoredNodes = pageNodes.filter { node ->
                    node.usesSourceOffset && node.id !in consumedNodes && node.startOffset <= block.start
                }
                var anchoredHeadingMatched = false
                for (node in anchoredNodes.sortedBy { it.startOffset }) {
                    currentNodeIdx = bodyNodes.indexOf(node)
                    activateNode(node)
                    if (!block.heading && titlesMatch(cleanTocTitle(block.text), node.title)) {
                        anchoredHeadingMatched = true
                    }
                }
                val matchedNode = pageNodes.firstOrNull { node ->
                    !node.usesSourceOffset && node.id !in consumedNodes &&
                        titlesMatch(cleanTocTitle(block.text), node.title)
                }
                if (matchedNode != null) {
                    currentNodeIdx = bodyNodes.indexOf(matchedNode)
                    val g = activateNode(matchedNode)
                    g.blocks += if (block.heading) block else block.copy(heading = true)
                } else {
                    // Advance monotonically if current block moved past currentNode.endPage
                    val active = currentGroup
                    val activeNode = active?.tocNodeId?.let(nodeById::get)
                    if (activeNode != null && block.page > activeNode.endPage) {
                        val nextNode = bodyNodes.drop(currentNodeIdx + 1).firstOrNull { block.page in it.startPage..it.endPage }
                        if (nextNode != null && nextNode.id !in consumedNodes) {
                            currentNodeIdx = bodyNodes.indexOf(nextNode)
                            activateNode(nextNode)
                        }
                    }
                    ensureFallbackGroup().blocks += if (anchoredHeadingMatched) block.copy(heading = true) else block
                }
            }
        } else {
            // No TOC nodes -> single fallback group
            rawGroups += RawGroup(null, bookTitle, null, bookTitle, 1, activeBlocks.toMutableList())
        }

        val nonEmpty = rawGroups.filter { g -> g.blocks.any { !it.heading && it.text.isNotBlank() } }
        val base = nonEmpty.ifEmpty {
            val fallbackBlocks = activeBlocks.filter { it.isBodyParagraph && it.text.isNotBlank() }
            if (fallbackBlocks.isEmpty()) emptyList()
            else listOf(RawGroup(null, bookTitle, null, bookTitle, 1, fallbackBlocks.toMutableList()))
        }

        // Short sibling merge (< 400 chars under same parent) - ONLY in studyChunks, NEVER in book_toc_nodes!
        val merged = mutableListOf<RawGroup>()
        val mergeableKinds = setOf(TocNodeKind.SECTION, TocNodeKind.SUBSECTION)
        for (sec in base) {
            val bodyLen = BookStructurePlanner.bodyCharsPublic(sec.blocks)
            val prev = merged.lastOrNull()
            val canMerge = prev != null &&
                prev.parentId != null &&
                prev.parentId == sec.parentId &&
                prev.tocNodeId?.let(nodeById::get)?.kind in mergeableKinds &&
                sec.tocNodeId?.let(nodeById::get)?.kind in mergeableKinds &&
                (BookStructurePlanner.bodyCharsPublic(prev.blocks) < BookStructurePlanner.MIN_SUBSECTION_CHARS ||
                    bodyLen < BookStructurePlanner.MIN_SUBSECTION_CHARS) &&
                BookStructurePlanner.bodyCharsPublic(prev.blocks) + bodyLen <= BookStructurePlanner.MAX_SECTION_CHARS
            if (canMerge && prev != null) {
                prev.blocks.addAll(sec.blocks)
                val combinedTitle = if (sec.title in prev.title) prev.title else "${prev.title} · ${sec.title}"
                val combinedBreadcrumb = prev.breadcrumb.substringBeforeLast(" › ", "")
                    .let { prefix -> if (prefix.isEmpty()) combinedTitle else "$prefix › $combinedTitle" }
                merged[merged.lastIndex] = prev.copy(title = combinedTitle, breadcrumb = combinedBreadcrumb)
            } else {
                merged += sec
            }
        }

        val studyChunks = mutableListOf<BookStudyChunk>()
        val finalSections = mutableListOf<BookSection>()
        val finalLessons = mutableListOf<BookLesson>()
        var ordinal = 0
        for (sec in merged) {
            val chunks = BookStructurePlanner.splitOversizedPublic(sec.blocks)
            chunks.forEachIndexed { idx, chunkBlocks ->
                val chunkTitle = if (chunks.size == 1) sec.title else "${sec.title}（${idx + 1}/${chunks.size}）"
                val chunkBreadcrumb = if (chunks.size == 1) sec.breadcrumb else "${sec.breadcrumb}（${idx + 1}/${chunks.size}）"
                val id = chunkBlocks.first().id
                val startPage = chunkBlocks.first().page
                val endPage = chunkBlocks.last().page
                val blockIds = chunkBlocks.map { it.id }
                val chars = BookStructurePlanner.bodyCharsPublic(chunkBlocks)
                studyChunks += BookStudyChunk(
                    id = id,
                    tocNodeId = sec.tocNodeId,
                    ordinal = ordinal++,
                    title = chunkTitle,
                    breadcrumb = chunkBreadcrumb,
                    level = sec.level,
                    startPage = startPage,
                    endPage = endPage,
                    charCount = chars,
                    blockIds = blockIds,
                )
                finalSections += BookSection(id, chunkTitle, chunkBreadcrumb, sec.level, startPage, endPage, blockIds)
                finalLessons += BookLesson(id, chunkTitle, chunkBlocks, chunkBreadcrumb)
            }
        }

        return Triple(studyChunks, finalSections, finalLessons)
    }

    private fun normalizeComparable(text: String): String =
        cleanTocTitle(text)
            .replace(Regex("""[\s　·.．、:：\-–—]+"""), "")
            .lowercase()

    private fun titlesMatch(blockText: String, targetTitle: String): Boolean {
        val firstLine = cleanTocTitle(blockText.lineSequence().firstOrNull().orEmpty())
        if (firstLine.isEmpty() || ExplainClassifier.nonWhitespaceLength(firstLine) > 85) return false
        val a = normalizeComparable(firstLine)
        val b = normalizeComparable(targetTitle)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || (b.length >= 4 && a.startsWith(b) && a.length <= b.length + 8)
    }
}
