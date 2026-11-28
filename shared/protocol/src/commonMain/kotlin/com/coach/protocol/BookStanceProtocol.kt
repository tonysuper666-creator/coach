package com.coach.protocol

/**
 * 路线 1 共用协议（TON-1976 / TON-1977，供阶段 2 库里提问与自动判类型复用）：
 * - 五种回答立场（wire 名、默认题头、跨类切换显示名）
 * - 三种回答层（book / outside / guess）与全部固定文案常量
 * - 跟着书走的两道限制标记（fiction / esoteric）
 * - 玄学书高危决定拦截关键词表（中英文）
 * - 单句切分与古籍正文/注疏切块规则
 */
enum class BookStance(val wire: String, val headerLabel: String) {
    KNOWLEDGE("knowledge", "按专业角度答"),
    LIFE("life", "按作者和原文答"),
    CLASSIC("classic", "按思想和注疏答"),
    ESOTERIC("esoteric", "按本书体系答"),
    ORIGINAL("original", "只按原文答"),
    ;

    /**
     * 题头和通用立场显示文案：
     * 知识立场在古籍书上显示为「历史学角度」，在玄学书上显示为「文献和思想史角度」，规则仍按知识类。
     */
    fun labelForBook(defaultStance: BookStance? = null, guards: Set<BookStanceGuard> = emptySet()): String {
        if (this != KNOWLEDGE) return headerLabel
        return when {
            BookStanceGuard.ESOTERIC in guards || defaultStance == ESOTERIC -> BookStanceCopy.KNOWLEDGE_ON_ESOTERIC_LABEL
            defaultStance == CLASSIC -> BookStanceCopy.KNOWLEDGE_ON_CLASSIC_LABEL
            else -> headerLabel
        }
    }

    /**
     * 卡底「换个角度」按钮文案（TON-1982）：
     * 带 `esoteric` 标记的书上，`original` 立场在「换个角度」里显示成「只看书里怎么讲」，
     * 切换后的新卡题头仍按 [labelForBook] 显示为「只按原文答」。
     */
    fun switchLabelForBook(defaultStance: BookStance? = null, guards: Set<BookStanceGuard> = emptySet()): String {
        if (this == ORIGINAL && BookStanceGuard.ESOTERIC in guards) {
            return BookStanceCopy.ORIGINAL_ON_ESOTERIC_SWITCH_LABEL
        }
        return labelForBook(defaultStance, guards)
    }

    companion object {
        fun fromWire(raw: String?): BookStance? {
            val normalized = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

enum class AnswerLayerKind(val wire: String) {
    BOOK("book"),
    OUTSIDE("outside"),
    GUESS("guess"),
    ;

    companion object {
        fun fromWire(raw: String?): AnswerLayerKind? {
            val normalized = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

enum class BookStanceGuard(val wire: String) {
    FICTION("fiction"),
    ESOTERIC("esoteric"),
    ;

    companion object {
        fun fromWire(raw: String?): BookStanceGuard? {
            val normalized = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

object BookStanceCopy {
    const val KNOWLEDGE_ON_CLASSIC_LABEL = "历史学角度"
    const val KNOWLEDGE_ON_ESOTERIC_LABEL = "文献和思想史角度"
    const val ORIGINAL_ON_ESOTERIC_SWITCH_LABEL = "只看书里怎么讲"

    const val BOOK_DEFAULT = "本书"
    const val BOOK_LIFE = "据原文转述"
    const val BOOK_CLASSIC_MAIN = "原文"
    const val BOOK_CLASSIC_NOTE = "注"
    const val BOOK_CLASSIC_COMMENTARY = "按语"

    const val OUTSIDE_PREFIX = "书外 · 未对照原文"
    const val GUESS_PREFIX = "推想 · 书外 · 未对照原文"
    val OUTSIDE_KINDS: Set<String> = setOf("通行讲法", "注家读法", "今人解读", "史学")

    const val ESOTERIC_SYSTEM_NOTICE = "按本书体系看，不是对现实的断言"
    const val HIGH_RISK_BOUNDARY = "high_risk"
    const val HIGH_RISK_NOTICE = "看病、法律、投资这类具体决定不从书里推，下面只讲书里怎么说。"

    const val NO_BOOK_DIRECT = "这本书里没有直接讲到这一点。"
    const val NO_BOOK_FICTION = "读到这里，书里还没讲到这一点。"
    const val SWITCH_PREFIX = "换个角度："

    fun outsideLabel(kind: String?): String {
        val clean = kind?.trim()?.takeIf { it in OUTSIDE_KINDS }
        return if (clean != null) "$OUTSIDE_PREFIX · $clean" else OUTSIDE_PREFIX
    }

    fun classicNoteLabel(annotator: String?, isCommentary: Boolean = false): String {
        val base = if (isCommentary) BOOK_CLASSIC_COMMENTARY else BOOK_CLASSIC_NOTE
        val clean = annotator?.trim()?.takeIf { it.isNotEmpty() }
        return if (clean != null) "$base · $clean" else base
    }

    fun noBookFallback(guards: Set<BookStanceGuard>): String =
        if (BookStanceGuard.FICTION in guards) NO_BOOK_FICTION else NO_BOOK_DIRECT

    fun noReadingRecord(bookTitle: String): String =
        "《${bookTitle.trim()}》还没有阅读记录，没有用它。"
}

/**
 * 玄学书高危决定本地关键词表（医疗、法律、投资的具体决定），中英文各一份。
 * 单本书与库里提问共用；命中后按高危直接回答（只讲书里怎么说，不出书外与推想，不替读者做决定）。
 */
object EsotericRiskKeywords {
    val zhKeywords: List<String> = listOf(
        // 医疗
        "停药", "用药", "吃药", "服药", "换药", "减药", "断药", "开药", "药方", "中药方",
        "降压药", "降糖药", "抗抑郁药", "处方药", "剂量", "诊断", "确诊", "手术",
        "化疗", "放疗", "住院", "就医", "看病", "治病", "治疗方案", "医嘱",
        "高血压", "糖尿病", "抑郁症", "癌症", "肿瘤",
        // 法律
        "起诉", "诉讼", "官司", "合同", "签合同", "违约", "毁约", "律师",
        "法院", "判决", "仲裁", "离婚协议", "遗嘱", "报案", "法律责任", "赔偿金额",
        // 投资
        "买入", "卖出", "加仓", "减仓", "清仓", "建仓", "满仓", "空仓", "仓位",
        "股票", "买股", "炒股", "基金", "买基金", "债券", "期货", "期权", "杠杆",
        "做多", "做空", "抄底", "逃顶", "止损", "止盈", "定投",
        "加密货币", "比特币", "以太坊", "虚拟币", "炒币", "外汇", "理财产品", "投资建议",
        "买房", "卖房", "贷款买",
    )

    private val zhPatterns: List<Regex> = listOf(
        Regex("""(?:不吃|停掉|停了|停用|少吃)[^，。！？\n]{0,6}药"""),
        Regex("""(?:买|卖|投|入)[^，。！？\n]{0,4}(?:股票|基金|比特币|虚拟币|加密货币|期货|期权)"""),
        Regex("""(?:能不能|要不要|该不该|是否)[^，。！？\n]{0,8}(?:停药|吃药|手术|起诉|签合同|买房|买股|买基金|加仓|清仓)"""),
    )

    val enKeywords: List<String> = listOf(
        // Medical
        "stop taking", "skip my meds", "off my meds", "medication", "prescription",
        "dosage", "blood pressure", "antihypertensive", "insulin", "antidepressant",
        "chemotherapy", "surgery", "diagnose", "diagnosis", "medical advice",
        "treatment plan", "cure cancer", "replace medicine",
        // Legal
        "sue", "lawsuit", "litigation", "contract", "legal advice",
        "attorney", "lawyer", "court case", "settlement", "divorce agreement",
        "breach of contract", "file charges", "plead guilty",
        // Investment
        "buy stock", "sell stock", "stocks", "invest in", "investment advice",
        "mutual fund", "index fund", "etf", "cryptocurrency", "crypto",
        "bitcoin", "ethereum", "forex", "options trading", "futures",
        "leverage", "short sell", "position size", "portfolio allocation", "margin call",
    )

    private val enPatterns: List<Regex> = enKeywords.map { kw ->
        Regex("""(?i)(?<![a-z0-9])${Regex.escape(kw)}(?![a-z0-9])""")
    }

    fun matches(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (zhKeywords.any { it in trimmed }) return true
        if (zhPatterns.any { it.containsMatchIn(trimmed) }) return true
        if (enPatterns.any { it.containsMatchIn(trimmed) }) return true
        return false
    }
}

/**
 * 逐句计数：按句末标点切分，忽略引号/括号内部标点、小数点、常见英文缩写和省略号。
 * 服务端门禁与 `tools/book-stance/check.py` 保持一致规则。
 */
object StanceSentenceSplitter {
    private val englishAbbrevRegex = Regex(
        """(?i)(?:^|[\s(（])(?:mr|mrs|ms|dr|prof|st|vs|etc|fig|eq|no|ch|vol|ed|pp|p|e\.g|i\.e|u\.s|u\.k|a\.m|p\.m|2e)$""",
    )

    fun countSentences(raw: String): Int {
        val text = raw.trim()
        if (text.isEmpty()) return 0
        var count = 0
        var hasContent = false
        var parenDepth = 0
        var quoteDepth = 0
        var inAsciiDouble = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when (ch) {
                '(', '（', '[', '【', '《', '〈' -> { parenDepth++; hasContent = true }
                ')', '）', ']', '】', '》', '〉' -> if (parenDepth > 0) parenDepth--
                '「', '『', '“', '‘' -> { quoteDepth++; hasContent = true }
                '」', '』', '”', '’' -> if (quoteDepth > 0) quoteDepth--
                '"' -> { inAsciiDouble = !inAsciiDouble; hasContent = true }
                '。', '！', '？', '!', '?' -> {
                    if (parenDepth == 0 && quoteDepth == 0 && !inAsciiDouble) {
                        if (hasContent) {
                            count++
                            hasContent = false
                        }
                        while (i + 1 < text.length && text[i + 1] in charArrayOf('。', '！', '？', '!', '?', '.', '」', '』', '”', '’', '）', ')')) {
                            val next = text[i + 1]
                            if (next in charArrayOf('」', '』', '”', '’') && quoteDepth > 0) quoteDepth--
                            if (next in charArrayOf('）', ')') && parenDepth > 0) parenDepth--
                            i++
                        }
                    } else {
                        hasContent = true
                    }
                }
                '.' -> {
                    val prev = text.getOrNull(i - 1)
                    val next = text.getOrNull(i + 1)
                    val isEllipsis = next == '.' || prev == '.'
                    val isDecimal = prev != null && prev.isDigit() && next != null && next.isDigit()
                    val prefix = text.substring(maxOf(0, i - 10), i)
                    val isAbbrev = englishAbbrevRegex.containsMatchIn(prefix)
                    val endsOrSpaced = next == null || next.isWhitespace() || next in charArrayOf('"', '”', '’', '」', '』', ')', '）')
                    if (!isEllipsis && !isDecimal && !isAbbrev && endsOrSpaced &&
                        parenDepth == 0 && quoteDepth == 0 && !inAsciiDouble
                    ) {
                        if (hasContent) {
                            count++
                            hasContent = false
                        }
                    } else {
                        hasContent = true
                    }
                }
                else -> if (!ch.isWhitespace()) hasContent = true
            }
            i++
        }
        if (hasContent) count++
        return count
    }
}

/**
 * 古籍书块切分与注家归属提取：
 * - 让 SourcePack 中每个 anchor 块只属于单一层次（正文 / 注 / 按语）
 * - 保持相对原块精确 UTF-16 偏移，不改动原页字符
 */
enum class ClassicalSpanKind {
    MAIN_TEXT,
    NOTE,
    COMMENTARY,
}

data class ClassicalSubBlock(
    val kind: ClassicalSpanKind,
    val annotator: String?,
    val startDeltaUtf16: Int,
    val endDeltaUtf16: Int,
    val text: String,
)

object ClassicalSourceSplitter {
    private val angleNoteRegex = Regex("""〈([^〉]{4,})〉""")
    private val fuanRegex = Regex("""(?:^|[（(\s\u3000])(復案[：:][^）\n]*)""")
    private val soyinRegex = Regex("""【(?:索隱述贊|索隱|正義|集解)】[^\n]*""")
    private val dynastyPrefixRegex = Regex("""^(?:[魏晉晋漢汉唐宋清梁齊齐周秦隋元明][\s\u3000]+)""")
    private val annotatorTailRegex = Regex("""(?:注本|註本|注|註|疏|釋|譯|撰|著|按語|按语|本)\.?$""")
    private val annotatorLineRegex = Regex("""(?:^|[：:；;\n])\s*(?:[魏晉晋漢汉唐宋清][\s\u3000]+)?([\p{IsHan}]{2,4})\s*(?:注本?|註本?|疏|釋|按語|按语)\.?""")

    fun extractAnnotatorName(annotationContext: String?, sampleText: String = ""): String? {
        val sources = listOfNotNull(annotationContext?.takeIf { it.isNotBlank() }, sampleText.takeIf { it.isNotBlank() })
        for (src in sources) {
            if ("王弼" in src) return "王弼"
            if ("嚴復" in src || "严复" in src || "復案" in src) return "嚴復"
            if ("司馬貞" in src || "司马贞" in src || "索隱" in src) return "司馬貞"
            if ("郭象" in src) return "郭象"
            if ("何晏" in src) return "何晏"
            if ("鄭玄" in src || "郑玄" in src) return "鄭玄"
            val m = annotatorLineRegex.find(src)
            if (m != null) {
                val clean = m.groupValues[1]
                    .replace(dynastyPrefixRegex, "")
                    .replace(annotatorTailRegex, "")
                    .trim()
                if (clean.length in 2..4 && clean !in setOf("版本", "注者", "按语", "按語", "正文", "注文")) {
                    return clean
                }
            }
        }
        return null
    }

    fun splitBlock(text: String, defaultAnnotator: String? = null): List<ClassicalSubBlock> {
        if (text.isBlank()) return emptyList()
        val trimmed = text.trim()
        val trimStart = text.indexOf(trimmed).coerceAtLeast(0)
        val trimEnd = trimStart + trimmed.length

        // Whole block is a single 〈...〉 note (typical of daodejing-wangbi.txt)
        if (trimmed.startsWith("〈") && trimmed.endsWith("〉") && trimmed.length >= 6 &&
            !trimmed.substring(1, trimmed.length - 1).trim().startsWith("一作")
        ) {
            return listOf(ClassicalSubBlock(ClassicalSpanKind.NOTE, defaultAnnotator, trimStart, trimEnd, trimmed))
        }
        if (trimmed.startsWith("復案：") || trimmed.startsWith("復案:") || trimmed.startsWith("（復案")) {
            return listOf(ClassicalSubBlock(ClassicalSpanKind.COMMENTARY, defaultAnnotator ?: "嚴復", trimStart, trimEnd, trimmed))
        }
        if (trimmed.startsWith("【索隱") || trimmed.startsWith("【正義】") || trimmed.startsWith("【集解】")) {
            val who = if ("索隱" in trimmed) (defaultAnnotator ?: "司馬貞") else defaultAnnotator
            return listOf(ClassicalSubBlock(ClassicalSpanKind.COMMENTARY, who, trimStart, trimEnd, trimmed))
        }

        data class Span(val start: Int, val end: Int, val kind: ClassicalSpanKind, val annotator: String?)
        val specialSpans = mutableListOf<Span>()
        for (m in angleNoteRegex.findAll(text)) {
            val inner = m.groupValues[1].trim()
            if (inner.startsWith("一作") || inner.startsWith("一無")) continue
            specialSpans += Span(m.range.first, m.range.last + 1, ClassicalSpanKind.NOTE, defaultAnnotator)
        }
        for (m in fuanRegex.findAll(text)) {
            val g = m.groups[1] ?: continue
            specialSpans += Span(g.range.first, g.range.last + 1, ClassicalSpanKind.COMMENTARY, defaultAnnotator ?: "嚴復")
        }
        for (m in soyinRegex.findAll(text)) {
            val who = if ("索隱" in m.value) (defaultAnnotator ?: "司馬貞") else defaultAnnotator
            specialSpans += Span(m.range.first, m.range.last + 1, ClassicalSpanKind.COMMENTARY, who)
        }
        if (specialSpans.isEmpty()) {
            return listOf(ClassicalSubBlock(ClassicalSpanKind.MAIN_TEXT, null, trimStart, trimEnd, trimmed))
        }

        specialSpans.sortBy { it.start }
        val merged = mutableListOf<Span>()
        for (sp in specialSpans) {
            if (merged.isEmpty() || sp.start >= merged.last().end) {
                merged += sp
            }
        }
        val out = mutableListOf<ClassicalSubBlock>()
        var cursor = 0
        fun emit(s: Int, e: Int, kind: ClassicalSpanKind, annotator: String?) {
            if (e <= s) return
            val raw = text.substring(s, e)
            val clean = raw.trim()
            if (clean.isEmpty()) return
            val rel = raw.indexOf(clean).coerceAtLeast(0)
            out += ClassicalSubBlock(kind, annotator, s + rel, s + rel + clean.length, clean)
        }
        for (sp in merged) {
            emit(cursor, sp.start, ClassicalSpanKind.MAIN_TEXT, null)
            emit(sp.start, sp.end, sp.kind, sp.annotator)
            cursor = sp.end
        }
        emit(cursor, text.length, ClassicalSpanKind.MAIN_TEXT, null)
        return out.ifEmpty {
            listOf(ClassicalSubBlock(ClassicalSpanKind.MAIN_TEXT, null, trimStart, trimEnd, trimmed))
        }
    }
}
