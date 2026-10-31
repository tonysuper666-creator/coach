package com.coach.ai

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class PoolWorkerTest {
    private val source="Synthetic source says the uniquely correct result is the selected text."
    private fun generated()=JsonArray((0..2).map { i -> json("stem" to "Synthetic question $i asks for the uniquely correct result.",
        "correct_text" to "Right result $i","distractors" to listOf("Wrong one $i","Wrong two $i","Wrong three $i"),
        "explanation" to "The source supports this result.","source_quote" to source) }).toString()
    private fun review()=JsonArray((0..2).map { i -> json("id" to i,"answer_text" to "Right result $i","defects" to emptyList<String>(),"note" to "Supported") }).toString()

    @Test fun generationReviewPersistenceAndAtomicTake() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("pool-worker")
        val actor=LedgerActor(this,LedgerStore(dir))
        try { StubBridge().use { bridge ->
            bridge.enqueue(200,StubBridge.completion(generated()))
            bridge.enqueue(200,StubBridge.completion(review()))
            val worker=PoolWorker(actor,BridgeClient(stubConfig(bridge)))
            val id=worker.ensure("course","essay-kp","Synthetic objective",source,3)
            assertEquals(id,worker.ensure("course","essay-kp","Synthetic objective",source,3))
            assertEquals("completed",worker.runOne()!!.state)
            assertEquals(0,actor.execute { poolAvailable("course","essay-kp") })
            assertEquals("blind_review",actor.execute { supplyJobs().single().kind })
            assertEquals(3,worker.runOne()!!.inserted)
            assertNull(worker.ensure("course","essay-kp","Synthetic objective",source,3))
            assertEquals(BridgeClient.CLAUDE,JsonText.objectOf(bridge.requests[0]).str("model"))
            assertEquals(BridgeClient.CODEX,JsonText.objectOf(bridge.requests[1]).str("model"))
            val group=actor.execute { takePoolGroup("course","essay-kp","group") }
            assertEquals("READY",group.status); assertEquals(3,group.events.size)
            assertTrue(group.events.all { it.type == "question_posed" && "correctOptionId" !in it.payload })
            assertEquals(group.events,actor.execute { takePoolGroup("course","essay-kp","group") }.events)
            assertEquals(0,actor.execute { poolAvailable("course","essay-kp") })
            assertEquals(2,actor.execute { supplyCallCount() })
        } } finally { actor.close() }
        LedgerStore(dir).use { reopened ->
            assertEquals(0,reopened.poolAvailable("course","essay-kp"))
            assertEquals(3,reopened.takePoolGroup("course","essay-kp","group").events.size)
        }
        dir.toFile().deleteRecursively()
    }

    @Test fun fourFailureModesUseRealPoolAndEmptyPoolNeverFabricatesCards() = runBlocking<Unit> {
        for ((reason,status,body) in listOf(
            Triple("EMPTY",200,StubBridge.completion("[]")),
            Triple("TEXT_ONLY",200,StubBridge.completion("Let us do a question.")),
            Triple("MALFORMED",200,StubBridge.completion("[{broken]")),
            Triple("TIMEOUT",504,StubBridge.errorWithTerminal(504)),
        )) {
            val dir=Files.createTempDirectory("pool-failure")
            val actor=LedgerActor(this,LedgerStore(dir))
            try { StubBridge().use { bridge ->
                val worker=PoolWorker(actor,BridgeClient(stubConfig(bridge)))
                // Verified reserve comes through the real generator/reviewer boundary.
                bridge.enqueue(200,StubBridge.completion(generated()));bridge.enqueue(200,StubBridge.completion(review()))
                worker.ensure("c","kp","Objective",source,3);worker.runOne();worker.runOne()
                bridge.enqueue(status,body)
                worker.ensure("c","kp","Objective",source,6)
                assertEquals("failed",worker.runOne()!!.state)
                val group=actor.execute { takePoolGroup("c","kp","fallback") }
                assertEquals(3,group.events.size)
                val fallback=actor.execute { events("c").single { it.type == "fallback_used" } }
                assertEquals(reason,fallback.payload.str("reason"))
                worker.ensure("c","kp","Objective",source,3)
                val empty=actor.execute { takePoolGroup("c","kp","empty") }
                assertTrue(empty.events.isEmpty());assertEquals("REFILLING",empty.status)
                assertEquals(1,empty.jobs.size);assertEquals("题池暂空，正在补题（约 20 秒）",empty.message)
                assertEquals(3,actor.execute { events("c").count { it.type == "question_posed" } })
                println("POOL_FAILURE reason=$reason reserveCards=${group.events.size} emptyCards=${empty.events.size} progress=${empty.jobs.single().second}")
            } } finally { actor.close();dir.toFile().deleteRecursively() }
        }
    }

    @Test fun missingServedByCannotGenerateOrReviewAndIsNotInventedInAudit() {
        StubBridge().use { bridge ->
            val body=JsonText.objectOf(StubBridge.completion("ok"))
            val terminal=body.getValue("x_coach").jsonObject
            bridge.enqueue(200,JsonObject(body + ("x_coach" to JsonObject(terminal - "served_by"))).toString())
            val result=BridgeClient(stubConfig(bridge)).chat(ChatRequest("gen_mcq",BridgeClient.CLAUDE,"","",false))
            assertIs<BridgeResult.Failure>(result); assertEquals("served_by_missing",result.reason)
            assertNull(result.call.servedBy);assertEquals(JsonNull,result.call.toJson()["served_by"])
        }
    }
    @Test fun confirmedEvidenceOffsetsArePersistedAndReopenAsOriginalUtf16Slices() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("grade-evidence")
        val actor=LedgerActor(this,LedgerStore(dir))
        val answer="😀 因为买了保险之后信息不对称，他就不爱护身体。"
        val quote="因为买了保险之后信息不对称"
        try { StubBridge().use { bridge ->
            bridge.enqueue(200,StubBridge.completion(json("items" to JsonArray(listOf(json("id" to "R1","points" to 2,"reason" to "ok","evidence" to quote))),"total" to 2,"awarded" to 2).toString()))
            bridge.enqueue(200,StubBridge.completion("""{"verdict":"CONFIRM","note":"fine"}"""))
            val question=ConstructedQuestion("kp","Why?",answer,listOf(RubricItem("R1","Information asymmetry",2.0,"information")))
            val outcome=GradePipeline(BridgeClient(stubConfig(bridge))).gradeConstructed(question,answer)
            assertTrue(outcome.written)
            assertEquals(3,outcome.evidenceSpans.single().span.start)
            val hash=sha256(answer.toByteArray())
            val token=actor.execute { claimJob(enqueueJob("grade_constructed","attempt","v1",hash))!! }
            assertTrue(persistConfirmedGrade(actor,token,"v1",hash,"c","attempt",outcome))
            assertFalse(persistConfirmedGrade(actor,token,"v1",hash,"c","attempt",outcome))
        } } finally { actor.close() }
        LedgerStore(dir).use { store ->
            val evidence=store.events("c").single().payload.getValue("evidence").jsonArray.single().jsonObject
            assertEquals(quote,answer.substring(evidence.num("start").toInt(),evidence.num("end").toInt()))
            assertEquals(quote,evidence.str("text"));assertEquals("R1",evidence.str("rubricItemId"))
        }
        dir.toFile().deleteRecursively()
    }

}
