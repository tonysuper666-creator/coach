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
}
