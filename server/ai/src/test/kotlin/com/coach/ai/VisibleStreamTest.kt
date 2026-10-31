package com.coach.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DESIGN §5.8 第 4 条的回放断言：用合成会话 + 桩输出注入协议字样，可见流里 0 条漏出。
 *
 * 注入的段落刻意在 chunk 边界上被切断（`tool_` / `call`），验证流式安全前缀不会把 token 切出去。
 */
class VisibleStreamTest {

    @Test
    fun proseIntervalsSetsAndCitationsSurviveEveryDeltaBoundary() {
        for (answer in listOf("[0, 1] 是效用的取值范围。", "[1] Drummond 2015。", "  [1]  引文。\n下一行", "{\"a\":1} 是一个对象示例。", "第一行\n[1] Drummond 2015。", "效用取值在区间 [0, 1] 之间。", "集合 {A, B} 是两种方案。", "参见文献[1]。", "上一行。\n区间 [0, 1] 与集合 {A, B}，参见文献[1]。")) {
            for (boundary in 0..answer.length) {
                val recorder = Recorder()
                recorder.push(answer.take(boundary))
                recorder.push(answer.drop(boundary))
                recorder.finish()
                assertEquals(answer, recorder.texts.toString(), "delta boundary $boundary in $answer")
                assertTrue(recorder.drops.isEmpty())
            }
            val recorder = Recorder()
            answer.forEach { recorder.push(it.toString()) }
            recorder.finish()
            assertEquals(answer, recorder.texts.toString(), "one-character deltas in $answer")
        }
    }

    @Test
    fun standaloneJsonWaitsForLineEndAndNeverLeaksAcrossAnyBoundary() {
        for (raw in listOf("[0, 1]", "{\"answer\":1}", "{\n\"answer\": [1]\n}")) {
            for (suffix in listOf("", "   ", " \n正文。\n")) {
                val answer = raw + suffix
                for (boundary in 0..answer.length) {
                    val recorder = Recorder()
                    recorder.push(answer.take(boundary))
                    assertEquals("", recorder.texts.toString().takeIf { boundary <= raw.length }.orEmpty())
                    recorder.push(answer.drop(boundary))
                    recorder.finish()
                    assertEquals(if ('\n' in suffix) "正文。\n" else "", recorder.texts.toString())
                    assertTrue(recorder.drops.any { it.reason == "raw_json" })
                }
            }
        }
    }

    private class Recorder {
        val drops = ArrayList<VisibleStream.Drop>()
        val texts = StringBuilder()
        val maths = ArrayList<String>()
        val stream = VisibleStream { drops.add(it) }

        fun push(chunk: String) {
            for (segment in stream.push(chunk)) collect(segment)
        }

        fun finish() {
            for (segment in stream.finish()) collect(segment)
        }

        private fun collect(segment: VisibleStream.Segment) {
            when (segment) {
                is VisibleStream.Segment.Text -> texts.append(segment.text)
                is VisibleStream.Segment.Math -> maths.add(segment.tex)
            }
        }
    }

    @Test
    fun protocolTokensInjectedByTheStubNeverReachTheVisibleStream() {
        val recorder = Recorder()
        val session = listOf(
            "导师：先说结论。\n",
            "这段是正文，应该出现。\n",
            "placeholder: {\"status\":\"complete\"}\n",
            "malformed output: choices[0].delta is null\n",
            "tool_",
            "call: {\"name\":\"read_file\"}\n",
            "<tool_calls>[{\"index\":0}]</tool_calls>\n",
            "{\"x_coach\":{\"status\":\"complete\"}}\n",
            "收尾一句，也应该出现。\n",
        )
        for (chunk in session) recorder.push(chunk)
        recorder.finish()

        val text = recorder.texts.toString()
        assertTrue(text.contains("先说结论"), "visible text lost the real answer: $text")
        assertTrue(text.contains("收尾一句"), "visible text lost the trailing answer: $text")
        val leaked = VisibleStream.TOKENS.filter { text.lowercase().contains(it) }
        assertTrue(leaked.isEmpty(), "protocol tokens leaked into the visible stream: $leaked\n$text")
        assertTrue(recorder.drops.size >= 5, "drops were not recorded: ${recorder.drops.map { it.reason }}")
        assertTrue(recorder.drops.any { it.reason == "tool_call" }, "tool_call drop not classified")
        assertTrue(recorder.drops.any { it.reason == "placeholder" }, "placeholder drop not classified")
        assertEquals(recorder.drops.size, recorder.stream.droppedCount)
    }

    @Test
    fun sourceLookupMarkerIsStrippedButTheRestOfTheAnswerStays() {
        val recorder = Recorder()
        recorder.push("按备课包答到这里。\n【需查原文:第 3 章 Elasticity 定义】\n")
        recorder.push("补充：需求价格弹性是数量的相对变化除以价格的相对变化。\n")
        recorder.finish()

        val text = recorder.texts.toString()
        assertTrue(text.contains("按备课包答到这里"), text)
        assertTrue(text.contains("需求价格弹性"), text)
        assertTrue(!text.contains("需查原文"), "marker leaked: $text")
        assertTrue(!text.contains("第 3 章"), "marker payload leaked: $text")
    }

    @Test
    fun unclosedMarkerAtEndOfStreamNeverLeaks() {
        val recorder = Recorder()
        recorder.push("正文。\n")
        recorder.push("【需查原文:还没闭合的标记")
        recorder.finish()

        val text = recorder.texts.toString()
        assertTrue(text.contains("正文"), text)
        assertTrue(!text.contains("需查原文"), "unclosed marker leaked: $text")
    }

    @Test
    fun tokenSplitAcrossChunksIsHeldBack() {
        val recorder = Recorder()
        recorder.push("正常开头\n")
        recorder.push("place")
        recorder.push("holder object emitted")
        recorder.push("\n正常结尾\n")
        recorder.finish()

        val text = recorder.texts.toString()
        assertTrue(text.contains("正常开头"), text)
        assertTrue(text.contains("正常结尾"), text)
        assertTrue(!text.contains("placeholder"), "split token leaked: $text")
    }

    @Test
    fun rawJsonWithoutProtocolKeywordsIsInternalEvenAcrossChunks() {
        val recorder = Recorder()
        recorder.push("{\n\"answer\": \"internal ")
        recorder.push("value\", \"meta\": [1, {\"debug\": true}]\n}\n正常的导师回答。\n")
        recorder.finish()
        assertEquals("正常的导师回答。\n", recorder.texts.toString())
        assertTrue(recorder.drops.any { it.reason == "raw_json" })
    }

    @Test
    fun formulaDelimitersSplitAcrossChunksStillYieldOneMathBlock() {
        val recorder = Recorder()
        recorder.push("公式 ")
        recorder.push("$")
        recorder.push("\$E = p")
        recorder.push(" q$")
        recorder.push("\$。")
        recorder.finish()
        assertEquals(listOf("E = p q"), recorder.maths)
        assertEquals("公式 。", recorder.texts.toString())
    }

    @Test
    fun displayMathBecomesATypedMathBlock() {
        val recorder = Recorder()
        val token = "$" + "$"
        recorder.push("弹性公式是 " + token + "E_d = \\frac{\\%\\Delta Q}{\\%\\Delta P}" + token + " 记住它。\n")
        recorder.finish()

        assertEquals(listOf("E_d = \\frac{\\%\\Delta Q}{\\%\\Delta P}"), recorder.maths)
        val text = recorder.texts.toString()
        assertTrue(text.contains("弹性公式是"), text)
        assertTrue(text.contains("记住它"), text)
    }
}
