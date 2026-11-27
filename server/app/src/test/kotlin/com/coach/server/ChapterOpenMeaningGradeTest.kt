package com.coach.server

import com.coach.domain.grade.ChapterOpenGate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TON-1919. Synthetic sentences and answers written for this test.
 * Nothing here is copied from a book. Human labels are the expected verdicts.
 */
class ChapterOpenMeaningGradeTest {
    @Test
    fun sentenceAroundStopsAtTheBoundary() {
        val page = "前一句放在这里。起雾的时候，灯塔每隔一阵加响一声长鸣。后一句不该带上。"
        assertEquals("起雾的时候，灯塔每隔一阵加响一声长鸣。", ChapterOpenGate.sentenceAround(page, "一声长鸣"))
        assertEquals("", ChapterOpenGate.sentenceAround(page, "不存在的锚"))
    }

    @Test
    fun attachedSentenceKeepsParaphraseAndBlocksShortOffTopic() = runBlocking {
        val faithful = script { answer, index -> labelOf(answer, index) }
        val svc = PracticeGradeAndDiagnoseService(faithful)
        var compared = 0
        var matched = 0
        var paraphraseFull = 0
        var paraphrasePartial = 0
        var offAnswered = 0
        var oldWouldDowngrade = 0
        chapters.forEach { chapter ->
            val page = chapter.points.joinToString("") { it.sentence }
            listOf("good", "average", "paraphrase", "off").forEach { kind ->
                val answer = chapter.answer(kind)
                val response = svc.process(request(chapter, page, answer))
                assertEquals("CHAPTER_OPEN", response.mode)
                assertTrue(response.diagnosis == null)
                assertTrue(response.variant == null)
                response.rubricItems.forEachIndexed { index, item ->
                    val expected = labelOf(answer, index)
                    val claim = chapter.points[index].claim
                    compared++
                    if (item.verdict.name == expected) matched++
                    if (kind == "paraphrase") {
                        if (item.verdict == PracticeRubricVerdict.FULL) paraphraseFull++
                        if (item.verdict == PracticeRubricVerdict.PARTIAL) paraphrasePartial++
                        if (legacyVerdict(claim, answer, "FULL") != "FULL") oldWouldDowngrade++
                        assertFalse(item.feedbackNoteZh.contains("只答到一部分"))
                    }
                    if (kind == "off" && item.verdict == PracticeRubricVerdict.FULL) offAnswered++
                    assertEquals(expected, item.verdict.name, "$kind ${item.cid}")
                }
            }
        }
        assertEquals(3 * 3 * 4 * 4, compared)
        assertTrue(matched.toDouble() / compared >= 0.90)
        assertEquals(36, paraphraseFull)
        assertEquals(0, paraphrasePartial)
        assertEquals(0, offAnswered)
        assertTrue(oldWouldDowngrade > 0, "sample paraphrases must include a case the old bigram gate would have lowered")
        val prompt = faithful.prompts.first { it.contains("Chapter-open judging") }
        assertTrue(prompt.contains("even when none of its words appear"))
        assertTrue(prompt.contains("courseDefinitionExcerpt"))
        assertTrue(chapters.first().points.first().sentence in prompt)
        println(
            "CHAPTER_OPEN_MEANING compared=$compared matched=$matched " +
                "paraphraseFull=$paraphraseFull paraphrasePartial=$paraphrasePartial " +
                "offAnswered=$offAnswered oldWouldDowngrade=$oldWouldDowngrade",
        )
    }

    @Test
    fun shortOffTopicCannotStayFullWhenTheModelSaysFull() = runBlocking {
        val chapter = chapters.first()
        val answer = chapter.offTopic
        assertTrue(answer.count { it.code in 0x4E00..0x9FFF } < 12)
        chapter.points.forEach { point ->
            assertTrue(ChapterOpenGate.obviouslyOffTopic(point.claim, point.sentence, answer))
        }
        val svc = PracticeGradeAndDiagnoseService(script { _, _ -> "FULL" })
        val response = svc.process(request(chapter, chapter.points.joinToString("") { it.sentence }, answer))
        assertTrue(response.rubricItems.all { it.verdict == PracticeRubricVerdict.ZERO })
        assertTrue(response.rubricItems.none { it.verdict == PracticeRubricVerdict.FULL })
    }

    @Test
    fun longParaphraseWithNoSharedWordsStaysFull() = runBlocking {
        val chapter = chapters.first()
        val answer = chapter.paraphrase
        assertTrue(answer.count { it.code in 0x4E00..0x9FFF } >= 12)
        val lowered = chapter.points.filter { legacyVerdict(it.claim, answer, "FULL") != "FULL" }
        assertTrue(lowered.isNotEmpty())
        val svc = PracticeGradeAndDiagnoseService(script { _, _ -> "FULL" })
        val response = svc.process(request(chapter, chapter.points.joinToString("") { it.sentence }, answer))
        assertTrue(response.rubricItems.all { it.verdict == PracticeRubricVerdict.FULL })
        assertTrue(response.rubricItems.none { it.verdict == PracticeRubricVerdict.PARTIAL })
    }

    @Test
    fun oneSharedWordShortParaphraseStaysFullWhenTheModelSaysFull() = runBlocking {
        val chapter = chapters.first()
        val answer = "天黑航道北移"
        val point = chapter.points.first()
        assertTrue(answer.count { it.code in 0x4E00..0x9FFF } < 12)
        assertEquals(1, ChapterOpenGate.contentTokens(point.claim).intersect(ChapterOpenGate.contentTokens(answer)).size)
        assertFalse(ChapterOpenGate.obviouslyOffTopic(point.claim, point.sentence, answer))
        val svc = PracticeGradeAndDiagnoseService(script { _, _ -> "FULL" })
        val response = svc.process(request(chapter, chapter.points.joinToString("") { it.sentence }, answer))
        assertEquals(PracticeRubricVerdict.FULL, response.rubricItems.first().verdict)
        assertTrue(response.rubricItems.drop(1).all { it.verdict == PracticeRubricVerdict.ZERO })
    }

    @Test
    fun shortParaphraseWithNoSharedWordsStaysZeroWhenTheModelSaysFull() = runBlocking {
        val chapter = chapters.first()
        val answer = "夜里改走北边"
        val point = chapter.points.first()
        assertTrue(answer.count { it.code in 0x4E00..0x9FFF } < 12)
        assertTrue(ChapterOpenGate.contentTokens(point.claim).intersect(ChapterOpenGate.contentTokens(answer)).isEmpty())
        assertTrue(ChapterOpenGate.contentTokens(point.sentence).intersect(ChapterOpenGate.contentTokens(answer)).isEmpty())
        assertTrue(ChapterOpenGate.obviouslyOffTopic(point.claim, point.sentence, answer))
        val svc = PracticeGradeAndDiagnoseService(script { _, _ -> "FULL" })
        val response = svc.process(request(chapter, chapter.points.joinToString("") { it.sentence }, answer))
        assertTrue(response.rubricItems.all { it.verdict == PracticeRubricVerdict.ZERO })
    }

    /**
     * Opt-in. Set COACH_CHAPTER_OPEN_LIVE=1 to grade the 144 samples on the pinned
     * model through Codex, the same model the production bridge serves as gpt-6.2-astra.
     * Skipped in the normal test run. Does not call ports 8790 or 8791.
     */
    @Test
    fun pinnedModelAgreesWithHumanLabels() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("COACH_CHAPTER_OPEN_LIVE") == "1")
        val chapter = chapters.first()
        val shortOne = "天黑航道北移"
        val shortNone = "夜里改走北边"
        val longOff = "晚饭是番茄炒蛋和米饭，另外还有一碗紫菜汤和两只苹果。吃完就把碗洗干净，再把桌子擦一遍。今天只谈这顿饭。"
        val point = chapter.points.first()
        assertTrue(shortOne.count { it.code in 0x4E00..0x9FFF } < 12)
        assertEquals(1, ChapterOpenGate.contentTokens(point.claim).intersect(ChapterOpenGate.contentTokens(shortOne)).size)
        assertFalse(ChapterOpenGate.obviouslyOffTopic(point.claim, point.sentence, shortOne))
        assertTrue(shortNone.count { it.code in 0x4E00..0x9FFF } < 12)
        assertTrue(ChapterOpenGate.contentTokens(point.claim).intersect(ChapterOpenGate.contentTokens(shortNone)).isEmpty())
        assertTrue(ChapterOpenGate.contentTokens(point.sentence).intersect(ChapterOpenGate.contentTokens(shortNone)).isEmpty())
        assertTrue(longOff.count { it.code in 0x4E00..0x9FFF } >= 12)
        assertTrue(chapter.points.all { ChapterOpenGate.isOffTopic(it.claim, longOff) && ChapterOpenGate.isOffTopic(it.sentence, longOff) })
        assertTrue(chapter.points.none { ChapterOpenGate.obviouslyOffTopic(it.claim, it.sentence, longOff) })

        val transport = CodexExecTransport()
        val svc = PracticeGradeAndDiagnoseService(transport)
        var compared = 0
        var matched = 0
        var rawMatched = 0
        var paraphraseFull = 0
        var paraphrasePartial = 0
        var offAnswered = 0
        var quoteZeroed = 0
        val misses = mutableListOf<String>()
        val byKind = linkedMapOf("good" to 0, "average" to 0, "paraphrase" to 0, "off" to 0)
        val byKindMatched = linkedMapOf("good" to 0, "average" to 0, "paraphrase" to 0, "off" to 0)
        chapters.forEach { sample ->
            val page = sample.points.joinToString("") { it.sentence }
            listOf("good", "average", "paraphrase", "off").forEach { kind ->
                val answer = sample.answer(kind)
                val response = svc.process(request(sample, page, answer))
                response.rubricItems.forEachIndexed { index, item ->
                    val expected = labelOf(answer, index)
                    compared++
                    byKind[kind] = byKind.getValue(kind) + 1
                    if (item.verdict.name == expected) {
                        matched++
                        byKindMatched[kind] = byKindMatched.getValue(kind) + 1
                    }
                    if (item.rawModelVerdict.name == expected) rawMatched++
                    if (kind == "paraphrase" && item.verdict == PracticeRubricVerdict.FULL) paraphraseFull++
                    if (kind == "paraphrase" && item.verdict == PracticeRubricVerdict.PARTIAL) paraphrasePartial++
                    if (kind == "off" && item.verdict == PracticeRubricVerdict.FULL) offAnswered++
                    if (item.quoteGateZeroed) quoteZeroed++
                    if (item.verdict.name != expected) {
                        misses += "$kind ${sample.book}-${sample.name} ${item.cid} want=$expected raw=${item.rawModelVerdict.name}"
                    }
                }
            }
        }
        val page = chapter.points.joinToString("") { it.sentence }
        val shortOneItems = svc.process(request(chapter, page, shortOne)).rubricItems
        val shortNoneItems = svc.process(request(chapter, page, shortNone)).rubricItems
        val longOffItems = svc.process(request(chapter, page, longOff)).rubricItems
        fun brief(items: List<GradedRubricItemResult>) =
            items.joinToString(",") { "${it.cid}:${it.rawModelVerdict.name}->${it.verdict.name}${if (it.quoteGateZeroed) ":q0" else ""}" }
        println(
            "CHAPTER_OPEN_LIVE compared=$compared matched=$matched rawMatched=$rawMatched " +
                "paraphraseFull=$paraphraseFull paraphrasePartial=$paraphrasePartial " +
                "offAnswered=$offAnswered quoteZeroed=$quoteZeroed calls=${transport.calls} " +
                "good=${byKindMatched.getValue("good")}/${byKind.getValue("good")} " +
                "average=${byKindMatched.getValue("average")}/${byKind.getValue("average")} " +
                "paraphrase=${byKindMatched.getValue("paraphrase")}/${byKind.getValue("paraphrase")} " +
                "off=${byKindMatched.getValue("off")}/${byKind.getValue("off")} " +
                "shortOne=${brief(shortOneItems)} shortNone=${brief(shortNoneItems)} longOff=${brief(longOffItems)} " +
                "misses=${misses.joinToString(";")}",
        )
        val summary = "CHAPTER_OPEN_LIVE compared=$compared matched=$matched rawMatched=$rawMatched " +
            "paraphraseFull=$paraphraseFull paraphrasePartial=$paraphrasePartial " +
            "offAnswered=$offAnswered quoteZeroed=$quoteZeroed calls=${transport.calls} " +
            "good=${byKindMatched.getValue("good")}/${byKind.getValue("good")} " +
            "average=${byKindMatched.getValue("average")}/${byKind.getValue("average")} " +
            "paraphrase=${byKindMatched.getValue("paraphrase")}/${byKind.getValue("paraphrase")} " +
            "off=${byKindMatched.getValue("off")}/${byKind.getValue("off")} " +
            "shortOne=${brief(shortOneItems)} shortNone=${brief(shortNoneItems)} longOff=${brief(longOffItems)} " +
            "misses=${misses.joinToString(";")}\n"
        Files.writeString(
            java.nio.file.Path.of("build", "chapter-open-live.log"),
            summary,
            StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
        assertEquals(144, compared)
        assertEquals(PracticeRubricVerdict.FULL, shortOneItems.first().verdict)
        assertTrue(longOffItems.all { it.verdict == PracticeRubricVerdict.ZERO })
        assertTrue(shortNoneItems.all { it.verdict == PracticeRubricVerdict.ZERO })
    }

    private class CodexExecTransport : BatchAiTransport {
        var calls: Int = 0

        override suspend fun generateSection(
            systemPrompt: String,
            userPrompt: String,
            task: String,
            batchKey: String,
            maxTokens: Int,
        ): BatchTransportReply {
            calls++
            Files.writeString(
                progressLog(),
                "call=$calls task=$task\n",
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
            val started = System.nanoTime()
            val out = Files.createTempFile("coach-grade-", ".txt")
            val err = Files.createTempFile("coach-grade-err-", ".txt")
            val work = Files.createTempDirectory("coach-grade-work-")
            val codex = System.getenv("COACH_CODEX") ?: "C:\\Users\\TONYS\\AppData\\Roaming\\npm\\codex.cmd"
            val prompt = "$systemPrompt\n\n$NO_TOOLS\n\n$userPrompt"
            val command = "\"$codex\" exec -m gpt-6.2-astra -c model_reasoning_effort=high --ephemeral --skip-git-repo-check -s read-only -o \"${out.toAbsolutePath()}\" -"
            val process = ProcessBuilder("cmd.exe", "/d", "/s", "/c", command)
                .directory(work.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(err.toFile())
                .start()
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(prompt) }
            val finished = process.waitFor(180, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                error("codex_timeout call=$calls")
            }
            val text = Files.readString(out).trim()
            if (process.exitValue() != 0 || text.isBlank()) {
                val detail = Files.readString(err)
                    .lineSequence()
                    .filter { '@' !in it && "logged" !in it.lowercase() }
                    .joinToString(" ")
                    .take(180)
                error("codex_exit=${process.exitValue()} textLen=${text.length} call=$calls detail=$detail")
            }
            val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            return BatchTransportReply(
                text = text,
                routeChannel = CompanionRouteChannel.HAIKU_REALTIME,
                model = PracticeGradeLimits.PINNED_GRADE_MODEL,
                inputTokens = 0,
                outputTokens = 0,
                ttftMs = elapsed,
                elapsedMs = elapsed,
            )
        }

        private companion object {
            const val NO_TOOLS =
                "Answer directly in plain text. Do not use any built-in tools (shell, files, browser, web search); you have no access to the user's machine."

            fun progressLog() = java.nio.file.Path.of("build", "chapter-open-live.log")
        }
    }

    private fun labelOf(answer: String, index: Int): String {
        val chapter = chapters.first { it.owns(answer) }
        return when (answer) {
            chapter.good, chapter.paraphrase -> "FULL"
            chapter.offTopic -> "ZERO"
            chapter.average -> if (index < 2) "FULL" else "ZERO"
            else -> error("unlabeled answer")
        }
    }

    private fun legacyVerdict(claim: String, answer: String, model: String): String = when {
        ChapterOpenGate.isOffTopic(claim, answer) -> "ZERO"
        model == "FULL" && !ChapterOpenGate.allowsHit(claim, answer) -> "PARTIAL"
        else -> model
    }

    private fun request(chapter: Chapter, page: String, answer: String) = PracticeGradeAndDiagnoseRequest(
        mode = "CHAPTER_OPEN",
        bookId = chapter.book,
        chapterId = chapter.name,
        questionId = "${chapter.book}-${chapter.name}",
        questionStem = "用自己的话讲清这一章。",
        studentAnswer = answer,
        rubricCriteria = chapter.points.mapIndexed { index, point ->
            PracticeRubricCriterionInput(
                cid = "P${index + 1}",
                slotLabel = point.claim,
                maxMarks = 1.0,
                pdfPage = 2,
                quoteAnchor = point.anchor,
                sourceExcerpt = point.sentence,
            )
        },
        sourcePages = listOf(PracticeSourcePageInput(2, page)),
        generateVariant = true,
    )

    private fun script(verdictFor: (String, Int) -> String): RecordingTransport = RecordingTransport(verdictFor)

    private class RecordingTransport(
        private val verdictFor: (String, Int) -> String,
    ) : BatchAiTransport {
        val prompts = mutableListOf<String>()

        override suspend fun generateSection(
            systemPrompt: String,
            userPrompt: String,
            task: String,
            batchKey: String,
            maxTokens: Int,
        ): BatchTransportReply {
            check(systemPrompt == PracticeGradeAndDiagnoseService.GRADER_SYSTEM)
            prompts += userPrompt
            val answer = answerOf(userPrompt)
            val cids = Regex(""""cid"\s*:\s*"([^"]+)"""")
                .findAll(userPrompt)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            val quote = answer.take(8)
            val body = buildJsonObject {
                put("items", buildJsonArray {
                    cids.forEach { cid ->
                        val index = cid.removePrefix("P").toInt() - 1
                        val verdict = verdictFor(answer, index)
                        add(buildJsonObject {
                            put("cid", cid)
                            put("verdict", verdict)
                            put("awardedMarks", if (verdict == "FULL") 1.0 else if (verdict == "PARTIAL") 0.5 else 0.0)
                            put("studentQuote", if (verdict == "ZERO") "" else quote)
                            put("feedbackNoteZh", if (verdict == "FULL") "这句的意思已经说全。" else if (verdict == "PARTIAL") "这句只说到一半。" else "这句没有答到。")
                            put("pdfPage", 2)
                            put("quoteAnchor", "锚")
                        })
                    }
                })
            }
            return BatchTransportReply(
                text = body.toString(),
                routeChannel = CompanionRouteChannel.HAIKU_REALTIME,
                model = PracticeGradeLimits.PINNED_GRADE_MODEL,
                inputTokens = 400,
                outputTokens = 180,
                ttftMs = 20,
                elapsedMs = 40,
            )
        }

        private fun answerOf(prompt: String): String {
            val start = prompt.indexOf("<<<\n")
            val end = prompt.indexOf("\n>>>")
            check(start >= 0 && end > start)
            return prompt.substring(start + 4, end)
        }
    }

    private data class Point(val claim: String, val sentence: String, val anchor: String)

    private data class Chapter(
        val book: String,
        val name: String,
        val points: List<Point>,
        val good: String,
        val average: String,
        val paraphrase: String,
        val offTopic: String = "晚饭是番茄炒蛋和米饭。",
    ) {
        fun owns(answer: String): Boolean = answer == good || answer == average || answer == paraphrase || answer == offTopic
        fun answer(kind: String): String = when (kind) {
            "good" -> good
            "average" -> average
            "paraphrase" -> paraphrase
            else -> offTopic
        }
    }

    private companion object {
        fun p(claim: String, sentence: String, anchor: String): Point {
            check(anchor in sentence) { anchor }
            check(ChapterOpenGate.sentenceAround(sentence, anchor) == sentence.trim())
            return Point(claim, sentence, anchor)
        }

        val chapters = listOf(
            Chapter(
                "港灯", "甲",
                listOf(
                    p("入夜以后航道会改", "入夜以后，港口把航道改到北侧。", "航道改到"),
                    p("灯塔留在原地给船认路", "灯塔仍留在原地，船上的人靠它辨认方向。", "靠它辨认"),
                    p("雾天要加响一声长鸣", "起雾的时候，灯塔每隔一阵加响一声长鸣。", "一声长鸣"),
                    p("红灯表示此段禁止驶入", "红灯亮起表示这一段禁止船只驶入。", "禁止船只"),
                ),
                good = "入夜以后港口把航道改到北侧。灯塔留在原地，船上的人靠它辨认方向。起雾要加响一声长鸣。红灯表示这一段禁止驶入。",
                average = "入夜以后港口把航道改到北侧。灯塔留在原地，船上的人靠它辨认方向。",
                paraphrase = "天色暗下来之后，船走的那条水路被挪到靠北的一边。那座高塔不搬家，航行的人用它分清前后。大雾里会定时传来一声拖长的响。看到红色灯光，就说明这段水路不许进。",
            ),
            Chapter(
                "港灯", "乙",
                listOf(
                    p("涨潮时码头缆绳要留余量", "涨潮的时候码头上的缆绳要留出一段余量。", "留出一段"),
                    p("退潮后跳板会变陡", "退潮之后跳板会变得更陡。", "变得更陡"),
                    p("潮位数字写在桩上", "当天的潮位数字写在木桩朝外的一面。", "木桩朝外"),
                    p("大潮日提前一小时收跳板", "大潮那一天要提前一个小时收起跳板。", "收起跳板"),
                ),
                good = "涨潮时码头缆绳要留出余量。退潮后跳板会变陡。潮位数字写在木桩朝外的一面。大潮日要提前一个小时收起跳板。",
                average = "涨潮时码头缆绳要留出余量。退潮后跳板会变陡。",
                paraphrase = "水面升高时，拴船的绳子不能拉满，得空出一截。水面落下以后，搭到岸上的板子会立得更厉害。今天水有多高，记在木头柱子朝外的那边。特别大的那一天，要早六十分钟把这块板子撤走。",
            ),
            Chapter(
                "港灯", "丙",
                listOf(
                    p("白圈表示这箱可以淋雨", "箱子上的白圈表示这一箱可以淋雨。", "可以淋雨"),
                    p("黑三角表示易碎要直立", "黑三角表示易碎，而且必须直立。", "必须直立"),
                    p("重量写在箱底横条", "重量写在箱子底部的横条上。", "底部的横条"),
                    p("两个人才能抬的箱子标双人", "需要两个人抬的箱子标上双人记号。", "双人记号"),
                ),
                good = "白圈表示这箱可以淋雨。黑三角表示易碎而且必须直立。重量写在箱子底部的横条上。两个人才能抬的箱子标上双人记号。",
                average = "白圈表示这箱可以淋雨。黑三角表示易碎而且必须直立。",
                paraphrase = "画一个空心圆，意思是这只容器不怕被水浇到。涂黑的三角告诉你里面怕磕，而且不能倒着放。有多重，记在最下面那根横着的条子上。一个人搬不动的，要做上两个人的标记。",
            ),
            Chapter(
                "蜂事", "甲",
                listOf(
                    p("蜂箱口要朝南避开北风", "蜂箱的门口要朝南，好避开北风。", "避开北风"),
                    p("清晨开箱蜜蜂比较稳", "清晨开箱的时候蜜蜂比较稳。", "蜜蜂比较稳"),
                    p("烟只用来让蜂退开", "烟只是用来让蜜蜂暂时退开。", "暂时退开"),
                    p("取蜜后要留够过冬的蜜", "取走蜂蜜以后要给蜂群留够过冬的蜜。", "留够过冬"),
                ),
                good = "蜂箱口要朝南，避开北风。清晨开箱时蜜蜂比较稳。烟只用来让蜜蜂暂时退开。取蜜后要给蜂群留够过冬的蜜。",
                average = "蜂箱口要朝南，避开北风。清晨开箱时蜜蜂比较稳。",
                paraphrase = "箱子的出入口对着南方，这样冷风吹不到。太阳刚出来就打开，里面的虫子不容易躁。点烟不是为了熏走它们，只是让它们先让开一会儿。把甜的收走之后，还得剩下足够它们挨过寒冷季节的那一份。",
            ),
            Chapter(
                "蜂事", "乙",
                listOf(
                    p("王台多了要拆掉多余的", "王台太多的时候要拆掉多余的。", "拆掉多余"),
                    p("新王出房后老王可能分走", "新王出房以后老王可能会带一部分蜂分走。", "带一部分"),
                    p("分走的蜂先落在近处", "分走的蜂群会先落在近处的树枝上。", "近处的树枝"),
                    p("收回分群要在傍晚", "要把分走的蜂收回来，最好在傍晚。", "最好在傍晚"),
                ),
                good = "王台太多要拆掉多余的。新王出房后老王可能带一部分蜂分走。分走的蜂群先落在近处的树枝上。收回来最好在傍晚。",
                average = "王台太多要拆掉多余的。新王出房后老王可能带一部分蜂分走。",
                paraphrase = "育王的小房子如果成片出现，多出来的要掐掉。新的带头者钻出来之后，原来的带头者往往会领着一队离开。离开的那一队会先停在旁边的树杈上。把它们请回去，选在天快黑的时候最合适。",
            ),
            Chapter(
                "蜂事", "丙",
                listOf(
                    p("糖水只在缺蜜时喂", "糖水只在蜂群缺蜜的时候喂。", "缺蜜的时候"),
                    p("喂糖要在箱内不要洒在门口", "喂糖要放在箱子里面，不要洒在门口。", "不要洒在门口"),
                    p("夏天给箱顶留一条缝透气", "夏天要给箱顶留一条窄缝透气。", "窄缝透气"),
                    p("蚂蚁靠近时把箱脚泡进水碗", "蚂蚁靠近的时候把箱脚泡进水碗里。", "泡进水碗"),
                ),
                good = "糖水只在缺蜜的时候喂。喂糖要放在箱子里面，不要洒在门口。夏天给箱顶留一条窄缝透气。蚂蚁靠近时把箱脚泡进水碗里。",
                average = "糖水只在缺蜜的时候喂。喂糖要放在箱子里面，不要洒在门口。",
                paraphrase = "只有储存的甜食不够了，才给兑了水的甜液。甜液要放进屋子内部，不能倒在出入口外面。热的季节，顶上留一道细缝让气流通。小黑虫爬过来时，把四只脚浸到装了水的小碟子里。",
            ),
            Chapter(
                "集市", "甲",
                listOf(
                    p("开市前先把秤校准", "开市以前先把秤校准。", "先把秤校准"),
                    p("标价写在货签朝外的一面", "标价写在货签朝外的一面。", "朝外的一面"),
                    p("缺货的位子留空不要用别的货填", "缺货的位子留空，不要拿别的货填上。", "不要拿别的"),
                    p("收摊时先收容易坏的货", "收摊的时候先收容易坏的货。", "容易坏的货"),
                ),
                good = "开市前先把秤校准。标价写在货签朝外的一面。缺货的位子留空，不要拿别的货填上。收摊时先收容易坏的货。",
                average = "开市前先把秤校准。标价写在货签朝外的一面。",
                paraphrase = "客人还没来，先把称重的家伙调准。价钱写在牌子朝着路人的那一侧。某个位置空了就让它空着，别塞进另一样东西。结束时先把放不久的那些拿走。",
            ),
            Chapter(
                "集市", "乙",
                listOf(
                    p("找零要从大面额点起", "找零要从大面额开始点。", "大面额开始"),
                    p("顾客数清再放手", "等顾客把钱数清再把手放开。", "数清再把手"),
                    p("账记在当天的格子里", "这笔账要记在当天的格子里。", "当天的格子"),
                    p("短款当天补上并写原因", "短了的钱要在当天补上，并且写上原因。", "写上原因"),
                ),
                good = "找零要从大面额开始点。等顾客把钱数清再放手。账要记在当天的格子里。短了的钱当天补上，并且写上原因。",
                average = "找零要从大面额开始点。等顾客把钱数清再放手。",
                paraphrase = "退回去的纸币，先数面值最大的那张。对面的人数明白之前，你的手不要松开。这笔进出写进今天对应的那一栏。少掉的数目要在今天补齐，旁边注明为什么少。",
            ),
            Chapter(
                "集市", "丙",
                listOf(
                    p("雨天把布棚向外撑开", "下雨的时候把布棚向外撑开。", "向外撑开"),
                    p("积水要引到摊外", "摊位里的积水要引到摊位外面。", "引到摊位外面"),
                    p("湿货和干货分开摆", "湿的货和干的货要分开摆。", "分开摆"),
                    p("收市后把棚布晾干", "收市以后把棚布晾干。", "把棚布晾干"),
                ),
                good = "下雨时把布棚向外撑开。摊位里的积水要引到外面。湿的货和干的货分开摆。收市后把棚布晾干。",
                average = "下雨时把布棚向外撑开。摊位里的积水要引到外面。",
                paraphrase = "掉下水点时，把头顶那块布往外支，别让它塌在货物上。洼下去的水要导到这个位子的外边。沾了水的和怕潮的不要放在一起。一天结束，把那块布挂起来让它变干。",
            ),
        )
    }
}
