package com.coach.domain

import kotlin.test.*
import kotlin.random.Random
import kotlinx.serialization.json.*

class MasteryInvariantPropertyTest {
    private val DAY=86400000L
    @Test fun invariants() {
        val random=Random(7)
        val validityEvents=setOf("attempt_voided","question_key_changed","grade_reviewed","override_cleared")
        var revokeViolations=0; var hintSolid=0; var assistedSolid=0; var reviewRevoke=0; var solidWithoutPassed=0; var checked=0
        repeat(3000) { case ->
            val ev=mutableListOf<Event>(); var at=1793188800000L
            val hinted=mutableSetOf<String>(); val assistedAttempts=mutableSetOf<String>()
            fun e(type: String, p: JsonObject, actor: String="SYSTEM") { val s=ev.size+1L; ev.add(Event(s,"e$s","course",type,recordedAt=at+1000,occurredAt=at,deviceId="phone",actor=actor,idemKey="i$s",payload=p)) }
            repeat(random.nextInt(2,12)) { i ->
                at+=random.nextLong(0,2*DAY)
                val id="$i"; val review=random.nextInt(4)==0
                val hint=random.nextInt(4)==0; val assisted=random.nextInt(4)==0
                val purpose=listOf("PRACTICE","CLOSED_BOOK","TIMED","UNSEEN_VARIANT","RECALL").random(random)
                val format=listOf("SINGLE_CHOICE","SHORT_ANSWER").random(random)
                e("question_posed",json("attemptId" to id,"questionId" to id,"kpIds" to listOf("kp"),"fingerprint" to id,"keyState" to "VERIFIED","origin" to "COACH","purpose" to purpose,"format" to format,"policyVersion" to 1,"review" to review))
                if(hint) { e("hint_revealed",json("attemptId" to id)); hinted.add(id) }
                if(assisted) { e("followup_asked",json("pendingAttemptId" to id),"LEARNER"); assistedAttempts.add(id) }
                e("answer_submitted",json("attemptId" to id,"answer" to "x"),"LEARNER")
                e("attempt_graded",json("attemptId" to id,"score" to random.nextInt(0,11),"max" to 10,"status" to if(random.nextInt(5)==0) "AI_UNREVIEWED" else "EXACT"))
                if(random.nextInt(6)==0) e("grade_reviewed",json("attemptId" to "${random.nextInt(i+1)}","decision" to "CONFIRM"))
                if(random.nextInt(10)==0) e("attempt_voided",json("attemptId" to "${random.nextInt(i+1)}","reason" to "X"))
                if(random.nextInt(6)==0) e("review_completed",json("kpId" to "kp","result" to listOf("PASS","FAIL","NEEDS_WORK").random(random)))
            }
            var prev: Mastery?=null
            for(k in 1..ev.size) {
                val m=project(ev.take(k)).mastery["kp"] ?: Mastery()
                val last=ev[k-1]
                if(prev?.passed==true && !m.passed) {
                    val regrade=last.type=="attempt_graded" && ev.take(k-1).any { it.type=="attempt_graded" && it.payload.str("attemptId")==last.payload.str("attemptId") }
                    if(last.type !in validityEvents && !regrade) revokeViolations++
                    if(last.type=="review_completed") reviewRevoke++
                }
                if(m.solidLatch.any { it in hinted }) hintSolid++
                if(m.solidLatch.any { it in assistedAttempts }) assistedSolid++
                if(m.solid && !m.passed) solidWithoutPassed++
                checked++
                prev=m
            }
        }
        println("INVARIANTS cases=3000 revokeOutsideValidityEvents=$revokeViolations reviewRevoke=$reviewRevoke hintedInSolidLatch=$hintSolid assistedInSolidLatch=$assistedSolid solidWithoutPassed=$solidWithoutPassed checkedPrefixes=$checked")
        assertEquals(0,revokeViolations);assertEquals(0,reviewRevoke);assertEquals(0,hintSolid);assertEquals(0,assistedSolid);assertEquals(0,solidWithoutPassed)
    }
}
