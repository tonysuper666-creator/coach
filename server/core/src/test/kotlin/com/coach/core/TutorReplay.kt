package com.coach.core

import com.coach.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

private data class ReplayQuestion(val stem: String, val options: List<Option>, val correct: String)

/** Private inputs never leave this process. Only aggregate counts are printed. No CLI calls. */
fun main() = runBlocking {
    val directory = Path.of(System.getenv("COACH_REPLAY_DIR") ?: "${System.getProperty("user.home")}/Coach/corpus/replay")
    val rows = Files.readAllLines(directory.resolve("replay-input.jsonl")).filter { it.isNotBlank() }.map { LedgerJson.parseToJsonElement(it).jsonObject }
    val questions = linkedMapOf<String,ReplayQuestion>()
    Files.lines(directory.resolve("sessions.jsonl")).use { lines -> lines.forEach { line ->
        val session = LedgerJson.parseToJsonElement(line).jsonObject
        for (message in session.getValue("messages").jsonArray) {
            val metadata = LedgerJson.parseToJsonElement(message.jsonObject.str("metadata_json","{}")).jsonObject
            val turns = (metadata["model_turn"] as? JsonObject)?.get("messages") as? JsonArray ?: continue
            for (turn in turns) for (call in (turn.jsonObject["tool_calls"] as? JsonArray).orEmpty()) {
                val function = call.jsonObject["function"]?.jsonObject ?: continue
                if (function.str("name") != "mastery_quiz") continue
                val args = runCatching { LedgerJson.parseToJsonElement(function.str("arguments")).jsonObject }.getOrNull() ?: continue
                for (item in (args["questions"] as? JsonArray ?: JsonArray(listOf(args)))) {
                    val q = item.jsonObject
                    val options = (q["options"] as? JsonArray)?.mapIndexed { i,opt -> Option("option-$i",opt.jsonObject.str("body")) } ?: continue
                    if (options.size != 4 || options.any { it.text.isBlank() }) continue
                    val correct = q.str("expected_answer")
                    val index = q.getValue("options").jsonArray.indexOfFirst { it.jsonObject.str("label").trim().uppercase() == correct.trim().uppercase() }
                    if (index < 0) continue
                    val stem = q.str("question")
                    questions.putIfAbsent(questionFingerprint(stem,options),ReplayQuestion(stem,options,options[index].optionId))
                }
            }
        }
    } }
    require(questions.size >= 3) { "Replay needs at least three usable private choices" }
    val real = questions.values.toList()
    suspend fun TutorFixture.seedReal(selected: List<ReplayQuestion>) = actor.execute {
        selected.forEachIndexed { i,q -> registerQuestion("private-$i","course","","SINGLE_CHOICE",q.stem,"VERIFIED",q.correct,q.options,listOf("kp-1")) }
    }
    var continues = 0
    var falsePositives = 0
    var missed = 0
    for (row in rows) {
        val actual = tutorIntent(row.getValue("user").jsonObject.str("content"))
        val labelledContinue = row.str("intent") == "continue"
        if (actual == TutorIntent.NEXT_STEP) {
            if (!labelledContinue) falsePositives++
            TutorFixture().use { f ->
                f.seedReal(real.take(3)); f.open(); f.teach()
                val next = f.machine.next("course","thread")
                assertNull(next.plan); assertEquals(3,next.cards.size)
            }
            continues++
        } else if (labelledContinue) missed++
    }
    var leakCases = 0
    var independentGrades = 0
    real.forEachIndexed { index,q ->
        for (withLetter in listOf(false,true)) TutorFixture().use { f ->
            f.seedReal(listOf(q,real[(index+1)%real.size],real[(index+2)%real.size]))
            f.open(); f.teach()
            val cards = f.machine.next("course","thread").cards
            val target = cards.single { it.payload.str("questionId") == "private-0" }.payload.str("attemptId")
            val plan = f.machine.followup("course","thread",target)
            val correctText = q.options.single { it.optionId == q.correct }.text
            val guard = TutorOutputGuard(plan.pending) { id -> runBlocking { f.machine.leak(plan,id) } }
            val raw = if (withLetter) "选 B，$correctText。" else "$correctText。"
            raw.chunked(3).forEach { guard.push(it) }; guard.finish()
            assertEquals(withLetter,guard.letterReferenceStripped)
            assertEquals("LEAK",f.events().single { it.type == "hint_revealed" }.payload.str("cause"))
            f.machine.finish(plan,"private-followup",true)
            val result = f.submit(cards)
            val state = f.state()
            assertFalse(target in state.mastery.getValue("kp-1").window)
            assertEquals(2,state.mastery.getValue("kp-1").window.size)
            assertFalse(state.mastery.getValue("kp-1").passed)
            result.grades.zip(cards).forEach { (grade,card) ->
                val correct = f.actor.execute { tutorChoiceKey(card.payload.str("questionId")).correctOptionId }
                val letter = card.payload.getValue("letterMap").jsonObject.getValue(correct).jsonPrimitive.content
                assertTrue(grade.payload.str("feedback").contains("（$letter）"))
                independentGrades++
            }
            // Re-explanation on these real cards still only recaps; concept identity is a synthetic fixture.
            val recap = assertNotNull(f.machine.explain("course","thread",TeachingMode.EXAMPLE).plan)
            assertTrue(recap.conceptIds.isEmpty())
            f.machine.finish(recap,"private-recap",true)
            assertEquals(1,f.events().filter { it.type == "explanation_delivered" }.flatMap { it.payload.strings("conceptIds") }.count { it == "cost" })
            for (size in listOf(0,2,4)) assertFailsWith<IllegalArgumentException> { f.machine.settings("course","thread",size) }
            for (size in listOf(1,3)) f.machine.settings("course","thread",size)
            leakCases++
        }
    }
    println("PRIVATE_REPLAY user_utterances=${rows.size} usable_choices=${real.size} continue_to_cards=$continues false_positives=$falsePositives missed_continues=$missed leak_cases=$leakCases independent_grades=$independentGrades ai_calls=0")
    println("Coverage: five structural assertions replayed using private utterances and option texts; concept IDs and Coach state are synthetic because the historical system has neither. No live-model claim.")
    require(falsePositives.toDouble()/rows.size <= .02) { "Keyword false-positive rate exceeds 2%" }
    require(missed == 0) { "Some labelled continues were not recognized; inspect private inputs locally" }
}
