package com.coach.desktop.books

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.sql.Connection

/**
 * 概念掌握图：作答记在概念上，每条证据都带一句能在本章原页上对上的原句。
 * 对不上原句、或页码不在指定章里的候选不入库，所以图上不会出现挂空或挂错章的概念。
 * 节级掌握度（`bookId:tocNodeId`）照旧，这张图只用 `cmap:` 开头的概念行。
 */
internal enum class ConceptAnswerKind {
    MCQ,
    CHAPTER_END,
    RUBRIC,
}

internal data class ConceptQuoteCandidate(
    val name: String,
    val page: Int,
    val quote: String,
    val startUtf16: Int = -1,
    val scoreRatio: Double,
)

internal data class ResolvedConceptHit(
    val conceptKey: String,
    val name: String,
    val chapterId: String,
    val chapterTitle: String,
    val page: Int,
    val quote: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val scoreRatio: Double,
)

internal data class ConceptEvidenceLine(
    val eventId: String,
    val label: String,
    val page: Int,
    val quote: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val createdAt: Long,
)

internal data class ConceptMasteryNode(
    val conceptKey: String,
    val name: String,
    val chapterId: String,
    val levelLabel: String,
    val evidenceCount: Int,
    val evidence: List<ConceptEvidenceLine>,
)

internal data class ConceptMasteryChapter(
    val chapterId: String,
    val chapterTitle: String,
    val concepts: List<ConceptMasteryNode>,
)

internal object ConceptMasteryMap {
    const val KEY_PREFIX = "cmap:"
    private val json = Json { ignoreUnknownKeys = true }
    private val whitespace = Regex("\\s+")

    fun levelLabel(record: ConceptMasteryRecord): String = when {
        !record.hasActiveTest -> "还没测过"
        record.pMastery < 0.45 -> "不稳"
        record.pMastery < MasteryEngine.HIGH_MASTERY_COLLAPSE_THRESHOLD -> "部分会"
        else -> "会了"
    }

    fun conceptKey(bookId: String, chapterId: String, name: String): String {
        val norm = name.trim().replace(whitespace, " ").lowercase()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$chapterId\n$norm".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return "$KEY_PREFIX$bookId:$digest"
    }

    fun locateQuote(pageText: String, quote: String, hintStart: Int = -1): IntRange? {
        val needle = quote.trim()
        if (needle.length < 8 || pageText.isEmpty()) return null
        if (hintStart >= 0 && hintStart + needle.length <= pageText.length &&
            pageText.regionMatches(hintStart, needle, 0, needle.length)
        ) {
            return hintStart until (hintStart + needle.length)
        }
        val at = pageText.indexOf(needle)
        if (at < 0) return null
        return at until (at + needle.length)
    }

    fun resolve(
        book: Textbook,
        expectedChapterId: String,
        candidates: List<ConceptQuoteCandidate>,
    ): List<ResolvedConceptHit> {
        val seen = HashSet<String>()
        val out = ArrayList<ResolvedConceptHit>()
        for (candidate in candidates) {
            val name = displayName(candidate.name, candidate.quote) ?: continue
            val page = book.page(candidate.page) ?: continue
            val range = locateQuote(page.text, candidate.quote, candidate.startUtf16) ?: continue
            val owner = BookChapterResolver.chapterForPage(book, candidate.page)
                ?: book.chapterSpans.firstOrNull { candidate.page in it.firstPage..it.lastPage }
                ?: continue
            if (candidate.page !in owner.firstPage..owner.lastPage) continue
            if (expectedChapterId.isNotBlank() && owner.tocNodeId != expectedChapterId) continue
            val quote = page.text.substring(range.first, range.last + 1)
            val key = conceptKey(book.id, owner.tocNodeId, name)
            if (!seen.add("$key@${candidate.page}:${range.first}")) continue
            out += ResolvedConceptHit(
                conceptKey = key,
                name = name,
                chapterId = owner.tocNodeId,
                chapterTitle = owner.title.ifBlank { "这一章" },
                page = candidate.page,
                quote = quote,
                startUtf16 = range.first,
                endUtf16 = range.last + 1,
                scoreRatio = candidate.scoreRatio.coerceIn(0.0, 1.0),
            )
        }
        return out
    }

    fun candidatesForQuiz(
        quiz: BookCompanionQuiz,
        keyPoints: List<BookCompanionKeyPoint>,
        scoreRatio: Double,
    ): List<ConceptQuoteCandidate> {
        val haystack = listOf(quiz.stem, quiz.explanation, quiz.anchor?.quoteAnchor.orEmpty()).joinToString("\n")
        val matched = keyPoints.mapNotNull { point ->
            val anchor = point.anchor ?: return@mapNotNull null
            val quote = anchor.quoteAnchor.trim()
            if (quote.length < 8) return@mapNotNull null
            val mentioned = quote in haystack || haystack.contains(quote) ||
                (quiz.anchor?.quoteAnchor?.let { mine -> quote in mine || mine in quote } == true)
            if (!mentioned) return@mapNotNull null
            val name = MasteryEngine.sanitizeHumanLabel(point.summary) ?: return@mapNotNull null
            ConceptQuoteCandidate(name, anchor.pdfPage, quote, anchor.startUtf16, scoreRatio)
        }
        val own = quiz.anchor?.takeIf { it.quoteAnchor.trim().length >= 8 }?.let { anchor ->
            val already = matched.any { it.quote == anchor.quoteAnchor.trim() || anchor.quoteAnchor.trim() in it.quote }
            if (already) return@let null
            val sameSection = keyPoints.firstOrNull { point ->
                point.sectionId == quiz.sectionId &&
                    point.anchor?.quoteAnchor?.let { quote ->
                        quote == anchor.quoteAnchor || quote in anchor.quoteAnchor || anchor.quoteAnchor in quote
                    } == true
            }
            val name = MasteryEngine.sanitizeHumanLabel(sameSection?.summary)
                ?: MasteryEngine.sanitizeHumanLabel(anchor.quoteAnchor.trim().take(48))
                ?: return@let null
            ConceptQuoteCandidate(name, anchor.pdfPage, anchor.quoteAnchor.trim(), anchor.startUtf16, scoreRatio)
        }
        return matched + listOfNotNull(own)
    }

    fun candidatesForRubric(
        items: List<PracticeRubricItemGrade>,
        quiz: BookCompanionQuiz,
        keyPoints: List<BookCompanionKeyPoint>,
    ): List<ConceptQuoteCandidate> = items.mapNotNull { item ->
        val score = verdictScore(item.verdict, item.awardedMarks, item.maxMarks)
        val named = keyPoints.firstOrNull { point ->
            val quote = point.anchor?.quoteAnchor?.trim().orEmpty()
            quote.length >= 8 && (quote == item.quoteAnchor.trim() || quote in item.quoteAnchor || item.quoteAnchor in quote)
        }
        val name = MasteryEngine.sanitizeHumanLabel(named?.summary)
            ?: MasteryEngine.sanitizeHumanLabel(item.quoteAnchor.trim().take(48))
            ?: MasteryEngine.sanitizeHumanLabel(quiz.stem.take(48))
            ?: return@mapNotNull null
        val quote = item.quoteAnchor.trim().takeIf { it.length >= 8 }
            ?: quiz.anchor?.quoteAnchor?.trim()?.takeIf { it.length >= 8 }
            ?: return@mapNotNull null
        val page = if (item.quoteAnchor.trim().length >= 8 && item.pdfPage > 0) item.pdfPage else quiz.anchor?.pdfPage ?: item.pdfPage
        val start = if (quote == quiz.anchor?.quoteAnchor?.trim()) quiz.anchor?.startUtf16 ?: -1 else -1
        ConceptQuoteCandidate(name, page, quote, start, score)
    }

    fun candidatesForChapterOpen(
        question: ChapterOpenQuestion,
        attempt: ChapterOpenAttempt,
    ): List<ConceptQuoteCandidate> {
        val byId = question.points.associateBy { it.id }
        return attempt.items.mapNotNull { item ->
            val point = byId[item.id] ?: return@mapNotNull null
            val name = MasteryEngine.sanitizeHumanLabel(point.claim) ?: return@mapNotNull null
            if (point.quoteAnchor.trim().length < 8 || point.page <= 0) return@mapNotNull null
            ConceptQuoteCandidate(
                name = name,
                page = point.page,
                quote = point.quoteAnchor.trim(),
                startUtf16 = point.startUtf16,
                scoreRatio = verdictScore(item.verdict, 0.0, 0.0),
            )
        }
    }

    fun verdictScore(verdict: String, awarded: Double, maxMarks: Double): Double {
        if (maxMarks > 0.0) return (awarded / maxMarks).coerceIn(0.0, 1.0)
        return when (verdict) {
            "FULL" -> 1.0
            "PARTIAL" -> 0.5
            else -> 0.0
        }
    }

    fun record(
        db: Connection,
        book: Textbook,
        expectedChapterId: String,
        candidates: List<ConceptQuoteCandidate>,
        kind: ConceptAnswerKind,
        signal: LearnerSignalType,
        guessed: Boolean,
        questionId: String,
        prompt: String,
        nowMs: Long,
    ): Int {
        val hits = resolve(book, expectedChapterId, candidates)
        for (hit in hits) {
            MasteryStore.recordEvidence(
                db = db,
                bookId = book.id,
                tocNodeId = hit.chapterId,
                page = hit.page,
                canonicalName = hit.name,
                signal = signal,
                scoreRatio = hit.scoreRatio,
                guessed = guessed,
                detailJson = detailJson(kind, questionId, prompt, hit),
                conceptKeyOverride = hit.conceptKey,
                nowMs = nowMs,
            )
        }
        return hits.size
    }

    fun load(db: Connection, book: Textbook): List<ConceptMasteryChapter> {
        val prefix = "$KEY_PREFIX${book.id}:"
        val records = MasteryStore.loadConceptsForBook(db, book.id).filter { it.conceptKey.startsWith(prefix) }
        if (records.isEmpty()) return emptyList()
        val chapterOrder = book.chapterSpans.mapIndexed { index, span -> span.tocNodeId to index }.toMap()
        val titles = book.chapterSpans.associate { it.tocNodeId to it.title.ifBlank { "这一章" } }
        val grouped = LinkedHashMap<String, MutableList<ConceptMasteryNode>>()
        for (record in records) {
            val lines = MasteryStore.listEvidenceEvents(db, conceptKey = record.conceptKey)
                .mapNotNull { event -> evidenceLine(book, record.tocNodeId, event) }
            if (lines.isEmpty()) continue
            val node = ConceptMasteryNode(
                conceptKey = record.conceptKey,
                name = record.canonicalName,
                chapterId = record.tocNodeId,
                levelLabel = levelLabel(record),
                evidenceCount = lines.size,
                evidence = lines.sortedByDescending { it.createdAt },
            )
            grouped.getOrPut(record.tocNodeId) { mutableListOf() }.add(node)
        }
        return grouped.map { (chapterId, nodes) ->
            ConceptMasteryChapter(
                chapterId = chapterId,
                chapterTitle = titles[chapterId] ?: "这一章",
                concepts = nodes.sortedBy { it.name },
            )
        }.sortedBy { chapterOrder[it.chapterId] ?: Int.MAX_VALUE }
    }

    fun anchorFor(line: ConceptEvidenceLine): BookCompanionAnchor =
        BookCompanionAnchor(line.page, "cmap:${line.startUtf16}", line.startUtf16, line.endUtf16, line.quote)

    private fun evidenceLine(book: Textbook, chapterId: String, event: LearnerEvidenceEvent): ConceptEvidenceLine? {
        val obj = runCatching { json.parseToJsonElement(event.detailJson).jsonObject }.getOrNull() ?: return null
        if ((obj["map"] as? JsonPrimitive)?.contentOrNull != "1") return null
        val quote = obj.str("quote")
        val hint = (obj["start"] as? JsonPrimitive)?.intOrNull ?: -1
        val page = book.page(event.page) ?: return null
        val range = locateQuote(page.text, quote, hint) ?: return null
        val owner = BookChapterResolver.chapterForPage(book, event.page)
            ?: book.chapterSpans.firstOrNull { event.page in it.firstPage..it.lastPage }
            ?: return null
        if (owner.tocNodeId != chapterId) return null
        val exact = page.text.substring(range.first, range.last + 1)
        val kind = runCatching { ConceptAnswerKind.valueOf(obj.str("kind")) }.getOrDefault(ConceptAnswerKind.MCQ)
        val guessed = event.guessed
        return ConceptEvidenceLine(
            eventId = event.eventId,
            label = evidenceLabel(kind, event.scoreRatio, guessed, obj.str("prompt")),
            page = event.page,
            quote = exact,
            startUtf16 = range.first,
            endUtf16 = range.last + 1,
            createdAt = event.createdAt,
        )
    }

    private fun evidenceLabel(kind: ConceptAnswerKind, score: Double?, guessed: Boolean, prompt: String): String {
        val head = when (kind) {
            ConceptAnswerKind.MCQ -> when {
                guessed -> "选择题，这题是猜的"
                score != null && score >= 0.99 -> "选择题答对"
                else -> "选择题答错"
            }
            ConceptAnswerKind.CHAPTER_END -> when {
                guessed -> "章末题，这题是猜的"
                score != null && score >= 0.99 -> "章末题答对"
                else -> "章末题答错"
            }
            ConceptAnswerKind.RUBRIC -> when {
                score != null && score >= 0.99 -> "开放题这一点答到了"
                score != null && score >= 0.4 -> "开放题这一点部分答到"
                else -> "开放题这一点没答到"
            }
        }
        val extra = MasteryEngine.sanitizeHumanLabel(prompt)?.take(32)
        return if (extra == null) head else "$head · $extra"
    }

    private fun detailJson(kind: ConceptAnswerKind, questionId: String, prompt: String, hit: ResolvedConceptHit): String =
        buildJsonObject {
            put("map", "1")
            put("kind", kind.name)
            put("questionId", questionId)
            put("chapterId", hit.chapterId)
            put("quote", hit.quote)
            put("start", hit.startUtf16)
            put("end", hit.endUtf16)
            val shortPrompt = MasteryEngine.sanitizeHumanLabel(prompt)?.take(80)
            if (shortPrompt != null) put("prompt", shortPrompt)
        }.toString()

    private fun displayName(raw: String, quote: String): String? =
        MasteryEngine.sanitizeHumanLabel(raw)?.take(48)
            ?: MasteryEngine.sanitizeHumanLabel(quote.trim().take(48))

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
