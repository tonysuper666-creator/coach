package com.coach.ai

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.store.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class ConstructedGradeWorkerTest {
    private val answer="😀 Uncertainty prevents fully informed market choices."
    private val quote="Uncertainty prevents fully informed market choices."
    private val rubric=listOf(RubricItem("R1","Explains uncertainty",10.0,"Synthetic basis"))
    private val question=ConstructedQuestion("kp","Explain this synthetic market failure.",quote,rubric)
    private fun draft(points: Int=10, evidence: String=quote)=json("items" to JsonArray(listOf(
        json("id" to "R1","points" to points,"reason" to "Synthetic explanation","evidence" to evidence))),"total" to 10,"awarded" to points).toString()

    private fun LedgerStore.poseAndSubmit(attempt: String="a", numeric: Boolean=false) {
        val base=question.copy(stem=question.stem+" "+attempt)
        val q=if(numeric) base.copy(stem=base.stem+" Calculate the result.",rubric=rubric+RubricItem("N","Numeric final",2.0,"Synthetic basis")) else base
        val value=if(numeric) JsonObject(storedConstructed(q)+("numeric_final" to json("rubricItemId" to "N","expected" to "0.25"))) else storedConstructed(q)
        val input=json("course" to "c","kp" to "kp","generator" to BridgeClient.CLAUDE)
        val id=enqueueJob("review_constructed",input.toString(),"pack")
        check(storeConstructed(claimJob(id)!!,SupplyJob(id,"review_constructed",input,"pack"),value,GradeGate.rubricFingerprint(q.rubric),BridgeClient.CODEX))
        val card=takeConstructed("c","kp",attempt)
        assertFalse("rubric" in card.payload || "reference_answer" in card.payload)
        val bytes=LedgerJson.encodeToString(CommandBody("submit_answer","c",1793188800000,
            json("attemptId" to attempt,"answer" to answer,"finalAnswer" to "0.25","posedSnapshot" to card.payload)))
        assertEquals("OK",command(WireCommand("phone",attempt,bytes,sha256(bytes.toByteArray()))).status)
    }

    @Test fun draftSurvivesRestartOutsideAllWindowsThenConfirmUnlocksEvidence() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("constructed-grade")
        var actor=LedgerActor(this,LedgerStore(dir))
        try { StubBridge().use { bridge ->
            actor.execute { poseAndSubmit() }
            bridge.enqueue(200,StubBridge.completion(draft()))
            assertTrue(ConstructedGradeWorker(actor,BridgeClient(stubConfig(bridge))).runOne())
            val m=actor.execute { snapshot("c").projection.mastery["kp"] ?: Mastery() }
            assertTrue(m.window.isEmpty());assertEquals(0,m.solidProgress.count)
            assertEquals("AI_UNREVIEWED",actor.execute { events("c").single { it.type=="attempt_graded" }.payload.str("status") })
            actor.close();actor=LedgerActor(this,LedgerStore(dir))
            bridge.enqueue(200,StubBridge.completion("""{"verdict":"CONFIRM","note":"Agreed"}"""))
            val notified=mutableListOf<String>()
            val worker=ConstructedGradeWorker(actor,BridgeClient(stubConfig(bridge))) { notified.add(it) }
            assertTrue(worker.runOne());assertFalse(worker.runOne())
            assertEquals(listOf("c"),notified)
            val record=actor.execute { constructedRecord("c","a")!! }
            val facts=record.getValue("grades").jsonArray
            assertEquals(2,facts.size)
            assertEquals(BridgeClient.CLAUDE,facts[0].jsonObject.str("graderModel"))
            assertEquals(BridgeClient.CODEX,facts[1].jsonObject.str("reviewerModel"))
            val evidence=facts[0].jsonObject.getValue("evidence").jsonArray.single().jsonObject
            assertEquals(quote,answer.substring(evidence.num("start").toInt(),evidence.num("end").toInt()))
            assertEquals(listOf("a"),actor.execute { snapshot("c").projection.mastery.getValue("kp").window })
            assertEquals(2L,actor.execute { supplyCallCount() })
            assertTrue(bridge.requests.last().contains(answer))
            assertTrue(bridge.requests.last().contains("Frozen rubric"))
        } } finally { actor.close();dir.toFile().deleteRecursively() }
    }

    @Test fun reviseRequiresValidatedItemsAndNeverAcceptsFabricatedEvidence() = runBlocking<Unit> {
        for (mode in listOf("valid","forged","total_only","same_vendor","missing_identity")) {
            val dir=Files.createTempDirectory("revised-grade")
            val actor=LedgerActor(this,LedgerStore(dir))
            try { StubBridge().use { bridge ->
                actor.execute { poseAndSubmit() }
                bridge.enqueue(200,StubBridge.completion(draft()))
                val worker=ConstructedGradeWorker(actor,BridgeClient(stubConfig(bridge)))
                worker.runOne()
                val revision=if(mode=="forged") draft(5,"Words absent from the learner response.") else draft(5)
                val response=if(mode=="total_only") """{"verdict":"REVISE","revised_total":5}"""
                    else """{"verdict":"REVISE","revised_grade":$revision}"""
                bridge.enqueue(200,StubBridge.completion(response,when(mode) { "same_vendor" -> BridgeClient.CLAUDE; "missing_identity" -> ""; else -> "__MODEL__" }))
                worker.runOne()
                val es=actor.execute { events("c") }
                val review=es.filter { it.type=="grade_reviewed" }
                if(mode=="valid") {
                    assertEquals("REVISE",review.single().payload.str("decision"))
                    assertEquals(500,review.single().payload.getValue("revised").jsonObject.num("score"))
                } else {
                    assertTrue(review.isEmpty(),mode)
                    assertTrue(actor.execute { snapshot("c").projection.mastery["kp"]?.window.orEmpty().isEmpty() })
                }
            } } finally { actor.close();dir.toFile().deleteRecursively() }
        }
    }

    @Test fun retryScheduleIsDurableLimitedAndExpiresByWallClock() = runBlocking<Unit> {
        var now=1793188800000L
        val dir=Files.createTempDirectory("grade-retry")
        var actor=LedgerActor(this,LedgerStore(dir) { now })
        try { StubBridge().use { bridge ->
            actor.execute { poseAndSubmit() }
            bridge.enqueue(200,StubBridge.completion(draft()))
            ConstructedGradeWorker(actor,BridgeClient(stubConfig(bridge))).runOne()
            repeat(4) { i ->
                bridge.enqueue(503,"""{"error":"review temporarily unavailable"}""")
                assertTrue(ConstructedGradeWorker(actor,BridgeClient(stubConfig(bridge))).runOne())
                assertFalse(ConstructedGradeWorker(actor,BridgeClient(stubConfig(bridge))).runOne())
                actor.close();actor=LedgerActor(this,LedgerStore(dir) { now })
                now+=when(i) { 0 -> 60_000; 1 -> 3_600_000; else -> 21_600_000 }
            }
            assertFalse(ConstructedGradeWorker(actor,BridgeClient(stubConfig(bridge))).runOne())
            assertEquals(5,bridge.requests.size)
            assertTrue(actor.execute { events("c").none { it.type=="grade_reviewed" } })
            actor.execute { poseAndSubmit("expired") }
            bridge.enqueue(200,StubBridge.completion(draft()))
            ConstructedGradeWorker(actor,BridgeClient(stubConfig(bridge))).runOne()
            now+=86_400_000
            assertFalse(ConstructedGradeWorker(actor,BridgeClient(stubConfig(bridge))).runOne())
            assertEquals(6,bridge.requests.size)
        } } finally { actor.close();dir.toFile().deleteRecursively() }
    }

    @Test fun claudeRateLimitFallsBackWithNoticeAndRetainsCrossVendorReview() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("grade-fallback")
        val actor=LedgerActor(this,LedgerStore(dir))
        try { StubBridge().use { bridge ->
            actor.execute { poseAndSubmit() }
            bridge.enqueue(429,"""{"error":{"message":"429 Too many requests"}}""")
            bridge.enqueue(200,StubBridge.completion(draft()))
            bridge.enqueue(200,StubBridge.completion("""{"verdict":"CONFIRM"}"""))
            val worker=ConstructedGradeWorker(actor,BridgeClient(stubConfig(bridge)))
            worker.runOne();worker.runOne()
            val es=actor.execute { events("c") }
            val graded=es.single { it.type=="attempt_graded" }.payload
            assertEquals(BridgeClient.CODEX,graded.str("graderModel"))
            assertTrue(graded.str("notice").contains("限流"))
            assertEquals(BridgeClient.CLAUDE,es.single { it.type=="grade_reviewed" }.payload.str("reviewerModel"))
            assertEquals(3L,actor.execute { supplyCallCount() })
        } } finally { actor.close();dir.toFile().deleteRecursively() }
    }

    @Test fun lateWorkerCannotCommitAfterReclaimOrRestore() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("grade-fence")
        val store=LedgerStore(dir)
        try {
            store.poseAndSubmit()
            val old=store.claimGradeWork()!!
            store.recoverSuspension()
            val current=store.claimGradeWork()!!
            val payload=json("status" to "AI_UNREVIEWED","score" to 1000,"max" to 1000)
            assertFalse(store.persistGradeDraft(old,payload,BridgeClient.CLAUDE))
            assertTrue(store.persistGradeDraft(current,payload,BridgeClient.CLAUDE))
            val review=store.claimGradeWork()!!
            val backup=store.backup(dir.resolveSibling(dir.fileName.toString()+"-backup"))
            store.close()
            LedgerStore.restore(dir,backup).use { restored ->
                assertFalse(restored.persistGradeReview(review,json("decision" to "CONFIRM")))
                assertTrue(restored.events("c").none { it.type=="grade_reviewed" })
            }
            backup.toFile().deleteRecursively()
        } finally { runCatching { store.close() };dir.toFile().deleteRecursively() }
    }

    @Test fun numericFinalIsServerScoredAndIndependentOfRevisedProcess() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("grade-numeric")
        val actor=LedgerActor(this,LedgerStore(dir))
        try { StubBridge().use { bridge ->
            actor.execute { poseAndSubmit(numeric=true) }
            bridge.enqueue(200,StubBridge.completion(draft()))
            bridge.enqueue(200,StubBridge.completion("""{"verdict":"REVISE","revised_grade":${draft(0,"")}}"""))
            val worker=ConstructedGradeWorker(actor,BridgeClient(stubConfig(bridge)))
            worker.runOne();worker.runOne()
            val review=actor.execute { events("c").single { it.type=="grade_reviewed" }.payload }
            val final=review.getValue("revised").jsonObject
            assertEquals(200,final.num("score"));assertEquals(1200,final.num("max"))
            assertTrue(final.getValue("numericFinal").jsonObject.flag("correct"))
            val numericEvidence=final.getValue("evidence").jsonArray.single().jsonObject
            assertEquals("finalAnswer",numericEvidence.str("field"))
            assertEquals("0.25",numericEvidence.str("text"))
            assertEquals("0.25",actor.execute { constructedRecord("c","a")!!.getValue("answer").jsonObject.str("finalAnswer") })
        } } finally { actor.close();dir.toFile().deleteRecursively() }
    }
}
