package com.coach.domain

import kotlinx.serialization.Serializable

/** Public, answer-free metadata from the same weekly pack as the original question. */
@Serializable
data class LadderQuestion(
    val id: String,
    val kpId: String,
    val sectionId: String,
    val kind: String,
    val difficulty: Int,
    val variantGroupId: String,
    val fingerprint: String,
    val prerequisites: List<String> = emptyList(),
    val priority: Int = 1,
    val eligible: Boolean = false,
    val calculationChain: String = "",
    val calculationStep: Int = 0,
    val completeExampleId: String = "",
    val capabilityId: String? = null,
    val requiredCapabilities: List<String>? = emptyList(),
    val prerequisitesKnown: Boolean = true,
    val questionVersion: String? = null,
    val recordedPrerequisiteRefs: List<String>? = emptyList(),
    val resolvedPrerequisiteRefs: List<String>? = emptyList(),
    val priorityStar: Int? = null,
    val syntheticOnly: Boolean = false,
    val priorityStarState: String = "missing",
    val contractIssues: List<String> = emptyList(),
)

@Serializable
data class LadderResponse(
    val id: String,
    val kpId: String,
    val fingerprint: String,
    val independentCorrect: Boolean,
    val tryFirst: Boolean = false,
    val remember: String = "",
    val capabilityId: String? = null,
)

/** Persisted with the question, selected option, immutable pending request and feedback. */
@Serializable
data class LadderSession(
    val responses: List<LadderResponse> = emptyList(),
    val skipped: List<String> = emptyList(),
    val recapThrough: Int = 0,
    val recapOpen: Boolean = false,
    val complete: Boolean = false,
)

data class LadderStep(val question: LadderQuestion?, val blockedKps: Set<String> = emptySet(),
    val blockedCapabilities: Set<String> = emptySet(), val prerequisitesUnknown: Boolean = false)

object QuestionLadder {
    private val kinds = listOf("try_first", "introduction", "concept", "detail", "application", "bridge", "exam_prototype")

    /** Three different questions, with no guess, failed answer or direct explanation between them. */
    fun learned(session: LadderSession, kpId: String): Boolean {
        val answers = session.responses.filter { it.kpId == kpId && !it.tryFirst }
        return answers.isNotEmpty() && answers.groupBy { it.capabilityId }.values.all { group ->
            val recent = group.takeLast(3)
            recent.size == 3 && recent.all { it.independentCorrect } && recent.map { it.fingerprint }.distinct().size == 3
        }
    }

    fun recap(session: LadderSession): List<String> = session.responses.drop(session.recapThrough)
        .filterNot { it.tryFirst }.map { it.remember.trim() }.filter { it.isNotEmpty() }.distinct().takeLast(5)

    fun closeRecap(session: LadderSession) = session.copy(recapOpen = false, recapThrough = session.responses.size)

    fun record(session: LadderSession, question: LadderQuestion, independentCorrect: Boolean, remember: String): LadderSession {
        // A replayed response must not increment progress or open a second recap.
        if (session.responses.any { it.id == question.id }) return session
        val response = LadderResponse(question.id, question.kpId, question.fingerprint,
            independentCorrect && question.kind != "try_first" && !question.syntheticOnly, question.kind == "try_first", remember, question.capabilityId)
        val next = session.copy(responses = session.responses + response, complete = false)
        return next.copy(recapOpen = next.responses.size - next.recapThrough >= 6)
    }

    /**
     * No random question generator and no repeats disguised as shuffled options. Exhaustion is a
     * real content gap. Prerequisites are never removed to get past that gap.
     */
    fun next(questions: List<LadderQuestion>, session: LadderSession, previouslyLearned: Set<String> = emptySet(),
        knownCapabilities: Set<String> = emptySet()): LadderStep {
        val pool = questions.filter { it.eligible && it.kind in kinds }
        val used = session.responses.map { it.id }.toSet() + session.skipped
        val fingerprints = session.responses.map { it.fingerprint }.toSet()
        val remaining = pool.filter { it.id !in used && it.fingerprint !in fingerprints }
        val known = previouslyLearned + pool.groupBy { it.kpId }.filter { (kp, group) ->
            learned(session, kp) && group.all { q -> session.responses.any { it.kpId == kp && it.capabilityId == q.capabilityId && !it.tryFirst } }
        }.keys
        val ready = remaining.filter { question ->
            val steps = pool.filter { it.calculationChain.isNotBlank() && it.calculationChain == question.calculationChain && it.calculationStep > 0 }
            val required = if (steps.any { it.completeExampleId == question.id }) steps else steps.filter { it.calculationStep < question.calculationStep }
            val requiredCount = if (steps.any { it.completeExampleId == question.id }) steps.maxOfOrNull { it.calculationStep } ?: 0 else (question.calculationStep - 1).coerceAtLeast(0)
            question.prerequisitesKnown && question.requiredCapabilities != null &&
                question.requiredCapabilities.all { it in knownCapabilities } && question.prerequisites.all { kp -> kp in known } &&
                required.map { it.calculationStep }.distinct().size == requiredCount &&
                required.groupBy { it.calculationStep }.values.all { variants ->
                    variants.any { step -> session.responses.any { it.id == step.id && it.independentCorrect } }
                }
        }
        val primer = pool.filter { it.kind == "try_first" }.take(4)
        if (primer.size >= 2) primer.firstOrNull { it in ready && it.id !in used && it.fingerprint !in fingerprints }?.let { return LadderStep(it) }
        val teaching = ready.filter { it.kind != "try_first" }
        val last = session.responses.lastOrNull { !it.tryFirst }
        if (last != null && !last.independentCorrect) {
            val previous = pool.firstOrNull { it.id == last.id }
            val remedial = teaching.filter { it.kpId == last.kpId && (previous?.capabilityId == null || it.capabilityId == previous.capabilityId) }
                .sortedWith(compareBy<LadderQuestion> { if (it.variantGroupId == previous?.variantGroupId) 0 else 1 }
                    .thenBy { it.difficulty }.thenBy { kinds.indexOf(it.kind) })
            val waiting = remaining.filter { it.kpId == last.kpId && (previous?.capabilityId == null || it.capabilityId == previous.capabilityId) }
            return LadderStep(remedial.firstOrNull(), if (remedial.isEmpty()) setOf(last.kpId) else emptySet(),
                if (remedial.isEmpty()) waiting.flatMap { it.requiredCapabilities.orEmpty() }.filter { it !in knownCapabilities }.toSet() else emptySet(),
                remedial.isEmpty() && waiting.any { !it.prerequisitesKnown || it.requiredCapabilities == null })
        }
        // Each point advances from its own simplest remaining question. Weighted fair selection
        // interleaves important points without dropping their introduction or prerequisites.
        val heads = teaching.groupBy { it.kpId to it.capabilityId }.values.mapNotNull { group ->
            val streak = session.responses.filter { it.kpId == group.first().kpId && it.capabilityId == group.first().capabilityId && !it.tryFirst }
                .takeLast(2).let { it.size == 2 && it.all { a -> a.independentCorrect } }
            group.filterNot { streak && it.kind == "introduction" }
                .minWithOrNull(compareBy<LadderQuestion> { kinds.indexOf(it.kind) }.thenBy { it.difficulty })
        }
        val selected = heads.minWithOrNull(compareBy<LadderQuestion> { q ->
            session.responses.count { it.kpId == q.kpId && it.capabilityId == q.capabilityId && !it.tryFirst }.toDouble() / q.priority.coerceAtLeast(1)
        }.thenByDescending { it.priority }.thenBy { pool.indexOf(it) })
        val gaps = if (selected == null) remaining.filter { it.kind != "try_first" }.flatMap { it.prerequisites }.filter { it !in known }.toSet() +
            remaining.filter { it.kind != "try_first" && it !in ready && it.calculationChain.isNotBlank() }.map { it.kpId } else emptySet()
        return LadderStep(selected, gaps, if (selected == null) remaining.flatMap { it.requiredCapabilities.orEmpty() }.filter { it !in knownCapabilities }.toSet() else emptySet(),
            selected == null && remaining.any { !it.prerequisitesKnown || it.requiredCapabilities == null })
    }
}
