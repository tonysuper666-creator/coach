package com.coach.migrate

import kotlinx.serialization.json.*

/** Reviewable data contract shared by the dry-run planner and the isolated database writer. */
fun proposalInput(mapping:JsonObject):JsonObject = buildJsonObject {
    put("knowledge_points",JsonArray(mapping.array("knowledge_points").map { v ->
        val k=v.jsonObject
        buildJsonObject { for(key in listOf("id","name","module_id","content_hash")) put(key,k.getValue(key)) }
    }))
    put("questions",JsonArray(mapping.array("questions").map { v ->
        val q=v.jsonObject
        buildJsonObject {
            put("id",q.str("id"));put("content_hash",q.str("content_hash"))
            val raw=q.obj("source_record")
            put("stem",raw.str("question").ifBlank { raw.str("prompt") })
            put("tags",raw["tags"] ?: JsonArray(emptyList()))
        }
    }))
}

fun validateProposal(mapping:JsonObject, proposal:JsonObject) {
    require(proposal.str("input_hash")==sha(canonical(proposalInput(mapping)))) { "Proposal is stale; regenerate and review against this snapshot" }
    val proposer=proposal.obj("proposer");val review=proposal.obj("review")
    require(proposer.str("model").isNotBlank() && proposer.str("vendor").isNotBlank()) { "Missing proposer provenance" }
    require(review.str("model").isNotBlank() && review.str("vendor").isNotBlank() && review.str("vendor")!=proposer.str("vendor")) { "Review must use another vendor" }
    val body=JsonObject(proposal.filterKeys { it!="review" })
    require(review.str("proposal_hash")==sha(canonical(body)) && review.str("status")=="reviewed") { "Review must bind the exact proposal" }
    val kpIds=mapping.array("knowledge_points").map { it.jsonObject.str("id") }.toSet()
    val questionIds=mapping.array("questions").map { it.jsonObject.str("id") }.toSet()
    val points=proposal.array("knowledge_points").map { it.jsonObject }
    val questions=proposal.array("questions").map { it.jsonObject }
    require(points.map { it.str("id") }.toSet()==kpIds && points.size==kpIds.size) { "Proposal must cover every KP exactly once" }
    require(questions.map { it.str("id") }.toSet()==questionIds && questions.size==questionIds.size) { "Proposal must cover every question exactly once" }
    fun ids(item:JsonObject,key:String)=item.array(key).map { it.jsonPrimitive.content }
    questions.forEach { q ->
        require(ids(q,"kp_ids").all { it in kpIds } && q.str("reason").isNotBlank()) { "Question needs supported KP IDs and a reason" }
        require(ids(q,"kp_ids").isNotEmpty() || (q.str("status")=="unresolved" && q.str("warning").isNotBlank())) { "Unmapped question must explicitly preserve the unresolved reason" }
    }
    val concepts=proposal.array("concepts").map { it.jsonObject }
    val conceptIds=concepts.map { it.str("id") }.toSet()
    require(conceptIds.size==concepts.size && concepts.all { it.str("id").isNotBlank() && it.str("name").isNotBlank() }) { "Invalid concept catalog" }
    points.forEach { k ->
        require(ids(k,"concept_ids").isNotEmpty() && ids(k,"concept_ids").all { it in conceptIds }) { "Every KP needs known concept IDs" }
        require(ids(k,"prerequisite_ids").all { it in kpIds && it!=k.str("id") }) { "Unknown or self prerequisite" }
    }
    val byId=points.associateBy { it.str("id") }
    val visiting=mutableSetOf<String>();val visited=mutableSetOf<String>()
    fun visit(id:String) {
        if(id in visited) return
        require(visiting.add(id)) { "Prerequisite cycle at $id" }
        ids(byId.getValue(id),"prerequisite_ids").forEach(::visit)
        visiting.remove(id);visited.add(id)
    }
    kpIds.forEach(::visit)
}

fun validateSeed(mapping:JsonObject, seed:JsonObject) {
    val kps=mapping.array("knowledge_points").map { it.jsonObject }
    val existing=seed.array("knowledge_points").map { it.jsonObject }
    require(existing.map { it.str("id") }.toSet()==kps.map { it.str("id") }.toSet() && existing.size==kps.size) { "Seeded course KP set differs; reconcile content before importing history" }
    require(existing.map { it.str("id") }==kps.map { it.str("id") } && existing.zip(kps).all { (a,b) -> a.str("name")==b.str("name") && a.str("module_id")==b.str("module_id") }) { "Seeded course order/title/module differs" }
    val sourceCourseIds=mapping.array("course_records").map { it.jsonObject.obj("source_record").str("id") }
    require(seed.obj("course").str("id") in sourceCourseIds) { "Seeded course ID not in source" }
    val weights=seed.obj("exam_weights")
    val kpIds=kps.map { it.str("id") }.toSet()
    require(weights.isNotEmpty() && weights.keys.all { it in kpIds } && weights.values.all { (it as? JsonPrimitive)?.doubleOrNull?.let { n -> n.isFinite() && n>=0 }==true }) { "Invalid seeded exam weights" }
    val total=weights.values.sumOf { it.jsonPrimitive.double }
    val sourceTotal=mapping.array("exam_weights_set").sumOf { it.jsonObject.obj("source_record").getValue("points").jsonPrimitive.double }
    require(kotlin.math.abs(total-sourceTotal)<0.000001) { "Seeded exam weight total differs from source table" }
}

/** Stable source identity plus payload hash: revisions replace a record rather than adding an answer. */
fun plannedRecord(source:JsonObject,kind:String,payload:JsonObject):JsonObject = buildJsonObject {
    put("id",source.str("id"));put("kind",kind);put("source_hash",source.str("content_hash"))
    put("payload",payload);put("payload_hash",sha(canonical(payload)))
}

fun importPlan(mapping:JsonObject,seed:JsonObject,proposal:JsonObject?):JsonObject {
    validateSeed(mapping,seed)
    if(proposal!=null) validateProposal(mapping,proposal)
    val courseId=seed.obj("course").str("id")
    val records=mutableListOf<JsonObject>()
    fun add(section:String,kind:String=section,transform:(JsonObject)->JsonObject={ it.obj("source_record") }) {
        mapping.array(section).forEach { v -> val row=v.jsonObject;records.add(plannedRecord(row,kind,transform(row))) }
    }
    add("questions","legacy_question") { row -> buildJsonObject {
        put("question",row.obj("question"));put("keyState","UNREVIEWED")
        put("kp_ids",JsonArray(emptyList()));put("association_confirmed",false)
    } }
    add("legacy_attempts","legacy_attempt") { row ->
        val raw=row.obj("source_record");val q=parseText(raw.str("question_json")).jsonObject
        val result=parseText(raw.str("result_json")) as? JsonObject ?: JsonObject(emptyMap())
        buildJsonObject {
            put("origin","LEGACY");put("window_eligible",false);put("question",mapChoiceQuestion(q))
            put("kp_id",q.str("knowledge_point_id"));put("reset",row.getValue("reset"))
            put("reset_reason",row.str("reset_reason"));put("created_at",raw["created_at"] ?: JsonNull)
            put("answer",if(isChoice(q)) choiceAnswer(q,raw.str("user_answer")) else JsonPrimitive(raw.str("user_answer")))
            put("answer_unresolved",isChoice(q) && choiceAnswer(q,raw.str("user_answer"))==JsonNull)
            if(isChoice(q) && choiceAnswer(q,raw.str("user_answer"))==JsonNull)
                put("unresolved_answer_text",raw.str("user_answer"))
            // Free feedback remains historical text; structured letter keys never become answer IDs.
            put("result",JsonObject(result.filterKeys { it in setOf("is_correct","result","feedback","confidence","final","needs_clarification","independent") }))
        }
    }
    add("legacy_passed") { row -> buildJsonObject {
        put("kp_id",row.str("id").substringAfterLast(':'));put("passed",true);put("solid",false)
    } }
    add("legacy_schedule") { row -> buildJsonObject {
        put("kp_id",row.str("kp_id"));put("dueAt",row.getValue("dueAt"))
        put("queue_eligible",row.getValue("queue_eligible"));put("original_state",row.obj("source_record"))
    } }
    add("notes");add("events_archive","legacy_event_archive")
    mapping.obj("journal_days").toSortedMap().forEach { (day,entries) -> entries.jsonArray.forEach { v ->
        val row=v.jsonObject
        records.add(plannedRecord(row,"legacy_journal",buildJsonObject { put("day",day);put("entry",row.obj("source_record")) }))
    } }
    records.add(plannedRecord(mapping.obj("journal"),"legacy_journal_metadata",mapping.obj("journal").obj("source_record")))
    mapping.obj("chat_archive").array("messages").forEach { v ->
        val row=v.jsonObject
        records.add(plannedRecord(row,"legacy_message",row.obj("source_record")))
    }
    require(records.map { it.str("id") }.toSet().size==records.size) { "Duplicate source identity in import plan" }
    return buildJsonObject {
        put("schema_version",1);put("dry_run",true);put("course_id",courseId)
        put("input_hash",sha(canonical(proposalInput(mapping))));put("seed_hash",sha(canonical(seed)))
        put("content_action","reference_existing_only");put("exam_weights_action","reuse_existing_event")
        put("exam_weights",seed.obj("exam_weights"))
        put("human_confirmation","pending");put("proposals",proposal ?: JsonNull)
        put("unknown_types",JsonArray(mapping.array("knowledge_points").map { it.jsonObject }.filter { it["original_type_available"]==JsonPrimitive(false) }))
        put("records",JsonArray(records.sortedBy { it.str("id") }))
        put("counts",buildJsonObject {
            put("records",records.size)
            put("question_proposals",proposal?.array("questions")?.count { it.jsonObject.array("kp_ids").isNotEmpty() } ?: 0)
            put("questions_without_proposals",mapping.array("questions").size-(proposal?.array("questions")?.count { it.jsonObject.array("kp_ids").isNotEmpty() } ?: 0))
            put("unresolved_historical_choice_answers",records.count { it.str("kind")=="legacy_attempt" && it.obj("payload")["answer_unresolved"]==JsonPrimitive(true) })
            put("confirmed_question_associations",0);put("content_inserts",0)
        })
        put("archive_only",buildJsonObject {
            for(key in listOf("legacy_attempt_groups","excluded_path_archives")) put(key,mapping.getValue(key))
            put("assessment_attempts",JsonArray(mapping.obj("chat_archive").array("assessment_attempts")))
            put("sessions",JsonArray(mapping.obj("chat_archive").array("sessions").map { v ->
                val row=v.jsonObject
                plannedRecord(row,"legacy_session",JsonObject(row.obj("source_record").filterKeys {
                    it in setOf("id","title","created_at","updated_at","deleted_at")
                }))
            }))
        })
    }
}

data class Simulation(val state:JsonObject,val inserted:Int,val revised:Int,val unchanged:Int)
fun simulatePlan(plan:JsonObject,prior:JsonObject=JsonObject(emptyMap())):Simulation {
    val state=prior.toMutableMap();var inserted=0;var revised=0;var unchanged=0
    for(value in plan.array("records")) {
        val row=value.jsonObject;val id=row.str("id");val old=state[id] as? JsonObject
        when { old==null -> inserted++;old==row -> unchanged++;else -> revised++ }
        state[id]=row
    }
    return Simulation(JsonObject(state.toSortedMap()),inserted,revised,unchanged)
}

fun confirmationSheet(mapping:JsonObject,proposal:JsonObject?):String = buildString {
    append("# 学习记录搬家确认草稿\n\n这份清单只供核对；隔离副本预演通过后再合并请你确认。正式库尚未导入学习记录。旧作答永远只作历史展示，不计入新学习成绩。\n\n")
    if(proposal==null) { append("题目归属、概念与建议先学的内容尚未完成异厂复核。\n");return@buildString }
    validateProposal(mapping,proposal)
    val kps=mapping.array("knowledge_points").map { it.jsonObject }.associateBy { it.str("id") }
    val concepts=proposal.array("concepts").map { it.jsonObject }.associate { it.str("id") to it.str("name") }
    append("提议：${proposal.obj("proposer").str("model")}；复核：${proposal.obj("review").str("model")}。模型意见仍需你确认，未确认的题目不计分。\n\n")
    append("## 题目放在哪个知识点\n\n")
    val qs=mapping.array("questions").map { it.jsonObject }.associateBy { it.str("id") }
    proposal.array("questions").forEachIndexed { n,v ->
        val q=v.jsonObject;val raw=qs.getValue(q.str("id")).obj("source_record")
        val names=q.array("kp_ids").map { kps.getValue(it.jsonPrimitive.content).str("name") }
        append("${n+1}. ${raw.str("question").replace('\n',' ')}\n   放到：${names.joinToString("；").ifBlank { "暂无对应知识点，待核对" }}。理由：${q.str("reason")}\n")
        if(q.str("warning").isNotBlank()) append("   待核对：${q.str("warning")}\n")
    }
    append("\n## 概念与建议先学的内容\n\n")
    proposal.array("knowledge_points").forEach { v ->
        val k=v.jsonObject
        val names=k.array("prerequisite_ids").map { kps.getValue(it.jsonPrimitive.content).str("name") }
        append("- ${kps.getValue(k.str("id")).str("name")}\n  概念：${k.array("concept_ids").joinToString("、") { concepts.getValue(it.jsonPrimitive.content) }}；建议先学：${names.joinToString("；").ifBlank { "无" }}\n")
    }
    append("\n## 无法自动识别的历史回答\n\n下列回答保留原文，不推断大小写或替你选择；字母仅用于对照当时选项。\n\n")
    mapping.array("legacy_attempts").forEach { v ->
        val raw=v.jsonObject.obj("source_record");val q=parseText(raw.str("question_json")).jsonObject
        if(isChoice(q) && choiceAnswer(q,raw.str("user_answer"))==JsonNull) {
            append("- ${q.str("question").ifBlank { q.str("prompt") }}\n  原回答：${raw.str("user_answer")}\n")
            choiceEntries(q).forEach { (letter,text) -> append("  $letter：$text\n") }
        }
    }
    append("\n## 简答评分标准抽查\n\n预演通过后合并询问：归属和学习顺序有没有需要调整的？以下 10 道简答的评分标准是否合理？\n\n")
    val short=qs.values.filter { it.obj("source_record").str("question_type")=="short_answer" }
    val sample=if(short.size<=10) short else (0..9).map { short[it*(short.size-1)/9] }
    sample.forEachIndexed { n,row ->
        val raw=row.obj("source_record")
        append("${n+1}. ${raw.str("question")}\n\n")
        val rubric=raw["rubric"] ?: raw["grading_rubric"] ?: raw["marking_criteria"] ?: raw["explanation"]
        val rubricText=when(rubric) { is JsonPrimitive -> rubric.contentOrNull;null -> null;else -> canonical(rubric) }
        append("源采分点（尚待本人抽查）：${rubricText ?: "源题没有独立评分标准，待生成并异厂复核"}\n\n")
    }
}

fun confirmationSummary(mapping:JsonObject,proposal:JsonObject?):String = buildString {
    append("# 学习记录搬家：确认草稿\n\n隔离副本预演通过后再确认，正式库目前没有导入学习记录。\n\n")
    val counts=mapping.obj("counts")
    append("保留 ${counts.str("passed")} 个已通过、${counts.str("attempts")} 条旧作答、${counts.str("schedules")} 条复习、${counts.str("notes")} 条笔记。旧作答只展示，不计入新成绩；${counts.str("original_types_missing")} 项缺失的原类型保留未知。\n\n")
    if(proposal==null) { append("模型提议和异厂复核尚未齐全。\n");return@buildString }
    validateProposal(mapping,proposal)
    append("提议：${proposal.obj("proposer").str("model")}；复核：${proposal.obj("review").str("model")}。这只是模型意见，尚未得到你的确认。\n\n")
    append("需要一次核对两件事：\n\n1. 108 道题放在哪些知识点、49 个知识点包含什么概念、建议先学什么。逐项草稿见随附的详细清单。\n2. 详细清单末尾抽出的 10 道简答，源采分点是否合理。\n\n")
    append("需特别留意的题（序号与详细清单一致）：\n\n")
    proposal.array("questions").forEachIndexed { n,v -> val q=v.jsonObject
        if(q.str("warning").isNotBlank()) append("- 第 ${n+1} 题：${q.str("warning")}\n")
    }
    val edges=proposal.array("knowledge_points").sumOf { it.jsonObject.array("prerequisite_ids").size }
    val unresolved=proposal.array("questions").count { it.jsonObject.array("kp_ids").isEmpty() }
    append("\n${proposal.array("questions").size-unresolved} 道题有归属提议，$unresolved 道待解决。概念采用共享名称，跨知识点讲过的内容可识别为同一个概念。共有 $edges 条建议前置，仅建议学习顺序；确认前不会生效。\n\n")
    append("你之后可以一次回复：需要调整的题号或学习顺序，以及 10 道简答中有问题的采分点。课程内容沿用已经装好的版本，不重复装载。\n")
}
