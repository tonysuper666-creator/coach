package com.coach.domain.fsrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyStreakMigrationTest {
    private fun record(
        id: String = "c1",
        dueDay: Long = 20,
        streak: Int = 0,
        lastReviewDay: Long? = null,
        answers: List<LegacyAnswer> = emptyList(),
    ) = LegacyCardRecord(
        cardId = id,
        materialId = "book/08",
        kpId = "kp-$id",
        dueDay = dueDay,
        streak = streak,
        lastReviewDay = lastReviewDay,
        answers = answers,
    )

    @Test
    fun intervalsAreTheRetiredStoresOwnTable() {
        assertEquals(1L, LegacyStreakMigration.legacyIntervalDays(0))
        assertEquals(1L, LegacyStreakMigration.legacyIntervalDays(1))
        assertEquals(3L, LegacyStreakMigration.legacyIntervalDays(2))
        assertEquals(7L, LegacyStreakMigration.legacyIntervalDays(3))
        assertEquals(7L, LegacyStreakMigration.legacyIntervalDays(12))
    }

    @Test
    fun aCardWithNoCorrectAnswerBecomesNewAndStaysDue() {
        val migrated = LegacyStreakMigration.migrate(record(streak = 0, dueDay = 7))
        assertEquals(MigrationPath.NEW, migrated.path)
        assertTrue(migrated.state.isNew)
        assertNull(migrated.state.stability)
        assertEquals(0, migrated.state.reps)
        assertEquals(7, migrated.state.dueDay)
    }

    @Test
    fun aStreakIsReplayedAsConsecutiveGoodAnswers() {
        val migrated = LegacyStreakMigration.migrate(record(streak = 3, dueDay = 20))
        assertEquals(MigrationPath.STREAK_ASSUMED, migrated.path)
        assertEquals(3, migrated.state.reps)
        assertEquals(0, migrated.state.lapses)
        assertEquals(13, migrated.state.lastReviewDay)
        val stability = migrated.state.stability
        assertTrue(stability != null && stability > 3.173, "stability $stability should exceed the first GOOD answer")
        assertEquals(20, migrated.state.dueDay)
        assertTrue(migrated.fsrsDueDay > 20, "FSRS alone would have waited past the old promise")
    }

    @Test
    fun historyReplayTurnsAMissIntoALapse() {
        val migrated = LegacyStreakMigration.migrate(
            record(
                streak = 1,
                dueDay = 30,
                answers = listOf(
                    LegacyAnswer(day = 0, correct = true),
                    LegacyAnswer(day = 1, correct = false),
                    LegacyAnswer(day = 5, correct = true),
                ),
            )
        )
        assertEquals(MigrationPath.HISTORY_REPLAY, migrated.path)
        assertEquals(1, migrated.state.lapses)
        assertEquals(3, migrated.state.reps)
        assertEquals(5, migrated.state.lastReviewDay)
    }

    @Test
    fun migrationNeverMakesACardDueLaterThanBefore() {
        val report = LegacyStreakMigration.migrateAll(
            listOf(
                record(id = "overdue", dueDay = 0, lastReviewDay = 0, streak = 1),
                record(id = "today", dueDay = 40, streak = 2),
                record(id = "fresh", dueDay = 90, streak = 0),
            )
        )
        assertEquals(3, report.cards.size)
        assertEquals(1, report.importedAsNew)
        assertEquals(2, report.assumedStreak)
        assertEquals(0, report.replayedHistory)
        for ((index, migrated) in report.cards.withIndex()) {
            val original = listOf(0L, 40L, 90L)[index]
            assertTrue(migrated.state.dueDay <= original, "due ${migrated.state.dueDay} moved past $original")
        }
        assertTrue(report.pulledForward >= 1)
    }
}
