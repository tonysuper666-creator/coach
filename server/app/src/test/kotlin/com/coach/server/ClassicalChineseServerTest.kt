package com.coach.server

import com.coach.domain.LedgerJson
import com.coach.domain.grade.ChapterOpenGate
import com.coach.domain.grade.ChapterOpenRawPoint
import com.coach.domain.grade.ChapterOpenSourceBlock
import com.coach.store.SourcePage
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClassicalChineseServerTest {
    @Test
    fun sourceCitationGateHandlesNestedCjkBookQuotes() {
        val page = SourcePage("book:p1:0", 1, "古人嘗言：「學者當記一作「日省其身」之語，不可須臾離也。」")
        val gate = SourceCitationGate(mapOf("S1" to page.text))
        val emitted = gate.push("古人強調「學者當記一作「日省其身」之語」【S1】。\n") + gate.finish(complete = true)
        assertTrue(emitted.any { "日省其身" in it }, "嵌套「...「...」...」引文应通过 SourceCitationGate: $emitted")
    }

    @Test
    fun serverSentenceCitationGateSupportsWrappedCjkAndTightParagraphWindow() {
        val page =
            "第一段僅述背景與年代。\n\n" +
                "第二段曰：\n「問學\n之道，\n貴在專精不貳，久行而有得。」\n\n" +
                "第三段論後世諸說流變。"
        val result = ServerSentenceCitationGate.verifyOrSnapAnchor(
            "問學之道，貴在專精不貳",
            page,
        )
        assertTrue(result.verified, "服务器引文门禁应兼容中文硬换行（即使仅两小句）")
        assertTrue(page.contains(result.canonicalAnchor), "返回锚点应在原页可定位: ${result.canonicalAnchor}")
    }

    @Test
    fun studyModeAnchorGateAndBookMapBatchSupportChineseAndOver60Chapters() {
        val pages = listOf(
            StudyPageInput(1, "一之一\n大道之行也，天下為公。選賢與能，講信修睦。\n故人不獨親其親，不獨子其子。"),
        )
        val candidates = StudyModeAnchorGate.extractCandidateSentences(pages, centerPage = 1)
        assertTrue(candidates.isNotEmpty(), "纯中文句不应被 ASCII token 阈值全部过滤")
        assertTrue(candidates.any { "大道之行也" in it.second })

        val normMap = mapOf(1 to pages.single().text)
        assertEquals(1, StudyModeAnchorGate.findExactPage("大道之行也，天下為公。", 1, normMap))

        val input81 = buildJsonObject {
            put("bookTitle", "玄言注本")
            put("detailLevel", "BRIEF")
            put("explainLang", "ZH")
            put(
                "chapters",
                buildJsonArray {
                    for (i in 1..81) {
                        add(
                            buildJsonObject {
                                put("chapterNum", i)
                                put("chapterTitle", "第${i}章")
                                put("pages", buildJsonArray {
                                    add(buildJsonObject {
                                        put("page", i)
                                        put("text", "第${i}章正文內容若干字，足以成句。")
                                    })
                                })
                            },
                        )
                    }
                },
            )
        }
        val parsed = StudyModeRequestParser.parseBatchRequest("b".repeat(64), input81, null, "mac")
        assertEquals(81, parsed.chapters.size)

        val input130 = buildJsonObject {
            put("bookTitle", "史記")
            put("detailLevel", "DETAILED")
            put("explainLang", "ZH")
            put(
                "chapters",
                buildJsonArray {
                    for (i in 1..130) {
                        add(
                            buildJsonObject {
                                put("chapterNum", i)
                                put("chapterTitle", "卷$i")
                                put("pages", buildJsonArray {
                                    add(buildJsonObject {
                                        put("page", i)
                                        put("text", "第${i}卷史事若干字，述興衰得失之故。")
                                    })
                                })
                            },
                        )
                    }
                },
            )
        }
        val parsed130 = StudyModeRequestParser.parseBatchRequest("c".repeat(64), input130, null, "mac")
        assertEquals(130, parsed130.chapters.size)
    }

    @Test
    fun readerAskInstructionIncludesClassicalAnnotationContext() {
        val rawSectionContext = buildJsonObject {
            put("chapter", "五帝本紀")
            put("section", "第一節")
            put("part", "本紀")
            put("annotationContext", "当前卷/篇题：五帝本紀；注者/按语归属：司馬貞【索隱述贊】；段落分层：注文/按语")
        }
        val annotationContext = rawSectionContext["annotationContext"]?.toString()?.trim('"').orEmpty()
        assertTrue("五帝本紀" in annotationContext)
        assertTrue("司馬貞【索隱述贊】" in annotationContext)
    }

    @Test
    fun chapterOpenBlockJsonCarriesStartUtf16ToPageRelativeAnchor() {
        val prefix = "導語文字用以佔位。\n\n"
        val blockText = "物競天擇，\n適者生存。"
        val pageText = prefix + blockText
        val start = pageText.indexOf(blockText)
        val rawJson = """
            {
              "id": "b1:p3:$start",
              "page": 3,
              "text": ${LedgerJson.encodeToString(kotlinx.serialization.serializer(), blockText)},
              "startUtf16": $start
            }
        """.trimIndent()
        val parsed = LedgerJson.parseToJsonElement(rawJson)
        assertNotNull(parsed)
        val accepted = ChapterOpenGate.acceptPoints(
            raw = listOf(ChapterOpenRawPoint("q1", "点明自然选择下适者得以留存", "b1:p3:$start", "物競天擇，適者生存")),
            blocks = listOf(ChapterOpenSourceBlock("b1:p3:$start", 3, blockText, start)),
        )
        assertEquals(1, accepted.size)
        val p = accepted.single()
        assertEquals(p.quoteAnchor, pageText.substring(p.startUtf16, p.endUtf16))
    }
}
