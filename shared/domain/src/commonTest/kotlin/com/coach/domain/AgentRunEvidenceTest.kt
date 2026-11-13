package com.coach.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class AgentRunEvidenceTest {
    private val events = mutableListOf<Event>()
    private fun add(type: String, payload: JsonObject, run: String? = null) {
        val seq = events.size.toLong() + 1
        events += Event(seq,"e$seq","course",type,recordedAt=1793188801000L,occurredAt=1793188800000L,
            deviceId="phone",actor="LEARNER",idemKey="i$seq",threadId=run?.let { agentThreadId(it,"kp") },
            payload=JsonObject(payload + agentRunMetadata(run)))
    }
    private fun answer(id: String, kp: String, score: Int, run: String? = null, question: String = id) {
        add("question_posed",json("attemptId" to id,"questionId" to question,"kpIds" to listOf(kp),
            "fingerprint" to question,"keyState" to "VERIFIED","origin" to "COACH","purpose" to "PRACTICE",
            "format" to "SINGLE_CHOICE","policyVersion" to 1,"review" to true),run)
        add("answer_submitted",json("attemptId" to id,"answer" to "A"),run)
        // Deliberately omit provenance on the delayed grade: the posed attempt must be enough.
        add("attempt_graded",json("attemptId" to id,"score" to score,"max" to 10,"status" to "EXACT"))
    }

    @Test fun agentSuccessFailureAndReviewCannotChangeAnyW1ThroughW8LearningNumbers() {
        add("outline_revised",json("ops" to JsonArray((1..8).map { json("kpId" to "kp$it","title" to "【W$it】 Synthetic") })))
        for (week in 1..8) {
            repeat(3) { answer("human-$week-$it","kp$week",if (week % 2 == 0) 10 else 0) }
        }
        val before = project(events)
        val mistakes = (1..8).associateWith { weekMistakes(events,it).map(Event::id) }
        assertEquals(4,before.mastery.values.count { it.passed })
        assertTrue(before.review.isNotEmpty())
        assertTrue(mistakes.values.any { it.isNotEmpty() })
        for (week in 1..8) {
            repeat(3) { answer("agent-$week-$it","kp$week",if (week % 2 == 0) 0 else 10,"oppo-w8",question="human-$week-$it") }
            add("review_completed",json("kpId" to "kp$week","result" to "FAIL"),"oppo-w8")
            add("kp_overridden",json("kpId" to "kp$week","kind" to "MARK_PASSED"),"oppo-w8")
            add("explanation_delivered",json("kpId" to "kp$week","conceptIds" to listOf("agent-concept")),"oppo-w8")
        }
        val after = project(events)
        assertEquals(before.copy(lastSeq=events.last().seq),after,"Includes mastery, weak points, review dates, journal and position")
        assertEquals(after,project(events.reversed()),"Replay order must not change exclusion")
        assertEquals(mistakes,(1..8).associateWith { weekMistakes(events,it).map(Event::id) })
        val wire = events.map(::agentEvidenceForWire)
        assertEquals(after,project(wire),"HTTP and socket facts must preserve the clean projection")
        assertEquals(project(events,includeAgentRun="oppo-w8"),project(wire,includeAgentRun="oppo-w8"))
        assertEquals(mistakes,(1..8).associateWith { weekMistakes(wire,it).map(Event::id) })
        assertTrue(wire.filter { it.type == "agent_run_fact" }.all { Upcasters().upcast(it) == null },
            "Legacy projectors must fall back to the clean server projection")
        // A later genuine attempt at questions seen by the agent still counts normally.
        repeat(3) { answer("later-$it","kp1",10,question="new-question-$it") }
        assertTrue(project(events).mastery.getValue("kp1").passed)
    }

    @Test fun onlyTheExplicitRunCanUseItsOwnTeachingAndGrades() {
        answer("a","kp",10,"first")
        add("explanation_delivered",json("kpId" to "kp","conceptIds" to listOf("concept")),"first")
        repeat(3) { answer("other-$it","kp",10,"second") }
        assertTrue(project(events).mastery.isEmpty())
        val own = project(events,includeAgentRun="first")
        assertEquals(listOf("a"),own.mastery.getValue("kp").window)
        assertEquals(setOf("concept"),own.taughtConceptIds)
        assertFalse(own.mastery.getValue("kp").passed)
    }

    @Test fun explicitLabelWithoutRunIdAlsoExcludesLateReviews() {
        answer("agent","kp",0,"run")
        add("grade_reviewed",json("attemptId" to "agent","decision" to "REVISE","revised" to json("score" to 10,"max" to 10)))
        val labelOnly = events.map { e -> e.copy(threadId=null,payload=JsonObject(e.payload - "agentRunId")) }
        assertTrue(project(labelOnly).mastery.isEmpty())
        assertTrue(learnerEvidence(labelOnly).isEmpty())
        assertFailsWith<IllegalArgumentException> { agentThreadId("first",agentThreadId("second","kp")) }
    }
}
