package com.coach.server

import com.coach.client.CourseView
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.store.LedgerStore
import com.coach.store.sha256
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/** Exercises the three candidate features on one isolated ledger and replays it after restart. */
class IntegratedCandidateTest {
    private fun wire(type: String, payload: JsonObject): WireCommand {
        val bytes = LedgerJson.encodeToString(CommandBody(type, "C", 1000, payload))
        return WireCommand("test-device", UUID.randomUUID().toString(), bytes, sha256(bytes.toByteArray()))
    }

    @Test fun weeklyRetryFeedbackAndMistakeReviewSurviveRestartAndContinueTheWeek() {
        val dir = Files.createTempDirectory("coach-integrated-candidate-")
        try {
            var db = LedgerStore(dir)
            db.append("C", "outline_revised", json(
                "knowledgePoints" to JsonArray(listOf(
                    json("id" to "a", "title" to "【W2·Ch3】First", "sourceAnchor" to json("docId" to "book", "pdfPage" to 7, "bookPage" to 5)),
                    json("id" to "b", "title" to "【W2·Ch3】Second")
                )),
                "ops" to JsonArray(listOf(json("kpId" to "a", "title" to "【W2·Ch3】First"), json("kpId" to "b", "title" to "【W2·Ch3】Second")))
            ))
            assertEquals("a", db.command(wire("set_study_scope", json("week" to 2))).result.str("target"))
            val options = listOf(
                Option("yes", "Right"), Option("no", "Wrong"),
                Option("other1", "Distractor one"), Option("other2", "Distractor two")
            )
            for (n in 0..3) db.registerQuestion("q$n", "C", "", "SINGLE_CHOICE", "Prompt $n", "VERIFIED", "yes", options, listOf("a"))
            fun submit(question: String, attempt: String, correct: Boolean) {
                val posed = db.poseChoiceGroup("C", "group-$attempt", listOf(json("attemptId" to attempt, "questionId" to question, "kpIds" to listOf("a"), "purpose" to "PRACTICE", "policyVersion" to 1))).single()
                val letter = (posed.payload["letterMap"] as JsonObject).str(if (correct) "yes" else "no")
                assertEquals("OK", db.command(wire("submit_answer", json("attemptId" to attempt, "answer" to letter, "posedSnapshot" to posed.payload))).status)
            }
            submit("q0", "wrong", false)
            val graded = db.events("C").last { it.type == "attempt_graded" }
            assertTrue(graded.payload.str("feedback").contains("Wrong"))
            assertTrue(graded.payload.str("correctAnswer").contains("Right"))
            assertEquals(1, weekMistakes(db.events("C"), 2).size)
            assertEquals("OK", db.command(wire("retry_week_mistake", json("week" to 2, "attemptId" to "wrong"))).status)
            val retry = db.events("C").last { it.type == "question_posed" }
            val right = (retry.payload["letterMap"] as JsonObject).str("yes")
            assertEquals("OK", db.command(wire("submit_answer", json("attemptId" to retry.payload.str("attemptId"), "answer" to right, "posedSnapshot" to retry.payload))).status)
            assertTrue(weekMistakes(db.events("C"), 2).isEmpty())
            submit("q1", "new1", true)
            submit("q2", "new2", true)
            assertEquals(1, CourseView(db.events("C"), db.snapshot("C").projection).mistakes.items.size)
            db.close()
            db = LedgerStore(dir)
            val restored = CourseView(db.events("C"), db.snapshot("C").projection)
            assertEquals("a", restored.weekTarget?.id)
            assertEquals("book", restored.kp("a")?.anchorDoc)
            assertEquals(7, restored.kp("a")?.anchorPdfPage)
            assertEquals(1, restored.mistakes.items.size)
            submit("q3", "new3", true)
            assertTrue(CourseView(db.events("C"), db.snapshot("C").projection).mistakes.items.isEmpty())
            val scope = assertNotNull(db.snapshot("C").projection.studyScope)
            assertEquals("b", db.command(wire("advance_study_scope", json("scopeId" to scope.id, "kpId" to "a"))).result.str("target"))
            db.close()
        } finally { dir.toFile().deleteRecursively() }
    }
}
