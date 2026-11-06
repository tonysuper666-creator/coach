package com.coach.core

import kotlin.random.Random
import kotlin.test.*

class ExamTextGuardTest {
    private val exam = "Amber badgers carry delicate emerald feathers gently home. Ivory jaguars keep lemons near old purple quails."
    private val index = ExamTextIndex(listOf(exam, "Rivers shine through unusual valleys while xenon yields zephyrs."))

    @Test fun everyByteBoundaryIsProtectedBeforePublication() {
        for (split in 0..exam.length) {
            val guard = ExamTextGuard(index)
            val seen = StringBuilder()
            for (chunk in listOf(exam.take(split), exam.drop(split))) {
                seen.append(guard.push(chunk))
                assertFalse(index.overlaps(seen.toString()), "split=$split")
            }
            seen.append(guard.finish())
            assertFalse(index.overlaps(seen.toString()))
            assertTrue(guard.blocked)
            assertTrue(seen.contains("解题思路"))
        }
    }

    @Test fun punctuationCaseCompatibilityLettersAndEndOfStreamCannotBypass() {
        val variants = listOf(
            "AMBER, badgers. carry\ndelicate **emerald** feathers gently home",
            "Ａｍｂｅｒ badgers carry delicate emerald feathers gently home!",
            "𝔄mber badgers carry delicate emerald feathers gently home!",
            "Amber e\u0301 badgers carry delicate emerald feathers gently home!",
            "Amber badgers carry delicate \$\$emerald\$\$ feathers gently home",
        )
        for (input in variants) repeat(10) { seed ->
            val random = Random(seed)
            val guard = ExamTextGuard(index)
            val seen = StringBuilder()
            var offset = 0
            while (offset < input.length) {
                val end = minOf(input.length, offset + random.nextInt(1, 12))
                seen.append(guard.push(input.substring(offset, end)))
                assertFalse(index.overlaps(seen.toString()))
                offset = end
            }
            seen.append(guard.finish())
            assertFalse(index.overlaps(seen.toString()))
            assertTrue(guard.blocked)
        }
    }

    @Test fun safePrefixIsImmediateAndSevenWordsArePreservedWhenTheyDiverge() {
        val guard = ExamTextGuard(index)
        assertEquals("先看机会成本。", guard.push("先看机会成本。"))
        assertEquals("", guard.push("Amber badgers carry delicate emerald feathers gently "))
        val expected = "Amber badgers carry delicate emerald feathers gently elsewhere."
        assertEquals(expected, guard.push("elsewhere.") + guard.finish())
        assertFalse(guard.blocked)
    }

    @Test fun completeCorpusAndMissingCorpusFailClosed() {
        val second = "Rivers shine through unusual valleys while xenon yields zephyrs."
        val guard = ExamTextGuard(index)
        val result = guard.push(second) + guard.finish()
        assertFalse(index.overlaps(result))
        assertTrue(guard.blocked)
        assertEquals(ExamTextGuard.REASONING, ExamTextGuard(null).push("anything"))
    }

    @Test fun normalResponsesRemainByteIdentical() {
        for (input in listOf("先写定义，再解释机制，用例子检验结论。", "Compare marginal benefit against marginal cost. Then check assumptions.", "\$\$ICER = (c_2-c_1)/(q_2-q_1)\$\$")) {
            val guard = ExamTextGuard(index)
            assertEquals(input, input.chunked(1).joinToString("") { guard.push(it) } + guard.finish())
        }
    }

    @Test fun referenceFormulasDoNotTurnVariablesOrCommandsIntoEnglishQuotations() {
        val formulas = listOf(
            "\$\$E = (Q_2-Q_1)/((Q_2+Q_1)/2) / ((P_2-P_1)/((P_2+P_1)/2))\$\$",
            "\$\$\\frac{Q_2-Q_1}{Q_2+Q_1} / \\frac{P_2-P_1}{P_2+P_1}\$\$",
            "\$\$ICER = (C_B-C_A)/(Q_B-Q_A); Q = S_1 U_1 + S_2 U_2\$\$",
        )
        val formulaIndex = ExamTextIndex(listOf(exam) + formulas)
        for (formula in formulas) {
            assertFalse(formulaIndex.overlaps(formula))
            val guard = ExamTextGuard(formulaIndex)
            assertEquals(formula, formula.chunked(1).joinToString("") { guard.push(it) } + guard.finish())
            assertFalse(guard.blocked)
        }
    }

    @Test fun singleLetterAndFormulaFillersCannotHideAQuotation() {
        for (filler in listOf("x", "e\u0301", "\$Q_2\$", "\\frac{Q_2}{P_2}"))
            assertObfuscationBlocked(quote.split(" ").joinToString(" $filler "))
    }

    @Test fun englishArticlesAndPronounsStillCountAndPartialVariablesStayUnpublished() {
        val sentence = "I saw a bright comet cross the horizon."
        val sentenceIndex = ExamTextIndex(listOf(sentence))
        assertTrue(sentenceIndex.overlaps(sentence))
        val guard = ExamTextGuard(index)
        assertEquals("", guard.push("A"))
        assertEquals("", guard.push("mber badgers carry delicate emerald feathers gently h"))
        val published = guard.push("ome.") + guard.finish()
        assertTrue(guard.blocked)
        assertFalse(index.overlaps(published))
        // 'h' is ignored for matching until the next delta makes it 'home'.
        val lastWord = ExamTextGuard(index)
        val prefix = lastWord.push("Amber badgers carry delicate emerald feathers gently ")
        assertEquals("", prefix + lastWord.push("h"))
        assertTrue(lastWord.push("ome.").contains("解题思路"))
        assertTrue(lastWord.blocked)
    }

    @Test fun splitSurrogatePairCannotEscapeTheWordIndex() {
        val input = "𝔄mber badgers carry delicate emerald feathers gently home."
        val guard = ExamTextGuard(index)
        val seen = StringBuilder()
        for (chunk in input.chunked(1)) {
            seen.append(guard.push(chunk))
            assertFalse(index.overlaps(seen.toString()))
        }
        seen.append(guard.finish())
        assertTrue(guard.blocked)
        assertFalse(index.overlaps(seen.toString()))
    }

    private val quote = "Amber badgers carry delicate emerald feathers gently home"
    private fun assertObfuscationBlocked(input: String, checkedIndex: ExamTextIndex = index) {
        assertTrue(checkedIndex.overlaps(input), "Full-text checker must recognize the visible quotation")
        // Reverse the roles too: reference text and reply must use the same folding.
        assertTrue(ExamTextIndex(listOf(input)).overlaps(quote))
        for (split in 0..input.length) {
            val guard = ExamTextGuard(checkedIndex)
            val seen = StringBuilder()
            for (chunk in listOf(input.take(split), input.drop(split))) {
                seen.append(guard.push(chunk))
                assertFalse(checkedIndex.overlaps(seen.toString()), "split=$split")
            }
            seen.append(guard.finish())
            assertTrue(guard.blocked, "split=$split")
            assertTrue(seen.contains("解题思路"))
            assertFalse(checkedIndex.overlaps(seen.toString()), "split=$split")
        }
        // Each boundary may also be split in a separate delta (including commands).
        val guard = ExamTextGuard(checkedIndex)
        val seen = StringBuilder()
        input.forEach { char ->
            seen.append(guard.push(char.toString()))
            assertFalse(checkedIndex.overlaps(seen.toString()))
        }
        seen.append(guard.finish())
        assertTrue(guard.blocked)
        assertFalse(checkedIndex.overlaps(seen.toString()))
    }

    @Test fun zeroWidthFormattingCannotSplitVisibleWords() =
        assertObfuscationBlocked(quote.map { "$it\u200B" }.joinToString(""))

    @Test fun softHyphensCannotSplitVisibleWords() =
        assertObfuscationBlocked(quote.map { "$it\u00AD" }.joinToString(""))

    @Test fun inlineMarkdownCannotSplitVisibleWords() {
        for (marker in listOf("**", "_", "~~", "`"))
            assertObfuscationBlocked(quote.split(" ").joinToString(" ") { it.take(1) + marker + it.drop(1) + marker })
    }

    @Test fun texTextWrappersCannotHideVisibleWords() =
        assertObfuscationBlocked(quote.split(" ").joinToString(" ") { "\$\$\\text{$it}\$\$" })

    @Test fun literalControlWordPrefixesCannotSplitOffTheirBackslash() {
        val commands = listOf("in", "to", "not", "max", "text")
        val collisionIndex = ExamTextIndex(listOf(exam) + commands.map { "$it $quote." })
        for (command in commands) for (prefixLength in 1..quote.substringBefore(' ').length) {
            val input = " \\$command{${quote.take(prefixLength)}}${quote.drop(prefixLength)}"
            assertObfuscationBlocked(input, collisionIndex)
        }
    }

    @Test fun cyrillicAndGreekHomoglyphsCannotHideVisibleWords() {
        assertObfuscationBlocked(quote.replace('e', 'е').replace('a', 'а'))
        assertObfuscationBlocked(quote.replace('A', 'Α').replace('o', 'ο'))
    }

    @Test fun accentsCannotHideVisibleWords() {
        assertObfuscationBlocked(quote.replace('e', 'é'))
        assertObfuscationBlocked(quote.replace("e", "e\u0301"))
    }

    @Test fun literalWordsSeparatedByMarkupRemainProtected() {
        for (delimiter in listOf("_", "*", "~", "`"))
            assertObfuscationBlocked(quote.replace(" ", delimiter))
    }

    @Test fun normalFormattedResponsesArePublishedUnchanged() {
        val inputs = listOf("A\\text{b}solutely safe **explanation** with caf\u00E9 and p\u200Brice.",
            "\$\$\\text{Marginal benefit} = x\$\$; check a_b and A\u00ADkerlof.")
        for (input in inputs) {
            val guard = ExamTextGuard(index)
            assertEquals(input, input.chunked(1).joinToString("") { guard.push(it) } + guard.finish())
            assertFalse(guard.blocked)
        }
    }
}
