package com.coach.desktop.books

import com.coach.domain.fsrs.Fsrs5
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * 2.0-AI-1 (Stage 4) nine learner evidence signal classes matching `learner_evidence_events.signal_type`.
 */
internal enum class LearnerSignalType {
    PRETEST_T0,
    POST_QUIZ_T1,
    DELAYED_TRANSFER_T2,
    RUBRIC_GRADE,
    MISCONCEPTION_DIAG,
    PAGE_DWELL,
    REREAD_BACKTRACK,
    SELECTION_ASK,
    HIGHLIGHT_NOTE,
}

/**
 * Four misconception categories plus `NONE` (matching `learner_evidence_events.error_class`).
 */
internal enum class MisconceptionErrorClass(val labelZh: String) {
    NONE("无"),
    CONCEPT_CONFUSION("概念混淆"),
    SIGN_OR_FORMULA("公式与符号"),
    GRAPH_SHIFT_OR_ROTATION("图形移动方向"),
    ARITHMETIC_OR_ALGEBRA("算术与代数"),
    ;

    companion object {
        fun fromWire(raw: String?): MisconceptionErrorClass? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

internal data class ConceptMasteryRecord(
    val conceptKey: String,
    val collectionId: String? = null,
    val bookId: String,
    val tocNodeId: String,
    val canonicalName: String,
    val syllabusWeight: Double = 1.0,
    val pMastery: Double = MasteryEngine.DEFAULT_P_MASTERY,
    val hasActiveTest: Boolean = false,
    val fsrsStability: Double = 1.0,
    val dominantErrorTag: String? = null,
    val rereadCount: Int = 0,
    val dwellMsTotal: Long = 0L,
    val askCount: Int = 0,
    val highlightCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

internal data class LearnerEvidenceEvent(
    val eventId: String,
    val conceptKey: String,
    val bookId: String,
    val page: Int,
    val signalType: LearnerSignalType,
    val scoreRatio: Double? = null,
    val guessed: Boolean = false,
    val errorClass: String? = null,
    val durationMs: Long = 0L,
    val detailJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Four-point learning gain cohort:
 * T0 pretest -> Read -> T1 immediate post-test -> T2 delayed transfer (>= t0_at + 72h).
 * Guessed answers strictly score 0.0 so guessing never inflates measured gain.
 */
internal data class LearningGainCohort(
    val cohortId: String,
    val collectionId: String? = null,
    val bookId: String,
    val chapterId: String,
    val conceptKey: String,
    val t0At: Long? = null,
    val t0Score: Double? = null,
    val t0Guessed: Boolean = false,
    val readCompletedAt: Long? = null,
    val readDwellSec: Int = 0,
    val t1At: Long? = null,
    val t1Score: Double? = null,
    val t2EligibleAfter: Long? = null,
    val t2At: Long? = null,
    val t2VariantId: String? = null,
    val t2Score: Double? = null,
) {
    fun isT2Eligible(nowMs: Long): Boolean =
        t2EligibleAfter != null && nowMs >= t2EligibleAfter && t2At == null

    /** Normalized gain `g = (T2 - T0_effective) / max(0.01, 1 - T0_effective)`. */
    fun normalizedGain(): Double? {
        val rawT0 = t0Score ?: return null
        val t2 = t2Score ?: return null
        val effectiveT0 = if (t0Guessed) 0.0 else rawT0.coerceIn(0.0, 1.0)
        val denom = maxOf(0.01, 1.0 - effectiveT0)
        return (t2.coerceIn(0.0, 1.0) - effectiveT0) / denom
    }
}

internal data class HomeFocusRecommendation(
    val bookId: String,
    val tocNodeId: String,
    val conceptKey: String,
    val canonicalName: String,
    val targetPage: Int,
    val needScore: Double,
    val dominantErrorTag: String? = null,
    val rereadCount: Int = 0,
    val askCount: Int = 0,
    val pMastery: Double = MasteryEngine.DEFAULT_P_MASTERY,
    val metaLine: String,
)

internal data class BookLastQuestion(
    val bookId: String,
    val text: String,
)

internal enum class MarginWhisperRule {
    CROSS_MATERIAL_CONFLICT,
    PRIOR_MISCONCEPTION_GUARD,
    REREAD_FRICTION_ON_PAGE,
}

internal data class MarginWhisperCandidate(
    val whisperId: String,
    val bookId: String,
    val tocNodeId: String,
    val page: Int,
    val rule: MarginWhisperRule,
    val collapsedText: String,
    val expandedText: String,
    val pdfPageAnchor: Int,
    val quoteAnchor: String = "",
    val triggerEvidenceJson: String,
    val dismissed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

internal data class EndOfSectionCheckpointData(
    val bookId: String,
    val sectionId: String,
    val sectionTitle: String,
    val page: Int,
    val questions: List<BookCompanionQuiz>,
)

/**
 * Local deterministic mastery & NeedScore engine (`$0`, `< 2ms`).
 *
 * Hard invariants:
 * 1. Passive reading/highlighting (`hasActiveTest == false`) is strictly capped at `p_mastery <= 0.35`.
 * 2. Highlighting (`HIGHLIGHT_NOTE`) records attention count only and never increases `p_mastery`.
 * 3. Guessed answers (`guessed == true`) never increase `p_mastery` and schedule next-day review.
 * 4. `NeedScore` combines exam weight, mastery × FSRS retrievability, active error tag, rereads and asks.
 * 5. `T2` delayed transfer unlocks strictly at `t0_at + 72h` (`259,200,000 ms`).
 */
internal object MasteryEngine {
    const val DEFAULT_P_MASTERY: Double = 0.15
    const val MIN_P_MASTERY: Double = 0.05
    const val MAX_P_MASTERY: Double = 0.98
    const val PASSIVE_MASTERY_CAP: Double = 0.35
    const val HIGH_MASTERY_COLLAPSE_THRESHOLD: Double = 0.85
    const val T2_DELAY_MS: Long = 72L * 3600L * 1000L
    const val REREAD_DWELL_MULTIPLIER: Double = 2.2
    const val WHISPER_DISMISS_COOLDOWN_PAGES: Int = 10
    const val HOME_REVIEW_EMPTY_MESSAGE: String = "还没有要复习的。做错的题会在明天出现。"
    const val LAST_QUESTION_MAX_CHARS: Int = 12

    private val WHITESPACE_RE = Regex("\\s+")
    private val HEX_ID_RE = Regex("^[0-9a-fA-F]{8,}(?:\\.{3}|…)?$")
    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val PAGE_NODE_ID_RE = Regex("^p\\d+$", RegexOption.IGNORE_CASE)
    private val PREFIXED_INTERNAL_ID_RE = Regex(
        "^(?:ev|var|mw|toc|sec|chunk|course|topic|col|book|node|item|lesson)[-_][0-9a-fA-F]{6,}(?::.*)?$",
        RegexOption.IGNORE_CASE,
    )
    private val COLON_INTERNAL_ID_RE = Regex("^(?:[0-9a-fA-F]{8,}|gain|t2):.+$", RegexOption.IGNORE_CASE)

    fun isInternalIdentifier(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return true
        if (text.equals("root", ignoreCase = true)) return true
        return HEX_ID_RE.matches(text) ||
            UUID_RE.matches(text) ||
            PAGE_NODE_ID_RE.matches(text) ||
            PREFIXED_INTERNAL_ID_RE.matches(text) ||
            COLON_INTERNAL_ID_RE.matches(text)
    }

    fun sanitizeHumanLabel(raw: String?): String? {
        val cleaned = raw?.replace(WHITESPACE_RE, " ")?.trim().orEmpty()
        if (cleaned.isEmpty() || isInternalIdentifier(cleaned)) return null
        return cleaned
    }

    fun formatQuestionExcerpt(rawQuestion: String?, maxChars: Int = LAST_QUESTION_MAX_CHARS): String? {
        val clean = sanitizeHumanLabel(rawQuestion) ?: return null
        val limit = maxChars.coerceAtLeast(1)
        val preview = if (clean.length > limit) clean.take(limit) + "…" else clean
        return "上次问：$preview"
    }

    fun formatBookLocation(bookTitle: String?, chapterTitle: String?, page: Int): String {
        val safePage = page.coerceAtLeast(1)
        val cleanBook = sanitizeHumanLabel(bookTitle)
        val cleanChapter = sanitizeHumanLabel(chapterTitle)?.takeIf { it != cleanBook }
        val compactBook = cleanBook?.let { compactLabel(it, 28) }
        val compactChapter = cleanChapter?.let { compactLabel(it, 20) }
        return buildList {
            if (compactBook != null) add(compactBook)
            if (compactChapter != null) add(compactChapter)
            add("第 $safePage 页")
        }.joinToString(" · ")
    }

    fun formatContinueCardMeta(
        bookTitle: String?,
        chapterTitle: String?,
        readingPage: Int,
        lastQuestion: String? = null,
        savedDraft: ChapterPracticeDraft? = null,
        masteryRec: HomeFocusRecommendation? = null,
    ): String {
        val questionExcerpt = formatQuestionExcerpt(lastQuestion)
        if (savedDraft != null && questionExcerpt != null) {
            return "$questionExcerpt · 第 ${savedDraft.page.coerceAtLeast(1)} 页 · 练习草稿已保存"
        }
        if (savedDraft != null) {
            return "${formatBookLocation(bookTitle, chapterTitle, savedDraft.page)} · 练习草稿已保存"
        }
        if (questionExcerpt != null) {
            return "$questionExcerpt · 第 ${readingPage.coerceAtLeast(1)} 页"
        }
        val errorLabel = masteryRec?.dominantErrorTag?.let(MisconceptionErrorClass::fromWire)
            ?.takeIf { it != MisconceptionErrorClass.NONE }
            ?.labelZh
        if (masteryRec != null && errorLabel != null) {
            val cleanMeta = sanitizeHumanLabel(masteryRec.metaLine)
            if (cleanMeta != null && !containsHexIdentifierToken(cleanMeta)) {
                return cleanMeta
            }
            return "${formatBookLocation(bookTitle, chapterTitle, masteryRec.targetPage)} · 上次卡在$errorLabel"
        }
        return formatBookLocation(bookTitle, chapterTitle, readingPage)
    }

    fun estimateReviewMinutes(dueCount: Int): Int {
        val count = dueCount.coerceAtLeast(1)
        return ((count * 4) + 2) / 3
    }

    fun formatHomeReviewMeta(dueCount: Int): String =
        "今天 $dueCount 道，约 ${estimateReviewMinutes(dueCount)} 分钟"

    private fun compactLabel(text: String, maxChars: Int): String =
        if (text.length > maxChars) text.take(maxChars - 1) + "…" else text

    private fun containsHexIdentifierToken(text: String): Boolean =
        Regex("\\b[0-9a-fA-F]{10,}\\b").containsMatchIn(text)

    fun conceptKeyFor(bookId: String, tocNodeId: String, suffix: String? = null): String {
        val cleanNode = tocNodeId.trim().ifBlank { "root" }
        val base = "$bookId:$cleanNode"
        val cleanSuffix = suffix?.trim()?.takeIf(String::isNotEmpty)
        return if (cleanSuffix == null) base else "$base:$cleanSuffix"
    }

    fun retrievability(stabilityDays: Double, elapsedDays: Double): Double {
        val s = stabilityDays.coerceAtLeast(0.1)
        val t = elapsedDays.coerceAtLeast(0.0)
        return (1.0 + Fsrs5.FACTOR * t / s).pow(Fsrs5.DECAY).coerceIn(0.0, 1.0)
    }

    fun elapsedDays(updatedAtMs: Long, nowMs: Long): Double =
        ((nowMs - updatedAtMs).coerceAtLeast(0L).toDouble()) / 86_400_000.0

    /**
     * Action urgency formula (aligned with `proposal.md` §1.2 and `sample-ch3/01-mastery-and-home-routing.json`):
     * `NeedScore(c) = w_exam * (1 - p_mastery * R_fsrs) + 0.25 * I[error_tag != null] + 0.08 * min(3, reread) + 0.06 * min(2, ask)`
     */
    fun needScore(
        syllabusWeight: Double,
        pMastery: Double,
        retrievability: Double,
        dominantErrorTag: String?,
        rereadCount: Int,
        askCount: Int,
    ): Double {
        val w = syllabusWeight.coerceAtLeast(0.1)
        val p = pMastery.coerceIn(0.0, 1.0)
        val r = retrievability.coerceIn(0.0, 1.0)
        val hasError = !dominantErrorTag.isNullOrBlank() && !dominantErrorTag.equals("NONE", ignoreCase = true)
        val errorBonus = if (hasError) 0.25 else 0.0
        val rereadBonus = 0.08 * minOf(3, rereadCount.coerceAtLeast(0))
        val askBonus = 0.06 * minOf(2, askCount.coerceAtLeast(0))
        return w * (1.0 - p * r) + errorBonus + rereadBonus + askBonus
    }

    fun needScore(record: ConceptMasteryRecord, nowMs: Long = System.currentTimeMillis()): Double {
        val r = retrievability(record.fsrsStability, elapsedDays(record.updatedAt, nowMs))
        return needScore(
            syllabusWeight = record.syllabusWeight,
            pMastery = record.pMastery,
            retrievability = r,
            dominantErrorTag = record.dominantErrorTag,
            rereadCount = record.rereadCount,
            askCount = record.askCount,
        )
    }

    fun applySignal(
        current: ConceptMasteryRecord,
        signal: LearnerSignalType,
        scoreRatio: Double? = null,
        guessed: Boolean = false,
        errorClass: MisconceptionErrorClass? = null,
        durationMs: Long = 0L,
        fsrsStability: Double? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): ConceptMasteryRecord {
        val addedDuration = durationMs.coerceAtLeast(0L)
        val nextStability = fsrsStability?.coerceAtLeast(0.1) ?: current.fsrsStability
        var nextActive = current.hasActiveTest
        var nextP = current.pMastery.coerceIn(MIN_P_MASTERY, MAX_P_MASTERY)
        var nextError = current.dominantErrorTag
        var nextReread = current.rereadCount
        var nextDwell = current.dwellMsTotal
        var nextAsk = current.askCount
        var nextHighlight = current.highlightCount

        when (signal) {
            LearnerSignalType.HIGHLIGHT_NOTE -> {
                nextHighlight += 1
                // Highlighting indicates attention only; it never increases p_mastery.
            }
            LearnerSignalType.PAGE_DWELL -> {
                nextDwell += addedDuration
                if (!nextActive && addedDuration >= ChapterReadPolicy.MIN_DWELL_MS) {
                    val nudge = minOf(0.04, (addedDuration.toDouble() / 60_000.0) * 0.025)
                    nextP = minOf(PASSIVE_MASTERY_CAP, nextP + nudge)
                }
            }
            LearnerSignalType.REREAD_BACKTRACK -> {
                nextReread += 1
                nextDwell += addedDuration
                // Repeated backtracking indicates reading friction; never inflate mastery.
                nextP = maxOf(MIN_P_MASTERY, nextP - 0.02)
            }
            LearnerSignalType.SELECTION_ASK -> {
                nextAsk += 1
                // Asking signals clarification need; never inflate mastery.
            }
            LearnerSignalType.MISCONCEPTION_DIAG -> {
                if (errorClass != null && errorClass != MisconceptionErrorClass.NONE) {
                    nextError = errorClass.name
                    nextP = maxOf(MIN_P_MASTERY, nextP - 0.05)
                }
            }
            LearnerSignalType.PRETEST_T0,
            LearnerSignalType.POST_QUIZ_T1,
            LearnerSignalType.DELAYED_TRANSFER_T2,
            LearnerSignalType.RUBRIC_GRADE,
            -> {
                val rawScore = (scoreRatio ?: 0.0).coerceIn(0.0, 1.0)
                if (guessed) {
                    // Checking "这题是猜的" must NEVER raise p_mastery, even when the selected option matched.
                    nextP = minOf(nextP, if (nextActive) nextP else PASSIVE_MASTERY_CAP)
                    if (errorClass != null && errorClass != MisconceptionErrorClass.NONE) {
                        nextError = errorClass.name
                    }
                } else {
                    nextActive = true
                    val stepWeight = when (signal) {
                        LearnerSignalType.DELAYED_TRANSFER_T2 -> 1.45
                        LearnerSignalType.RUBRIC_GRADE -> 1.25
                        else -> 1.15
                    }
                    val deltaLogit = stepWeight * (2.0 * rawScore - 1.0)
                    nextP = sigmoid(logit(nextP) + deltaLogit).coerceIn(MIN_P_MASTERY, MAX_P_MASTERY)
                    if (errorClass != null && errorClass != MisconceptionErrorClass.NONE) {
                        nextError = errorClass.name
                    } else if (rawScore >= 0.99) {
                        nextError = null
                    }
                }
            }
        }

        // Absolute safety guard: untested concepts can NEVER exceed PASSIVE_MASTERY_CAP (0.35).
        if (!nextActive) {
            nextP = nextP.coerceIn(MIN_P_MASTERY, PASSIVE_MASTERY_CAP)
        } else {
            nextP = nextP.coerceIn(MIN_P_MASTERY, MAX_P_MASTERY)
        }

        return current.copy(
            pMastery = nextP,
            hasActiveTest = nextActive,
            fsrsStability = nextStability,
            dominantErrorTag = nextError,
            rereadCount = nextReread,
            dwellMsTotal = nextDwell,
            askCount = nextAsk,
            highlightCount = nextHighlight,
            updatedAt = nowMs,
        )
    }

    /** Companion drawer adaptive folding: collapse basic jargon for well-mastered concepts without active errors. */
    fun shouldCollapseBasicTerminology(record: ConceptMasteryRecord?): Boolean =
        record != null &&
            record.hasActiveTest &&
            record.pMastery >= HIGH_MASTERY_COLLAPSE_THRESHOLD &&
            record.dominantErrorTag.isNullOrBlank()

    fun shouldHighlightWeakSection(record: ConceptMasteryRecord?): Boolean =
        record != null && (record.rereadCount >= 2 || !record.dominantErrorTag.isNullOrBlank())

    fun buildHomeFocusMeta(
        record: ConceptMasteryRecord,
        sectionLabel: String?,
        targetPage: Int,
        bookTitle: String? = null,
        lastQuestion: String? = null,
    ): String {
        val safePage = targetPage.coerceAtLeast(1)
        val questionExcerpt = formatQuestionExcerpt(lastQuestion)
        if (questionExcerpt != null) {
            return "$questionExcerpt · 第 $safePage 页"
        }
        val cleanBook = sanitizeHumanLabel(bookTitle)
        val cleanSection = sanitizeHumanLabel(sectionLabel)
        val cleanConcept = sanitizeHumanLabel(record.canonicalName)
        val prefix = cleanSection ?: cleanConcept ?: cleanBook ?: "第 $safePage 页"
        val errorLabel = MisconceptionErrorClass.fromWire(record.dominantErrorTag)
            ?.takeIf { it != MisconceptionErrorClass.NONE }
            ?.labelZh
        val conceptShort = cleanConcept?.let { if (it.length > 16) it.take(15) + "…" else it }
        return when {
            errorLabel != null -> {
                if (conceptShort != null && conceptShort != prefix) {
                    "$prefix · 上次卡在$conceptShort（$errorLabel） · 建议巩固。"
                } else {
                    "$prefix · 上次卡在（$errorLabel） · 建议巩固。"
                }
            }
            record.rereadCount >= 2 -> "${formatBookLocation(cleanBook, cleanSection ?: cleanConcept, safePage)} · 回看 ${record.rereadCount} 次"
            else -> formatBookLocation(cleanBook, cleanSection ?: cleanConcept, safePage)
        }
    }

    private fun logit(p: Double): Double {
        val clamped = p.coerceIn(1e-6, 1.0 - 1e-6)
        return ln(clamped / (1.0 - clamped))
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
}

/**
 * Tracks sequential page turns, per-page cumulative dwell, personal median page dwell,
 * backtrack rereads (`p+1 -> p` with cumulative dwell > 2.2x median), and genuine section reading
 * (filtering out TOC jumps and rapid key-repeat flips).
 */
internal class PageReadingTracker(
    private val defaultMedianFloorMs: Long = 15_000L,
) {
    private val dwellByPage = LinkedHashMap<Int, Long>()
    private val visitCountByPage = HashMap<Int, Int>()
    private val backtrackCountByPage = HashMap<Int, Int>()
    private val sectionDwelledPages = HashMap<String, MutableSet<Int>>()
    private val seenCheckpoints = HashSet<String>()
    private var lastPage: Int? = null
    private var lastSectionId: String? = null

    fun onPageEntered(page: Int, isJump: Boolean, sectionId: String?): Boolean {
        val prev = lastPage
        val backtracked = !isJump && prev != null && (prev - page) in 1..2 && (visitCountByPage[page] ?: 0) >= 1
        if (backtracked) {
            backtrackCountByPage[page] = (backtrackCountByPage[page] ?: 0) + 1
        }
        visitCountByPage[page] = (visitCountByPage[page] ?: 0) + 1
        lastPage = page
        lastSectionId = sectionId
        return backtracked
    }

    /**
     * Records a verified dwell tick (`>= 8s` in production, configurable in tests).
     * Returns `true` when this page now satisfies the `REREAD_BACKTRACK` friction rule:
     * backtracked from `p+1 -> p` and cumulative dwell on `p` exceeds `2.2 * personalMedianDwellMs`.
     */
    fun recordDwell(page: Int, dwellMs: Long, sectionId: String?): Boolean {
        val safeDwell = dwellMs.coerceAtLeast(0L)
        val total = (dwellByPage[page] ?: 0L) + safeDwell
        dwellByPage[page] = total
        if (!sectionId.isNullOrBlank()) {
            sectionDwelledPages.getOrPut(sectionId) { LinkedHashSet() }.add(page)
        }
        val backtracks = backtrackCountByPage[page] ?: 0
        if (backtracks <= 0) return false
        val median = personalMedianDwellMs(excludePage = page)
        return total.toDouble() > MasteryEngine.REREAD_DWELL_MULTIPLIER * median
    }

    fun personalMedianDwellMs(excludePage: Int? = null): Double {
        val values = dwellByPage.entries
            .filter { excludePage == null || it.key != excludePage }
            .map { it.value }
            .filter { it > 0L }
            .sorted()
        if (values.isEmpty()) return defaultMedianFloorMs.toDouble()
        val mid = values.size / 2
        val raw = if (values.size % 2 == 1) values[mid].toDouble()
        else (values[mid - 1] + values[mid]) / 2.0
        return maxOf(raw, defaultMedianFloorMs.toDouble())
    }

    fun cumulativeDwellMs(page: Int): Long = dwellByPage[page] ?: 0L

    fun backtrackCount(page: Int): Int = backtrackCountByPage[page] ?: 0

    fun hasGenuineSectionRead(sectionId: String): Boolean =
        sectionDwelledPages[sectionId]?.isNotEmpty() == true

    /**
     * True only when the learner actually dwelled inside [previousSectionId] and naturally flipped
     * past its final page (`fromPage -> toPage = fromPage + 1`, not a TOC jump), at most once per section.
     */
    fun shouldOfferSectionCheckpoint(
        previousSectionId: String?,
        previousSectionLastPage: Int,
        fromPage: Int,
        toPage: Int,
        isJump: Boolean,
    ): Boolean {
        if (isJump || previousSectionId.isNullOrBlank()) return false
        if ((toPage - fromPage) !in 1..2) return false
        if (toPage <= previousSectionLastPage) return false
        if (!hasGenuineSectionRead(previousSectionId)) return false
        if (!seenCheckpoints.add(previousSectionId)) return false
        return true
    }
}

/**
 * Quiet proactivity restraint gate:
 * - Only 3 evidence-backed rules may trigger a whisper:
 *   1) Cross-material conflict on this page
 *   2) Unresolved prior misconception on this page's concept
 *   3) Repeated backtrack reread (`>= 2`) with cumulative dwell `> 2.2x` personal median
 * - At most 1 whisper per section (`tocNodeId`).
 * - Clicking `×` activates a silent cooldown of 10 distinct page turns.
 * - Every triggered whisper carries a non-empty auditable `triggerEvidenceJson`.
 */
internal class QuietProactivityPolicy {
    private val shownSections = HashSet<String>()
    var cooldownPagesRemaining: Int = 0
        private set

    fun onPageTurn() {
        if (cooldownPagesRemaining > 0) cooldownPagesRemaining -= 1
    }

    fun onUserDismiss() {
        cooldownPagesRemaining = MasteryEngine.WHISPER_DISMISS_COOLDOWN_PAGES
    }

    fun hasShownInSection(tocNodeId: String): Boolean = tocNodeId in shownSections

    fun evaluate(
        bookId: String,
        tocNodeId: String,
        page: Int,
        conflictInfo: Pair<String, String>?,
        mastery: ConceptMasteryRecord?,
        rereadCountOnPage: Int,
        cumulativeDwellMs: Long,
        personalMedianDwellMs: Double,
        quoteAnchor: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): MarginWhisperCandidate? {
        if (cooldownPagesRemaining > 0) return null
        if (tocNodeId.isBlank() || tocNodeId in shownSections) return null

        val candidate: MarginWhisperCandidate? = when {
            conflictInfo != null -> {
                val collapsed = compactCollapsedText(conflictInfo.first, "本页与其他材料表述有差异")
                val evidenceJson = buildJsonObject {
                    put("rule", MarginWhisperRule.CROSS_MATERIAL_CONFLICT.name)
                    put("bookId", bookId)
                    put("tocNodeId", tocNodeId)
                    put("page", page)
                    put("pdfPageAnchor", page)
                    put("quoteAnchor", quoteAnchor)
                    put("evidence", conflictInfo.second)
                    put("timestampMs", nowMs)
                }.toString()
                MarginWhisperCandidate(
                    whisperId = "mw-$bookId-$tocNodeId-p$page-conflict",
                    bookId = bookId,
                    tocNodeId = tocNodeId,
                    page = page,
                    rule = MarginWhisperRule.CROSS_MATERIAL_CONFLICT,
                    collapsedText = collapsed,
                    expandedText = conflictInfo.second,
                    pdfPageAnchor = page,
                    quoteAnchor = quoteAnchor,
                    triggerEvidenceJson = evidenceJson,
                    createdAt = nowMs,
                )
            }
            mastery != null && !mastery.dominantErrorTag.isNullOrBlank() &&
                !mastery.dominantErrorTag.equals("NONE", ignoreCase = true) -> {
                val errClass = MisconceptionErrorClass.fromWire(mastery.dominantErrorTag)
                val errLabel = errClass?.labelZh ?: "易错点"
                val nameShort = mastery.canonicalName.trim().let { if (it.length > 10) it.take(10) else it }
                val collapsed = compactCollapsedText("留意$nameShort 的$errLabel", "留意本页易错点")
                val expanded = "上次在「${mastery.canonicalName}」出现过$errLabel 偏差，读这页时可重点核对定义与推导方向。"
                val evidenceJson = buildJsonObject {
                    put("rule", MarginWhisperRule.PRIOR_MISCONCEPTION_GUARD.name)
                    put("bookId", bookId)
                    put("tocNodeId", tocNodeId)
                    put("page", page)
                    put("pdfPageAnchor", page)
                    put("quoteAnchor", quoteAnchor)
                    put("conceptKey", mastery.conceptKey)
                    put("dominantErrorTag", mastery.dominantErrorTag)
                    put("evidence", "掌握度台账记录 ${mastery.conceptKey} 存在未消解 ${mastery.dominantErrorTag} 记录。")
                    put("timestampMs", nowMs)
                }.toString()
                MarginWhisperCandidate(
                    whisperId = "mw-$bookId-$tocNodeId-p$page-error",
                    bookId = bookId,
                    tocNodeId = tocNodeId,
                    page = page,
                    rule = MarginWhisperRule.PRIOR_MISCONCEPTION_GUARD,
                    collapsedText = collapsed,
                    expandedText = expanded,
                    pdfPageAnchor = page,
                    quoteAnchor = quoteAnchor,
                    triggerEvidenceJson = evidenceJson,
                    createdAt = nowMs,
                )
            }
            rereadCountOnPage >= 2 &&
                cumulativeDwellMs.toDouble() > MasteryEngine.REREAD_DWELL_MULTIPLIER * personalMedianDwellMs -> {
                val dwellSec = (cumulativeDwellMs / 1000L).coerceAtLeast(1L)
                val ratio = cumulativeDwellMs.toDouble() / personalMedianDwellMs.coerceAtLeast(1.0)
                val collapsed = compactCollapsedText("这页已回看 $rereadCountOnPage 次，需要拆开讲吗", "这页回看多次，可看要点")
                val expanded = "你在第 $page 页累计停留约 ${dwellSec} 秒并往返重读 $rereadCountOnPage 次，点右侧「伴读」可对照本节要点或划句提问。"
                val evidenceJson = buildJsonObject {
                    put("rule", MarginWhisperRule.REREAD_FRICTION_ON_PAGE.name)
                    put("bookId", bookId)
                    put("tocNodeId", tocNodeId)
                    put("page", page)
                    put("pdfPageAnchor", page)
                    put("quoteAnchor", quoteAnchor)
                    put("rereadCount", rereadCountOnPage)
                    put("cumulativeDwellMs", cumulativeDwellMs)
                    put("personalMedianDwellMs", personalMedianDwellMs)
                    put("dwellRatio", "%.2f".format(ratio))
                    put("evidence", "第 $page 页往返重读 $rereadCountOnPage 次，累计停留 ${dwellSec}s 超个人中位页速 2.2 倍。")
                    put("timestampMs", nowMs)
                }.toString()
                MarginWhisperCandidate(
                    whisperId = "mw-$bookId-$tocNodeId-p$page-reread",
                    bookId = bookId,
                    tocNodeId = tocNodeId,
                    page = page,
                    rule = MarginWhisperRule.REREAD_FRICTION_ON_PAGE,
                    collapsedText = collapsed,
                    expandedText = expanded,
                    pdfPageAnchor = page,
                    quoteAnchor = quoteAnchor,
                    triggerEvidenceJson = evidenceJson,
                    createdAt = nowMs,
                )
            }
            else -> null
        }

        if (candidate != null) {
            shownSections += tocNodeId
        }
        return candidate
    }

    private fun compactCollapsedText(raw: String, fallback: String): String {
        val clean = raw.trim().ifBlank { fallback }
        val capped = if (clean.length > 24) clean.take(23) + "…" else clean
        return if (capped.endsWith("▸")) capped else "$capped ▸"
    }
}
