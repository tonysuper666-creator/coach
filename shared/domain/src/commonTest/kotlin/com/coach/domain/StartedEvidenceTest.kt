package com.coach.domain

import kotlin.test.*

class StartedEvidenceTest {
    private fun event(seq: Long, type: String, payload: kotlinx.serialization.json.JsonObject, thread: String? = null) =
        Event(seq, "e$seq", "C", type, recordedAt = seq, occurredAt = seq,
            deviceId = "test", actor = "SYSTEM", payload = payload, threadId = thread)

    @Test fun explicitStudyFactsSurviveReplaySerializationAndAnotherOpen() {
        val events = listOf(
            event(1, "kp_opened", json("kpId" to "opened")),
            event(2, "explanation_delivered", json("kpId" to "explained")),
            event(3, "question_posed", json("kpIds" to listOf("posed"), "attemptId" to "a", "questionId" to "q")),
            event(4, "legacy_attempt", json("kpIds" to listOf("legacy"), "attemptId" to "b", "questionId" to "r")),
            event(5, "kp_opened", json("kpId" to "other", "via" to "REVIEW")))
        val projected = project(events.reversed())
        val restored = LedgerJson.decodeFromString(Projection.serializer(), projected.bytes())
        assertEquals(setOf("opened", "explained", "posed", "legacy", "other"), restored.startedKpIds)
        for (id in restored.startedKpIds) assertTrue(restored.today(10, id).currentOpened, id)
    }

    @Test fun outlineAndQuickAskDoNotInventStudyEvidence() {
        val events = listOf(
            event(1, "outline_revised", json("ops" to listOf<String>())),
            event(2, "quick_ask_opened", json("day" to 0), "quick"),
            event(3, "kp_opened", json("kpId" to "quick"), "quick"),
            event(4, "explanation_delivered", json("kpId" to "quick"), "quick"),
            event(5, "question_posed", json("kpIds" to listOf("quick")), "quick"))
        val state = project(events).copy(mastery = mapOf("default" to Mastery()))
        assertTrue(state.startedKpIds.isEmpty())
        assertFalse(state.today(10, "default").currentOpened)
        assertFalse(state.today(10, "quick").currentOpened)
    }
}
