package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.domain.grade.ChapterOpenDraft
import com.coach.domain.grade.ChapterOpenGate
import com.coach.domain.grade.ChapterOpenHistory
import com.coach.domain.grade.ChapterOpenJudgement
import com.coach.domain.grade.ChapterOpenPointRef
import com.coach.domain.grade.ChapterOpenSourceBlock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TON-1910. The grader stub always returns 答到 and quotes a real slice of the answer.
 * Off-topic answers must still come back 没答到. Synthetic sentences only; nothing here is from a book.
 */
class ChapterOpenQuestionTest {
    private val sourcePage = "这一页只留下位置标记，供跳回原句使用。"
    private val offTopic = "晚饭是番茄炒蛋和米饭。"
    private val report = mutableListOf<String>()

    @Test
    fun ownerChapterOpenUsesRealtimeHaikuAndPracticeGradeDoesNot() {
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
        assertEquals(PracticeGradeLimits.TASK_GRADE, grade.task)

        val friend = planPracticeCall(
            PracticeGradeLimits.TASK_CHAPTER_OPEN,
            friend = "friend-a",
            backgroundBatch = true,
            defaultModel = PracticeGradeLimits.PINNED_GRADE_MODEL,
        )
        assertFalse(friend.useBatch)
        assertEquals(BridgeClient.HAIKU, friend.model)
        assertEquals("READER_SELECTION", friend.task)
    }

    @Test
    fun generationDropsCopiedClaimAndKeepsThree() = runBlocking {
        val source = "港口在入夜后改道灯塔仍留在原处供船识别方向"
        val copied = "回答需要写到${source.take(12)}才算碰到这一处"
        val fresh = listOf("入夜改道要写清", "灯塔作用要单讲", "识别方向不能漏")
        val anchors = listOf("入夜后改道", "灯塔仍留", "供船识别")
        assertTrue(ChapterOpenGate.copiesSource(copied, source))
        fresh.forEach { assertFalse(ChapterOpenGate.copiesSource(it, source)) }
        val englishSource = "Ships can see the channel after the lamp is lit on the old pier."
        val englishClaim = "After dark a reader can still see the channel from the water."
        assertFalse(ChapterOpenGate.copiesSource(englishClaim, englishSource))
        assertTrue(englishClaim.length > 40)
        val copiedWords = "Ships can see the channel after the lamp is lit"
        assertTrue(ChapterOpenGate.copiesSource(copiedWords, englishSource))
        val longClaim = "The lamp on the pier still lets a ship find the channel once the sky is dark and the water is calm enough to steer."
        assertTrue(longClaim.length in 81..ChapterOpenGate.MAX_CLAIM_CHARS)
        val longAnchor = "see the channel after the lamp is lit on the old pier tonight"
        val kept = ChapterOpenGate.acceptPoints(
            listOf(com.coach.domain.grade.ChapterOpenRawPoint("P9", longClaim, "b1", longAnchor)),
            listOf(ChapterOpenSourceBlock("b1", 3, englishSource)),
        )
        assertEquals(listOf("see the channel after the lamp is lit on"), kept.map { it.quoteAnchor })
        assertEquals(listOf(longClaim), kept.map { it.claim })

        var calls = 0
        val transport = object : BatchAiTransport {
            override suspend fun generateSection(
                systemPrompt: String,
                userPrompt: String,
                task: String,
                batchKey: String,
                maxTokens: Int,
            ): BatchTransportReply {
                calls++
                return reply(openJson(source, copied, fresh, anchors))
            }
        }
        val pack = PracticeGradeAndDiagnoseService(transport).generateChapterOpen(
            bookId = "synth",
            chapterId = "ch",
            blocks = listOf(ChapterOpenSourceBlock("b1", 3, source)),
        )
        assertEquals(1, calls)
        assertEquals(fresh, pack.points.map { it.claim })
        assertTrue(pack.points.none { it.claim == copied })
        assertEquals(source.indexOf("入夜后改道"), pack.points[0].startUtf16)
        assertTrue(pack.stem.isNotBlank())
    }

    @Test
    fun generationFailsWhenBothCallsStayUnderThreePoints() = runBlocking {
        val source = "港口在入夜后改道灯塔仍留在原处供船识别方向"
        val copied = "回答需要写到${source.take(12)}才算碰到这一处"
        var calls = 0
        val transport = object : BatchAiTransport {
            override suspend fun generateSection(
                systemPrompt: String,
                userPrompt: String,
                task: String,
                batchKey: String,
                maxTokens: Int,
            ): BatchTransportReply {
                calls++
                return reply(openJson(source, copied, emptyList(), emptyList()))
            }
        }
        val ex = runCatching {
            PracticeGradeAndDiagnoseService(transport).generateChapterOpen(
                "synth",
                "ch",
                listOf(ChapterOpenSourceBlock("b1", 3, source)),
            )
        }.exceptionOrNull()
        assertEquals(2, calls)
        assertTrue(ex is IllegalArgumentException)
        assertTrue(ex?.message.orEmpty().contains("没能整理出足够的得分点"))

        val empty = runCatching {
            PracticeGradeAndDiagnoseService(transport).generateChapterOpen("synth", "ch", emptyList())
        }.exceptionOrNull()
        assertTrue(empty is IllegalArgumentException)
        assertTrue(empty?.message.orEmpty().contains("没有可用于出题的正文"))
    }

    @Test
    fun sampleAnswersNeverTreatOffTopicAsHit() = runBlocking {
        val prompts = mutableListOf<String>()
        val transport = object : BatchAiTransport {
            override suspend fun generateSection(
                systemPrompt: String,
                userPrompt: String,
                task: String,
                batchKey: String,
                maxTokens: Int,
            ): BatchTransportReply {
                check(systemPrompt == PracticeGradeAndDiagnoseService.GRADER_SYSTEM) { "chapter open must not diagnose or write a variant" }
                prompts += userPrompt
                val answer = answerOf(userPrompt)
                val quote = answer.take(12)
                val cids = Regex(""""cid"\s*:\s*"([^"]+)"""").findAll(userPrompt).map { it.groupValues[1] }.distinct().toList()
                val body = buildJsonObject {
                    put("items", buildJsonArray {
                        cids.forEach { cid ->
                            add(buildJsonObject {
                                put("cid", cid)
                                put("verdict", "FULL")
                                put("awardedMarks", 1.0)
                                put("studentQuote", quote)
                                put("feedbackNoteZh", "模型判为答到。")
                                put("pdfPage", 2)
                                put("quoteAnchor", "位置标记")
                            })
                        }
                    })
                }
                return reply(body.toString())
            }
        }
        val svc = PracticeGradeAndDiagnoseService(transport)
        val books = listOf("潮", "岸", "岭")
        val chapters = listOf("甲", "乙", "丙")
        books.forEachIndexed { bookIndex, book ->
            chapters.forEachIndexed { chapterIndex, chapter ->
                val claims = (0 until 4).map { point -> claim((bookIndex * 3 + chapterIndex) * 4 + point) }
                claims.forEach { assertTrue(ChapterOpenGate.isOffTopic(it, offTopic)) }
                val good = claims.joinToString("。")
                val average = claims.take(2).joinToString("。")
                val samples = listOf("good" to good, "avg" to average, "off" to offTopic)
                var missed = emptyList<String>()
                var averageItems = emptyList<ChapterOpenJudgement>()
                samples.forEach { (kind, answer) ->
                    val response = svc.process(gradeRequest(book, chapter, claims, answer))
                    assertEquals("CHAPTER_OPEN", response.mode)
                    assertTrue(response.diagnosis == null)
                    assertTrue(response.variant == null)
                    response.rubricItems.forEach { item ->
                        val line = "$book-$chapter $kind ${item.cid} ${ChapterOpenGate.verdictLabel(item.verdict.name)}" +
                            item.missCause.takeIf { it.isNotBlank() }?.let { "/${ChapterOpenGate.causeLabel(it)}" }.orEmpty()
                        report += line
                        assertFalse(item.feedbackNoteZh.contains("供跳回原句"))
                        if (item.verdict == PracticeRubricVerdict.ZERO) {
                            assertTrue("missCause" in item.toJson())
                        } else {
                            assertFalse("missCause" in item.toJson())
                            assertTrue(item.missCause.isBlank())
                        }
                    }
                    when (kind) {
                        "good" -> assertTrue(response.rubricItems.all { it.verdict == PracticeRubricVerdict.FULL })
                        "off" -> {
                            assertTrue(response.rubricItems.all { it.verdict == PracticeRubricVerdict.ZERO })
                            assertTrue(response.rubricItems.all { it.missCause == ChapterOpenGate.NOT_READ })
                        }
                        "avg" -> {
                            val byId = response.rubricItems.associateBy { it.cid }
                            assertEquals(PracticeRubricVerdict.FULL, byId.getValue("P1").verdict)
                            assertEquals(PracticeRubricVerdict.FULL, byId.getValue("P2").verdict)
                            assertEquals(PracticeRubricVerdict.ZERO, byId.getValue("P3").verdict)
                            assertEquals(PracticeRubricVerdict.ZERO, byId.getValue("P4").verdict)
                            missed = response.rubricItems.filter { it.verdict == PracticeRubricVerdict.ZERO }.map { it.cid }
                            averageItems = response.rubricItems.map {
                                ChapterOpenJudgement(it.cid, it.verdict.name, it.feedbackNoteZh, it.missCause)
                            }
                        }
                    }
                }
                val beforeRetry = prompts.size
                val retryAnswer = missed.joinToString("。") { id -> claims[id.removePrefix("P").toInt() - 1] }
                val retry = svc.process(gradeRequest(book, chapter, claims, retryAnswer, missed))
                assertEquals(missed, retry.rubricItems.map { it.cid })
                assertTrue(retry.rubricItems.all { it.verdict == PracticeRubricVerdict.FULL && it.missCause.isBlank() })
                val asked = Regex(""""cid"\s*:\s*"([^"]+)"""").findAll(prompts.last()).map { it.groupValues[1] }.distinct().toList()
                assertEquals(missed, asked)
                assertTrue(prompts.size == beforeRetry + 1)
                retry.rubricItems.forEach { item ->
                    report += "$book-$chapter retry ${item.cid} ${ChapterOpenGate.verdictLabel(item.verdict.name)}"
                }
                val history = ChapterOpenHistory()
                    .add(averageItems)
                    .add(retry.rubricItems.map {
                        ChapterOpenJudgement(it.cid, it.verdict.name, it.feedbackNoteZh, it.missCause)
                    })
                assertEquals(2, history.attempts.size)
                assertEquals(listOf("P1", "P2", "P3", "P4"), history.attempts[0].map { it.id })
                assertEquals(missed, history.attempts[1].map { it.id })
            }
        }

        val partialClaim = "松柏枫槐柳榕樟楠"
        val partialAnswer = "松柏外加樟楠"
        assertFalse(ChapterOpenGate.isOffTopic(partialClaim, partialAnswer))
        assertFalse(ChapterOpenGate.allowsHit(partialClaim, partialAnswer))
        val partial = svc.process(gradeRequest("潮", "甲", listOf(partialClaim, claim(100), claim(101), claim(102)), partialAnswer))
        val partialItem = partial.rubricItems.first { it.cid == "P1" }
        assertEquals(PracticeRubricVerdict.FULL, partialItem.verdict)
        assertEquals(1.0, partialItem.awardedMarks, 1e-6)
        report += "潮-甲 paraphrase P1 ${ChapterOpenGate.verdictLabel(partialItem.verdict.name)}"

        val text = report.joinToString("\n")
        println("CHAPTER_OPEN_SAMPLE\n$text")
        val out = System.getenv("CHAPTER_OPEN_REPORT")
        if (!out.isNullOrBlank()) Files.writeString(Path.of(out), text)
        assertTrue(report.none { it.contains(" off ") && it.contains("答到") && !it.contains("没答到") })
    }

    @Test
    fun ownWordsStayFullAndAnOmittedPointIsNotConfusion() {
        val paraphrase = ChapterOpenGate.judge(
            answer = "松柏外加樟楠",
            points = listOf(ChapterOpenPointRef("P1", "松柏枫槐柳榕樟楠")),
            drafts = listOf(ChapterOpenDraft("P1", "FULL", "这一点答到了。")),
        )
        assertEquals("FULL", paraphrase.single().verdict)
        assertEquals("", paraphrase.single().missCause)

        val points = listOf(
            ChapterOpenPointRef("P1", "港口入夜改道仍可识别"),
            ChapterOpenPointRef("P2", "灯塔入夜仍留在原处"),
            ChapterOpenPointRef("P3", "入夜航标改用新色标记"),
        )
        val covered = "港口入夜会改道，但仍能识别。灯塔入夜留在原处。"
        assertTrue(ChapterOpenGate.contentTokens(points[2].claim).intersect(ChapterOpenGate.contentTokens(covered)).isNotEmpty())
        val omitted = causeOf(points, covered, "P3")
        assertEquals("ZERO", omitted.verdict)
        assertEquals(ChapterOpenGate.NOT_READ, omitted.missCause)

        val swapped = causeOf(points, "航标仍留在原处", "P3")
        assertEquals(ChapterOpenGate.CONCEPT_CONFUSION, swapped.missCause)

        val unclear = causeOf(points, "只提到航标", "P3")
        assertEquals(ChapterOpenGate.UNCLEAR_EXPRESSION, unclear.missCause)

        val touched = causeOf(points, "入夜航标改用新色但是方向说反了", "P3")
        assertEquals(ChapterOpenGate.READ_NOT_UNDERSTOOD, touched.missCause)
    }

    @Test
    fun gradingUsesThePageSentenceWhenTheClientExcerptMissesTheAnchor() = runBlocking {
        val page = "The fog bell stays on the old pier. A long blast marks the channel at night. Ships keep to the north side."
        val anchor = "long blast"
        val wrong = "The fog bell stays on the old pier."
        assertFalse(anchor in wrong)
        val prompts = mutableListOf<String>()
        val transport = object : BatchAiTransport {
            override suspend fun generateSection(
                systemPrompt: String,
                userPrompt: String,
                task: String,
                batchKey: String,
                maxTokens: Int,
            ): BatchTransportReply {
                prompts += userPrompt
                val body = buildJsonObject {
                    put("items", buildJsonArray {
                        add(buildJsonObject {
                            put("cid", "P1")
                            put("verdict", "FULL")
                            put("awardedMarks", 1.0)
                            put("studentQuote", "long blast")
                            put("feedbackNoteZh", "这句的意思已经说全。")
                            put("pdfPage", 2)
                            put("quoteAnchor", anchor)
                        })
                    })
                }
                return reply(body.toString())
            }
        }
        val response = PracticeGradeAndDiagnoseService(transport).process(
            PracticeGradeAndDiagnoseRequest(
                mode = "CHAPTER_OPEN",
                bookId = "pier",
                chapterId = "night",
                questionId = "pier-night",
                questionStem = "用自己的话讲清这一章。",
                studentAnswer = "A long blast at night shows the channel.",
                rubricCriteria = listOf(
                    PracticeRubricCriterionInput(
                        cid = "P1",
                        slotLabel = "夜里用长声标出航道",
                        maxMarks = 1.0,
                        pdfPage = 2,
                        quoteAnchor = anchor,
                        sourceExcerpt = wrong,
                    ),
                ),
                sourcePages = listOf(PracticeSourcePageInput(2, page)),
            ),
        )
        val excerpt = Regex(""""courseDefinitionExcerpt":"([^"]+)"""").find(prompts.single())?.groupValues?.get(1)
        assertEquals("A long blast marks the channel at night.", excerpt)
        assertEquals(PracticeRubricVerdict.FULL, response.rubricItems.single().verdict)
    }

    private fun causeOf(
        points: List<ChapterOpenPointRef>,
        answer: String,
        id: String,
    ): ChapterOpenJudgement = ChapterOpenGate.judge(
        answer = answer,
        points = points,
        drafts = points.map { ChapterOpenDraft(it.id, "ZERO", "模型没给分。") },
    ).first { it.id == id }

    private fun claim(index: Int): String = buildString {
        val base = 0x5B00 + index * 8
        for (offset in 0 until 8) append(Char(base + offset))
    }

    private fun gradeRequest(
        book: String,
        chapter: String,
        claims: List<String>,
        answer: String,
        retry: List<String> = emptyList(),
    ) = PracticeGradeAndDiagnoseRequest(
        mode = "CHAPTER_OPEN",
        bookId = book,
        chapterId = chapter,
        questionId = "$book-$chapter",
        questionStem = "用自己的话讲清这一章。",
        studentAnswer = answer,
        rubricCriteria = claims.mapIndexed { index, claim ->
            PracticeRubricCriterionInput("P${index + 1}", claim, 1.0, 2, "找不到锚")
        },
        sourcePages = listOf(PracticeSourcePageInput(2, sourcePage)),
        generateVariant = true,
        retryPointIds = retry,
    )

    private fun answerOf(prompt: String): String {
        val start = prompt.indexOf("<<<\n")
        val end = prompt.indexOf("\n>>>")
        check(start >= 0 && end > start)
        return prompt.substring(start + 4, end)
    }

    private fun openJson(source: String, copied: String, fresh: List<String>, anchors: List<String>): String =
        buildJsonObject {
            put("stem", "请用自己的话讲清这一章。")
            put("points", buildJsonArray {
                add(buildJsonObject {
                    put("id", "BAD")
                    put("claim", copied)
                    put("blockId", "b1")
                    put("quoteAnchor", source.take(8))
                })
                fresh.forEachIndexed { index, claim ->
                    add(buildJsonObject {
                        put("id", "P${index + 1}")
                        put("claim", claim)
                        put("blockId", "b1")
                        put("quoteAnchor", anchors[index])
                    })
                }
            })
        }.toString()

    private fun reply(text: String): BatchTransportReply = BatchTransportReply(
        text = text,
        routeChannel = CompanionRouteChannel.HAIKU_REALTIME,
        model = PracticeGradeLimits.PINNED_HAIKU_MODEL,
        inputTokens = 400,
        outputTokens = 180,
        ttftMs = 120,
        elapsedMs = 350,
    )
}
