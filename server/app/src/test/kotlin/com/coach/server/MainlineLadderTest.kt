package com.coach.server

import com.coach.domain.*
import com.coach.store.LedgerStore
import com.coach.store.sha256
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

/** Entirely invented teaching material. No exam, corpus, private mapping or real answer key. */
class MainlineLadderTest {
    private fun candidate() = json(
        "contract_version" to "1.0.0", "id" to "fruit1", "course_id" to "SYNTHETIC", "week" to 1,
        "kp_id" to "fruit", "section_id" to "orchard", "variant_group_id" to "fruit-group",
        "kind" to "concept", "difficulty" to 0, "prerequisite_kp_ids" to emptyList<String>(),
        "key_state" to "VERIFIED", "stem" to "A synthetic basket has two pears. How many pears?",
        "options" to listOf("One", "Two", "Three"), "answer" to 1, "remember" to "Count every pear once.",
        "utility" to json("rationale" to "Counting is needed before combining baskets.", "evidence" to listOf("synthetic-handbook-1")),
        "priority" to json("star" to 3, "star_source" to "Synthetic handbook 1", "lecture_emphasis" to "verified", "lecture_emphasis_source" to "Synthetic lecture 1"),
        "sources" to JsonArray(listOf(json("kind" to "textbook", "document_id" to "synthetic-book", "locator" to "p.1", "anchor" to "Count pears once."))),
        "explanation" to json("correct" to "There are two pears.", "background" to "Each pear contributes one.", "source" to "Synthetic book p.1",
            "wrong" to JsonArray(listOf(json("option" to 0, "reason" to "One pear was omitted."), json("option" to 2, "reason" to "A pear was counted twice.")))))

    private fun approved(candidate: JsonObject): JsonObject = JsonObject(candidate + ("runtime_approval" to json(
        "screening_decision" to "keep", "isolation_state" to "CLEARED", "release_state" to "APPROVED",
        "independent_key_evidence" to "synthetic-key-review", "utility_review_evidence" to "synthetic-utility-review",
        "originality_review_evidence" to "synthetic-originality-review",
        "candidate_sha256" to sha256(canonicalJson(candidate).encodeToByteArray()))))

    private fun item(learning: JsonObject?): MainlineItem = MainlineItem(
        MockUnit("fruit1", "fruit1", 0, "mcq", "fruit", 1.0, "fruit", 1,
            candidate().str("stem"), listOf("One", "Two", "Three"), 1, null, "0", emptyList(), "", "正确：Two.", "VERIFIED"),
        "detail", listOf("F1"), "Synthetic book p.1", "Count pears once.", learning)

    @Test fun storageKeyAndAuthorLabelsCannotReleaseACandidate() {
        assertNull(item(null).ladder())
        assertNull(item(candidate()).ladder())
        val approved = approved(candidate())
        assertTrue(assertNotNull(item(approved).ladder()).eligible)
        assertEquals(4, item(approved).ladder()!!.priority)
        assertTrue(item(approved).eligibleForLearning(setOf("SYNTHETIC"), 1))
        assertFalse(item(approved).eligibleForLearning(setOf("OTHER"), 1))
        assertFalse(item(approved).eligibleForLearning(setOf("SYNTHETIC"), 2))
        for (field in listOf("candidate_sha256", "independent_key_evidence", "utility_review_evidence", "originality_review_evidence", "release_state", "isolation_state", "screening_decision")) {
            val evidence = approved.getValue("runtime_approval").jsonObject
            val changed = JsonObject(approved + ("runtime_approval" to JsonObject(evidence - field)))
            assertFalse(assertNotNull(item(changed).ladder()).eligible, field)
        }
        val edited = JsonObject(approved + ("remember" to JsonPrimitive("Unreviewed edit")))
        assertFalse(item(edited).ladder()!!.eligible)
    }

    @Test fun signedCandidateRetainsCapabilityUnknownStarAndRejectsQuestionIdsAsKpPrerequisites() {
        fun candidateWith(vararg fields: Pair<String, JsonElement>) = approved(JsonObject(candidate() + fields))
        val wrong = item(candidateWith("prerequisite_kp_ids" to JsonArray(listOf(JsonPrimitive("fruit1"))))).ladder(setOf("fruit"))!!
        assertFalse(wrong.eligible)
        assertEquals(listOf("prerequisite_id_is_not_a_kp"), wrong.contractIssues)
        val valid = item(candidateWith("capability_id" to JsonPrimitive("SYN-count"),
            "prerequisite_kp_ids" to JsonArray(listOf(JsonPrimitive("fruit"))), "priority" to json("star" to "unknown"))).ladder(setOf("fruit"))!!
        assertTrue(valid.eligible); assertEquals("SYN-count", valid.capabilityId)
        assertNull(valid.priorityStar); assertEquals("unknown", valid.priorityStarState); assertEquals(1, valid.priority)
        val one = item(candidateWith("priority" to json("star" to 1, "star_source" to "SYN-source"))).ladder()!!
        assertEquals(1, one.priorityStar); assertEquals("verified", one.priorityStarState); assertEquals(1, one.priority)
    }

    @Test fun runtimeFeedWithholdsRemovedContentButOriginalRecordsAndDontKnowRetriesSurvive() {
        val dir = Files.createTempDirectory("synthetic-ladder")
        try {
            val root = Files.createDirectories(dir.resolve("papers/mcq-mainline"))
            var learning = approved(JsonObject(candidate() + ("essay" to json("model_answer_id" to "SYN-E1", "rubric_point_ids" to listOf("SYN-R1")))))
            fun write() {
                val raw = json("id" to "fruit1", "type" to "mcq", "kp" to "fruit", "kp_week" to 1, "marks" to 1,
                    "tier" to "detail", "key_state" to "VERIFIED", "stem" to candidate().str("stem"), "options" to listOf("One", "Two", "Three"),
                    "answer" to 1, "source" to "Synthetic book p.1", "fact_ids" to listOf("F1"), "explanation" to "正确：Two.", "learning" to learning)
                Files.writeString(root.resolve("W01.json"), json("id" to "synthetic-week", "course" to "SYNTHETIC", "kind" to "pack", "minutes" to 10,
                    "sections" to JsonArray(listOf(json("title" to "Synthetic orchard", "items" to JsonArray(listOf(raw)))))).toString())
            }
            write()
            LedgerStore(Files.createDirectories(dir.resolve("db"))).use { store ->
                store.append("SYNTHETIC", "outline_revised", json("knowledgePoints" to JsonArray(listOf(json("id" to "fruit", "title" to "Synthetic fruit", "week" to 1)))))
                val mainline = Mainline(MockLibrary(dir.resolve("papers")))
                val visible = mainline.week(store, "SYNTHETIC", 1, shuffled = true, learning = true)
                assertEquals(1, visible["items"]!!.jsonArray.size)
                val publicItem = visible["items"]!!.jsonArray.single().jsonObject
                assertFalse(publicItem.toString().contains("Count every pear once."))
                assertFalse("answer" in publicItem)
                val wire = json("itemId" to "fruit1", "dontKnow" to true, "learning" to true, "submissionId" to "direct-explanation", "layout" to publicItem.num("layout"))
                val result = mainline.answer(store, "SYNTHETIC", 1, wire)
                assertEquals(-1, result.num("choice").toInt())
                assertTrue(result.flag("dontKnow")); assertFalse(result.flag("independentCorrect"))
                assertEquals("Count every pear once.", result.str("remember"))
                assertEquals("SYN-E1", result["essay"]!!.jsonObject.str("model_answer_id"))
                val answer = store.events("SYNTHETIC").single { it.type == "answer_submitted" }
                assertEquals("", answer.payload.str("answer")); assertTrue(answer.payload.flag("dontKnow"))
                val before = store.events("SYNTHETIC")
                val approval = learning["runtime_approval"]!!.jsonObject
                learning = JsonObject(learning + ("runtime_approval" to JsonObject(approval + ("screening_decision" to JsonPrimitive("remove")))))
                write()
                assertTrue(mainline.week(store, "SYNTHETIC", 1, learning = true)["items"]!!.jsonArray.isEmpty())
                val replay = mainline.answer(store, "SYNTHETIC", 1, JsonObject(wire + ("choice" to JsonPrimitive(1)) - "dontKnow"))
                assertTrue(replay.flag("dontKnow")); assertFalse(replay.flag("independentCorrect"))
                assertEquals(before, store.events("SYNTHETIC"))
                assertFailsWith<MockError> { mainline.answer(store, "SYNTHETIC", 1, JsonObject(wire + ("submissionId" to JsonPrimitive("new-submit")))) }
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
