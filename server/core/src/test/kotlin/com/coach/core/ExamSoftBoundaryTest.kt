package com.coach.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExamSoftBoundaryTest {
    private val index = ExamTextIndex(listOf("Ivory jaguars keep lemons near old purple quails. Rivers shine through unusual valleys while xenon yields zephyrs."))

    @Test fun mixedBoundariesAndConfusablesAreBlockedAtEverySplit() {
        val examples = listOf(
            "Ivory j_aguars keep lemons near old purple quаils*next",
            "prev_Ivory jaguars keep lemons near old purple quаils",
            "Ivory jaguars keep lemons near old purple quаils_next",
            "Ivory j_aguars keep lemons near old purple quails*Rivers shine through unusual valleys while xenon yields zephyrs",
        )
        for (input in examples) {
            assertTrue(index.overlaps(input))
            for (size in 1..5) {
                val guard = ExamTextGuard(index)
                val published = input.chunked(size).joinToString("") { guard.push(it) } + guard.finish()
                assertTrue(guard.blocked, "size=$size")
                assertFalse(index.overlaps(published.substringBefore(ExamTextGuard.REASONING)), "size=$size")
            }
        }
    }

    @Test fun mixedMarkupInReferenceAlsoProtectsTheReadableWindow() {
        val mixedReference = ExamTextIndex(listOf("Ivory j_aguars keep lemons near old purple quails*Rivers"))
        assertTrue(mixedReference.overlaps("Ivory jaguars keep lemons near old purple quails"))
        val guard = ExamTextGuard(mixedReference)
        "Ivory jaguars keep lemons near old purple quails".chunked(1).forEach { guard.push(it) }
        guard.finish()
        assertTrue(guard.blocked)
    }

    @Test fun oversizedAmbiguousReferenceFailsClosed() {
        val index = ExamTextIndex(listOf("a".repeat(1025) + "*"))
        assertTrue(index.incompleteIndex)
        val guard = ExamTextGuard(index)
        assertTrue(guard.push("anything").contains(ExamTextGuard.REASONING))
        assertTrue(guard.blocked)
    }
}
