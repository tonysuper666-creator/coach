package com.coach.migrate

import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.*
import kotlinx.serialization.json.*

/** Two successive captured snapshots, each applied twice to a disposable copy of its predecessor.
 * This command deliberately has no in-place target or production import option.
 */
fun incrementalRehearsalCommand(args:List<String>) {
    val allowed=setOf("--snapshot","--next-snapshot","--second-snapshot","--coach-db","--seed-manifest","--proposal","--out")
    require(args.size==allowed.size*2) { "Supply initial, next and second snapshots, Coach DB, seed, proposal and NEW output directory" }
    val options=args.chunked(2).associate { (k,v) -> require(k in allowed);k to v }
    require(options.keys==allowed)
    val home=Path.of(System.getProperty("user.home"))
    val protected=listOf(resolvedDestination(home.resolve("DeepTutor")))
    val out=resolvedDestination(Path.of(options.getValue("--out")))
    val source=Path.of(options.getValue("--coach-db")).toRealPath()
    val snapshots=listOf("--snapshot","--next-snapshot","--second-snapshot").map { Path.of(options.getValue(it)).toRealPath() }
    requirePrivateDestination(out,protected)
    require(protected.none { source.startsWith(it) } && !out.exists() && !out.startsWith(source.parent))
    snapshots.forEach { snap -> requirePrivateDestination(snap,protected);require(!out.startsWith(snap) && !snap.startsWith(out)) }
    val seed=parseFile(Path.of(options.getValue("--seed-manifest"))).jsonObject
    val proposal=parseFile(Path.of(options.getValue("--proposal"))).jsonObject
    // Validate every snapshot and the original review before creating any output.
    val mappings=snapshots.map { snap ->
        require(seed.obj("exam_weights")==prepareSeed(snap).manifest.obj("exam_weights"))
        mapSnapshot(snap).mapping
    }
    validateProposal(mappings.first(),proposal)
    val plans=mappings.drop(1).map { incrementalPlan(it,seed,proposal,mappings.first()) }
    val times=mappings.map { it.obj("history_time").getValue("snapshot_at").jsonPrimitive.long }
    require(times.zipWithNext().all { (a,b) -> a<b }) { "Incremental snapshots must advance capture time" }
    privateDir(out)
    val initial=out.resolve("initial")
    rehearseCommand(listOf("--snapshot",snapshots.first().toString(),"--coach-db",source.toString(),
        "--seed-manifest",options.getValue("--seed-manifest"),"--proposal",options.getValue("--proposal"),"--out",initial.toString()))
    var previous=initial.resolve("coach.sqlite")
    val reports=mutableListOf<JsonObject>()
    for(index in plans.indices) {
        val plan=plans[index];val mapping=mappings[index+1];val course=plan.str("course_id")
        val dir=privateDir(out.resolve("round-${index+1}"))
        dir.resolve("mapping.json").writeText(canonical(mapping))
        dir.resolve("import-plan.json").writeText(canonical(plan))
        backupDatabase(previous,dir.resolve("before.sqlite"))
        backupDatabase(dir.resolve("before.sqlite"),dir.resolve("coach.sqlite"))
        openReadOnly(dir.resolve("before.sqlite")).use { dir.resolve("before-state.json").writeText(canonical(databaseState(it,course))) }
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db ->
            db.exec("PRAGMA foreign_keys=ON")
            val first=writeHistory(db,plan,seed,mapping)
            val state=canonical(databaseState(db,course));dir.resolve("first-state.json").writeText(state)
            val second=writeHistory(db,plan,seed,mapping)
            val repeated=canonical(databaseState(db,course));dir.resolve("second-state.json").writeText(repeated)
            check(state==repeated && listOf("records_inserted","records_revised","events_added").all { second[it]==JsonPrimitive(0) })
            val report=buildJsonObject {
                put("round",index+1);put("first",first);put("second",second)
                put("snapshot_at",times[index+1]);put("state_sha256",sha(state))
                put("projection_sha256",sha(canonical(databaseState(db,course).getValue("projection"))))
                put("event_count",db.query("SELECT count(*) AS n FROM events").single().getValue("n"))
                put("production_written",false);put("activated",false)
            }
            dir.resolve("rehearsal.json").writeText(canonical(report));reports+=report
        }
        backupDatabase(dir.resolve("before.sqlite"),dir.resolve("rollback.sqlite"))
        openReadOnly(dir.resolve("rollback.sqlite")).use { check(canonical(databaseState(it,course))==dir.resolve("before-state.json").readText()) }
        previous=dir.resolve("coach.sqlite")
    }
    out.resolve("incremental.json").writeText(canonical(JsonArray(reports)))
    // Keep the reviewed sheet alongside the evidence. Changed bank versions must be reviewed
    // again; none of these proposals are admitted to question_kp/keys by the writer.
    val sheet=initial.resolve("confirmation-details.md").readText()
    val changes=plans.flatMapIndexed { index,plan -> plan.array("records").mapNotNull { v ->
        val row=v.jsonObject
        val old=(if(index==0) importPlan(mappings.first(),seed,proposal) else plans[index-1]).array("records")
            .map { it.jsonObject }.firstOrNull { it.str("id")==row.str("id") }
        if(old==null || old.str("source_hash")!=row.str("source_hash")) "- 第 ${index+1} 轮 `${row.str("id")}`：${old?.str("source_hash") ?: "新增"} → ${row.str("source_hash")}" else null
    } }
    out.resolve("confirmation-details.md").writeText(sheet+"\n\n## 隔离增量证据的版本差异（不发起确认）\n\n"+
        "以下差异仅供核对；若为人工构造的快照，不能当作真实新来源。已变题目的旧归属提议已隔离，须按新内容另行复核。\n\n"+changes.joinToString("\n")+"\n")
    println(canonical(JsonArray(reports)))
}
