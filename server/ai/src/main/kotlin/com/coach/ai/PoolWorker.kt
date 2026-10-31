package com.coach.ai

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

/** One claimed job at a time; bridge I/O never blocks the ledger actor. Owner schedules runOne. */
class PoolWorker(
    private val actor: LedgerActor,
    private val client: BridgeClient,
    private val beforeCall: (String) -> Unit = {},
    private val requestType: String? = null,
) {
    data class Step(val jobId: String, val kind: String, val state: String, val inserted: Int = 0)

    suspend fun runOne(course: String? = null, kp: String? = null): Step? {
        val claimed = actor.execute {
            supplyJobs(course,kp).firstNotNullOfOrNull { job -> claimJob(job.id)?.let { job to it } }
        } ?: return null
        val (job, token) = claimed
        val input = job.subject
        val background = input.flag("background")
        val type = requestType ?: if (background) "background" else "foreground"
        if (job.kind in setOf("gen_constructed","review_constructed")) return try { runConstructed(job,token,type) }
        catch(e: Exception) { actor.execute { failSupply(token,"MALFORMED") }; throw e }
        val pool = QuestionPool(client)
        if (job.kind == "gen_mcq") {
            val avoid = actor.execute { poolStems(input.str("course"),input.str("kp")) }
            val generated = try {
                withContext(Dispatchers.IO) {
                    beforeCall(BridgeClient.CLAUDE)
                    pool.generateFor(input.str("kp"),input.str("objective"),input.str("excerpt"),3,
                        avoid=avoid,background=background)
                }
            } catch (e: Exception) {
                actor.execute { failSupply(token,"MALFORMED") }
                throw e
            }
            actor.execute { recordAiCall(generated.call.toJson(),type) }
            if (generated.questions.size != 3) {
                val accepted = actor.execute { failSupply(token,generated.failureReason ?: "MALFORMED") }
                return Step(job.id,job.kind,if(accepted) "failed" else "stale")
            }
            var questions = generated.questions
            if (questions.any { !QuestionGate.quotedVerbatim(it,input.str("excerpt")) }) {
                // Repair quotations before review; the response cannot replace questions, options or keys.
                val repair = try { withContext(Dispatchers.IO) {
                    beforeCall(BridgeClient.CLAUDE)
                    client.chat(ChatRequest("repair_mcq_sources",BridgeClient.CLAUDE,Prompts.GEN_SYSTEM,
                        "Return ONLY [{\"id\":0,\"source_quote\":\"exact contiguous supporting source text\"}, ...] for every candidate. Copy at least eight words verbatim, including HTML/LaTeX/Markdown; do not render symbols, paraphrase or join fragments. Do not change any question or key.\nSource:\n"+
                        input.str("excerpt")+"\nCandidates:\n"+JsonArray(questions.mapIndexed { i,q -> JsonObject(storedMcq(q)+("id" to JsonPrimitive(i))) }),
                        false,background=background,maxTokens=2500))
                } } catch (e: Exception) {
                    actor.execute { failSupply(token,"MALFORMED") }
                    throw e
                }
                actor.execute { recordAiCall(repair.call.toJson(),type) }
                val quotes = (repair as? BridgeResult.Success)?.let { runCatching {
                    JsonText.arrayOf(it.text).map { value -> value.jsonObject.also { row ->
                        require(row.keys == setOf("id","source_quote"))
                    } }.also { rows -> require(rows.map { row -> row.num("id").toInt() }.sorted() == questions.indices.toList()) }
                        .associate { row -> row.num("id").toInt() to row.str("source_quote") }
                }.getOrNull() }
                if (quotes == null || questions.indices.any { !QuestionGate.sourceQuoteVerbatim(quotes[it].orEmpty(),input.str("excerpt")) }) {
                    val failed=actor.execute { failSupply(token,"MALFORMED") }
                    return Step(job.id,job.kind,if(failed) "failed" else "stale")
                }
                questions=questions.mapIndexed { i,q -> q.copy(sourceQuote=quotes.getValue(i)) }
            }
            val queued = actor.execute { queueBlindReview(token,job,
                JsonArray(questions.map(::storedMcq)), requireNotNull(generated.call.servedBy)) }
            return Step(job.id,job.kind,if(queued) "completed" else "stale")
        }
        val questions = (input.getValue("questions") as JsonArray).map { readStoredMcq(it.jsonObject) }
        val review = try {
            withContext(Dispatchers.IO) {
                beforeCall(BridgeClient.CODEX)
                pool.reviewGroup(listOf(QuestionPool.ReviewEntry(input.str("kp"),input.str("excerpt"),questions,0)),
                    background=background)
            }
        } catch (e: Exception) {
            actor.execute { failSupply(token,"MALFORMED") }
            throw e
        }
        val call = requireNotNull(review.call)
        actor.execute { recordAiCall(call.toJson(),type) }
        val generator=input.str("generator")
        val reviewer=call.servedBy
        val accepted=questions.mapIndexedNotNull { index,q ->
            if (reviewer.isNullOrBlank() || modelVendor(generator) == modelVendor(reviewer) ||
                pool.score(q,review.verdicts[index],input.str("excerpt")).isNotEmpty()) null
            else VerifiedPoolQuestion(q.stem,q.options,q.correctOptionId,q.explanation,q.sourceQuote,generator,reviewer)
        }
        if (accepted.isEmpty()) {
            val committed=actor.execute { failSupply(token,review.failureReason ?: "MALFORMED") }
            return Step(job.id,job.kind,if(committed) "failed" else "stale")
        }
        val inserted=actor.execute { storeVerifiedBatch(token,job,accepted) }
        val state=actor.execute { jobState(job.id)?.first ?: "stale" }
        return Step(job.id,job.kind,state,inserted)
    }

    private suspend fun runConstructed(job: SupplyJob, token: Fence, type: String): Step {
        val input=job.subject
        val generating=job.kind == "gen_constructed"
        val question=if(generating) null else TaskParsing.constructed(JsonArray(listOf(input.getValue("question"))).toString()).single().copy(kpId=input.str("kp"))
        val model=if(generating) BridgeClient.CLAUDE else BridgeClient.CODEX
        val avoid=if(generating) actor.execute { poolStems(input.str("course"),input.str("kp")) } else emptyList()
        var response=withContext(Dispatchers.IO) {
            beforeCall(model)
            client.chat(ChatRequest(job.kind,model,if(generating) Prompts.GEN_SYSTEM else Prompts.CROSS_REVIEW_SYSTEM,
                if(generating) Prompts.genConstructed(input.str("objective"),input.str("excerpt"),2) + "\nDo not repeat these previous stems:\n" + avoid.joinToString("\n")
                else Prompts.reviewConstructedKey(requireNotNull(question),input.str("excerpt")),false,background=true,maxTokens=4000))
        }
        actor.execute { recordAiCall(response.call.toJson(),type) }
        fun validShape(q: ConstructedQuestion): Boolean = q.stem.length >= 15 && q.referenceAnswer.isNotBlank() &&
            q.rubric.size in 3..5 && q.rubric.map { it.id }.distinct().size == q.rubric.size &&
            q.rubric.all { it.id.isNotBlank() && it.text.isNotBlank() && it.points.isFinite() && it.points > 0 } &&
            kotlin.math.abs(q.rubric.sumOf { it.points }-10.0) < 0.00001 &&
            (q.numericFinal==null || (q.rubric.count { it.id==q.numericFinal.str("rubricItemId") }==1 &&
                com.coach.domain.grade.NumericValue.parse(q.numericFinal.str("expected"))!=null &&
                listOf("relTolerance","absTolerance").all { field -> q.numericFinal[field]?.jsonPrimitive?.doubleOrNull?.let { it.isFinite() && it>=0 } ?: (field !in q.numericFinal) }))
        fun valid(q: ConstructedQuestion): Boolean = validShape(q) &&
            q.rubric.all { QuestionGate.sourceQuoteVerbatim(it.basis,input.str("excerpt")) }
        val initial=(response as? BridgeResult.Success)?.let { runCatching { TaskParsing.constructed(it.text) }.getOrNull() }
        if (generating && initial?.size==2 && initial.all(::validShape) && !initial.all(::valid)) {
            // Repair source quotations once before freezing; question, reference, criteria and points cannot change.
            val repaired=withContext(Dispatchers.IO) {
                beforeCall(model)
                client.chat(ChatRequest("repair_constructed_sources",model,Prompts.GEN_SYSTEM,
                    "Fix ONLY rubric basis strings by copying contiguous exact source text. Keep every stem, reference_answer, rubric id, text and points unchanged. No paraphrases or ellipses. Return the complete JSON array.\nSource:\n"+
                    input.str("excerpt")+"\nCandidates:\n"+JsonArray(initial.map(::storedConstructed)),false,background=true,maxTokens=4000))
            }
            actor.execute { recordAiCall(repaired.call.toJson(),type) }
            fun withoutBasis(q: ConstructedQuestion)=q.copy(rubric=q.rubric.map { it.copy(basis="") })
            val fixed=(repaired as? BridgeResult.Success)?.let { runCatching { TaskParsing.constructed(it.text) }.getOrNull() }
            response=if (fixed?.map(::withoutBasis)==initial.map(::withoutBasis)) repaired
                else BridgeResult.Failure("repair_changed_question",repaired.terminal,repaired.call)
        }
        val result=response
        if(result is BridgeResult.Success && !result.call.servedBy.isNullOrBlank()) {
            if(generating) {
                val qs=runCatching { TaskParsing.constructed(result.text) }.getOrDefault(emptyList())
                if(qs.size==2 && qs.all(::valid) && qs.map { it.stem }.distinct().size==2) {
                    val ok=actor.execute { queueConstructedReviews(token,job,qs.map(::storedConstructed),result.call.servedBy) }
                    return Step(job.id,job.kind,if(ok) "completed" else "stale")
                }
            } else if(valid(requireNotNull(question)) && modelVendor(input.str("generator")) != modelVendor(result.call.servedBy) &&
                runCatching { TaskParsing.crossReview(result.text).verdict == "CONFIRM" }.getOrDefault(false)) {
                val ok=actor.execute { storeConstructed(token,job,storedConstructed(question),GradeGate.rubricFingerprint(question.rubric),result.call.servedBy) }
                return Step(job.id,job.kind,if(ok) "completed" else "stale",if(ok) 1 else 0)
            }
        }
        val reason=if(result is BridgeResult.Failure && (result.reason.contains("timeout",true) || result.terminal?.reason?.contains("timeout",true)==true)) "TIMEOUT" else "MALFORMED"
        val failed=actor.execute { failSupply(token,reason) }
        return Step(job.id,job.kind,if(failed) "failed" else "stale")
    }

    suspend fun ensureConstructed(course: String, kp: String, objective: String, excerpt: String): String? =
        actor.execute { enqueueConstructed(course,kp,objective,excerpt) }

    /** Caller may schedule this without waiting before taking a group. No KP-type filter. */
    suspend fun ensure(course: String, kp: String, objective: String, excerpt: String, target: Int,
        background: Boolean = false): String? = actor.execute {
        require(target > 0)
        enqueueConstructed(course,kp,objective,excerpt)
        if (poolAvailable(course,kp) >= target) null else enqueueSupply(course,kp,objective,excerpt,background)
    }
}

internal fun storedMcq(q: Mcq): JsonObject = json("stem" to q.stem,
    "options" to LedgerJson.encodeToJsonElement(q.options), "correctOptionId" to q.correctOptionId,
    "explanation" to q.explanation, "sourceQuote" to q.sourceQuote)
internal fun readStoredMcq(q: JsonObject): Mcq = Mcq(q.str("stem"),
    LedgerJson.decodeFromJsonElement<List<Option>>(q.getValue("options")),q.str("correctOptionId"),
    q.str("explanation"),q.str("sourceQuote"))

/** Persist the deterministic original-answer UTF-16 offsets with a confirmed grade. */
suspend fun persistConfirmedGrade(actor: LedgerActor, token: Fence, questionVersion: String,
    answerHash: String, course: String, attempt: String, outcome: GradePipeline.Outcome): Boolean {
    require(outcome.written && outcome.crossReview?.verdict == "CONFIRM")
    val draft=requireNotNull(outcome.draft)
    return actor.execute {
        tutorTransaction {
            if(!completeJob(token,questionVersion,answerHash)) return@tutorTransaction false
            val payload=json("attemptId" to attempt,"status" to "AI_UNREVIEWED", "method" to "AI",
                "score" to kotlin.math.round(requireNotNull(outcome.awarded)*100).toLong(),
                "max" to kotlin.math.round(draft.total*100).toLong(),
                "rubricFingerprint" to outcome.rubricFingerprint,
                "evidence" to JsonArray(outcome.evidenceSpans.map { e -> json("rubricItemId" to e.rubricItemId,
                    "start" to e.span.start,"end" to e.span.end,"text" to e.span.text) }))
            append(course,"attempt_graded",payload,"job:${token.id}:draft")
            append(course,"grade_reviewed",json("attemptId" to attempt,"decision" to "CONFIRM",
                "graderModel" to outcome.calls.first().servedBy,"reviewerModel" to outcome.calls.last().servedBy),"job:${token.id}:review")
            true
        }
    }
}

internal fun storedConstructed(q: ConstructedQuestion): JsonObject = json("stem" to q.stem,
    "reference_answer" to q.referenceAnswer,"rubric" to JsonText.json.parseToJsonElement(Prompts.rubricJson(q.rubric)),
    "numeric_final" to q.numericFinal)
