package com.coach.server

import com.coach.ai.BridgeClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * TON-1933. Synthetic course pages only. No live model calls.
 * A framework the answer should have used must be flagged, and at most one of the ten
 * answers may flag a framework the hand label says was not missed.
 */
class CourseEssayFrameworkTest {
    private val textbook = """
        Grossman model: health is both a consumption good and an investment good.
        Marginal efficiency of capital: the return falls as the stock of health rises.
    """.trimIndent()
    private val slides = """
        Grossman model: health is both a consumption good and an investment good.
        Supply and demand framework: buyers and sellers meet at one price.
    """.trimIndent()

    @Test
    fun tenSyntheticAnswersFlagEveryMissedFramework() {
        val points = CourseEssayFramework.extract("Grossman model", coursePages())
        val grossman = points.first { CourseEssayFramework.normalize(it.name) == "grossman model" }
        val supply = points.first { CourseEssayFramework.normalize(it.name) == "supply and demand framework" }
        val capital = points.first { CourseEssayFramework.normalize(it.name) == "marginal efficiency of capital" }
        assertEquals("tb", grossman.bookId)
        assertEquals(12, grossman.page)
        assertEquals(CourseEssayFramework.Kind.FRAMEWORK, grossman.kind)
        assertEquals("slides", supply.bookId)
        assertEquals(4, supply.page)
        assertEquals(CourseEssayFramework.Kind.DEFINITION, capital.kind)
        assertEquals("tb", capital.bookId)

        val missedBoth = setOf(grossman.id, supply.id)
        val samples = listOf(
            Sample(
                "The Grossman model treats health as both a consumption good and an investment good. The supply and demand framework has buyers and sellers meet at one price.",
                emptySet(),
            ),
            Sample(
                "Under the Grossman model, health is both a consumption good and an investment good. Using the supply and demand framework, buyers and sellers meet at one price.",
                emptySet(),
            ),
            Sample(
                "The Grossman model treats health as both a consumption good and an investment good. In the market, buyers and sellers meet at one price.",
                emptySet(),
            ),
            Sample(
                "The Grossman model treats health as both a consumption good and an investment good, which is the whole of the argument.",
                setOf(supply.id),
            ),
            Sample(
                "The supply and demand framework says buyers and sellers meet at one price, and that is the mechanism.",
                setOf(grossman.id),
            ),
            Sample(
                "The Grossman model treats health as only a consumption good and not an investment good. The supply and demand framework says buyers and sellers meet at one price.",
                emptySet(),
            ),
            Sample(
                "People care about staying well, plan their weeks with care, and hope the years ahead stay comfortable and calm.",
                missedBoth,
            ),
            Sample(
                "The capital model and the demand side explain how a household arranges its spending over time.",
                missedBoth,
            ),
            Sample(
                "This essay does not use the Grossman model or the supply and demand framework.",
                missedBoth,
            ),
            Sample(
                "The marginal efficiency of capital falls as the stock of health rises, which is the return schedule used here.",
                missedBoth,
            ),
        )
        assertEquals(10, samples.size)
        var falsePositives = 0
        samples.forEachIndexed { index, sample ->
            val flagged = CourseEssayFramework.missedFrameworkIds(CourseEssayFramework.judge(points, sample.answer))
            assertTrue(sample.missed.all { it in flagged }, "sample $index missed ${sample.missed} but flagged $flagged")
            if (flagged.any { it !in sample.missed }) falsePositives += 1
        }
        assertTrue(falsePositives <= 1, "false positive answers: $falsePositives")

        val phrase = CourseEssayFramework.judge(points, samples[2].answer)
        assertEquals(CourseEssayFramework.Usage.USED, phrase.first { it.point.id == supply.id }.usage)
        val wrong = CourseEssayFramework.judge(points, samples[5].answer)
        assertEquals(CourseEssayFramework.Usage.WRONG, wrong.first { it.point.id == grossman.id }.usage)
        assertEquals(CourseEssayFramework.Usage.USED, wrong.first { it.point.id == supply.id }.usage)
        val negated = CourseEssayFramework.judge(points, samples[8].answer)
        assertEquals(CourseEssayFramework.Usage.MISSING, negated.first { it.point.id == grossman.id }.usage)
        assertEquals(CourseEssayFramework.Usage.MISSING, negated.first { it.point.id == supply.id }.usage)
        val alias = CourseEssayFramework.judge(points, samples[7].answer)
        assertEquals(missedBoth, CourseEssayFramework.missedFrameworkIds(alias))
    }

    @Test
    fun extractPrefersTextbookAndDropsExamPages() {
        val past = CourseEssayFramework.Page(
            bookId = "past",
            bookTitle = "Past paper",
            role = "PAST_EXAM",
            page = 12,
            text = "Trick framework: this decoy must not become a scoring point at all.",
            authorityRank = 1,
        )
        val samePageSlides = coursePages().map { page -> if (page.bookId == "slides") page.copy(page = 12) else page }
        val points = CourseEssayFramework.extract("Grossman model", samePageSlides + past)
        assertTrue(points.none { it.name.contains("Trick", ignoreCase = true) })
        val grossman = points.first { CourseEssayFramework.normalize(it.name) == "grossman model" }
        assertEquals("tb", grossman.bookId)
        assertEquals(12, grossman.page)
        assertTrue(points.any { CourseEssayFramework.normalize(it.name) == "supply and demand framework" && it.bookId == "slides" })
    }

    @Test
    fun agreePathAwardsFullOnTheTextbookPageAndIgnoresGenericSlots() = runBlocking {
        val ids = ids()
        val script = script { _, _, _ -> votes(ids.grossman to true, ids.supply to true) }
        val response = PracticeGradeAndDiagnoseService(script).process(request(bothFrameworks()))
        assertEquals(listOf(PracticeGradeLimits.TASK_COURSE_ESSAY_NOTE, PracticeGradeLimits.TASK_COURSE_ESSAY_CHECK), script.tasks)
        assertEquals(script.users[0], script.users[1])
        assertTrue(script.systems.all { it == CourseEssayFramework.CHECK_SYSTEM.trim() })
        assertTrue(script.users.all { prompt ->
            !prompt.contains("\"usage\"") && !prompt.contains("\"verdict\"") && !prompt.contains("\"gap\"") && !prompt.contains("和材料一致")
        })
        val grossman = response.rubricItems.first { it.cid == ids.grossman }
        assertEquals(PracticeRubricVerdict.FULL, grossman.verdict)
        assertEquals(1.0, grossman.awardedMarks)
        assertEquals(12, grossman.pdfPage)
        assertEquals("tb", grossman.bookId)
        assertEquals("USED", grossman.usage)
        assertEquals("FRAMEWORK", grossman.pointKind)
        assertEquals("框架 · Grossman model", grossman.slotLabel)
        assertTrue(grossman.studentQuoteVerified)
        assertTrue(grossman.missCause.isBlank())
        assertTrue(response.rubricItems.none { it.cid.startsWith("E-") })
        assertEquals(null, response.variant)
        assertEquals(null, response.diagnosis)
        assertTrue(response.summaryMetaLine.contains("按这门课的定义和框架核对 3 条"))
        assertTrue(response.summaryMetaLine.contains("没用到的框架 0 条"))
    }

    @Test
    fun missingFrameworkStaysZeroWhenTheModelCallsItCorrect() = runBlocking {
        val ids = ids()
        val answer = "The supply and demand framework says buyers and sellers meet at one price."
        val script = script { _, _, _ -> votes(ids.grossman to true, ids.supply to true) }
        val response = PracticeGradeAndDiagnoseService(script).process(request(answer))
        assertTrue(script.users.none { it.contains("\"id\":\"${ids.grossman}\"") })
        val grossman = response.rubricItems.first { it.cid == ids.grossman }
        val supply = response.rubricItems.first { it.cid == ids.supply }
        assertEquals(PracticeRubricVerdict.ZERO, grossman.verdict)
        assertEquals("MISSING", grossman.usage)
        assertEquals(0.0, grossman.awardedMarks)
        assertTrue(grossman.feedbackNoteZh.contains("没用到"))
        assertEquals(PracticeRubricVerdict.FULL, supply.verdict)
        assertEquals("slides", supply.bookId)
        assertEquals(4, supply.pdfPage)
    }

    @Test
    fun localWrongUseIsNotClearedByEitherModel() = runBlocking {
        val ids = ids()
        val answer = "The Grossman model treats health as only a consumption good and not an investment good. The supply and demand framework says buyers and sellers meet at one price."
        val script = script { _, _, _ -> votes(ids.grossman to true, ids.supply to true) }
        val response = PracticeGradeAndDiagnoseService(script).process(request(answer))
        assertTrue(script.users.none { it.contains("\"id\":\"${ids.grossman}\"") })
        val grossman = response.rubricItems.first { it.cid == ids.grossman }
        assertEquals("WRONG", grossman.usage)
        assertEquals(PracticeRubricVerdict.PARTIAL, grossman.verdict)
        assertNotEquals(PracticeRubricVerdict.FULL, grossman.verdict)
        assertTrue(grossman.feedbackNoteZh.contains("不一致"))
    }

    @Test
    fun disagreementAsksOnceMoreAndDoesNotAwardFull() = runBlocking {
        val ids = ids()
        val script = script { call, _, _ ->
            when (call) {
                1 -> votes(ids.grossman to true, ids.supply to true)
                2 -> votes(ids.grossman to true, ids.supply to false)
                else -> votes(ids.supply to false)
            }
        }
        val response = PracticeGradeAndDiagnoseService(script).process(request(bothFrameworks()))
        assertEquals(3, script.tasks.size)
        assertEquals(PracticeGradeLimits.TASK_COURSE_ESSAY_CHECK, script.tasks[2])
        assertTrue(script.users[2].contains("\"id\":\"${ids.supply}\""))
        assertFalse(script.users[2].contains("\"id\":\"${ids.grossman}\""))
        assertFalse(script.users[2].contains("和材料一致"))
        val grossman = response.rubricItems.first { it.cid == ids.grossman }
        val supply = response.rubricItems.first { it.cid == ids.supply }
        assertEquals(PracticeRubricVerdict.FULL, grossman.verdict)
        assertEquals(PracticeRubricVerdict.PARTIAL, supply.verdict)
        assertTrue(supply.feedbackNoteZh.contains("两家核对不一致"))
        assertEquals("USED", supply.usage)
    }

    @Test
    fun modelOutageDoesNotHideALocalMiss() = runBlocking {
        val ids = ids()
        val transport = object : BatchAiTransport {
            override suspend fun generateSection(
                systemPrompt: String,
                userPrompt: String,
                task: String,
                batchKey: String,
                maxTokens: Int,
            ): BatchTransportReply = error("down")
        }
        val answer = "The Grossman model treats health as both a consumption good and an investment good."
        val response = PracticeGradeAndDiagnoseService(transport).process(request(answer))
        val grossman = response.rubricItems.first { it.cid == ids.grossman }
        val supply = response.rubricItems.first { it.cid == ids.supply }
        assertEquals(PracticeRubricVerdict.PARTIAL, grossman.verdict)
        assertTrue(grossman.feedbackNoteZh.contains("核对暂时不可用"))
        assertEquals(PracticeRubricVerdict.ZERO, supply.verdict)
        assertEquals("MISSING", supply.usage)
    }

    @Test
    fun wrongModelFamilyIsNotAccepted() = runBlocking {
        val ids = ids()
        val script = script(modelFor = { "claude-sonnet-5-5" }) { _, _, _ -> votes(ids.grossman to true, ids.supply to true) }
        val response = PracticeGradeAndDiagnoseService(script).process(request(bothFrameworks()))
        val grossman = response.rubricItems.first { it.cid == ids.grossman }
        assertNotEquals(PracticeRubricVerdict.FULL, grossman.verdict)
        assertTrue(grossman.feedbackNoteZh.contains("核对暂时不可用"))
    }

    @Test
    fun emptyMaterialAndEmptyExtractionDoNotFallBackToGenericSlots() = runBlocking {
        val quiet = object : BatchAiTransport {
            override suspend fun generateSection(
                systemPrompt: String,
                userPrompt: String,
                task: String,
                batchKey: String,
                maxTokens: Int,
            ): BatchTransportReply = error("should not call")
        }
        val service = PracticeGradeAndDiagnoseService(quiet)
        val unread = service.process(request("A short answer.", pages = emptyList()))
        assertEquals("材料页还没读到，这次不能按课程框架批改。", unread.summaryMetaLine)
        assertTrue(unread.rubricItems.isEmpty())
        val blank = service.process(
            request(
                "A short answer.",
                pages = listOf(
                    PracticeSourcePageInput(
                        pdfPage = 3,
                        text = "Today the class met and talked about the weather for a full hour.",
                        bookId = "tb",
                        role = "CORE_TEXTBOOK",
                        authorityRank = 1,
                    ),
                ),
            ),
        )
        assertEquals("这一考点的材料里没有抽出定义或框架，这次不能按课程框架批改。", blank.summaryMetaLine)
        assertTrue(blank.rubricItems.isEmpty())
    }

    @Test
    fun courseEssayRoutingStaysRealtimeAndChapterOpenIsUnchanged() {
        val note = planPracticeCall(
            PracticeGradeLimits.TASK_COURSE_ESSAY_NOTE,
            friend = null,
            backgroundBatch = true,
            defaultModel = PracticeGradeLimits.PINNED_GRADE_MODEL,
        )
        assertFalse(note.useBatch)
        assertEquals(BridgeClient.HAIKU, note.model)
        assertEquals(PracticeGradeLimits.TASK_COURSE_ESSAY_NOTE, note.task)

        val friendNote = planPracticeCall(
            PracticeGradeLimits.TASK_COURSE_ESSAY_NOTE,
            friend = "friend-a",
            backgroundBatch = true,
            defaultModel = PracticeGradeLimits.PINNED_GRADE_MODEL,
        )
        assertFalse(friendNote.useBatch)
        assertEquals(BridgeClient.HAIKU, friendNote.model)
        assertEquals(PracticeGradeLimits.TASK_COURSE_ESSAY_NOTE, friendNote.task)

        val check = planPracticeCall(
            PracticeGradeLimits.TASK_COURSE_ESSAY_CHECK,
            friend = "friend-a",
            backgroundBatch = true,
            defaultModel = PracticeGradeLimits.PINNED_GRADE_MODEL,
        )
        assertFalse(check.useBatch)
        assertEquals(BridgeClient.GPT_62_ASTRA, check.model)
        assertEquals(PracticeGradeLimits.TASK_COURSE_ESSAY_CHECK, check.task)

        val chapter = planPracticeCall(
            PracticeGradeLimits.TASK_CHAPTER_OPEN,
            friend = null,
            backgroundBatch = false,
            defaultModel = PracticeGradeLimits.PINNED_GRADE_MODEL,
        )
        assertFalse(chapter.useBatch)
        assertEquals(BridgeClient.HAIKU, chapter.model)
        assertEquals(PracticeGradeLimits.TASK_CHAPTER_OPEN, chapter.task)

        val grade = planPracticeCall(
            PracticeGradeLimits.TASK_GRADE,
            friend = null,
            backgroundBatch = false,
            defaultModel = PracticeGradeLimits.PINNED_GRADE_MODEL,
        )
        assertFalse(grade.useBatch)
        assertEquals(PracticeGradeLimits.PINNED_GRADE_MODEL, grade.model)

        val friendChapter = planPracticeCall(
            PracticeGradeLimits.TASK_CHAPTER_OPEN,
            friend = "friend-a",
            backgroundBatch = true,
            defaultModel = PracticeGradeLimits.PINNED_GRADE_MODEL,
        )
        assertFalse(friendChapter.useBatch)
        assertEquals(BridgeClient.HAIKU, friendChapter.model)
        assertEquals("READER_SELECTION", friendChapter.task)
    }

    private data class Sample(val answer: String, val missed: Set<String>)

    private data class Ids(val grossman: String, val supply: String)

    private fun ids(): Ids {
        val points = CourseEssayFramework.extract("Grossman model", coursePages())
        return Ids(
            grossman = points.first { CourseEssayFramework.normalize(it.name) == "grossman model" }.id,
            supply = points.first { CourseEssayFramework.normalize(it.name) == "supply and demand framework" }.id,
        )
    }

    private fun bothFrameworks() =
        "The Grossman model treats health as both a consumption good and an investment good. The supply and demand framework has buyers and sellers meet at one price."

    private fun coursePages(): List<CourseEssayFramework.Page> = listOf(
        CourseEssayFramework.Page("tb", "Textbook", "CORE_TEXTBOOK", 12, textbook, 1),
        CourseEssayFramework.Page("slides", "Slides", "LECTURE_SLIDES", 4, slides, 2),
    )

    private fun sourcePages(): List<PracticeSourcePageInput> = coursePages().map { page ->
        PracticeSourcePageInput(
            pdfPage = page.page,
            text = page.text,
            title = page.bookTitle,
            authorityRank = page.authorityRank,
            bookId = page.bookId,
            role = page.role,
        )
    }

    private fun request(
        answer: String,
        pages: List<PracticeSourcePageInput> = sourcePages(),
    ) = PracticeGradeAndDiagnoseRequest(
        mode = "COURSE_ESSAY",
        bookId = "tb",
        questionStem = "Explain the Grossman model.",
        studentAnswer = answer,
        topicTitle = "Grossman model",
        sourcePages = pages,
        generateVariant = false,
        rubricCriteria = listOf(
            PracticeRubricCriterionInput(cid = "E-1", slotLabel = "核心概念定义与基本模型设定"),
            PracticeRubricCriterionInput(cid = "E-2", slotLabel = "适用前提条件与机制推导"),
            PracticeRubricCriterionInput(cid = "E-3", slotLabel = "关键边界对比与结论"),
        ),
    )

    private fun votes(vararg rows: Pair<String, Boolean>): String = buildJsonObject {
        put("items", buildJsonArray {
            for ((id, correct) in rows) {
                add(buildJsonObject {
                    put("id", id)
                    put("correct", correct)
                    put("gap", if (correct) "和材料一致" else "用到了但没对上")
                })
            }
        })
    }.toString()

    private fun script(
        modelFor: (String) -> String = { task ->
            if (task == PracticeGradeLimits.TASK_COURSE_ESSAY_NOTE) "claude-haiku-5-5" else "gpt-6.2-astra"
        },
        body: (call: Int, task: String, user: String) -> String,
    ) = object : BatchAiTransport {
        val tasks = mutableListOf<String>()
        val users = mutableListOf<String>()
        val systems = mutableListOf<String>()
        override suspend fun generateSection(
            systemPrompt: String,
            userPrompt: String,
            task: String,
            batchKey: String,
            maxTokens: Int,
        ): BatchTransportReply {
            tasks += task
            users += userPrompt
            systems += systemPrompt
            return BatchTransportReply(
                text = body(tasks.size, task, userPrompt),
                routeChannel = CompanionRouteChannel.HAIKU_REALTIME,
                model = modelFor(task),
                inputTokens = 1,
                outputTokens = 1,
                ttftMs = 1,
                elapsedMs = 1,
            )
        }
    }
}
