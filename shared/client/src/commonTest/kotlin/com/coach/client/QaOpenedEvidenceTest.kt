package com.coach.client

import com.coach.domain.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

/** Independent synthetic-ledger reproductions; no user content. */
class QaOpenedEvidenceTest {
    private val now = 1_794_225_600_000L
    private fun e(n: Long, type: String, p: JsonObject) = Event(n, "qa$n", "C", type,
        recordedAt = now, occurredAt = now, deviceId = "qa", actor = "SYSTEM", payload = p)

    @Test fun chatKeepsAnOpenedPointInProgressAfterOpeningAnother() {
        val events = listOf(
            e(1, "outline_revised", json("knowledgePoints" to JsonArray(listOf(
                json("id" to "A", "title" to "合成甲"), json("id" to "B", "title" to "合成乙"))))),
            e(2, "kp_opened", json("kpId" to "A")),
            e(3, "kp_opened", json("kpId" to "B")))
        val p = project(events)
        assertEquals(KpStatus.IN_PROGRESS, CourseView(events, p).kp("A")!!.status)
        val answer = p.today(now, "A").answer(ProgressIntent.CURRENT_KP)
        assertTrue("：在学" in answer, "Opened A's row says 在学; its thread answer says $answer")
    }

    @Test fun anOutlineEntryIsNotEvidenceThatThePointWasOpened() {
        val events = listOf(e(1, "outline_revised", json("ops" to JsonArray(listOf(
            json("kpId" to "A", "title" to "合成甲"))))))
        val p = project(events)
        assertEquals(KpStatus.NEW, CourseView(events, p).kp("A")!!.status)
        val answer = p.today(now, "A").answer(ProgressIntent.CURRENT_KP)
        assertTrue("：未学" in answer, "Never-opened A's row says 未学; its answer says $answer")
    }
}
