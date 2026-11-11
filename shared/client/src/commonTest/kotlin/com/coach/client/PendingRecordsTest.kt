package com.coach.client

import com.coach.domain.*
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class PendingRecordsTest {
    private fun records(mainline: Boolean, key: String?, purpose: String = "PRACTICE"): RecordsView {
        val payloads = listOf(
            "question_posed" to JsonObject(json("attemptId" to "a", "questionId" to "q",
                "format" to "SINGLE_CHOICE", "purpose" to purpose, "mainline" to mainline) +
                if (key == null) emptyMap() else json("keyState" to key)),
            "answer_submitted" to json("attemptId" to "a", "answer" to "A"),
            "attempt_graded" to json("attemptId" to "a", "status" to "EXACT", "score" to 100, "max" to 100),
            "question_key_changed" to json("questionId" to "q", "keyState" to "VERIFIED"),
        )
        return RecordsView(payloads.mapIndexed { i, (type, p) -> Event(i + 1L, "e$i", "C", type,
            recordedAt = 1_000L, occurredAt = 1_000L, deviceId = "synthetic", actor = "SYSTEM", payload = p) })
    }

    @Test fun recordUsesFrozenKeyEvenAfterVerificationIncludingLegacyMissingState() {
        for (key in listOf("UNREVIEWED", "REJECTED", null)) {
            val detail = assertNotNull(records(true, key).attempt("a"))
            assertTrue(detail.pendingKey)
            assertFalse(detail.counted)
            assertNull(detail.effectiveGrade)
            assertEquals(100, detail.grade?.score, "retain the historical fact for audit")
        }
    }

    @Test fun verifiedMainlineTutoringAndMockRecordsKeepTheirGrades() {
        for (view in listOf(records(true, "VERIFIED"), records(false, null), records(false, "VERIFIED", "FULL_MOCK"))) {
            val detail = assertNotNull(view.attempt("a"))
            assertFalse(detail.pendingKey)
            assertTrue(detail.counted)
            assertEquals(100, detail.effectiveGrade?.score)
        }
    }
}
