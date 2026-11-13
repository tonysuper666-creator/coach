package com.coach.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Explicit provenance, never inferred from the words a learner typed. */
const val AGENT_RUN_LABEL = "agent 代跑，非真人"
private const val AGENT_THREAD_PREFIX = "agent-run/"

fun agentThreadId(run: String, thread: String): String {
    require(run.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid agent run ID" }
    require(thread.isNotBlank())
    val existing = agentRunId(thread)
    require(existing == null || existing == run) { "Cannot move a thread between agent runs" }
    return if (existing != null) thread else "$AGENT_THREAD_PREFIX$run/$thread"
}

fun agentRunId(thread: String?): String? {
    if (thread?.startsWith(AGENT_THREAD_PREFIX) != true) return null
    val run = thread.removePrefix(AGENT_THREAD_PREFIX).substringBefore('/')
    require(run.matches(Regex("[A-Za-z0-9_-]{1,80}")) && thread.removePrefix(AGENT_THREAD_PREFIX).contains('/')) { "Invalid agent thread" }
    return run
}

fun agentRunMetadata(run: String?): JsonObject = if (run == null) json() else {
    agentThreadId(run, "validate")
    json("agentRunId" to run, "sampleOrigin" to AGENT_RUN_LABEL)
}

/** Old clients must not treat assessment-shaped agent facts as learner evidence.
 * They preserve this unknown wire type and use the authoritative server projection.
 * Raw ledger facts keep their original types; current clients unwrap before filtering.
 */
fun agentEvidenceForWire(event: Event): Event =
    if (event.type != "agent_run_fact" && (agentRunId(event.threadId) != null ||
            event.payload.str("agentRunId").isNotBlank() || event.payload.str("sampleOrigin") == AGENT_RUN_LABEL))
        event.copy(type="agent_run_fact", payload=JsonObject(event.payload +
            ("sampleOrigin" to JsonPrimitive(AGENT_RUN_LABEL)) + ("agentEventType" to JsonPrimitive(event.type))))
    else event

/** Keep raw facts for audit/sync, but never count agent evidence as learner progress.
 * The attempt closure also covers a late grade/review whose producer omitted its thread.
 * A tutor can explicitly include its own run to advance that isolated lesson.
 */
fun learnerEvidence(input: List<Event>, includeAgentRun: String? = null): List<Event> {
    val events = input.map { e ->
        if (e.type == "agent_run_fact" && e.payload.str("sampleOrigin") == AGENT_RUN_LABEL)
            e.copy(type=e.payload.str("agentEventType"),payload=JsonObject(e.payload - "agentEventType"))
        else e
    }
    fun run(e: Event): String? = agentRunId(e.threadId) ?: e.payload.str("agentRunId").ifBlank { null }
    fun excluded(e: Event): Boolean =
        run(e)?.let { it != includeAgentRun } ?: (e.payload.str("sampleOrigin") == AGENT_RUN_LABEL)
    val excludedAttempts = events.filter(::excluded).map { it.payload.str("attemptId") }.filter { it.isNotBlank() }.toSet()
    return events.filterNot { excluded(it) || it.payload.str("attemptId") in excludedAttempts }
}
