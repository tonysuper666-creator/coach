package com.coach.client

import com.coach.domain.*
import kotlin.test.*

class AgentRunCourseViewTest {
    @Test fun normalReopenCannotAdoptAgentThreadAndEachRunReusesOnlyItsOwnThread() {
        fun event(seq: Long, thread: String) = Event(seq,"e$seq","course","kp_opened",
            recordedAt=1,occurredAt=1,deviceId="phone",actor="LEARNER",idemKey="i$seq",threadId=thread,
            payload=json("kpId" to "kp","title" to "Synthetic W8","via" to "LEARN"))
        val runThread = agentThreadId("oppo","legacy-human")
        val facts = listOf(event(1,"legacy-human"),event(2,runThread),event(3,agentThreadId("other","legacy-human")))
        val normal = CourseView(facts,project(facts))
        assertEquals("legacy-human",normal.tutorThreadId("kp"))
        assertEquals(1,normal.events.size)
        val agent = CourseView(facts,project(facts),"oppo")
        assertEquals(runThread,agent.tutorThreadId("kp"))
        assertEquals(2,agent.events.size)
        assertEquals(agentThreadId("oppo","kp:new"),agent.tutorThreadId("new"))
        val wire = facts.map(::agentEvidenceForWire)
        assertEquals("legacy-human",CourseView(wire,project(wire)).tutorThreadId("kp"))
        assertEquals(runThread,CourseView(wire,project(wire),"oppo").tutorThreadId("kp"))
    }
}
