package com.coach.ai

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.domain.grade.NumericValue
import com.coach.store.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Each step persists before the next model call. Restart never loses an unreviewed grade. */
class ConstructedGradeWorker(private val actor: LedgerActor, private val client: BridgeClient,
    private val onChanged: suspend (String) -> Unit = {}) {
    suspend fun runOne(): Boolean {
        val now=java.time.Instant.now()
        if(now>=java.time.Instant.parse("2026-11-02T00:00:00Z") && now<java.time.Instant.parse("2026-11-02T15:00:00Z")) return false
        val work=actor.execute { claimGradeWork() } ?: return false
        try {
            val input=work.subject
            val question=TaskParsing.constructed(JsonArray(listOf(input.getValue("question"))).toString()).single()
            require(GradeGate.rubricFingerprint(question.rubric)==work.fingerprint) { "rubric_changed" }
            val answer=input.str("answer")
            require(sha256(json("answer" to answer,"finalAnswer" to input.str("finalAnswer")).toString().toByteArray(Charsets.UTF_8))==work.answerHash) { "answer_changed" }
            val numeric=(input.getValue("question").jsonObject["numeric_final"] as? JsonObject)
            val process=if(numeric==null) question else {
                require(NumericValue.parse(numeric.str("expected"))!=null) { "invalid_numeric_key" }
                require(question.rubric.count { it.id==numeric.str("rubricItemId") }==1)
                question.copy(stem=question.stem+"\nFull frozen rubric (numeric final is server-owned, do not grade it):\n"+
                    Prompts.rubricJson(question.rubric)+"\nServer numeric criterion: $numeric"+"\nSubmitted final value (verbatim, server-owned): <<<${input.str("finalAnswer")}>>>",
                    rubric=question.rubric.filter { it.id!=numeric.str("rubricItemId") })
            }
            require(process.rubric.isNotEmpty()) { "missing_process_rubric" }
            val pipeline=GradePipeline(client)
            if(work.kind=="grade_attempt") {
                var draft=withContext(Dispatchers.IO) { pipeline.gradeConstructed(process,answer,draftOnly=true) }
                var fallbackNotice: String?=null
                if(draft.calls.any { it.error?.contains("429")==true } || draft.defects.any { it.contains("429") }) {
                    val first=draft.calls
                    draft=withContext(Dispatchers.IO) { pipeline.gradeConstructed(process,answer,
                        graderModel=BridgeClient.CODEX,reviewerModel=BridgeClient.CLAUDE,draftOnly=true) }
                    draft=draft.copy(calls=first+draft.calls)
                    fallbackNotice="Claude 当前限流，改由 ${BridgeClient.CODEX} 批改；异厂复核后才计入。"
                }
                actor.execute { draft.calls.forEach { recordAiCall(it.toJson(),"foreground") } }
                if(draft.defects.isNotEmpty() || draft.draft==null) {
                    actor.execute { failGradeWork(work,draft.defects.joinToString(),false) };return true
                }
                val grader=draft.calls.last().servedBy ?: error("missing_grader_identity")
                val payload=gradePayload(requireNotNull(draft.draft),answer,numeric,question,input.str("finalAnswer"),draft.evidenceSpans)
                val stored=JsonObject(payload + json("status" to "AI_UNREVIEWED","graderModel" to grader,
                    "rubricFingerprint" to work.fingerprint,"processGrade" to storedDraft(draft.draft),
                    "callIds" to draft.calls.map { it.callId },"notice" to fallbackNotice))
                if(actor.execute { persistGradeDraft(work,stored,grader) }) onChanged(input.str("course"))
            } else {
                val grader=input.str("grader")
                val reviewer=if(modelVendor(grader)=="openai") BridgeClient.CLAUDE else BridgeClient.CODEX
                val frozen=input.getValue("draft").jsonObject
                val draft=TaskParsing.grade(frozen.getValue("processGrade").toString())
                val prepared=GradePipeline.Outcome(false,draft.awarded,emptyList(),emptyList(),null,
                    GradeGate.evidence(answer,draft).second,GradeGate.rubricFingerprint(process.rubric),draft)
                val reviewed=withContext(Dispatchers.IO) { pipeline.reviewDraft(process,answer,prepared,reviewer) }
                actor.execute { reviewed.calls.forEach { recordAiCall(it.toJson(),"background") } }
                val served=reviewed.calls.lastOrNull()?.servedBy
                if(!reviewed.written || served.isNullOrBlank() || modelVendor(served)==modelVendor(grader)) {
                    actor.execute { failGradeWork(work,reviewed.defects.joinToString().ifBlank { "review_vendor_mismatch" }) };return true
                }
                val final=gradePayload(requireNotNull(reviewed.draft),answer,numeric,question,input.str("finalAnswer"),reviewed.evidenceSpans)
                val decision=requireNotNull(reviewed.crossReview).verdict
                val committed=actor.execute { persistGradeReview(work,json("decision" to decision,"note" to reviewed.crossReview.note,
                    "reviewerModel" to served,"graderModel" to grader,"rubricFingerprint" to work.fingerprint,
                    "callIds" to reviewed.calls.map { it.callId },"revised" to final)) }
                if(committed) onChanged(input.str("course"))
            }
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { actor.execute { failGradeWork(work,e.javaClass.simpleName) } }
        return true
    }
}

internal fun storedDraft(draft: DraftGrade): JsonObject = json("items" to JsonArray(draft.items.map {
    json("id" to it.id,"points" to it.points,"reason" to it.reason,"evidence" to it.evidence)
}),"total" to draft.total,"awarded" to draft.awarded)

/** Only the separate final-value field is compared; the model receives no authority over its points. */
internal fun gradePayload(process: DraftGrade, answer: String, numeric: JsonObject?, question: ConstructedQuestion,
    finalAnswer: String, spans: List<GradedEvidence>): JsonObject {
    var grade=process
    var numericFact: JsonObject?=null
    if(numeric!=null) {
        val item=question.rubric.single { it.id==numeric.str("rubricItemId") }
        val rel=numeric["relTolerance"]?.jsonPrimitive?.double ?: NumericValue.DEFAULT_REL_TOLERANCE
        val abs=numeric["absTolerance"]?.jsonPrimitive?.double ?: NumericValue.DEFAULT_ABS_TOLERANCE
        require(rel.isFinite() && abs.isFinite() && rel>=0 && abs>=0)
        val correct=NumericValue.matches(finalAnswer,numeric.str("expected"),rel,abs)
        val points=if(correct) item.points else 0.0
        grade=DraftGrade(process.items+GradeItem(item.id,points,"SERVER_NUMERIC",finalAnswer),question.rubric.sumOf { it.points },process.awarded+points)
        numericFact=json("expected" to numeric.str("expected"),"submitted" to finalAnswer,"correct" to correct,
            "relTolerance" to rel,"absTolerance" to abs,"rubricItemId" to item.id)
    }
    require(GradeGate.arithmetic(grade,question.rubric).isEmpty()) { "invalid_merged_grade" }
    val evidence=spans.map { e ->
        require(answer.substring(e.span.start,e.span.end)==e.span.text)
        json("rubricItemId" to e.rubricItemId,"field" to "answer","start" to e.span.start,"end" to e.span.end,"text" to e.span.text)
    } + if(numeric!=null && finalAnswer.isNotEmpty()) listOf(json("rubricItemId" to numeric.str("rubricItemId"),
        "field" to "finalAnswer","start" to 0,"end" to finalAnswer.length,"text" to finalAnswer,"method" to "SERVER_NUMERIC")) else emptyList()
    return json("score" to kotlin.math.round(grade.awarded*100).toLong(),"max" to kotlin.math.round(grade.total*100).toLong(),
        "items" to storedDraft(grade).getValue("items"),"numericFinal" to numericFact,
        "evidence" to JsonArray(evidence))
}
