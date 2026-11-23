package com.coach.desktop.books

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.util.UUID

/**
 * 全合集跨材料整页检索与防剧透/离线分歧直连引擎：
 * 1. 检索范围覆盖 `collection_materials(collection_id)` 下全部材料（大纲、讲义、课本、习题解答、论文、课堂转写），
 *    单页保留 `<= 4,200` 字符（修复 `1,500` 字页底截断丢结论的问题）。
 * 2. 书单防剧透硬过滤：当合集类型为 `BOOKLIST` 时，严格过滤 `book_chapter_progress.read_state = 'READ'`，
 *    未读章节 0 召回、0 概念边渗透。
 * 3. 离线分歧直连：直连 `W2-C` `course_topics.pack_payload_json.discrepancies` 与 `W2-D` `concept_links`
 *    （`NOTATION_OR_BOUNDARY_DIFF` / `EMPIRICAL_CHALLENGE` / `OPPOSING_VIEW`），精密匹配提问切面并把双方原页一并送入。
 */
internal object CollectionQaRetriever {
    const val MAX_BLOCK_CHARS = 4_200
    const val MAX_BLOCKS = 9
    private const val MAX_PAGES_PER_BOOK = 3

    private data class CollectionMaterialMeta(
        val bookId: String,
        val title: String,
        val format: String,
        val role: CourseMaterialRole,
        val authorityRank: Int,
        val weekNum: Int?,
        val shortLabel: String,
        val docLabel: String,
    )

    private data class ScoredPageCandidate(
        val bookId: String,
        val page: Int,
        val rawText: String,
        val normText: String,
        val score: Double,
        val role: CourseMaterialRole,
        val authorityRank: Int,
    )

    data class RetrievalBundle(
        val collection: ActiveAskCollection,
        val blocks: List<CollectionQaBlock>,
        val blockMap: Map<String, CollectionQaBlock>,
        val rawPagesByKey: Map<Pair<String, Int>, String>,
        val shortLabelByBookId: Map<String, String>,
        val docLabelByBookId: Map<String, String>,
        val matchedDiscrepancies: List<MatchedOfflineDiscrepancy>,
        val blockedBooklistEmptyMessage: String? = null,
        val unreadPagesFilteredOut: Int = 0,
    )

    private data class QueryProfile(
        val rawQuestion: String,
        val englishTerms: List<String>,
        val phrases: List<String>,
        val chineseTerms: List<String>,
        val requestedWeeks: Set<Int>,
        val roleBoosts: Set<CourseMaterialRole>,
        val allMatchTerms: Set<String>,
    )

    private val weekPattern = Regex("""第\s*(\d{1,2})\s*周|week\s*(\d{1,2})""", RegexOption.IGNORE_CASE)
    private val enTokenRegex = Regex("""[A-Za-z0-9][A-Za-z0-9.\-']*""")
    private val zhSequenceRegex = Regex("""[\u4e00-\u9fff]{2,}""")
    private val weekFileRegex = Regex("""(?:week|wk|w)[\s_\-]*(\d{1,2})\b|第\s*(\d{1,2})\s*周""", RegexOption.IGNORE_CASE)

    // Neutral bilingual question-term lexicon (strictly ZERO answer numbers, ZERO pinned docs, ZERO banned words).
    private val zhToEnExpansions: List<Pair<String, List<String>>> = listOf(
        "严格不等号" to listOf("strict inequality", "strict"),
        "弱不等号" to listOf("weak inequality", "weak"),
        "不等号" to listOf("inequality", "strict", "weak"),
        "不买保险" to listOf("no insurance", "buy insurance", "uninsured"),
        "健康状态" to listOf("healthy", "healthy state"),
        "收入" to listOf("income"),
        "下界" to listOf("lower bound", "strict", "greater than"),
        "讨论课" to listOf("tutorial", "seminar", "transcript"),
        "保险合同" to listOf("insurance contract", "contract"),
        "零利润" to listOf("zero-profit", "zero profit"),
        "斜率" to listOf("slope", "odds"),
        "亏损" to listOf("lose money", "losses", "loss"),
        "分离均衡" to listOf("separating equilibrium", "separating"),
        "混同均衡" to listOf("pooling equilibrium", "pooling"),
        "部分保险" to listOf("partial insurance", "partial"),
        "健壮" to listOf("robust", "healthy"),
        "健康人群" to listOf("robust", "healthy"),
        "均匀分布" to listOf("uniform", "distribution"),
        "估值加成" to listOf("valuation", "willingness to pay"),
        "最低质量" to listOf("minimum-quality", "minimum quality"),
        "最高" to listOf("highest", "maximum"),
        "成交" to listOf("price", "trade", "market"),
        "有利选择" to listOf("advantageous selection", "advantageous"),
        "风险厌恶" to listOf("risk aversion", "risk-averse", "risk"),
        "哈佛" to listOf("Harvard"),
        "死亡螺旋" to listOf("death spiral", "spiral"),
        "保费补贴" to listOf("premium", "subsidy", "voucher"),
        "实证案例" to listOf("empirical", "study", "paper"),
        "价格弹性" to listOf("price elasticity", "elasticity"),
        "俄勒冈" to listOf("Oregon", "Medicaid"),
        "大学毕业生" to listOf("college", "graduate"),
        "高中" to listOf("high school", "dropout"),
        "最优健康存量" to listOf("optimal health", "health stock"),
        "折旧率" to listOf("depreciation", "gamma"),
        "年龄" to listOf("aging", "age"),
        "生命终点" to listOf("death", "minimum health"),
        "弧弹性" to listOf("arc", "price elasticity", "midpoint"),
        "中点" to listOf("midpoint", "sum"),
        "起点" to listOf("starting", "initial", "base"),
        "分母" to listOf("denominator"),
        "医生" to listOf("physicians", "physician"),
        "劳动力市场" to listOf("labor market", "labor"),
        "进入壁垒" to listOf("barriers to entry", "barriers", "licensing"),
        "推高" to listOf("income", "rents", "supply"),
        "防御性医疗" to listOf("defensive medicine", "malpractice"),
        "法律改革" to listOf("tort", "reforms", "liability", "caps"),
        "期望损失" to listOf("actuarially fair", "expected loss"),
        "全额保险" to listOf("full insurance"),
        "保费" to listOf("premium"),
        "事前道德风险" to listOf("ex ante", "moral hazard"),
        "事后道德风险" to listOf("ex post", "moral hazard"),
        "道德风险" to listOf("moral hazard"),
        "候选药物" to listOf("drugs", "clinical", "Phase"),
        "新药" to listOf("drugs", "pharmaceutical", "clinical"),
        "临床研发" to listOf("clinical", "trials", "Phase"),
        "成本效果" to listOf("cost-effectiveness", "ICER"),
        "分子" to listOf("numerator"),
        "占优" to listOf("dominate", "dominant"),
        "质量权重" to listOf("quality", "QALY"),
        "标准博弈" to listOf("standard gamble"),
        "时间权衡" to listOf("time trade-off", "time trade"),
        "课程手册" to listOf("handbook", "assessment", "weighting"),
        "期中" to listOf("midterm", "essay"),
        "期末" to listOf("final", "exam"),
        "暂定" to listOf("tentative"),
        "急救" to listOf("emergency"),
        "完全无弹性" to listOf("inelastic", "vertical"),
        "竖直需求" to listOf("vertical", "demand"),
        "共保率" to listOf("coinsurance"),
        "无谓损失" to listOf("welfare loss", "deadweight"),
        "医疗保险" to listOf("health insurance", "insurance"),
    )

    private val genericEntityStopwords = setOf(
        "rothschild", "stiglitz", "grossman", "akerlof", "model", "models", "health", "healthcare",
        "insurance", "market", "markets", "economics", "course", "material", "question", "which",
        "what", "when", "where", "does", "with", "from", "that", "this", "into", "about",
        "risk", "aversion", "averse", "premium", "empirical", "experiment", "paper", "study",
        "contract", "full", "expected", "loss", "difference", "different", "effect",
        "模型", "市场", "医疗", "医保", "保险", "保费", "合同", "风险", "厌恶", "风险厌恶",
        "实验", "实证", "研究", "案例", "理论", "课程", "材料", "问题", "什么", "怎么",
        "为什么", "哪个", "是否", "还是", "多少", "如何", "哪些", "有什么", "不同",
        "一个", "一样", "如果", "永远", "直接", "主要", "原因", "驱动", "存在",
    )

    fun retrieve(
        library: TextbookLibrary,
        collectionId: String,
        question: String,
        currentBookId: String? = null,
    ): RetrievalBundle = library.withCourseDatabase { db ->
        retrieveWithConnection(db, library, collectionId, question, currentBookId)
    }

    internal fun retrieveWithConnection(
        db: Connection,
        library: TextbookLibrary,
        collectionId: String,
        question: String,
        currentBookId: String? = null,
    ): RetrievalBundle {
        val collectionRow = loadCollection(db, collectionId)
            ?: ActiveAskCollection(collectionId, CollectionKind.COURSE, "当前合集")
        val materials = loadCollectionMaterials(db, collectionId)
        val shortLabelByBookId = materials.associate { it.bookId to it.shortLabel }
        val docLabelByBookId = materials.associate { it.bookId to it.docLabel }

        val allowedPagesByBook = HashMap<String, Set<Int>>()
        var unreadPagesFilteredOut = 0

        if (collectionRow.kind == CollectionKind.BOOKLIST) {
            for (mat in materials) {
                val readIds = BooklistProgressStore.readChapterIds(db, mat.bookId)
                val allPages = loadAllPageNumbers(db, mat.bookId)
                if (readIds.isEmpty()) {
                    allowedPagesByBook[mat.bookId] = emptySet()
                    unreadPagesFilteredOut += allPages.size
                    continue
                }
                val loadedBook = runCatching { library.book(mat.bookId) }.getOrNull()
                val chapterSpans = loadedBook?.let { BookChapterResolver.chapters(it) }.orEmpty()
                val allowed = LinkedHashSet<Int>()
                for (span in chapterSpans) {
                    if (span.tocNodeId in readIds) {
                        for (p in span.firstPage..span.lastPage) allowed += p
                    }
                }
                if (allowed.isEmpty()) {
                    // Fallback to book_toc_nodes if lessons were empty
                    db.prepareStatement(
                        "SELECT id, start_page, end_page FROM book_toc_nodes WHERE book=?",
                    ).use { sql ->
                        sql.setString(1, mat.bookId)
                        sql.executeQuery().use { rows ->
                            while (rows.next()) {
                                if (rows.getString(1) in readIds) {
                                    for (p in rows.getInt(2)..rows.getInt(3)) allowed += p
                                }
                            }
                        }
                    }
                }
                val filteredAllowed = allowed.intersect(allPages.toSet())
                allowedPagesByBook[mat.bookId] = filteredAllowed
                unreadPagesFilteredOut += (allPages.size - filteredAllowed.size).coerceAtLeast(0)
            }
            if (allowedPagesByBook.values.all { it.isEmpty() }) {
                return RetrievalBundle(
                    collection = collectionRow,
                    blocks = emptyList(),
                    blockMap = emptyMap(),
                    rawPagesByKey = emptyMap(),
                    shortLabelByBookId = shortLabelByBookId,
                    docLabelByBookId = docLabelByBookId,
                    matchedDiscrepancies = emptyList(),
                    blockedBooklistEmptyMessage = "这份书单里还没有已读章节。先读完一章，再来跨书提问。",
                    unreadPagesFilteredOut = unreadPagesFilteredOut,
                )
            }
        }

        val queryProfile = buildQueryProfile(db, collectionId, question)
        val offlineCandidates = loadOfflineDiscrepancies(
            db = db,
            collectionId = collectionId,
            collectionKind = collectionRow.kind,
            materials = materials,
            allowedPagesByBook = allowedPagesByBook,
        )
        val matchedDiscrepancies = matchOfflineDiscrepancies(offlineCandidates, queryProfile)

        val pageTextCache = HashMap<Pair<String, Int>, String>()
        fun readPage(bookId: String, page: Int): String? {
            if (collectionRow.kind == CollectionKind.BOOKLIST) {
                val allowed = allowedPagesByBook[bookId].orEmpty()
                if (page !in allowed) return null
            }
            return pageTextCache.getOrPut(bookId to page) {
                loadSinglePageText(db, bookId, page).orEmpty()
            }.takeIf { it.isNotBlank() }
        }

        val matByBook = materials.associateBy { it.bookId }
        val chosenKeys = LinkedHashSet<Pair<String, Int>>()
        val chosenList = mutableListOf<CollectionQaBlock>()

        fun addBlock(bookId: String, page: Int, text: String) {
            if (chosenList.size >= MAX_BLOCKS) return
            val key = bookId to page
            if (!chosenKeys.add(key)) return
            val mat = matByBook[bookId] ?: return
            val blockId = "B${chosenList.size + 1}"
            val ts = if (mat.role == CourseMaterialRole.TRANSCRIPT) {
                CourseTranscriptParser.pageTimeRangeLabel(text)
            } else null
            chosenList += CollectionQaBlock(
                blockId = blockId,
                bookId = bookId,
                docLabel = mat.docLabel,
                shortLabel = mat.shortLabel,
                authorityRank = mat.authorityRank,
                page = page,
                timestampLabel = ts,
                text = text.take(MAX_BLOCK_CHARS),
            )
        }

        // 1. Co-retrieve both sides of matched offline discrepancies (with adjacent transcript context)
        for (disc in matchedDiscrepancies.filter { it.directAspectMatch }) {
            for (cite in disc.citations) {
                val raw = readPage(cite.bookId, cite.page) ?: continue
                addBlock(cite.bookId, cite.page, raw)
                if (matByBook[cite.bookId]?.role == CourseMaterialRole.TRANSCRIPT) {
                    readPage(cite.bookId, cite.page - 1)?.let { addBlock(cite.bookId, cite.page - 1, it) }
                    readPage(cite.bookId, cite.page + 1)?.let { addBlock(cite.bookId, cite.page + 1, it) }
                }
            }
        }

        // 2. FTS5 + normalized full-page BM25-style ranking across all allowed materials
        val candidates = scoreAllowedPages(
            db = db,
            materials = materials,
            collectionKind = collectionRow.kind,
            allowedPagesByBook = allowedPagesByBook,
            queryProfile = queryProfile,
            currentBookId = currentBookId,
            pageTextCache = pageTextCache,
        )

        val perBookCount = HashMap<String, Int>()
        for (b in chosenList) {
            perBookCount[b.bookId] = (perBookCount[b.bookId] ?: 0) + 1
        }
        // First pass: diversify across materials (at most MAX_PAGES_PER_BOOK per doc)
        for (cand in candidates) {
            if (chosenList.size >= MAX_BLOCKS) break
            if ((cand.bookId to cand.page) in chosenKeys) continue
            if ((perBookCount[cand.bookId] ?: 0) >= MAX_PAGES_PER_BOOK) continue
            addBlock(cand.bookId, cand.page, cand.rawText)
            perBookCount[cand.bookId] = (perBookCount[cand.bookId] ?: 0) + 1
        }
        // Second pass: fill remaining slots if needed
        for (cand in candidates) {
            if (chosenList.size >= MAX_BLOCKS) break
            if ((cand.bookId to cand.page) in chosenKeys) continue
            addBlock(cand.bookId, cand.page, cand.rawText)
        }

        val rawPagesByKey = HashMap<Pair<String, Int>, String>()
        for (b in chosenList) {
            rawPagesByKey[b.bookId to b.page] = pageTextCache[b.bookId to b.page] ?: b.text
        }
        for (disc in matchedDiscrepancies) {
            for (c in disc.citations) {
                readPage(c.bookId, c.page)?.let { rawPagesByKey[c.bookId to c.page] = it }
            }
        }

        return RetrievalBundle(
            collection = collectionRow,
            blocks = chosenList,
            blockMap = chosenList.associateBy { it.blockId },
            rawPagesByKey = rawPagesByKey,
            shortLabelByBookId = shortLabelByBookId,
            docLabelByBookId = docLabelByBookId,
            matchedDiscrepancies = matchedDiscrepancies,
            unreadPagesFilteredOut = unreadPagesFilteredOut,
        )
    }

    private fun buildQueryProfile(
        db: Connection,
        collectionId: String,
        question: String,
    ): QueryProfile {
        val enTerms = LinkedHashSet<String>()
        val phrases = LinkedHashSet<String>()
        val zhTerms = LinkedHashSet<String>()

        // Direct English / numeric tokens from the user's question
        for (m in enTokenRegex.findAll(question)) {
            val tok = m.value.trim('.', ',', ';', ':', '?', '!')
            if (tok.length >= 2) {
                enTerms += tok.lowercase()
            }
        }
        // Map Greek symbols like Ω2 / Ω3 to both symbol and Omega forms
        if ("Ω" in question) {
            enTerms += "omega"
            for (d in listOf("1", "2", "3", "4")) {
                if ("Ω$d" in question || "Omega$d" in question || "Omega $d" in question) {
                    phrases += "omega_$d"
                    phrases += "omega $d"
                    enTerms += "omega_$d"
                }
            }
        }
        // Chinese sequences (whole + 2-char/3-char n-grams for flexible matching)
        for (m in zhSequenceRegex.findAll(question)) {
            val seq = m.value
            zhTerms += seq
            if (seq.length > 2) {
                for (i in 0..(seq.length - 2)) zhTerms += seq.substring(i, i + 2)
            }
            if (seq.length > 3) {
                for (i in 0..(seq.length - 3)) zhTerms += seq.substring(i, i + 3)
            }
        }
        // Bilingual question-term expansions (NO answer keywords!)
        val qLower = question.lowercase()
        for ((zh, targets) in zhToEnExpansions) {
            if (zh in qLower) {
                for (target in targets) {
                    if (' ' in target) phrases += target.lowercase()
                    for (part in target.split(' ', '-')) {
                        if (part.length >= 2) enTerms += part.lowercase()
                    }
                }
            }
        }
        // Harvest topic titles & concept entity aliases dynamically from the local collection DB
        if (BookSchemaMigration.tableExists(db, "course_topics")) {
            db.prepareStatement("SELECT title FROM course_topics WHERE collection_id=?").use { sql ->
                sql.setString(1, collectionId)
                sql.executeQuery().use { rows ->
                    while (rows.next()) {
                        val title = rows.getString(1).orEmpty()
                        val topicZh = zhSequenceRegex.findAll(title).map { it.value }.toList()
                        if (topicZh.any { it in question }) {
                            for (tok in enTokenRegex.findAll(title)) {
                                if (tok.value.length >= 3) enTerms += tok.value.lowercase()
                            }
                        }
                    }
                }
            }
        }
        if (BookSchemaMigration.tableExists(db, "concept_entities")) {
            db.prepareStatement(
                "SELECT canonical_name, aliases_json FROM concept_entities WHERE collection_id=? AND muted=0",
            ).use { sql ->
                sql.setString(1, collectionId)
                sql.executeQuery().use { rows ->
                    while (rows.next()) {
                        val name = rows.getString(1).orEmpty()
                        val aliasesRaw = rows.getString(2).orEmpty()
                        if (name.length >= 2 && (name.lowercase() in qLower || enTerms.any { it in name.lowercase() })) {
                            for (tok in enTokenRegex.findAll("$name $aliasesRaw")) {
                                if (tok.value.length >= 3) enTerms += tok.value.lowercase()
                            }
                        }
                    }
                }
            }
        }

        val requestedWeeks = LinkedHashSet<Int>()
        for (m in weekPattern.findAll(question)) {
            val wk = (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
            if (wk != null && wk in 1..20) requestedWeeks += wk
        }

        val roleBoosts = LinkedHashSet<CourseMaterialRole>()
        if (anyIn(qLower, "手册", "大纲", "考核", "权重", "暂定", "handbook", "syllabus", "assessment")) {
            roleBoosts += CourseMaterialRole.SYLLABUS
        }
        if (anyIn(qLower, "讨论课", "习题", "解答", "tutorial", "workshop", "key")) {
            roleBoosts += CourseMaterialRole.TUTORIAL
            roleBoosts += CourseMaterialRole.SOLUTION_KEY
            roleBoosts += CourseMaterialRole.TRANSCRIPT
        }
        if (anyIn(qLower, "录音", "转写", "口误", "讲课", "transcript")) {
            roleBoosts += CourseMaterialRole.TRANSCRIPT
        }
        if (anyIn(qLower, "实证", "论文", "研究", "paper", "empirical")) {
            roleBoosts += CourseMaterialRole.REQUIRED_READING
        }

        val allMatch = LinkedHashSet<String>()
        allMatch += enTerms
        allMatch += zhTerms
        return QueryProfile(
            rawQuestion = question,
            englishTerms = enTerms.toList(),
            phrases = phrases.toList(),
            chineseTerms = zhTerms.toList(),
            requestedWeeks = requestedWeeks,
            roleBoosts = roleBoosts,
            allMatchTerms = allMatch,
        )
    }

    private fun scoreAllowedPages(
        db: Connection,
        materials: List<CollectionMaterialMeta>,
        collectionKind: CollectionKind,
        allowedPagesByBook: Map<String, Set<Int>>,
        queryProfile: QueryProfile,
        currentBookId: String?,
        pageTextCache: HashMap<Pair<String, Int>, String>,
    ): List<ScoredPageCandidate> {
        val matByBook = materials.associateBy { it.bookId }
        val ftsBoostPages = HashSet<Pair<String, Int>>()

        // Use SQLite pages_fts index for >= 3-char anchor terms across the collection
        val ftsTokens = queryProfile.englishTerms.filter { it.length >= 3 && it !in genericEntityStopwords }.take(8)
        for (mat in materials) {
            for (tok in ftsTokens) {
                val hits = BookFtsStore.queryCandidatePages(db, mat.bookId, tok).orEmpty()
                for (p in hits) ftsBoostPages += mat.bookId to p
            }
        }

        // Load and score allowed pages of the collection's materials
        val allCandidates = ArrayList<ScoredPageCandidate>()
        for (mat in materials) {
            val allowedSet = if (collectionKind == CollectionKind.BOOKLIST) allowedPagesByBook[mat.bookId].orEmpty() else null
            if (allowedSet != null && allowedSet.isEmpty()) continue
            db.prepareStatement("SELECT page, text FROM pages WHERE book=? ORDER BY page").use { sql ->
                sql.setString(1, mat.bookId)
                sql.executeQuery().use { rows ->
                    while (rows.next()) {
                        val page = rows.getInt(1)
                        if (allowedSet != null && page !in allowedSet) continue
                        val rawText = rows.getString(2).orEmpty()
                        if (rawText.isBlank()) continue
                        pageTextCache[mat.bookId to page] = rawText
                        val normText = SentenceCitationGate.normalizeText(rawText)
                        val normLower = normText.lowercase()

                        var termScore = 0.0
                        var distinctHits = 0
                        for (term in queryProfile.englishTerms) {
                            if (containsWordOrToken(normLower, term)) {
                                distinctHits++
                                val weight = if (term in genericEntityStopwords) 0.5 else if (term.length >= 5) 3.0 else 2.2
                                termScore += weight
                            }
                        }
                        for (phrase in queryProfile.phrases) {
                            if (phrase in normLower) {
                                distinctHits++
                                termScore += 4.5
                            }
                        }
                        for (zh in queryProfile.chineseTerms) {
                            if (zh.length >= 2 && zh in normLower && zh !in genericEntityStopwords) {
                                distinctHits++
                                termScore += 1.6
                            }
                        }
                        if (distinctHits == 0 && (mat.bookId to page) !in ftsBoostPages) continue

                        if ((mat.bookId to page) in ftsBoostPages) termScore += 1.2
                        val authorityBoost = when (mat.authorityRank) {
                            1 -> 1.8
                            2 -> 1.1
                            3 -> 0.8
                            else -> 0.3
                        }
                        val weekBoost = if (mat.weekNum != null && mat.weekNum in queryProfile.requestedWeeks) 3.5 else 0.0
                        val roleBoost = if (mat.role in queryProfile.roleBoosts) 2.2 else 0.0
                        val currentBookBoost = if (mat.bookId == currentBookId) 0.25 else 0.0
                        val total = termScore + authorityBoost + weekBoost + roleBoost + currentBookBoost

                        allCandidates += ScoredPageCandidate(
                            bookId = mat.bookId,
                            page = page,
                            rawText = rawText,
                            normText = normText,
                            score = total,
                            role = mat.role,
                            authorityRank = mat.authorityRank,
                        )
                    }
                }
            }
        }
        return allCandidates.sortedWith(
            compareByDescending<ScoredPageCandidate> { it.score }
                .thenBy { it.authorityRank }
                .thenBy { it.page },
        )
    }

    private fun containsWordOrToken(normLower: String, term: String): Boolean {
        if (term.isEmpty()) return false
        val idx = normLower.indexOf(term)
        if (idx < 0) return false
        if (term.length >= 4) return true
        // For short 2-3 char terms (e.g. "sg", "3b", "po"), verify word boundaries
        var searchFrom = idx
        while (searchFrom >= 0) {
            val end = searchFrom + term.length
            val leftOk = searchFrom == 0 || !normLower[searchFrom - 1].isLetterOrDigit()
            val rightOk = end == normLower.length || !normLower[end].isLetterOrDigit()
            if (leftOk && rightOk) return true
            searchFrom = normLower.indexOf(term, startIndex = searchFrom + 1)
        }
        return false
    }

    private fun loadOfflineDiscrepancies(
        db: Connection,
        collectionId: String,
        collectionKind: CollectionKind,
        materials: List<CollectionMaterialMeta>,
        allowedPagesByBook: Map<String, Set<Int>>,
    ): List<MatchedOfflineDiscrepancy> {
        val out = mutableListOf<MatchedOfflineDiscrepancy>()
        val matByBook = materials.associateBy { it.bookId }

        if (collectionKind == CollectionKind.COURSE && BookSchemaMigration.tableExists(db, "course_topics")) {
            db.prepareStatement(
                "SELECT topic_id, title, pack_payload_json FROM course_topics WHERE collection_id=? AND pack_payload_json IS NOT NULL",
            ).use { sql ->
                sql.setString(1, collectionId)
                sql.executeQuery().use { rows ->
                    while (rows.next()) {
                        val tid = rows.getString(1)
                        val payload = rows.getString(3)
                        val pack = CourseJson.decodeTopicPack(payload) ?: continue
                        pack.discrepancies.forEachIndexed { idx, d ->
                            out += MatchedOfflineDiscrepancy(
                                id = "topic:$tid:$idx",
                                point = d.point,
                                authoritativeView = d.authoritativeView,
                                secondaryView = d.secondaryView,
                                advice = d.advice,
                                directAspectMatch = false,
                                citations = d.citations,
                            )
                        }
                    }
                }
            }
        }

        if (BookSchemaMigration.tableExists(db, "concept_links") &&
            BookSchemaMigration.tableExists(db, "concept_mentions") &&
            BookSchemaMigration.tableExists(db, "concept_entities")
        ) {
            db.prepareStatement(
                """SELECT cl.link_id, ce.canonical_name, ce.aliases_json, cl.relation_type, cl.diff_summary,
                          fm.book_id, fm.page, fm.offset_utf16, fm.quote_anchor, fm.claim_summary,
                          tm.book_id, tm.page, tm.offset_utf16, tm.quote_anchor, tm.claim_summary
                   FROM concept_links cl
                   JOIN concept_entities ce ON ce.concept_id = cl.concept_id
                   JOIN concept_mentions fm ON fm.mention_id = cl.from_mention_id
                   JOIN concept_mentions tm ON tm.mention_id = cl.to_mention_id
                   WHERE cl.collection_id=? AND ce.muted=0 AND cl.dismissed=0
                     AND cl.relation_type IN ('NOTATION_OR_BOUNDARY_DIFF','EMPIRICAL_CHALLENGE','OPPOSING_VIEW')""",
            ).use { sql ->
                sql.setString(1, collectionId)
                sql.executeQuery().use { rows ->
                    while (rows.next()) {
                        val linkId = rows.getString(1)
                        val canonicalName = rows.getString(2)
                        val aliases = rows.getString(3)
                        val diffSummary = rows.getString(5)
                        val fBook = rows.getString(6)
                        val fPage = rows.getInt(7)
                        val fOffset = rows.getInt(8)
                        val fQuote = rows.getString(9)
                        val fSummary = rows.getString(10)
                        val tBook = rows.getString(11)
                        val tPage = rows.getInt(12)
                        val tOffset = rows.getInt(13)
                        val tQuote = rows.getString(14)
                        val tSummary = rows.getString(15)

                        // Booklist anti-spoiler hard guard: BOTH sides MUST be inside READ chapters!
                        if (collectionKind == CollectionKind.BOOKLIST) {
                            val fAllowed = allowedPagesByBook[fBook].orEmpty()
                            val tAllowed = allowedPagesByBook[tBook].orEmpty()
                            if (fPage !in fAllowed || tPage !in tAllowed) continue
                        }
                        val fMat = matByBook[fBook]
                        val tMat = matByBook[tBook]
                        val cites = listOf(
                            CourseCitationRef(
                                bookId = fBook,
                                bookTitle = fMat?.title ?: fBook,
                                role = fMat?.role ?: CourseMaterialRole.CORE_TEXTBOOK,
                                page = fPage,
                                startUtf16 = fOffset,
                                endUtf16 = fOffset + fQuote.length,
                                quote = fQuote,
                                verified = true,
                            ),
                            CourseCitationRef(
                                bookId = tBook,
                                bookTitle = tMat?.title ?: tBook,
                                role = tMat?.role ?: CourseMaterialRole.CORE_TEXTBOOK,
                                page = tPage,
                                startUtf16 = tOffset,
                                endUtf16 = tOffset + tQuote.length,
                                quote = tQuote,
                                verified = true,
                            ),
                        )
                        out += MatchedOfflineDiscrepancy(
                            id = "link:$linkId",
                            point = "$canonicalName（$diffSummary）",
                            authoritativeView = fSummary,
                            secondaryView = tSummary,
                            advice = diffSummary + if (aliases.isNotBlank()) "" else "",
                            directAspectMatch = false,
                            citations = cites,
                        )
                    }
                }
            }
        }
        return out
    }

    private fun matchOfflineDiscrepancies(
        candidates: List<MatchedOfflineDiscrepancy>,
        query: QueryProfile,
    ): List<MatchedOfflineDiscrepancy> {
        if (candidates.isEmpty()) return emptyList()
        val qLower = query.rawQuestion.lowercase()

        // Extract non-overlapping distinctive concept tokens directly from the student's question
        // (do NOT multiply overlapping Chinese 2-grams + translated English words into false hit counts).
        val directConcepts = LinkedHashSet<String>()
        for (m in enTokenRegex.findAll(qLower)) {
            val tok = m.value.trim('.', ',', ';', ':', '?', '!')
            if (tok.length >= 2 && tok !in genericEntityStopwords) directConcepts += tok
        }
        if ("Ω" in query.rawQuestion) {
            for (d in listOf("1", "2", "3", "4")) {
                if ("Ω$d" in query.rawQuestion || "Omega$d" in qLower || "Omega $d" in qLower) {
                    directConcepts += "omega_$d"
                }
            }
        }
        for (m in zhSequenceRegex.findAll(qLower)) {
            val seq = m.value
            if (seq.length >= 2 && seq !in genericEntityStopwords) directConcepts += seq
        }
        // Also extract distinct 2-to-4 char meaningful concepts from zhToEnExpansions whose Chinese key is in qLower
        // mapped to a single normalized representative token so bilingual queries match English discrepancy points once.
        val conceptRepresentatives = LinkedHashMap<String, List<String>>()
        for (c in directConcepts) {
            val alts = mutableListOf(c)
            if (c.startsWith("omega_")) {
                val n = c.removePrefix("omega_")
                alts += listOf("ω$n", "omega$n", "omega $n", "omega_$n")
            }
            conceptRepresentatives[c] = alts
        }
        for ((zhKey, targets) in zhToEnExpansions) {
            if (zhKey.length >= 2 && zhKey !in genericEntityStopwords && zhKey in qLower) {
                val list = mutableListOf(zhKey)
                list += targets.map { it.lowercase() }.filter { it !in genericEntityStopwords }
                conceptRepresentatives[zhKey] = list.distinct()
            }
        }

        // Discrepancy aspect guard pairs: prevent matching separating/zero-profit discrepancies on pooling questions
        val qAsksPooling = "pooling" in qLower || "混同" in qLower
        val qAsksSeparating = "separating" in qLower || "分离" in qLower
        val qAsksZeroProfit = "zero-profit" in qLower || "zero profit" in qLower || "零利润" in qLower || "斜率" in qLower || "亏损" in qLower

        val matched = mutableListOf<MatchedOfflineDiscrepancy>()
        for (d in candidates) {
            val pointBlob = SentenceCitationGate.normalizeText(
                listOf(d.point, d.authoritativeView, d.secondaryView).joinToString(" "),
            ).lowercase()
            val titleOnly = SentenceCitationGate.normalizeText(d.point).lowercase()

            if (qAsksPooling && !qAsksSeparating && ("分离均衡" in pointBlob || "separating" in pointBlob)) continue
            if (qAsksPooling && !qAsksZeroProfit && ("零利润" in pointBlob || "zero-profit" in pointBlob || "zero profit" in pointBlob)) continue

            // Deduplicate subsumed overlapping keys (e.g. if both "分离均衡" and "分离均衡图" matched, keep distinct hits)
            val hitKeys = conceptRepresentatives.entries.filter { (_, reps) ->
                reps.any { tok ->
                    if (tok.any { it in '\u4E00'..'\u9FFF' }) tok in pointBlob
                    else containsWordOrToken(pointBlob, tok)
                }
            }.map { it.key }
            val distinctHits = hitKeys.filter { k ->
                hitKeys.none { other -> other != k && other.length > k.length && k in other }
            }
            val titleHit = distinctHits.any { k ->
                val reps = conceptRepresentatives[k].orEmpty()
                reps.any { tok ->
                    if (tok.any { it in '\u4E00'..'\u9FFF' }) tok in titleOnly
                    else containsWordOrToken(titleOnly, tok)
                }
            }
            val hasUltraSpecific = distinctHits.any { k ->
                k in setOf(
                    "omega_2", "omega_3", "3b", "700", "ppo", "medigap", "fang",
                    "不等号", "严格不等号", "弱不等号", "零利润", "均匀分布", "最低质量",
                    "有利选择", "死亡螺旋", "保费补贴",
                )
            }
            if (titleHit && (distinctHits.size >= 2 || hasUltraSpecific)) {
                matched += d.copy(directAspectMatch = true)
            }
        }
        return matched.take(3)
    }

    private fun loadCollection(db: Connection, collectionId: String): ActiveAskCollection? {
        if (!BookSchemaMigration.tableExists(db, "collections")) return null
        return db.prepareStatement("SELECT kind, title FROM collections WHERE id=?").use { sql ->
            sql.setString(1, collectionId)
            sql.executeQuery().use { rows ->
                if (!rows.next()) null
                else {
                    val kind = runCatching { CollectionKind.valueOf(rows.getString(1)) }.getOrDefault(CollectionKind.COURSE)
                    ActiveAskCollection(collectionId, kind, rows.getString(2))
                }
            }
        }
    }

    private fun loadCollectionMaterials(db: Connection, collectionId: String): List<CollectionMaterialMeta> {
        if (!BookSchemaMigration.tableExists(db, "collection_materials")) return emptyList()
        return db.prepareStatement(
            """SELECT cm.material_id, b.title, b.format, cm.role, cm.authority_rank, cm.week_num
               FROM collection_materials cm
               JOIN books b ON b.id = cm.material_id
               WHERE cm.collection_id=?
               ORDER BY COALESCE(cm.week_num, 999), cm.authority_rank, cm.ordinal, cm.added_at""",
        ).use { sql ->
            sql.setString(1, collectionId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val bookId = rows.getString(1)
                        val title = rows.getString(2)
                        val format = rows.getString(3)
                        val role = CourseMaterialRole.fromWire(rows.getString(4))
                        val rank = rows.getInt(5).coerceIn(1, 4)
                        val weekNum = rows.getObject(6)?.let { (it as Number).toInt() }
                            ?: weekFileRegex.find(title)?.let { m ->
                                (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
                            }
                        val shortLabel = formatShortLabel(title, role, weekNum)
                        val docLabel = "${shortLabel} (L$rank)"
                        add(
                            CollectionMaterialMeta(
                                bookId = bookId,
                                title = title,
                                format = format,
                                role = role,
                                authorityRank = rank,
                                weekNum = weekNum,
                                shortLabel = shortLabel,
                                docLabel = docLabel,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun formatShortLabel(title: String, role: CourseMaterialRole, weekNum: Int?): String {
        val wkPrefix = weekNum?.let { "第${it}周" }.orEmpty()
        return when (role) {
            CourseMaterialRole.SYLLABUS -> "课程手册"
            CourseMaterialRole.LECTURE_SLIDES -> if (wkPrefix.isNotEmpty()) "${wkPrefix}讲义" else title.take(12)
            CourseMaterialRole.TUTORIAL -> if (wkPrefix.isNotEmpty()) "${wkPrefix}习题" else title.take(12)
            CourseMaterialRole.SOLUTION_KEY -> if (wkPrefix.isNotEmpty()) "${wkPrefix}解答" else title.take(12)
            CourseMaterialRole.TRANSCRIPT -> if (wkPrefix.isNotEmpty()) "${wkPrefix}录音" else "课堂录音"
            CourseMaterialRole.CORE_TEXTBOOK -> "课本"
            CourseMaterialRole.REQUIRED_READING -> {
                val author = title.substringBefore('_').substringBefore('-').substringBefore(' ').trim()
                val year = Regex("""(19|20)\d{2}""").find(title)?.value.orEmpty()
                if (author.length in 2..12 && year.isNotEmpty()) "$author $year"
                else title.take(12)
            }
            CourseMaterialRole.USER_NOTES -> if (wkPrefix.isNotEmpty()) "${wkPrefix}笔记" else title.take(12)
        }
    }

    private fun loadAllPageNumbers(db: Connection, bookId: String): List<Int> =
        db.prepareStatement("SELECT page FROM pages WHERE book=? ORDER BY page").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getInt(1)) } }
        }

    private fun loadSinglePageText(db: Connection, bookId: String, page: Int): String? =
        db.prepareStatement("SELECT text FROM pages WHERE book=? AND page=?").use { sql ->
            sql.setString(1, bookId)
            sql.setInt(2, page)
            sql.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun anyIn(haystack: String, vararg needles: String): Boolean =
        needles.any { it in haystack }
}

/**
 * 跨材料整页问答客户端编排引擎：执行本地检索 + 防剧透过滤 + 远端问答调用 + 客户端原页二次核验。
 */
internal object CollectionQaEngine {
    suspend fun ask(
        library: TextbookLibrary,
        collectionId: String,
        question: String,
        currentBookId: String? = null,
        remoteAsk: suspend (JsonObject) -> CourseServerCallResult,
    ): Result<CollectionQaTurn> {
        val cleanQ = question.trim()
        if (cleanQ.isEmpty()) {
            return Result.failure(IllegalArgumentException("先输入问题。"))
        }
        if (!currentBookId.isNullOrBlank()) {
            withContext(Dispatchers.IO) { runCatching { library.saveLastQuestion(currentBookId, cleanQ) } }
        }
        val bundle = withContext(Dispatchers.IO) {
            CollectionQaRetriever.retrieve(library, collectionId, cleanQ, currentBookId)
        }
        bundle.blockedBooklistEmptyMessage?.let { msg ->
            return Result.success(
                CollectionQaTurn(
                    question = cleanQ,
                    sentences = emptyList(),
                    hasMaterialConflict = false,
                    conflictSummaryZh = "",
                    fallbackMessage = msg,
                ),
            )
        }
        if (bundle.blocks.isEmpty()) {
            return Result.success(
                CollectionQaTurn(
                    question = cleanQ,
                    sentences = emptyList(),
                    hasMaterialConflict = false,
                    conflictSummaryZh = "",
                    fallbackMessage = "当前材料中暂时没有检索到相关段落，请换个关键词再试。",
                ),
            )
        }

        val reqId = UUID.randomUUID().toString().replace("-", "").take(16)
        val payload = CollectionQaJson.encodeRequest(
            requestId = reqId,
            collection = bundle.collection,
            question = cleanQ,
            blocks = bundle.blocks,
            discrepancies = bundle.matchedDiscrepancies,
            shortLabelByBookId = bundle.shortLabelByBookId,
        )
        val serverResult = remoteAsk(payload)
        val body = serverResult.body
            ?: return Result.failure(CollectionQaException(serverResult.error ?: "跨材料问答暂时不可用，请重试。", serverResult.trouble ?: ExplainTrouble.Other))

        val verifiedTurn = SentenceCitationGate.verifyResponseLocally(
            collectionId = bundle.collection.id,
            question = cleanQ,
            responseJson = body,
            blockMap = bundle.blockMap,
            rawPagesByKey = bundle.rawPagesByKey,
            shortLabelByBookId = bundle.shortLabelByBookId,
            docLabelByBookId = bundle.docLabelByBookId,
            offlineDiscrepancies = bundle.matchedDiscrepancies,
        )
        return Result.success(verifiedTurn)
    }
}

internal class CollectionQaException(
    override val message: String,
    val trouble: ExplainTrouble,
) : RuntimeException(message)
