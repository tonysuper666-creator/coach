package com.coach.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.coach.ui.markdown.StreamingMarkdown
import com.coach.ui.math.inlineMathBox
import com.coach.domain.math.MathDelimiters
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*

class RenderingQualityTest {
    @Test fun sentenceFractionsFlowWithCurrencyWhileWideMathKeepsItsRow() {
        val mark = '$'.toString().repeat(2)
        val tokens = MathDelimiters.tokenize("金额为 " + mark + "x" + mark + " 美元",true).segments
        val inline = com.coach.ui.markdown.groupMathRuns(tokens) { true }
        assertEquals(1,inline.size)
        assertTrue(inline.single().any { it.value.text?.contains("美元") == true })
        assertEquals(3,com.coach.ui.markdown.groupMathRuns(tokens) { false }.size)
        val standalone = MathDelimiters.tokenize(mark + "x" + mark,true).segments
        assertEquals(1,com.coach.ui.markdown.groupMathRuns(standalone) { true }.size)
    }
    @Test fun compoundLearningTitleKeepsOtherAnchorsInDetails() {
        val title = "【W1】First objective ｜ 【W2】Second objective ｜ 【W3】Third objective"
        assertEquals("W1" to "First objective", splitTitle(title))
        assertEquals(listOf("【W2】Second objective", "【W3】Third objective"), additionalTitleScope(title))
        assertEquals("" to "P(A|B)", splitTitle("P(A|B)"))
        assertEquals("" to "P(A | B)", splitTitle("P(A | B)"))
    }
    @Test fun completedMarkdownListsAndEmphasisRenderWithoutSourceMarkers() {
        val rendered = com.coach.ui.markdown.renderMarkdownText("- **First** item\n- Second item")
        assertEquals("• First item\n• Second item", rendered.text.trim())
        assertTrue(rendered.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })
    }

    @Test fun blocksStayImmutableAsDeltasArrive() {
        val source = "# Title\n\nFirst **paragraph**.\n\n- one\n\n  continued\n- two\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n```kotlin\nval dollar = \"$\"\n\n// code\n```\n\nFinal\nheading\n=======\n\n[late]: https://example.org\n"
        val engine = StreamingMarkdown()
        val sealed = mutableMapOf<Int, Any>()
        var blocks = emptyList<com.coach.ui.markdown.MarkdownBlock>()
        for ((i, c) in source.withIndex()) {
            blocks = engine.append(c.toString(), i == source.lastIndex)
            for (block in blocks.filter { it.sealed }) {
                sealed[block.key]?.let { assertSame(it, block, "A sealed block changed") }
                sealed[block.key] = block
            }
        }
        // No text is lost when blank-line separators stay in the mutable tail.
        assertEquals(source.filterNot { it.isWhitespace() }, blocks.joinToString("") { it.source }.filterNot { it.isWhitespace() })
        assertTrue(blocks.any { it.source.contains("continued") && it.source.contains("two") })
        assertTrue(blocks.any { it.source.contains("heading\n=======") })
    }
    @Test fun desktopEnterDoesNotSubmitCompositionOrKeyUp() {
        assertTrue(shouldSendOnEnter(true, true, false, false))
        assertFalse(shouldSendOnEnter(true, true, false, true))
        assertFalse(shouldSendOnEnter(true, false, false, false))
        assertFalse(shouldSendOnEnter(true, true, true, false))
        assertFalse(shouldSendOnEnter(false, true, false, false))
    }
    @Test fun localSnapshotRetainsDraftChoiceStreamAndAnchor() {
        val old = ReplayState().apply { input = TextFieldValue("中文 x^2", TextRange(2)); selected = "B"; cursor = 174; running = true; scrollIndex = 3; scrollOffset = 91; message = 7; pinned = false; anchorKey = 17; anchorFraction = 0.397f; autoplayRun = 1234L }
        val restored = ReplayState(old.snapshot())
        assertEquals(old.snapshot(), restored.snapshot())
        assertEquals(17, restored.anchorKey); assertEquals(0.397f, restored.anchorFraction); assertEquals(false, restored.pinned)
    }
    @Test fun inlinePlaceholderRetainsBaselineForTallAndDeepMath() {
        for ((h, d) in listOf(12f to 3f, 36f to 21f, 5f to 38f)) {
            val box = inlineMathBox(h, d, 16f, 22f)
            assertEquals(16f - 11f, box.baseline - box.height / 2, 0.001f)
            assertTrue(box.baseline >= h && box.height - box.baseline >= d)
        }
    }
    @Test fun stagedCurrencyAndRealAnswerReplay() {
        val root = File(System.getProperty("user.home"), "Coach/corpus/render")
        org.junit.Assume.assumeTrue("缺语料，未运行 staged replay/currency", root.exists())
        val corpus = ReplayCorpus.load(File(root, "replay.json").readText())
        var formulas = 0
        for (message in corpus.messages) {
            val engine = StreamingMarkdown(); var last = emptyList<com.coach.ui.markdown.MarkdownBlock>()
            val sealed = mutableMapOf<Int, Any>()
            for (chunk in message.chunked(30)) {
                last = engine.append(chunk)
                last.filter { it.sealed }.forEach { block -> sealed[block.key]?.let { assertSame(it, block) }; sealed[block.key] = block }
            }
            last = engine.append("", true)
            val all = last.joinToString("") { it.source }.filterNot { it.isWhitespace() }
            assertEquals(message.filterNot { it.isWhitespace() }, all)
            formulas += MathDelimiters.tokenize(message, true).segments.count { it.math != null }
        }
        val lines = Json.parseToJsonElement(File(root, "currency.json").readText()).jsonArray
        var mismatches = 0
        for (raw in lines) {
            val line = raw.jsonObject
            val actual = MathDelimiters.tokenize(line.getValue("text").jsonPrimitive.content).segments.filter { it.math != null }
            val expected = line["expected"]?.jsonPrimitive?.int ?: 0
            if (actual.size != expected) mismatches++
        }
        println("REAL replay=${corpus.messages.size} formulas=$formulas currencyLines=${lines.size} delimiterMismatches=$mismatches")
        assertEquals(0, mismatches)
    }
}

class CurrencyEmphasisTest {
    private fun rendered(source: String) = com.coach.ui.markdown.renderMarkdownText(source)

    @Test fun boldCurrencyShowsNoAsterisks() {
        val out = rendered("你应该付 **$20**，保险付 $80。")
        assertEquals("你应该付 $20，保险付 $80。", out.text)
        assertTrue(out.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold && out.text.substring(it.start, it.end) == "$20" })
    }
    @Test fun boldCurrencyThroughTheMathTokenizer() {
        val tokens = com.coach.domain.math.MathDelimiters.tokenize("你应该付 **$20**，保险付 $80。", true).segments
        val shown = tokens.joinToString("") { it.text?.let { t -> rendered(t).text } ?: "⟦${it.math}⟧" }
        assertEquals("你应该付 $20，保险付 $80。", shown)
    }
    @Test fun dollarsStayLiteralWhateverSurroundsThem() {
        assertEquals("先付 $20，再付 $80", rendered("先付 \\$20，再付 \\$80").text)
        assertEquals("price = $5", rendered("`price = $5`").text)
        assertEquals("A $20, B $80, C $100", rendered("A $20, B $80, C $100").text)
    }
}

class VerdictCopyTest {
    @Test fun feedbackThatRepeatsTheVerdictIsDropped() {
        assertNull(com.coach.ui.extraFeedback("回答正确（B）"))
        assertNull(com.coach.ui.extraFeedback("回答正确。"))
        assertNull(com.coach.ui.extraFeedback("  "))
        assertEquals("需求缺乏弹性，因为 |ε|<1", com.coach.ui.extraFeedback("需求缺乏弹性，因为 |ε|<1"))
    }
}

class WrongAnswerCopyTest {
    private val letters = mapOf("o1" to "A", "o2" to "B", "o3" to "C")

    @Test fun theCardNamesTheRightOptionFromTheServerSentence() {
        val feedback = com.coach.domain.choiceFeedback("o2", "A", letters)
        assertEquals("B", com.coach.ui.correctLetterOf(feedback))
        assertNull(com.coach.ui.correctLetterOf(com.coach.domain.choiceFeedback("o2", "B", letters)))
        assertEquals("✗ 答错了，正确答案是 B", com.coach.ui.verdictText(com.coach.client.CardGrade(false, 0, 1, feedback)))
        assertEquals("✓ 答对了", com.coach.ui.verdictText(com.coach.client.CardGrade(true, 1, 1, com.coach.domain.choiceFeedback("o2", "B", letters))))
    }
    @Test fun extraExplanationIsKeptAfterTheVerdict() {
        val grade = com.coach.client.CardGrade(false, 0, 1, "回答错误，正确答案是 C。需求缺乏弹性，因为 |ε|<1")
        assertEquals("✗ 答错了，正确答案是 C：需求缺乏弹性，因为 |ε|<1", com.coach.ui.verdictText(grade))
    }
}
