package com.coach.core

import com.coach.domain.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AgentRunTutorTest {
    @Test fun agentCanLearnAndAnswerThreeWithoutTeachingOrPassingForTheHuman(): Unit = runBlocking {
        TutorFixture().use { f ->
            f.seed(); f.open()
            val before = f.state()
            val thread = agentThreadId("oppo-w8","thread")
            f.open(thread); f.teach(thread)
            val next = f.machine.next("course",thread)
            assertNull(next.plan,"The agent's own explanation advances its lesson")
            assertEquals(3,next.cards.size)
            val result = f.machine.submit("course",thread,next.cards.first().payload.str("groupId"),f.answers(next.cards),"phone","agent-submit")
            assertEquals(3,result.grades.size)
            val after = f.state()
            assertEquals(before.copy(lastSeq=after.lastSeq),after)
            val facts = f.events()
            assertTrue(project(facts,includeAgentRun="oppo-w8").mastery.getValue("kp-1").passed)
            facts.filter { it.threadId==thread || it.type=="attempt_graded" }.forEach {
                assertEquals(AGENT_RUN_LABEL,it.payload.str("sampleOrigin"))
            }
            // The ordinary thread must still explain; it cannot inherit agent teaching.
            assertNotNull(f.machine.next("course","thread").plan)
        }
    }
}
