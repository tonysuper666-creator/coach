package com.coach.server

import com.coach.client.RecordsView
import com.coach.domain.*
import com.coach.store.LedgerStore
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

/** Synthetic audit: historical numeric grades remain facts, but unchecked keys never count. */
class PendingScoreContractAudit {
    private fun audit(check: (LedgerStore, Mainline, JsonObject) -> Unit) {
        val root = Files.createTempDirectory("pending-score-contract")
        try {
            val papers = Files.createDirectories(root.resolve("papers/mcq-mainline"))
            val item = json("id" to "pending", "type" to "mcq", "kp_week" to 1, "kp" to "kp",
                "key_state" to "UNREVIEWED", "fact_ids" to listOf("F1"), "tier" to "setup", "marks" to 1,
                "stem" to "Synthetic orchard fruit", "options" to listOf("Pears", "Figs"), "answer" to 0,
                "explanation" to "正确：Synthetic explanation.", "source" to "Synthetic source")
            val pack = json("id" to "synthetic", "kind" to "pack", "course" to "C", "minutes" to 10,
                "sections" to JsonArray(listOf(json("title" to "Synthetic", "instructions" to "Synthetic",
                    "choose" to null, "items" to JsonArray(listOf(item))))))
            Files.writeString(papers.resolve("W01.json"), pack.toString())
            val now = 1_800_000_000_000L
            LedgerStore(Files.createDirectories(root.resolve("data"))) { now }.use { store ->
                store.append("C", "outline_revised", json("knowledgePoints" to JsonArray(listOf(
                    json("id" to "kp", "title" to "Synthetic", "week" to 1)))))
                val engine = Mainline(MockLibrary(root.resolve("papers")), clock = { now })
                val result = engine.answer(store, "C", 1, json("itemId" to "pending", "choice" to 0, "submissionId" to "audit"))
                assertEquals("pending", result.str("status"))
                assertFalse(store.snapshot("C").projection.mastery.getValue("kp").passed)
                check(store, engine, result)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun unverifiedNumericalGradeDoesNotCountDownstream() = audit { store, _, result ->
        val events = store.events("C")
        val grade = events.single { it.type == "attempt_graded" }.payload
        assertEquals("EXACT", grade.str("status"))
        assertEquals(grade.num("max"), grade.num("score"))
        val state = store.snapshot("C").projection
        assertEquals(0, state.journal.values.sumOf { it.correct })
        assertTrue(state.mastery.values.none { it.passed || it.solid })
        val detail = assertNotNull(RecordsView(events).attempt(result.str("attemptId")))
        assertTrue(detail.pendingKey)
        assertFalse(detail.counted)
        assertNull(detail.effectiveGrade)
    }

    @Test fun replayDoesNotCountUnverifiedChoiceAsCorrect() = audit { store, _, _ ->
        val events = store.events("C")
        val actual = project(events)
        assertEquals(actual, project(events.reversed()))
        assertEquals(0, actual.journal.values.sumOf { it.correct }, "Replay must not add unverified choices to correct-answer totals")
    }

    @Test fun recordsDoNotShowUnverifiedChoiceAsCounted() = audit { store, _, result ->
        val detail = assertNotNull(RecordsView(store.events("C")).attempt(result.str("attemptId")))
        assertFalse(detail.counted, "Record view must not count a provisional exact grade")
        assertNull(detail.effectiveGrade, "Record view must not expose provisional numerical scores")
    }

    @Test fun retryPreservesTheFirstAnswerWithoutDuplicateEvents() = audit { store, engine, result ->
        val before = store.events("C")
        assertEquals(result, engine.answer(store, "C", 1, json("itemId" to "pending", "choice" to 1,
            "guessed" to true, "submissionId" to "audit")))
        assertEquals(before, store.events("C"))
        assertEquals(store.snapshot("C").projection, project(before))
    }
}
