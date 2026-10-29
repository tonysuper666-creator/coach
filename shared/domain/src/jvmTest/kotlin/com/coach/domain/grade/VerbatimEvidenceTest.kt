package com.coach.domain.grade

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VerbatimEvidenceTest {

    private fun accepted(answer: String, quote: String): EvidenceSpan {
        val check = VerbatimEvidence.verify(answer, quote)
        return assertIs<EvidenceCheck.Accepted>(
            check,
            "应当通过：$quote ⇐ $answer（实际 ${(check as? EvidenceCheck.Rejected)?.reason}）",
        ).span
    }

    private fun rejected(answer: String, quote: String, reason: EvidenceReject) {
        val check = assertIs<EvidenceCheck.Rejected>(
            VerbatimEvidence.verify(answer, quote),
            "应当拒绝：$quote ⇐ $answer",
        )
        assertEquals(reason, check.reason, "拒绝原因不符：$quote ⇐ $answer")
    }

    @Test
    fun clauseDelimitedBySpacesIsAccepted() {
        // 带标号的短答：整个分句就是「逆向选择」，比 8 字短也得过
        accepted("a 逆向选择 b 道德风险", "逆向选择")
        accepted("逆向选择 是事前的", "逆向选择")
    }

    @Test
    fun wholeClauseShorterThanEightIsAccepted() {
        accepted("完全竞争的条件是：没有信息不对称，没有外部性。", "没有信息不对称")
        accepted("完全竞争的条件是：没有信息不对称，没有外部性。", "没有外部性")
    }

    @Test
    fun longQuoteInsideSentenceIsAccepted() {
        accepted("然后信息不对称包括了逆向选择和道德风险是这样吗？", "信息不对称包括了逆向选择和道德风险")
    }

    @Test
    fun wholeAnswerIsAcceptedHoweverShort() {
        accepted("逆向选择", "逆向选择")
        accepted("无", "无")
        accepted("0", "0")
        accepted("是", "是")
    }

    @Test
    fun singleCharacterInsideLongAnswerIsRejected() {
        rejected("它不会导致信息不对称。", "导", EvidenceReject.BELOW_FLOOR)
        rejected("它不会导致信息不对称。", "不", EvidenceReject.BELOW_FLOOR)
        rejected("不知道", "知道", EvidenceReject.BELOW_FLOOR)
    }

    @Test
    fun wordInternalTruncationIsRejected() {
        rejected("逆向选择", "选择", EvidenceReject.BELOW_FLOOR)
        rejected("逆向选择是事前的", "逆向选", EvidenceReject.BELOW_FLOOR)
    }

    @Test
    fun numberTruncationIsRejected() {
        rejected("0.25", "25", EvidenceReject.CROSSES_TOKEN_BOUNDARY)
        rejected("11", "1", EvidenceReject.CROSSES_TOKEN_BOUNDARY)
        rejected("弹性是-0.27，缺乏弹性", "0.27", EvidenceReject.CROSSES_TOKEN_BOUNDARY)
    }

    @Test
    fun droppedMinusIsRejected() {
        rejected("-0.25", "0.25", EvidenceReject.CROSSES_TOKEN_BOUNDARY)
        rejected("负十一分之三", "十一分之三", EvidenceReject.BELOW_FLOOR)
    }

    @Test
    fun forgedRewriteIsRejected() {
        rejected("所以是否是完全竞争市场是因为外部性和信息不对称。", "逆向选择发生在签约前", EvidenceReject.NOT_IN_ANSWER)
    }

    @Test
    fun fullWidthDigitsAndDashVariantsAreFolded() {
        accepted("０．２５", "0.25")
        accepted("−3/11", "-3/11")
        accepted("3–11", "3-11")
        accepted("１ｋｇ", "1kg")
    }

    @Test
    fun nfkcExpansionInteriorIsRejected() {
        // ㎏ 在 NFKC 下拆成 kg：引文截在 k 上不该被认成原文片段
        rejected("重量是1㎏", "1k", EvidenceReject.CROSSES_TOKEN_BOUNDARY)
        accepted("重量是1㎏", "重量是1kg")
    }

    @Test
    fun offsetsAreUtf16IndexesIntoTheOriginalAnswer() {
        val astralHan = "\uD842\uDFB7"
        val astral = "答案是" + astralHan.repeat(8) + "。"
        val span = accepted(astral, astralHan.repeat(8))
        assertEquals(3, span.start)
        assertEquals(3 + 16, span.end)
        assertEquals(astral.substring(span.start, span.end), span.text)

        val full = "弹性是-0.27，缺乏弹性"
        val numeric = accepted(full, "弹性是-0.27")
        assertEquals(0, numeric.start)
        assertEquals(full.indexOf("-0.27") + 5, numeric.end)
        assertEquals("弹性是-0.27", full.substring(numeric.start, numeric.end))
    }

    @Test
    fun spanAcrossCollapsedWhitespaceCoversOriginalWhitespace() {
        val answer = "信息不对称\n包括了逆向选择"
        val span = accepted(answer, "信息不对称 包括了逆向选择")
        assertEquals(0, span.start)
        assertEquals(answer.length, span.end)
        assertTrue(span.text.contains("\n"))
    }

    @Test
    fun numericAnswerQuotedWithItsClauseIsAccepted() {
        accepted("HHI=0.30，高度集中", "HHI=0.30")
        // 只引数值、不引所在分句 → 拒绝（批改 prompt 要求「数值连同所在分句一起引」）
        rejected("需求量上升20%", "20%", EvidenceReject.BELOW_FLOOR)
        accepted("需求量上升20%", "需求量上升20%")
    }

    @Test
    fun effectiveCharCountAndTokensAreReported() {
        val check = VerbatimEvidence.verify("没有信息不对称", "没有信息不对称")
        assertIs<EvidenceCheck.Accepted>(check)
        assertEquals(7, check.effectiveChars)
        assertEquals(7, check.tokens)
    }

    @Test
    fun englishPhraseIsMatchedOnTokenBoundaries() {
        accepted(
            "Adverse selection happens before the contract; moral hazard happens after.",
            "moral hazard happens after",
        )
        // 一个完整英文单词 = 一个完整分句（≥2 有效字符即过门槛），这是 ADR 已知的口子：
        // 语义伪证挡不住，只挡截断
        accepted("moral hazard", "hazard")
        rejected("moralhazard happens", "moralhazard happensx", EvidenceReject.NOT_IN_ANSWER)
    }
}
