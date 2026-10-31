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
            val queued = actor.execute { queueBlindReview(token,job,
                JsonArray(generated.questions.map(::storedMcq)), requireNotNull(generated.call.servedBy)) }
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

    /** Caller may schedule this without waiting before taking a group. No KP-type filter. */
    suspend fun ensure(course: String, kp: String, objective: String, excerpt: String, target: Int,
        background: Boolean = false): String? = actor.execute {
        require(target > 0)
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
        commitJobResult(token,questionVersion,answerHash,course,"attempt_graded",json(
            "attemptId" to attempt,"status" to "VERIFIED", "method" to "AI",
            "score" to kotlin.math.round(requireNotNull(outcome.awarded)*100).toLong(),
            "max" to kotlin.math.round(draft.total*100).toLong(),
            "rubricFingerprint" to outcome.rubricFingerprint,
            "evidence" to JsonArray(outcome.evidenceSpans.map { e -> json("rubricItemId" to e.rubricItemId,
                "start" to e.span.start,"end" to e.span.end,"text" to e.span.text) })))
    }
}
