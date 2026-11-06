package com.coach.client

import com.coach.domain.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Reviewer (TON-1332) probe: the same question answered wrong again (weekly replay) must not show twice. */
class ReviewerMistakeDupTest {
    private var seq = 0L
    private val events = mutableListOf<Event>()
    private fun event(type: String, p: JsonObject) {
        events += Event(++seq,"e$seq","course",type,recordedAt=seq,occurredAt=seq,deviceId="phone",actor="SYSTEM",payload=p)
    }
    private fun wrong(id: String, question: String, weekReview: Boolean = false) {
        val base = mutableMapOf<String, Any?>("attemptId" to id,"questionId" to question,"kpIds" to listOf("kp"),
            "stem" to "Which result?", "format" to "SINGLE_CHOICE",
            "options" to JsonArray(listOf(json("optionId" to "right","text" to "Right result"),json("optionId" to "wrong","text" to "Wrong result"))),
            "letterMap" to json("right" to "B","wrong" to "A"))
        if (weekReview) { base["review"] = true; base["weekReview"] = 2 }
        event("question_posed",json(*base.toList().toTypedArray()))
        event("answer_submitted",json("attemptId" to id,"answer" to "A"))
        event("attempt_graded",json("attemptId" to id,"score" to 0,"max" to 1,"status" to "EXACT","feedback" to "回答错误，正确答案是 B。"))
    }
    @Test fun replayedWrongQuestionIsListedOnce() {
        wrong("first","q1"); wrong("replay","q1",weekReview=true)
        val items = mistakeReview(events).items
        assertEquals(1, items.size, "same question shown twice: ${items.map { it.attemptId }}")
    }
}
