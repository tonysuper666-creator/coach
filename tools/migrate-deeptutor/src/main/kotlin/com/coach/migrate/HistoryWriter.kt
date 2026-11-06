package com.coach.migrate

import com.coach.domain.Event
import com.coach.domain.LedgerJson
import com.coach.domain.logicalDay
import com.coach.domain.project
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.sqlite.SQLiteConnection
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlin.io.path.*

internal fun openReadOnly(path:Path):Connection = DriverManager.getConnection("jdbc:sqlite:file:${path.toRealPath().toUri().rawPath}?mode=ro")
internal fun Connection.exec(sql:String,vararg args:Any?):Int = prepareStatement(sql).use { ps ->
    args.forEachIndexed { i,v -> ps.setObject(i+1,v) };ps.executeUpdate()
}
internal fun Connection.query(sql:String,vararg args:Any?):List<JsonObject> = prepareStatement(sql).use { ps ->
    args.forEachIndexed { i,v -> ps.setObject(i+1,v) }
    ps.executeQuery().use { rs -> buildList { while(rs.next()) add(buildJsonObject {
        for(i in 1..rs.metaData.columnCount) put(rs.metaData.getColumnLabel(i),when(val v=rs.getObject(i)) {
            null -> JsonNull
            is ByteArray -> JsonPrimitive(java.util.Base64.getEncoder().encodeToString(v))
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        })
    }) } }
}

/** SQLite online backup includes committed WAL data; the source connection cannot write. */
internal fun backupDatabase(source:Path,destination:Path) {
    require(!destination.exists()) { "Backup destination already exists" }
    openReadOnly(source).use { db ->
        check((db as SQLiteConnection).database.backup("main",destination.toString(),null)==0) { "SQLite backup failed" }
    }
}

/** Logical rows, schema and replay. Only migration audit clocks are normalized; native rows stay exact. */
internal fun databaseState(db:Connection,course:String):JsonObject {
    val tables=db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
    val logical=buildJsonObject {
        for(t in tables) {
            val name=t.str("name");val escaped=name.replace("\"","\"\"")
            val rows=db.query("SELECT * FROM \"$escaped\"").map { row -> when {
                name=="migration_batches" -> JsonObject(row+("recorded_at" to JsonPrimitive(0)))
                name=="events" && row.str("device_id")=="deeptutor-migration" -> {
                    val event=parseText(row.str("event_json")).jsonObject
                    JsonObject(row+mapOf("recorded_at" to JsonPrimitive(0),
                        "event_json" to JsonPrimitive(canonical(JsonObject(event+("recordedAt" to JsonPrimitive(0)))))))
                }
                else -> row
            } }
            put(name,JsonArray(rows.sortedBy(::canonical)))
        }
    }
    val events=db.query("SELECT event_json FROM events WHERE course_id=? ORDER BY seq",course)
        .map { LedgerJson.decodeFromString<Event>(it.str("event_json")) }
    val projection=project(events)
    return buildJsonObject {
        put("tables",logical)
        put("schema",JsonArray(db.query("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type,name")))
        put("projection",Json.parseToJsonElement(projection.bytes()))
    }
}

internal fun validateInstalledCourse(db:Connection,seed:JsonObject) {
    val course=seed.obj("course").str("id")
    val existing=db.query("SELECT kp_id,title,module_id,ord FROM outline_kp WHERE course_id=? AND retired=0 ORDER BY ord,kp_id",course)
    val expected=seed.array("knowledge_points").map { it.jsonObject }
    require(existing.map { it.str("kp_id") }==expected.map { it.str("id") } && existing.zip(expected).all { (a,b) ->
        a.str("title")==b.str("name") && a.str("module_id")==b.str("module_id")
    }) { "Installed course KP identities/order/titles/modules differ from the seed manifest" }
    val weights=db.query("SELECT payload FROM events WHERE course_id=? AND type='exam_weights_set' ORDER BY seq DESC LIMIT 1",course)
    require(weights.size==1 && parseText(weights.single().str("payload")).jsonObject.obj("weights")==seed.obj("exam_weights")) {
        "Installed exam_weights_set differs from seed manifest; never reseed during history import"
    }
}

private fun sourceTimestamp(value:JsonElement?):Long? {
    val p=value as? JsonPrimitive ?: return null
    return p.doubleOrNull?.takeIf { it.isFinite() && it>0 && it<1e11 }?.let { (it*1000).toLong() }
        ?: runCatching { Instant.parse(p.content).toEpochMilli().takeIf { it>0 } }.getOrNull()
}
private fun timestamp(payload:JsonObject,fallback:Long):Long = sourceTimestamp(payload["created_at"]) ?: fallback

/** No import wall clock participates in historical facts, including untimed archive records. */
private fun historicalTime(row:JsonObject,plan:JsonObject,mapping:JsonObject):Long {
    val history=plan.obj("history_time")
    val cutoff=history["snapshot_at"]?.jsonPrimitive?.long ?: error("Missing deterministic snapshot timestamp")
    val p=row.obj("payload");val kind=row.str("kind")
    val kp=p.str("kp_id")
    val schedule=if(kind=="legacy_passed") plan.array("records").map { it.jsonObject }
        .firstOrNull { it.str("kind")=="legacy_schedule" && it.obj("payload").str("kp_id")==kp }?.obj("payload") else p
    val evidence=mapping.array("knowledge_points").map { it.jsonObject }.firstOrNull { it.str("id")==kp }?.obj("anchors")
    val candidates=when(kind) {
        "legacy_passed","legacy_schedule" -> listOf(schedule?.obj("original_state")?.get("last_review_at"),
            evidence?.get("created_at"),history["mastery_updated_at"])
        else -> listOf(p["created_at"],p["updated_at"])
    }
    val dayTime=if(kind=="legacy_journal") LocalDate.parse(p.str("day")).atTime(12,0)
        .atZone(ZoneId.of("Europe/London")).toInstant().toEpochMilli() else null
    return (listOfNotNull(dayTime)+candidates.mapNotNull(::sourceTimestamp)).firstOrNull { it<=cutoff } ?: cutoff
}

/** Transactional source revisions. The CLI can only write newly created rehearsal copies. */
internal fun writeHistory(db:Connection,plan:JsonObject,seed:JsonObject,mapping:JsonObject,clock:()->Long=System::currentTimeMillis):JsonObject {
    require(db.autoCommit) { "Writer owns its transaction" }
    val course=plan.str("course_id");require(course==seed.obj("course").str("id"))
    validateInstalledCourse(db,seed)
    val nativeReview=project(db.query("SELECT event_json FROM events WHERE course_id=? AND device_id!='deeptutor-migration' ORDER BY seq",course)
        .map { LedgerJson.decodeFromString<Event>(it.str("event_json")) }).review.keys
    val rawById=mutableMapOf<String,JsonObject>()
    fun collect(v:JsonElement) {
        when(v) {
            is JsonObject -> { if(v.containsKey("source_record") && v.str("id").startsWith("legacy:")) rawById[v.str("id")]=v
                v.values.forEach(::collect) }
            is JsonArray -> v.forEach(::collect)
            else -> Unit
        }
    }
    collect(mapping)
    // Never persist hidden model round metadata, even in the archive.
    val manifest=JsonObject(plan.filterKeys { it!="records" })
    val manifestHash=sha(canonical(manifest))
    fun batchId(manifestJson:String, records:List<JsonObject>) = sha(manifestJson+canonical(JsonArray(records.sortedBy { it.str("id") })))
    val identities=plan.array("records").map { v -> val r=v.jsonObject
        buildJsonObject { for(k in listOf("id","source_hash","payload_hash")) put(k,r.getValue(k)) } }
    val batchId=batchId(canonical(manifest),identities)
    val snapshotAt=plan.obj("history_time").getValue("snapshot_at").jsonPrimitive.long
    db.autoCommit=false
    try {
        db.exec("CREATE TABLE IF NOT EXISTS migration_records(source_id TEXT PRIMARY KEY,course_id TEXT NOT NULL,kind TEXT NOT NULL,source_hash TEXT NOT NULL,payload_hash TEXT NOT NULL,payload_json TEXT NOT NULL,source_json TEXT NOT NULL)")
        val oldColumns=db.query("PRAGMA table_info(migration_batches)").map { it.str("name") }
        if(oldColumns.isNotEmpty() && "batch_id" !in oldColumns) db.exec("ALTER TABLE migration_batches RENAME TO migration_batches_v1")
        db.exec("CREATE TABLE IF NOT EXISTS migration_batches(course_id TEXT NOT NULL,batch_id TEXT NOT NULL,manifest_hash TEXT NOT NULL,manifest_json TEXT NOT NULL,recorded_at INTEGER NOT NULL,snapshot_at INTEGER NOT NULL,PRIMARY KEY(course_id,batch_id))")
        if(oldColumns.isNotEmpty() && "batch_id" !in oldColumns) {
            for(old in db.query("SELECT * FROM migration_batches_v1")) {
                val ids=db.query("SELECT source_id AS id,source_hash,payload_hash FROM migration_records WHERE course_id=?",old.str("course_id"))
                val history=parseText(old.str("manifest_json")).jsonObject.obj("history_time")
                db.exec("INSERT INTO migration_batches VALUES(?,?,?,?,?,?)",old.str("course_id"),batchId(old.str("manifest_json"),ids),old.str("manifest_hash"),old.str("manifest_json"),old.getValue("recorded_at").jsonPrimitive.long,history.getValue("snapshot_at").jsonPrimitive.long)
            }
            db.exec("DROP TABLE migration_batches_v1")
        }
        val batch=db.query("SELECT * FROM migration_batches WHERE course_id=? AND batch_id=?",course,batchId).singleOrNull()
        val now=batch?.get("recorded_at")?.jsonPrimitive?.long ?: clock()
        require(now>=snapshotAt) { "Import clock precedes snapshot" }
        val latest=db.query("SELECT max(snapshot_at) AS n FROM migration_batches WHERE course_id=?",course).single()["n"]?.jsonPrimitive?.longOrNull
        require(latest==null || snapshotAt>=latest) { "Cannot apply a snapshot older than the latest imported batch" }
        val sourceIds=identities.map { it.str("id") }.toSet()
        require(sourceIds.size==identities.size && db.query("SELECT source_id FROM migration_records WHERE course_id=?",course).all { it.str("source_id") in sourceIds }) { "Source deletion requires an explicit reconciliation; no silent history removal" }
        if(batch==null) db.exec("INSERT INTO migration_batches VALUES(?,?,?,?,?,?)",course,batchId,manifestHash,canonical(manifest),now,snapshotAt)
        var inserted=0;var revised=0;var addedEvents=0
        var revisionFacts:MutableList<JsonObject>?=null
        fun emit(row:JsonObject,type:String,payload:JsonObject,suffix:String="",at:Long=historicalTime(row,plan,mapping)):Long {
            val id="migration:"+sha(course+":"+row.str("id")+":"+type+":"+suffix)
            val event=Event(0,id,course,type,recordedAt=now,occurredAt=at,deviceId="deeptutor-migration",actor="SYSTEM",
                threadId=if(type in setOf("legacy_attempt","answer_submitted","attempt_voided")) "legacy-thread:"+sha(row.str("id")) else null,
                payload=JsonObject(payload+mapOf("sourceId" to JsonPrimitive(row.str("id")))))
            if(revisionFacts!=null) {
                revisionFacts!!.add(buildJsonObject { put("type",type);put("occurredAt",at);put("payload",event.payload) })
                return db.query("SELECT min(seq) AS n FROM events WHERE course_id=? AND device_id='deeptutor-migration' AND json_extract(payload,'$.sourceId')=?",course,row.str("id")).single().getValue("n").jsonPrimitive.long
            }
            val old=db.query("SELECT seq,event_json FROM events WHERE course_id=? AND idem_key=?",course,id).singleOrNull()
            if(old!=null) {
                val previous=LedgerJson.decodeFromString<Event>(old.str("event_json"))
                require(previous.copy(seq=0)==event) { "Conflicting migration event" }
                return old.getValue("seq").jsonPrimitive.long
            }
            val seq=db.query("SELECT coalesce(max(seq),0)+1 AS n FROM events").single().getValue("n").jsonPrimitive.long
            val actual=event.copy(seq=seq)
            db.exec("INSERT INTO events(seq,id,course_id,type,schema,recorded_at,occurred_at,device_id,actor,command_id,effect_index,idem_key,payload_sha256,thread_id,payload,event_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                seq,id,course,type,1,now,actual.occurredAt,actual.deviceId,actual.actor,null,0,id,sha(canonical(actual.payload)),actual.threadId,canonical(actual.payload),LedgerJson.encodeToString(actual))
            addedEvents++;return seq
        }
        // The projector only accepts a legacy schedule after its legacy_passed fact.
        val ordered=plan.array("records").map { it.jsonObject }.sortedWith(compareBy<JsonObject> {
            when(it.str("kind")) { "legacy_passed" -> 0;"legacy_schedule" -> 2;else -> 1 }
        }.thenBy { if(it.str("kind")=="legacy_message") timestamp(it.obj("payload"),0L) else 0L }.thenBy { it.str("id") })
        for(row in ordered) {
            val p=row.obj("payload");val id=row.str("id");val kind=row.str("kind")
            require(row.str("payload_hash")==sha(canonical(p))) { "Corrupt planned payload" }
            val raw=if(kind=="legacy_message") p else rawById[id]?.get("source_record") ?: error("Missing original source: $id")
            require(row.str("source_hash")==sourceContentHash(id,raw)) { "Corrupt source content hash: $id" }
            val old=db.query("SELECT * FROM migration_records WHERE source_id=?",id).singleOrNull()
            if(old!=null) {
                require(old.str("course_id")==course && old.str("kind")==kind) { "Source identity changed course or kind: $id" }
                if(old.str("source_hash")==row.str("source_hash")) {
                    require(old.str("payload_hash")==row.str("payload_hash") && old.str("payload_json")==canonical(p) && old.str("source_json")==canonical(raw)) { "Unchanged source has conflicting derived data: $id" }
                    continue
                }
                revisionFacts=mutableListOf()
                revised++
            } else {
                revisionFacts=null
                inserted++
            }
            when(kind) {
                "legacy_question" -> {
                    val q=p.obj("question");val stem=q.str("question").ifBlank { q.str("stem") }.ifBlank { q.str("prompt") }
                    val seq=emit(row,"legacy_imported",p)
                    if(old!=null) {
                        require(db.query("SELECT question_id FROM questions WHERE question_id=? AND course_id=? AND origin='LEGACY' AND key_state='UNREVIEWED'",id,course).size==1 &&
                            listOf("question_kp","answer_keys","pool_details","constructed_details").all { table -> db.query("SELECT question_id FROM $table WHERE question_id=?",id).isEmpty() }) { "Cannot revise a promoted legacy question" }
                        db.exec("UPDATE questions SET format=?,stem_md=?,options=? WHERE question_id=?",if(isChoice(q)) "SINGLE_CHOICE" else "SHORT_ANSWER",stem,q["options"]?.let(::canonical),id)
                    } else db.exec("INSERT INTO questions(question_id,course_id,format,stem_md,options,origin,fingerprint,key_state,created_seq) VALUES(?,?,?,?,?,'LEGACY',?,'UNREVIEWED',?)",
                        id,course,if(isChoice(q)) "SINGLE_CHOICE" else "SHORT_ANSWER",stem,q["options"]?.let(::canonical),"legacy:"+sha(id),seq)
                    // Deliberately no question_kp, answer_keys or pool_details.
                }
                "legacy_attempt" -> {
                    val attempt="legacy-attempt:"+sha(id)
                    emit(row,"legacy_attempt",JsonObject(p+mapOf(
                        "attemptId" to JsonPrimitive(attempt),"questionId" to JsonPrimitive("legacy-attempt-question:"+sha(id)),
                        "fingerprint" to JsonPrimitive("legacy-attempt-fingerprint:"+sha(id)),"kpIds" to JsonArray(emptyList()),
                        "posedAt" to JsonPrimitive(historicalTime(row,plan,mapping)),
                        "keyState" to JsonPrimitive("UNREVIEWED"),"purpose" to JsonPrimitive("HISTORY"))))
                    emit(row,"answer_submitted",buildJsonObject { put("attemptId",attempt);put("answer",p["answer"] ?: JsonNull);put("historical",true) })
                    if(p["reset"]==JsonPrimitive(true)) emit(row,"attempt_voided",buildJsonObject { put("attemptId",attempt);put("reason",p.str("reset_reason")) })
                }
                "legacy_passed" -> emit(row,"legacy_passed",JsonObject(p+mapOf("kpId" to JsonPrimitive(p.str("kp_id")))))
                "legacy_schedule" -> {
                    if(p["queue_eligible"]==JsonPrimitive(true) && p.str("kp_id") !in nativeReview) {
                        val source=p.obj("original_state")
                        val due=p.getValue("dueAt").jsonPrimitive.double
                        require(due.isFinite() && due>0 && due<1e11) { "Invalid historical review timestamp" }
                        val dueDay=logicalDay((due*1000).toLong(),"Europe/London")
                        val last=(source["last_review_at"] as? JsonPrimitive)?.doubleOrNull
                            ?.takeIf { it.isFinite() && it>0 && it<1e11 }?.let { logicalDay((it*1000).toLong(),"Europe/London") }
                        emit(row,"legacy_schedule",JsonObject(p+mapOf(
                            "kpId" to JsonPrimitive(p.str("kp_id")),"dueDay" to JsonPrimitive(dueDay),
                            "interval" to JsonPrimitive(last?.let { (dueDay-it).coerceAtLeast(1) } ?: 1),"ease" to JsonPrimitive(2.3),
                            "lapses" to (source["lapse_count"] ?: JsonPrimitive(0)),
                            "intervalMapping" to JsonPrimitive("elapsed logical days from last_review_at to dueAt, minimum one; new scheduler ease 2.3; raw state retained"))))
                    } else emit(row,"legacy_imported",JsonObject(p+mapOf("reviewNotAppliedReason" to
                        JsonPrimitive(if(p.str("kp_id") in nativeReview) "existing Coach review takes precedence" else "not historically passed"))))
                }
                "notes" -> emit(row,"note_saved",p)
                "legacy_journal" -> emit(row,"legacy_journal_imported",buildJsonObject {
                    put("day",LocalDate.parse(p.str("day")).toEpochDay());put("text",canonical(p.obj("entry")))
                })
                "legacy_message" -> {
                    val thread="legacy-chat:"+sha(course+":"+p.str("session_id"))
                    db.exec("INSERT OR IGNORE INTO threads(thread_id,course_id,kind,anchor_type,anchor_id) VALUES(?,?,'LEGACY','LEGACY_SESSION',?)",thread,course,thread)
                    if(old!=null) {
                        val previous=db.query("SELECT m.thread_id,t.kind,t.course_id FROM messages m JOIN threads t USING(thread_id) WHERE message_id=?",id).single()
                        require(previous.str("kind")=="LEGACY" && previous.str("course_id")==course && previous.str("thread_id")==thread) { "Moving messages between sessions requires explicit reconciliation" }
                        db.exec("UPDATE messages SET role=?,content_md=?,created_at=? WHERE message_id=?",p.str("role"),p.str("content"),historicalTime(row,plan,mapping),id)
                    } else {
                        val next=db.query("SELECT coalesce(max(mseq),0)+1 AS n FROM messages WHERE thread_id=?",thread).single().getValue("n").jsonPrimitive.long
                        db.exec("INSERT INTO messages(thread_id,mseq,message_id,role,status,content_md,created_at,delta_index) VALUES(?,?,?,?,'complete',?,?,0)",
                            thread,next,id,p.str("role"),p.str("content"),historicalTime(row,plan,mapping))
                    }
                    emit(row,"legacy_imported",buildJsonObject { put("messageId",id);put("threadId",thread) })
                }
                else -> emit(row,"legacy_imported",buildJsonObject { put("kind",kind);put("archive",p) })
            }
            val facts=revisionFacts
            revisionFacts=null
            if(old!=null) emit(row,"legacy_source_revised",buildJsonObject {
                put("kind",kind);put("old_content_hash",old.str("source_hash"));put("new_content_hash",row.str("source_hash"))
                put("old_payload_hash",old.str("payload_hash"));put("new_payload_hash",row.str("payload_hash"))
                put("snapshot_at",snapshotAt);put("old_source",parseText(old.str("source_json")));put("new_source",raw)
                put("facts",JsonArray(requireNotNull(facts)))
            },suffix=batchId)
            db.exec("INSERT INTO migration_records VALUES(?,?,?,?,?,?,?) ON CONFLICT(source_id) DO UPDATE SET source_hash=excluded.source_hash,payload_hash=excluded.payload_hash,payload_json=excluded.payload_json,source_json=excluded.source_json",
                id,course,kind,row.str("source_hash"),row.str("payload_hash"),canonical(p),canonical(raw))
        }
        // Stable chronological ordering inside migration-owned threads, including late arrivals.
        if(inserted+revised>0) {
            for(thread in db.query("SELECT DISTINCT m.thread_id FROM messages m JOIN migration_records r ON r.source_id=m.message_id WHERE r.course_id=? AND r.kind='legacy_message'",course)) {
                val messages=db.query("SELECT message_id FROM messages WHERE thread_id=? ORDER BY created_at,message_id",thread.str("thread_id"))
                require(messages.all { it.str("message_id") in sourceIds }) { "Legacy thread contains a native message; cannot reorder" }
                messages.forEachIndexed { index,m -> db.exec("UPDATE messages SET mseq=? WHERE message_id=?",-index-1,m.str("message_id")) }
                messages.forEachIndexed { index,m -> db.exec("UPDATE messages SET mseq=? WHERE message_id=?",index+1,m.str("message_id")) }
            }
            db.exec("DELETE FROM projection_cache WHERE course_id=?",course)
        }
        // Fail inside the transaction if corrected history cannot be replayed safely.
        databaseState(db,course)
        require(db.query("PRAGMA foreign_key_check").isEmpty()) { "Foreign key violation" }
        db.commit()
        return buildJsonObject { put("records_inserted",inserted);put("records_revised",revised);put("events_added",addedEvents);put("records_unchanged",ordered.size-inserted-revised) }
    } catch(e:Throwable) { db.rollback();throw e } finally { db.autoCommit=true }
}

/** No production writer switch. Every invocation starts from a read-only online backup. */
fun rehearseCommand(args:List<String>) {
    val allowed=setOf("--snapshot","--coach-db","--seed-manifest","--proposal","--out")
    require(args.size%2==0) { "rehearse requires --snapshot DIR --coach-db FILE --seed-manifest FILE --proposal FILE --out NEW_DIR" }
    val options=args.chunked(2).associate { (k,v) -> require(k in allowed) { "Unknown rehearsal option: $k" };k to v }
    require(options.size*2==args.size && options.keys==allowed) { "Supply each rehearsal option exactly once" }
    val home=Path.of(System.getProperty("user.home"))
    val source=Path.of(options.getValue("--coach-db")).toRealPath()
    val snap=Path.of(options.getValue("--snapshot")).toRealPath()
    val out=resolvedDestination(Path.of(options.getValue("--out")))
    val protected=listOf(resolvedDestination(home.resolve("DeepTutor")))
    require(protected.none { source.startsWith(it) }) { "Cannot open the DeepTutor database" }
    requirePrivateDestination(snap,protected);requirePrivateDestination(out,protected)
    require(!out.exists() && !out.startsWith(snap) && !snap.startsWith(out) && !out.startsWith(source.parent)) {
        "Rehearsal output must be a new private directory outside snapshot and live Coach data directory"
    }
    val mapped=mapSnapshot(snap)
    val seed=parseFile(Path.of(options.getValue("--seed-manifest"))).jsonObject
    require(seed.obj("exam_weights")==prepareSeed(snap).manifest.obj("exam_weights"))
    val proposal=parseFile(Path.of(options.getValue("--proposal"))).jsonObject
    val plan=importPlan(mapped.mapping,seed,proposal)
    privateDir(out)
    mapped.let { out.resolve("mapping.json").writeText(canonical(it.mapping));out.resolve("report.md").writeText(it.report) }
    out.resolve("import-plan.json").writeText(canonical(plan))
    out.resolve("confirmation.md").writeText(confirmationSummary(mapped.mapping,proposal))
    out.resolve("confirmation-details.md").writeText(confirmationSheet(mapped.mapping,proposal))
    val baseline=out.resolve("before.sqlite");backupDatabase(source,baseline)
    val target=out.resolve("coach.sqlite");backupDatabase(baseline,target)
    val course=plan.str("course_id")
    openReadOnly(baseline).use { db -> validateInstalledCourse(db,seed);out.resolve("before-state.json").writeText(canonical(databaseState(db,course))) }
    DriverManager.getConnection("jdbc:sqlite:$target").use { db ->
        db.createStatement().use { it.execute("PRAGMA foreign_keys=ON");it.execute("PRAGMA busy_timeout=5000") }
        val first=writeHistory(db,plan,seed,mapped.mapping)
        val state=databaseState(db,course);out.resolve("first-state.json").writeText(canonical(state))
        val second=writeHistory(db,plan,seed,mapped.mapping)
        val repeated=databaseState(db,course);out.resolve("second-state.json").writeText(canonical(repeated))
        check(state==repeated && second["records_inserted"]==JsonPrimitive(0) && second["events_added"]==JsonPrimitive(0)) { "Database import is not idempotent" }
        val report=buildJsonObject {
            put("first",first);put("second",second);put("state_sha256",sha(canonical(state)));put("projection_sha256",sha(canonical(state.getValue("projection"))))
            put("baseline_sha256",sha(out.resolve("before-state.json").readText()))
            put("event_counts",JsonArray(db.query("SELECT type,count(*) AS count FROM events GROUP BY type ORDER BY type")))
            put("migration_kinds",JsonArray(db.query("SELECT kind,count(*) AS count FROM migration_records GROUP BY kind ORDER BY kind")))
            put("integrity_check",JsonArray(db.query("PRAGMA integrity_check")));put("production_written",false);put("activated",false)
        }
        out.resolve("rehearsal.json").writeText(canonical(report));println(canonical(report))
    }
    // An independently restorable rollback copy, never replace the live database.
    backupDatabase(baseline,out.resolve("rollback.sqlite"))
    openReadOnly(out.resolve("rollback.sqlite")).use { db ->
        check(canonical(databaseState(db,course))==out.resolve("before-state.json").readText()) { "Rollback copy differs from baseline" }
    }
}
