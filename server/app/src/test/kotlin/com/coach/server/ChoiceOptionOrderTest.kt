package com.coach.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The writer model puts the true option first almost every time, so the key has to be
 * moved after the blind check. These tests pin the two properties that make the move safe:
 * the option payloads are untouched, and the same question always lands the same way.
 */
class ChoiceOptionOrderTest {
    private val letters = listOf("A", "B", "C", "D")

    @Test
    fun aThousandSyntheticQuestionsSpreadTheKeyOverAllFourLetters() {
        val counts = mutableMapOf<String, Int>()
        for (index in 1..1000) {
            val draft = draft("A")
            val stored = ChoiceOptionOrder.reorder(draft, "point-$index\u0000q$index")
            assertEquals(letters, stored.map { it.letter }, "letters must stay A-D in order")
            assertEquals(1, stored.count { it.correct }, "exactly one key per item")
            counts[stored.single { it.correct }.letter] = (counts[stored.single { it.correct }.letter] ?: 0) + 1
        }
        for (letter in letters) {
            val share = counts[letter] ?: 0
            assertTrue(share in 200..300, "$letter held $share of 1000 keys, expected 20-30%")
        }
    }

    @Test
    fun theSameQuestionAlwaysGetsTheSameOrder() {
        val draft = draft("A")
        val first = ChoiceOptionOrder.reorder(draft, "ECNM10119-7.2\u0000c1")
        val second = ChoiceOptionOrder.reorder(draft, "ECNM10119-7.2\u0000c1")
        assertEquals(first, second)
        assertEquals(
            first.map { it.letter to it.text },
            second.map { it.letter to it.text },
        )
    }

    @Test
    fun differentQuestionsDoNotShareOneOrder() {
        val orders = (1..30).map { index ->
            ChoiceOptionOrder.reorder(draft("A"), "point-$index\u0000c1")
                .single { it.correct }.letter
        }.toSet()
        assertTrue(orders.size >= 3, "same id under 30 exam points produced only $orders")
    }

    @Test
    fun optionPayloadsSurviveTheReorder() {
        val draft = draft("B")
        val stored = ChoiceOptionOrder.reorder(draft, "point-1\u0000c2")
        assertEquals(draft.map { it.text }.toSet(), stored.map { it.text }.toSet())
        draft.forEach { original ->
            val moved = stored.single { it.text == original.text }
            assertEquals(original.reason, moved.reason)
            assertEquals(original.citation, moved.citation)
            assertEquals(original.correct, moved.correct)
        }
        assertEquals(draft.single { it.correct }.text, stored.single { it.correct }.text)
    }

    @Test
    fun aboveStyleOptionsArePinnedLast() {
        for (meta in listOf("以上皆是", "以上皆非", "以上都不是", "以上各项均正确", "All of the above", "None of the above")) {
            for (position in letters.indices) {
                // The meta option is the key: it must stay last and stay correct.
                val keyed = ChoiceOptionOrder.reorder(draftWithMeta(meta, position, null), "point-9\u0000c3")
                assertEquals(meta, keyed.last().text, "$meta must stay last, was at $position")
                assertTrue(keyed.last().correct)
                assertEquals(letters, keyed.map { it.letter })

                // The meta option is a distractor: still last, the key moves among A-C.
                val keyLetter = if (position == 1) "C" else "B"
                val decoy = ChoiceOptionOrder.reorder(draftWithMeta(meta, position, keyLetter), "point-9\u0000c3")
                assertEquals(meta, decoy.last().text, "$meta must stay last, was at $position")
                assertTrue(!decoy.last().correct)
                assertEquals("option-$keyLetter", decoy.single { it.correct }.text)
                assertTrue(decoy.single { it.correct }.letter in listOf("A", "B", "C"))
                assertEquals(letters, decoy.map { it.letter })
            }
        }
    }

    @Test
    fun ordinaryOptionsThatQuoteAboveAreNotTreatedAsMeta() {
        assertTrue(!ChoiceOptionOrder.isTailOption("以上分析表明需求曲线向下倾斜"))
        assertTrue(!ChoiceOptionOrder.isTailOption("Described above in section 3"))
        assertTrue(ChoiceOptionOrder.isTailOption("以上四个选项都正确"))
    }

    @Test
    fun storedItemsKeepTheirReasonsAndMoveTheKeyAwayFromTheFirstSlot() = runBlocking {
        val keys = mutableSetOf<String>()
        for (point in 1..12) {
            val id = "point-$point"
            val gen = script(ExamPointDrillLimits.GENERATOR_MODEL) { _ ->
                choiceJson(id, "c1", correct = "A")
            }
            val check = script(ExamPointDrillLimits.CHECKER_MODEL) { _ ->
                votesJson(id, "c1", "A")
            }
            val body = ExamPointDrillPipeline(gen, check).generate(input(id))
            val options = (body["choices"] as JsonArray).single().jsonObject["options"] as JsonArray
            val rows = options.map { it.jsonObject }
            assertEquals(listOf("A", "B", "C", "D"), rows.map { it.str("letter") })
            val correct = rows.single { it.str("correct") == "true" }
            assertEquals("correct-option", correct.str("text"))
            rows.forEach { row -> assertTrue(row.str("reason").isNotBlank()) }
            keys += correct.str("letter")
        }
        assertTrue(keys.size >= 3, "pipeline stored every key at $keys")
    }

    private fun draft(correctLetter: String): List<DrillOptionDraft> =
        letters.mapIndexed { index, letter ->
            DrillOptionDraft(
                letter = letter,
                text = "option-$letter",
                correct = letter == correctLetter,
                reason = "reason-$letter",
                citation = buildJsonObject {
                    put("bookId", "tb")
                    put("page", 1)
                    put("quote", "quote-$letter")
                },
            )
        }

    private fun draftWithMeta(meta: String, metaAt: Int, correctLetter: String?): List<DrillOptionDraft> =
        letters.mapIndexed { index, letter ->
            val text = if (index == metaAt) meta else "option-$letter"
            DrillOptionDraft(
                letter = letter,
                text = text,
                correct = if (correctLetter != null) letter == correctLetter else index == metaAt,
                reason = "reason-$letter",
                citation = buildJsonObject {
                    put("bookId", "tb")
                    put("page", 1)
                    put("quote", "quote-$letter")
                },
            )
        }

    private fun choiceJson(topicId: String, id: String, correct: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("id", id)
                put("stem", "Why does supply shift when input costs fall? $topicId")
                put("options", buildJsonArray {
                    for (letter in letters) {
                        add(buildJsonObject {
                            put("letter", letter)
                            put("text", if (letter == correct) "correct-option" else "distractor-$letter")
                            put("correct", letter == correct)
                            put("reason", "reason-$letter for $topicId")
                            put("citation", buildJsonObject {
                                put("bookId", "tb")
                                put("page", 1)
                                put("quote", quote)
                            })
                        })
                    }
                })
            })
        })
        put("essay", kotlinx.serialization.json.JsonNull)
    }.toString()

    private fun votesJson(topicId: String, id: String, letter: String): String = buildJsonObject {
        put("answers", buildJsonArray {
            add(buildJsonObject {
                put("id", id)
                put("letter", letter)
                put("onTopic", true)
                put("support", buildJsonObject {
                    put("bookId", "tb")
                    put("page", 1)
                    put("quote", quote)
                })
            })
        })
        put("essay", buildJsonArray {})
    }.toString()

    private fun input(topicId: String) = buildJsonObject {
        put("topicId", topicId)
        put("topicTitle", "supply")
        put("examFormat", "只考选择题")
        put("blocks", buildJsonArray {
            add(buildJsonObject {
                put("bookId", "tb")
                put("bookTitle", "Textbook")
                put("role", "CORE_TEXTBOOK")
                put("page", 1)
                put("text", textbook)
            })
        })
    }

    private fun script(model: String, body: (call: Int) -> String) = object : BatchAiTransport {
        override suspend fun generateSection(
            systemPrompt: String,
            userPrompt: String,
            task: String,
            batchKey: String,
            maxTokens: Int,
        ): BatchTransportReply = BatchTransportReply(
            text = body(1),
            routeChannel = CompanionRouteChannel.HAIKU_BATCH,
            model = model,
            inputTokens = 1,
            outputTokens = 1,
            ttftMs = 1,
            elapsedMs = 1,
        )
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content.orEmpty()

    private val quote = "Supply shifts when input costs fall."
    private val textbook = "Supply shifts when input costs fall. Demand slopes down."
}
