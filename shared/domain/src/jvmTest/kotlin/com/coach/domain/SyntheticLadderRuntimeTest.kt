package com.coach.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class SyntheticLadderRuntimeTest {
    private fun fixture() = LedgerJson.parseToJsonElement(javaClass.getResource("/question-ladder/synthetic-runtime-v1.json")!!.readText()).jsonObject
    private fun changeQuestion(raw: JsonObject, index: Int, update: (JsonObject) -> JsonObject): JsonObject {
        val rows = raw.getValue("questions").jsonArray.toMutableList()
        rows[index] = update(rows[index].jsonObject)
        return JsonObject(raw + ("questions" to JsonArray(rows)))
    }

    @Test fun frozenProducerInputResolvesVersionsAndCapabilitiesWithoutInventingMastery() {
        val runtime = SyntheticLadderRuntime.parse(fixture())
        val pool = runtime.questions.map(runtime::ladder)
        val selected = QuestionLadder.next(pool, LadderSession()).question!!
        assertEquals("SYN-Q1@v2", selected.id)
        assertEquals("SYN-add", selected.capabilityId)
        val essay = runtime.questions.single { it.id == "SYN-Q2" }
        assertEquals(listOf("SYN-Q1@v1"), runtime.ladder(essay).recordedPrerequisiteRefs)
        assertEquals(listOf("SYN-Q1@v2"), runtime.ladder(essay).resolvedPrerequisiteRefs)
        assertEquals("SYN-Q1@v1", essay.prerequisiteRefs!!.single()) // Original input/history stays intact.
        assertTrue("capability_evidence_missing" in runtime.missing(essay, emptySet()))
        assertTrue(runtime.missing(essay, setOf("SYN-add")).isEmpty()) // No previous-answer requirement.
        assertFailsWith<IllegalArgumentException> { runtime.reveal(essay.ref, emptySet(), true) }
        assertFailsWith<IllegalArgumentException> { runtime.reveal(essay.ref, setOf("SYN-add"), false) }
        assertFailsWith<IllegalArgumentException> { runtime.reveal(essay.ref, setOf("SYN-add"), true) }
        val revealed = runtime.reveal(selected.id, emptySet(), true)
        assertEquals(1L, revealed.num("answer"))
        assertFalse(revealed.flag("mastery_evidence"))
        assertFalse(runtime.context(emptySet()).productionAuthorized)
        assertFalse(runtime.context(emptySet()).scopeVerified)
        assertEquals(3, runtime.context(emptySet()).applicableKpIds!!.size)
        val session = QuestionLadder.record(LadderSession(), selected, true, "")
        assertFalse(session.responses.single().independentCorrect)
        assertFalse(QuestionLadder.learned(session, "SYN-K1"))
    }

    @Test fun capabilityIsAnIndependentGateAndUnknownIsNotEmpty() {
        val q = SyntheticLadderRuntime.parse(fixture()).questions[1]
        val raw = changeQuestion(fixture(), 1) { JsonObject(it + ("required_capabilities" to JsonArray(listOf(JsonPrimitive("SYN-other"))))) }
        val runtime = SyntheticLadderRuntime.parse(raw)
        val pool = runtime.questions.map(runtime::ladder)
        assertNull(QuestionLadder.next(pool, LadderSession(), setOf("SYN-other")).question)
        assertEquals(setOf("SYN-other"), QuestionLadder.next(pool, LadderSession()).blockedCapabilities)
        assertEquals(q.ref, QuestionLadder.next(pool, LadderSession(), knownCapabilities = setOf("SYN-other")).question!!.id)
        for (value in listOf<JsonElement?>(null, JsonNull)) {
            val unknown = SyntheticLadderRuntime.parse(changeQuestion(fixture(), 1) {
                JsonObject(if (value == null) it - "prerequisite_refs" else it + ("prerequisite_refs" to value))
            })
            val step = QuestionLadder.next(unknown.questions.map(unknown::ladder), LadderSession())
            assertNull(step.question); assertTrue(step.prerequisitesUnknown)
        }
        val unknownScope = SyntheticLadderRuntime.parse(JsonObject(fixture() + ("applicable_kps" to JsonNull)))
        assertNull(unknownScope.context(emptySet()).applicableKpIds)
        val newKind = SyntheticLadderRuntime.parse(changeQuestion(fixture(), 1) { JsonObject(it + ("teaching_kind" to JsonPrimitive("new-kind"))) })
        assertNull(QuestionLadder.next(newKind.questions.map(newKind::ladder), LadderSession()).question)
        assertTrue("teaching_kind" in newKind.context(emptySet()).unsupported)
    }

    @Test fun invalidIdentityAliasesAndDanglingReferencesAreRejected() {
        fun reject(raw: JsonObject) { assertFails { SyntheticLadderRuntime.parse(raw) } }
        reject(JsonObject(fixture() + ("schema" to JsonPrimitive("v-next"))))
        reject(JsonObject(fixture() + ("synthetic" to JsonPrimitive(false))))
        for ((field, value) in listOf("kp" to "SYN-K2", "capability" to "SYN-other", "version" to "v0", "ref" to "SYN-other@v2")) {
            reject(changeQuestion(fixture(), 1) { JsonObject(it + (field to JsonPrimitive(value))) })
        }
        reject(JsonObject(fixture() + ("prerequisite_aliases" to JsonArray(listOf(json("from" to "SYN-Q1@v2", "to" to "SYN-Q1@v1"))))))
        reject(changeQuestion(fixture(), 1) { JsonObject(it + mapOf("format" to JsonPrimitive("essay"), "options" to JsonNull, "answer" to JsonNull)) })
        reject(changeQuestion(fixture(), 2) { JsonObject(it + ("prerequisite_refs" to JsonArray(listOf(JsonPrimitive("SYN-missing@v1"))))) })
        reject(changeQuestion(fixture(), 1) { JsonObject(it + ("prerequisite_refs" to JsonArray(listOf(JsonPrimitive("SYN-Q2@v1"))))) })
        reject(changeQuestion(fixture(), 1) { JsonObject(it + ("required_capabilities" to JsonPrimitive("SYN-add"))) })
    }

    @Test fun candidateKpPrerequisitesAndStarsRetainTheirActualMeaning() {
        assertTrue(LadderPrerequisites.read(JsonArray(emptyList()), null).known)
        assertFalse(LadderPrerequisites.read(null, setOf("SYN-K1")).known)
        val wrong = LadderPrerequisites.read(JsonArray(listOf(JsonPrimitive("SYN-Q1"))), setOf("SYN-K1"))
        assertEquals(listOf("prerequisite_id_is_not_a_kp"), wrong.issues)
        assertFalse(wrong.known)
        assertTrue(LadderPrerequisites.read(JsonArray(listOf(JsonPrimitive("SYN-K1"))), setOf("SYN-K1")).known)
        assertEquals(LadderStar(null, "missing"), LadderStar.read(json()))
        assertEquals(LadderStar(null, "null"), LadderStar.read(json("star" to JsonNull)))
        assertEquals(LadderStar(null, "unknown"), LadderStar.read(json("star" to "unknown")))
        assertEquals(LadderStar(1, "verified"), LadderStar.read(json("star" to 1, "star_source" to "SYN-source")))
        assertNull(LadderStar.read(json("star" to 1)).value)
    }

    @Test fun anotherCapabilityKeepsItsIntroductionAndCannotBorrowMastery() {
        val a = (1..3).map { LadderQuestion("a$it", "K", "S", "concept", 0, "A", "a$it", eligible = true, capabilityId = "A") }
        val b = LadderQuestion("b", "K", "S", "introduction", 0, "B", "b", eligible = true, capabilityId = "B")
        val dependent = LadderQuestion("c", "C", "S", "concept", 0, "C", "c", prerequisites = listOf("K"), eligible = true)
        var session = LadderSession()
        for (q in a) session = QuestionLadder.record(session, q, true, "")
        assertEquals("b", QuestionLadder.next(a + b + dependent, session).question!!.id)
        session = QuestionLadder.record(session, b, true, "")
        assertFalse(QuestionLadder.learned(session, "K"))
        assertNull(QuestionLadder.next(a + b + dependent, session).question)
        assertTrue("K" in QuestionLadder.next(a + b + dependent, session).blockedKps)
    }
}
