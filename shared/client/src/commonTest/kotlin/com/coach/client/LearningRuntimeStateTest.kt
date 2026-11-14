package com.coach.client

import com.coach.domain.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LearningRuntimeStateTest {
    @Test fun outlineRowsNeverSupplyTheMissingScopeDenominator() {
        val state = LearningRuntimeState.fromWeek(json("items" to listOf("SYN-K1", "SYN-K2")))
        assertNull(state.completeDenominator)
        val synthetic = LearningRuntimeState(LadderRuntimeContext(applicableKpIds = listOf("SYN-K1"), scopeVerified = true, syntheticOnly = true))
        assertNull(synthetic.completeDenominator)
        assertTrue("待核" in synthetic.scopeMessage)
        assertNull(LearningRuntimeState(LadderRuntimeContext(applicableKpIds = listOf("K1", "K2"), scopeVerified = true)).completeDenominator)
        val declared = LadderRuntimeContext(applicableKpIds = listOf("K1", "K2"), scopeVerified = true,
            scopeKnown = true, scopeVersion = "v1", scopeEvidence = listOf("SYN-only-scope-receipt"),
            scopePoints = listOf(LadderScopePoint("K1", "Pairing", listOf("C"), "candidate_present_unverified"),
                LadderScopePoint("K2", "Grouping", listOf("C"), "candidate_missing")))
        assertEquals(2, LearningRuntimeState(declared).completeDenominator)
        assertEquals(2, LearningRuntimeState(declared).declaredCapabilityTotal) // Pairs, not globally deduplicated capability IDs.
        assertNull(LearningRuntimeState(declared.copy(syntheticOnly = true)).completeDenominator)
        assertEquals(2, LearningRuntimeState(declared.copy(syntheticOnly = true)).declaredKpTotal)
        assertNull(LearningRuntimeState(declared.copy(scopePoints = declared.scopePoints!!.dropLast(1))).declaredKpTotal)
        assertNull(LearningRuntimeState.fromWeek(json("runtime" to json("scopeVerified" to "yes"))).completeDenominator)
    }

    @Test fun essayIdsAreConsumedAfterSubmissionWithoutPretendingToHaveFullText() {
        assertNull(LadderEssayState.fromAnswer(json("items" to emptyList<String>())))
        val pending = LadderEssayState.fromAnswer(json("essay" to json("model_answer_id" to "SYN-E1", "rubric_point_ids" to listOf("SYN-R1"))))!!
        assertEquals("SYN-E1", pending.modelAnswerId)
        assertEquals(listOf("SYN-R1"), pending.rubricPointIds)
        assertTrue("尚未接通" in pending.message)
        assertFalse(pending.malformed)
        val missing = LadderEssayState.fromAnswer(json("essay" to json("model_answer_id" to JsonNull, "rubric_point_ids" to emptyList<String>())))!!
        assertTrue("待核" in missing.message)
        val invalid = LadderEssayState.fromAnswer(json("essay" to json("model_answer_id" to 1, "rubric_point_ids" to "SYN-R1")))!!
        assertTrue(invalid.malformed)
    }
}
