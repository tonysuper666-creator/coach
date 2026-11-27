package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*

internal data class GuideExcerpt(val book: Textbook, val anchor: BookCompanionAnchor, val region: GuideRegion)
internal data class GuidePaper(
    val bookId: String,
    val title: String,
    val author: String,
    val year: String,
    val keyword: String,
    val claim: String,
    val source: GuideExcerpt,
)
internal data class GuideAnswerSentence(val text: String, val sources: List<GuideExcerpt>)
internal data class GuideAnswer(val question: String, val sentences: List<GuideAnswerSentence>)
internal data class LiteratureGuide(
    val question: String,
    val thread: String,
    val papers: List<GuidePaper>,
    val answers: List<GuideAnswer>,
)

internal data class GuideOcrCheck(
    val ref: Int,
    val bookId: String,
    val pdfPage: Int,
    val similarity: Double,
    val passed: Boolean,
    val detail: String = "",
)

internal data class GuideItemFailure(
    val itemKey: String,
    val label: String,
    val rule: String,
    val detail: String,
)

internal data class GuideVerification(
    val question: String?,
    val thread: String?,
    val papers: Map<String, GuidePaper>,
    val answers: Map<Int, GuideAnswer>,
    val failures: List<GuideItemFailure>,
    val ocrChecks: List<GuideOcrCheck>,
    val rejectedQuotes: Set<String>,
) {
    fun toComplete(books: List<Textbook>, questions: List<String>): LiteratureGuide? {
        if (failures.isNotEmpty() || question == null || thread == null) return null
        if (books.any { it.id !in papers } || questions.indices.any { it !in answers }) return null
        return LiteratureGuide(
            question = question,
            thread = thread,
            papers = books.map { papers.getValue(it.id) },
            answers = questions.indices.map { answers.getValue(it) },
        )
    }
}

internal class LiteratureOcrUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal fun interface LiteratureOcrVerifier {
    fun ocrPageText(book: Textbook, pdfPage: Int): String
    fun ocrQuoteCropText(book: Textbook, pdfPage: Int, quote: String): String? = null
}

internal class CachingOcrVerifier(private val delegate: LiteratureOcrVerifier) : LiteratureOcrVerifier {
    private val cache = HashMap<Pair<String, Int>, Result<String>>()
    override fun ocrPageText(book: Textbook, pdfPage: Int): String =
        cache.getOrPut(book.id to pdfPage) { runCatching { delegate.ocrPageText(book, pdfPage) } }.getOrThrow()
    override fun ocrQuoteCropText(book: Textbook, pdfPage: Int, quote: String): String? =
        delegate.ocrQuoteCropText(book, pdfPage, quote)
}

internal class LiteratureInput(
    val books: List<Textbook>,
    val questions: List<String>,
    metaOverrides: Map<String, LiteraturePaperMeta> = emptyMap(),
    val revision: Int = 0,
) {
    init {
        require(books.size in 3..5 && books.distinctBy { it.id }.size == books.size)
        require(questions.size in 1..7 && questions.all { it.isNotBlank() && it.length <= 400 })
        require(books.none { it.hasPendingOcr }) { "扫描页正在识别，完成后再生成导读。" }
    }

    val paperMetas: Map<String, LiteraturePaperMeta> = books.associate { book ->
        val base = book.literatureMeta ?: LiteraturePaperMeta(book.id, LiteratureTextNorm.decodeHtmlEntities(book.title))
        val override = metaOverrides[book.id]
        val merged = if (override != null) {
            LiteraturePaperMeta(
                bookId = book.id,
                title = LiteratureTextNorm.decodeHtmlEntities(override.title).ifBlank { base.title },
                author = LiteratureTextNorm.decodeHtmlEntities(override.author),
                year = override.year.trim(),
            )
        } else {
            base.copy(
                title = LiteratureTextNorm.decodeHtmlEntities(base.title),
                author = LiteratureTextNorm.decodeHtmlEntities(base.author),
            )
        }
        book.id to merged
    }

    internal val allCandidatesByBook: Map<String, List<GuideExcerpt>> = books.associate { book ->
        book.id to LiteraturePassages.extractCandidates(book)
    }

    val excerpts: List<GuideExcerpt> = selectBalancedExcerpts()

    private fun selectBalancedExcerpts(): List<GuideExcerpt> {
        val rankedByBook = books.associate { book ->
            val meta = paperMetas.getValue(book.id)
            book.id to LiteraturePassages.rankForPaper(allCandidatesByBook.getValue(book.id), meta, questions)
        }
        val overhead = buildRequestJson(emptyList(), mode = "initial", rev = revision).toString().toByteArray().size
        val totalBudget = (REQUEST_BYTE_BUDGET - overhead).coerceAtLeast(0)

        fun blockBytes(e: GuideExcerpt, ref: Int): Int = blockJson(e, ref).toString().toByteArray().size + 1

        val chosen = books.associate { it.id to mutableListOf<GuideExcerpt>() }
        val usedBytes = books.associate { it.id to 0 }.toMutableMap()
        val cursors = books.associate { it.id to 0 }.toMutableMap()
        var runningRef = 0
        var totalUsed = 0

        // Pass 1: guarantee at least MIN_SENTENCES_PER_PAPER sentences per paper (where available).
        for (book in books) {
            val list = rankedByBook.getValue(book.id)
            val target = minOf(MIN_SENTENCES_PER_PAPER, list.size)
            while (cursors.getValue(book.id) < target) {
                val idx = cursors.getValue(book.id)
                val item = list[idx]
                val cost = blockBytes(item, runningRef)
                if (totalUsed + cost > totalBudget) break
                chosen.getValue(book.id) += item
                usedBytes[book.id] = usedBytes.getValue(book.id) + cost
                totalUsed += cost
                cursors[book.id] = idx + 1
                runningRef++
            }
        }

        // Pass 2: water-filling equal byte budget across all papers with remaining candidates.
        while (totalUsed < totalBudget) {
            val active = books.filter { cursors.getValue(it.id) < rankedByBook.getValue(it.id).size }
            if (active.isEmpty()) break
            val remainingTotal = totalBudget - totalUsed
            val equalTarget = totalUsed / books.size + maxOf(1, remainingTotal / active.size)
            var progressed = false
            for (book in active) {
                val list = rankedByBook.getValue(book.id)
                while (cursors.getValue(book.id) < list.size) {
                    val idx = cursors.getValue(book.id)
                    val item = list[idx]
                    val cost = blockBytes(item, runningRef)
                    if (totalUsed + cost > totalBudget) break
                    if (usedBytes.getValue(book.id) + cost > equalTarget && progressed) break
                    chosen.getValue(book.id) += item
                    usedBytes[book.id] = usedBytes.getValue(book.id) + cost
                    totalUsed += cost
                    cursors[book.id] = idx + 1
                    runningRef++
                    progressed = true
                    if (usedBytes.getValue(book.id) >= equalTarget) break
                }
            }
            if (!progressed) break
        }

        val ordered = books.flatMap { book ->
            chosen.getValue(book.id).sortedWith(compareBy({ it.anchor.pdfPage }, { it.anchor.startUtf16 }))
        }
        val finalList = ordered.toMutableList()
        while (finalList.size > books.size && buildRequestJson(finalList, mode = "initial", rev = revision).toString().toByteArray().size > REQUEST_BYTE_BUDGET) {
            // Trim from the book that currently has the most excerpts so no paper is starved.
            val largestBook = finalList.groupingBy { it.book.id }.eachCount().maxBy { it.value }.key
            val removeIdx = finalList.indexOfLast { it.book.id == largestBook }
            if (removeIdx < 0) break
            finalList.removeAt(removeIdx)
        }
        require(finalList.isNotEmpty() && buildRequestJson(finalList, mode = "initial", rev = revision).toString().toByteArray().size <= REQUEST_BYTE_BUDGET) {
            "所选文献的候选句超出长度上限，请减少篇数或缩短思考题。"
        }
        return finalList
    }

    internal fun blockJson(e: GuideExcerpt, ref: Int): JsonObject {
        val meta = paperMetas.getValue(e.book.id)
        return buildJsonObject {
            put("ref", ref)
            put("bookId", e.book.id)
            if (meta.author.isNotBlank()) put("author", meta.author) else put("title", meta.title.take(60))
            if (meta.year.isNotBlank()) put("year", meta.year)
            put("page", e.anchor.pdfPage)
            put("text", e.anchor.quoteAnchor)
        }
    }

    internal fun papersJson(): JsonArray = buildJsonArray {
        books.forEach { book ->
            val meta = paperMetas.getValue(book.id)
            add(buildJsonObject {
                put("bookId", meta.bookId)
                put("title", meta.title)
                put("author", meta.author)
                put("year", meta.year)
            })
        }
    }

    internal fun buildRequestJson(
        blocks: List<GuideExcerpt>,
        mode: String,
        rev: Int,
        repairSpec: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("kind", "literatureGuide")
        put("selectionVersion", SELECTION_VERSION)
        put("mode", mode)
        put("revision", rev)
        put("papers", papersJson())
        put("questions", JsonArray(questions.map(::JsonPrimitive)))
        if (repairSpec != null) put("repair", repairSpec)
        put("blocks", buildJsonArray { blocks.forEachIndexed { ref, e -> add(blockJson(e, ref)) } })
    }

    fun request(): JsonObject = buildRequestJson(excerpts, mode = "initial", rev = revision)

    /** Item-by-item verification with independent 300 dpi Tesseract OCR check (>= 0.98 similarity). */
    fun verify(body: JsonObject, ocr: LiteratureOcrVerifier): GuideVerification {
        val cachedOcr = (ocr as? CachingOcrVerifier) ?: CachingOcrVerifier(ocr)
        return verifyItems(
            body = body,
            pool = excerpts,
            needQuestion = true,
            needThread = true,
            expectedBookIds = books.map { it.id },
            expectedQuestionIndices = questions.indices.toList(),
            ocr = cachedOcr,
        )
    }

    /** Build a single targeted repair request containing ONLY failed items and top-30 relevant sentences per failed item. */
    fun buildRepair(first: GuideVerification): LiteratureRepairInput {
        val needQuestion = first.question == null
        val needThread = first.thread == null
        val missingBookIds = books.map { it.id }.filter { it !in first.papers }
        val failedQuestionIndices = questions.indices.filter { it !in first.answers }
        val pool = LinkedHashMap<String, GuideExcerpt>()

        fun addExcerpt(e: GuideExcerpt) {
            val key = "${e.book.id}#${e.anchor.pdfPage}#${e.anchor.startUtf16}"
            pool.putIfAbsent(key, e)
        }

        for (qIdx in failedQuestionIndices) {
            LiteraturePassages.topAcrossBooksForQuestion(
                books = books,
                metas = paperMetas,
                candidatesByBook = allCandidatesByBook,
                question = questions[qIdx],
                excludedQuotes = first.rejectedQuotes,
                limit = REPAIR_TOP_PER_ITEM,
            ).forEach(::addExcerpt)
        }
        for (bookId in missingBookIds) {
            val meta = paperMetas.getValue(bookId)
            LiteraturePassages.rankForPaper(
                candidates = allCandidatesByBook.getValue(bookId),
                meta = meta,
                questions = questions,
                excludedQuotes = first.rejectedQuotes,
            ).take(REPAIR_TOP_PER_ITEM).forEach(::addExcerpt)
        }
        if (pool.isEmpty()) {
            // Only question or thread failed; supply top sentences across all books.
            for (book in books) {
                val meta = paperMetas.getValue(book.id)
                LiteraturePassages.rankForPaper(
                    candidates = allCandidatesByBook.getValue(book.id),
                    meta = meta,
                    questions = questions,
                    excludedQuotes = first.rejectedQuotes,
                ).take(10).forEach(::addExcerpt)
            }
        }
        val repairSpec = buildJsonObject {
            put("needQuestion", needQuestion)
            put("needThread", needThread)
            put("missingBookIds", JsonArray(missingBookIds.map(::JsonPrimitive)))
            put("failedQuestions", buildJsonArray {
                failedQuestionIndices.forEach { idx ->
                    add(buildJsonObject {
                        put("questionIndex", idx)
                        put("question", questions[idx])
                    })
                }
            })
        }
        val selected = pool.values.sortedWith(compareBy({ books.indexOf(it.book) }, { it.anchor.pdfPage }, { it.anchor.startUtf16 })).toMutableList()
        while (selected.size > 1 && buildRequestJson(selected, mode = "repair", rev = revision + 1, repairSpec = repairSpec).toString().toByteArray().size > REQUEST_BYTE_BUDGET) {
            selected.removeAt(selected.lastIndex)
        }
        return LiteratureRepairInput(
            parent = this,
            needQuestion = needQuestion,
            needThread = needThread,
            missingBookIds = missingBookIds,
            failedQuestionIndices = failedQuestionIndices,
            excerpts = selected,
            repairSpec = repairSpec,
        )
    }

    internal fun verifyItems(
        body: JsonObject,
        pool: List<GuideExcerpt>,
        needQuestion: Boolean,
        needThread: Boolean,
        expectedBookIds: List<String>,
        expectedQuestionIndices: List<Int>,
        ocr: CachingOcrVerifier,
    ): GuideVerification {
        val failures = mutableListOf<GuideItemFailure>()
        val ocrChecks = mutableListOf<GuideOcrCheck>()
        val rejectedQuotes = mutableSetOf<String>()
        val refOutcomes = HashMap<Int, Pair<GuideExcerpt?, String>>()

        fun checkRef(ref: Int, expectedBookId: String? = null): Pair<GuideExcerpt?, String> {
            if (ref !in pool.indices) return null to "ref_out_of_range:ref=$ref"
            val excerpt = pool[ref]
            if (expectedBookId != null && excerpt.book.id != expectedBookId) {
                return null to "ref_wrong_book:expected=$expectedBookId actual=${excerpt.book.id}"
            }
            val cached = refOutcomes[ref]
            if (cached != null) return cached
            val a = excerpt.anchor
            val pageText = excerpt.book.page(a.pdfPage)?.text
            if (pageText == null || a.startUtf16 < 0 || a.endUtf16 > pageText.length ||
                pageText.substring(a.startUtf16, a.endUtf16) != a.quoteAnchor
            ) {
                val res = null to "offset_mismatch:page=${a.pdfPage}"
                refOutcomes[ref] = res
                return res
            }
            if (LiteraturePassages.rejectedFootnote(excerpt)) {
                val res = null to LiteraturePassages.FOOTNOTE_REJECTION
                refOutcomes[ref] = res
                return res
            }
            if (!LiteraturePassages.bodySentence(a.quoteAnchor)) {
                val res = null to "not_body_sentence"
                refOutcomes[ref] = res
                return res
            }
            val ocrText = try {
                ocr.ocrPageText(excerpt.book, a.pdfPage)
            } catch (e: Exception) {
                val detail = e.message ?: "OCR failed"
                ocrChecks += GuideOcrCheck(ref, excerpt.book.id, a.pdfPage, 0.0, false, detail)
                val res = null to "ocr_unavailable:$detail"
                refOutcomes[ref] = res
                return res
            }
            var similarity = LiteratureTextNorm.bestContiguousSimilarity(a.quoteAnchor, ocrText)
            if (similarity < LiteratureTextNorm.OCR_SIMILARITY_THRESHOLD) {
                val cropOcr = runCatching { ocr.ocrQuoteCropText(excerpt.book, a.pdfPage, a.quoteAnchor) }.getOrNull()
                if (!cropOcr.isNullOrBlank()) {
                    similarity = maxOf(similarity, LiteratureTextNorm.bestContiguousSimilarity(a.quoteAnchor, cropOcr))
                }
            }
            val passed = similarity >= LiteratureTextNorm.OCR_SIMILARITY_THRESHOLD
            ocrChecks += GuideOcrCheck(ref, excerpt.book.id, a.pdfPage, similarity, passed)
            val res = if (passed) {
                excerpt to "ok"
            } else {
                rejectedQuotes += LiteratureTextNorm.normalize(a.quoteAnchor)
                null to "ocr_similarity_low:${"%.4f".format(similarity)}<${LiteratureTextNorm.OCR_SIMILARITY_THRESHOLD}"
            }
            refOutcomes[ref] = res
            return res
        }

        val invalidFlag = (body["invalidGuide"] as? JsonPrimitive)?.booleanOrNull == true

        var verifiedQuestion: String? = null
        if (needQuestion) {
            val q = (body["question"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            when {
                invalidFlag -> failures += GuideItemFailure("question", "总问题", "invalid_json", "模型未返回有效 JSON")
                q.isBlank() -> failures += GuideItemFailure("question", "总问题", "missing", "缺少总问题")
                q.length > 120 -> failures += GuideItemFailure("question", "总问题", "too_long", "总问题超过 120 字（${q.length}）")
                else -> verifiedQuestion = q
            }
        }

        var verifiedThread: String? = null
        if (needThread) {
            val t = (body["thread"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            when {
                invalidFlag -> failures += GuideItemFailure("thread", "主线", "invalid_json", "模型未返回有效 JSON")
                t.isBlank() -> failures += GuideItemFailure("thread", "主线", "missing", "缺少串起全部文献的一条线")
                t.length > 180 -> failures += GuideItemFailure("thread", "主线", "too_long", "主线超过 180 字（${t.length}）")
                else -> verifiedThread = t
            }
        }

        val verifiedPapers = LinkedHashMap<String, GuidePaper>()
        if (expectedBookIds.isNotEmpty()) {
            val rawPapers = (body["papers"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            val expectedSet = expectedBookIds.toSet()
            val byBookId = LinkedHashMap<String, JsonObject>()
            for (row in rawPapers) {
                val explicitId = (row["bookId"] as? JsonPrimitive)?.contentOrNull?.trim()
                val refInt = (row["ref"] as? JsonPrimitive)?.intOrNull
                val inferredId = if (explicitId != null && explicitId in expectedSet) explicitId
                else if (explicitId == null && refInt != null && refInt in pool.indices) pool[refInt].book.id
                else null
                // Extra/unknown or duplicate bookIds are ignored per First-Cut rule #2.
                if (inferredId != null && inferredId in expectedSet && inferredId !in byBookId) {
                    byBookId[inferredId] = row
                }
            }
            for (bookId in expectedBookIds) {
                val meta = paperMetas.getValue(bookId)
                val label = "文献卡《${meta.title.take(28)}》"
                val row = byBookId[bookId]
                if (invalidFlag) {
                    failures += GuideItemFailure("paper:$bookId", label, "invalid_json", "模型未返回有效 JSON")
                    continue
                }
                if (row == null) {
                    failures += GuideItemFailure("paper:$bookId", label, "missing_paper", "回包缺少该文献的卡片")
                    continue
                }
                val keyword = (row["keyword"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val claim = (row["claim"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val refInt = (row["ref"] as? JsonPrimitive)?.intOrNull
                when {
                    keyword.isBlank() || keyword.length > 32 ->
                        failures += GuideItemFailure("paper:$bookId", label, "invalid_keyword", "关键词为空或超过 32 字")
                    claim.isBlank() || claim.length > 120 ->
                        failures += GuideItemFailure("paper:$bookId", label, "invalid_claim", "概括句为空或超过 120 字")
                    LiteraturePassages.hasLeakedRef(claim) ->
                        failures += GuideItemFailure("paper:$bookId", label, "leaked_ref", "概括句泄露了 ref/片段编号")
                    !LiteraturePassages.substantive(claim) ->
                        failures += GuideItemFailure("paper:$bookId", label, "fallback_unanswerable", "概括句含「无法回答/未涉及」表述")
                    refInt == null ->
                        failures += GuideItemFailure("paper:$bookId", label, "missing_ref", "缺少证据 ref")
                    else -> {
                        val (src, reason) = checkRef(refInt, expectedBookId = bookId)
                        if (src == null) {
                            val rule = reason.substringBefore(':')
                            failures += GuideItemFailure("paper:$bookId", label, rule, reason)
                        } else {
                            verifiedPapers[bookId] = GuidePaper(
                                bookId = bookId,
                                title = meta.title,
                                author = meta.author,
                                year = meta.year,
                                keyword = keyword,
                                claim = claim,
                                source = src,
                            )
                        }
                    }
                }
            }
        }

        val verifiedAnswers = LinkedHashMap<Int, GuideAnswer>()
        if (expectedQuestionIndices.isNotEmpty()) {
            val rawAnswers = (body["answers"] as? JsonArray).orEmpty()
            val byQuestionIndex = LinkedHashMap<Int, JsonObject>()
            rawAnswers.forEachIndexed { pos, element ->
                val obj = element as? JsonObject ?: return@forEachIndexed
                val explicitIdx = (obj["questionIndex"] as? JsonPrimitive)?.intOrNull
                val idx = explicitIdx ?: expectedQuestionIndices.getOrNull(pos)
                if (idx != null && idx in expectedQuestionIndices && idx !in byQuestionIndex) {
                    byQuestionIndex[idx] = obj
                }
            }
            for (qIdx in expectedQuestionIndices) {
                val label = "思考题 ${qIdx + 1}"
                val row = byQuestionIndex[qIdx]
                if (invalidFlag) {
                    failures += GuideItemFailure("answer:$qIdx", label, "invalid_json", "模型未返回有效 JSON")
                    continue
                }
                if (row == null) {
                    failures += GuideItemFailure("answer:$qIdx", label, "missing_answer", "回包缺少该思考题答案")
                    continue
                }
                val answerable = (row["answerable"] as? JsonPrimitive)?.booleanOrNull
                if (answerable != true) {
                    val rule = if (answerable == false) "answerable_false" else "answerable_missing"
                    failures += GuideItemFailure("answer:$qIdx", label, rule, "模型标记该题暂无法回答（answerable=$answerable）")
                    continue
                }
                val rawSentences = (row["sentences"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                if (rawSentences == null || rawSentences.size < 2) {
                    failures += GuideItemFailure("answer:$qIdx", label, "sentence_count", "答案句数不足 2 句，实际为 ${rawSentences?.size ?: 0}")
                    continue
                }
                var firstFailure: GuideItemFailure? = null
                val builtSentences = mutableListOf<GuideAnswerSentence>()
                for ((sIdx, sObj) in rawSentences.withIndex()) {
                    if (builtSentences.size >= 2) break
                    val text = (sObj["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    if (text.isBlank() || text.length > 100) {
                        if (firstFailure == null) {
                            firstFailure = GuideItemFailure("answer:$qIdx", label, "invalid_sentence", "第 ${sIdx + 1} 句为空或超过 100 字")
                        }
                        continue
                    }
                    if (LiteraturePassages.hasLeakedRef(text)) {
                        if (firstFailure == null) {
                            firstFailure = GuideItemFailure("answer:$qIdx", label, "leaked_ref", "第 ${sIdx + 1} 句把 ref/片段编号写进了答案正文")
                        }
                        continue
                    }
                    if (!LiteraturePassages.substantive(text)) {
                        if (firstFailure == null) {
                            firstFailure = GuideItemFailure("answer:$qIdx", label, "fallback_unanswerable", "第 ${sIdx + 1} 句命中兜底回避/「无法回答」规则")
                        }
                        continue
                    }
                    val refs = extractRefs(sObj)
                    if (refs.isEmpty()) {
                        if (firstFailure == null) {
                            firstFailure = GuideItemFailure("answer:$qIdx", label, "missing_ref", "第 ${sIdx + 1} 句缺少证据 ref")
                        }
                        continue
                    }
                    val sources = mutableListOf<GuideExcerpt>()
                    var refFailed = false
                    for (ref in refs) {
                        val (src, reason) = checkRef(ref)
                        if (src == null) {
                            if (firstFailure == null) {
                                firstFailure = GuideItemFailure("answer:$qIdx", label, reason.substringBefore(':'), "第 ${sIdx + 1} 句 $reason")
                            }
                            refFailed = true
                            break
                        }
                        sources += src
                    }
                    if (refFailed) continue
                    builtSentences += GuideAnswerSentence(text, sources.distinctBy { "${it.book.id}#${it.anchor.pdfPage}#${it.anchor.startUtf16}" })
                }
                if (builtSentences.size < 2) {
                    failures += firstFailure ?: GuideItemFailure("answer:$qIdx", label, "sentence_count", "通过核验的答案句不足 2 句（仅 ${builtSentences.size} 句）")
                } else {
                    verifiedAnswers[qIdx] = GuideAnswer(questions[qIdx], builtSentences.take(2))
                }
            }
        }

        return GuideVerification(
            question = verifiedQuestion,
            thread = verifiedThread,
            papers = verifiedPapers,
            answers = verifiedAnswers,
            failures = failures,
            ocrChecks = ocrChecks,
            rejectedQuotes = rejectedQuotes,
        )
    }

    private fun extractRefs(sentenceObj: JsonObject): List<Int> {
        val arr = sentenceObj["refs"] as? JsonArray
        if (arr != null) {
            val parsed = arr.map { (it as? JsonPrimitive)?.intOrNull }
            if (parsed.any { it == null }) return emptyList()
            return parsed.filterNotNull().distinct()
        }
        val single = (sentenceObj["ref"] as? JsonPrimitive)?.intOrNull
        return if (single != null) listOf(single) else emptyList()
    }

    companion object {
        const val SELECTION_VERSION = 3
        const val REQUEST_BYTE_BUDGET = 79_000
        const val MIN_SENTENCES_PER_PAPER = 15
        const val REPAIR_TOP_PER_ITEM = 30

        fun toStoredJson(guide: LiteratureGuide): JsonObject = buildJsonObject {
            put("version", SELECTION_VERSION)
            put("question", guide.question)
            put("thread", guide.thread)
            put("papers", buildJsonArray {
                guide.papers.forEach { p ->
                    add(buildJsonObject {
                        put("bookId", p.bookId)
                        put("title", p.title)
                        put("author", p.author)
                        put("year", p.year)
                        put("keyword", p.keyword)
                        put("claim", p.claim)
                        put("source", sourceToJson(p.source))
                    })
                }
            })
            put("answers", buildJsonArray {
                guide.answers.forEach { a ->
                    add(buildJsonObject {
                        put("question", a.question)
                        put("sentences", buildJsonArray {
                            a.sentences.forEach { s ->
                                add(buildJsonObject {
                                    put("text", s.text)
                                    put("sources", buildJsonArray { s.sources.forEach { add(sourceToJson(it)) } })
                                })
                            }
                        })
                    })
                }
            })
        }

        private fun sourceToJson(e: GuideExcerpt): JsonObject = buildJsonObject {
            put("bookId", e.book.id)
            put("pdfPage", e.anchor.pdfPage)
            put("startUtf16", e.anchor.startUtf16)
            put("endUtf16", e.anchor.endUtf16)
            put("quote", e.anchor.quoteAnchor)
        }

        fun restoreStored(stored: JsonObject, books: List<Textbook>, questions: List<String>): LiteratureGuide? {
            if ((stored["version"] as? JsonPrimitive)?.intOrNull != SELECTION_VERSION) return null
            val byId = books.associateBy { it.id }
            fun resolveSource(obj: JsonObject): GuideExcerpt? {
                val bookId = (obj["bookId"] as? JsonPrimitive)?.contentOrNull ?: return null
                val book = byId[bookId] ?: return null
                val pdfPage = (obj["pdfPage"] as? JsonPrimitive)?.intOrNull ?: return null
                val start = (obj["startUtf16"] as? JsonPrimitive)?.intOrNull ?: return null
                val end = (obj["endUtf16"] as? JsonPrimitive)?.intOrNull ?: return null
                val quote = (obj["quote"] as? JsonPrimitive)?.contentOrNull ?: return null
                val page = book.page(pdfPage) ?: return null
                if (start < 0 || end > page.text.length || page.text.substring(start, end) != quote) return null
                val region = page.guideRegions.firstOrNull { it.containsSentence(quote) } ?: GuideRegion(page.text)
                return GuideExcerpt(book, BookCompanionAnchor(pdfPage, "$bookId#p$pdfPage-$start", start, end, quote), region)
            }
            val question = (stored["question"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            val thread = (stored["thread"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            val papersArr = (stored["papers"] as? JsonArray) ?: return null
            val papers = papersArr.map { elem ->
                val obj = elem as? JsonObject ?: return null
                val src = resolveSource(obj["source"] as? JsonObject ?: return null) ?: return null
                GuidePaper(
                    bookId = (obj["bookId"] as? JsonPrimitive)?.contentOrNull ?: return null,
                    title = (obj["title"] as? JsonPrimitive)?.contentOrNull ?: return null,
                    author = (obj["author"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    year = (obj["year"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    keyword = (obj["keyword"] as? JsonPrimitive)?.contentOrNull ?: return null,
                    claim = (obj["claim"] as? JsonPrimitive)?.contentOrNull ?: return null,
                    source = src,
                )
            }
            if (papers.size != books.size || papers.map { it.bookId }.toSet() != byId.keys) return null
            val answersArr = (stored["answers"] as? JsonArray) ?: return null
            if (answersArr.size != questions.size) return null
            val answers = answersArr.mapIndexed { idx, elem ->
                val obj = elem as? JsonObject ?: return null
                val sArr = (obj["sentences"] as? JsonArray) ?: return null
                if (sArr.size != 2) return null
                val sentences = sArr.map { se ->
                    val so = se as? JsonObject ?: return null
                    val text = (so["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
                    val srcs = (so["sources"] as? JsonArray)?.map { resolveSource(it as? JsonObject ?: return null) ?: return null }
                        ?.takeIf { it.isNotEmpty() } ?: return null
                    GuideAnswerSentence(text, srcs)
                }
                GuideAnswer(questions[idx], sentences)
            }
            return LiteratureGuide(question, thread, papers, answers)
        }
    }
}

internal class LiteratureRepairInput(
    private val parent: LiteratureInput,
    val needQuestion: Boolean,
    val needThread: Boolean,
    val missingBookIds: List<String>,
    val failedQuestionIndices: List<Int>,
    val excerpts: List<GuideExcerpt>,
    private val repairSpec: JsonObject,
) {
    fun request(): JsonObject = parent.buildRequestJson(
        blocks = excerpts,
        mode = "repair",
        rev = parent.revision + 1,
        repairSpec = repairSpec,
    )

    fun verifyAndMerge(
        first: GuideVerification,
        repairBody: JsonObject,
        ocr: LiteratureOcrVerifier,
    ): Pair<GuideVerification, GuideVerification> {
        val cachedOcr = (ocr as? CachingOcrVerifier) ?: CachingOcrVerifier(ocr)
        val pass2 = parent.verifyItems(
            body = repairBody,
            pool = excerpts,
            needQuestion = needQuestion,
            needThread = needThread,
            expectedBookIds = missingBookIds,
            expectedQuestionIndices = failedQuestionIndices,
            ocr = cachedOcr,
        )
        val merged = GuideVerification(
            question = first.question ?: pass2.question,
            thread = first.thread ?: pass2.thread,
            papers = LinkedHashMap(first.papers).apply { putAll(pass2.papers) },
            answers = LinkedHashMap(first.answers).apply { putAll(pass2.answers) },
            failures = pass2.failures,
            ocrChecks = first.ocrChecks + pass2.ocrChecks,
            rejectedQuotes = first.rejectedQuotes + pass2.rejectedQuotes,
        )
        return pass2 to merged
    }
}

internal data class GuidePassDiagnostic(
    val pass: Int,
    val mode: String,
    val requestBytes: Int,
    val candidateCount: Int,
    val rawReply: JsonObject,
    val verification: GuideVerification,
)

internal object LiteratureDiagnostics {
    fun write(
        libraryDir: Path,
        courseId: String,
        digest: String,
        passes: List<GuidePassDiagnostic>,
        finalVerification: GuideVerification,
        complete: Boolean,
    ): Path {
        val dir = libraryDir.resolve("literature-diagnostics")
        Files.createDirectories(dir)
        val now = System.currentTimeMillis()
        val payload = buildJsonObject {
            put("courseId", courseId)
            put("digest", digest)
            put("timestampMs", now)
            put("status", if (complete) "complete" else "incomplete")
            put("calls", passes.size)
            put("passes", buildJsonArray {
                passes.forEach { p ->
                    add(buildJsonObject {
                        put("pass", p.pass)
                        put("mode", p.mode)
                        put("requestBytes", p.requestBytes)
                        put("candidateCount", p.candidateCount)
                        put("rawReply", p.rawReply)
                        put("ocrChecks", buildJsonArray {
                            p.verification.ocrChecks.forEach { c ->
                                add(buildJsonObject {
                                    put("ref", c.ref)
                                    put("bookId", c.bookId)
                                    put("pdfPage", c.pdfPage)
                                    put("similarity", c.similarity)
                                    put("passed", c.passed)
                                    if (c.detail.isNotEmpty()) put("detail", c.detail)
                                })
                            }
                        })
                        put("failures", failuresJson(p.verification.failures))
                    })
                }
            })
            put("finalFailures", failuresJson(finalVerification.failures))
        }
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val safeCourse = courseId.replace(Regex("[^a-zA-Z0-9_-]"), "").take(12).ifEmpty { "course" }
        val file = dir.resolve("guide-$safeCourse-${digest.take(12)}-$now.json")
        Files.write(file, bytes)
        Files.write(dir.resolve("latest.json"), bytes)
        return file
    }

    private fun failuresJson(list: List<GuideItemFailure>): JsonArray = buildJsonArray {
        list.forEach { f ->
            add(buildJsonObject {
                put("itemKey", f.itemKey)
                put("label", f.label)
                put("rule", f.rule)
                put("detail", f.detail)
            })
        }
    }
}
