package com.coach.migrate

import kotlinx.serialization.json.*

/** The ten samples are the same evenly spaced source rows shown on the reviewed sheet.
 * Acceptance preserves their text; it does not manufacture points or a VERIFIED key.
 */
fun shortAnswerSamples(mapping:JsonObject):JsonArray {
    val short=mapping.array("questions").map { it.jsonObject }
        .filter { it.obj("source_record").str("question_type")=="short_answer" }
    val sample=if(short.size<=10) short else (0..9).map { short[it*(short.size-1)/9] }
    return JsonArray(sample.map { row ->
        val raw=row.obj("source_record")
        val field=listOf("rubric","grading_rubric","marking_criteria","explanation").firstOrNull { raw.containsKey(it) }
        buildJsonObject {
            put("id",row.str("id"));put("content_hash",row.str("content_hash"))
            put("field",field?.let(::JsonPrimitive) ?: JsonNull);put("value",field?.let { raw.getValue(it) } ?: JsonNull)
        }
    })
}

/** Explicit, version-bound decision input. Provenance points to the actual human decision,
 * never to a fabricated key-review record. The CLI requires this file separately.
 */
fun validateConfirmation(mapping:JsonObject,proposal:JsonObject,confirmation:JsonObject) {
    validateProposal(mapping,proposal)
    require(confirmation["schema_version"]==JsonPrimitive(1) && confirmation.str("decision_ref").isNotBlank()) { "Missing decision provenance" }
    require(confirmation.str("input_hash")==sha(canonical(proposalInput(mapping))) &&
        confirmation.str("proposal_hash")==sha(canonical(proposal))) { "Confirmation does not bind this exact reviewed input" }
    val policies=mapOf("question_associations" to "accept_reviewed_proposal", "curriculum" to "accept_reviewed_proposal",
        "ambiguous_answers" to "preserve_verbatim_unscored", "choice_explanations" to "archive_only",
        "passed_times" to "approximate", "grading" to "unreviewed_no_keys")
    require(policies.all { (k,v) -> confirmation.str(k)==v }) { "Unsupported confirmation policy" }
    require(confirmation["short_answer_samples"]==shortAnswerSamples(mapping)) { "Accepted short-answer samples differ from the sheet" }
}

internal fun confirmedCurriculum(plan:JsonObject):JsonObject? {
    if(plan["human_confirmation"] !is JsonObject) return null
    val proposal=plan.obj("proposals")
    return buildJsonObject { put("concepts",proposal.getValue("concepts"));put("knowledge_points",proposal.getValue("knowledge_points")) }
}

fun confirmationReceipt(mapping:JsonObject,proposal:JsonObject,confirmation:JsonObject):String {
    validateConfirmation(mapping,proposal,confirmation)
    val unknown=proposal.array("questions").count { it.jsonObject.array("kp_ids").isEmpty() }
    return "# 学习记录搬家：已记录确认\n\n"+
        "确认来源：${confirmation.str("decision_ref")}。本次输入 SHA：${confirmation.str("input_hash")}；提议 SHA：${confirmation.str("proposal_hash")}。\n\n"+
        "${proposal.array("questions").size-unknown} 题接受归属，$unknown 题保留未知；接受概念与前置提议，以及 ${shortAnswerSamples(mapping).size} 道简答的源材料原文。\n\n"+
        "不补分值、不生成认证评分键，全部题目仍为 UNREVIEWED。无法解析的旧回答保留原文且不计分，选择题解析只归档，历史通过时间标为近似。\n\n"+
        "本回执绑定上述输入，无需重复询问。正式写入与启用仍须完成独立复核、备份、恢复和回退验收。\n"
}
