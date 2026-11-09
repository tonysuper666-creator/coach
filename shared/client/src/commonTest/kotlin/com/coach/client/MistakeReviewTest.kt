package com.coach.client

import com.coach.domain.*
import kotlinx.serialization.json.*
import kotlin.test.*

class MistakeReviewTest {
    private var seq = 0L
    private val events = mutableListOf<Event>()
    private fun event(type: String, p: JsonObject) {
        events += Event(++seq,"e$seq","course",type,recordedAt=seq,occurredAt=seq,deviceId="phone",actor="SYSTEM",payload=p)
    }
    private fun answer(id: String, correct: Boolean, kp: String = "kp", question: String = id) {
        event("question_posed",json("attemptId" to id,"questionId" to question,"kpIds" to listOf(kp),
            "stem" to "Which result?", "format" to "SINGLE_CHOICE",
            "options" to JsonArray(listOf(json("optionId" to "right","text" to "Right result"),json("optionId" to "wrong","text" to "Wrong result"))),
            "letterMap" to json("right" to "B","wrong" to "A")))
        event("answer_submitted",json("attemptId" to id,"answer" to if(correct) "B" else "A"))
        event("attempt_graded",json("attemptId" to id,"score" to if(correct) 1 else 0,"max" to 1,"status" to "EXACT",
            "feedback" to if(correct) "回答正确（B）。" else "回答错误，正确答案是 B。"))
    }
    @Test fun filtersOnlyWrongAndUsesFrozenOptionLetters() {
        answer("wrong1",false); answer("right",true); answer("wrong2",false,"other")
        val result=mistakeReview(events.reversed()+events)
        assertEquals(listOf("wrong1","wrong2"),result.items.map { it.attemptId })
        assertEquals("B · Right result",result.items.first().correctAnswer)
        assertEquals("A",result.items.first().card.answer)
    }
    @Test fun threeConsecutiveNewCorrectAnswersClearOnlyThatKnowledgePoint() {
        answer("w",false); answer("other",false,"other")
        answer("c1",true); answer("c2",true)
        assertEquals(2,mistakeReview(events).items.size)
        answer("c3",true)
        assertEquals(listOf("other"),mistakeReview(events).items.map { it.attemptId })
        answer("new-wrong",false)
        assertEquals(listOf("other","new-wrong"),mistakeReview(events).items.map { it.attemptId })
    }
    @Test fun anotherErrorResetsTheStreak() {
        answer("w",false); answer("c1",true); answer("c2",true); answer("w2",false); answer("c3",true)
        assertEquals(2,mistakeReview(events).items.size)
        answer("c4",true); answer("c5",true)
        assertTrue(mistakeReview(events).items.isEmpty())
    }
    @Test fun hintedVoidedRepeatedAndUncertainAnswersCannotClear() {
        answer("w",false); answer("c1",true); answer("c2",true)
        event("hint_revealed",json("attemptId" to "c2"))
        answer("c3",true); answer("c4",true)
        event("attempt_voided",json("attemptId" to "c4"))
        answer("repeat",true,question="c3")
        answer("draft",true)
        event("grade_reviewed",json("attemptId" to "draft","decision" to "UNCERTAIN"))
        assertEquals(listOf("w"),mistakeReview(events).items.map { it.attemptId })
    }
    @Test fun lateReviewRebuildsTheOriginalAnswerOrder() {
        answer("w",false); answer("c1",true); answer("c2",true); answer("c3",true)
        assertTrue(mistakeReview(events).items.isEmpty())
        event("grade_reviewed",json("attemptId" to "c2","decision" to "REVISE","revised" to json("score" to 0,"max" to 1)))
        assertEquals(listOf("w","c2"),mistakeReview(events).items.map { it.attemptId })
        event("attempt_voided",json("attemptId" to "w"))
        assertEquals(listOf("c2"),mistakeReview(events).items.map { it.attemptId })
    }
    @Test fun emptyStatesDistinguishNoErrorsFromClearedErrors() {
        assertEquals("还没有答错的题",mistakeReview(events).emptyText)
        answer("c",true)
        assertEquals("还没有答错的题",mistakeReview(events).emptyText)
        answer("w",false); answer("c1",true); answer("c2",true); answer("c3",true)
        assertEquals("错题都已掌握，继续保持。",mistakeReview(events).emptyText)
    }
    @Test fun multipleKnowledgePointsDoNotDuplicateAQuestion() {
        answer("w",false)
        events[0] = events[0].copy(payload=JsonObject(events[0].payload + ("kpIds" to JsonArray(listOf(JsonPrimitive("kp"),JsonPrimitive("other"))))))
        assertEquals(1,mistakeReview(events).items.size)
        answer("c1",true); answer("c2",true); answer("c3",true)
        assertEquals("other",mistakeReview(events).items.single().kpId)
    }
    @Test fun structuredAnswerAndRationaleSurviveReplicaReplay() {
        answer("w",false)
        event("attempt_graded",json("attemptId" to "w","score" to 0,"max" to 1,"status" to "EXACT",
            "correctAnswer" to "B · Right result","rationale" to "The cited source establishes this result."))
        val v=CourseView(events,Projection())
        assertEquals("The cited source establishes this result.",v.mistakes.items.single().reason)
        assertEquals("B · Right result",v.mistakes.items.single().correctAnswer)
    }
}
