package com.coach.domain.grade

/**
 * Chapter open-question gates (TON-1910, scoring updated in TON-1919).
 *
 * A scoring point cites a location in the chapter. The claim is what the student
 * should be able to say, and must not be a copied stretch of the chapter.
 * When the point carries the sentence it hangs on, the model judges that meaning:
 * a correct paraphrase stays 答到, and local word overlap only blocks a short
 * answer that shares nothing with the sentence or the claim. Without that
 * sentence, an off-topic answer cannot stay at FULL, and a paraphrase the model
 * marked FULL stays FULL. A point the answer never mentions is 没读到. 混淆了别的概念
 * is only when the answer uses another point's wording in place of this one.
 * A second try keeps the earlier attempt and rejudges only the points that were missed.
 */
data class ChapterOpenPointRef(
    val id: String,
    val claim: String,
    val sourceSentence: String = "",
)

data class ChapterOpenDraft(
    val id: String,
    val verdict: String,
    val reason: String = "",
)

data class ChapterOpenJudgement(
    val id: String,
    val verdict: String,
    val reason: String,
    val missCause: String = "",
)

data class ChapterOpenSourceBlock(
    val id: String,
    val page: Int,
    val text: String,
)

data class ChapterOpenRawPoint(
    val id: String,
    val claim: String,
    val blockId: String,
    val quoteAnchor: String,
)

data class ChapterOpenAcceptedPoint(
    val id: String,
    val claim: String,
    val blockId: String,
    val page: Int,
    val quoteAnchor: String,
    val startUtf16: Int,
    val endUtf16: Int,
)

data class ChapterOpenHistory(
    val attempts: List<List<ChapterOpenJudgement>> = emptyList(),
) {
    fun add(items: List<ChapterOpenJudgement>): ChapterOpenHistory =
        copy(attempts = attempts + listOf(items))
}

object ChapterOpenGate {
    const val MIN_POINTS: Int = 3
    const val MAX_POINTS: Int = 6
    const val NOT_READ: String = "NOT_READ"
    const val READ_NOT_UNDERSTOOD: String = "READ_NOT_UNDERSTOOD"
    const val CONCEPT_CONFUSION: String = "CONCEPT_CONFUSION"
    const val UNCLEAR_EXPRESSION: String = "UNCLEAR_EXPRESSION"

    fun verdictLabel(verdict: String): String = when (verdict.trim().uppercase()) {
        "FULL" -> "答到"
        "PARTIAL" -> "部分"
        else -> "没答到"
    }

    fun causeLabel(cause: String): String = when (cause.trim().uppercase()) {
        NOT_READ -> "没读到"
        READ_NOT_UNDERSTOOD -> "读了没懂"
        CONCEPT_CONFUSION -> "混淆了别的概念"
        UNCLEAR_EXPRESSION -> "表达不清"
        else -> ""
    }

    fun contentTokens(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        val han = StringBuilder()
        val latin = StringBuilder()
        fun flushHan() {
            val s = han.toString()
            han.clear()
            if (s.length >= 2) {
                for (i in 0 until s.length - 1) out += s.substring(i, i + 2)
            }
        }
        fun flushLatin() {
            val word = latin.toString().lowercase()
            latin.clear()
            if (word.length >= 4) out += word
        }
        for (ch in text) {
            when {
                ch.code in 0x4E00..0x9FFF -> {
                    flushLatin()
                    han.append(ch)
                }
                ch.isLetter() -> {
                    flushHan()
                    latin.append(ch)
                }
                else -> {
                    flushHan()
                    flushLatin()
                }
            }
        }
        flushHan()
        flushLatin()
        return out
    }

    fun copiesSource(claim: String, source: String): Boolean {
        if (claim.isBlank() || source.isBlank()) return false
        val compactClaim = claim.replace(Regex("\\s+"), "")
        val compactSource = source.replace(Regex("\\s+"), "")
        if (compactClaim.length >= 12 && compactSource.length >= 12) {
            for (i in 0..compactSource.length - 12) {
                if (compactSource.substring(i, i + 12) in compactClaim) return true
            }
        }
        val words = source.split(Regex("[^A-Za-z0-9]+")).filter { it.length > 1 }
        if (words.size >= 8) {
            val lower = compactClaim.lowercase()
            for (i in 0..words.size - 8) {
                val phrase = words.subList(i, i + 8).joinToString("").lowercase()
                if (phrase.isNotEmpty() && phrase in lower) return true
            }
        }
        return false
    }

    fun acceptPoints(
        raw: List<ChapterOpenRawPoint>,
        blocks: List<ChapterOpenSourceBlock>,
    ): List<ChapterOpenAcceptedPoint> {
        val byId = blocks.associateBy { it.id }
        val accepted = ArrayList<ChapterOpenAcceptedPoint>()
        val seen = HashSet<String>()
        for (point in raw) {
            if (accepted.size >= MAX_POINTS) break
            val id = point.id.trim()
            val claim = point.claim.trim()
            val anchor = point.quoteAnchor.trim()
            val block = byId[point.blockId.trim()] ?: continue
            if (id.isBlank() || id in seen) continue
            if (claim.length !in 4..80) continue
            if (anchor.length !in 4..40) continue
            if (copiesSource(claim, block.text)) continue
            val start = block.text.indexOf(anchor)
            if (start < 0) continue
            seen += id
            accepted += ChapterOpenAcceptedPoint(
                id = id,
                claim = claim,
                blockId = block.id,
                page = block.page,
                quoteAnchor = anchor,
                startUtf16 = start,
                endUtf16 = start + anchor.length,
            )
        }
        return accepted
    }

    fun judge(
        answer: String,
        points: List<ChapterOpenPointRef>,
        drafts: List<ChapterOpenDraft>,
    ): List<ChapterOpenJudgement> {
        val byId = points.associateBy { it.id }
        return drafts.mapNotNull { draft ->
            val point = byId[draft.id] ?: return@mapNotNull null
            val model = when (draft.verdict.trim().uppercase()) {
                "FULL" -> "FULL"
                "PARTIAL" -> "PARTIAL"
                else -> "ZERO"
            }
            val verdict = if (point.sourceSentence.isNotBlank()) {
                meaningVerdict(point, answer, model)
            } else when {
                isOffTopic(point.claim, answer) -> "ZERO"
                else -> model
            }
            val cause = if (verdict == "ZERO") missCause(point, points, answer) else ""
            val reason = if (verdict == model && usableReason(draft.reason)) {
                draft.reason.trim()
            } else {
                defaultReason(verdict, cause)
            }
            ChapterOpenJudgement(
                id = point.id,
                verdict = verdict,
                reason = reason,
                missCause = cause,
            )
        }
    }

    fun isOffTopic(claim: String, answer: String): Boolean {
        val shared = contentTokens(claim).intersect(contentTokens(answer))
        if (shared.size >= 2) return false
        return fourSpans(claim).none { it in answer }
    }

    fun allowsHit(claim: String, answer: String): Boolean {
        val claimTokens = contentTokens(claim)
        if (claimTokens.isEmpty()) return false
        val shared = claimTokens.intersect(contentTokens(answer))
        if (shared.size < 2) return false
        return shared.size.toDouble() / claimTokens.size >= 0.34
    }

    /**
     * The sentence a scoring point hangs on. [anchor] must occur in [pageText].
     * Empty when the anchor is missing, so callers can leave the lexical gate in place.
     */
    fun sentenceAround(pageText: String, anchor: String): String {
        if (pageText.isBlank() || anchor.isBlank()) return ""
        val start = pageText.indexOf(anchor)
        if (start < 0) return ""
        val breaks = setOf('。', '！', '？', '\n', '!', '?')
        var left = start
        while (left > 0 && pageText[left - 1] !in breaks) left--
        var right = start + anchor.length
        while (right < pageText.length && pageText[right] !in breaks) right++
        if (right < pageText.length && pageText[right] in breaks && pageText[right] != '\n') right++
        return pageText.substring(left, right).trim().take(180)
    }

    /**
     * A credited answer is obviously off-topic only when it shares no content
     * with the claim or the hanging sentence and is too short to be a rewording.
     * A longer paraphrase is left to the model.
     */
    fun obviouslyOffTopic(claim: String, sourceSentence: String, answer: String): Boolean {
        if (answer.isBlank()) return true
        if (!isOffTopic(claim, answer)) return false
        if (sourceSentence.isNotBlank() && !isOffTopic(sourceSentence, answer)) return false
        return !longAttempt(answer)
    }

    private fun meaningVerdict(point: ChapterOpenPointRef, answer: String, model: String): String {
        if (answer.isBlank()) return "ZERO"
        if (model != "ZERO" && obviouslyOffTopic(point.claim, point.sourceSentence, answer)) return "ZERO"
        return model
    }

    private fun longAttempt(answer: String): Boolean {
        val han = answer.count { it.code in 0x4E00..0x9FFF }
        if (han >= 12) return true
        val words = answer.split(Regex("[^A-Za-z0-9]+")).count { it.length >= 3 }
        return words >= 6
    }

    private fun missCause(
        point: ChapterOpenPointRef,
        points: List<ChapterOpenPointRef>,
        answer: String,
    ): String {
        val spansHere = fourSpans(point.claim).any { it in answer }
        val distinctive = distinctiveShared(point, points, answer)
        val otherSpan = points.any { other ->
            other.id != point.id && fourSpans(other.claim).any { span ->
                span in answer && span !in hanRun(point.claim)
            }
        }
        return when {
            !spansHere && distinctive.isEmpty() -> NOT_READ
            otherSpan && !spansHere && distinctive.size < 2 -> CONCEPT_CONFUSION
            !spansHere && distinctive.size <= 1 -> UNCLEAR_EXPRESSION
            else -> READ_NOT_UNDERSTOOD
        }
    }

    private fun distinctiveShared(
        point: ChapterOpenPointRef,
        points: List<ChapterOpenPointRef>,
        answer: String,
    ): Set<String> {
        val others = points.asSequence()
            .filter { it.id != point.id }
            .flatMap { contentTokens(it.claim).asSequence() }
            .toSet()
        return contentTokens(point.claim).intersect(contentTokens(answer)) - others
    }

    private fun hanRun(text: String): String = text.filter { it.code in 0x4E00..0x9FFF }

    private fun fourSpans(claim: String): List<String> {
        val han = claim.filter { it.code in 0x4E00..0x9FFF }
        if (han.length < 4) return emptyList()
        return (0..han.length - 4).map { han.substring(it, it + 4) }
    }

    private fun usableReason(reason: String): Boolean {
        val text = reason.trim()
        return text.isNotBlank() && text.length <= 42
    }

    private fun defaultReason(verdict: String, cause: String): String = when (verdict) {
        "FULL" -> "这一点答到了。"
        "PARTIAL" -> "这一点只答到一部分。"
        else -> when (cause) {
            NOT_READ -> "这一点没答到，像是没读到。"
            CONCEPT_CONFUSION -> "这一点没答到，和另一个概念混了。"
            UNCLEAR_EXPRESSION -> "这一点没答到，没有说清楚。"
            else -> "这一点没答到，读过但没讲明白。"
        }
    }
}
