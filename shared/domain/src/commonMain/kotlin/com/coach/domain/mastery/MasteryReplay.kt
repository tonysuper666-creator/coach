package com.coach.domain.mastery

import kotlin.math.exp
import kotlin.math.ln

/**
 * 2.0-AI-1 (Stage 4) nine learner evidence signal classes matching `learner_evidence_events.signal_type`.
 */
enum class LearnerSignalType {
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
enum class MisconceptionErrorClass(val labelZh: String) {
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

data class ConceptMasteryRecord(
    val conceptKey: String,
    val collectionId: String? = null,
    val bookId: String,
    val tocNodeId: String,
    val canonicalName: String,
    val syllabusWeight: Double = 1.0,
    val pMastery: Double = MasteryReplay.DEFAULT_P_MASTERY,
    val hasActiveTest: Boolean = false,
    val fsrsStability: Double = 1.0,
    val dominantErrorTag: String? = null,
    val rereadCount: Int = 0,
    val dwellMsTotal: Long = 0L,
    val askCount: Int = 0,
    val highlightCount: Int = 0,
    val updatedAt: Long = kotlin.time.Clock.System.now().toEpochMilliseconds(),
)

object MasteryReplay {
    const val DEFAULT_P_MASTERY: Double = 0.15
    const val MIN_P_MASTERY: Double = 0.05
    const val MAX_P_MASTERY: Double = 0.98
    const val PASSIVE_MASTERY_CAP: Double = 0.35
    const val MIN_DWELL_MS = 8_000L

    fun applySignal(
        current: ConceptMasteryRecord,
        signal: LearnerSignalType,
        scoreRatio: Double? = null,
        guessed: Boolean = false,
        errorClass: MisconceptionErrorClass? = null,
        durationMs: Long = 0L,
        fsrsStability: Double? = null,
        nowMs: Long = kotlin.time.Clock.System.now().toEpochMilliseconds(),
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
                if (!nextActive && addedDuration >= MIN_DWELL_MS) {
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

    private fun logit(p: Double): Double {
        val clamped = p.coerceIn(1e-6, 1.0 - 1e-6)
        return ln(clamped / (1.0 - clamped))
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
}
