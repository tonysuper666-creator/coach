package com.coach.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class LadderRuntimeContext(
    val contractVersion: String? = null,
    val syntheticOnly: Boolean = false,
    val productionAuthorized: Boolean = false,
    val applicableKpIds: List<String>? = null,
    val scopeVerified: Boolean = false,
    val knownCapabilities: Set<String>? = null,
    val unsupported: List<String> = emptyList(),
)

data class SyntheticLadderQuestion(
    val id: String, val version: String, val ref: String, val kp: String, val capability: String,
    val format: String, val kind: String, val stem: String, val options: List<String>?, val answer: Int?,
    val requiredCapabilities: List<String>?, val prerequisiteRefs: List<String>?,
    val modelAnswer: String?, val rubric: List<String>?,
)

/** PR133 ca7a3340's public synthetic contract. Never grants production approval or mastery. */
class SyntheticLadderRuntime private constructor(
    val questions: List<SyntheticLadderQuestion>, val applicableKps: List<String>?, val aliases: Map<String, String>
) {
    fun resolve(ref: String): String {
        var current = ref
        val seen = mutableSetOf<String>()
        while (current in aliases) {
            require(seen.add(current)) { "alias_cycle" }
            current = aliases.getValue(current)
        }
        return current
    }

    fun missing(q: SyntheticLadderQuestion, knownCapabilities: Set<String>): List<String> = buildList {
        if (q.ref in aliases) add("superseded_version")
        if (q.requiredCapabilities == null || q.prerequisiteRefs == null) add("prerequisites_unknown")
        if (q.requiredCapabilities?.any { it !in knownCapabilities } == true) add("capability_evidence_missing")
        if (q.format == "essay" && (q.modelAnswer == null || q.rubric == null)) add("essay_answer_or_rubric_unknown")
    }

    fun ladder(q: SyntheticLadderQuestion): LadderQuestion = LadderQuestion(
        id = q.ref, kpId = q.kp, sectionId = q.kp, kind = q.kind, difficulty = 0,
        variantGroupId = q.id, fingerprint = "synthetic:${q.ref}:${q.stem}",
        eligible = q.ref !in aliases && q.format == "mcq", capabilityId = q.capability,
        requiredCapabilities = q.requiredCapabilities, prerequisitesKnown = q.prerequisiteRefs != null,
        questionVersion = q.version, recordedPrerequisiteRefs = q.prerequisiteRefs,
        resolvedPrerequisiteRefs = q.prerequisiteRefs?.map(::resolve), syntheticOnly = true,
    )

    fun context(knownCapabilities: Set<String>) = LadderRuntimeContext(
        contractVersion = SCHEMA, syntheticOnly = true, productionAuthorized = false,
        applicableKpIds = applicableKps, scopeVerified = false, knownCapabilities = knownCapabilities,
        unsupported = buildList {
            if (questions.any { it.format == "essay" }) add("essay_exercise_format")
            if (questions.any { it.kind !in KINDS }) add("teaching_kind")
            add("production_sources_and_release")
        })

    /** Acknowledgement is deliberately not a grade or a claim of independent learning evidence. */
    fun reveal(ref: String, knownCapabilities: Set<String>, submitted: Boolean): JsonObject {
        require(submitted) { "synthetic_submission_required" }
        val q = questions.singleOrNull { it.ref == ref } ?: error("unknown_question_version")
        require(missing(q, knownCapabilities).isEmpty()) { "synthetic_question_not_ready" }
        return if (q.format == "essay") json("ref" to ref, "synthetic_only" to true, "mastery_evidence" to false,
            "model_answer" to q.modelAnswer, "rubric" to q.rubric)
        else json("ref" to ref, "synthetic_only" to true, "mastery_evidence" to false, "answer" to q.answer)
    }

    companion object {
        const val SCHEMA = "synthetic-runtime-contract-v1"
        val KINDS = setOf("try_first", "introduction", "concept", "detail", "application", "bridge", "exam_prototype")

        fun parse(raw: JsonObject): SyntheticLadderRuntime {
            require(raw.str("schema") == SCHEMA && raw["synthetic"] == JsonPrimitive(true)) { "synthetic_contract_required" }
            fun strings(value: JsonElement?, field: String): List<String>? {
                if (value == null || value == JsonNull) return null
                require(value is JsonArray) { "invalid_$field" }
                return value.map { item ->
                    val text = item as? JsonPrimitive
                    require(text?.isString == true && text.content.isNotBlank()) { "invalid_$field" }
                    text.content
                }.also { require(it.distinct().size == it.size) { "duplicate_$field" } }
            }
            fun required(q: JsonObject, field: String): String {
                val value = q[field] as? JsonPrimitive
                require(value?.isString == true && value.content.isNotBlank()) { "missing_$field" }
                return value.content
            }
            val scope = strings(raw["applicable_kps"], "applicable_kps")
            val rows = raw["questions"] as? JsonArray ?: error("questions_required")
            val questions = rows.map { value ->
                val q = value as? JsonObject ?: error("invalid_question")
                val id = required(q, "id"); val version = required(q, "version"); val ref = required(q, "ref")
                require(ref == "$id@$version") { "question_ref_mismatch" }
                val kp = required(q, "kp"); val capability = required(q, "capability")
                require(scope == null || kp in scope) { "question_kp_outside_scope" }
                val format = required(q, "format")
                require(format in setOf("mcq", "essay")) { "unsupported_format" }
                val options = strings(q["options"], "options")
                val answer = (q["answer"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                val model = when (val text = q["model_answer"]) {
                    null, JsonNull -> null
                    else -> (text as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: error("invalid_model_answer")
                }
                val rubric = strings(q["rubric"], "rubric")?.also { require(it.isNotEmpty()) { "invalid_rubric" } }
                if (format == "mcq") {
                    require(options != null && options.size >= 2 && answer != null && answer in options.indices) { "invalid_mcq" }
                    require(model == null && rubric == null) { "mcq_essay_payload" }
                } else require(options == null && q["answer"] in listOf(null, JsonNull)) { "essay_mcq_payload" }
                val requiredCapabilities = strings(q["required_capabilities"], "required_capabilities")
                require(requiredCapabilities == null || capability !in requiredCapabilities) { "self_capability" }
                SyntheticLadderQuestion(id, version, ref, kp, capability, format, required(q, "teaching_kind"),
                    required(q, "stem"), options, answer, requiredCapabilities, strings(q["prerequisite_refs"], "prerequisite_refs"), model, rubric)
            }
            val index = questions.associateBy { it.ref }
            require(index.size == questions.size) { "duplicate_question_version" }
            val aliases = linkedMapOf<String, String>()
            val aliasRows = raw["prerequisite_aliases"] as? JsonArray ?: error("aliases_required")
            for (value in aliasRows) {
                val a = value as? JsonObject ?: error("invalid_alias")
                val from = required(a, "from"); val to = required(a, "to")
                require(from !in aliases) { "ambiguous_alias" }
                val old = index[from] ?: error("dangling_alias"); val next = index[to] ?: error("dangling_alias")
                require(old.id == next.id && old.kp == next.kp && old.capability == next.capability && old.format == next.format) { "alias_identity_changed" }
                val order = Regex("v[1-9][0-9]*")
                require(order.matches(old.version) && order.matches(next.version)) { "alias_version_order_unknown" }
                val left = old.version.drop(1).toLongOrNull(); val right = next.version.drop(1).toLongOrNull()
                require(left != null && right != null && right > left) { "alias_not_newer" }
                aliases[from] = to
            }
            val runtime = SyntheticLadderRuntime(questions, scope, aliases)
            val visiting = mutableSetOf<String>(); val done = mutableSetOf<String>()
            fun visit(ref: String) {
                if (ref in done) return
                require(visiting.add(ref)) { "prerequisite_cycle" }
                for (recorded in index.getValue(ref).prerequisiteRefs.orEmpty()) {
                    val target = runtime.resolve(recorded)
                    require(target in index) { "dangling_prerequisite_ref" }
                    visit(target)
                }
                visiting.remove(ref); done.add(ref)
            }
            index.keys.forEach(::visit)
            return runtime
        }
    }
}
