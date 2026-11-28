package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.core.LedgerActor
import com.coach.domain.json
import com.coach.domain.scope.VisibleScope
import com.coach.domain.str
import com.coach.protocol.AnswerLayerKind
import com.coach.protocol.BookStance
import com.coach.protocol.BookStanceCopy
import com.coach.protocol.BookStanceGuard
import com.coach.protocol.EsotericRiskKeywords
import com.coach.protocol.StanceSentenceSplitter
import com.coach.store.LedgerStore
import com.coach.store.SourcePage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 路线 1 服务端立场门禁与反例单测（TON-1976 §3 / TON-1977 #3）。
 */
class StanceExplanationTest {

    private class Harness : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(Files.createTempDirectory("stance-reader-"))
        val actor = LedgerActor(scope, store)
        val stub = StubTutor()
        val reader = ReaderExplanation(actor, BridgeClient(BridgeConfig(stub.baseUrl)), readSources = { pack, _ -> pack })
        override fun close() {
            runBlocking { actor.close() }
            scope.cancel()
            stub.close()
        }
    }

    /**
     * 反例 1（TON-1976 §3）：两句共用一个 anchor，原句只支持第一句：
     * 返回里只剩第一句，带出处、能点；第二句不出现，droppedSentences = 1。
     */
    @Test
    fun sharedAnchorDropsUnsupportedSecondSentenceWithoutDroppingFirst() {
        val bookHash = "a".repeat(64)
        val pageText = "Synthetic pears grow in the orchard every autumn."
        val pack = StanceSourcePack.fromSourcePack(
            SourcePack(listOf(SourcePage("$bookHash:p4:20", 4, pageText))),
            BookStance.KNOWLEDGE,
        )
        // 用 quote 验证与引号验证两种形态分别测一次，确保共用 anchor 均只丢第二句。
        val payloadWithQuotes = """
            {
              "paragraphs": [
                {
                  "layer": "book",
                  "sentences": [
                    {"text": "梨树每年秋天在果园结果。", "anchors": ["S1"], "quote": "Synthetic pears grow in the orchard"},
                    {"text": "火星殖民将在下世纪建成。", "anchors": ["S1"], "quote": "Mars colony built next century"}
                  ]
                }
              ],
              "boundary": null
            }
        """.trimIndent()
        val res1 = assertNotNull(checkedStancePayload(payloadWithQuotes, pack, BookStance.KNOWLEDGE))
        assertEquals(1, res1.droppedSentences)
        val bookLayer1 = res1.layers.single() as CheckedStanceLayer.Book
        assertEquals(BookStanceCopy.BOOK_DEFAULT, bookLayer1.label)
        assertEquals(1, bookLayer1.sentences.size)
        assertEquals("梨树每年秋天在果园结果。", bookLayer1.sentences.single().text)
        val cite = bookLayer1.sentences.single().citations.single()
        assertEquals("$bookHash:p4:20", cite.docId)
        assertEquals(4, cite.pdfPage)
        assertEquals(20, cite.startUtf16)
        assertEquals(20 + pageText.length, cite.endUtf16)
        assertEquals(pageText, cite.quote)

        val payloadWithInlineQuotes = """
            {
              "paragraphs": [
                {
                  "layer": "book",
                  "sentences": [
                    {"text": "原文提到“Synthetic pears”长在果园。", "anchors": ["S1"]},
                    {"text": "原文还写到“Synthetic apples”长在海边。", "anchors": ["S1"]}
                  ]
                }
              ],
              "boundary": null
            }
        """.trimIndent()
        val res2 = assertNotNull(checkedStancePayload(payloadWithInlineQuotes, pack, BookStance.KNOWLEDGE))
        assertEquals(1, res2.droppedSentences)
        val bookLayer2 = res2.layers.single() as CheckedStanceLayer.Book
        assertEquals(1, bookLayer2.sentences.size)
        assertTrue(bookLayer2.sentences.single().text.contains("Synthetic pears"))
    }

    /**
     * QA 回归反例：两句共用同一个真实引文（且带完全相同的真实 quote），原文只支持第一句，
     * 第二句（含反序、含虚词陷阱、中文对中文、英文对英文）一律不得借用他人引文标成「本书」。
     */
    @Test
    fun unrelatedStatementBorrowingSameRealQuoteIsDroppedPerSentence() {
        val evidence = "Only pears grow in this orchard."
        val pack = StanceSourcePack.fromSourcePack(
            SourcePack(listOf(SourcePage("a".repeat(64) + ":p1:0", 1, evidence))),
            BookStance.KNOWLEDGE,
        )
        // 1. 中文陈述共用英文真实引文（原顺序）
        val forward = """
            {"paragraphs":[{"layer":"book","sentences":[
                {"text":"果园里只种梨。","anchors":["S1"],"quote":"$evidence"},
                {"text":"月球表面有一座歌剧院。","anchors":["S1"],"quote":"$evidence"}
            ]}],"boundary":null}
        """.trimIndent()
        val r1 = assertNotNull(checkedStancePayload(forward, pack, BookStance.KNOWLEDGE))
        val kept1 = r1.layers.filterIsInstance<CheckedStanceLayer.Book>().flatMap { it.sentences }
        assertEquals(1, kept1.size, "kept=${kept1.size}, dropped=${r1.droppedSentences}")
        assertEquals("果园里只种梨。", kept1.single().text)
        assertEquals(1, r1.droppedSentences)

        // 2. 反序 + 虚词陷阱（「只」不得借 "Only" 放过无关句）
        val reversed = """
            {"paragraphs":[{"layer":"book","sentences":[
                {"text":"月球表面只有一座歌剧院。","anchors":["S1"],"quote":"$evidence"},
                {"text":"果园里只种梨。","anchors":["S1"],"quote":"$evidence"}
            ]}],"boundary":null}
        """.trimIndent()
        val r2 = assertNotNull(checkedStancePayload(reversed, pack, BookStance.KNOWLEDGE))
        val kept2 = r2.layers.filterIsInstance<CheckedStanceLayer.Book>().flatMap { it.sentences }
        assertEquals(1, kept2.size)
        assertEquals("果园里只种梨。", kept2.single().text)
        assertEquals(1, r2.droppedSentences)

        // 3. 中文原文共用同一真实引文
        val zhEvidence = "道可道，非常道。"
        val zhPack = StanceSourcePack.fromSourcePack(
            SourcePack(listOf(SourcePage("b".repeat(64) + ":p1:0", 1, zhEvidence))),
            BookStance.CLASSIC,
        )
        val zhPayload = """
            {"paragraphs":[{"layer":"book","sentences":[
                {"text":"能够言说的道并非恒常之道。","anchors":["S1"],"quote":"$zhEvidence"},
                {"text":"月球表面有一座歌剧院。","anchors":["S1"],"quote":"$zhEvidence"}
            ]}],"boundary":null}
        """.trimIndent()
        val r3 = assertNotNull(checkedStancePayload(zhPayload, zhPack, BookStance.CLASSIC))
        val kept3 = r3.layers.filterIsInstance<CheckedStanceLayer.Book>().flatMap { it.sentences }
        assertEquals(1, kept3.size)
        assertEquals("能够言说的道并非恒常之道。", kept3.single().text)
        assertEquals(1, r3.droppedSentences)

        // 4. 英文陈述共用英文真实引文
        val enPayload = """
            {"paragraphs":[{"layer":"book","sentences":[
                {"text":"Only pears are grown inside the orchard.","anchors":["S1"],"quote":"$evidence"},
                {"text":"An opera house stands on the lunar surface.","anchors":["S1"],"quote":"$evidence"}
            ]}],"boundary":null}
        """.trimIndent()
        val r4 = assertNotNull(checkedStancePayload(enPayload, pack, BookStance.KNOWLEDGE))
        val kept4 = r4.layers.filterIsInstance<CheckedStanceLayer.Book>().flatMap { it.sentences }
        assertEquals(1, kept4.size)
        assertEquals("Only pears are grown inside the orchard.", kept4.single().text)
        assertEquals(1, r4.droppedSentences)
    }

    /**
     * 反例 2（TON-1976 §3）：小说书在 knowledge 立场下，模型给了超过有效进度的出处：
     * 这一句被丢、越界记 1。
     */
    @Test
    fun fictionInKnowledgeStanceDropsOutOfRangeCitationAndCountsViolation() {
        val bookHash = "b".repeat(64)
        val pack = StanceSourcePack.fromSourcePack(
            SourcePack(
                listOf(
                    SourcePage("$bookHash:p5:0", 5, "She is tolerable, but not handsome enough to tempt me."),
                    SourcePage("$bookHash:p90:0", 90, "Elizabeth and Darcy finally married at the end of the novel."),
                ),
            ),
            BookStance.KNOWLEDGE,
        )
        val payload = """
            {
              "paragraphs": [
                {
                  "layer": "book",
                  "sentences": [
                    {"text": "达西最初觉得她只是勉强过得去。", "anchors": ["S1"], "quote": "She is tolerable"},
                    {"text": "两人最终在结尾结婚。", "anchors": ["S2"], "quote": "Elizabeth and Darcy finally married"}
                  ]
                }
              ],
              "boundary": null
            }
        """.trimIndent()
        val res = assertNotNull(
            checkedStancePayload(
                payload = payload,
                pack = pack,
                stance = BookStance.KNOWLEDGE,
                guards = setOf(BookStanceGuard.FICTION),
                effectiveReadThroughPage = 5,
            ),
        )
        assertEquals(1, res.outOfRange)
        assertEquals(1, res.droppedSentences)
        val bookLayer = res.layers.single() as CheckedStanceLayer.Book
        assertEquals(1, bookLayer.sentences.size)
        assertEquals(5, bookLayer.sentences.single().citations.single().pdfPage)
    }

    /**
     * 反例 3（TON-1976 §3）：小说书没带有效进度（没带、非整数、超出页数）：
     * 发出去的块只有选段那一页。
     */
    @Test
    fun fictionWithoutValidReadThroughPageSendsOnlySelectedPage() = runBlocking {
        val bookHash = "c".repeat(64)
        val pages = listOf(
            SourcePage("$bookHash:p1:0", 1, "Chapter one opens in Hertfordshire before the ball."),
            SourcePage("$bookHash:p2:0", 2, "She is tolerable, but not handsome enough to tempt me."),
            SourcePage("$bookHash:p3:0", 3, "Later spoiler chapter reveals the final marriage."),
        )
        val invalidProgressCases = listOf<Pair<String, kotlinx.serialization.json.JsonElement?>>(
            "missing" to null,
            "string" to JsonPrimitive("2"),
            "zero" to JsonPrimitive(0),
            "exceeds" to JsonPrimitive(500),
        )
        for ((caseName, badProgress) in invalidProgressCases) {
            Harness().use { h ->
                val modelJson = """{"paragraphs":[{"layer":"book","sentences":[{"text":"达西认为她只是勉强过得去。","anchors":["S1"],"quote":"She is tolerable"}]}],"boundary":null}"""
                h.stub.stream(1, modelJson, StubTutor.terminal("complete"), StubTutor.DONE)
                val fields = mutableListOf<Pair<String, Any?>>(
                    "requestId" to "aa11",
                    "kind" to "ask",
                    "question" to "达西态度如何？",
                    "stance" to "knowledge",
                    "guards" to JsonArray(listOf(JsonPrimitive("fiction"))),
                    "bookPages" to 100,
                    "sectionContext" to json("location" to "Chapter 1"),
                )
                if (badProgress != null) fields += "readThroughPage" to badProgress
                val reply = h.reader.explain(
                    device = "d",
                    course = "library",
                    input = json(*fields.toTypedArray()),
                    quote = "She is tolerable",
                    pages = pages,
                    scope = VisibleScope.learner(),
                )
                assertEquals(1, h.stub.requests, caseName)
                val systemPrompt = Json.parseToJsonElement(h.stub.requestBodies.single()).jsonObject
                    .getValue("messages").jsonArray
                    .single { it.jsonObject.str("role") == "system" }.jsonObject.str("content")
                assertTrue(systemPrompt.contains("She is tolerable"), "$caseName must include selected page")
                assertFalse(systemPrompt.contains("Chapter one opens"), "$caseName must drop earlier page when progress missing")
                assertFalse(systemPrompt.contains("Later spoiler chapter"), "$caseName must drop later page when progress missing")
                assertEquals(0, reply.getValue("gate").jsonObject.getValue("outOfRange").jsonPrimitive.int, caseName)
            }
        }
    }

    /**
     * 反例 4（TON-1976 §3 / TON-1982 改写）：玄学书在 knowledge 立场下问「该不该买股票」，
     * 模型桩返回两句带合法 anchor 的 book（一句仅解释原文，一句给读者具体投资建议）及一段 outside：
     * 期望第一行是高危提示行，只剩解释原文的句子，具体建议和 outside 被去掉，boundary="high_risk"，没有拦截卡。
     */
    @Test
    fun esotericBookInKnowledgeStanceAnswersDirectlyWithHighRiskNoticeAndDropsAdvice() = runBlocking {
        val bookHash = "d".repeat(64)
        val pages = listOf(SourcePage("$bookHash:p1:0", 1, "九二：見龍在田，利見大人。九五：飛龍在天，利見大人。"))
        val modelJson = """
            {
              "paragraphs": [
                {
                  "layer": "book",
                  "sentences": [
                    {"text": "原文写九五为飞龙在天，利见大人。", "anchors": ["S1"], "quote": "九五：飛龍在天，利見大人。"},
                    {"text": "原文写九五为飞龙在天，现在适合买入股票。", "anchors": ["S1"], "quote": "九五：飛龍在天，利見大人。"}
                  ]
                },
                {
                  "layer": "outside",
                  "kind": "今人解读",
                  "text": "股票投资应当根据基本面与个人风险承受能力判断。"
                }
              ],
              "boundary": "high_risk"
            }
        """.trimIndent()
        Harness().use { h ->
            h.stub.stream(1, modelJson, StubTutor.terminal("complete"), StubTutor.DONE)
            val reply = h.reader.explain(
                device = "d",
                course = "library",
                input = json(
                    "requestId" to "ee11",
                    "kind" to "ask",
                    "question" to "九五是最好的一爻，我该现在买股票吗？",
                    "stance" to "knowledge",
                    "guards" to JsonArray(listOf(JsonPrimitive("esoteric"))),
                ),
                quote = "見龍在田",
                pages = pages,
                scope = VisibleScope.learner(),
            )
            assertEquals(1, h.stub.requests, "高危直接回答应照常调模型解释书内说法")
            val systemPrompt = Json.parseToJsonElement(h.stub.requestBodies.single()).jsonObject
                .getValue("messages").jsonArray
                .single { it.jsonObject.str("role") == "system" }.jsonObject.str("content")
            assertTrue(systemPrompt.contains("只讲书里这一处怎么说，不替读者做这个决定"))
            assertEquals("knowledge", reply.str("stance"))
            assertTrue(reply.getValue("modelCalled").jsonPrimitive.boolean)
            val boundaryEl = reply.getValue("boundary")
            assertTrue(boundaryEl is JsonPrimitive && boundaryEl.isString, "boundary 必须是字符串 high_risk，不能是拦截卡对象")
            assertEquals(BookStanceCopy.HIGH_RISK_BOUNDARY, boundaryEl.content)
            assertEquals(BookStanceCopy.HIGH_RISK_NOTICE, reply.str("highRiskNotice"))
            assertEquals(BookStanceCopy.HIGH_RISK_NOTICE, reply.str("text").lineSequence().first())
            assertEquals(JsonNull, reply["systemNotice"], "knowledge 立场不应出体系提示行")
            assertEquals(JsonNull, reply["noBookNotice"])
            val layers = reply.getValue("layers").jsonArray
            assertEquals(1, layers.size, "outside 必须去掉，只剩 book 层")
            val bookLayer = layers.single().jsonObject
            assertEquals("book", bookLayer.str("layer"))
            val sents = bookLayer.getValue("sentences").jsonArray
            assertEquals(1, sents.size, "带合法 anchor 的具体投资建议句也必须丢弃")
            assertEquals("原文写九五为飞龙在天，利见大人。", sents.single().jsonObject.str("text"))
            assertEquals(1, reply.getValue("gate").jsonObject.getValue("droppedSentences").jsonPrimitive.int)
        }
    }

    /**
     * TON-1982 新增单测：未命中本地关键词但模型自己返回 boundary 时，同样按高危直接回答处理：
     * 第一行高危提示行，只剩解释原文的句子，具体建议与 outside 被去掉，boundary="high_risk"，没有拦截卡。
     */
    @Test
    fun esotericBookModelSelfBoundaryAppliesSameHighRiskDirectAnswerGate() = runBlocking {
        val bookHash = "d".repeat(64)
        val pages = listOf(SourcePage("$bookHash:p1:0", 1, "九二：見龍在田，利見大人。九五：飛龍在天，利見大人。"))
        val question = "九五对我的个人资产抉择有什么启示？"
        assertFalse(EsotericRiskKeywords.matches(question), "此用例需验证未命中本地词表但模型返回 boundary 的路径")
        val modelJson = """
            {
              "paragraphs": [
                {
                  "layer": "book",
                  "sentences": [
                    {"text": "原文写九五为飞龙在天，利见大人。", "anchors": ["S1"], "quote": "九五：飛龍在天，利見大人。"},
                    {"text": "原文写九五利见大人，建议趁机加仓买入。", "anchors": ["S1"], "quote": "九五：飛龍在天，利見大人。"}
                  ]
                },
                {
                  "layer": "outside",
                  "kind": "今人解读",
                  "text": "书外说明应当被高危门禁移除。"
                }
              ],
              "boundary": "high_risk"
            }
        """.trimIndent()
        Harness().use { h ->
            h.stub.stream(1, modelJson, StubTutor.terminal("complete"), StubTutor.DONE)
            val reply = h.reader.explain(
                device = "d",
                course = "library",
                input = json(
                    "requestId" to "ee12",
                    "kind" to "ask",
                    "question" to question,
                    "stance" to "knowledge",
                    "guards" to JsonArray(listOf(JsonPrimitive("esoteric"))),
                ),
                quote = "見龍在田",
                pages = pages,
                scope = VisibleScope.learner(),
            )
            assertEquals(1, h.stub.requests)
            val boundaryEl = reply.getValue("boundary")
            assertTrue(boundaryEl is JsonPrimitive && boundaryEl.isString)
            assertEquals(BookStanceCopy.HIGH_RISK_BOUNDARY, boundaryEl.content)
            assertEquals(BookStanceCopy.HIGH_RISK_NOTICE, reply.str("highRiskNotice"))
            assertEquals(BookStanceCopy.HIGH_RISK_NOTICE, reply.str("text").lineSequence().first())
            assertEquals(JsonNull, reply["systemNotice"])
            val layers = reply.getValue("layers").jsonArray
            assertEquals(1, layers.size)
            val sents = layers.single().jsonObject.getValue("sentences").jsonArray
            assertEquals(1, sents.size)
            assertEquals("原文写九五为飞龙在天，利见大人。", sents.single().jsonObject.str("text"))
            assertEquals(1, reply.getValue("gate").jsonObject.getValue("droppedSentences").jsonPrimitive.int)
        }
    }

    /**
     * TON-1982 安全加严单测：高危模式下，无法判定为仅解释书中说法的句子以及医疗/法律具体建议句一律丢弃；
     * 一句不剩时补兜底句；玄学立场下同时保留体系提示行。
     */
    @Test
    fun esotericHighRiskDropsUnclassifiableAndMedicalLegalAdviceAndFallsBackWhenEmpty() {
        val bookHash = "d".repeat(64)
        val kybalionText = "The Principle of Correspondence says As above, so below; as below, so above."
        val pack = StanceSourcePack.fromSourcePack(
            SourcePack(listOf(SourcePage("$bookHash:p1:0", 1, kybalionText))),
            BookStance.ESOTERIC,
        )
        val payload = """
            {
              "paragraphs": [
                {
                  "layer": "book",
                  "sentences": [
                    {"text": "原文讲对应原则联系上下，所以可以停掉降压药靠心念调理。", "anchors": ["S1"], "quote": "As above, so below"},
                    {"text": "原文讲对应原则，建议立刻起诉对方违约。", "anchors": ["S1"], "quote": "Principle of Correspondence"},
                    {"text": "上下相应带来极佳的运势和机会。", "anchors": ["S1"], "quote": "As above, so below"}
                  ]
                },
                {
                  "layer": "outside",
                  "kind": "今人解读",
                  "text": "书外内容在高危模式下必须去掉。"
                }
              ],
              "boundary": "high_risk"
            }
        """.trimIndent()
        val res = assertNotNull(
            checkedStancePayload(
                payload = payload,
                pack = pack,
                stance = BookStance.ESOTERIC,
                guards = setOf(BookStanceGuard.ESOTERIC),
                forceHighRisk = true,
            ),
        )
        assertEquals(BookStanceCopy.HIGH_RISK_BOUNDARY, res.boundary)
        assertEquals(BookStanceCopy.HIGH_RISK_NOTICE, res.highRiskNotice)
        assertEquals(BookStanceCopy.ESOTERIC_SYSTEM_NOTICE, res.systemNotice)
        assertEquals(BookStanceCopy.NO_BOOK_DIRECT, res.noBookNotice)
        assertTrue(res.layers.isEmpty(), "医疗/法律建议句、无法判定句及 outside 必须全部去掉")
        assertEquals(3, res.droppedSentences)
        val jsonOut = res.toResponseJson(10)
        val lines = jsonOut.str("text").lines()
        assertEquals(BookStanceCopy.HIGH_RISK_NOTICE, lines[0])
        assertEquals(BookStanceCopy.ESOTERIC_SYSTEM_NOTICE, lines[1])
        assertEquals(BookStanceCopy.NO_BOOK_DIRECT, lines[2])
    }

    /**
     * QA-Astra 退回回归（TON-1982）：同一个有效引文下：
     * 1. 仅解释引文中出现的「吉凶」一词的句子必须保留；
     * 2. 前半句挂有效引文、后半分句转为现实财务/操作指令或脱离引文无法判定的句子必须整句丢弃；
     * 3. 覆盖直接门禁调用、本地关键词命中入口、以及模型主动返回 boundary 入口。
     */
    @Test
    fun highRiskKeepsGroundedSymbolGlossAndDropsAppendedRealWorldInstructionAcrossBothEntries() = runBlocking {
        val bookHash = "d".repeat(64)
        val quote = "青鸟是本章用来解释吉凶的一种意象。"
        val pages = listOf(SourcePage("$bookHash:p1:0", 1, quote))
        val pack = StanceSourcePack.fromSourcePack(SourcePack(pages), BookStance.ESOTERIC)

        fun retainedDirect(text: String): Int {
            val payload = """{"paragraphs":[{"layer":"book","sentences":[{"text":"$text","anchors":["S1"],"quote":"$quote"}]}],"boundary":"high_risk"}"""
            val res = assertNotNull(
                checkedStancePayload(
                    payload = payload,
                    pack = pack,
                    stance = BookStance.ESOTERIC,
                    guards = setOf(BookStanceGuard.ESOTERIC),
                    forceHighRisk = true,
                ),
            )
            return res.layers.filterIsInstance<CheckedStanceLayer.Book>().sumOf { it.sentences.size }
        }

        // 1. 附件两例直接验证 + 脱离引文的现实动作分句（无金融关键词亦须丢弃）
        assertEquals(1, retainedDirect("本章将青鸟作为说明吉凶的意象。"), "有原文依据的「吉凶」术语解释必须保留")
        assertEquals(0, retainedDirect("本章将青鸟写作意象，明早把全部积蓄换成黄金。"), "附带现实投资指令的分句必须丢弃整句")
        assertEquals(0, retainedDirect("本章将青鸟写作意象，傍晚去城南办件私事。"), "脱离引文无法判定的分句必须丢弃整句")

        // 2. 端到端覆盖「本地关键词命中」与「模型主动标记 boundary」两条入口
        val modelJson = """
            {
              "paragraphs": [
                {
                  "layer": "book",
                  "sentences": [
                    {"text": "本章将青鸟作为说明吉凶的意象。", "anchors": ["S1"], "quote": "$quote"},
                    {"text": "本章将青鸟写作意象，明早把全部积蓄换成黄金。", "anchors": ["S1"], "quote": "$quote"}
                  ]
                },
                {
                  "layer": "outside",
                  "kind": "今人解读",
                  "text": "书外内容在高危回答中必须去掉。"
                }
              ],
              "boundary": "high_risk"
            }
        """.trimIndent()

        val entryCases = listOf(
            "keyword_hit" to "青鸟代表吉凶，我该现在买股票吗？",
            "model_self_boundary" to "青鸟在这一章代表什么，我个人接下来该怎么安排？",
        )
        assertTrue(EsotericRiskKeywords.matches(entryCases[0].second))
        assertFalse(EsotericRiskKeywords.matches(entryCases[1].second))

        for ((caseName, question) in entryCases) {
            Harness().use { h ->
                h.stub.stream(1, modelJson, StubTutor.terminal("complete"), StubTutor.DONE)
                val reply = h.reader.explain(
                    device = "d",
                    course = "library",
                    input = json(
                        "requestId" to "ee21",
                        "kind" to "ask",
                        "question" to question,
                        "stance" to "esoteric",
                        "guards" to JsonArray(listOf(JsonPrimitive("esoteric"))),
                    ),
                    quote = "青鸟",
                    pages = pages,
                    scope = VisibleScope.learner(),
                )
                assertEquals(1, h.stub.requests, caseName)
                assertEquals(BookStanceCopy.HIGH_RISK_BOUNDARY, reply.str("boundary"), caseName)
                assertEquals(BookStanceCopy.HIGH_RISK_NOTICE, reply.str("highRiskNotice"), caseName)
                assertEquals(BookStanceCopy.HIGH_RISK_NOTICE, reply.str("text").lineSequence().first(), caseName)
                assertEquals(BookStanceCopy.ESOTERIC_SYSTEM_NOTICE, reply.str("systemNotice"), caseName)
                val layers = reply.getValue("layers").jsonArray
                assertEquals(1, layers.size, "$caseName: outside 必须去掉，只剩 book 层")
                val sents = layers.single().jsonObject.getValue("sentences").jsonArray
                assertEquals(1, sents.size, "$caseName: 仅保留术语解释，附带投资指令句必须丢弃")
                assertEquals("本章将青鸟作为说明吉凶的意象。", sents.single().jsonObject.str("text"), caseName)
                assertEquals(1, reply.getValue("gate").jsonObject.getValue("droppedSentences").jsonPrimitive.int, caseName)
            }
        }
    }

    /**
     * 第二次退回范围裁定第 4 条（TON-1982）：100% 白名单全覆盖反例：
     * 1. 中文对中文：同一出处下「书中把云舟入港说成航运行情转好」丢句（非原句实字），仅留原句字词组成的解释；
     * 2. 英文对英文：英文原句配「the book says this means buy shipping stocks」丢句，仅留原句词干与框架词组成的解释；
     * 3. 中文句对英文原句（Kybalion 场景）：词表映射不到的中文实字丢句，全丢后回落到兜底句并保留高危提示行与体系提示行。
     */
    @Test
    fun highRiskAllowlistDropsExtraChineseAndEnglishUnbackedClaimsAndCrossScriptUnmapped() {
        // 1. 中文反例：「书中把云舟入港说成航运行情转好」必须丢弃
        val zhQuote = "云舟入港是航程结束的意象。"
        val zhPack = StanceSourcePack.fromSourcePack(
            SourcePack(listOf(SourcePage("c".repeat(64) + ":p1:0", 1, zhQuote))),
            BookStance.KNOWLEDGE,
        )
        val zhPayload = """
            {
              "paragraphs": [{
                "layer": "book",
                "sentences": [
                  {"text": "书中用云舟入港表示航程结束。", "anchors": ["S1"], "quote": "$zhQuote"},
                  {"text": "书中把云舟入港说成航运行情转好。", "anchors": ["S1"], "quote": "$zhQuote"}
                ]
              }],
              "boundary": "high_risk"
            }
        """.trimIndent()
        val zhRes = assertNotNull(
            checkedStancePayload(
                payload = zhPayload,
                pack = zhPack,
                stance = BookStance.KNOWLEDGE,
                guards = setOf(BookStanceGuard.ESOTERIC),
                forceHighRisk = true,
            ),
        )
        val zhKept = zhRes.layers.filterIsInstance<CheckedStanceLayer.Book>().flatMap { it.sentences }
        assertEquals(1, zhKept.size)
        assertEquals("书中用云舟入港表示航程结束。", zhKept.single().text)
        assertEquals(1, zhRes.droppedSentences)

        // 2. 英文反例：英文原句配「the book says this means buy shipping stocks」必须丢弃（含共享原句词与无黑名单词的英文句）
        val enQuote = "The cloud boat entering port is an image of a voyage ending."
        val enPack = StanceSourcePack.fromSourcePack(
            SourcePack(listOf(SourcePage("e".repeat(64) + ":p1:0", 1, enQuote))),
            BookStance.KNOWLEDGE,
        )
        val enPayload = """
            {
              "paragraphs": [{
                "layer": "book",
                "sentences": [
                  {"text": "The book says the cloud boat entering port represents an image of a voyage ending.", "anchors": ["S1"], "quote": "$enQuote"},
                  {"text": "the book says this means buy shipping stocks", "anchors": ["S1"], "quote": "$enQuote"},
                  {"text": "the book says the cloud boat means buy shipping stocks", "anchors": ["S1"], "quote": "$enQuote"},
                  {"text": "The book says the cloud boat represents a lucky voyage ahead.", "anchors": ["S1"], "quote": "$enQuote"}
                ]
              }],
              "boundary": "high_risk"
            }
        """.trimIndent()
        val enRes = assertNotNull(
            checkedStancePayload(
                payload = enPayload,
                pack = enPack,
                stance = BookStance.KNOWLEDGE,
                guards = setOf(BookStanceGuard.ESOTERIC),
                forceHighRisk = true,
            ),
        )
        val enKept = enRes.layers.filterIsInstance<CheckedStanceLayer.Book>().flatMap { it.sentences }
        assertEquals(1, enKept.size)
        assertEquals("The book says the cloud boat entering port represents an image of a voyage ending.", enKept.single().text)
        assertEquals(3, enRes.droppedSentences)

        // 3. 中文句对英文原句（Kybalion）：词表映射不到即丢句，全丢后回落到兜底句并保留高危提示行与体系提示行
        val kyQuote = "The Principle of Correspondence says As above, so below."
        val kyPack = StanceSourcePack.fromSourcePack(
            SourcePack(listOf(SourcePage("f".repeat(64) + ":p1:0", 1, kyQuote))),
            BookStance.ESOTERIC,
        )
        val kyPayload = """
            {
              "paragraphs": [{
                "layer": "book",
                "sentences": [
                  {"text": "书里讲对应原则能让身体自动康复。", "anchors": ["S1"], "quote": "$kyQuote"}
                ]
              }],
              "boundary": "high_risk"
            }
        """.trimIndent()
        val kyRes = assertNotNull(
            checkedStancePayload(
                payload = kyPayload,
                pack = kyPack,
                stance = BookStance.ESOTERIC,
                guards = setOf(BookStanceGuard.ESOTERIC),
                forceHighRisk = true,
            ),
        )
        assertTrue(kyRes.layers.isEmpty(), "词表映射不到的跨语种高危句必须丢弃")
        assertEquals(1, kyRes.droppedSentences)
        assertEquals(BookStanceCopy.HIGH_RISK_BOUNDARY, kyRes.boundary)
        assertEquals(BookStanceCopy.HIGH_RISK_NOTICE, kyRes.highRiskNotice)
        assertEquals(BookStanceCopy.ESOTERIC_SYSTEM_NOTICE, kyRes.systemNotice)
        assertEquals(BookStanceCopy.NO_BOOK_DIRECT, kyRes.noBookNotice)
    }

    /**
     * 反例 5（TON-1976 §3）：请求不带 stance，走旧路径，返回结构和现在一样。
     */
    @Test
    fun requestWithoutStanceTakesLegacyPathUnchanged() = runBlocking {
        val bookHash = "e".repeat(64)
        val pages = listOf(SourcePage("$bookHash:p1:0", 1, "Synthetic pears grow in the orchard."))
        Harness().use { h ->
            val legacyModel = """{"paragraphs":[{"text":"“Synthetic pears”长在果园里。","anchors":["S1"]}]}"""
            h.stub.stream(1, legacyModel, StubTutor.terminal("complete"), StubTutor.DONE)
            val reply = h.reader.explain(
                device = "d",
                course = "library",
                input = json(
                    "requestId" to "ff11",
                    "kind" to "explain",
                ),
                quote = "Synthetic pears",
                pages = pages,
                scope = VisibleScope.learner(),
            )
            assertEquals(1, h.stub.requests)
            assertNull(reply["stance"], "旧路径不返回 stance")
            assertNull(reply["layers"], "旧路径不返回 layers")
            assertNull(reply["boundary"], "旧路径不返回 boundary")
            assertTrue(reply.str("text").contains("Synthetic pears"))
            assertEquals(1, reply.getValue("sources").jsonArray.size)
        }
    }

    /**
     * 古籍分块与规则 5：王弼本《道德经》切分正文与〈〉注文，标签为「原文」与「注 · 王弼」；
     * 同句混引正文和注文时丢句。
     */
    @Test
    fun classicSplitsMainAndWangBiNoteAndDropsMixedAnchorSentence() {
        val bookHash = "f".repeat(64)
        val pageText = "道可道，非常道。\n〈可道之道，可名之名，指事造形，非其常也。〉"
        val pack = StanceSourcePack.fromSourcePack(
            SourcePack(listOf(SourcePage("$bookHash:p1:100", 1, pageText))),
            BookStance.CLASSIC,
            annotationContext = "注者/按语归属：晉 王弼注",
        )
        assertEquals(2, pack.blocks.size)
        val payload = """
            {
              "paragraphs": [
                {
                  "layer": "book",
                  "sentences": [
                    {"text": "老子开篇指出能够言说的道并非恒常之道。", "anchors": ["S1"], "quote": "道可道，非常道"},
                    {"text": "王弼注认为可言说可命名的道落于具体形事而非恒常。", "anchors": ["S2"], "quote": "指事造形，非其常也"},
                    {"text": "混引正文与注文的一句。", "anchors": ["S1", "S2"]}
                  ]
                },
                {"layer": "outside", "kind": "今人解读", "text": "现代学者常将此章与语言哲学并读。"}
              ],
              "boundary": null
            }
        """.trimIndent()
        val res = assertNotNull(checkedStancePayload(payload, pack, BookStance.CLASSIC))
        assertEquals(1, res.droppedSentences)
        assertEquals(3, res.layers.size)
        val mainLayer = res.layers[0] as CheckedStanceLayer.Book
        val noteLayer = res.layers[1] as CheckedStanceLayer.Book
        val outLayer = res.layers[2] as CheckedStanceLayer.Outside
        assertEquals("原文", mainLayer.label)
        assertEquals("注 · 王弼", noteLayer.label)
        assertEquals("书外 · 未对照原文 · 今人解读", outLayer.label)
        val noteCite = noteLayer.sentences.single().citations.single()
        assertEquals("〈可道之道，可名之名，指事造形，非其常也。〉", noteCite.quote)
        val relStart = noteCite.startUtf16 - 100
        val relEnd = noteCite.endUtf16 - 100
        assertEquals(noteCite.quote, pageText.substring(relStart, relEnd))
    }

    /**
     * 裁定回归：模型给出半句 quote 时，服务端必须向两侧扩到本块内的整句原句（支持跨硬折行，
     * 不跨空行/块界；扩后超 400 UTF-16 时保留原区间；同块多句用 quotes 数组分别引并各自扩到整句）。
     */
    @Test
    fun halfSentenceQuoteExpandsToFullSentenceWithinBlockAndCapsAt400Utf16() {
        val bookHash = "9".repeat(64)
        // 1. 英文多句块含约 70 字符硬折行与缩写 Mr.：给出半句 quote，必须扩到整句（不截在硬折行，也不吞上下句）
        val enSent1 = "Mr. Franklin made a little book for each virtue."
        val enSent2 = "He ruled each page with red ink into seven columns,\nmarking each day of the week with its initial letter."
        val enSent3 = "Then he crossed those columns with thirteen red lines."
        val enBlock = "$enSent1 $enSent2 $enSent3"
        val enPack = StanceSourcePack.fromSourcePack(
            SourcePack(listOf(SourcePage("$bookHash:p47:100", 47, enBlock))),
            BookStance.LIFE,
        )
        val enPayload = """
            {"paragraphs":[{"layer":"book","sentences":[
                {"text":"他用红墨水画了七列，并用首字母标出星期几。","anchors":["S1"],"quote":"red ink into seven columns"},
                {"text":"他先做了小册子，再用十三条红线横穿竖列。","anchors":["S1"],"quotes":["made a little book","thirteen red lines"]}
            ]}],"boundary":null}
        """.trimIndent()
        val enRes = assertNotNull(checkedStancePayload(enPayload, enPack, BookStance.LIFE))
        val enSents = (enRes.layers.single() as CheckedStanceLayer.Book).sentences
        assertEquals(2, enSents.size)
        val c0 = enSents[0].citations.single()
        assertEquals(enSent2, c0.quote)
        assertEquals(100 + enBlock.indexOf(enSent2), c0.startUtf16)
        assertEquals(100 + enBlock.indexOf(enSent2) + enSent2.length, c0.endUtf16)
        // 同块 quotes 数组两条半句各自扩为整句
        assertEquals(listOf(enSent1, enSent3), enSents[1].citations.map { it.quote })

        // 2. 中文多句块含空行标题：半句扩到所在整句，不跨空行，也不吞下一句
        val zhSent = "果园东边只种晚熟的秋梨，每年十月采摘。"
        val zhBlock = "第一章\n\n${zhSent}西边种着樱桃。"
        val zhPack = StanceSourcePack.fromSourcePack(
            SourcePack(listOf(SourcePage("$bookHash:p2:50", 2, zhBlock))),
            BookStance.KNOWLEDGE,
        )
        val zhPayload = """
            {"paragraphs":[{"layer":"book","sentences":[
                {"text":"果园东边种晚熟秋梨并在十月采摘。","anchors":["S1"],"quote":"晚熟的秋梨"}
            ]}],"boundary":null}
        """.trimIndent()
        val zhRes = assertNotNull(checkedStancePayload(zhPayload, zhPack, BookStance.KNOWLEDGE))
        val zhCite = (zhRes.layers.single() as CheckedStanceLayer.Book).sentences.single().citations.single()
        assertEquals(zhSent, zhCite.quote)
        assertEquals(50 + zhBlock.indexOf(zhSent), zhCite.startUtf16)
        assertEquals(50 + zhBlock.indexOf(zhSent) + zhSent.length, zhCite.endUtf16)

        // 3. 扩后超过 400 UTF-16 时保留原定位区间，不把整段巨长句全高亮
        val longSent = "Only pears grow in this orchard " + "and every autumn visitors walk along the quiet path ".repeat(8) + "until dusk."
        assertTrue(longSent.length > 400)
        val longPack = StanceSourcePack.fromSourcePack(
            SourcePack(listOf(SourcePage("$bookHash:p3:0", 3, longSent))),
            BookStance.KNOWLEDGE,
        )
        val shortQuote = "Only pears grow in this orchard"
        val longPayload = """
            {"paragraphs":[{"layer":"book","sentences":[
                {"text":"果园里只种梨。","anchors":["S1"],"quote":"$shortQuote"}
            ]}],"boundary":null}
        """.trimIndent()
        val longRes = assertNotNull(checkedStancePayload(longPayload, longPack, BookStance.KNOWLEDGE))
        val longCite = (longRes.layers.single() as CheckedStanceLayer.Book).sentences.single().citations.single()
        assertEquals(shortQuote, longCite.quote)
        assertEquals(0, longCite.startUtf16)
        assertEquals(shortQuote.length, longCite.endUtf16)
    }

    /**
     * 裁定回归（E1 / 古籍短引文扩整句）：古籍与繁文典籍块里只给一段短 quote（5 字以上），
     * 服务端必须将区间与 quote 扩到所在整句原句，且该句通过全部门禁并保留。
     */
    @Test
    fun classicalShortQuoteExpandsToFullSentenceAndKeepsSentence() {
        val bookHash = "8".repeat(64)
        val firstSent = "初九：潛龍勿用。"
        val secondSent = "象曰：潛龍勿用，陽在下也。"
        val zhouyiBlock = "$firstSent$secondSent"
        val esotericPack = StanceSourcePack.fromSourcePack(
            SourcePack(listOf(SourcePage("$bookHash:p2:40", 2, zhouyiBlock))),
            BookStance.ESOTERIC,
        )
        val esotericPayload = """
            {"paragraphs":[{"layer":"book","sentences":[
                {"text":"象传解释初九潜龙勿用是因为阳气尚处在最下方。","anchors":["S1"],"quote":"潛龍勿用，陽在下"}
            ]}],"boundary":null}
        """.trimIndent()
        val esotericRes = assertNotNull(checkedStancePayload(esotericPayload, esotericPack, BookStance.ESOTERIC))
        assertEquals(0, esotericRes.droppedSentences)
        val esotericSent = (esotericRes.layers.single() as CheckedStanceLayer.Book).sentences.single()
        val esotericCite = esotericSent.citations.single()
        assertEquals(secondSent, esotericCite.quote)
        assertEquals(40 + zhouyiBlock.indexOf(secondSent), esotericCite.startUtf16)
        assertEquals(40 + zhouyiBlock.indexOf(secondSent) + secondSent.length, esotericCite.endUtf16)

        val lunyuSent1 = "子曰：學而時習之，不亦說乎？"
        val lunyuSent2 = "有朋自遠方來，不亦樂乎？"
        val lunyuBlock = "$lunyuSent1$lunyuSent2"
        val classicPack = StanceSourcePack.fromSourcePack(
            SourcePack(listOf(SourcePage("$bookHash:p3:10", 3, lunyuBlock))),
            BookStance.CLASSIC,
        )
        val classicPayload = """
            {"paragraphs":[{"layer":"book","sentences":[
                {"text":"原文说朋友从远方到来也是令人快乐的事。","anchors":["S1"],"quote":"有朋自遠方來"}
            ]}],"boundary":null}
        """.trimIndent()
        val classicRes = assertNotNull(checkedStancePayload(classicPayload, classicPack, BookStance.CLASSIC))
        assertEquals(0, classicRes.droppedSentences)
        val classicLayer = classicRes.layers.single() as CheckedStanceLayer.Book
        assertEquals("原文", classicLayer.label)
        val classicCite = classicLayer.sentences.single().citations.single()
        assertEquals(lunyuSent2, classicCite.quote)
        assertEquals(10 + lunyuBlock.indexOf(lunyuSent2), classicCite.startUtf16)
        assertEquals(10 + lunyuBlock.indexOf(lunyuSent2) + lunyuSent2.length, classicCite.endUtf16)
    }

    /**
     * QA 安全回归：丢句日志只能输出本次来源包 `pack.byAnchor` 中已知的 anchor 编号，
     * 形状 / 句数 / 非法 anchor 三处分支中模型填入的未知字符串必须统一记为 `invalid`，绝不进入日志。
     */
    @Test
    fun dropLogNeverEmitsUntrustedModelAnchorStrings() {
        val bookHash = "7".repeat(64)
        val canary = "SYNTHETIC_SECRET_BOOK_TEXT_CANARY_98765"
        val pack = StanceSourcePack.fromSourcePack(
            SourcePack(listOf(SourcePage("$bookHash:p1:0", 1, "Only pears grow in this orchard."))),
            BookStance.KNOWLEDGE,
        )
        val payload = """
            {"paragraphs":[{"layer":"book","sentences":[
                {"text":"含换行\n的非法形状。","anchors":["$canary","S1"]},
                {"text":"第一句。第二句超出单句限制。","anchors":["$canary"]},
                {"text":"果园里只种梨。","anchors":["S1","$canary"],"quote":"Only pears grow in this orchard."}
            ]}],"boundary":null}
        """.trimIndent()
        val capturedLogs = mutableListOf<String>()
        val res = assertNotNull(checkedStancePayload(payload, pack, BookStance.KNOWLEDGE, onDropLog = capturedLogs::add))
        assertEquals(3, res.droppedSentences)
        assertEquals(3, capturedLogs.size)
        assertEquals(
            listOf(
                "COACH stance.drop stance=knowledge reason=SHAPE anchors=invalid,S1",
                "COACH stance.drop stance=knowledge reason=SENTENCE_COUNT anchors=invalid",
                "COACH stance.drop stance=knowledge reason=ANCHOR anchors=S1,invalid",
            ),
            capturedLogs,
        )
        assertTrue(capturedLogs.none { canary in it }, "未知模型字符串绝不能出现在丢句日志中")

        // 同时验证默认 SLF4J 日志输出路径也不会泄露未知字符串
        val prevErr = System.err
        val errBuf = java.io.ByteArrayOutputStream()
        try {
            System.setErr(java.io.PrintStream(errBuf, true, Charsets.UTF_8))
            assertNotNull(checkedStancePayload(payload, pack, BookStance.KNOWLEDGE))
        } finally {
            System.setErr(prevErr)
        }
        val stderrText = errBuf.toString(Charsets.UTF_8)
        assertFalse(canary in stderrText, "默认服务端日志输出绝不能包含未知 anchor 字符串")
    }

    /**
     * 规则 2、3、4、8：结构违规整次拒绝（触发重试），包括推想含第一人称、书外带页码、只看原文带书外、超 5 段。
     */
    @Test
    fun structuralViolationsRejectWholePayloadForRetry() {
        val pack = StanceSourcePack.fromSourcePack(
            SourcePack(listOf(SourcePage("doc:p1:0", 1, "I went to the woods because I wished to live deliberately."))),
            BookStance.LIFE,
        )
        // 规则 4：人生推想出现第一人称「我」或 I/my
        for (badGuess in listOf(
            """{"paragraphs":[{"layer":"guess","text":"我觉得你应该辞职。"}],"boundary":null}""",
            """{"paragraphs":[{"layer":"guess","text":"In my view he would disagree."}],"boundary":null}""",
        )) {
            val reasons = mutableListOf<ReaderFormatReason>()
            assertNull(checkedStancePayload(badGuess, pack, BookStance.LIFE, onReject = reasons::add))
            assertEquals(listOf(ReaderFormatReason.STANCE_PERSON), reasons)
        }
        // 规则 3：非人生立场不允许 guess；只看原文不允许 outside
        assertNull(checkedStancePayload("""{"paragraphs":[{"layer":"guess","text":"按他推想会反对。"}],"boundary":null}""", pack, BookStance.KNOWLEDGE))
        assertNull(checkedStancePayload("""{"paragraphs":[{"layer":"outside","text":"书外背景。"}],"boundary":null}""", pack, BookStance.ORIGINAL))
        // 规则 2：outside 带页码或 anchors
        assertNull(checkedStancePayload("""{"paragraphs":[{"layer":"outside","text":"见第 12 页说明。"}],"boundary":null}""", pack, BookStance.KNOWLEDGE))
        assertNull(checkedStancePayload("""{"paragraphs":[{"layer":"outside","text":"书外说明。","anchors":["S1"]}],"boundary":null}""", pack, BookStance.KNOWLEDGE))
        // 规则 8：超过 5 段
        val six = (1..6).joinToString(",") { """{"layer":"outside","text":"第 $it 段说明。"}""" }
        assertNull(checkedStancePayload("""{"paragraphs":[$six],"boundary":null}""", pack, BookStance.KNOWLEDGE))
        // 合法人生回答含第三人称推想双标签
        val validLife = """{"paragraphs":[{"layer":"book","sentences":[{"text":"作者说明自己去林中是为了有意识地生活。","anchors":["S1"],"quote":"live deliberately"}]},{"layer":"guess","text":"按他书里的办法推想，他会建议先削减多余开销而不是盲目搬走。"}],"boundary":null}"""
        val ok = assertNotNull(checkedStancePayload(validLife, pack, BookStance.LIFE))
        assertEquals(2, ok.layers.size)
        assertEquals(BookStanceCopy.BOOK_LIFE, ok.layers[0].label)
        assertEquals(BookStanceCopy.GUESS_PREFIX, ok.layers[1].label)
        assertEquals(AnswerLayerKind.GUESS, ok.layers[1].layer)
    }

    @Test
    fun singleSentenceGateDropsMultiSentenceItemAndKeywordsAvoidFalsePositives() {
        assertEquals(1, StanceSentenceSplitter.countSentences("这一句在讲需求弹性。"))
        assertEquals(2, StanceSentenceSplitter.countSentences("第一句在这里。第二句塞在同一条里。"))
        assertEquals(1, StanceSentenceSplitter.countSentences("原文写到「學而時習之，不亦說乎？」用来强调温习。"))
        assertEquals(1, StanceSentenceSplitter.countSentences("价格为 3.14 美元，见 e.g. 表格。"))

        assertTrue(EsotericRiskKeywords.matches("九五是最好的一爻，我该现在买股票吗？"))
        assertTrue(EsotericRiskKeywords.matches("按这本书，我能不能不吃降压药，靠心念调过来？"))
        assertTrue(EsotericRiskKeywords.matches("Should I stop taking my medication and buy bitcoin?"))
        assertFalse(EsotericRiskKeywords.matches("我抽到乾卦，这个月会升职吗？"))
        assertFalse(EsotericRiskKeywords.matches("初九这一爻在这本书的体系里什么意思？"))
        assertFalse(EsotericRiskKeywords.matches("按两极原则，我下周心情会变好吗？"))
    }

    @Test
    fun streamingBodyProgressAcceptsStanceNullBoundaryAndNestedSentences() {
        val progress = ReaderBodyProgress(
            maxSize = 16_000,
            progressPaths = ReaderBodyProgress.STANCE_PROGRESS_PATHS,
            allowLiterals = true,
        )
        val payload = """{"paragraphs":[{"layer":"book","sentences":[{"text":"测试一句。","anchors":["S1"],"quote":"abc"}]}],"boundary":null}"""
        var sawProgress = false
        for (ch in payload) {
            if (progress.push(ch.toString())) sawProgress = true
        }
        assertFalse(progress.invalid, "Stance stream with null boundary must not fail: ${progress.invalidReason}")
        assertTrue(sawProgress)
    }
}
