package com.coach.desktop.books

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import kotlin.math.ln

/**
 * 单章抽取出的候选概念实体（已通过 UTF-16 原文锚点回验，每章 6–10 个核心专业概念）。
 */
internal data class ExtractedChapterConcept(
    val bookId: String,
    val bookTitle: String,
    val tocNodeId: String,
    val chapterTitle: String,
    val term: String,
    val normalizedTerm: String,
    val page: Int,
    val offsetUtf16: Int,
    val quoteAnchor: String,
    val mentionRole: ConceptMentionRole,
    val claimSummary: String,
    val contextTokens: Set<String>,
    val score: Double,
)

/** 阶段 1（SQLite FTS5 初筛）产出的跨书候选对，送入阶段 2（语义裁决）。 */
internal data class ConceptCandidatePair(
    val pairId: String,
    val left: ExtractedChapterConcept,
    val right: ExtractedChapterConcept,
    val ftsHitsInRightBook: Int,
    val ftsHitsInLeftBook: Int,
    val preScore: Double,
)

/** 阶段 2（语义裁决）对每对候选跨书概念的判定结论。 */
internal data class ConceptAlignmentDecision(
    val pairId: String,
    val keep: Boolean,
    val canonicalName: String = "",
    val aliases: List<String> = emptyList(),
    val relationType: ConceptRelationType = ConceptRelationType.CONSISTENT_DEEPENING,
    val diffSummary: String = "",
    val confidence: Double = 0.0,
    val rejectReason: String = "",
)

internal fun interface ConceptSemanticJudge {
    suspend fun judge(collectionTitle: String, candidates: List<ConceptCandidatePair>): List<ConceptAlignmentDecision>
}

internal data class BooklistAlignmentReport(
    val collectionId: String,
    val booksProcessed: Int,
    val chaptersProcessed: Int,
    val extractedMentions: Int,
    val rejectedAnchors: Int,
    val ftsCandidatePairs: Int,
    val alignedConcepts: Int,
    val alignedLinks: Int,
    val usedRemoteJudge: Boolean,
)

/**
 * 逐章提取 6–10 个核心概念实体 + UTF-16 `quote_anchor` 原文锚点硬核验。
 * 严格剔除普通废词（market, price, policy, study, table, 问题, 方法等）与单字碎片。
 */
internal object ChapterConceptExtractor {
    const val MIN_CONCEPTS_PER_CHAPTER = 6
    const val MAX_CONCEPTS_PER_CHAPTER = 10

    private val EN_STOPWORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "about", "above", "after", "again",
        "against", "all", "also", "among", "another", "any", "are", "been", "before", "being", "between",
        "both", "but", "can", "could", "did", "does", "done", "during", "each", "either", "even", "every",
        "few", "first", "found", "further", "had", "has", "have", "her", "here", "hers", "him", "his",
        "how", "however", "its", "itself", "just", "last", "less", "like", "made", "make", "many", "may",
        "might", "more", "most", "much", "must", "need", "neither", "never", "new", "next", "not", "now",
        "off", "often", "one", "only", "other", "others", "our", "out", "over", "own", "part", "per",
        "perhaps", "same", "second", "see", "seem", "several", "shall", "she", "should", "since", "some",
        "still", "such", "take", "than", "their", "them", "then", "there", "these", "they", "third",
        "those", "though", "three", "through", "thus", "too", "two", "under", "until", "upon", "use",
        "used", "using", "very", "was", "way", "well", "were", "what", "when", "where", "whether",
        "which", "while", "who", "whom", "whose", "why", "will", "within", "without", "would", "yet",
        "across", "toward", "towards", "onto", "via", "versus", "amid", "alongside",
        // Generic academic/editorial waste words (proposal §4.1)
        "market", "markets", "price", "prices", "policy", "policies", "system", "systems", "study",
        "studies", "result", "results", "chapter", "chapters", "section", "sections", "table", "tables",
        "figure", "figures", "example", "examples", "book", "books", "paper", "papers", "author",
        "authors", "page", "pages", "time", "times", "year", "years", "work", "works", "people",
        "person", "state", "states", "case", "cases", "form", "forms", "fact", "facts", "point",
        "points", "number", "numbers", "level", "levels", "rate", "rates", "group", "groups", "type",
        "types", "kind", "kinds", "order", "place", "world", "general", "different", "important",
        "particular", "certain", "various", "possible", "common", "large", "small", "great", "high",
        "low", "long", "short", "early", "late", "later", "earlier", "modern", "present", "history",
        "historical", "century", "centuries", "account", "accounts", "review", "journal", "university",
        "press", "london", "oxford", "cambridge", "new york", "introduction", "conclusion", "notes",
        "references", "bibliography", "index", "appendix", "volume", "edition", "vol", "pp", "ed", "eds",
        "fig", "figs", "ibid", "supra", "infra", "et", "al", "cf", "see also", "university press",
        "new york", "united states", "british", "english", "french", "german", "european", "national",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
        "january", "february", "march", "april", "june", "july", "august", "september", "october",
        "november", "december", "edn", "rev", "ms", "mss", "fol", "folio", "ser", "chap",
        "sir", "mr", "mrs", "dr", "lord", "lady", "prof", "professor", "saint",
    )

    private val EN_DANGLING_TAIL_WORDS = setOf(
        "natural", "artificial", "royal", "scientific", "earliest", "early", "later", "latest",
        "modern", "political", "social", "economic", "medical", "public", "private", "original",
        "prominent", "chief", "principal", "certain", "various", "other", "different", "local",
        "foreign", "human", "physical", "mental", "moral", "legal", "formal", "general", "special",
        "major", "minor", "basic", "primary", "secondary", "further", "greater", "smaller",
        "higher", "lower", "best", "worst", "first", "second", "third", "last", "next", "former",
        "latter", "full", "complete", "whole", "entire", "new", "old", "true", "false", "good",
        "bad", "big", "small", "great", "own", "same", "such", "many", "most", "more", "less",
        "several", "single", "double", "multiple", "common", "usual", "regular", "specific",
        "particular", "individual", "collective", "practical", "theoretical", "experimental",
        "empirical", "curious", "strange", "rare", "extraordinary", "remarkable", "important",
        "significant", "essential", "necessary", "possible", "likely", "available", "existing",
        "established", "proposed", "observed", "recorded", "reported", "published", "printed",
        "written", "known", "unknown", "called", "named", "described", "discussed", "examined",
        "belonging", "concerning", "regarding", "relating", "following", "preceding", "including",
        "containing", "consisting", "representing", "preserved", "kept", "stored", "located",
    )

    private val CITATION_LINE_RE = Regex(
        """(^\s*\d{1,3}\s+[A-Z]|(?i)\b(?:ibid|op\.\s*cit|ed\.|eds\.|edn|pp\.\s*\d|vol\.\s*\d|university press|journal books,|certificates of election|letter book|classified papers)\b|\((?:London|Oxford|Cambridge|New York|Paris|Chicago|Princeton|Harvard)[^)]*\d{4}\))""",
    )
    private val BACKMATTER_HEADING_RE = Regex(
        """(?i)^\s*(?:notes|endnotes|footnotes|references|bibliography|works cited|index|appendix|acknowledgements|acknowledgments)\b""",
    )

    private val ZH_STOPWORDS = setOf(
        "问题", "方法", "结果", "分析", "系统", "研究", "数据", "过程", "内容", "情况", "影响", "因素",
        "关系", "结构", "特点", "表现", "形式", "作用", "意义", "条件", "基础", "核心", "方面", "部分",
        "主要", "重要", "一般", "不同", "相同", "具体", "实际", "理论", "实践", "发展", "变化", "过程",
        "机制", "模型", "概念", "定义", "例子", "本章", "本节", "教材", "课程", "作者", "市场", "价格",
        "政策", "社会", "历史", "国家", "世界", "时代", "时期", "人们", "可以", "通过", "进行", "需要",
        "使用", "出现", "形成", "具有", "包括", "认为", "指出", "说明", "提出", "表明", "讨论", "介绍",
    )

    private val EN_COMMON_VERBS = setOf(
        "arise", "arises", "arose", "occur", "occurs", "occurred", "show", "shows", "showed", "shown",
        "find", "finds", "found", "depend", "depends", "depended", "require", "requires", "required",
        "prevent", "prevents", "prevented", "increase", "increases", "increased", "decrease", "decreases",
        "reduce", "reduces", "reduced", "expand", "expands", "expanded", "become", "becomes", "became",
        "remain", "remains", "remained", "appear", "appears", "appeared", "provide", "provides", "provided",
        "affect", "affects", "affected", "predict", "predicts", "predicted", "reveal", "reveals", "revealed",
        "fail", "fails", "failed", "target", "targets", "targeted", "stabilize", "stabilizes", "stabilized",
        "mitigate", "mitigates", "mitigated", "conflict", "conflicts", "break", "breaks", "broke", "broken",
        "respond", "responds", "responded", "alter", "alters", "altered", "erode", "erodes", "eroded",
        "lower", "lowers", "lowered", "drive", "drives", "driven", "design", "designs", "designed",
        "hold", "holds", "held", "conceal", "conceals", "concealed", "reflect", "reflects", "reflected",
        "separate", "separates", "separated", "drop", "drops", "dropped", "exit", "exits", "exiting",
        "combine", "combines", "combined", "preserve", "preserves", "preserved", "contain", "contains",
        "contained", "moderate", "moderates", "moderated", "subsidize", "subsidizes", "cover", "covers",
        "recover", "recovers", "recoup", "recoups", "argue", "argues", "argued", "claim", "claims",
        "claimed", "note", "notes", "noted", "suggest", "suggests", "suggested", "explain", "explains",
        "explained", "describe", "describes", "described", "discuss", "discusses", "discussed",
        "examine", "examines", "examined", "consider", "considers", "considered", "include", "includes",
        "included", "involve", "involves", "involved", "produce", "produces", "produced", "create",
        "creates", "created", "lead", "leads", "led", "cause", "causes", "caused", "allow", "allows",
        "allowed", "enable", "enables", "enabled", "mean", "means", "meant", "help", "helps", "helped",
        "give", "gives", "given", "bring", "brings", "brought", "keep", "keeps", "kept", "set", "sets",
        "put", "puts", "turn", "turns", "turned", "move", "moves", "moved", "call", "calls", "called",
        "known", "called", "termed", "referred", "based", "related", "associated", "connected",
        "compared", "contrary", "simple", "unlike", "whereas", "although", "because", "therefore",
        "thus", "hence", "furthermore", "moreover", "instead", "rather", "often", "usually", "always",
        "never", "sometimes", "rapidly", "strongly", "substantially", "entire", "total", "average",
        "transfer", "transfers", "transferred", "transferring",
    )

    private val EN_WORD_SPAN_RE = Regex("""[A-Za-z][A-Za-z'-]{1,}""")
    private val ZH_TERM_RE = Regex("""[\u4e00-\u9fa5]{2,8}""")
    private val TOKEN_RE = Regex("""[a-z][a-z'-]{2,}|[\u4e00-\u9fa5]{2,4}""")

    private val CONTRAST_CUES = listOf(
        "however", "contrary", "opposed", "critique", "criticism", "challenge", "challenges", "reject",
        "disagree", "contrast", "whereas", "unlike", "dispute", "debate", "argue against", "fails to",
        "相反", "反驳", "质疑", "分歧", "不同", "批判", "局限", "挑战",
    )
    private val EMPIRICAL_CUES = listOf(
        "empirical", "experiment", "evidence", "regression", "survey", "archive", "catalog", "catalogue",
        "inventory", "manuscript", "specimen", "specimens", "sample", "estimate", "observed", "data",
        "实证", "实验", "证据", "统计", "档案", "标本", "目录", "检验", "观测",
    )
    private val MODEL_CUES = listOf(
        "equilibrium", "utility", "theorem", "equation", "derivative", "elasticity", "function",
        "premium", "constraint", "probability", "expected", "optimal", "marginal", "slope",
        "均衡", "效用", "公式", "推导", "弹性", "函数", "约束", "最优", "边际",
    )
    private val PROBLEM_CUES = listOf(
        "exercise", "problem", "suppose", "calculate", "compute", "derive", "prove",
        "习题", "计算", "求证", "设", "求解",
    )

    internal fun isGenericStopConcept(raw: String): Boolean {
        val norm = normalizeConceptTerm(raw)
        if (norm.length < 3 && norm.none { it in '\u4e00'..'\u9fa5' }) return true
        if (norm.length < 2) return true
        if (norm in EN_STOPWORDS || norm in ZH_STOPWORDS) return true
        val words = norm.split(' ').filter(String::isNotBlank)
        if (words.isEmpty()) return true
        if (words.all { it in EN_STOPWORDS || it.length <= 2 || it.all(Char::isDigit) }) return true
        if (words.size == 1 && words[0].length < 4 && words[0].all { it.code < 128 }) return true
        // Reject dangling leading/trailing glue words, possessives, or trailing bare adjectives.
        val glue = setOf("of", "and", "in", "for", "the", "to", "a", "an", "on", "at", "by", "as", "or", "is", "are", "was", "were")
        if (words.first() in glue || words.last() in glue) return true
        if (words.any { it.endsWith("'s") || it.endsWith("'") || it.startsWith("'") }) return true
        if (words.size >= 2 && words.last() in EN_DANGLING_TAIL_WORDS) return true
        if (words.size == 1 && words[0] in EN_DANGLING_TAIL_WORDS) return true
        if ("and" in words && words.first() in EN_DANGLING_TAIL_WORDS) return true
        return false
    }

    /**
     * 半截概念：短语停在动词或介词上，例如「adjustment transfers」。
     * 完整名词短语（risk adjustment、adverse selection）不是半截。
     */
    internal fun looksLikeFragment(raw: String): Boolean {
        val norm = normalizeConceptTerm(raw)
        val words = norm.split(' ').filter(String::isNotBlank)
        if (words.size < 2) return false
        val last = words.last()
        val stemmed = stemWord(last)
        if (last in EN_COMMON_VERBS || stemmed in EN_COMMON_VERBS) return true
        if (last in EN_STOPWORDS || stemmed in EN_STOPWORDS) return true
        return false
    }

    internal fun normalizeConceptTerm(raw: String): String {
        val collapsed = raw.trim()
            .replace('\u2019', '\'')
            .replace('\u2018', '\'')
            .replace(Regex("\\s+"), " ")
            .lowercase()
        // Mild English plural stemming for single/last word when length >= 5 (keeps "curiosities" -> "curiosity", "museums" -> "museum").
        if (collapsed.any { it in '\u4e00'..'\u9fa5' }) return collapsed
        val parts = collapsed.split(' ').filter(String::isNotBlank)
        if (parts.isEmpty()) return ""
        val stemmed = parts.mapIndexed { idx, w ->
            if (idx < parts.lastIndex) w else stemWord(w)
        }
        return stemmed.joinToString(" ")
    }

    private fun stemWord(w: String): String = when {
        w.length >= 6 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length >= 5 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") -> w.dropLast(1)
        else -> w
    }

    private fun isContentWord(rawWord: String): Boolean {
        val low = rawWord.lowercase()
        if (low.length < 3) return false
        if (low in EN_STOPWORDS || low in EN_COMMON_VERBS) return false
        val stemmed = stemWord(low)
        if (stemmed in EN_STOPWORDS || stemmed in EN_COMMON_VERBS) return false
        return true
    }

    private fun continuesContentRun(text: String, matches: List<MatchResult>, index: Int): Boolean {
        if (index <= 0) return false
        val prev = matches[index - 1]
        val cur = matches[index]
        val between = text.substring(prev.range.last + 1, cur.range.first)
        if (between.isEmpty() || between.any { it != ' ' && it != '\t' }) return false
        return isContentWord(prev.value)
    }

    private fun extractCandidateTermsFromLine(text: String): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        val glueWords = setOf("of", "the", "and")
        val matches = EN_WORD_SPAN_RE.findAll(text).toList()

        // 1. Sliding content-word noun phrases (2-word, 3-word, and "X of Y" / "X of the Y" academic phrases).
        // Phrases only start at the left edge of a content run, so "risk adjustment transfers"
        // keeps "risk adjustment" and drops the half-phrase "adjustment transfers".
        for (i in matches.indices) {
            val first = matches[i]
            if (!isContentWord(first.value)) continue
            val leftEdge = !continuesContentRun(text, matches, i)
            // Single long domain word (>= 9 chars, e.g. "coinsurance", "exclusivity", "curiosities", "repository").
            if (leftEdge && first.value.length >= 9 && first.value.none(Char::isDigit) && !isGenericStopConcept(first.value)) {
                out += first.value to first.range.first
            }
            if (!leftEdge) continue
            var contentCount = 1
            var prevEnd = first.range.last
            for (j in i + 1 until minOf(matches.size, i + 5)) {
                val cur = matches[j]
                // Must be separated only by simple whitespace (no comma/period/newline breaking the phrase).
                val between = text.substring(prevEnd + 1, cur.range.first)
                if (between.isEmpty() || between.any { it != ' ' && it != '\t' }) break
                val low = cur.value.lowercase()
                val isGlue = low in glueWords
                if (!isGlue && !isContentWord(cur.value)) break
                prevEnd = cur.range.last
                if (!isGlue) {
                    contentCount++
                    if (contentCount in 2..3) {
                        val phrase = text.substring(first.range.first, cur.range.last + 1)
                        if (!isGenericStopConcept(phrase) && !looksLikeFragment(phrase)) {
                            out += phrase to first.range.first
                        }
                    }
                    if (contentCount >= 3) break
                }
            }
        }
        // 2. Chinese 2-6 character domain terms.
        for (m in ZH_TERM_RE.findAll(text)) {
            val raw = m.value.trim()
            if (raw.length in 2..6 && !isGenericStopConcept(raw)) {
                out += raw to m.range.first
            }
        }
        return out
    }

    private fun extractSentenceAround(pageText: String, offset: Int, quoteLen: Int): String {
        if (pageText.isEmpty()) return ""
        val safeStart = offset.coerceIn(0, pageText.length)
        val safeEnd = (offset + quoteLen).coerceIn(safeStart, pageText.length)
        var left = safeStart
        while (left > 0 && safeStart - left < 120) {
            val ch = pageText[left - 1]
            if (ch == '\n' || ch == '.' || ch == '。' || ch == '!' || ch == '?' || ch == '！' || ch == '？') break
            left--
        }
        var right = safeEnd
        while (right < pageText.length && right - safeEnd < 160) {
            val ch = pageText[right]
            if (ch == '\n' || ch == '.' || ch == '。' || ch == '!' || ch == '?' || ch == '！' || ch == '？') {
                if (ch != '\n') right++
                break
            }
            right++
        }
        return pageText.substring(left, right).replace(Regex("\\s+"), " ").trim()
    }

    private fun classifyRole(sentence: String): ConceptMentionRole {
        val low = sentence.lowercase()
        return when {
            CONTRAST_CUES.any { it in low } -> ConceptMentionRole.COUNTER_VIEW
            PROBLEM_CUES.any { it in low } -> ConceptMentionRole.WORKED_PROBLEM
            MODEL_CUES.any { it in low } -> ConceptMentionRole.FORMAL_MODEL
            EMPIRICAL_CUES.any { it in low } -> ConceptMentionRole.EMPIRICAL_TEST
            else -> ConceptMentionRole.PRIMARY_DEF
        }
    }

    private fun contextTokensOf(text: String): Set<String> =
        TOKEN_RE.findAll(text.lowercase())
            .map { stemWord(it.value) }
            .filter { it !in EN_STOPWORDS && it !in ZH_STOPWORDS && it.length >= 3 }
            .toSet()

    /**
     * 对整本书按章节提取核心概念（每章 6–10 个），并返回 `(已通过 UTF-16 校验的提及列表, 被拦截的无效锚点数)`。
     */
    fun extractForBook(book: Textbook): Pair<List<ExtractedChapterConcept>, Int> {
        val chapters = BookChapterResolver.chapters(book)
        val pagesByNum = book.pages.associateBy { it.number }
        val allExtracted = mutableListOf<ExtractedChapterConcept>()
        var rejectedAnchors = 0

        // Document frequency across chapters of this book to downweight boiler-plate running headers.
        val chapterTermPresence = HashMap<String, MutableSet<String>>()
        data class Occurrence(
            val rawTerm: String,
            val page: Int,
            val offset: Int,
            val sentence: String,
            val inHeading: Boolean,
        )
        val perChapterOccs = LinkedHashMap<BookChapterSpan, Map<String, List<Occurrence>>>()

        for (ch in chapters) {
            val occMap = LinkedHashMap<String, MutableList<Occurrence>>()
            if (BACKMATTER_HEADING_RE.containsMatchIn(ch.title)) {
                perChapterOccs[ch] = occMap
                continue
            }
            val headingNorms = ch.title.lowercase()
            for (pNum in ch.firstPage..ch.lastPage) {
                val pageText = pagesByNum[pNum]?.text.orEmpty()
                if (pageText.length < 40) continue
                for ((rawTerm, offset) in extractCandidateTermsFromLine(pageText)) {
                    val norm = normalizeConceptTerm(rawTerm)
                    if (norm.isEmpty()) continue
                    val sent = extractSentenceAround(pageText, offset, rawTerm.length)
                    if (sent.length < 15 || CITATION_LINE_RE.containsMatchIn(sent)) continue
                    occMap.getOrPut(norm) { mutableListOf() }.add(
                        Occurrence(
                            rawTerm = rawTerm,
                            page = pNum,
                            offset = offset,
                            sentence = sent,
                            inHeading = norm in headingNorms,
                        ),
                    )
                    chapterTermPresence.getOrPut(norm) { mutableSetOf() }.add(ch.tocNodeId)
                }
            }
            perChapterOccs[ch] = occMap
        }

        val totalChapters = chapters.size.coerceAtLeast(1)
        for ((ch, occMap) in perChapterOccs) {
            if (occMap.isEmpty()) continue
            val scored = occMap.entries.mapNotNull { (norm, occs) ->
                val df = chapterTermPresence[norm]?.size ?: 1
                // Filter out running headers that appear identically on almost every chapter if book has >= 5 chapters.
                if (totalChapters >= 5 && df >= totalChapters && occs.size <= 2) return@mapNotNull null
                val words = norm.split(' ').filter(String::isNotBlank)
                val multiWordBonus = when {
                    words.size in 2..3 -> 2.4
                    words.size == 4 -> 1.8
                    norm.any { it in '\u4e00'..'\u9fa5' } && norm.length in 3..6 -> 2.1
                    else -> 1.0
                }
                val headingBonus = if (occs.any { it.inHeading }) 1.6 else 1.0
                val idf = ln((totalChapters + 1.5) / (df + 0.5)) + 1.0
                val tf = 1.0 + ln(occs.size.toDouble())
                val bestOcc = occs.maxByOrNull { o ->
                    (if (o.inHeading) 2 else 0) + (if (o.sentence.length in 45..220) 2 else 0)
                } ?: occs.first()
                val score = tf * idf * multiWordBonus * headingBonus
                Triple(norm, bestOcc, score)
            }.sortedByDescending { it.third }

            // Suppress overlapping sub-phrases (e.g. keep "adverse selection" over bare "selection" or "adverse").
            val picked = mutableListOf<Triple<String, Occurrence, Double>>()
            for (cand in scored) {
                if (picked.size >= MAX_CONCEPTS_PER_CHAPTER) break
                val norm = cand.first
                val overlaps = picked.any { (existingNorm, _, _) ->
                    existingNorm == norm ||
                        (existingNorm.contains(norm) && norm.length >= 4) ||
                        (norm.contains(existingNorm) && existingNorm.length >= 4)
                }
                if (overlaps) continue
                picked += cand
            }

            for ((norm, occ, score) in picked) {
                val pageText = pagesByNum[occ.page]?.text.orEmpty()
                // Prefer a verified quote_anchor snippet (term or short exact phrase from the page).
                val desiredQuote = chooseVerifiedQuoteSlice(pageText, occ.offset, occ.rawTerm)
                val verified = ConceptAnchorVerifier.verify(pageText, desiredQuote, occ.offset)
                if (verified == null) {
                    rejectedAnchors++
                    continue
                }
                val role = classifyRole(occ.sentence)
                val claim = occ.sentence.take(140)
                allExtracted += ExtractedChapterConcept(
                    bookId = book.id,
                    bookTitle = book.title,
                    tocNodeId = ch.tocNodeId,
                    chapterTitle = ch.title,
                    term = cleanDisplayTerm(occ.rawTerm),
                    normalizedTerm = norm,
                    page = occ.page,
                    offsetUtf16 = verified.offsetUtf16,
                    quoteAnchor = verified.quoteAnchor,
                    mentionRole = role,
                    claimSummary = claim,
                    contextTokens = contextTokensOf(occ.sentence + " " + ch.title),
                    score = score,
                )
            }
        }
        return allExtracted to rejectedAnchors
    }

    private fun chooseVerifiedQuoteSlice(pageText: String, offset: Int, rawTerm: String): String {
        val safeStart = offset.coerceIn(0, pageText.length)
        val safeEnd = (offset + rawTerm.length).coerceIn(safeStart, pageText.length)
        // Expand cleanly along word boundaries to a crisp verbatim phrase (up to 72 chars) within the same line.
        var s = safeStart
        var e = safeEnd
        while (s > 0 && safeStart - s < 22 && pageText[s - 1] !in charArrayOf('\n', '.', '。', ';', '；')) s--
        // Never start mid-word on Latin letters/digits.
        if (s > 0 && pageText[s].isLetterOrDigit() && pageText[s - 1].isLetterOrDigit()) {
            while (s < safeStart && pageText[s].isLetterOrDigit()) s++
        }
        while (s < safeStart && (pageText[s].isWhitespace() || pageText[s] in charArrayOf(',', ':', ';', '-', '–', '—'))) s++
        while (e < pageText.length && e - s < 72 && pageText[e] !in charArrayOf('\n', '.', '。', ';', '；')) e++
        // Never end mid-word on Latin letters/digits.
        if (e < pageText.length && e > safeEnd && pageText[e].isLetterOrDigit() && pageText[e - 1].isLetterOrDigit()) {
            while (e > safeEnd && pageText[e - 1].isLetterOrDigit()) e--
        }
        while (e > safeEnd && (pageText[e - 1].isWhitespace() || pageText[e - 1] in charArrayOf(',', ':', ';', '-', '–', '—'))) e--
        val slice = pageText.substring(s, e).trim()
        return if (slice.length >= rawTerm.length && rawTerm.lowercase() in slice.lowercase()) slice else rawTerm
    }

    private fun cleanDisplayTerm(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ")
}

/**
 * 阶段 1：SQLite `FTS5` 跨书候选初筛（`$0` 本机预筛）。
 * 结合 SQLite `pages_fts`（trigram / unicode61）跨书命中页数与概念术语重叠度，
 * 筛出高质量跨书候选对送入阶段 2 语义裁决。
 */
internal object FtsConceptCandidateScreener {
    fun screen(
        library: TextbookLibrary,
        conceptsByBook: Map<String, List<ExtractedChapterConcept>>,
        maxPairs: Int = 96,
    ): List<ConceptCandidatePair> {
        val bookIds = conceptsByBook.keys.toList()
        if (bookIds.size < 2) return emptyList()

        val ftsCache = HashMap<Pair<String, String>, List<Int>>()
        fun ftsHits(bookId: String, query: String): List<Int> =
            ftsCache.getOrPut(bookId to query.lowercase()) {
                library.ftsCandidatePages(bookId, query)
            }

        val candidates = mutableListOf<ConceptCandidatePair>()
        val seenPairs = HashSet<String>()

        for (i in bookIds.indices) {
            for (j in i + 1 until bookIds.size) {
                val bookA = bookIds[i]
                val bookB = bookIds[j]
                val listA = conceptsByBook[bookA].orEmpty()
                val listB = conceptsByBook[bookB].orEmpty()
                val byNormB = listB.groupBy { it.normalizedTerm }

                for (a in listA) {
                    val directHitsB = byNormB[a.normalizedTerm].orEmpty()
                    val ftsPagesInB = ftsHits(bookB, a.term)
                    val matchedB = mutableListOf<ExtractedChapterConcept>()
                    matchedB += directHitsB

                    // If FTS5 confirms the concept appears in Book B, only pair with concepts that share real lexical overlap
                    // OR contain the other concept's phrase in their context OR bridge Chinese/English cross-lingual concepts.
                    if (matchedB.isEmpty() && ftsPagesInB.isNotEmpty()) {
                        val ftsPageSet = ftsPagesInB.toSet()
                        val aWords = significantWords(a.normalizedTerm)
                        val aHasCjk = a.normalizedTerm.any { it in '\u4e00'..'\u9fa5' }
                        val nearB = listB.filter { b ->
                            val bWords = significantWords(b.normalizedTerm)
                            val bHasCjk = b.normalizedTerm.any { it in '\u4e00'..'\u9fa5' }
                            val shared = aWords.intersect(bWords)
                            val crossLingual = aHasCjk != bHasCjk
                            when {
                                shared.size >= 2 -> true
                                shared.size == 1 && (shared.single().length >= 8 || (aWords.size == 1 && bWords.size == 1)) -> true
                                b.page in ftsPageSet && (a.normalizedTerm in b.claimSummary.lowercase() || b.normalizedTerm in a.claimSummary.lowercase()) -> true
                                crossLingual && b.page in ftsPageSet && tokenJaccard(a.contextTokens, b.contextTokens) >= 0.15 -> true
                                else -> false
                            }
                        }
                        if (nearB.isNotEmpty()) {
                            matchedB += nearB.maxByOrNull { b ->
                                val tokOverlap = tokenJaccard(a.contextTokens, b.contextTokens)
                                val wordOverlap = tokenJaccard(aWords, significantWords(b.normalizedTerm))
                                (if (b.page in ftsPageSet) 0.5 else 0.0) + wordOverlap * 1.5 + tokOverlap
                            }!!
                        }
                    }

                    // Also match strong multi-word lexical overlap (e.g. "Royal Society repository" <-> "Repository of the Royal Society").
                    if (matchedB.isEmpty()) {
                        val aWords = significantWords(a.normalizedTerm)
                        if (aWords.size >= 2) {
                            val lexMatch = listB.firstOrNull { b ->
                                val bWords = significantWords(b.normalizedTerm)
                                val shared = aWords.intersect(bWords)
                                shared.size >= 2
                            }
                            if (lexMatch != null) matchedB += lexMatch
                        }
                    }

                    for (b in matchedB.distinctBy { "${it.tocNodeId}:${it.page}:${it.normalizedTerm}" }) {
                        val key = "${a.bookId}:${a.tocNodeId}:${a.normalizedTerm}|${b.bookId}:${b.tocNodeId}:${b.normalizedTerm}"
                        if (!seenPairs.add(key)) continue
                        val ftsPagesInA = ftsHits(bookA, b.term)
                        val exactTermMatch = a.normalizedTerm == b.normalizedTerm
                        val contextSim = tokenJaccard(a.contextTokens, b.contextTokens)
                        val wordOverlap = tokenJaccard(significantWords(a.normalizedTerm), significantWords(b.normalizedTerm))
                        val ftsBonus = if (ftsPagesInB.isNotEmpty() || ftsPagesInA.isNotEmpty()) 0.35 else 0.0
                        val preScore = (if (exactTermMatch) 0.50 else wordOverlap * 0.45) + contextSim * 0.35 + ftsBonus
                        if (preScore < 0.30) continue
                        val pairId = "pair-${shortHash(key)}"
                        candidates += ConceptCandidatePair(
                            pairId = pairId,
                            left = a,
                            right = b,
                            ftsHitsInRightBook = ftsPagesInB.size,
                            ftsHitsInLeftBook = ftsPagesInA.size,
                            preScore = preScore,
                        )
                    }
                }
            }
        }
        // Keep Stage 1 candidates diverse: cap repetitive (term, bookPair) pairs so one dominant term
        // never starves 30+ other domain concepts before Stage 2 semantic adjudication.
        val sorted = candidates.sortedByDescending { it.preScore }
        val diverse = mutableListOf<ConceptCandidatePair>()
        val perTermBookPair = HashMap<String, Int>()
        for (c in sorted) {
            if (diverse.size >= maxPairs) break
            val normKey = if (c.left.normalizedTerm <= c.right.normalizedTerm) {
                "${c.left.normalizedTerm}|${c.right.normalizedTerm}"
            } else {
                "${c.right.normalizedTerm}|${c.left.normalizedTerm}"
            }
            val bp = listOf(c.left.bookId, c.right.bookId).sorted().joinToString("|")
            val slot = "$normKey|$bp"
            val used = perTermBookPair[slot] ?: 0
            if (used >= 3) continue
            perTermBookPair[slot] = used + 1
            diverse += c
        }
        return diverse
    }

    internal fun significantWords(norm: String): Set<String> =
        norm.split(' ').filter { it.length >= 4 && !ChapterConceptExtractor.isGenericStopConcept(it) }.toSet()

    internal fun tokenJaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size
        if (inter == 0) return 0.0
        val union = a.size + b.size - inter
        return inter.toDouble() / union.toDouble()
    }
}

/**
 * 离线/未配对兜底语义裁决器：保守高精度策略，剔除废词与同名异义假阳性，产出 <= 45 字平实中文异同说明。
 */
internal object HeuristicConceptSemanticJudge : ConceptSemanticJudge {
    private val KNOWN_HOMONYMS = setOf(
        "bank", "cell", "charge", "class", "court", "current", "degree", "depression", "field",
        "force", "interest", "marginal", "matter", "medium", "nature", "order", "organ", "party",
        "plant", "power", "present", "race", "range", "rate", "record", "return", "scale", "series",
        "service", "share", "space", "spring", "stock", "subject", "term", "trade", "trust", "value",
    )

    override suspend fun judge(
        collectionTitle: String,
        candidates: List<ConceptCandidatePair>,
    ): List<ConceptAlignmentDecision> = candidates.map { pair ->
        val left = pair.left
        val right = pair.right
        val normA = left.normalizedTerm
        val normB = right.normalizedTerm

        if (ChapterConceptExtractor.isGenericStopConcept(normA) || ChapterConceptExtractor.isGenericStopConcept(normB)) {
            return@map ConceptAlignmentDecision(pair.pairId, keep = false, rejectReason = "generic_term")
        }
        val contextSim = FtsConceptCandidateScreener.tokenJaccard(left.contextTokens, right.contextTokens)
        val leftWords = FtsConceptCandidateScreener.significantWords(normA)
        val rightWords = FtsConceptCandidateScreener.significantWords(normB)
        val sharedWords = leftWords.intersect(rightWords)
        val wordSim = FtsConceptCandidateScreener.tokenJaccard(leftWords, rightWords)
        val isHomonymRisk = (normA in KNOWN_HOMONYMS || normB in KNOWN_HOMONYMS)
        if (isHomonymRisk && contextSim < 0.14) {
            return@map ConceptAlignmentDecision(pair.pairId, keep = false, rejectReason = "homonym_context_mismatch")
        }
        val exactOrStrong = normA == normB ||
            wordSim >= 0.50 ||
            sharedWords.size >= 2 ||
            (sharedWords.size == 1 && sharedWords.single().length >= 8 && contextSim >= 0.12)
        if (!exactOrStrong) {
            return@map ConceptAlignmentDecision(pair.pairId, keep = false, rejectReason = "weak_alignment")
        }

        val relation = when {
            left.mentionRole == ConceptMentionRole.COUNTER_VIEW || right.mentionRole == ConceptMentionRole.COUNTER_VIEW ->
                ConceptRelationType.OPPOSING_VIEW
            (left.mentionRole == ConceptMentionRole.EMPIRICAL_TEST && right.mentionRole != ConceptMentionRole.EMPIRICAL_TEST) ||
                (right.mentionRole == ConceptMentionRole.EMPIRICAL_TEST && left.mentionRole != ConceptMentionRole.EMPIRICAL_TEST) ->
                ConceptRelationType.EMPIRICAL_CHALLENGE
            (left.mentionRole == ConceptMentionRole.FORMAL_MODEL && right.mentionRole == ConceptMentionRole.WORKED_PROBLEM) ||
                (right.mentionRole == ConceptMentionRole.FORMAL_MODEL && left.mentionRole == ConceptMentionRole.WORKED_PROBLEM) ->
                ConceptRelationType.PREREQUISITE
            left.mentionRole != right.mentionRole ->
                ConceptRelationType.NOTATION_OR_BOUNDARY_DIFF
            else ->
                ConceptRelationType.CONSISTENT_DEEPENING
        }

        val canonical = if (left.term.length >= right.term.length) left.term else right.term
        val aliases = listOf(left.term, right.term).distinct().filterNot { it.equals(canonical, ignoreCase = true) }
        val diff = buildPlainDiffSummary(canonical, left, right, relation)
        val conf = (0.78 + (if (normA == normB) 0.10 else wordSim * 0.08) + minOf(0.09, contextSim * 0.30) +
            if (pair.ftsHitsInRightBook > 0 || pair.ftsHitsInLeftBook > 0) 0.04 else 0.0).coerceIn(0.0, 0.98)

        ConceptAlignmentDecision(
            pairId = pair.pairId,
            keep = conf >= ConceptStore.MIN_COMPANION_CONFIDENCE,
            canonicalName = canonical,
            aliases = aliases,
            relationType = relation,
            diffSummary = diff,
            confidence = conf,
        )
    }

    private fun buildPlainDiffSummary(
        canonical: String,
        left: ExtractedChapterConcept,
        right: ExtractedChapterConcept,
        relation: ConceptRelationType,
    ): String {
        val raw = when (relation) {
            ConceptRelationType.OPPOSING_VIEW -> "两书对「$canonical」的解释视角存在分歧，可对照原页论据。"
            ConceptRelationType.EMPIRICAL_CHALLENGE -> "一书偏概念界定，另一书补充了「$canonical」的实证案例。"
            ConceptRelationType.NOTATION_OR_BOUNDARY_DIFF -> "两书对「$canonical」的适用边界与侧重点不同。"
            ConceptRelationType.PREREQUISITE -> "前书铺垫了「$canonical」原理，本书侧重具体应用。"
            ConceptRelationType.CONSISTENT_DEEPENING -> "两书从不同章节共同推进了「$canonical」的讨论。"
        }
        return if (raw.length <= 45) raw else raw.take(44) + "。"
    }
}

/**
 * 经配对学习服务调用远端 DeepSeek 语义对齐端点（`/v1/library/booklist/align`）；
 * 当用户关闭「伴读讲解与提问」、离线或未登录时，严格退回本地 [HeuristicConceptSemanticJudge]，不发送正文。
 */
internal class RemoteBooklistSemanticJudge(
    private val explainer: BookExplainer,
    private val allowRemote: () -> Boolean = { true },
    private val fallback: ConceptSemanticJudge = HeuristicConceptSemanticJudge,
) : ConceptSemanticJudge {
    var lastUsedRemote: Boolean = false
        private set

    override suspend fun judge(
        collectionTitle: String,
        candidates: List<ConceptCandidatePair>,
    ): List<ConceptAlignmentDecision> {
        lastUsedRemote = false
        if (candidates.isEmpty()) return emptyList()
        if (!allowRemote()) return fallback.judge(collectionTitle, candidates)
        if (!explainer.loaded) runCatching { explainer.load() }
        if (explainer.identity == null) return fallback.judge(collectionTitle, candidates)

        val requestJson = buildWireRequest(collectionTitle, candidates)
        val response = runCatching { explainer.alignBooklistConcepts(requestJson) }.getOrNull()
        val parsed = response?.let(::parseWireResponse)
        if (parsed != null && parsed.isNotEmpty()) {
            lastUsedRemote = true
            return parsed
        }
        return fallback.judge(collectionTitle, candidates)
    }

    companion object {
        fun buildWireRequest(collectionTitle: String, candidates: List<ConceptCandidatePair>): JsonObject = buildJsonObject {
            put("collectionTitle", collectionTitle.take(120))
            put("candidates", buildJsonArray {
                for (c in candidates) {
                    add(buildJsonObject {
                        put("pairId", c.pairId)
                        put("ftsHitsLeft", c.ftsHitsInLeftBook)
                        put("ftsHitsRight", c.ftsHitsInRightBook)
                        put("left", buildJsonObject {
                            put("bookTitle", c.left.bookTitle.take(80))
                            put("chapterTitle", c.left.chapterTitle.take(80))
                            put("term", c.left.term.take(80))
                            put("page", c.left.page)
                            put("role", c.left.mentionRole.name)
                            put("quote", c.left.quoteAnchor.take(120))
                            put("claim", c.left.claimSummary.take(180))
                        })
                        put("right", buildJsonObject {
                            put("bookTitle", c.right.bookTitle.take(80))
                            put("chapterTitle", c.right.chapterTitle.take(80))
                            put("term", c.right.term.take(80))
                            put("page", c.right.page)
                            put("role", c.right.mentionRole.name)
                            put("quote", c.right.quoteAnchor.take(120))
                            put("claim", c.right.claimSummary.take(180))
                        })
                    })
                }
            })
        }

        fun parseWireResponse(body: JsonObject): List<ConceptAlignmentDecision> {
            val arr = body["decisions"] as? JsonArray ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val pairId = (o["pairId"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return@mapNotNull null
                val keep = (o["keep"] as? JsonPrimitive)?.booleanOrNull ?: false
                val canonical = (o["canonicalName"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val aliases = (o["aliases"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                val relation = ConceptRelationType.fromWire((o["relationType"] as? JsonPrimitive)?.contentOrNull)
                val diff = (o["diffSummary"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().take(45)
                val conf = (o["confidence"] as? JsonPrimitive)?.doubleOrNull ?: if (keep) 0.88 else 0.0
                val reason = (o["rejectReason"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                ConceptAlignmentDecision(
                    pairId = pairId,
                    keep = keep && canonical.isNotBlank() && diff.isNotBlank(),
                    canonicalName = canonical,
                    aliases = aliases,
                    relationType = relation,
                    diffSummary = diff,
                    confidence = conf.coerceIn(0.0, 1.0),
                    rejectReason = reason,
                )
            }
        }
    }
}

/**
 * 书单两阶段共同主题对齐总控：
 * 1. 逐章提取 6–10 个核心概念并做 UTF-16 `quote_anchor` 原文回验；
 * 2. 阶段 1 用 SQLite `FTS5` 初筛跨书候选对；
 * 3. 阶段 2 经语义裁决器（DeepSeek 远端优先，离线退回保守规则）去伪存真；
 * 4. 写入 `concept_entities`、`concept_mentions`、`concept_links`（保留已有消音与共享笔记）。
 */
internal object BooklistAlignmentEngine {
    suspend fun alignBooklist(
        library: TextbookLibrary,
        collectionId: String,
        judge: ConceptSemanticJudge = HeuristicConceptSemanticJudge,
    ): BooklistAlignmentReport {
        val summary = library.collectionSummaries().firstOrNull { it.record.id == collectionId }
            ?: return BooklistAlignmentReport(collectionId, 0, 0, 0, 0, 0, 0, 0, false)
        val books = summary.materialIds.mapNotNull { id -> runCatching { library.book(id) }.getOrNull() }
        if (books.size < 2) {
            return BooklistAlignmentReport(collectionId, books.size, 0, 0, 0, 0, 0, 0, false)
        }

        val conceptsByBook = LinkedHashMap<String, List<ExtractedChapterConcept>>()
        var totalChapters = 0
        var totalMentions = 0
        var rejectedAnchors = 0
        for (book in books) {
            totalChapters += BookChapterResolver.chapters(book).size
            val (extracted, rejected) = ChapterConceptExtractor.extractForBook(book)
            conceptsByBook[book.id] = extracted
            totalMentions += extracted.size
            rejectedAnchors += rejected
        }

        // Stage 1: SQLite FTS5 candidate pre-screening.
        val candidatePairs = FtsConceptCandidateScreener.screen(library, conceptsByBook)
        if (candidatePairs.isEmpty()) {
            return BooklistAlignmentReport(
                collectionId = collectionId,
                booksProcessed = books.size,
                chaptersProcessed = totalChapters,
                extractedMentions = totalMentions,
                rejectedAnchors = rejectedAnchors,
                ftsCandidatePairs = 0,
                alignedConcepts = 0,
                alignedLinks = 0,
                usedRemoteJudge = false,
            )
        }

        // Stage 2: Semantic alignment adjudication (DeepSeek via judge, or offline fallback).
        val decisions = judge.judge(summary.record.title, candidatePairs)
        val decisionByPair = decisions.associateBy { it.pairId }
        val usedRemote = (judge as? RemoteBooklistSemanticJudge)?.lastUsedRemote == true

        // Group accepted pairs into canonical concept_entities, deduplicated concept_mentions, and concept_links.
        val entitiesByNorm = LinkedHashMap<String, ConceptEntityRecord>()
        val mentionsByKey = LinkedHashMap<String, ConceptMentionRecord>()
        val links = mutableListOf<ConceptLinkRecord>()
        val seenLinkKey = HashSet<String>()
        val perConceptBookPairCount = HashMap<String, Int>()

        for (pair in candidatePairs) {
            val dec = decisionByPair[pair.pairId] ?: continue
            if (!dec.keep || dec.confidence < ConceptStore.MIN_COMPANION_CONFIDENCE) continue
            val canonicalName = dec.canonicalName.trim().ifBlank { pair.left.term }
            if (ChapterConceptExtractor.isGenericStopConcept(canonicalName)) continue
            val normKey = ChapterConceptExtractor.normalizeConceptTerm(canonicalName)
            val conceptId = "concept-${shortHash("$collectionId|$normKey")}"
            val bookPairKey = "$conceptId|" + listOf(pair.left.bookId, pair.right.bookId).sorted().joinToString("|")
            if ((perConceptBookPairCount[bookPairKey] ?: 0) >= 4) continue

            val existingEntity = entitiesByNorm[normKey]
            val mergedAliases = (existingEntity?.aliases.orEmpty() + dec.aliases + pair.left.term + pair.right.term)
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.equals(canonicalName, ignoreCase = true) }
                .distinct()
            entitiesByNorm[normKey] = (existingEntity ?: ConceptEntityRecord(
                conceptId = conceptId,
                collectionId = collectionId,
                canonicalName = canonicalName,
            )).copy(aliases = mergedAliases)

            fun registerMention(c: ExtractedChapterConcept): ConceptMentionRecord {
                val mKey = "$conceptId|${c.bookId}|${c.page}|${c.offsetUtf16}"
                return mentionsByKey.getOrPut(mKey) {
                    ConceptMentionRecord(
                        mentionId = "mention-${shortHash(mKey)}",
                        conceptId = conceptId,
                        bookId = c.bookId,
                        tocNodeId = c.tocNodeId,
                        chunkId = null,
                        page = c.page,
                        offsetUtf16 = c.offsetUtf16,
                        quoteAnchor = c.quoteAnchor,
                        mentionRole = c.mentionRole,
                        claimSummary = c.claimSummary,
                    )
                }
            }

            val mLeft = registerMention(pair.left)
            val mRight = registerMention(pair.right)
            if (mLeft.mentionId == mRight.mentionId) continue

            val canonicalLinkKey = listOf(mLeft.mentionId, mRight.mentionId).sorted().joinToString("->")
            if (!seenLinkKey.add("$conceptId|$canonicalLinkKey")) continue
            perConceptBookPairCount[bookPairKey] = (perConceptBookPairCount[bookPairKey] ?: 0) + 1

            val diff = dec.diffSummary.trim().ifBlank { "两书在不同章节共同讨论了「$canonicalName」。" }.take(45)
            links += ConceptLinkRecord(
                linkId = "link-${shortHash("$collectionId|$conceptId|$canonicalLinkKey")}",
                collectionId = collectionId,
                conceptId = conceptId,
                fromMentionId = mLeft.mentionId,
                toMentionId = mRight.mentionId,
                relationType = dec.relationType,
                diffSummary = diff,
                confidence = dec.confidence,
                dismissed = false,
            )
        }

        val entityList = entitiesByNorm.values.toList()
        val mentionList = mentionsByKey.values.toList()
        library.saveAlignedConcepts(collectionId, entityList, mentionList, links)

        return BooklistAlignmentReport(
            collectionId = collectionId,
            booksProcessed = books.size,
            chaptersProcessed = totalChapters,
            extractedMentions = totalMentions,
            rejectedAnchors = rejectedAnchors,
            ftsCandidatePairs = candidatePairs.size,
            alignedConcepts = entityList.size,
            alignedLinks = links.size,
            usedRemoteJudge = usedRemote,
        )
    }
}

private fun shortHash(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(12)
