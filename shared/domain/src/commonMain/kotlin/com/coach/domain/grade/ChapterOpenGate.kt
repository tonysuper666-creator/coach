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
    val startUtf16: Int = 0,
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
    const val MAX_CLAIM_CHARS: Int = 160
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
        val claimHan = claim.filter { it.code in 0x4E00..0x9FFF }
        val sourceHan = source.filter { it.code in 0x4E00..0x9FFF }
        if (claimHan.length >= 12 && sourceHan.length >= 12) {
            for (i in 0..sourceHan.length - 12) {
                if (sourceHan.substring(i, i + 12) in claimHan) return true
            }
        }
        val words = source.split(Regex("[^A-Za-z0-9]+")).filter { it.length > 1 }
        if (words.size >= 8) {
            val lower = claim.replace(Regex("\\s+"), "").lowercase()
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
            if (claim.length !in 4..MAX_CLAIM_CHARS) continue
            if (copiesSource(claim, block.text)) continue
            val placed = placeAnchor(anchor, block.text) ?: continue
            seen += id
            val baseStart = if (block.startUtf16 > 0) {
                block.startUtf16
            } else {
                block.id.substringAfter(":p${block.page}:", "").toIntOrNull()?.coerceAtLeast(0) ?: 0
            }
            val startUtf16 = baseStart + placed.second
            accepted += ChapterOpenAcceptedPoint(
                id = id,
                claim = claim,
                blockId = block.id,
                page = block.page,
                quoteAnchor = placed.first,
                startUtf16 = startUtf16,
                endUtf16 = startUtf16 + placed.first.length,
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
     * English periods end a sentence unless they sit between digits. A long sentence
     * is trimmed to 180 characters without dropping [anchor].
     */
    fun sentenceAround(pageText: String, anchor: String): String {
        if (pageText.isBlank() || anchor.isBlank()) return ""
        val start = pageText.indexOf(anchor)
        if (start < 0) return ""
        var left = start
        while (left > 0 && !isSentenceBreak(pageText, left - 1)) left--
        var right = start + anchor.length
        while (right < pageText.length && !isSentenceBreak(pageText, right)) right++
        if (right < pageText.length && isSentenceBreak(pageText, right) && pageText[right] != '\n') right++
        val raw = pageText.substring(left, right).trim()
        if (raw.length <= 180) return raw
        val anchorAt = raw.indexOf(anchor)
        if (anchorAt < 0) return raw.take(180)
        val winStart = (anchorAt + anchor.length - 180).coerceAtLeast(0).coerceAtMost(anchorAt)
        val winEnd = (winStart + 180).coerceAtMost(raw.length)
        return raw.substring(winStart, winEnd).trim()
    }

    private fun isSentenceBreak(text: String, index: Int): Boolean {
        if (index !in text.indices) return false
        val ch = text[index]
        if (ch == '\n' || ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?') return true
        if (ch != '.') return false
        val prev = text.getOrNull(index - 1)
        val next = text.getOrNull(index + 1)
        if (prev != null && prev.isDigit() && next != null && next.isDigit()) return false
        return next == null || next.isWhitespace() || next in "\"'”’)]"
    }

    private fun placeAnchor(anchor: String, text: String): Pair<String, Int>? {
        val raw = anchor.trim()
        if (raw.length < 4 || text.isEmpty()) return null
        if (raw.length <= 40) {
            val at = text.indexOf(raw)
            if (at >= 0) return raw to at
        } else {
            for (len in 40 downTo 4) {
                val slice = raw.substring(0, len).trimEnd()
                if (slice.length < 4) continue
                val at = text.indexOf(slice)
                if (at >= 0) return slice to at
            }
        }
        return placeAnchorNormalized(raw, text)
    }

    private fun placeAnchorNormalized(raw: String, text: String): Pair<String, Int>? {
        val hasCjk = raw.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }
        val normText = StringBuilder(text.length)
        val normToRaw = IntArray(text.length)
        var prevSpace = false
        for (i in text.indices) {
            val ch = text[i]
            if (ch.isWhitespace()) {
                if (!hasCjk && normText.isNotEmpty() && !prevSpace) {
                    normToRaw[normText.length] = i
                    normText.append(' ')
                    prevSpace = true
                }
            } else {
                normToRaw[normText.length] = i
                normText.append(ch)
                prevSpace = false
            }
        }
        val normAnchor = buildString(raw.length) {
            var space = false
            for (ch in raw) {
                if (ch.isWhitespace()) {
                    if (!hasCjk && isNotEmpty() && !space) {
                        append(' ')
                        space = true
                    }
                } else {
                    append(ch)
                    space = false
                }
            }
        }.trim()
        if (normAnchor.length < 4 || normText.isEmpty()) return null
        val haystack = normText.toString()
        val maxLen = minOf(normAnchor.length, 40)
        for (len in maxLen downTo 4) {
            val needle = normAnchor.substring(0, len).trimEnd()
            if (needle.length < 4) continue
            val atNorm = haystack.indexOf(needle)
            if (atNorm < 0) continue
            val rawStart = normToRaw[atNorm]
            var rawEnd = normToRaw[atNorm + needle.length - 1] + 1
            if (rawEnd - rawStart > 40) {
                var shrunkLen = needle.length - 1
                while (shrunkLen >= 4) {
                    val candidateEnd = normToRaw[atNorm + shrunkLen - 1] + 1
                    if (candidateEnd - rawStart <= 40) {
                        rawEnd = candidateEnd
                        break
                    }
                    shrunkLen--
                }
                if (rawEnd - rawStart > 40 || shrunkLen < 4) continue
            }
            val exactSlice = text.substring(rawStart, rawEnd)
            if (exactSlice.length in 4..40) return exactSlice to rawStart
        }
        return null
    }

    /**
     * A credited answer is obviously off-topic only when it shares no content
     * word with the claim or the hanging sentence and is too short to be a rewording.
     * One shared word is enough to leave the verdict to the model. A short answer
     * that shares nothing is still forced to 没答到, so a zero-overlap dinner sentence
     * cannot stay 答到. A longer paraphrase is left to the model.
     */
    fun obviouslyOffTopic(claim: String, sourceSentence: String, answer: String): Boolean {
        if (answer.isBlank()) return true
        if (!isOffTopic(claim, answer)) return false
        if (sourceSentence.isNotBlank() && !isOffTopic(sourceSentence, answer)) return false
        if (contentTokens(claim).intersect(contentTokens(answer)).isNotEmpty()) return false
        if (sourceSentence.isNotBlank() &&
            contentTokens(sourceSentence).intersect(contentTokens(answer)).isNotEmpty()
        ) {
            return false
        }
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
