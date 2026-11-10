package com.coach.server

import com.coach.domain.*
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.*

/** Explicit constructor injection only. Production Mainline never loads or approves a synthetic pack. */
internal fun SyntheticLadderRuntime.mainlineWeek(capabilities: Set<String>): JsonObject = json(
    "week" to 1, "title" to "Synthetic runtime", "learning" to true,
    "runtime" to LedgerJson.encodeToJsonElement(context(capabilities)),
    "items" to JsonArray(questions.map { it to ladder(it) }.filter { (q, l) ->
        l.eligible && q.kind in SyntheticLadderRuntime.KINDS && missing(q, capabilities).isEmpty()
    }.map { (q, l) -> json("id" to q.ref, "tier" to "detail", "stem" to q.stem,
        "options" to q.options, "status" to "pending", "keyState" to "UNREVIEWED", "ladder" to LedgerJson.encodeToJsonElement(l)) }))

internal fun SyntheticLadderRuntime.mainlineAnswer(input: JsonObject, capabilities: Set<String>): JsonObject {
    val q = questions.singleOrNull { it.ref == input.str("itemId") }
        ?: throw MockError(HttpStatusCode.NotFound, "unknown_question_version")
    if (q.format != "mcq" || q.kind !in SyntheticLadderRuntime.KINDS)
        throw MockError(HttpStatusCode.Conflict, "synthetic_exercise_format_unsupported")
    if (missing(q, capabilities).isNotEmpty()) throw MockError(HttpStatusCode.Conflict, "synthetic_question_not_ready")
    val choice = if (input.flag("dontKnow")) -1 else (input["choice"] as? JsonPrimitive)?.intOrNull
        ?.takeIf { it in q.options.orEmpty().indices } ?: throw MockError(HttpStatusCode.BadRequest, "invalid_choice")
    if (input.str("submissionId").isBlank()) throw MockError(HttpStatusCode.BadRequest, "synthetic_submission_required")
    val receipt = reveal(q.ref, capabilities, submitted = true)
    // No registerQuestion, answer_submitted, grade, review or mastery writes in this branch.
    return JsonObject(receipt + json("itemId" to q.ref, "choice" to choice, "keyState" to "UNREVIEWED",
        "independentCorrect" to false, "status" to "pending", "dontKnow" to input.flag("dontKnow"),
        "guessed" to input.flag("guessed"), "syntheticOnly" to true,
        "explanation" to json("correct" to "合成示例：参考选项 ${q.options.orEmpty().getOrNull(q.answer ?: -1).orEmpty()}。不计掌握。")))
}
