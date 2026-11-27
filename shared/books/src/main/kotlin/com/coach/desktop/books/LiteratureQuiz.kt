package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*

/** A verified multiple-choice item grounded on candidate-pool sentences. */
internal data class QuizOption(
    val text: String,
    val correct: Boolean,
    val why: String,
    val sources: List<GuideExcerpt>,
)

internal data class QuizItem(
    val questionIndex: Int,
    val stem: String,
    val options: List<QuizOption>,
)

internal data class LiteratureQuizSet(
    val digest: String,
    val questions: List<String>,
    val items: List<QuizItem>,
)

internal data class QuizItemFailure(
    val questionIndex: Int,
    val label: String,
    val rule: String,
    val detail: String,
)

internal data class QuizVerification(
    val items: List<QuizItem>,
    val failures: List<QuizItemFailure>,
    val ocrChecks: List<GuideOcrCheck>,
    val rejectedQuotes: Set<String>,
)

internal data class QuizRequest(
    val json: JsonObject,
    val pool: List<GuideExcerpt>,
    val slots: Set<Int> = emptySet(),
)

internal data class QuizResolution(
    val saved: LiteratureQuizSet?,
    val gaps: List<String>,
)

/**
 * Separate literature multiple-choice request. The one-pager prompt is unchanged.
 * Sources must be candidate-pool sentences and pass the same OCR gate as the one-pager.
 */
internal object LiteratureQuiz {
    const val MIN_ITEMS = 10
    const val MAX_ITEMS = 15
    const val MIN_PER_QUESTION = 2
    private const val REQUEST_BYTE_BUDGET = 79_000
    private const val REPLY_SHAPE =
        """{"items":[{"questionIndex":0,"stem":"题干","options":[{"text":"选项","correct":true,"why":"为什么对","refs":[0]},{"text":"选项","correct":false,"why":"原文实际怎么说","refs":[1]},{"text":"选项","correct":false,"why":"原文实际怎么说","refs":[0]},{"text":"选项","correct":false,"why":"原文实际怎么说","refs":[1]}]}]}"""
    private val comparisonClaim = Regex("""与.{1,16}同样|同样重要|更重要|相比|比起|而非|优于|不如|比.{1,12}更|equally|more important|rather than|in contrast""")
    private val comparisonSource = Regex("""(?i)同样|相比|而非|whereas|unlike|rather than|equally|more important|in contrast|compared with|compared to""")
    private val denial = Regex("""原文(?:并)?没有|并未(?:提及|讨论|论述|说明|记载)|没有这类|没提到|未提及|不存在这类|并无此类|does not (?:mention|discuss)|no such""")
    private val leadingDenial = Regex("""^(?:原文(?:并)?没有|并未|没有这类|并没有|未提及|没提到|不存在这类|并无此类|does not\b|no such)""")
    private val metadataStem = Regex(
        """(?i)页眉|页脚|书目|参考文献|版权|jstor|下载于|作者是谁|哪一年|发表于哪|标题是什么|running\s*head|bibliography|page\s*header""",
    )

    fun request(input: LiteratureInput): QuizRequest {
        val pool = select(input, input.questions.indices.toList(), emptySet())
        return QuizRequest(json = payload(input, pool, "initial", null), pool = pool)
    }

    /** One targeted repair. Null when there is nothing left to ask or no sentences remain. */
    fun repairRequest(input: LiteratureInput, first: QuizVerification): QuizRequest? {
        val slots = planSlots(input.questions.size, first)
        if (slots.isEmpty()) return null
        val pool = select(input, slots.keys.toList(), first.rejectedQuotes)
        if (pool.isEmpty()) return null
        val spec = buildJsonObject {
            put("slots", buildJsonArray {
                slots.forEach { (index, ask) ->
                    add(buildJsonObject {
                        put("questionIndex", index)
                        put("ask", ask)
                        put("question", input.questions[index])
                        put("because", repairBecause(first, index, ask))
                    })
                }
            })
        }
        return QuizRequest(payload(input, pool, "repair", spec), pool, slots.keys)
    }

    fun verify(
        pool: List<GuideExcerpt>,
        body: JsonObject,
        ocr: LiteratureOcrVerifier,
        only: Set<Int>? = null,
    ): QuizVerification {
        val cached = (ocr as? CachingOcrVerifier) ?: CachingOcrVerifier(ocr)
        val failures = mutableListOf<QuizItemFailure>()
        val accepted = mutableListOf<QuizItem>()
        val ocrChecks = mutableListOf<GuideOcrCheck>()
        val rejected = mutableSetOf<String>()
        val refOutcomes = HashMap<Int, Pair<GuideExcerpt?, String>>()
        if ((body["invalidQuiz"] as? JsonPrimitive)?.booleanOrNull == true || body["items"] !is JsonArray) {
            failures += QuizItemFailure(-1, "整套选择题", "invalid_json", "模型回复缺少 items")
            return QuizVerification(emptyList(), failures, emptyList(), emptySet())
        }
        val raw = (body["items"] as JsonArray).mapNotNull { it as? JsonObject }
        raw.forEachIndexed { ordinal, row ->
            val index = (row["questionIndex"] as? JsonPrimitive)?.intOrNull
            if (index == null) {
                failures += QuizItemFailure(-1, "第 ${ordinal + 1} 题", "question_index", "缺少思考题编号")
                return@forEachIndexed
            }
            if (only != null && index !in only) return@forEachIndexed
            val label = "思考题 ${index + 1} 的选择题"
            val built = acceptItem(index, label, row, pool, cached, refOutcomes, ocrChecks, rejected)
            if (built.item != null) accepted += built.item else failures += built.failure!!
        }
        return QuizVerification(accepted, failures, ocrChecks, rejected)
    }

    fun resolve(input: LiteratureInput, first: QuizVerification, second: QuizVerification?): QuizResolution {
        val merged = if (second == null) first.items else first.items + second.items
        val trimmed = trim(input.questions.size, merged)
        if (trimmed == null) {
            val gaps = gapLabels(input.questions, merged).toMutableList()
            val invalid = (first.failures + (second?.failures.orEmpty())).any { it.rule == "invalid_json" }
            if (invalid) {
                val tail = if (gaps.isEmpty()) "这套选择题没有保存。" else gaps.removeAt(gaps.lastIndex)
                gaps += "模型回复缺少 items，记为 invalid_json。"
                gaps += tail
            }
            return QuizResolution(null, gaps)
        }
        val digest = LiteratureGuideStore.digest(input.request())
        return QuizResolution(LiteratureQuizSet(digest, input.questions, trimmed), emptyList())
    }

    fun gapLabels(questions: List<String>, items: List<QuizItem>): List<String> {
        val counts = items.groupingBy { it.questionIndex }.eachCount()
        val lines = mutableListOf<String>()
        questions.forEachIndexed { index, question ->
            val have = counts[index] ?: 0
            if (have < MIN_PER_QUESTION) {
                val title = question.replace("\n", " ").trim().take(24)
                lines += "思考题 ${index + 1}「$title」：只有 $have 道，还缺 ${MIN_PER_QUESTION - have} 道"
            }
        }
        if (items.size < MIN_ITEMS) lines += "全套只有 ${items.size} 道，少于 $MIN_ITEMS 道。"
        if (items.size > MAX_ITEMS && lines.isEmpty()) lines += "全套有 ${items.size} 道，多过 $MAX_ITEMS 道，而且无法在每题保留两道的前提下裁剪。"
        lines += "这套选择题没有保存。"
        return lines
    }

    fun toStoredJson(quiz: LiteratureQuizSet): JsonObject = buildJsonObject {
        put("version", 1)
        put("questions", JsonArray(quiz.questions.map(::JsonPrimitive)))
        put("items", buildJsonArray {
            quiz.items.forEach { item ->
                add(buildJsonObject {
                    put("questionIndex", item.questionIndex)
                    put("stem", item.stem)
                    put("options", buildJsonArray {
                        item.options.forEach { opt ->
                            add(buildJsonObject {
                                put("text", opt.text)
                                put("correct", opt.correct)
                                put("why", opt.why)
                                put("sources", buildJsonArray {
                                    opt.sources.forEach { src ->
                                        add(buildJsonObject {
                                            put("bookId", src.book.id)
                                            put("pdfPage", src.anchor.pdfPage)
                                            put("start", src.anchor.startUtf16)
                                            put("end", src.anchor.endUtf16)
                                            put("quote", src.anchor.quoteAnchor)
                                        })
                                    }
                                })
                            })
                        }
                    })
                })
            }
        })
    }

    fun restore(stored: JsonObject, books: List<Textbook>, questions: List<String>, digest: String): LiteratureQuizSet? {
        if ((stored["version"] as? JsonPrimitive)?.intOrNull != 1) return null
        val byId = books.associateBy { it.id }
        val items = (stored["items"] as? JsonArray)?.mapNotNull { row ->
            val obj = row as? JsonObject ?: return null
            val index = (obj["questionIndex"] as? JsonPrimitive)?.intOrNull ?: return null
            if (index !in questions.indices) return null
            val stem = (obj["stem"] as? JsonPrimitive)?.contentOrNull ?: return null
            val options = (obj["options"] as? JsonArray)?.map { optEl ->
                val opt = optEl as? JsonObject ?: return null
                val text = (opt["text"] as? JsonPrimitive)?.contentOrNull ?: return null
                val why = (opt["why"] as? JsonPrimitive)?.contentOrNull ?: return null
                val correct = (opt["correct"] as? JsonPrimitive)?.booleanOrNull ?: return null
                val sources = (opt["sources"] as? JsonArray)?.map { srcEl ->
                    val src = srcEl as? JsonObject ?: return null
                    val book = byId[(src["bookId"] as? JsonPrimitive)?.contentOrNull] ?: return null
                    val page = (src["pdfPage"] as? JsonPrimitive)?.intOrNull ?: return null
                    val start = (src["start"] as? JsonPrimitive)?.intOrNull ?: return null
                    val end = (src["end"] as? JsonPrimitive)?.intOrNull ?: return null
                    val quote = (src["quote"] as? JsonPrimitive)?.contentOrNull ?: return null
                    val pageText = book.page(page)?.text ?: return null
                    if (start < 0 || end > pageText.length || pageText.substring(start, end) != quote) return null
                    GuideExcerpt(book, BookCompanionAnchor(page, "litq", start, end, quote), GuideRegion(quote))
                } ?: return null
                QuizOption(text, correct, why, sources)
            } ?: return null
            QuizItem(index, stem, options)
        } ?: return null
        if (trim(questions.size, items) == null) return null
        return LiteratureQuizSet(digest, questions, items)
    }

    private fun planSlots(questionCount: Int, first: QuizVerification): Map<Int, Int> {
        val good = IntArray(questionCount)
        first.items.forEach { if (it.questionIndex in 0 until questionCount) good[it.questionIndex]++ }
        val failed = IntArray(questionCount)
        first.failures.forEach { if (it.questionIndex in 0 until questionCount) failed[it.questionIndex]++ }
        val ask = IntArray(questionCount)
        for (index in 0 until questionCount) {
            val short = (MIN_PER_QUESTION - good[index]).coerceAtLeast(0)
            ask[index] = maxOf(short, if (short > 0) failed[index] else 0).coerceAtMost(4)
            if (short > 0 && ask[index] < short) ask[index] = short
        }
        fun projected() = good.sum() + ask.sum()
        var guard = 0
        while (projected() < MIN_ITEMS && guard++ < 28) {
            val index = (0 until questionCount).filter { ask[it] < 4 }.minByOrNull { good[it] + ask[it] } ?: break
            ask[index]++
        }
        val room = (MAX_ITEMS - good.sum()).coerceAtLeast(0)
        var left = room
        return buildMap {
            for (index in 0 until questionCount) {
                if (ask[index] <= 0 || left <= 0) continue
                val give = minOf(ask[index], left)
                put(index, give)
                left -= give
            }
        }
    }

    private fun trim(questionCount: Int, items: List<QuizItem>): List<QuizItem>? {
        if (questionCount <= 0) return null
        if ((0 until questionCount).any { index -> items.count { it.questionIndex == index } < MIN_PER_QUESTION }) return null
        val ordered = items.filter { it.questionIndex in 0 until questionCount }.toMutableList()
        while (ordered.size > MAX_ITEMS) {
            val victim = ordered.groupingBy { it.questionIndex }.eachCount().filter { it.value > MIN_PER_QUESTION }
                .maxByOrNull { it.value }?.key ?: return null
            val remove = ordered.indexOfLast { it.questionIndex == victim }
            if (remove < 0) return null
            ordered.removeAt(remove)
        }
        if (ordered.size < MIN_ITEMS) return null
        return ordered
    }

    private fun select(input: LiteratureInput, indices: List<Int>, excluded: Set<String>): List<GuideExcerpt> {
        val candidatesByBook = input.allCandidatesByBook.mapValues { (_, excerpts) -> quizCandidates(excerpts) }
        val groups = indices.distinct().map { index ->
            LiteraturePassages.topAcrossBooksForQuestion(
                books = input.books,
                metas = input.paperMetas,
                candidatesByBook = candidatesByBook,
                question = input.questions[index],
                excludedQuotes = excluded,
                limit = 30,
            )
        }
        val cursors = IntArray(groups.size)
        val chosen = mutableListOf<GuideExcerpt>()
        val seen = HashSet<String>()
        var progressed = true
        while (progressed) {
            progressed = false
            for (group in groups.indices) {
                while (cursors[group] < groups[group].size) {
                    val excerpt = groups[group][cursors[group]]
                    cursors[group]++
                    val key = "${excerpt.book.id}#${excerpt.anchor.pdfPage}#${excerpt.anchor.startUtf16}"
                    if (seen.add(key)) {
                        chosen += excerpt
                        progressed = true
                        break
                    }
                }
            }
        }
        while (chosen.size > 1 && payload(input, chosen, "initial", null).toString().toByteArray().size > REQUEST_BYTE_BUDGET) {
            chosen.removeAt(chosen.lastIndex)
        }
        return chosen
    }

    private fun payload(input: LiteratureInput, blocks: List<GuideExcerpt>, mode: String, repair: JsonObject?): JsonObject =
        buildJsonObject {
            put("kind", "literatureQuiz")
            put("mode", mode)
            put("replyShape", REPLY_SHAPE)
            put("papers", input.papersJson())
            put("questions", JsonArray(input.questions.map(::JsonPrimitive)))
            if (repair != null) put("repair", repair)
            put("blocks", buildJsonArray { blocks.forEachIndexed { ref, excerpt -> add(input.blockJson(excerpt, ref)) } })
        }

    private data class ItemBuild(val item: QuizItem?, val failure: QuizItemFailure?)

    private fun acceptItem(
        index: Int,
        label: String,
        row: JsonObject,
        pool: List<GuideExcerpt>,
        ocr: CachingOcrVerifier,
        refOutcomes: HashMap<Int, Pair<GuideExcerpt?, String>>,
        ocrChecks: MutableList<GuideOcrCheck>,
        rejected: MutableSet<String>,
    ): ItemBuild {
        fun fail(rule: String, detail: String) = ItemBuild(null, QuizItemFailure(index, label, rule, detail))
        if (index < 0) return fail("question_index", "思考题编号无效")
        val stem = (row["stem"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        when {
            stem.length !in 8..180 -> return fail("stem_length", "题干长度需在 8 到 180 字之间")
            LiteraturePassages.hasLeakedRef(stem) -> return fail("leaked_ref", "题干写进了片段编号")
            metadataStem.containsMatchIn(stem) -> return fail("metadata_stem", "不考查页眉、书目、作者年份或标题")
            !LiteraturePassages.substantive(stem) -> return fail("stem_substantive", "题干没有正面作答")
        }
        val options = (row["options"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        if (options.size != 4) return fail("option_count", "需要恰好 4 个选项")
        if (options.count { (it["correct"] as? JsonPrimitive)?.booleanOrNull == true } != 1) {
            return fail("correct_count", "需要恰好 1 个正确选项")
        }
        val built = mutableListOf<QuizOption>()
        for (option in options) {
            val text = (option["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val why = (option["why"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val correct = (option["correct"] as? JsonPrimitive)?.booleanOrNull == true
            when {
                text.length !in 2..80 -> return fail("option_length", "选项长度需在 2 到 80 字之间")
                why.length !in 4..160 -> return fail("why_length", "每个选项都要说明对或错的理由")
                LiteraturePassages.hasLeakedRef(text) || LiteraturePassages.hasLeakedRef(why) ->
                    return fail("leaked_ref", "选项或理由写进了片段编号")
                correct && (!LiteraturePassages.substantive(text) || !LiteraturePassages.substantive(why)) ->
                    return fail("option_substantive", "正确项不能使用回避句")
            }
            val refs = (option["refs"] as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull } .orEmpty()
            if (refs.isEmpty() || refs.any { it == null }) return fail("refs", "每个选项至少要有一个候选句编号")
            val sources = mutableListOf<GuideExcerpt>()
            for (ref in refs.filterNotNull().distinct()) {
                val (excerpt, rule) = checkRef(ref, pool, ocr, refOutcomes, ocrChecks, rejected)
                if (excerpt == null) {
                    val name = rule.substringBefore(':')
                    val detail = rule.substringAfter(':', "").ifBlank { name }
                    return fail(name, detail)
                }
                if (copiesQuote(stem, excerpt.anchor.quoteAnchor) || copiesQuote(text, excerpt.anchor.quoteAnchor) ||
                    copiesQuote(why, excerpt.anchor.quoteAnchor)
                ) {
                    return fail("copied_quote", "自写文字复述了所引原句")
                }
                sources += excerpt
            }
            val quotes = sources.map { it.anchor.quoteAnchor }
            if (correct && unsupportedClaim(text, why, quotes)) {
                return fail("unsupported_claim", "正确项里有出处没有的判断")
            }
            if (!correct && contradictsQuote(text, why, quotes)) {
                return fail("contradicts_source", "错项理由和所引原文相反")
            }
            built += QuizOption(text, correct, why, sources.distinctBy { it.book.id to it.anchor.pdfPage to it.anchor.startUtf16 })
        }
        if (built.map { it.text }.distinct().size != built.size) return fail("duplicate_option", "选项文字不能重复")
        return ItemBuild(QuizItem(index, stem, built), null)
    }

    private fun checkRef(
        ref: Int,
        pool: List<GuideExcerpt>,
        ocr: CachingOcrVerifier,
        refOutcomes: HashMap<Int, Pair<GuideExcerpt?, String>>,
        ocrChecks: MutableList<GuideOcrCheck>,
        rejected: MutableSet<String>,
    ): Pair<GuideExcerpt?, String> {
        if (ref !in pool.indices) return null to "ref_out_of_range:ref=$ref"
        refOutcomes[ref]?.let { return it }
        val excerpt = pool[ref]
        val anchor = excerpt.anchor
        val pageText = excerpt.book.page(anchor.pdfPage)?.text
        if (pageText == null || anchor.startUtf16 < 0 || anchor.endUtf16 > pageText.length ||
            pageText.substring(anchor.startUtf16, anchor.endUtf16) != anchor.quoteAnchor
        ) {
            return (null to "offset_mismatch:page=${anchor.pdfPage}").also { refOutcomes[ref] = it }
        }
        if (LiteraturePassages.rejectedFootnote(excerpt)) {
            return (null to LiteraturePassages.FOOTNOTE_REJECTION).also { refOutcomes[ref] = it }
        }
        if (!LiteraturePassages.bodySentence(excerpt)) {
            return (null to "not_body_sentence:这不是完整的正文句子").also { refOutcomes[ref] = it }
        }
        val ocrText = try {
            ocr.ocrPageText(excerpt.book, anchor.pdfPage)
        } catch (e: Exception) {
            val detail = e.message ?: "OCR failed"
            ocrChecks += GuideOcrCheck(ref, excerpt.book.id, anchor.pdfPage, 0.0, false, detail)
            return (null to "ocr_unavailable:$detail").also { refOutcomes[ref] = it }
        }
        var similarity = LiteratureTextNorm.bestContiguousSimilarity(anchor.quoteAnchor, ocrText)
        if (similarity < LiteratureTextNorm.OCR_SIMILARITY_THRESHOLD) {
            val crop = runCatching { ocr.ocrQuoteCropText(excerpt.book, anchor.pdfPage, anchor.quoteAnchor) }.getOrNull()
            if (!crop.isNullOrBlank()) {
                similarity = maxOf(similarity, LiteratureTextNorm.bestContiguousSimilarity(anchor.quoteAnchor, crop))
            }
        }
        val passed = similarity >= LiteratureTextNorm.OCR_SIMILARITY_THRESHOLD
        ocrChecks += GuideOcrCheck(ref, excerpt.book.id, anchor.pdfPage, similarity, passed)
        val result = if (passed) excerpt to "ok" else {
            rejected += LiteratureTextNorm.normalize(anchor.quoteAnchor)
            null to "ocr_similarity_low:${"%.4f".format(similarity)}<${LiteratureTextNorm.OCR_SIMILARITY_THRESHOLD}"
        }
        refOutcomes[ref] = result
        return result
    }

    private fun repairBecause(first: QuizVerification, index: Int, ask: Int): String {
        val reasons = first.failures.filter { it.questionIndex == index }
            .distinctBy { it.rule to it.detail }
            .take(6)
            .joinToString("；") { "${it.rule}：${it.detail}" }
        return buildString {
            append("该思考题目前通过核验 ${count(first.items, index)} 道，请再写 $ask 道。")
            if (reasons.isNotEmpty()) append("上次被拒：").append(reasons).append("。请按这个原因改写，不要重复。")
        }
    }

    /** Drop note sentences. A page that is mostly notes drops its remaining fragments too. */
    private fun quizCandidates(excerpts: List<GuideExcerpt>): List<GuideExcerpt> {
        val dropPages = excerpts.groupBy { it.book.id to it.anchor.pdfPage }
            .filter { (_, items) ->
                val notes = items.count { LiteraturePassages.rejectedFootnote(it) }
                notes >= 3 && notes * 2 >= items.size
            }.keys
        return excerpts.filter {
            (it.book.id to it.anchor.pdfPage) !in dropPages && !LiteraturePassages.rejectedFootnote(it)
        }
    }

    private fun firstClause(why: String) =
        why.trim().split(Regex("[。！？；.!?]")).firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun unsupportedClaim(text: String, why: String, quotes: List<String>): Boolean {
        val claim = "$text $why"
        if (comparisonClaim.containsMatchIn(claim) && quotes.none { comparisonSource.containsMatchIn(it) }) return true
        val quoteBlob = quotes.joinToString("\n")
        if (hanCount(claim) < 8 || hanCount(quoteBlob) < 8) return false
        val quoteTokens = LiteraturePassages.tokens(quoteBlob)
        return claim.split(Regex("[。！？；;.!?]")).map { it.trim() }.any { clause ->
            if (clause.length < 8) return@any false
            val tokens = LiteraturePassages.tokens(clause)
            tokens.size >= 4 && tokens.count { it in quoteTokens } * 2 < tokens.size
        }
    }

    private fun contradictsQuote(optionText: String, why: String, quotes: List<String>): Boolean {
        if (leadingDenial.containsMatchIn(firstClause(why))) return true
        if (!denial.containsMatchIn(why)) return false
        val quoteTokens = quotes.flatMap { LiteraturePassages.tokens(it) }.toSet()
        return LiteraturePassages.tokens(optionText).count { it in quoteTokens } >= 2
    }

    private fun hanCount(text: String) = text.count { it.code in 0x3400..0x9fff }

    private fun copiesQuote(text: String, quote: String): Boolean {
        val normalized = LiteratureTextNorm.normalize(quote)
        if (normalized.length < 12) return false
        return normalized in LiteratureTextNorm.normalize(text)
    }

    private fun count(items: List<QuizItem>, index: Int) = items.count { it.questionIndex == index }
}

internal object LiteratureQuizBank {
    fun commit(library: TextbookLibrary, quiz: LiteratureQuizSet, index: Int, chosenText: String?, guessed: Boolean) {
        val item = quiz.items[index]
        val correct = item.options.first { it.correct }
        val source = correct.sources.first()
        val lessonId = "litq-${quiz.digest.take(16)}-$index"
        val practice = Practice(
            prompt = item.stem,
            reference = explanation(item),
            answer = correct.text,
            options = item.options.map { it.text },
            sourcePage = source.anchor.pdfPage,
        )
        val pageText = source.book.page(source.anchor.pdfPage)?.text?.takeIf { it.isNotBlank() } ?: " "
        val lesson = BookLesson(
            id = lessonId,
            title = item.stem.take(80),
            blocks = listOf(
                SourceBlock(
                    id = "$lessonId-src",
                    page = source.anchor.pdfPage,
                    start = source.anchor.startUtf16,
                    end = source.anchor.endUtf16,
                    heading = false,
                    text = pageText,
                ),
            ),
        )
        val answer = if (chosenText.isNullOrBlank()) {
            PracticeAnswer(text = "不确定", revealed = true, guessed = true)
        } else {
            PracticeAnswer(text = chosenText, revealed = true, guessed = guessed)
        }
        library.saveSectionCheckAnswer(
            bookId = source.book.id,
            lesson = lesson,
            questionIndex = 0,
            practice = practice,
            answer = answer,
            questionKey = lessonId,
        )
    }

    fun explanation(item: QuizItem): String = buildString {
        item.options.forEachIndexed { index, option ->
            append("${'A' + index}. ${if (option.correct) "对" else "错"}：${option.why}")
            val quote = option.sources.firstOrNull()?.anchor?.quoteAnchor
            if (!quote.isNullOrBlank()) append(" 出处：").append(quote)
            append('\n')
        }
    }.trim()
}

internal object LiteratureQuizDiagnostics {
    fun write(libraryDir: Path, courseId: String, digest: String, calls: Int, complete: Boolean, gaps: List<String>): Path {
        val dir = libraryDir.resolve("literature-quiz-diagnostics")
        Files.createDirectories(dir)
        val payload = buildJsonObject {
            put("courseId", courseId)
            put("digest", digest)
            put("calls", calls)
            put("status", if (complete) "complete" else "incomplete")
            put("gaps", JsonArray(gaps.map(::JsonPrimitive)))
        }
        val path = dir.resolve("latest.json")
        Files.writeString(path, payload.toString())
        return path
    }
}
