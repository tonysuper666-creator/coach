package com.coach.core

import com.coach.domain.*
import com.coach.protocol.CommandBody
import com.coach.protocol.WireCommand
import com.coach.store.LedgerStore
import com.coach.store.sha256
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

enum class TutorState { IDLE, PREPARE, TEACH, AWAIT, POSE, AWAIT_ANSWER, GRADE, REVIEW, FEEDBACK }

data class TutorThread(val course: String, val thread: String, val kp: String, val concepts: Set<String>, val title: String)
data class PendingChoice(val attemptId: String, val stem: String, val key: ChoiceKey)
data class TutorReplyPlan(
    val context: TutorThread, val turnId: String, val explanation: Boolean,
    val conceptIds: Set<String>, val recapConceptIds: Set<String>, val firstExplanation: Boolean,
    val mode: TeachingMode, val pending: List<PendingChoice> = emptyList(),
) {
    /** No letter map, answer key, or historical feedback goes into this context. */
    fun instruction(): String = buildString {
        appendLine("Knowledge point: ${context.title}; mode: ${mode.label}.")
        appendLine("Fully teach ONLY these new conceptIds: ${conceptIds.sorted()}.")
        appendLine("recapConceptIds: ${recapConceptIds.sorted()}; recap overlaps in at most 2-3 sentences total.")
        appendLine(if (firstExplanation) "Only this first explanation may include a section headed ‘期中怎么考’, at most 3 lines."
            else "Do not include any ‘期中怎么考’ section or repeat a full explanation.")
        if (pending.isNotEmpty()) {
            appendLine("There are pending answers. Guide the student without revealing any answer or naming option letters.")
            pending.forEach { p ->
                appendLine("Question: ${p.stem}")
                appendLine("Unordered option texts:")
                p.key.options.sortedBy { it.text }.forEach { appendLine("- ${it.text}") }
            }
        }
    }
}

data class TutorOutcome(
    val state: TutorState, val nextLabel: String, val plan: TutorReplyPlan? = null,
    val cards: List<Event> = emptyList(), val grades: List<Event> = emptyList(), val waitingForPool: Boolean = false,
    val poolMessage: String? = null,
)

/** The ledger is the state. Every decision runs through the single writer, including after restart. */
class TutorMachine(private val actor: LedgerActor) {
    suspend fun recoverInterruptedReplies() = actor.execute {
        tutorTransaction {
            courses().forEach { course ->
                events(course).filter { it.type == "kp_opened" }.mapNotNull { it.threadId }.distinct().forEach { thread ->
                    contextOrNull(course,thread)?.let { c ->
                        if (activeTurn(c).isNotEmpty()) transition(c, if (pending(c).isEmpty()) TutorState.IDLE else TutorState.AWAIT_ANSWER)
                    }
                }
            }
        }
    }

    suspend fun open(course: String, thread: String, kp: String, concepts: Set<String>, title: String): TutorOutcome = actor.execute {
        tutorTransaction {
            require(concepts.isNotEmpty() && concepts.all { it.isNotBlank() }) { "Missing concept IDs" }
            bindTutorThread(course,thread,kp)
            val old = contextOrNull(course,thread)
            if (old == null) {
                append(course,"kp_opened",json("kpId" to kp,"conceptIds" to concepts.sorted(),"title" to title), thread=thread, actor="LEARNER")
                transition(TutorThread(course,thread,kp,concepts,title),TutorState.IDLE)
            } else require(old.kp == kp && old.concepts == concepts) { "KP thread identity changed" }
            outcome(context(course,thread))
        }
    }

    suspend fun hasThread(course: String, thread: String): Boolean = actor.execute { contextOrNull(course,thread) != null }

    suspend fun settings(course: String, thread: String, groupSize: Int? = null, mode: TeachingMode? = null): TutorOutcome = actor.execute {
        tutorTransaction {
            val c = context(course,thread)
            groupSize?.let(::requireTutorGroupSize)
            append(course,"tutor_settings_changed",json("kpId" to c.kp,"groupSize" to (groupSize ?: groupSize(c)),"mode" to (mode ?: mode(c)).name),thread=thread)
            outcome(c)
        }
    }

    /** Plain continuation practices a taught KP; only the explicit button may start an example recap. */
    suspend fun next(course: String, thread: String, testUntaught: Boolean = false, allowExample: Boolean = false): TutorOutcome = actor.execute {
        tutorTransaction {
            val c = context(course,thread)
            requireNotBusy(c)
            val pending = pending(c)
            if (pending.isNotEmpty()) return@tutorTransaction outcome(c, cards=pending)
            val explained = snapshot(course).projection.explainedKpIds.contains(c.kp)
            if (!testUntaught && (!explained || allowExample && needsExample(c))) return@tutorTransaction beginExplanation(c, explained)
            pose(c, untaught=!explained)
        }
    }

    suspend fun poolWaitActive(course: String, thread: String): Boolean = actor.execute {
        val c=context(course,thread)
        stateEvent(c)?.payload?.str("state")=="AWAIT" && pending(c).isEmpty() && activeTurn(c).isEmpty()
    }

    /** Review is a pool-only action, even when normal learning is configured for one question.
     * Repeated taps return the same pending group and never queue AI work. */
    suspend fun retryMistakes(course: String, thread: String): TutorOutcome = actor.execute {
        tutorTransaction {
            val c = context(course,thread)
            requireNotBusy(c)
            val existing = pending(c)
            if (existing.isNotEmpty()) return@tutorTransaction outcome(c,cards=existing)
            val take = takePoolGroup(course,c.kp,uid(),3,thread=thread,
                untaught=!snapshot(course).projection.explainedKpIds.contains(c.kp))
            require(take.status == "READY") { "这个知识点暂时不足 3 道新题，请稍后再练。错题仍会保留。" }
            transition(c,TutorState.AWAIT_ANSWER)
            outcome(c,cards=take.events)
        }
    }

    /** An asynchronous refill must not advance a teaching turn or pose a second pending group. */
    suspend fun resumePool(course: String, thread: String): TutorOutcome? = actor.execute {
        tutorTransaction {
            val c = context(course,thread)
            if (stateEvent(c)?.payload?.str("state") != "AWAIT" || pending(c).isNotEmpty() ||
                activeTurn(c).isNotEmpty() || poolAvailable(course,c.kp) < groupSize(c)) null
            else pose(c,untaught=!snapshot(course).projection.explainedKpIds.contains(c.kp))
        }
    }

    /** A deliberate mode action produces a recap; it never marks concepts fully taught twice. */
    suspend fun explain(course: String, thread: String, mode: TeachingMode): TutorOutcome = actor.execute {
        tutorTransaction {
            val c = context(course,thread)
            requireNotBusy(c)
            require(pending(c).isEmpty()) { "请先提交当前题组" }
            append(course,"tutor_settings_changed",json("kpId" to c.kp,"groupSize" to groupSize(c),"mode" to mode.name),thread=thread)
            beginExplanation(c, snapshot(course).projection.explainedKpIds.contains(c.kp), mode)
        }
    }

    suspend fun followup(course: String, thread: String, attemptId: String? = null): TutorReplyPlan = actor.execute {
        tutorTransaction {
            val c = context(course,thread)
            requireNotBusy(c)
            val pending = pending(c)
            require(attemptId == null || pending.any { it.payload.str("attemptId") == attemptId }) { "Unknown pending attempt" }
            val target = if (attemptId == null) pending else pending.filter { it.payload.str("attemptId") == attemptId }
            val turn = uid()
            // Without a focused card, the reply can assist any question in the displayed group.
            if (target.isEmpty()) append(course,"followup_asked",json("kpId" to c.kp,"turnId" to turn),thread=thread,actor="LEARNER")
            target.forEach { append(course,"followup_asked",json("kpId" to c.kp,"pendingAttemptId" to it.payload.str("attemptId"),"turnId" to turn),thread=thread,actor="LEARNER") }
            transition(c, if (pending.isEmpty()) TutorState.AWAIT else TutorState.AWAIT_ANSWER,turn)
            TutorReplyPlan(c,turn,false,emptySet(),c.concepts,false,mode(c),target.map {
                PendingChoice(it.payload.str("attemptId"),it.payload.str("stem"),tutorChoiceKey(it.payload.str("questionId")))
            })
        }
    }

    /** Called on RAW output before display filtering. A late/cancelled reply cannot alter new evidence. */
    suspend fun leak(plan: TutorReplyPlan, attemptId: String) = actor.execute {
        tutorTransaction {
            val c = plan.context
            if (activeTurn(c) == plan.turnId && pending(c).any { it.payload.str("attemptId") == attemptId }) {
                val idem = "tutor:${plan.turnId}:leak:$attemptId"
                if (events(c.course).none { it.idemKey == idem }) append(c.course,"hint_revealed",
                    json("kpId" to c.kp,"attemptId" to attemptId,"cause" to "LEAK"),idem,thread=c.thread,actor="TUTOR")
            }
        }
    }

    suspend fun finish(plan: TutorReplyPlan, messageId: String, complete: Boolean, persist: LedgerStore.() -> Unit = {}): TutorOutcome = actor.execute {
        tutorTransaction {
            val c = plan.context
            if (activeTurn(c) != plan.turnId) return@tutorTransaction outcome(c)
            persist()
            if (plan.explanation && complete) append(c.course,"explanation_delivered",json(
                "kpId" to c.kp,"msgId" to messageId,"conceptIds" to plan.conceptIds.sorted(),
                "recapConceptIds" to plan.recapConceptIds.sorted(),"mode" to plan.mode.name
            ),"tutor:${plan.turnId}:explanation",thread=c.thread,actor="TUTOR")
            val next = when {
                pending(c).isNotEmpty() -> TutorState.AWAIT_ANSWER
                !complete && plan.firstExplanation -> TutorState.IDLE
                else -> TutorState.AWAIT
            }
            transition(c,next)
            outcome(c)
        }
    }

    suspend fun submit(course: String, thread: String, groupId: String, answers: Map<String,String>, device: String, submissionId: String): TutorOutcome = actor.execute {
        tutorTransaction {
            val c = context(course,thread)
            requireNotBusy(c)
            require(submissionId.isNotBlank() && device.isNotBlank())
            val cards = threadEvents(c).filter { it.type == "question_posed" && it.payload.str("groupId") == groupId }
            require(cards.isNotEmpty() && answers.keys == cards.map { it.payload.str("attemptId") }.toSet()) { "请一次提交完整题组" }
            cards.forEach { card -> require(answers.getValue(card.payload.str("attemptId")).trim().uppercase() in card.payload.getValue("letterMap").jsonObject.values.map { it.jsonPrimitive.content }) { "Invalid answer letter" } }
            val all = events(course)
            val existing = all.filter { it.type == "answer_submitted" && it.payload.str("attemptId") in answers }
            if (existing.isNotEmpty()) {
                require(existing.size == cards.size && existing.all { it.payload.str("answer").trim().uppercase() == answers.getValue(it.payload.str("attemptId")).trim().uppercase() }) { "题组已提交" }
                return@tutorTransaction outcome(c, grades=all.filter { it.type == "attempt_graded" && it.payload.str("attemptId") in answers })
            }
            require(pending(c).map { it.payload.str("attemptId") }.toSet() == answers.keys) { "Not the pending group" }
            transition(c,TutorState.GRADE)
            cards.forEach { card ->
                val id = card.payload.str("attemptId")
                val body = CommandBody("submit_answer",course,System.currentTimeMillis(),json("attemptId" to id,"answer" to answers.getValue(id),"posedSnapshot" to card.payload,"threadId" to thread))
                val bytes = LedgerJson.encodeToString(body)
                val receipt = command(WireCommand(device,"$submissionId:$id",bytes,sha256(bytes.toByteArray())),tutorGroup=true)
                check(receipt.status == "OK") { "Group submission rejected: ${receipt.error}" }
            }
            // Choices are EXACT: no model review is needed and no invented review verdict is written.
            transition(c,TutorState.REVIEW)
            transition(c,TutorState.FEEDBACK)
            outcome(c,grades=events(course).filter { it.type == "attempt_graded" && it.payload.str("attemptId") in answers })
        }
    }

    private fun LedgerStore.beginExplanation(c: TutorThread, explained: Boolean, requested: TeachingMode? = null): TutorOutcome {
        val known = snapshot(c.course).projection.taughtConceptIds
        val turn = uid()
        val plan = TutorReplyPlan(c,turn,true,c.concepts - known,c.concepts.intersect(known),!explained,
            requested ?: if (explained && needsExample(c)) TeachingMode.EXAMPLE else mode(c))
        transition(c,TutorState.PREPARE,turn)
        transition(c,TutorState.TEACH,turn)
        return outcome(c,plan=plan)
    }

    private fun LedgerStore.pose(c: TutorThread, untaught: Boolean): TutorOutcome {
        transition(c,TutorState.POSE)
        val take = takePoolGroup(c.course,c.kp,uid(),groupSize(c),untaught=untaught,thread=c.thread)
        if (take.status != "READY") {
            transition(c,TutorState.AWAIT)
            return outcome(c,waitingForPool=true).copy(poolMessage=take.message)
        }
        val cards = take.events
        transition(c,TutorState.AWAIT_ANSWER)
        return outcome(c,cards=cards)
    }

    private fun LedgerStore.contextOrNull(course: String, thread: String): TutorThread? = events(course).lastOrNull {
        it.threadId == thread && it.type == "kp_opened" && it.payload.strings("conceptIds").isNotEmpty()
    }?.let { TutorThread(course,thread,it.payload.str("kpId"),it.payload.strings("conceptIds").toSet(),it.payload.str("title")) }
    private fun LedgerStore.context(course: String, thread: String) = requireNotNull(contextOrNull(course,thread)) { "请先打开知识点" }
    private fun LedgerStore.threadEvents(c: TutorThread) = events(c.course).filter { it.threadId == c.thread }
    private fun LedgerStore.stateEvent(c: TutorThread) = threadEvents(c).lastOrNull { it.type == "tutor_state_changed" }
    private fun LedgerStore.activeTurn(c: TutorThread) = stateEvent(c)?.payload?.str("turnId").orEmpty()
    private fun LedgerStore.requireNotBusy(c: TutorThread) { require(activeTurn(c).isEmpty()) { "导师正在回复，请等待或取消" } }
    private fun LedgerStore.pending(c: TutorThread): List<Event> {
        val es = events(c.course)
        val submitted = es.filter { it.type in setOf("answer_submitted","attempt_voided") }.map { it.payload.str("attemptId") }.toSet()
        return es.filter { it.threadId == c.thread && it.type == "question_posed" && it.payload.str("attemptId") !in submitted }
    }
    private fun LedgerStore.groupSize(c: TutorThread) = requireTutorGroupSize(threadEvents(c).lastOrNull { it.type == "tutor_settings_changed" }?.payload?.num("groupSize",3)?.toInt() ?: 3)
    private fun LedgerStore.mode(c: TutorThread) = TeachingMode.valueOf(threadEvents(c).lastOrNull { it.type == "tutor_settings_changed" }?.payload?.str("mode") ?: "CURRENT")
    private fun LedgerStore.needsExample(c: TutorThread): Boolean {
        val es = events(c.course)
        val lastExplanation = es.lastOrNull { it.type == "explanation_delivered" && it.payload.str("kpId") == c.kp }?.seq ?: 0
        val attempts = es.filter { it.type == "question_posed" && c.kp in it.payload.strings("kpIds") }.map { it.payload.str("attemptId") }.toSet()
        return es.count { it.type == "attempt_graded" && it.seq > lastExplanation && it.payload.str("attemptId") in attempts && it.payload.num("score") * 5 < it.payload.num("max") * 4 } >= 2
    }
    private fun LedgerStore.transition(c: TutorThread, state: TutorState, turnId: String = "") {
        append(c.course,"tutor_state_changed",json("kpId" to c.kp,"from" to (stateEvent(c)?.payload?.str("state") ?: "IDLE"),"state" to state.name,"turnId" to turnId),thread=c.thread)
    }
    private fun LedgerStore.outcome(c: TutorThread, plan: TutorReplyPlan? = null, cards: List<Event> = emptyList(), grades: List<Event> = emptyList(), waitingForPool: Boolean = false) = TutorOutcome(
        TutorState.valueOf(stateEvent(c)?.payload?.str("state") ?: "IDLE"),if (needsExample(c)) "换个例子再讲" else "下一步",plan,cards,grades,waitingForPool)
    private fun uid() = UUID.randomUUID().toString()
}
