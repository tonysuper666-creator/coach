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
    val lapses: Int = 0, val lastResult: String? = null, val lastReviewDay: Long? = null)
@Serializable data class Journal(val answers: Int = 0, val correct: Int = 0, val orphaned: Int = 0,
    val followups: Int = 0, val notes: Int = 0, val minutes: Double = 0.0,
    val opened: List<String> = emptyList(), val delivered: List<String> = emptyList(),
    val newlyPassed: List<String> = emptyList(), val newlySolid: List<String> = emptyList(),
    val reviewPass: Int = 0, val reviewNeedsWork: Int = 0, val reviewFail: Int = 0,
    val summaryFromSeq: Long? = null, val summaryStale: Boolean = false, val rev: Long = 0)
@Serializable data class Position(val current: String? = null, val pending: Map<String, String> = emptyMap(), val continueTarget: String? = null)
@Serializable data class Coverage(val passed: Int = 0, val solid: Int = 0, val total: Int = 0)
@Serializable data class Projection(
    val version: Int = PROJECTION_VERSION, val lastSeq: Long = 0,
    val position: Position = Position(), val mastery: Map<String, Mastery> = emptyMap(),
    val review: Map<String, Review> = emptyMap(), val journal: Map<Long, Journal> = emptyMap(),
    val coverage: Coverage = Coverage(), val orphanedAttempts: List<String> = emptyList(),
    val unsupportedTypes: List<String> = emptyList(), val requiresServerSnapshot: Boolean = false
) {
    fun bytes(): String = canonicalJson(LedgerJson.encodeToJsonElement(serializer(), this))
}
