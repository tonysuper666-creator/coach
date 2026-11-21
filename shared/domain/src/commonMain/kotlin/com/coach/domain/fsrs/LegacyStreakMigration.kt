package com.coach.domain.fsrs

import kotlin.math.min

/**
 * One answer as the retired 1/3/7 store recorded it (`book_review_history`).
 * A guessed answer counts as a miss there, so `correct = !guessed`.
 */
data class LegacyAnswer(val day: Long, val correct: Boolean)

/**
 * A row from the retired `book_reviews` table.
 *
 * [streak] is the number of consecutive correct answers the 1/3/7 scheduler kept. [answers] is the
 * richer history when the row has one; without it the migration has to assume the streak was built
 * by correct answers (see [LegacyStreakMigration.migrate]).
 */
data class LegacyCardRecord(
    val cardId: String,
    val materialId: String,
    val kpId: String,
    val dueDay: Long,
    val streak: Int,
    val syllabusWeight: Double = 0.5,
    val lastReviewDay: Long? = null,
    val answers: List<LegacyAnswer> = emptyList(),
)

enum class MigrationPath {
    /** The old history was available and replayed answer by answer. */
    HISTORY_REPLAY,

    /** Only the streak survived; replayed as that many correct answers on the old spacing. */
    STREAK_ASSUMED,

    /** No correct answer recorded — imported as a never-answered card. */
    NEW,
}

data class MigratedCard(
    val card: FsrsQueueCard,
    val state: FsrsState,
    val path: MigrationPath,
    /** Due day FSRS computed before it was capped at the old promise; for the migration report. */
    val fsrsDueDay: Long,
)

data class MigrationReport(val cards: List<MigratedCard>) {
    val replayedHistory: Int get() = cards.count { it.path == MigrationPath.HISTORY_REPLAY }
    val assumedStreak: Int get() = cards.count { it.path == MigrationPath.STREAK_ASSUMED }
    val importedAsNew: Int get() = cards.count { it.path == MigrationPath.NEW }

    /** Cards the FSRS replay would have scheduled later than the old due date; kept earlier instead. */
    val pulledForward: Int get() = cards.count { it.fsrsDueDay > it.state.dueDay }
}

/**
 * Maps the retired 1/3/7 scheme onto FSRS-5.
 *
 * The old store kept only a consecutive-correct count, so a streak is replayed as that many GOOD
 * answers on the old spacing (1, 3, 7, 7, …). That is the strongest claim the old data supports:
 * it recovers a real stability and difficulty, but it cannot recover a lapse that the old store
 * forgot. When the per-answer history is present, it is replayed instead and misses become AGAIN.
 *
 * The migrated card never becomes due later than the old row promised: `dueDay` is
 * `min(FSRS due, legacy due)`, so a learner who was due today still sees the card today.
 */
object LegacyStreakMigration {
    /** The retired store's table, copied exactly: streak 0,1 → 1 day, 2 → 3 days, 3+ → 7 days. */
    fun legacyIntervalDays(streak: Int): Long = when {
        streak <= 1 -> 1L
        streak == 2 -> 3L
        else -> 7L
    }

    fun migrate(record: LegacyCardRecord, config: FsrsConfig = FsrsConfig()): MigratedCard {
        require(record.streak >= 0) { "streak cannot be negative" }
        require(record.syllabusWeight >= 0.0)
        val supplied = record.answers.sortedBy { it.day }
        val path: MigrationPath
        val answers: List<LegacyAnswer>
        if (supplied.isNotEmpty()) {
            path = MigrationPath.HISTORY_REPLAY
            answers = supplied
        } else if (record.streak > 0) {
            path = MigrationPath.STREAK_ASSUMED
            answers = assumedAnswers(record)
        } else {
            path = MigrationPath.NEW
            answers = emptyList()
        }

        var state = Fsrs5.newCard(dueDay = record.dueDay)
        for (answer in answers) {
            state = Fsrs5.review(
                state,
                if (answer.correct) FsrsRating.GOOD else FsrsRating.AGAIN,
                answer.day,
                config,
            )
        }
        val fsrsDueDay = state.dueDay
        val dueDay = if (answers.isEmpty()) record.dueDay else min(fsrsDueDay, record.dueDay)
        state = state.copy(dueDay = dueDay)
        return MigratedCard(
            card = FsrsQueueCard(
                cardId = record.cardId,
                materialId = record.materialId,
                kpId = record.kpId,
                syllabusWeight = record.syllabusWeight,
                state = state,
            ),
            state = state,
            path = path,
            fsrsDueDay = fsrsDueDay,
        )
    }

    fun migrateAll(records: List<LegacyCardRecord>, config: FsrsConfig = FsrsConfig()): MigrationReport =
        MigrationReport(records.map { migrate(it, config) })

    /** Rebuilds the old answer days backwards from the last answer on the 1/3/7 spacing. */
    private fun assumedAnswers(record: LegacyCardRecord): List<LegacyAnswer> {
        val last = record.lastReviewDay
            ?: (record.dueDay - legacyIntervalDays(record.streak)).coerceAtLeast(0)
        val days = LongArray(record.streak)
        days[record.streak - 1] = last
        for (i in record.streak - 1 downTo 1) {
            days[i - 1] = (days[i] - legacyIntervalDays(i)).coerceAtLeast(0)
        }
        return days.map { LegacyAnswer(day = it, correct = true) }
    }
}
