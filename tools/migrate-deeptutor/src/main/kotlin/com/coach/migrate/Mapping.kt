package com.coach.migrate

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.serialization.json.*

fun JsonObject.array(key:String)=(this[key] as? JsonArray)?.toList().orEmpty()
fun JsonObject.obj(key:String)=(this[key] as? JsonObject) ?: JsonObject(emptyMap())
fun record(source:String, table:String, pk:String, data:JsonElement)=buildJsonObject {
    put("id", "legacy:$source:$table:$pk")
    put("content_hash",sha(canonical(data)))
    put("source_record",data)
}
data class Result(val mapping:JsonObject,val report:String)
fun mapSnapshot(snap:Path):Result {
    val root=snap.toRealPath()
    require(root.resolve("copy-stats.json").isRegularFile()) { "Use a captured staging directory" }
    for(name in listOf("data/user/workspace/learning/mastery/mastery.sqlite3", "data/user/chat_history.db")) {
        require(root.resolve(name).toRealPath().startsWith(root)) { "Database escapes staged directory" }
    }
    val mastery=readDatabase(snap.resolve("data/user/workspace/learning/mastery/mastery.sqlite3"))
    val chat=readDatabase(snap.resolve("data/user/chat_history.db"))
    val gates=parseFile(snap.resolve("tools/patch_backups/gate_types_before.json")).jsonObject
    val ranked=mastery.getValue("mastery_paths").map { row ->
        val aggregate=parseText(row.str("state_json")).jsonObject
        val overlap=aggregate.array("modules").flatMap { it.jsonObject.array("knowledge_points") }
            .count { it.jsonObject.str("id") in gates }
        row to overlap
    }.sortedByDescending { it.second }
    require(ranked.isNotEmpty() && ranked.first().second>0 && ranked.count { it.second==ranked.first().second }==1) {
        "Cannot uniquely identify the original path from gate_types_before; inspect the schema before migration"
    }
    val pathRow=ranked.first().first
    val mainPath=pathRow.str("path_id")
    val state=parseText(pathRow.str("state_json")).jsonObject
    val modules=state.array("modules").map { it.jsonObject }
    val evidence=state.obj("lesson_evidence")
    val kps=modules.flatMap { module -> module.array("knowledge_points").map { kp ->
        val k=kp.jsonObject; val id=k.str("id")
        buildJsonObject {
            put("id",id); put("legacy_id","legacy:mastery.sqlite3:knowledge_points:$id")
            put("name",k.str("name")); put("module_id",module.str("id"))
            put("type",gates[id] ?: JsonNull)
            put("current_source_type",k["type"] ?: JsonNull)
            put("original_type_available",gates.containsKey(id))
            put("examFormats",JsonArray(if("【Essay】" in k.str("name")) listOf(JsonPrimitive("ESSAY")) else emptyList()))
            put("prerequisite_ids",JsonArray(emptyList()))
            put("anchors",evidence[id] ?: JsonNull)
            put("content_hash",sha(canonical(k)))
        }
    } }
    val kpIds=kps.map { it.str("id") }.toSet()
    val passed=state.obj("mastery_levels").filter { (id,v) -> id in kpIds && (v.jsonPrimitive.doubleOrNull ?: 0.0)>=1.0 }.keys.sorted()
    val answeredRows=mastery.getValue("mastery_interactions").filter { it.str("path_id")==mainPath && it.str("user_answer").isNotBlank() }
    val groups=answeredRows.filter { parseText(it.str("question_json")).jsonObject.str("question_type")=="group" }
    val interactions=answeredRows.filterNot { it in groups }
    val interactionIds=interactions.map { it.str("interaction_id") }.toSet()
    val groupMappings=groups.map { row ->
        val question=parseText(row.str("question_json")).jsonObject
        val children=question.array("questions").map { it.jsonObject.str("question_id") }
        JsonObject(legacy("mastery.sqlite3","mastery_interactions",row) + mapOf(
            "child_ids" to JsonArray(children.map { JsonPrimitive("legacy:mastery.sqlite3:mastery_interactions:${java.net.URLEncoder.encode(it,Charsets.UTF_8)}") }),
            "missing_child_rows" to JsonArray(children.filterNot { it in interactionIds }.map(::JsonPrimitive)),
            "window_eligible" to JsonPrimitive(false)
        ))
    }
    val learning=mastery.getValue("mastery_learning_evidence").filter { it.str("path_id")==mainPath }
    val events=mastery.getValue("mastery_events").filter { it.str("path_id")==mainPath }
    val assessments=chat["assessment_attempts"].orEmpty().filter { it.str("mastery_path_id")==mainPath }
    val sessionIds=(interactions.map { it.str("session_id") } + assessments.map { it.str("session_id") } + pathRow.str("owner_session_id")).filter { it.isNotBlank() }.toSet()
    val courseChat=mapOf(
        "sessions" to chat["sessions"].orEmpty().filter { it.str("id") in sessionIds },
        "messages" to chat["messages"].orEmpty().filter { it.str("session_id") in sessionIds },
        "assessment_attempts" to assessments
    )
    val attempts=interactions.map { row ->
        val question=parseText(row.str("question_json")).jsonObject
        val qid=question.str("question_id").ifBlank { question.str("id") }
        val retained=learning.any { e ->
            val ev=parseText(e.str("evidence_json")).jsonObject
            ev.str("evidence_id")==row.str("interaction_id") || (qid.isNotBlank() && ev.str("question_id")==qid)
        }
        val reset=!retained && question.str("knowledge_point_id") !in kpIds
        buildJsonObject {
            legacy("mastery.sqlite3","mastery_interactions",row).forEach { (k,v) -> put(k,v) }
            put("window_eligible",false)
            put("reset",reset)
            put("current_evidence_present",retained)
            put("reset_reason",if(reset) "大纲合并时重置（原 KP 已移除且当前证据表无对应记录）" else "")
            put("event_ids",JsonArray(events.filter { e ->
                e.str("payload_json").contains(qid) && qid.isNotBlank()
            }.map { JsonPrimitive("legacy:mastery.sqlite3:mastery_events:${it.str("_source_pk")}") }))
            put("question_id",qid)
        }
    }
    val schedules=state.obj("repetition_states").toSortedMap().map { (kp,s) ->
        val entry=s.jsonObject
        buildJsonObject {
            record("mastery.sqlite3","repetition_states",kp,s).forEach { (k,v) -> put(k,v) }
            put("kp_id",kp); put("kind","legacy_schedule"); put("queue_eligible",kp in passed)
            put("dueAt",entry["next_review_at"] ?: JsonNull)
            val next=entry["next_review_at"]?.jsonPrimitive?.doubleOrNull
            val last=entry["last_review_at"]?.jsonPrimitive?.doubleOrNull
            put("interval_seconds",if(next!=null && last!=null) JsonPrimitive(next-last) else JsonNull)
        }
    }
    val qroot=parseFile(snap.resolve("data/digests/questions/ECNM10119-期中练习题.json"))
    val rawQuestions=if(qroot is JsonArray) qroot.map { it.jsonObject } else qroot.jsonObject.array("questions").map { it.jsonObject }
    val questions=rawQuestions.mapIndexed { n,q -> JsonObject(q + mapOf("id" to JsonPrimitive(q.str("id").ifBlank { "position:$n" }))) }
    val matches=questions.map { matchQuestion(it,kps) }
    val notesRoot=snap.resolve("data/user/workspace/notebook")
    val notebooks=safeFiles(notesRoot).filter { it.name!="notebooks_index.json" && it.extension=="json" }.map { it to parseFile(it).jsonObject }
    val notes=notebooks.flatMap { (file,book) -> book.array("records").mapIndexed { n,entry ->
        val item=entry.jsonObject
        val chapter=chapters(item.str("title"))
        val related=kps.filter { chapters(it.str("name")).intersect(chapter).isNotEmpty() }
        JsonObject(record(file.name,"records",item.str("id").ifBlank { n.toString() },item)+mapOf(
            "kp_ids" to JsonArray(related.map { JsonPrimitive(it.str("id")) }),
            "association_status" to JsonPrimitive(if(related.isEmpty()) "需人工确认" else "chapter")
        ))
    } }
    val journal=parseFile(snap.resolve("data/learning_journal/journal.json"))
    val journalRecords=if(journal is JsonArray) journal.toList() else journal.jsonObject.array("records")
    val journalDays=journalRecords.mapIndexed { n,value ->
        val item=value.jsonObject
        val date=item.str("created_at").substringBefore('T').ifBlank { "undated" }
        date to record("journal.json","records",item.str("id").ifBlank { "position:$n" },item)
    }.groupBy({ it.first },{ it.second }).toSortedMap()
    val courses=parseFile(snap.resolve("data/user/workspace/courses/courses.json"))
    val pageRegex=Regex("<!--\\s*PDF 第 (\\d+) 页 · 书第 (.*?) 页\\s*-->")
    val textbooks=safeFiles(snap.resolve("library/ECNM10119-健康经济学/教材")).map { file -> buildJsonObject {
        put("file",file.name)
        put("anchors",JsonArray(pageRegex.findAll(file.readText()).map { m -> buildJsonObject {
            put("pdf_page",m.groupValues[1].toInt());put("book_page",m.groupValues[2])
        } }.toList()))
    } }
    val weights=snap.resolve("exam-weights.md").readText()
    val weightRows=weights.lines().filter { it.startsWith("| ") && it.split('|').getOrNull(1)?.trim()?.toIntOrNull()!=null }.mapIndexed { n,line ->
        val cells=line.split('|').drop(1).dropLast(1).map { it.trim() }
        record("exam-weights.md","exam_weights_set",n.toString(),buildJsonObject {
            put("points",cells[0].toInt());put("question",cells[1]);put("kp_hint",cells[2]);put("estimated",true)
        })
    }
    val excludedSchedules=schedules.filter { it["queue_eligible"]==JsonPrimitive(false) }.map { it.str("kp_id") }
    val count=buildJsonObject {
        put("knowledge_points",kps.size);put("modules",modules.size);put("questions",questions.size)
        put("mcq",questions.count { it.str("question_type").ifBlank { it.str("type") }.lowercase() in setOf("mcq","multiple_choice","single_choice","choice") })
        put("interaction_rows",answeredRows.size);put("attempt_groups",groups.size)
        put("groups_missing_children",groupMappings.sumOf { it.array("missing_child_rows").size })
        put("original_types_missing",kps.count { it["original_type_available"]==JsonPrimitive(false) })
        put("attempts",attempts.size);put("retained_evidence",learning.size);put("reset_attempts",attempts.count { it["reset"]==JsonPrimitive(true) })
        put("schedules",schedules.size);put("notes",notes.size);put("passed",passed.size)
        put("deterministic_questions",matches.count { it.str("status")=="deterministic" })
        put("short_answer",questions.count { it.str("question_type")=="short_answer" })
        put("journal_entries",journalRecords.size)
        put("textbook_files",textbooks.size);put("textbook_pages",textbooks.sumOf { it.array("anchors").size })
        put("exam_weight_rows",weightRows.size)
        put("assessment_attempts",assessments.size)
        put("assessment_missing_interactions",assessments.count { a ->
            a.str("mastery_path_id")==mainPath && interactions.none { it.str("interaction_id")==a.str("question_id") }
        })
        put("questions_without_source_ids",rawQuestions.count { it.str("id").isBlank() })
        put("notes_pending_association",notes.count { it.str("association_status")=="需人工确认" })
        put("messages",courseChat.getValue("messages").size)
        put("sessions",courseChat.getValue("sessions").size)
        put("events",events.size)
        put("attempts_without_current_evidence",attempts.count { it["current_evidence_present"]==JsonPrimitive(false) })
        put("ungraded_answers",interactions.count { it.str("status")!="graded" })
    }
    val mapping=buildJsonObject {
        put("schema_version",1);put("dry_run",true);put("counts",count)
        put("path_id",mainPath)
        put("courses",record("courses.json","courses","root",courses))
        put("course_records",JsonArray(courses.jsonObject.array("courses").map { value ->
            val course=value.jsonObject
            require(course.str("id").isNotBlank()) { "Course has no stable source ID" }
            record("courses.json","courses",course.str("id"),course)
        }))
        put("knowledge_points",JsonArray(kps))
        put("modules",JsonArray(modules.map { record("mastery.sqlite3","modules",it.str("id"),it) }))
        put("legacy_passed",JsonArray(passed.map { record("mastery.sqlite3","mastery_levels",it,JsonPrimitive(1.0)) }))
        put("legacy_attempts",JsonArray(attempts));put("legacy_attempt_groups",JsonArray(groupMappings));put("legacy_schedule",JsonArray(schedules))
        put("question_kp_candidates",JsonArray(matches))
        put("questions",JsonArray(questions.mapIndexed { n,q -> record("ECNM10119-期中练习题.json","questions",q.str("id").ifBlank { n.toString() },q) }))
        put("notes",JsonArray(notes));put("journal",record("journal.json","journal","root",journal))
        put("journal_days",JsonObject(journalDays.mapValues { JsonArray(it.value) }))
        put("textbook_page_inventory",JsonArray(textbooks));put("exam_weights_set",JsonArray(weightRows))
        put("materials_pending_confirmation",parseFile(snap.resolve("materials.json")))
        put("events_archive",JsonArray(events.map { legacy("mastery.sqlite3","mastery_events",it) }))
        put("chat_archive",buildJsonObject { for(table in listOf("sessions","messages","assessment_attempts")) put(table,JsonArray(courseChat[table].orEmpty().map { legacy("chat_history.db",table,it) })) })
        put("excluded_path_archives",JsonArray(mastery.getValue("mastery_paths").filter { it.str("path_id")!=mainPath }.map { legacy("mastery.sqlite3","mastery_paths",it) }))
    }
    val expected=mapOf("knowledge_points" to 49,"questions" to 108,"mcq" to 60,"short_answer" to 48,"attempts" to 20,"retained_evidence" to 17,"reset_attempts" to 3,"schedules" to 5,"notes" to 12,"passed" to 5)
    val report=buildString {
        append("# DeepTutor 迁移只读对账\n\n")
        append("仅 dry-run，不写正式库。LEGACY 作答永不进入任何窗口，仅历史展示与 legacy_passed 锁存。\n\n")
        append("| 项目 | 实测 | 设计基线 | 差异 |\n|---|---:|---:|---:|\n")
        expected.forEach { (k,v) -> val actual=count.getValue(k).jsonPrimitive.int;append("| $k | $actual | $v | ${actual-v} |\n") }
        val rate=if(questions.isEmpty()) 0.0 else 100.0*count.getValue("deterministic_questions").jsonPrimitive.int/questions.size
        append("\n自动归属：${count.str("deterministic_questions")}/${questions.size}（${"%.2f".format(java.util.Locale.ROOT,rate)}%）；其余需模型/人工确认。\n")
        append("\nlegacy_schedule 不在 legacy_passed：${excludedSchedules.size}；这些项不进入复习队列。\n")
        excludedSchedules.forEach { append("- $it\n") }
        append("\n原类型缺失：${kps.count { it["original_type_available"]==JsonPrimitive(false) }}；缺失时 type 为 null，当前松绑后的类型单列 current_source_type，不冒充原类型。实际表结构以 mastery_paths.state_json 内嵌 KP/掌握度/复习状态为准。\n")
        append("\n作答按叶子事实计数：${answeredRows.size} 行含 ${groups.size} 个组题容器，容器另存且只引用子题，不重复计为作答。${attempts.size} 条事实 = 当前证据 ${learning.size} + 重置 ${attempts.count { it["reset"]==JsonPrimitive(true) }}；两者仍有差异时须核对报告里的缺证据计数。\n")
        append("\n没有原始 ID 的题目以源数组位置为稳定键；内容修改是修订，题库重排需先人工核对，不能直接增量导入。\n")
        append("\n全部计数：\n```json\n${canonical(count)}```\n")
        append("\n教材只统计页标与页数，不建正式库；无页标记文件保留清单。资料仅列文件名，不进入导师上下文。\n")
        append("\n## 需模型/人工确认\n")
        matches.filter { it.str("status")!="deterministic" }.forEach { append("- ${it.str("question_id")}: ${it.str("reason")} (${it.array("candidate_ids").size} candidates)\n") }
        append("\n## 资料目录（M1 前逐项确认）\n")
        parseFile(snap.resolve("materials.json")).jsonArray.forEach { append("- ${it.jsonPrimitive.content}\n") }
        append("\n事件不重放；另一条路径仅存档；日志按源日期归档，对话只读展示。sealed 与受限答案目录不进入任何映射。\n")
        append("\n差异解释：设计基线是历史时点；本表保留这次有效副本的全部实际记录，非零差异不自动判成丢失或新增，须结合记录时间与源事件核对。缺当前证据且原 KP 已移除的历史作答标为大纲合并时重置，关联源事件随映射保留。\n")
    }
    return Result(mapping,report)
}
