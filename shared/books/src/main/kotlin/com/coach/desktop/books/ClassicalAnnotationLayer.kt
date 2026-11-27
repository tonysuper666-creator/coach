package com.coach.desktop.books

/**
 * 中文古籍与文言译本分层标记与上下文构造器：
 * - 识别正文/经文、王弼〈〉注文、庄子「一作某」异文、严复「復案」按语、史记「【索隱述贊】」、以及「一之一」章号；
 * - 保持 `pages.text` 原始 UTF-16 偏移不变，仅提供只读分层标签与讲解追问上下文。
 */
internal enum class ClassicalTextLayer(val label: String) {
    MAIN_TEXT("正文"),
    COMMENTARY("注文/按语"),
    TEXTUAL_VARIANT("异文"),
    CHAPTER_NUMBER("章号"),
}

internal data class ClassicalLayerSpan(
    val layer: ClassicalTextLayer,
    val label: String,
    val startUtf16: Int,
    val endUtf16: Int,
)

internal object ClassicalAnnotationLayer {
    private val chapterNumberLineRegex = Regex("""^[零〇一二三四五六七八九十百]+之[零〇一二三四五六七八九十百]+$""")
    private val inlineVariantRegex = Regex("""一作[「『]?[^」』\s，。；！？]{1,8}[」』]?""")
    private val angleNoteRegex = Regex("""〈([^〉]{4,})〉""")
    private val fuanNoteRegex = Regex("""(?:^|[（(\s\u3000])(復案[：:][^）\n]*)""")
    private val soyinTagRegex = Regex("""【(?:索隱述贊|索隱|正義|集解)】""")
    private val collationSectionTitleRegex = Regex("""^(?:校勘記|經典釋文|老子[道德]經音義|音義|注釋|註釋)$""")
    private val annotatorLineRegex = Regex("""^(?:[晉晋漢汉唐宋清魏][\s\u3000]+)?([\p{IsHan}]{2,6})\s*[注註疏釋譯撰著]\.?$""")
    private val bookTitleParenRegex = Regex("""[（(]([^）)]{2,12}(?:注本?|本|釋|譯))[）)]""")
    private val prefaceAuthorRegex = Regex("""([\p{IsHan}]{2,4})\s*序$""")
    private val classicalChapterTitleRegex = Regex(
        """^(?:第?[零〇一二三四五六七八九十百千]+章|導言[零〇一二三四五六七八九十百]+|論[零〇一二三四五六七八九十百]+|[\p{IsHan}]{1,6}第[一二三四五六七八九十百]+)$""",
    )
    private val shijiPartTitleRegex = Regex("""^(?:本紀|表|書|世家|列傳)$""")

    fun isClassicalChapterNumberLine(line: String): Boolean =
        chapterNumberLineRegex.matches(line.trim())

    fun isClassicalChapterTitle(title: String): Boolean =
        classicalChapterTitleRegex.matches(title.trim())

    fun isShijiPartTitle(title: String): Boolean =
        shijiPartTitleRegex.matches(title.trim())

    fun isCollationOrNotesHeading(title: String): Boolean =
        collationSectionTitleRegex.matches(title.trim())

    fun classifyBlock(text: String, sectionTitle: String? = null): ClassicalTextLayer {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ClassicalTextLayer.MAIN_TEXT
        if (isClassicalChapterNumberLine(trimmed)) return ClassicalTextLayer.CHAPTER_NUMBER
        if (sectionTitle != null && isCollationOrNotesHeading(sectionTitle)) return ClassicalTextLayer.COMMENTARY
        if (soyinTagRegex.containsMatchIn(trimmed)) return ClassicalTextLayer.COMMENTARY
        if (trimmed.startsWith("〈") && trimmed.endsWith("〉") && trimmed.length >= 6) return ClassicalTextLayer.COMMENTARY
        if (trimmed.startsWith("復案：") || trimmed.startsWith("復案:") || trimmed.startsWith("（復案")) {
            return ClassicalTextLayer.COMMENTARY
        }
        if (fuanNoteRegex.containsMatchIn(trimmed)) return ClassicalTextLayer.COMMENTARY
        if (inlineVariantRegex.containsMatchIn(trimmed)) return ClassicalTextLayer.TEXTUAL_VARIANT
        if (angleNoteRegex.containsMatchIn(trimmed)) return ClassicalTextLayer.COMMENTARY
        return ClassicalTextLayer.MAIN_TEXT
    }

    fun extractLayerSpans(text: String): List<ClassicalLayerSpan> {
        if (text.isBlank()) return emptyList()
        val spans = ArrayList<ClassicalLayerSpan>()
        val trimmed = text.trim()
        if (isClassicalChapterNumberLine(trimmed)) {
            val start = text.indexOf(trimmed)
            if (start >= 0) spans += ClassicalLayerSpan(ClassicalTextLayer.CHAPTER_NUMBER, "章号", start, start + trimmed.length)
            return spans
        }
        for (m in angleNoteRegex.findAll(text)) {
            spans += ClassicalLayerSpan(ClassicalTextLayer.COMMENTARY, "注文〈〉", m.range.first, m.range.last + 1)
        }
        for (m in inlineVariantRegex.findAll(text)) {
            spans += ClassicalLayerSpan(ClassicalTextLayer.TEXTUAL_VARIANT, m.value, m.range.first, m.range.last + 1)
        }
        for (m in fuanNoteRegex.findAll(text)) {
            val g = m.groups[1] ?: continue
            spans += ClassicalLayerSpan(ClassicalTextLayer.COMMENTARY, "復案按语", g.range.first, g.range.last + 1)
        }
        for (m in soyinTagRegex.findAll(text)) {
            spans += ClassicalLayerSpan(ClassicalTextLayer.COMMENTARY, m.value, m.range.first, m.range.last + 1)
        }
        return spans.sortedBy { it.startUtf16 }
    }

    fun detectBookAnnotator(book: Textbook, currentPage: Int): String? {
        val candidates = LinkedHashSet<String>()
        bookTitleParenRegex.find(book.title)?.groupValues?.getOrNull(1)?.let { candidates += it }
        val scanPages = book.pages.filter { it.number <= 3 || it.number in (currentPage - 2).coerceAtLeast(1)..currentPage }
        for (page in scanPages) {
            for (rawLine in page.text.lineSequence()) {
                val line = rawLine.trim().trimStart('#').trim()
                if (line.isEmpty() || line.length > 48) continue
                if (annotatorLineRegex.matches(line)) {
                    candidates += line.replace('\u3000', ' ').replace(Regex("\\s+"), " ").trimEnd('.')
                }
                if (line.startsWith("譯例言") || line.startsWith("自序")) continue
                prefaceAuthorRegex.find(line)?.let { m ->
                    if (line.length <= 16) candidates += m.groupValues[1]
                }
            }
        }
        val activePageText = book.page(currentPage)?.text.orEmpty()
        if (fuanNoteRegex.containsMatchIn(activePageText)) candidates += "嚴復按語（復案）"
        if (soyinTagRegex.containsMatchIn(activePageText)) candidates += "司馬貞【索隱述贊】"
        return candidates.takeIf { it.isNotEmpty() }?.joinToString("；")
    }

    fun buildExplainContext(book: Textbook, chosen: SourceBlock, section: BookLesson?): String? {
        val parts = ArrayList<String>()
        val chapter = BookChapterResolver.chapterForPage(book, chosen.page, chosen.start)
        val tocNode = BookBoundaries.activeTocNode(book.tocNodes, chosen.page, chosen.start, pageText = book.page(chosen.page)?.text)
        val volumeTitle = chapter?.title?.takeIf { it.isNotBlank() && it != book.title }
            ?: tocNode?.title?.takeIf { it.isNotBlank() && it != book.title }
        if (volumeTitle != null) {
            parts += "当前卷/篇题：$volumeTitle"
        }
        val chapterNum = extractPassageNumber(chosen, book, section)
        if (chapterNum != null) {
            parts += "章号：$chapterNum"
        }
        val annotator = detectBookAnnotator(book, chosen.page)
        val layer = classifyBlock(chosen.text, section?.title ?: volumeTitle)
        val spans = extractLayerSpans(chosen.text)
        if (annotator != null && (layer == ClassicalTextLayer.COMMENTARY || "〈" in chosen.text || "復案" in chosen.text || "索隱" in chosen.text)) {
            parts += "注者/按语归属：$annotator"
        } else if (annotator != null && isClassicalChineseBook(book)) {
            parts += "版本/注者：$annotator"
        }
        if (layer != ClassicalTextLayer.MAIN_TEXT || spans.isNotEmpty()) {
            val tagSummary = if (spans.isNotEmpty()) {
                spans.take(4).joinToString("、") { it.label }
            } else {
                layer.label
            }
            parts += "段落分层：${layer.label}（含 $tagSummary）"
        }
        if (layer == ClassicalTextLayer.COMMENTARY && section != null) {
            val idx = section.blocks.indexOfFirst { it.id == chosen.id }
            val prevMain = if (idx > 0) section.blocks.subList(0, idx).lastOrNull {
                !it.heading && classifyBlock(it.text) == ClassicalTextLayer.MAIN_TEXT
            } else null
            if (prevMain != null) {
                parts += "所注相邻正文：「${prevMain.text.trim().take(36)}」"
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("；")
    }

    private fun extractPassageNumber(chosen: SourceBlock, book: Textbook, section: BookLesson?): String? {
        chosen.text.lineSequence().map(String::trim).firstOrNull(::isClassicalChapterNumberLine)?.let { return it }
        val blocks = section?.blocks ?: book.sourceBlocks().filter { it.page == chosen.page }
        val idx = blocks.indexOfFirst { it.id == chosen.id }
        if (idx > 0) {
            val prev = blocks[idx - 1].text.trim()
            if (isClassicalChapterNumberLine(prev)) return prev
        }
        return null
    }

    private val classicalOnlyChapterTitleRegex = Regex(
        """^(?:[零〇一二三四五六七八九十百千]+章|導言[零〇一二三四五六七八九十百]+|論[零〇一二三四五六七八九十百]+|[\p{IsHan}]{1,6}第[一二三四五六七八九十百]+)$""",
    )

    fun isClassicalChineseBook(book: Textbook): Boolean {
        val samplePages = book.pages.take(6)
        val joined = samplePages.joinToString("\n") { it.text }
        if (angleNoteRegex.containsMatchIn(joined) || inlineVariantRegex.containsMatchIn(joined) ||
            fuanNoteRegex.containsMatchIn(joined) || soyinTagRegex.containsMatchIn(joined)
        ) {
            return true
        }
        if (samplePages.any { p -> p.text.lineSequence().any(::isClassicalChapterNumberLine) }) return true
        val tocTitles = book.tocNodes.map { it.title }
        if (tocTitles.any { classicalOnlyChapterTitleRegex.matches(it.trim()) || isShijiPartTitle(it) || it.startsWith("史記 ") }) {
            return true
        }
        return false
    }

    fun hasClassicalCommentaryOrVariants(book: Textbook): Boolean {
        val sample = book.pages.take(8).joinToString("\n") { it.text }
        return angleNoteRegex.containsMatchIn(sample) ||
            inlineVariantRegex.containsMatchIn(sample) ||
            fuanNoteRegex.containsMatchIn(sample) ||
            soyinTagRegex.containsMatchIn(sample)
    }
}
