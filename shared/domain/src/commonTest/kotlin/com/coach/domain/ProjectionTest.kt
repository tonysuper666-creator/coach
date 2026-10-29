package com.coach.domain

import kotlin.test.*
import kotlin.random.Random
import kotlinx.serialization.json.*

private const val DAY = 86400000L
private const val START = 1793188800000L // synthetic noon UTC
private class Script {
    val events = mutableListOf<Event>()
    var at = START
    fun e(type: String, p: JsonObject, actor: String = "SYSTEM", device: String = "phone") {
        val seq = events.size.toLong()+1
        events.add(Event(seq,"e$seq","course",type,recordedAt=at+1000,occurredAt=at,deviceId=device,actor=actor,idemKey="i$seq",payload=p))
    }
    fun answer(id: String, score: Int = 10, purpose: String = "PRACTICE", format: String = "SINGLE_CHOICE",
        hints: Boolean = false, assisted: Boolean = false, status: String = "EXACT", question: String = id,
        fingerprint: String = question, origin: String = "COACH", keyState: String = "VERIFIED",
        review: Boolean = false, offline: Boolean = false, kp: String = "kp") {
        e("question_posed",json("attemptId" to id,"questionId" to question,"kpIds" to listOf(kp),"fingerprint" to fingerprint,"keyState" to keyState,"origin" to origin,"purpose" to purpose,"format" to format,"policyVersion" to 1,"review" to review))
        if(hints) e("hint_revealed",json("attemptId" to id,"cause" to "LEAK"))
        if(assisted) e("followup_asked",json("pendingAttemptId" to id),"LEARNER","mac")
        e("answer_submitted",json("attemptId" to id,"answer" to "synthetic answer","offline" to offline),"LEARNER")
        e("attempt_graded",json("attemptId" to id,"score" to score,"max" to 10,"status" to status))
    }
    fun state() = project(events)
    fun mastery() = state().mastery["kp"] ?: Mastery()
}

class ProjectionTest {
    @Test fun threeCorrectLatchAndSubsequentFailureRepairs() {
        val s=Script();s.answer("1");s.answer("2");assertFalse(s.mastery().passed)
        s.answer("3");assertTrue(s.mastery().passed);val seq=s.mastery().passedAtSeq
        s.answer("4",score=0);assertTrue(s.mastery().passed);assertTrue(s.mastery().needsRepair);assertEquals(seq,s.mastery().passedAtSeq)
        assertEquals(1,s.state().review.size)
    }
    @Test fun latestThreeIsTheCanonicalWindow() {
        val s=Script();s.answer("wrong",score=0);s.answer("1");s.answer("2");assertFalse(s.mastery().passed)
        s.answer("3");assertTrue(s.mastery().passed)
    }
    @Test fun hintsExcludedAndAssistedOnlyBlocksSolidAcrossDevices() {
        val s=Script();s.answer("hint",hints=true);s.answer("1");s.answer("2");assertFalse(s.mastery().passed)
        s.answer("3",assisted=true);assertTrue(s.mastery().passed)
        val a=Script();a.answer("a",format="SHORT_ANSWER",purpose="CLOSED_BOOK",assisted=true)
        a.at+=DAY;a.answer("b",format="SHORT_ANSWER",purpose="CLOSED_BOOK");assertFalse(a.mastery().solid)
        a.at+=DAY;a.answer("c",format="SHORT_ANSWER",purpose="CLOSED_BOOK");assertTrue(a.mastery().solid)
    }
    @Test fun strictSolidDaysFormatsOfflineAndAverage() {
        val s=Script();s.answer("a",score=9,format="SHORT_ANSWER",purpose="CLOSED_BOOK")
        s.at+=DAY;s.answer("b",score=9,purpose="TIMED");assertTrue(s.mastery().solid);assertTrue(s.mastery().passed)
        val same=Script();same.answer("1",format="SHORT_ANSWER",purpose="CLOSED_BOOK");same.answer("2",format="SHORT_ANSWER",purpose="CLOSED_BOOK");assertFalse(same.mastery().solid)
        val offline=Script();offline.answer("1",format="SHORT_ANSWER",purpose="CLOSED_BOOK");offline.at+=DAY;offline.answer("2",purpose="TIMED",offline=true);assertFalse(offline.mastery().solid)
        val ordinary=Script();ordinary.answer("1",format="SHORT_ANSWER",purpose="CLOSED_BOOK");ordinary.at+=DAY;ordinary.answer("2");assertFalse(ordinary.mastery().solid)
        val low=Script();low.answer("1",score=8,format="SHORT_ANSWER",purpose="CLOSED_BOOK");low.at+=DAY;low.answer("2",score=8,format="SHORT_ANSWER",purpose="CLOSED_BOOK");assertFalse(low.mastery().solid)
        s.at+=DAY;s.answer("fail",score=0,purpose="CLOSED_BOOK",format="SHORT_ANSWER");assertFalse(s.mastery().solid);assertTrue(s.mastery().formerlySolid);assertTrue(s.mastery().passed)
    }
    @Test fun reviewFailureKeepsPassedAndOnlyFirstDailyAnswerSchedules() {
        val s=Script();repeat(3) { s.answer("$it") };s.at+=DAY
        s.answer("r1",score=0,purpose="RECALL",review=true)
        val r=s.state().review.getValue("kp");assertEquals("FAIL",r.lastResult);assertEquals(1,r.lapses);assertTrue(s.mastery().passed)
        s.answer("r2",purpose="RECALL",review=true);assertEquals(r,s.state().review.getValue("kp"))
    }
    @Test fun unreviewedLegacyAndRepeatedAnswersCannotSupplyEvidence() {
        val s=Script();repeat(3) { s.answer("$it",status="AI_UNREVIEWED") };assertFalse(s.mastery().passed)
        for(i in 0..2) s.e("grade_reviewed",json("attemptId" to "$i","decision" to "CONFIRM"))
        assertTrue(s.mastery().passed);assertEquals(s.events.last().seq,s.mastery().passedAtSeq)
        val legacy=Script();repeat(3) { legacy.answer("$it",origin="LEGACY",keyState="UNREVIEWED") }
        repeat(3) { legacy.e("question_key_changed",json("questionId" to "$it","keyState" to "VERIFIED")) }
        assertFalse(legacy.mastery().passed)
        legacy.e("legacy_passed",json("kpId" to "kp","sourceRef" to "synthetic"));assertTrue(legacy.mastery().passed);assertFalse(legacy.mastery().solid)
        val repeated=Script();repeat(4) { repeated.answer("a$it",question="same") };assertFalse(repeated.mastery().passed)
        val sameFp=Script();repeat(4) { sameFp.answer("$it",fingerprint="same") };assertFalse(sameFp.mastery().passed)
    }
    @Test fun voidAndRejectedKeysRevokeAtTheirOwnEventAndExplainWhy() {
        val s=Script();repeat(3) { s.answer("$it") };val before=s.mastery().passedAtSeq
        s.e("attempt_voided",json("attemptId" to "1","reason" to "DISPUTE_UPHELD"));assertFalse(s.mastery().passed)
        assertEquals(s.events.last().seq,s.mastery().revokedAtSeq);assertContains(s.mastery().revokedReason!!,"DISPUTE_UPHELD")
        assertTrue(project(s.events.dropLast(1)).mastery.getValue("kp").passed);assertNotNull(before)
        val k=Script();repeat(3) { k.answer("$it") };k.e("question_key_changed",json("questionId" to "0","keyState" to "REJECTED","reason" to "KEY_WRONG"));assertFalse(k.mastery().passed)
        val revised=Script();repeat(3) { revised.answer("$it") }
        revised.e("grade_reviewed",json("attemptId" to "1","decision" to "REVISE","revised" to json("score" to 0,"max" to 10)))
        assertFalse(revised.mastery().passed)
    }
    @Test fun voidReleasesFirstnessWithoutChangingPastConclusions() {
        val s=Script();s.answer("invalid",question="q",score=0)
        s.at+=15*DAY;s.answer("replacement",question="q")
        s.answer("other1");s.answer("other2");assertFalse(s.mastery().passed)
        s.e("attempt_voided",json("attemptId" to "invalid","reason" to "DISPUTE_UPHELD"))
        assertTrue(s.mastery().passed);assertEquals(s.events.last().seq,s.mastery().passedAtSeq)
        assertFalse(project(s.events.dropLast(1)).mastery.getValue("kp").passed)
    }
    @Test fun manualAndLegacyNeverCreateSolidAndExamCompresses() {
        val s=Script();s.e("kp_overridden",json("kpId" to "kp","kind" to "MARK_PASSED"));assertTrue(s.mastery().passed);assertFalse(s.mastery().solid)
        val day=logicalDay(s.at,"Europe/London");s.e("exam_set",json("examDay" to day+3));s.e("review_completed",json("kpId" to "kp","result" to "PASS"));assertTrue(s.state().review.getValue("kp").dueDay<=day+2)
        s.e("exam_set",json("examDay" to day+1));assertTrue(s.state().review.getValue("kp").dueDay<=day)
        s.e("exam_weights_set",json("weights" to json("kp" to 25,"other" to 75)));assertEquals(Coverage(25,0,100),s.state().coverage)
    }
    @Test fun positionSoftPrereqsAndOfflinePackNeverHijacksContinue() {
        val s=Script();s.e("outline_revised",json("ops" to JsonArray(listOf(json("kpId" to "kp"),json("kpId" to "next","prereqs" to listOf("kp"))))))
        s.e("kp_opened",json("kpId" to "kp","via" to "CONTINUE"));s.e("question_posed",json("attemptId" to "review","questionId" to "q","kpIds" to listOf("next"),"review" to true,"purpose" to "RECALL","offlinePack" to true))
        assertEquals("kp",s.state().position.continueTarget)
        repeat(3) { s.answer("$it") };assertEquals("next",s.state().position.continueTarget)
    }
    @Test fun londonDstUsesCalendarBeforeFourAmBoundary() {
        // 2026-10-25 03:30 UTC is 03:30 local after the autumn clock change.
        val epoch=1792899000000L
        assertEquals(logicalDay(epoch-86400000,"Europe/London"),logicalDay(epoch,"Europe/London"))
        assertEquals(logicalDay(epoch,"Europe/London")+1,logicalDay(epoch+1800000,"Europe/London"))
    }
    @Test fun unknownEventsAndUpcastChainsRetainRawFacts() {
        val s=Script();s.e("future_event",json("keep" to "raw"));assertTrue(s.state().requiresServerSnapshot)
        val raw=s.events.single().copy(type="kp_opened",payload=json("oldKp" to "k"))
        val up=Upcasters(mapOf("kp_opened" to listOf({ p -> json("kpId" to p.str("oldKp")) })))
        assertEquals("k",project(listOf(raw),upcasters=up).position.current);assertEquals(1,raw.schema)
    }
    @Test fun tenThousandRandomArrivalOrdersDuplicatesAndLateReviewsAreByteIdentical() {
        val random=Random(1042)
        repeat(10000) { case ->
            val s=Script()
            repeat(random.nextInt(1,9)) { i ->
                s.at += random.nextLong(0,3*DAY)
                s.answer("$i",score=random.nextInt(0,11),purpose=if(random.nextBoolean()) "CLOSED_BOOK" else "PRACTICE",format=if(random.nextBoolean()) "SHORT_ANSWER" else "SINGLE_CHOICE",hints=random.nextInt(5)==0,assisted=random.nextInt(4)==0,status=if(random.nextBoolean()) "EXACT" else "AI_UNREVIEWED")
                if(random.nextInt(3)==0) s.e("grade_reviewed",json("attemptId" to "${random.nextInt(i+1)}","decision" to "CONFIRM"))
                if(random.nextInt(8)==0) s.e("attempt_voided",json("attemptId" to "${random.nextInt(i+1)}","reason" to "DISPUTE_UPHELD"))
            }
            val expected=s.state();val delivered=(s.events+s.events.filter { random.nextBoolean() }).shuffled(random)
            val actual=project(delivered)
            assertEquals(expected.bytes(),actual.bytes(),"case=$case")
            assertTrue(actual.mastery.values.all { !it.solid || it.passed })
            val cut=random.nextInt(s.events.size+1)
            val batches=s.events.take(cut)+s.events.drop(cut).shuffled(random)
            assertEquals(expected.bytes(),project(batches).bytes())
        }
        println("PROPERTY cases=10000 permutation+duplicate+late-review byte-identical")
    }
}
