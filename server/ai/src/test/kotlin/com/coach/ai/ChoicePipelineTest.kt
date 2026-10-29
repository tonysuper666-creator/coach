package com.coach.ai

import com.coach.domain.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.*

class ChoicePipelineTest {
    private val excerpt = "Synthetic source says the uniquely correct result is the selected text."
    private fun generated(count: Int) = buildJsonArray {
        repeat(count) { index -> add(buildJsonObject {
            put("stem","Synthetic question number $index asks for its uniquely correct result.")
            put("correct_text","Right result $index")
            put("distractors",JsonArray((1..3).shuffled(Random(index)).map { JsonPrimitive("Distractor $index:$it") }))
            put("explanation","The source supports the selected text.")
            put("source_quote",excerpt)
        }) }
    }.toString()
    private fun review(count: Int, planted: Boolean = false) = buildJsonArray {
        repeat(count) { index -> add(buildJsonObject {
            put("id",index)
            put("answer_text",when {
                planted && index == 0 -> "NONE"
                planted && index in 3..4 -> "Distractor $index:1"
                else -> "Right result $index"
            })
            put("defects",JsonArray(when {
                planted && index == 0 -> listOf(JsonPrimitive("no_correct"))
                planted && index in 1..2 -> listOf(JsonPrimitive("multiple_correct"))
                planted && index == 3 -> listOf(JsonPrimitive("not_supported_by_source"))
                else -> emptyList()
            }))
            put("note","Synthetic reviewer explanation using option text only.")
        }) }
    }.toString()

    @Test fun sixModelQuestionsUseDifferentCorrectLettersAndThreePromptsHaveNoLetterKeys() {
        StubBridge().use { bridge ->
            bridge.enqueue(200,StubBridge.completion(generated(6)))
            bridge.enqueue(200,StubBridge.completion(review(6)))
            bridge.enqueue(200,StubBridge.completion(review(6)))
            val client=BridgeClient(stubConfig(bridge));val pool=QuestionPool(client)
            val result=pool.fill("kp","Synthetic objective",excerpt,6)
            assertEquals(6,result.accepted.size)
            val rng=Random(1076)
            var previous: String?=null
            val emitted=result.accepted.mapIndexed { i,q -> q.copy(options=q.options.shuffled(Random(i))) }
                .chunked(3).flatMap { group ->
                    val maps=assignLetters(group.map { ChoiceKey(it.options,it.correctOptionId) },previous,rng)
                    val letters=maps.zip(group) { map,q -> map.getValue(q.correctOptionId) }
                    assertEquals(3,letters.distinct().size);assertNotEquals(previous,letters.first())
                    previous=letters.last();letters
                }
            assertTrue(emitted.distinct().size >= 3)
            val response=client.chat(ChatRequest("blind_review_single",BridgeClient.CLAUDE,Prompts.REVIEW_SYSTEM,
                Prompts.blindReview(excerpt,result.accepted,Random(91)),false))
            assertIs<BridgeResult.Success>(response)
            val prompts=bridge.requests.map { JsonText.objectOf(it).getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content }
            assertEquals(3,prompts.size)
            prompts.forEach { prompt ->
                assertFalse(Regex("\\\"[A-D]\\\"\\s*:").containsMatchIn(prompt))
                assertFalse(prompt.contains("A|B|C|D"))
                assertFalse(prompt.contains("\"key\""))
                assertFalse(prompt.contains("\"correctOptionId\""))
            }
            val out=Path.of("build/reports/letter-prompts.txt")
            Files.createDirectories(out.parent)
            Files.writeString(out,prompts.mapIndexed { i,prompt -> "PROMPT ${i+1}\n$prompt" }.joinToString("\n\n"))
            println("SIX_REPLAY accepted=6 correctLetters=$emitted distinctWithinGroups=true boundaryDifferent=true capturedPrompts=3 paidCalls=0")
        }
    }

    @Test fun plantedRegressionStillCatchesFiveOfFiveWithNoCleanFalseAlarm() {
        StubBridge().use { bridge ->
            bridge.enqueue(200,StubBridge.completion(review(6,planted=true)))
            val pool=QuestionPool(BridgeClient(stubConfig(bridge)))
            val questions=TaskParsing.mcqs(generated(6))
            val batch=pool.reviewGroup(listOf(QuestionPool.ReviewEntry("kp",excerpt,questions,0)))
            val reasons=questions.mapIndexed { i,q -> pool.score(q,batch.verdicts[i],excerpt) }
            assertTrue(reasons.take(5).all { it.isNotEmpty() })
            assertTrue(reasons.last().isEmpty())
            // In particular, one wrong key is rejected solely by independently solving to a different text.
            assertTrue(reasons[4].single().startsWith("blind_review_disagrees:"))
            assertTrue(batch.verdicts.values.all { it.answer == "NONE" || questions.any { q -> q.options.any { o -> o.optionId == it.answer } } })
            println("PLANTED stubCaught=5/5 cleanFlagged=0/1 wrongKeyWithoutDefectsCaught=true paidCalls=0")
        }
    }
    @Test fun legacyGenerationAndInvalidReviewTextsFailClosed() {
        assertFails { TaskParsing.mcqs("""[{"stem":"bad","options":{"A":"x"},"key":"A"}]""") }
        val question=TaskParsing.mcqs(generated(1)).single()
        val invalid=TaskParsing.resolveVerdict(ReviewVerdict(0,"1",emptyList(),""),question)
        assertEquals("NONE",invalid.answer)
        val pool=QuestionPool(BridgeClient(BridgeConfig()))
        assertTrue(pool.score(question,invalid,excerpt).isNotEmpty())
        assertTrue(pool.score(question,null,excerpt).isNotEmpty())
    }
}
