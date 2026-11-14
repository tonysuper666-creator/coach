package com.coach.store

import com.coach.domain.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class AgentRunPersistenceTest {
    @Test fun bothMessageRolesAndLateGradesRetainOriginAcrossRestart() {
        val directory = Files.createTempDirectory("coach-agent-")
        val thread = agentThreadId("oppo-w8","kp:one")
        try {
            LedgerStore(directory).use { store ->
                store.bindTutorThread("course","human","one")
                store.bindTutorThread("course",thread,"one")
                assertFailsWith<IllegalArgumentException> { store.bindTutorThread("course","another-human","one") }
                store.saveChatInput("course",thread,"  Synthetic question\n原文  ","phone","input")
                store.beginStreamingMessage(thread,"reply","synthetic")
                store.finishMessage("reply","complete",null,"Synthetic reply",1)
                store.append("course","question_posed",json("attemptId" to "a","questionId" to "q","kpIds" to listOf("one"),"format" to "SHORT_ANSWER"),thread=thread)
                store.append("course","answer_submitted",json("attemptId" to "a","answer" to "synthetic"))
                store.append("course","attempt_graded",json("attemptId" to "a","score" to 10,"max" to 10,"status" to "EXACT"))
                assertTrue(store.snapshot("course").projection.mastery.isEmpty())
                // A matching-watermark v12 cache may already contain agent learning.
                val old = project(store.events("course"),includeAgentRun="oppo-w8").copy(version=12)
                assertTrue(old.mastery.isNotEmpty())
                DriverManager.getConnection("jdbc:sqlite:${directory.resolve("coach.sqlite")}").use { db ->
                    db.prepareStatement("UPDATE projection_cache SET version=12,blob=? WHERE course_id='course'").use {
                        it.setBytes(1,LedgerJson.encodeToString(old).toByteArray())
                        assertEquals(1,it.executeUpdate())
                    }
                }
            }
            LedgerStore(directory).use { store ->
                assertTrue(store.hello().agentRunsSupported)
                val messages = store.chatHistory("course",thread).getValue("messages").jsonArray.map { it.jsonObject }
                assertEquals(setOf("user","assistant"),messages.map { it.str("role") }.toSet())
                assertEquals("  Synthetic question\n原文  ",messages.first { it.str("role")=="user" }.str("text"))
                messages.forEach { assertEquals(AGENT_RUN_LABEL,it.str("sampleOrigin")); assertEquals("oppo-w8",it.str("agentRunId")) }
                store.events("course").forEach { assertEquals("oppo-w8",it.payload.str("agentRunId")); assertEquals(AGENT_RUN_LABEL,it.payload.str("sampleOrigin")) }
                assertEquals(PROJECTION_VERSION,store.snapshot("course").projection.version)
                assertTrue(store.snapshot("course").projection.mastery.isEmpty())
                store.append("course","question_posed",json("attemptId" to "human-attempt","questionId" to "human-q","kpIds" to listOf("one"),"format" to "SHORT_ANSWER"),thread="human")
                assertFailsWith<IllegalArgumentException> {
                    store.append("course","answer_submitted",JsonObject(json("attemptId" to "human-attempt") + agentRunMetadata("oppo-w8")))
                }
                assertEquals("",store.events("course").last().payload.str("agentRunId"))
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
