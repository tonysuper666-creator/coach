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
            val working=input.str("answer")
            require(sha256(json("answer" to working,"finalAnswer" to input.str("finalAnswer")).toString().toByteArray(Charsets.UTF_8))==work.answerHash) { "answer_changed" }
            val numeric=(input.getValue("question").jsonObject["numeric_final"] as? JsonObject)
            val feedbackOnly=numeric?.flag("feedbackOnly")==true
            // Full rubrics can award marks based on either field. Keep both available for
            // evidence validation; map the resulting spans back to their original fields.
            val answer=if(feedbackOnly) working+FINAL_SEPARATOR+input.str("finalAnswer") else working
            val process=if(numeric==null) question else {
                require(NumericValue.parse(numeric.str("expected"))!=null) { "invalid_numeric_key" }
                val strict=analyticalNumericKey(numeric) // Validate before spending a model call.
                require(if(feedbackOnly) strict!=null else question.rubric.count { it.id==numeric.str("rubricItemId") }==1)
                question.copy(stem=question.stem+(if(feedbackOnly)
                    "\nFull frozen rubric: grade both submitted fields against these criteria. The server independently checks final format and value without adding marks.\n"
                    else "\nFull frozen rubric (numeric final is server-owned, do not grade it):\n")+
                    Prompts.rubricJson(question.rubric)+"\nServer numeric criterion: $numeric"+"\nSubmitted final value (verbatim, server-owned): <<<${input.str("finalAnswer")}>>>",
                    rubric=question.rubric.filter { feedbackOnly || it.id!=numeric.str("rubricItemId") })
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
                    // A bridge that is restarting or rate limited is not a verdict on the answer; only a bad grade is final.
                    val outage=draft.defects.isNotEmpty() && draft.defects.all { it.startsWith("grade_call_") }
                    actor.execute { failGradeWork(work,draft.defects.joinToString(),outage) };return true
                }
                val grader=draft.calls.last().servedBy ?: error("missing_grader_identity")
                val payload=gradePayload(requireNotNull(draft.draft),working,numeric,question,input.str("finalAnswer"),draft.evidenceSpans)
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
                val final=gradePayload(requireNotNull(reviewed.draft),working,numeric,question,input.str("finalAnswer"),reviewed.evidenceSpans)
                val decision=requireNotNull(reviewed.crossReview).verdict
                val committed=actor.execute { persistGradeReview(work,json("decision" to decision,"note" to reviewed.crossReview.note,
                    "reviewerModel" to served,"graderModel" to grader,"rubricFingerprint" to work.fingerprint,
                    "callIds" to reviewed.calls.map { it.callId },"revised" to final)) }
                if(committed) onChanged(input.str("course"))
            }
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { actor.execute { failGradeWork(work,e.javaClass.simpleName,work.kind=="review_grade") } }
        return true
    }
}

internal fun storedDraft(draft: DraftGrade): JsonObject = json("items" to JsonArray(draft.items.map {
    json("id" to it.id,"points" to it.points,"reason" to it.reason,"evidence" to it.evidence)
}),"total" to draft.total,"awarded" to draft.awarded)

// Keep descriptive labels in the prompt, outside quotable student evidence.
private const val FINAL_SEPARATOR="\n\n"

/** Only the separate final-value field is compared; the model receives no authority over its points. */
internal fun gradePayload(process: DraftGrade, answer: String, numeric: JsonObject?, question: ConstructedQuestion,
    finalAnswer: String, spans: List<GradedEvidence>): JsonObject {
    var grade=process
    var numericFact: JsonObject?=null
    if(numeric!=null) {
        val item=if(numeric.flag("feedbackOnly")) null else question.rubric.single { it.id==numeric.str("rubricItemId") }
        val analytical=analyticalNumericKey(numeric)
        val checked=analytical?.grade(finalAnswer)
        val rel=numeric["relTolerance"]?.jsonPrimitive?.double ?: if(analytical!=null) 0.0 else NumericValue.DEFAULT_REL_TOLERANCE
        val abs=numeric["absTolerance"]?.jsonPrimitive?.double ?: NumericValue.DEFAULT_ABS_TOLERANCE
        require(rel.isFinite() && abs.isFinite() && rel>=0 && abs>=0)
        val correct=checked?.correct ?: NumericValue.matches(finalAnswer,numeric.str("expected"),rel,abs)
        if(item!=null) {
            val points=if(correct) item.points else 0.0
            grade=DraftGrade(process.items+GradeItem(item.id,points,"SERVER_NUMERIC",finalAnswer),question.rubric.sumOf { it.points },process.awarded+points)
        }
        numericFact=json("expected" to numeric.str("expected"),"submitted" to finalAnswer,"correct" to correct,
            "relTolerance" to rel,"absTolerance" to abs,"rubricItemId" to item?.id,"feedbackOnly" to (item==null))
        if(checked!=null) numericFact=JsonObject(numericFact + mapOf("decimalPlaces" to JsonPrimitive(2),
            "status" to JsonPrimitive(checked.status),"absTolerance" to numeric.getValue("absTolerance")))
    }
    require(GradeGate.arithmetic(grade,question.rubric).isEmpty()) { "invalid_merged_grade" }
    val feedbackOnly=numeric?.flag("feedbackOnly")==true
    val gradingAnswer=if(feedbackOnly) answer+FINAL_SEPARATOR+finalAnswer else answer
    val finalOffset=answer.length+FINAL_SEPARATOR.length
    val evidence=spans.flatMap { e ->
        require(gradingAnswer.substring(e.span.start,e.span.end)==e.span.text)
        val fields=if(feedbackOnly) listOf(Triple("answer",0,answer),Triple("finalAnswer",finalOffset,finalAnswer))
            else listOf(Triple("answer",0,answer))
        fields.mapNotNull { (field,offset,value) ->
            val start=maxOf(e.span.start,offset)-offset
            val end=minOf(e.span.end,offset+value.length)-offset
            if(start>=end) null else json("rubricItemId" to e.rubricItemId,"field" to field,
                "start" to start,"end" to end,"text" to value.substring(start,end))
        }
    } + if(numeric!=null && finalAnswer.isNotEmpty()) listOf(json("rubricItemId" to if(feedbackOnly) null else numeric.str("rubricItemId"),
        "field" to "finalAnswer","start" to 0,"end" to finalAnswer.length,"text" to finalAnswer,"method" to "SERVER_NUMERIC")) else emptyList()
    return json("score" to kotlin.math.round(grade.awarded*100).toLong(),"max" to kotlin.math.round(grade.total*100).toLong(),
        "items" to storedDraft(grade).getValue("items"),"numericFinal" to numericFact,
        "evidence" to JsonArray(evidence))
}
