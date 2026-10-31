package com.coach.ai

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.store.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Opt-in integration probe. Every database and raw answer stays in the private corpus. */
fun main() = runBlocking {
    val now=Instant.now()
    require(now<Instant.parse("2026-11-02T00:00:00Z") || now>=Instant.parse("2026-11-02T15:00:00Z")) { "Exam protection: no live calls" }
    val fixture=Path.of(requireNotNull(System.getenv("COACH_GRADE_FIXTURE")) { "COACH_GRADE_FIXTURE required" })
    val input=LedgerJson.parseToJsonElement(Files.readString(fixture)).jsonObject
    require(input.getValue("rubric_review").jsonObject.str("verdict")=="CONFIRM") { "Need a previously reviewed rubric fixture" }
    val question=TaskParsing.constructed(JsonArray(listOf(input.getValue("question"))).toString()).single()
    val answer=question.referenceAnswer // Clearly labelled reference replay; not a human benchmark score.
    val output=Path.of(requireNotNull(System.getenv("COACH_GRADE_PROBE_OUT")))
    Files.createDirectories(output)
    val directory=Files.createTempDirectory(output,"ledger-probe-")
    val actor=LedgerActor(this,LedgerStore(directory))
    try {
        actor.execute {
            val subject=json("course" to "probe","kp" to "kp","generator" to BridgeClient.CLAUDE)
            val id=enqueueJob("review_constructed",subject.toString(),"fixture")
            check(storeConstructed(claimJob(id)!!,SupplyJob(id,"review_constructed",subject,"fixture"),
                storedConstructed(question),GradeGate.rubricFingerprint(question.rubric),BridgeClient.CODEX))
            val posed=takeConstructed("probe","kp","reference")
            val bytes=LedgerJson.encodeToString(CommandBody("submit_answer","probe",System.currentTimeMillis(),
                json("attemptId" to "reference","answer" to answer,"posedSnapshot" to posed.payload)))
            check(command(WireCommand("probe-device","submit",bytes,sha256(bytes.toByteArray()))).status=="OK")
        }
        val worker=ConstructedGradeWorker(actor,BridgeClient())
        check(worker.runOne())
        val before=actor.execute { snapshot("probe").projection.mastery["kp"] ?: Mastery() }
        check(before.window.isEmpty() && before.solidProgress.count==0)
        check(worker.runOne())
        val record=actor.execute { constructedRecord("probe","reference")!! }
        Files.writeString(directory.resolve("record.json"),record.toString())
        val facts=record.getValue("grades").jsonArray
        val review=facts.map { it.jsonObject }.firstOrNull { it.str("decision") in setOf("CONFIRM","REVISE") }
        val report=json("answerSource" to "REFERENCE_FIXTURE_NOT_HUMAN_BENCHMARK","draftEvents" to facts.count { it.jsonObject.str("status")=="AI_UNREVIEWED" },
            "unreviewedWindowCount" to before.window.size,"reviewDecision" to review?.str("decision"),
            "reviewedWindowCount" to actor.execute { snapshot("probe").projection.mastery["kp"]?.window?.size ?: 0 },
            "physicalCalls" to actor.execute { supplyCallCount() },"at" to Instant.now().toString())
        Files.writeString(directory.resolve("summary.json"),report.toString())
        println(report)
        check(review!=null) { "Live review not available; raw results retained privately" }
    } finally { actor.close() }
}
