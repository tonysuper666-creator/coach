package com.coach.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExamPointDrillTest {
    private val quote = "Supply shifts when input costs fall."
    private val textbook = "Supply shifts when input costs fall. Demand slopes down."

    @Test
    fun essayEligibilityFollowsTechniqueAndFormat() {
        assertTrue(ExamPointDrillGate.essayEligible("", listOf("EXPLANATION")))
        assertTrue(ExamPointDrillGate.essayEligible("", listOf("comparison")))
        assertTrue(ExamPointDrillGate.essayEligible("", listOf("APPLICATION")))
        assertTrue(ExamPointDrillGate.essayEligible("含论述题", listOf("CALCULATION")))
        assertFalse(ExamPointDrillGate.essayEligible("", listOf("CALCULATION")))
        assertFalse(ExamPointDrillGate.essayEligible("", listOf("RECALL")))
        assertFalse(ExamPointDrillGate.essayEligible("只考选择题", emptyList()))
        assertFalse(ExamPointDrillGate.essayEligible("multiple choice", emptyList()))
        assertTrue(ExamPointDrillGate.essayEligible("", emptyList()))
        assertTrue(ExamPointDrillGate.essayEligible("short answer", emptyList()))
    }

    @Test
    fun agreeingCheckerStoresThreeChoicesAndHidesTheKeyFromTheChecker() = runBlocking {
        val gen = script(haiku) { call ->
            assertEquals(1, call)
            choicesJson(listOf("c1" to "A", "c2" to "B", "c3" to "C"))
        }
        val check = script(gpt) { call ->
            assertEquals(1, call)
            votes(listOf("c1" to "A", "c2" to "B", "c3" to "C"))
        }
        val body = ExamPointDrillPipeline(gen, check).generate(input(choiceOnly = true))
        assertEquals(3, body.arr("choices").size)
        assertEquals(0, body.int("missingChoices"))
        assertEquals(3, body.int("checked"))
        assertEquals(3, body.int("agreed"))
        assertFalse(body.bool("essayMissing"))
        assertTrue(body["essay"] !is kotlinx.serialization.json.JsonObject)
        assertEquals("A", option(body, 0, 0).str("letter"))
        assertTrue(option(body, 0, 0).bool("correct"))
        assertTrue(check.prompts.single().contains(quote))
        assertFalse(check.prompts.single().contains("REASON_SENTINEL"))
        assertFalse(check.prompts.single().contains("\"correct\""))
        assertEquals(listOf("exam_point_check"), check.tasks)
        assertEquals(listOf("exam_point_drill"), gen.tasks)
    }

    @Test
    fun secondCheckerVoteAdmitsAndBothVotesCount() = runBlocking {
        val gen = script(haiku) { choicesJson(listOf("c1" to "A")) }
        val check = script(gpt) { call ->
            if (call == 1) votes(listOf("c1" to "B")) else votes(listOf("c1" to "A"))
        }
        val body = ExamPointDrillPipeline(gen, check).generate(input(choiceOnly = true))
        assertEquals(1, body.arr("choices").size)
        assertEquals(2, body.int("missingChoices"))
        assertEquals(2, body.int("checked"))
        assertEquals(1, body.int("agreed"))
        assertEquals(2, check.prompts.size)
        assertTrue(check.keys[0].startsWith("drill-check-"))
        assertTrue(check.keys[1].startsWith("drill-recheck-"))
    }

    @Test
    fun twoMismatchesAreDiscarded() = runBlocking {
        val gen = script(haiku) { choicesJson(listOf("c1" to "A", "c2" to "B")) }
        val check = script(gpt) { call ->
            if (call == 1) votes(listOf("c1" to "C", "c2" to "B")) else votes(listOf("c1" to "D"))
        }
        val body = ExamPointDrillPipeline(gen, check).generate(input(choiceOnly = true))
        assertEquals(1, body.arr("choices").size)
        assertEquals(2, body.int("missingChoices"))
        assertEquals(1, body.obj("discards").int("answer_mismatch"))
        assertEquals(3, body.int("checked"))
        assertEquals(1, body.int("agreed"))
    }

    @Test
    fun badCitationIsRewrittenOnceThenDropped() = runBlocking {
        val bad = choicesJson(listOf("c1" to "A"), quote = "not in the source")
        val gen = script(haiku) { bad }
        val check = script(gpt) { error("checker must not see a citation that failed locally") }
        val body = ExamPointDrillPipeline(gen, check).generate(input(choiceOnly = true))
        assertEquals(0, body.arr("choices").size)
        assertEquals(3, body.int("missingChoices"))
        assertEquals(1, body.obj("discards").int("source_mismatch"))
        assertEquals(0, check.prompts.size)
        assertEquals(2, gen.prompts.size)
        assertTrue(gen.keys[0].startsWith("drill-gen-"))
        assertTrue(gen.keys[1].startsWith("drill-repair-"))
        assertFalse(gen.keys[0] == gen.keys[1])
    }

    @Test
    fun textbookRequiresTextbookCitationEvenWhenTheSlideHasTheSameSentence() = runBlocking {
        val gen = script(haiku) { call ->
            val book = if (call == 1) "slides" else "tb"
            choicesJson(listOf("c1" to "A"), bookId = book)
        }
        val check = script(gpt) { votes(listOf("c1" to "A")) }
        val blocks = buildJsonArray {
            add(block("tb", "CORE_TEXTBOOK", textbook, "Textbook"))
            add(block("slides", "LECTURE_SLIDES", textbook, "Slides"))
        }
        val body = ExamPointDrillPipeline(gen, check).generate(input(choiceOnly = true, blocks = blocks))
        assertEquals(1, body.arr("choices").size)
        val storedBook = option(body, 0, 0).obj("citation").str("bookId")
        assertEquals("tb", storedBook)
        assertEquals("CORE_TEXTBOOK", option(body, 0, 0).obj("citation").str("role"))
    }

    @Test
    fun slideCitationIsAllowedWhenNoTextbookIsPresent() = runBlocking {
        val gen = script(haiku) { choicesJson(listOf("c1" to "A", "c2" to "B", "c3" to "C"), bookId = "slides") }
        val check = script(gpt) { votes(listOf("c1" to "A", "c2" to "B", "c3" to "C"), bookId = "slides") }
        val blocks = buildJsonArray { add(block("slides", "LECTURE_SLIDES", textbook, "Slides")) }
        val body = ExamPointDrillPipeline(gen, check).generate(input(choiceOnly = true, blocks = blocks))
        assertEquals(3, body.arr("choices").size)
        assertEquals(0, body.int("missingChoices"))
    }

    @Test
    fun pastExamTextNeverReachesTheGenerator() = runBlocking {
        val gen = script(haiku) { choicesJson(listOf("c1" to "A", "c2" to "B", "c3" to "C")) }
        val check = script(gpt) { votes(listOf("c1" to "A", "c2" to "B", "c3" to "C")) }
        val blocks = buildJsonArray {
            add(block("tb", "CORE_TEXTBOOK", textbook, "Textbook"))
            add(block("old", "PAST_EXAM", "PAST_ONLY_SECRET", "2020 真题"))
        }
        val body = ExamPointDrillPipeline(gen, check).generate(input(choiceOnly = true, blocks = blocks))
        assertEquals(3, body.arr("choices").size)
        assertFalse(gen.prompts.single().contains("PAST_ONLY_SECRET"))
        assertFalse(gen.prompts.single().contains("2020 真题"))
        val onlyPast = ExamPointDrillPipeline(
            script(haiku) { error("no model") },
            script(gpt) { error("no model") },
        ).generate(input(choiceOnly = true, blocks = buildJsonArray {
            add(block("old", "PAST_EXAM", "PAST_ONLY_SECRET", "2020 真题"))
        }))
        assertEquals("只有往年题，不能进题库", onlyPast.str("emptyReason"))
        assertEquals(0, onlyPast.arr("choices").size)
        assertEquals(3, onlyPast.int("missingChoices"))
    }

    @Test
    fun noMaterialsExplainsTheGapWithoutAModelCall() = runBlocking {
        val body = ExamPointDrillPipeline(
            script(haiku) { error("no model") },
            script(gpt) { error("no model") },
        ).generate(input(choiceOnly = true, blocks = buildJsonArray {}))
        assertEquals("没有可引用的教材或讲义", body.str("emptyReason"))
        assertEquals(0, body.arr("choices").size)
    }

    @Test
    fun nonHaikuGeneratorIsNotAdmittedAndTheCheckerIsNotCalled() = runBlocking {
        val gen = script("gpt-6.2-sol") { choicesJson(listOf("c1" to "A", "c2" to "B", "c3" to "C")) }
        val check = script(gpt) { error("checker must not run") }
        val body = ExamPointDrillPipeline(gen, check).generate(input(choiceOnly = true))
        assertEquals(0, body.arr("choices").size)
        assertEquals(1, body.obj("discards").int("wrong_vendor"))
        assertEquals("出题模型不是 Haiku，整批未入库", body.str("emptyReason"))
        assertEquals(0, check.prompts.size)
    }

    @Test
    fun copiedStemIsDiscardedAsRecall() = runBlocking {
        val source = "供给曲线会向右移动因为成本下降。"
        val stem = "供给曲线会向右移动因为成这是理解题"
        val gen = script(haiku) {
            choicesJson(listOf("c1" to "A"), quote = "成本下降", stem = stem)
        }
        val check = script(gpt) { error("recall stem must not be checked") }
        val blocks = buildJsonArray { add(block("tb", "CORE_TEXTBOOK", source, "教材")) }
        val body = ExamPointDrillPipeline(gen, check).generate(input(choiceOnly = true, blocks = blocks))
        assertEquals(0, body.arr("choices").size)
        assertEquals(1, body.obj("discards").int("recall_stem"))
        assertEquals(0, check.prompts.size)
        assertEquals(2, gen.prompts.size)
    }

    @Test
    fun essayFailureKeepsTheChoicesAndASecondSupportedRoundStoresTheOutline() = runBlocking {
        val gen = script(haiku) { draftWithEssay() }
        val failing = script(gpt) {
            votes(listOf("c1" to "A", "c2" to "B", "c3" to "C"), essaySupported = listOf(false, false))
        }
        val failed = ExamPointDrillPipeline(gen, failing).generate(input(choiceOnly = false))
        assertEquals(3, failed.arr("choices").size)
        assertTrue(failed.bool("essayMissing"))
        assertTrue(failed["essay"] !is kotlinx.serialization.json.JsonObject)
        assertEquals(1, failed.obj("discards").int("essay_unsupported"))
        assertEquals(7, failed.int("checked"))
        assertEquals(3, failed.int("agreed"))

        val repairing = script(gpt) { call ->
            val supported = call == 2
            votes(listOf("c1" to "A", "c2" to "B", "c3" to "C"), essaySupported = listOf(supported, supported))
        }
        val stored = ExamPointDrillPipeline(script(haiku) { draftWithEssay() }, repairing).generate(input(choiceOnly = false))
        assertEquals(3, stored.arr("choices").size)
        assertFalse(stored.bool("essayMissing"))
        assertEquals(2, stored.obj("essay").arr("paragraphs").size)
        assertEquals(7, stored.int("checked"))
        assertEquals(5, stored.int("agreed"))
    }

    @Test
    fun fencedJsonStillParses() = runBlocking {
        val gen = script(haiku) { "```json\n${choicesJson(listOf("c1" to "A", "c2" to "B", "c3" to "C"))}\n```" }
        val check = script(gpt) { votes(listOf("c1" to "A", "c2" to "B", "c3" to "C")) }
        val body = ExamPointDrillPipeline(gen, check).generate(input(choiceOnly = true))
        assertEquals(3, body.arr("choices").size)
    }

    private val haiku = ExamPointDrillLimits.GENERATOR_MODEL
    private val gpt = ExamPointDrillLimits.CHECKER_MODEL

    private fun input(choiceOnly: Boolean, blocks: JsonArray? = null) = buildJsonObject {
        put("topicId", "point-1")
        put("topicTitle", "supply")
        put("sourcesHash", "src-1")
        put("examFormat", if (choiceOnly) "只考选择题" else "")
        put("examTechniques", buildJsonArray {
            if (!choiceOnly) add(JsonPrimitive("EXPLANATION"))
        })
        put("blocks", blocks ?: buildJsonArray { add(block("tb", "CORE_TEXTBOOK", textbook, "Textbook")) })
    }

    private fun block(bookId: String, role: String, text: String, title: String) = buildJsonObject {
        put("bookId", bookId)
        put("bookTitle", title)
        put("role", role)
        put("page", 1)
        put("text", text)
    }

    private fun cite(bookId: String, quote: String) = buildJsonObject {
        put("bookId", bookId)
        put("page", 1)
        put("quote", quote)
    }

    private fun choicesJson(
        keys: List<Pair<String, String>>,
        quote: String = this.quote,
        bookId: String = "tb",
        stem: String = "Why does a lower input cost move the supply curve?",
    ) = buildJsonObject {
        put("choices", buildJsonArray {
            for ((id, correct) in keys) {
                add(buildJsonObject {
                    put("id", id)
                    put("stem", "$stem $id")
                    put("options", buildJsonArray {
                        for (letter in listOf("A", "B", "C", "D")) {
                            add(buildJsonObject {
                                put("letter", letter)
                                put("text", "option $letter for $id")
                                put("correct", letter == correct)
                                put("reason", "REASON_SENTINEL $letter")
                                put("citation", cite(bookId, quote))
                            })
                        }
                    })
                })
            }
        })
        put("essay", kotlinx.serialization.json.JsonNull)
    }.toString()

    private fun draftWithEssay(): String {
        val choices = Json.parseToJsonElement(choicesJson(listOf("c1" to "A", "c2" to "B", "c3" to "C"))).jsonObject
        return buildJsonObject {
            put("choices", choices["choices"]!!)
            put("essay", buildJsonObject {
                put("thesis", "Cheaper inputs change the amount offered.")
                put("paragraphs", buildJsonArray {
                    add(buildJsonObject {
                        put("claim", "Firms offer more when an input gets cheaper.")
                        put("citation", cite("tb", quote))
                    })
                    add(buildJsonObject {
                        put("claim", "Buyers still want less at a higher price.")
                        put("citation", cite("tb", quote))
                    })
                })
            })
        }.toString()
    }

    private fun votes(
        letters: List<Pair<String, String>>,
        bookId: String = "tb",
        essaySupported: List<Boolean>? = null,
    ) = buildJsonObject {
        put("answers", buildJsonArray {
            for ((id, letter) in letters) {
                add(buildJsonObject {
                    put("id", id)
                    put("letter", letter)
                    put("support", cite(bookId, quote))
                })
            }
        })
        put("essay", buildJsonArray {
            essaySupported.orEmpty().forEach { ok ->
                add(buildJsonObject {
                    put("supported", ok)
                    if (ok) put("support", cite(bookId, quote))
                })
            }
        })
    }.toString()

    private fun script(model: String, body: (call: Int) -> String) = object : BatchAiTransport {
        val prompts = mutableListOf<String>()
        val tasks = mutableListOf<String>()
        val keys = mutableListOf<String>()
        override suspend fun generateSection(
            systemPrompt: String,
            userPrompt: String,
            task: String,
            batchKey: String,
            maxTokens: Int,
        ): BatchTransportReply {
            prompts += userPrompt
            tasks += task
            keys += batchKey
            return BatchTransportReply(
                text = body(prompts.size),
                routeChannel = CompanionRouteChannel.HAIKU_BATCH,
                model = model,
                inputTokens = 1,
                outputTokens = 1,
                cachedTokens = 0,
                ttftMs = 1,
                elapsedMs = 1,
            )
        }
    }

    private fun option(body: kotlinx.serialization.json.JsonObject, choice: Int, index: Int): kotlinx.serialization.json.JsonObject {
        val choices = body["choices"] as JsonArray
        val options = choices[choice].jsonObject["options"] as JsonArray
        return options[index].jsonObject
    }

    private fun kotlinx.serialization.json.JsonObject.str(key: String) =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun kotlinx.serialization.json.JsonObject.int(key: String) =
        (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    private fun kotlinx.serialization.json.JsonObject.bool(key: String) =
        (this[key] as? JsonPrimitive)?.contentOrNull == "true"

    private fun kotlinx.serialization.json.JsonObject.arr(key: String) =
        this[key] as? JsonArray ?: JsonArray(emptyList())

    private fun kotlinx.serialization.json.JsonObject.obj(key: String) =
        this[key]?.jsonObject ?: buildJsonObject {}
}
