package com.coach.client

import com.coach.domain.Event
import com.coach.domain.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordsViewTest {
    private var seq = 0L
    private fun event(type: String, payload: JsonObject, thread: String? = "kp:K1") =
        Event(++seq, "e$seq", "C", type, recordedAt = 1_000 * seq, occurredAt = 1_000 * seq, deviceId = "d", actor = "LEARNER", threadId = thread, payload = payload)

    private fun outline() = event("outline_revised", json("ops" to JsonArray(listOf(JsonObject(mapOf("op" to JsonPrimitive("add"), "kpId" to JsonPrimitive("K1"), "title" to JsonPrimitive("逆向选择")))))))

    private fun choice(attempt: String, right: Boolean): List<Event> = listOf(
        event("question_posed", json("attemptId" to attempt, "questionId" to "q-$attempt", "groupId" to "g", "format" to "SINGLE_CHOICE", "kpIds" to listOf("K1"),
            "purpose" to "PRACTICE", "fingerprint" to "fp-$attempt", "stem" to "题干 $attempt",
            "options" to JsonArray(listOf("o1", "o2").map { JsonObject(mapOf("optionId" to JsonPrimitive(it), "text" to JsonPrimitive("选项 $it"))) }),
            "letterMap" to JsonObject(mapOf("o1" to JsonPrimitive("A"), "o2" to JsonPrimitive("B")), ), "keyState" to "VERIFIED")),
        event("answer_submitted", json("attemptId" to attempt, "answer" to if (right) "A" else "B")),
        event("attempt_graded", json("attemptId" to attempt, "score" to if (right) 1 else 0, "max" to 1, "status" to "EXACT", "feedback" to "反馈 $attempt")),
    )

    @Test fun threeCorrectAnswersAreOneChangeThatDrillsDownToEachQuestionAndAnswer() {
        val events = listOf(outline()) + choice("a1", true) + choice("a2", true) + choice("a3", true)
        val view = RecordsView(events)
        val change = view.changes.single()
        assertEquals(ChangeKind.PASSED, change.kind); assertEquals("K1", change.kpId)
        assertEquals(setOf("a1", "a2", "a3"), change.attemptIds.toSet())
        // Every attempt behind the change can be opened: original question, options, original answer, grade.
        for (id in change.attemptIds) {
            val d = assertNotNull(view.attempt(id))
            assertEquals("题干 $id", d.stem); assertEquals(listOf("A" to "选项 o1", "B" to "选项 o2"), d.options)
            assertEquals("A", d.answer); assertEquals(1, d.effectiveGrade?.score); assertTrue(d.counted)
        }
    }

    @Test fun wrongAnswerAfterPassingIsNeedsRepairNotARevoke() {
        val events = listOf(outline()) + choice("a1", true) + choice("a2", true) + choice("a3", true) + choice("a4", false)
        val kinds = RecordsView(events).changes.map { it.kind }
        assertEquals(listOf(ChangeKind.PASSED, ChangeKind.NEEDS_REPAIR), kinds)
    }

    @Test fun voidingEvidenceRevokesWithTheReason() {
        val events = listOf(outline()) + choice("a1", true) + choice("a2", true) + choice("a3", true) +
            event("attempt_voided", json("attemptId" to "a2", "reason" to "key_wrong"))
        val revoke = RecordsView(events).changes.last()
        assertEquals(ChangeKind.PASS_REVOKED, revoke.kind)
        assertTrue("作废" in revoke.reason, revoke.reason)
        assertEquals("attempt_voided", revoke.causeEventType)
    }

    @Test fun nothingChangesBeforeAThirdCorrectAnswer() {
        assertTrue(RecordsView(listOf(outline()) + choice("a1", true) + choice("a2", true)).changes.isEmpty())
    }

    @Test fun orphanedAnswerShowsItsReasonAndOriginalText() {
        val view = RecordsView(listOf(outline(),
            event("answer_orphaned", json("attemptId" to "x", "reason" to "QUESTION_CHANGED", "rawAnswer" to "B", "posedSnapshot" to json("stem" to "旧题干")))))
        val o = view.orphans.single()
        assertEquals("x", o.attemptId); assertEquals("旧题干", o.stem); assertEquals("B", o.rawAnswer); assertTrue("题目已经变了" in o.reason)
    }

    @Test fun constructedAttemptKeepsEvidenceIntervalsGraderAndReviewerModels() {
        val answer = "免赔额是先自付的部分，共付额是之后按比例分担。"
        val posed = event("question_posed", json("attemptId" to "c1", "questionId" to "q-c1", "groupId" to "g", "format" to "SHORT_ANSWER", "kpIds" to listOf("K1"),
            "purpose" to "CLOSED_BOOK", "fingerprint" to "fp", "stem" to "区分免赔额与共付额。"))
        val submitted = event("answer_submitted", json("attemptId" to "c1", "answer" to answer))
        val graded = event("attempt_graded", json("attemptId" to "c1", "score" to 300, "max" to 400, "status" to "AI_UNREVIEWED", "graderModel" to "gpt-5",
            "items" to JsonArray(listOf(json("id" to "R1", "points" to 2.0, "reason" to "写出了免赔额", "evidence" to "免赔额是先自付的部分"))),
            "evidence" to JsonArray(listOf(json("rubricItemId" to "R1", "field" to "answer", "start" to 0, "end" to 10, "text" to answer.substring(0, 10))))))
        val before = RecordsView(listOf(outline(), posed, submitted, graded)).attempt("c1")!!
        assertEquals("gpt-5", before.grade?.graderModel); assertNull(before.review); assertTrue(!before.counted, "an unreviewed draft is not counted")
        val reviewed = event("grade_reviewed", json("attemptId" to "c1", "decision" to "CONFIRM", "note" to "同意", "reviewerModel" to "claude-sonnet-5-5", "graderModel" to "gpt-5",
            "revised" to json("score" to 300, "max" to 400)))
        val after = RecordsView(listOf(outline(), posed, submitted, graded, reviewed)).attempt("c1")!!
        assertEquals("claude-sonnet-5-5", after.review?.reviewerModel); assertTrue(after.counted)
        val span = after.grade!!.evidence.single()
        assertEquals(span.text, after.answer.substring(span.start, span.end))
    }

    @Test fun rubricComesFromTheFrozenQuestionOnTheServerRecord() {
        val record = json("rubricFingerprint" to "fp1", "question" to json("stem" to "题", "reference_answer" to "参考答案",
            "rubric" to JsonArray(listOf(json("id" to "R1", "text" to "写出定义", "points" to 2, "basis" to "教材原文")))))
        val rubric = assertNotNull(rubricFrom(record))
        assertEquals("参考答案", rubric.referenceAnswer); assertEquals("写出定义", rubric.rubric.single().text); assertEquals("fp1", rubric.fingerprint)
        assertNull(rubricFrom(json("question" to json("stem" to "x"))))
    }
}
