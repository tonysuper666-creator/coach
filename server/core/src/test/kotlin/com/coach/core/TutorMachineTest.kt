package com.coach.core

import com.coach.domain.*
import com.coach.store.LedgerStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

internal class TutorFixture : AutoCloseable {
    val directory = Files.createTempDirectory("coach-tutor-")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val actor = LedgerActor(scope,LedgerStore(directory))
    val machine = TutorMachine(actor)
    suspend fun seed(kp: String = "kp-1", count: Int = 9) = actor.execute {
        repeat(count) { i ->
            val id = "$kp-q$i"
            val options = listOf("opportunity cost $i","sunk expenditure $i","fixed payment $i","total revenue $i").mapIndexed { j,text -> Option("$id-$j",text) }
            registerQuestion(id,"course","", "SINGLE_CHOICE","Synthetic problem $i for $kp", "VERIFIED",options.first().optionId,options,listOf(kp))
        }
    }
    suspend fun open(thread: String = "thread", kp: String = "kp-1", concepts: Set<String> = setOf("cost")) =
        machine.open("course",thread,kp,concepts,"Synthetic learning objective")
    suspend fun teach(thread: String = "thread"): TutorReplyPlan {
        val plan = assertNotNull(machine.next("course",thread).plan)
        machine.finish(plan,"explanation-${plan.turnId}",true)
        return plan
    }
    suspend fun events() = actor.execute { events("course") }
    suspend fun state() = actor.execute { snapshot("course").projection }
    suspend fun answers(cards: List<Event>, wrong: Set<Int> = emptySet()): Map<String,String> = actor.execute {
        cards.mapIndexed { index, card ->
            val key = tutorChoiceKey(card.payload.str("questionId"))
            val map = card.payload.getValue("letterMap").jsonObject
            val correct = map.getValue(key.correctOptionId).jsonPrimitive.content
            card.payload.str("attemptId") to if (index in wrong) map.values.map { it.jsonPrimitive.content }.first { it != correct } else correct
        }.toMap()
    }
    suspend fun submit(cards: List<Event>, wrong: Set<Int> = emptySet(), id: String = "submission") =
        machine.submit("course","thread",cards.first().payload.str("groupId"),answers(cards,wrong),"desktop",id)
    override fun close() {
        runBlocking { actor.close() }
        scope.cancel()
        directory.toFile().deleteRecursively()
    }
}

class TutorMachineTest {
    @Test fun gradedMistakesUseReviewedExplanationsAndNeverLeakPendingKeys() = runBlocking {
        TutorFixture().use { f ->
            f.actor.execute {
                val id = enqueueSupply("course","kp-1","Objective","Synthetic source")
                val job = supplyJobs().single { it.id == id }
                val options = listOf(Option("right","Opportunity cost is the forgone alternative"),Option("wrong","Sunk cost is recoverable"),Option("other","Total revenue"),Option("last","Fixed payment"))
                storeVerifiedBatch(claimJob(id)!!,job,listOf(com.coach.store.VerifiedPoolQuestion(
                    "Which cost describes the forgone alternative?",options,"right",
                    "Opportunity cost is the best forgone alternative; sunk costs cannot be recovered.","Synthetic source","claude","gpt")))
            }
            f.open(); f.teach(); f.machine.settings("course","thread",1)
            val cards=f.machine.next("course","thread").cards
            assertFalse(cards.single().payload.toString().contains("forgone alternative;"))
            val pending=f.machine.followup("course","thread")
            assertTrue(pending.mistakes.isEmpty())
            f.machine.finish(pending,"hint",true)
            val graded=f.submit(cards,setOf(0))
            val feedback=graded.grades.single().payload.str("feedback")
            assertTrue(feedback.contains("Sunk cost is recoverable"))
            assertTrue(feedback.contains("sunk costs cannot be recovered"))
            val plan=assertNotNull(f.machine.explain("course","thread",TeachingMode.EXAMPLE).plan)
            val mistake=plan.mistakes.single()
            assertEquals("Sunk cost is recoverable",mistake.selectedText)
            assertEquals("Opportunity cost is the forgone alternative",mistake.correctText)
            assertTrue(plan.instruction().contains("First paragraph"))
            assertTrue(plan.instruction().contains(mistake.explanation))
            f.machine.finish(plan,"recap",true)
            f.seed("kp-2"); f.open("other","kp-2")
            assertTrue(assertNotNull(f.machine.next("course","other").plan).mistakes.isEmpty())
        }
    }

    @Test fun missingExplanationIsAdmittedAndNewCorrectGroupClearsOldMistakes() = runBlocking {
        TutorFixture().use { f ->
            f.seed();f.open();f.teach()
            val cards=f.machine.next("course","thread").cards
            val grades=f.submit(cards,setOf(0,1))
            assertTrue(grades.grades.first().payload.str("feedback").contains("暂无已复核解析"))
            val plan=assertNotNull(f.machine.next("course","thread",allowExample=true).plan)
            assertEquals(2,plan.mistakes.size)
            assertTrue(plan.mistakes.all { it.explanation.isEmpty() })
            f.machine.finish(plan,"recap",true)
            f.submit(f.machine.next("course","thread").cards,id="correct-group")
            assertTrue(assertNotNull(f.machine.explain("course","thread",TeachingMode.EXAMPLE).plan).mistakes.isEmpty())
        }
    }

    @Test fun reopeningPersistedKpThreadDoesNotCreateAnotherBinding() = runBlocking {
        TutorFixture().use { f ->
            f.seed()
            val first = f.open(thread = "persisted-thread-for-kp-1")
            val eventsAfterOpen = f.events()
            val reopened = f.open(thread = "persisted-thread-for-kp-1")
            assertEquals(first.state, reopened.state)
            assertEquals(eventsAfterOpen, f.events())
            assertEquals(1, f.events().count { it.type == "kp_opened" })
        }
    }

    @Test fun continueAfterTwoErrorsUsesTheRemainingPoolWithoutRepeatingTheLesson() = runBlocking {
        TutorFixture().use { f ->
            f.seed(); f.open(); f.teach()
            f.submit(f.machine.next("course","thread").cards)
            assertEquals("换个例子再讲",f.submit(f.machine.next("course","thread").cards,setOf(0,1),"second").nextLabel)
            val after = f.events().last().seq
            val next = f.machine.next("course","thread")
            assertNull(next.plan)
            assertEquals(3,next.cards.size)
            assertEquals(TutorState.AWAIT_ANSWER,next.state)
            assertTrue(f.events().filter { it.seq > after }.none {
                it.type == "tutor_state_changed" && it.payload.str("state") in setOf("PREPARE","TEACH")
            })
        }
    }

    @Test fun taughtContinueProducesCardsWithoutAnExplanationCall() = runBlocking {
        TutorFixture().use { f ->
            f.seed(); f.open(); f.teach()
            val after = f.events().last().seq
            val next = f.machine.next("course","thread")
            assertNull(next.plan)
            assertEquals(3,next.cards.size)
            assertEquals(TutorState.AWAIT_ANSWER,next.state)
            assertTrue(f.events().filter { it.seq > after }.none { it.type == "explanation_delivered" })
            assertEquals(next.cards,f.machine.next("course","thread").cards)
        }
    }

    @Test fun teachesBeforeTestingAndDirectTestMarksUntaught() = runBlocking {
        TutorFixture().use { f ->
            f.seed(); f.open()
            val lesson = f.machine.next("course","thread")
            assertNotNull(lesson.plan)
            assertTrue(lesson.cards.isEmpty())
            f.machine.finish(lesson.plan,"cancelled",false)
            assertTrue(f.state().explainedKpIds.isEmpty())
            val cards = f.machine.next("course","thread",testUntaught=true).cards
            assertEquals(3,cards.size)
            assertTrue(cards.all { it.payload.flag("untaught") })
        }
    }

    @Test fun overlappingConceptsAreOnlyRecappedIncludingAcrossThreads() = runBlocking {
        TutorFixture().use { f ->
            f.seed(); f.seed("kp-2"); f.open(concepts=setOf("cost","benefit")); f.teach()
            f.open("thread-2","kp-2",setOf("cost","risk"))
            val lesson = assertNotNull(f.machine.next("course","thread-2").plan)
            assertEquals(setOf("risk"),lesson.conceptIds)
            assertEquals(setOf("cost"),lesson.recapConceptIds)
            f.machine.finish(lesson,"new-lesson",true)
            val recap = assertNotNull(f.machine.explain("course","thread",TeachingMode.FORMAL).plan)
            assertTrue(recap.conceptIds.isEmpty())
            assertEquals(setOf("cost","benefit"),recap.recapConceptIds)
            assertFalse(recap.firstExplanation)
            f.machine.finish(recap,"recap",true)
            val full = f.events().filter { it.type == "explanation_delivered" }.flatMap { e -> e.payload.strings("conceptIds").map { e.threadId to it } }
            assertEquals(full.distinct(),full)
            assertEquals(setOf("cost","benefit","risk"),f.state().taughtConceptIds)
        }
    }

    @Test fun correctTextLeaksEvenWithoutLettersAndStrippingNeverRestoresEligibility() = runBlocking {
        for (withLetter in listOf(false,true)) TutorFixture().use { f ->
            f.seed(); f.open(); f.teach()
            val cards = f.machine.next("course","thread").cards
            val target = cards.first().payload.str("attemptId")
            val plan = f.machine.followup("course","thread",target)
            val option = plan.pending.single().key.let { k -> k.options.single { it.optionId == k.correctOptionId }.text }
            val guard = TutorOutputGuard(plan.pending) { attempt -> runBlocking { f.machine.leak(plan,attempt) } }
            val raw = if (withLetter) "选 B，$option。" else "$option。"
            val visible = raw.chunked(2).joinToString("") { guard.push(it) } + guard.finish()
            assertEquals(withLetter,guard.letterReferenceStripped)
            if (withLetter) assertEquals("",visible)
            val leak = f.events().single { it.type == "hint_revealed" }
            assertEquals("LEAK",leak.payload.str("cause"))
            assertEquals(target,leak.payload.str("attemptId"))
            assertFailsWith<IllegalArgumentException> { f.submit(cards) }
            f.machine.finish(plan,"followup",true)
            f.submit(cards)
            val mastery = assertNotNull(f.state().mastery["kp-1"])
            assertEquals(2,mastery.window.size)
            assertFalse(target in mastery.window)
            assertFalse(mastery.passed)
        }
    }

    @Test fun groupSubmissionIsAtomicIndependentAndFeedbackUsesTheFrozenMap() = runBlocking {
        TutorFixture().use { f ->
            f.seed(); f.open(); f.teach()
            for (size in listOf(-1,0,2,4,10)) assertFailsWith<IllegalArgumentException> { f.machine.settings("course","thread",size) }
            f.machine.settings("course","thread",1)
            f.machine.settings("course","thread",3)
            val cards = f.machine.next("course","thread").cards
            val answers = f.answers(cards,setOf(1))
            assertFailsWith<IllegalArgumentException> { f.machine.submit("course","thread",cards.first().payload.str("groupId"),answers.entries.take(2).associate { it.toPair() },"desktop","incomplete") }
            assertTrue(f.events().none { it.type == "answer_submitted" })
            val result = f.submit(cards,setOf(1))
            assertEquals(listOf(1L,0L,1L),result.grades.map { it.payload.num("score") })
            result.grades.zip(cards).forEach { (grade,card) ->
                val correctLetter = f.actor.execute { card.payload.getValue("letterMap").jsonObject.getValue(tutorChoiceKey(card.payload.str("questionId")).correctOptionId).jsonPrimitive.content }
                assertTrue(grade.payload.str("feedback").contains(correctLetter))
            }
            assertEquals(cards.map { it.payload.str("attemptId") },f.state().mastery.getValue("kp-1").window)
            assertEquals(result.grades,f.submit(cards,setOf(1)).grades)
            assertEquals(3,f.events().count { it.type == "answer_submitted" })
        }
    }

    @Test fun threeCorrectChoicesPassAndTwoWrongAnswersOfferAnExampleRecap() = runBlocking {
        TutorFixture().use { f ->
            f.seed(); f.open(); f.teach()
            val cards = f.machine.next("course","thread").cards
            f.submit(cards)
            assertTrue(f.state().mastery.getValue("kp-1").passed)
            val second = f.machine.next("course","thread").cards
            assertEquals("换个例子再讲",f.submit(second,setOf(0,1),"second").nextLabel)
            val example = assertNotNull(f.machine.next("course","thread",allowExample=true).plan)
            assertEquals(TeachingMode.EXAMPLE,example.mode)
            assertTrue(example.conceptIds.isEmpty())
            f.machine.finish(example,"example",true)
            assertNull(f.machine.next("course","thread").plan)
        }
    }

    @Test fun legacySingleAnswerCommandsCannotBypassAGroupOrAnActiveFollowup() = runBlocking {
        TutorFixture().use { f ->
            f.seed(); f.open(); f.teach()
            val cards = f.machine.next("course","thread").cards
            val card = cards.first()
            val answer = f.answers(cards).getValue(card.payload.str("attemptId"))
            suspend fun direct(id: String) = f.actor.execute {
                val bytes = LedgerJson.encodeToString(com.coach.protocol.CommandBody.serializer(),com.coach.protocol.CommandBody(
                    "submit_answer","course",System.currentTimeMillis(),json("attemptId" to card.payload.str("attemptId"),"answer" to answer,"posedSnapshot" to card.payload)))
                command(com.coach.protocol.WireCommand("desktop",id,bytes,com.coach.store.sha256(bytes.toByteArray())))
            }
            assertEquals("GROUP_SUBMISSION_REQUIRED",direct("raw-group").error)
            val reply = f.machine.followup("course","thread")
            assertEquals("TUTOR_BUSY",direct("raw-busy").error)
            assertTrue(f.events().none { it.type == "answer_submitted" })
            f.machine.finish(reply,"reply",true)
            assertEquals(3,f.submit(cards).grades.size)
        }
    }

    @Test fun singleQuestionSettingProducesASingletonAndRetainsIndependentGrading() = runBlocking {
        TutorFixture().use { f ->
            f.seed(); f.open(); f.teach()
            f.machine.settings("course","thread",1)
            val cards = f.machine.next("course","thread").cards
            assertEquals(1,cards.size)
            assertEquals(1,f.submit(cards).grades.size)
            assertEquals(listOf(cards.single().payload.str("attemptId")),f.state().mastery.getValue("kp-1").window)
        }
    }

    @Test fun restartRecoversInterruptedTeachingWithoutPretendingItWasDelivered() = runBlocking {
        TutorFixture().use { f ->
            f.open()
            val old = assertNotNull(f.machine.next("course","thread").plan)
            val recovered = TutorMachine(f.actor)
            recovered.recoverInterruptedReplies()
            assertTrue(f.state().taughtConceptIds.isEmpty())
            val fresh = assertNotNull(recovered.next("course","thread").plan)
            recovered.finish(old,"stale",true)
            assertTrue(f.state().taughtConceptIds.isEmpty())
            recovered.finish(fresh,"fresh",true)
            assertEquals(setOf("cost"),f.state().taughtConceptIds)
        }
    }

    @Test fun emptyPoolShowsProgressAndDoesNotCallATeacherOrInventACard() = runBlocking {
        TutorFixture().use { f ->
            f.open(); f.teach()
            val next = f.machine.next("course","thread")
            assertNull(next.plan)
            assertTrue(next.waitingForPool)
            assertTrue(next.cards.isEmpty())
        }
    }
}
