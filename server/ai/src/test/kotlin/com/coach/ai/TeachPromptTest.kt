package com.coach.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeachPromptTest {
    @Test
    fun reviewedBasisReplacesTheVerifyAgainstSourceInstruction() {
        val ordinary = TeachPrompt.build("q", "pack")
        assertTrue(ordinary.contains("answer only from the course pack below"))

        val reviewed = TeachPrompt.build("q", "pack", reviewedBasis = true)
        assertFalse(reviewed.contains("answer only from the course pack below"))
        assertTrue(reviewed.contains("the reviewed explanations above are the basis for the answer"))
    }

    @Test
    fun sourceLookupKeepsItsOwnInstructionEvenWithAReviewedBasis() {
        val lookup = TeachPrompt.build("q", "pack", allowSourceLookup = true, reviewedBasis = true)
        assertTrue(lookup.contains("【需查原文:"))
        assertFalse(lookup.contains("the reviewed explanations above"))
    }

    @Test
    fun teachRequestCarriesEffortAndBasis() {
        val request = teachRequest("q", "pack", effort = "low", reviewedBasis = true)
        assertEquals("low", request.effort)
        assertTrue(request.user.contains("the reviewed explanations above"))
        assertEquals("medium", teachRequest("q", "pack").effort)
    }
}
