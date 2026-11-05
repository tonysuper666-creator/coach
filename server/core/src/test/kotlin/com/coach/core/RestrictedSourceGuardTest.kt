package com.coach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestrictedSourceGuardTest {
    @Test fun removesPrivateOverlapAndKeepsSharedAndNovelSentences() {
        val shared = "Deductibles can reduce moral hazard in insurance markets"
        val restricted = "A synthetic restricted scenario contains twenty seven silver umbrellas"
        val guard = RestrictedSourceGuard(listOf("$shared. $restricted."), listOf(shared))
        assertEquals("$shared. A new example uses 42 umbrellas.",
            guard.filter("$shared. $restricted. A new example uses 42 umbrellas."))
        assertTrue(guard.overlaps("A SYNTHETIC restricted scenario contains twenty seven silver umbrellas"))
        assertFalse(guard.overlaps(shared))
    }

    @Test fun detectsTenHanCharactersAndNormalizesFullWidthText() {
        val guard = RestrictedSourceGuard(listOf("这是一段专门用于测试的合成受限文本。"), emptyList())
        assertTrue(guard.overlaps("这里引用一段专门用于测试的合成内容"))
        assertFalse(guard.overlaps("答案是７"))
    }

    @Test fun shortAnswersAndParaphrasesAreNotDeterministicallyBlocked() {
        val guard = RestrictedSourceGuard(
            listOf("答案是７。保险免赔额可以减少被保险人的道德风险。"), emptyList(),
        )
        assertFalse(guard.overlaps("答案是7。"))
        assertFalse(guard.overlaps("自负部分让投保人更谨慎。"))
    }
}
