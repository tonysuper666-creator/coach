package com.coach.domain
import kotlinx.serialization.Serializable

@Serializable data class Kp(val id: String, val prereqs: List<String> = emptyList(), val retired: Boolean = false)
@Serializable data class SolidProgress(val count: Int = 0, val constructed: Int = 0, val days: Int = 0, val average: Double = 0.0)
@Serializable data class Mastery(
    val passed: Boolean = false, val passedBy: String? = null, val passedAtSeq: Long? = null,
    val solid: Boolean = false, val formerlySolid: Boolean = false, val needsRepair: Boolean = false,
    val window: List<String> = emptyList(), val support: List<String> = emptyList(),
    val solidProgress: SolidProgress = SolidProgress(), val revokedAtSeq: Long? = null, val revokedReason: String? = null, val solidLatch: List<String> = emptyList()
)
@Serializable data class Review(val dueDay: Long, val intervalDays: Int = 1, val ease: Double = 2.3,
    val lapses: Int = 0, val lastResult: String? = null, val lastReviewDay: Long? = null,
    val baseDueDay: Long? = null, val baseIntervalDays: Int? = null, val snoozedUntilDay: Long? = null,
    val targetRetention: Double = ReviewRetentionPolicy.BASELINE)
@Serializable data class Journal(val answers: Int = 0, val correct: Int = 0, val orphaned: Int = 0,
    val followups: Int = 0, val notes: Int = 0, val minutes: Double = 0.0,
    val opened: List<String> = emptyList(), val delivered: List<String> = emptyList(),
    val newlyPassed: List<String> = emptyList(), val newlySolid: List<String> = emptyList(),
    val reviewPass: Int = 0, val reviewNeedsWork: Int = 0, val reviewFail: Int = 0,
    val summaryFromSeq: Long? = null, val summaryStale: Boolean = false, val rev: Long = 0,
    val summary: String? = null, val summaryModel: String? = null, val summaryNotice: String? = null, val manual: String = "", val legacyLogs: List<LegacyJournalEntry> = emptyList())
@Serializable data class Position(val current: String? = null, val pending: Map<String, String> = emptyMap(), val continueTarget: String? = null)
@Serializable data class QuickAskThread(val threadId: String, val courseId: String, val day: Long, val kpId: String?)

@Serializable data class LegacyJournalEntry(val sourceId: String, val text: String)

/** Exam points, never a ratio of knowledge-point counts. */
@Serializable data class ExamCoverage(val passed: Int = 0, val solid: Int = 0, val total: Int = 0,
    /** "FINAL" when the weights are the final exam's; blank = weights imported from the midterm scope. */
    val scope: String = "")
typealias Coverage = ExamCoverage
typealias JournalDay = Journal

@Serializable data class Projection(
    val version: Int = PROJECTION_VERSION, val lastSeq: Long = 0, val timeZone: String = "Europe/London",
    val taughtConceptIds: Set<String> = emptySet(), val explainedKpIds: Set<String> = emptySet(),
    /** Explicit opened, explanation or question facts; an outline/mastery entry alone is not study evidence. */
    val startedKpIds: Set<String> = emptySet(),
    val kpTitles: Map<String, String> = emptyMap(),
    val activeKpIds: Set<String>? = null, val examDay: Long? = null,
    val quickAsks: Map<Long, QuickAskThread> = emptyMap(),
    val studyScope: StudyScope? = null,
    val position: Position = Position(), val mastery: Map<String, Mastery> = emptyMap(),
    val review: Map<String, Review> = emptyMap(),
    /** Points not yet passed that a missed or guessed answer put back into the due queue, by due day. */
    val revisit: Map<String, Long> = emptyMap(), val journal: Map<Long, Journal> = emptyMap(),
    val coverage: Coverage = Coverage(), val orphanedAttempts: List<String> = emptyList(),
    val unsupportedTypes: List<String> = emptyList(), val requiresServerSnapshot: Boolean = false,
    /** Explicit checked 1..3 stars, independent of exam point weights. */
    val reviewStars: Map<String, Int> = emptyMap()
) {
    val examCoverage: ExamCoverage get() = coverage
    fun bytes(): String = canonicalJson(LedgerJson.encodeToJsonElement(serializer(), this))
}
