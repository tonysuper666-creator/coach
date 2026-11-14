package com.coach.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class SyntheticTeachingIdentityTest {
    private fun raw() = LedgerJson.parseToJsonElement(javaClass.getResource("/question-ladder/synthetic-runtime-identity-v1.json")!!.readText()).jsonObject

    @Test fun producerIdentityBindsCourseVersionResourcesAndRecordedLinks() {
        val runtime = SyntheticLadderRuntime.parse(raw())
        val linked = runtime.linkedEssay("SYN-Q1@v2")!!
        assertEquals("SYN-Q2@v1", linked.ref)
        assertEquals(listOf("SYN-Q1@v1"), linked.essayIdentity!!.linkedQuestionRefs)
        assertEquals(listOf("SYN-Q1@v2"), linked.essayIdentity!!.linkedQuestionRefs.map(runtime::resolve))
        val read = runtime.readEssay("SYN-COURSE-A", linked.ref, setOf("SYN-add"), true)
        assertEquals("SYN-ANSWER-2", read.essayIdentity!!.modelAnswerId)
        assertEquals(2, read.essayIdentity!!.rubricPointIds.size)
        assertEquals("Two objects form a pair.", read.modelAnswer)
        assertEquals(listOf("States the number.", "Explains the grouping."), read.rubric)
        assertFails { runtime.reveal(linked.ref, setOf("SYN-add"), true) }
        assertFails { runtime.readEssay("SYN-COURSE-B", linked.ref, setOf("SYN-add"), true) }
        assertFails { runtime.readEssay("SYN-COURSE-A", "SYN-Q2@v2", setOf("SYN-add"), true) }
        assertFails { runtime.readEssay("SYN-COURSE-A", "SYN-Q1@v2", setOf("SYN-add"), true) }
        assertFails { runtime.readEssay("SYN-COURSE-A", linked.ref, setOf("SYN-add"), false) }
        assertFails { runtime.readEssay("SYN-COURSE-A", linked.ref, emptySet(), true) }
    }

    @Test fun declaredScopeKeepsMissingKpAndCapabilityWithoutPretendingCandidatesAreReleased() {
        val runtime = SyntheticLadderRuntime.parse(raw())
        val context = runtime.context(emptySet())
        assertEquals(4, context.applicableKpIds!!.size)
        assertEquals(5, context.capabilityScope!!.size)
        assertFalse(context.scopeVerified); assertFalse(context.productionAuthorized)
        val points = context.scopePoints!!
        assertEquals("candidate_missing", points.single { it.id == "SYN-K4" }.availability)
        assertEquals("candidate_missing", points.single { it.id == "SYN-K2" }.capabilityAvailability!!["SYN-extra"])
        assertEquals("candidate_present_unverified", points.single { it.id == "SYN-K3" }.availability)
        val unknownInventory = SyntheticLadderRuntime.parse(JsonObject(raw() + ("candidate_inventory_complete" to JsonNull))).context(emptySet())
        assertEquals("unknown", unknownInventory.scopePoints!!.single { it.id == "SYN-K4" }.availability)
        val unknownKps = SyntheticLadderRuntime.parse(JsonObject(raw() + ("applicable_kps" to JsonNull))).context(emptySet())
        assertNull(unknownKps.scopePoints); assertEquals(5, unknownKps.capabilityScope!!.size)
        val unknownCapabilities = SyntheticLadderRuntime.parse(JsonObject(raw() + ("applicable_capabilities" to JsonNull))).context(emptySet())
        assertEquals(4, unknownCapabilities.scopePoints!!.size); assertNull(unknownCapabilities.capabilityScope)
        assertTrue(unknownCapabilities.scopePoints!!.all { it.capabilityIds == null })
    }

    @Test fun malformedEssayResourceIdsCannotBecomeAnApprovedAssociation() {
        val data = raw()
        val rows = data["questions"]!!.jsonArray
        val essay = rows[2].jsonObject
        val identity = essay["essay_identity"]!!.jsonObject
        for (bad in listOf(
            JsonObject(identity + ("rubric_point_ids" to JsonArray(listOf(JsonPrimitive("SYN-one"))))),
            JsonObject(identity + ("linked_question_refs" to JsonArray(listOf(JsonPrimitive("SYN-Q2@v1"))))),
            JsonObject(identity + ("model_answer_id" to JsonNull)))) {
            val changed = JsonArray(rows.mapIndexed { index, value -> if (index == 2) JsonObject(essay + ("essay_identity" to bad)) else value })
            assertFails { SyntheticLadderRuntime.parse(JsonObject(data + ("questions" to changed))) }
        }
    }
}
