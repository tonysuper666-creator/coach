package com.coach.client

import com.coach.domain.Event
import com.coach.domain.Projection
import com.coach.domain.json
import com.coach.domain.project
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CourseViewTest {
    private var seq = 0L
    private fun event(type: String, payload: JsonObject, thread: String? = null) =
        Event(++seq, "e$seq", "C", type, recordedAt = seq, occurredAt = seq, deviceId = "d", actor = "SYSTEM", threadId = thread, payload = payload)

    private fun outline(vararg kps: Pair<String, String>) = event("outline_revised", json("ops" to JsonArray(kps.map {
        JsonObject(mapOf("op" to JsonPrimitive("add"), "kpId" to JsonPrimitive(it.first), "title" to JsonPrimitive(it.second)))
    })))

    private fun posed(attempt: String, group: String, letters: Map<String, String>, untaught: Boolean = false) = event(
        "question_posed",
        json(
            "attemptId" to attempt, "questionId" to "q-$attempt", "groupId" to group, "format" to "SINGLE_CHOICE", "kpIds" to listOf("K1"),
            "stem" to "题干 $attempt", "untaught" to untaught,
            "options" to JsonArray(letters.keys.map { JsonObject(mapOf("optionId" to JsonPrimitive(it), "text" to JsonPrimitive("选项 $it"))) }),
            "letterMap" to JsonObject(letters.mapValues { JsonPrimitive(it.value) }),
        ),
        thread = "kp:K1",
    )

    private fun view(events: List<Event>): CourseView = CourseView(events, project(events))

    @Test fun kpListKeepsOutlineOrderAndNames() {
        val v = view(listOf(outline("K1" to "信息不对称", "K2" to "逆向选择")))
        assertEquals(listOf("信息不对称", "逆向选择"), v.kps.map { it.title })
        assertTrue(v.kps.all { it.status == KpStatus.NEW })
        assertEquals("K1", v.continueTarget?.id)
    }

    @Test fun cardsShowTheServerAssignedLettersInOrder() {
        val v = view(listOf(outline("K1" to "A"), posed("a1", "g1", mapOf("o1" to "C", "o2" to "A", "o3" to "B", "o4" to "D"), untaught = true)))
        val card = v.quizGroups("kp:K1").single().cards.single()
        assertEquals(listOf("A", "B", "C", "D"), card.options.map { it.first })
        assertEquals("选项 o2", card.options.first().second)
        assertTrue(card.untaught)
        assertNull(card.answer)
        assertEquals("g1", v.pendingGroup("kp:K1")?.groupId)
    }

    @Test fun submittedAndGradedCardsShowPerQuestionResult() {
        val letters = mapOf("o1" to "A", "o2" to "B")
        val events = listOf(
            outline("K1" to "A"), posed("a1", "g1", letters), posed("a2", "g1", letters),
            event("answer_submitted", json("attemptId" to "a1", "answer" to "a")),
            event("answer_submitted", json("attemptId" to "a2", "answer" to "B")),
            event("attempt_graded", json("attemptId" to "a1", "score" to 1, "max" to 1, "status" to "EXACT", "feedback" to "对")),
            event("attempt_graded", json("attemptId" to "a2", "score" to 0, "max" to 1, "status" to "EXACT", "feedback" to "错")),
        )
        val group = view(events).quizGroups("kp:K1").single()
        assertTrue(group.submitted)
        assertEquals(listOf("A", "B"), group.cards.map { it.answer })
        assertEquals(listOf(true, false), group.cards.map { it.grade?.correct })
        assertNull(view(events).pendingGroup("kp:K1"))
    }

    @Test fun otherThreadsDoNotLeakIntoThisKp() {
        val v = view(listOf(outline("K1" to "A"), posed("a1", "g1", mapOf("o1" to "A", "o2" to "B"))))
        assertTrue(v.quizGroups("kp:K2").isEmpty())
    }
}
