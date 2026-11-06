package com.coach.store

import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class WeekStudyTest {
    private fun wire(type: String, payload: kotlinx.serialization.json.JsonObject, device: String = "a"): WireCommand {
        val bytes = LedgerJson.encodeToString(CommandBody(type, "C", 1000, payload))
        return WireCommand(device, UUID.randomUUID().toString(), bytes, sha256(bytes.toByteArray()))
    }
    private fun seed(db: LedgerStore) {
        db.append("C", "outline_revised", json("ops" to JsonArray(listOf(
            json("kpId" to "b", "title" to "【W2·Ch3】First"),
            json("kpId" to "a", "title" to "【W2·slides】Second"),
            json("kpId" to "c", "title" to "【W3·Ch4】Third"),
            json("kpId" to "d", "title" to "【W2·Ch3】Fourth")
        ))))
    }
    @Test fun scopeSurvivesRestartAndAnotherDeviceCannotSkipAfterAConcurrentAdvance() {
        val dir = Files.createTempDirectory("coach-week-")
        try {
            var db = LedgerStore(dir)
            seed(db)
            db.append("C", "kp_overridden", json("kpId" to "a", "kind" to "MARK_PASSED"))
            val select = wire("set_study_scope", json("week" to 2))
            val selected = db.command(select)
            assertEquals("OK", selected.status)
            assertEquals("b", selected.result.str("target"))
            assertEquals("DUPLICATE", db.command(select).status)
            val active = assertNotNull(db.snapshot("C").projection.studyScope)
            assertEquals(listOf("b", "d"), active.kpIds)
            val before = db.snapshot("C").projection.mastery
            val next = json("scopeId" to active.id, "kpId" to "b")
            assertEquals("d", db.command(wire("advance_study_scope", next)).result.str("target"))
            assertEquals("CONFLICT", db.command(wire("advance_study_scope", next, "other-device")).status)
            assertEquals(before, db.snapshot("C").projection.mastery)
            db.close(); db = LedgerStore(dir)
            assertEquals("d", db.snapshot("C").projection.studyScope?.target(db.snapshot("C").projection.mastery))
            assertEquals(db.snapshot("C").projection.studyScope, project(db.events("C")).studyScope)
            db.append("C", "kp_overridden", json("kpId" to "d", "kind" to "MARK_PASSED"))
            assertNull(db.snapshot("C").projection.studyScope?.target(db.snapshot("C").projection.mastery))
            assertEquals("REJECTED", db.command(wire("set_study_scope", json("week" to 99))).status)
            assertEquals("c", db.command(wire("set_study_scope", json("week" to 3))).result.str("target"))
            assertEquals("CONFLICT", db.command(wire("advance_study_scope", next)).status)
            db.command(wire("set_study_scope", json("week" to 0)))
            assertNull(db.snapshot("C").projection.studyScope)
            db.close()
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun originalMistakeIsReposedAndServerGradedWithoutChangingScopeOrCountingFreshEvidence() {
        val dir = Files.createTempDirectory("coach-week-mistake-")
        try { LedgerStore(dir).use { db ->
            seed(db)
            db.command(wire("set_study_scope", json("week" to 2)))
            val scope = db.snapshot("C").projection.studyScope
            val options = listOf(Option("yes", "Right"), Option("no", "Wrong"))
            db.registerQuestion("q", "C", "", "SINGLE_CHOICE", "Original prompt", "VERIFIED", "yes", options, listOf("b"))
            val original = db.poseChoiceGroup("C", "g", listOf(json("attemptId" to "old", "questionId" to "q", "kpIds" to listOf("b"), "purpose" to "PRACTICE", "policyVersion" to 1))).single()
            val wrong = original.payload["letterMap"]!!.let { (it as kotlinx.serialization.json.JsonObject).str("no") }
            assertEquals("OK", db.command(wire("submit_answer", json("attemptId" to "old", "answer" to wrong, "posedSnapshot" to original.payload))).status)
            assertEquals(1, weekMistakes(db.events("C"), 2).size)
            assertEquals("REJECTED", db.command(wire("retry_week_mistake", json("week" to 3, "attemptId" to "old"))).status)
            val retry = db.command(wire("retry_week_mistake", json("week" to 2, "attemptId" to "old")))
            assertEquals("OK", retry.status)
            val posed = db.events("C").last { it.type == "question_posed" }
            assertNotEquals("old", posed.payload.str("attemptId"))
            assertEquals("Original prompt", posed.payload.str("stem"))
            val right = (posed.payload["letterMap"] as kotlinx.serialization.json.JsonObject).str("yes")
            val answered = db.command(wire("submit_answer", json("attemptId" to posed.payload.str("attemptId"), "answer" to right, "posedSnapshot" to posed.payload)))
            assertEquals("OK", answered.status)
            assertTrue(weekMistakes(db.events("C"), 2).isEmpty())
            assertEquals(scope, db.snapshot("C").projection.studyScope)
            assertFalse(db.snapshot("C").projection.mastery.getValue("b").passed)
        } } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun repickingTheAnswerJustShownDoesNotRepairAPassedPointOrPushItsReview() {
        val dir = Files.createTempDirectory("coach-week-repair-")
        try { LedgerStore(dir).use { db ->
            seed(db)
            db.append("C", "kp_overridden", json("kpId" to "b", "kind" to "MARK_PASSED"))
            val options = listOf(Option("yes", "Right"), Option("no", "Wrong"))
            db.registerQuestion("q", "C", "", "SINGLE_CHOICE", "Original prompt", "VERIFIED", "yes", options, listOf("b"))
            val original = db.poseChoiceGroup("C", "g", listOf(json("attemptId" to "old", "questionId" to "q", "kpIds" to listOf("b"), "purpose" to "PRACTICE", "policyVersion" to 1))).single()
            val wrong = (original.payload["letterMap"] as kotlinx.serialization.json.JsonObject).str("no")
            assertEquals("OK", db.command(wire("submit_answer", json("attemptId" to "old", "answer" to wrong, "posedSnapshot" to original.payload))).status)
            val repair = db.snapshot("C").projection
            assertTrue(repair.mastery.getValue("b").needsRepair)
            assertEquals("OK", db.command(wire("retry_week_mistake", json("week" to 2, "attemptId" to "old"))).status)
            val posed = db.events("C").last { it.type == "question_posed" }
            val right = (posed.payload["letterMap"] as kotlinx.serialization.json.JsonObject).str("yes")
            assertEquals("OK", db.command(wire("submit_answer", json("attemptId" to posed.payload.str("attemptId"), "answer" to right, "posedSnapshot" to posed.payload))).status)
            // The feedback just showed the right option; picking it again is not a spaced review.
            val after = db.snapshot("C").projection
            assertTrue(after.mastery.getValue("b").needsRepair, "repair cleared by re-picking the shown answer")
            assertEquals(repair.review["b"], after.review["b"])
        } } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun weekMistakeFromAnOfflinePackCanBeRetriedWhileThePackIsLive() {
        val dir = Files.createTempDirectory("coach-week-offline-")
        try { LedgerStore(dir).use { db ->
            seed(db)
            val options = (1..4).map { Option("o$it", "Option $it") }
            db.registerQuestion("q", "C", "", "SINGLE_CHOICE", "Pack prompt", "VERIFIED", "o1", options, listOf("b"))
            val card = db.downloadOfflinePack("C", "a").groups.flatten().single { it.payload.str("questionId") == "q" }.payload
            val wrong = (card["letterMap"] as kotlinx.serialization.json.JsonObject).str("o2")
            assertEquals("OK", db.command(wire("submit_answer", json("attemptId" to card.str("attemptId"), "answer" to wrong, "offline" to true, "posedSnapshot" to card))).status)
            assertEquals("OK", db.command(wire("retry_week_mistake", json("week" to 2, "attemptId" to card.str("attemptId")))).status)
            val posed = db.events("C").last { it.type == "question_posed" }
            val right = (posed.payload["letterMap"] as kotlinx.serialization.json.JsonObject).str("o1")
            val retried = db.command(wire("submit_answer", json("attemptId" to posed.payload.str("attemptId"), "answer" to right, "posedSnapshot" to posed.payload)))
            assertEquals("OK", retried.status, "retry rejected: ${retried.error}")
            assertTrue(weekMistakes(db.events("C"), 2).isEmpty())
        } } finally { dir.toFile().deleteRecursively() }
    }
}
