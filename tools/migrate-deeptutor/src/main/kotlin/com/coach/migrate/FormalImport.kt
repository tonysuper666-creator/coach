package com.coach.migrate

import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.*
import kotlinx.serialization.json.*

internal fun fileHash(path:Path):String {
    val digest=MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer=ByteArray(65536)
        while(true) { val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Never open LedgerStore here: opening it can run migrations before the backup exists. */
internal fun <T> offlineData(data:Path,block:()->T):T {
    require(data.resolve("coach.sqlite").isRegularFile(LinkOption.NOFOLLOW_LINKS)) { "Expected an existing real Coach database" }
    require(!data.resolve("server.lock").isSymbolicLink()) { "server.lock cannot be a symbolic link" }
    return FileChannel.open(data.resolve("server.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE).use { channel ->
        val lock=channel.tryLock() ?: error("Coach server is running; stop local.coach.server first")
        lock.use { block() }
    }
}

internal fun blobInventory(data:Path):JsonObject {
    val dir=data.resolve("blobs")
    require(dir.isDirectory() && !dir.isSymbolicLink()) { "Expected a real blobs directory" }
    return buildJsonObject {
        Files.list(dir).use { paths -> paths.sorted().forEach { file ->
            require(file.isRegularFile(LinkOption.NOFOLLOW_LINKS)) { "Blobs must be regular files" }
            put(file.fileName.toString(),fileHash(file))
        } }
    }
}

internal fun copyBlobs(source:Path,target:Path) {
    privateDir(target.resolve("blobs"))
    Files.list(source.resolve("blobs")).use { paths -> paths.forEach { file ->
        require(file.isRegularFile(LinkOption.NOFOLLOW_LINKS))
        Files.copy(file,target.resolve("blobs").resolve(file.fileName))
    } }
}

private fun integrity(db:Connection) {
    check(db.query("PRAGMA integrity_check").map { it.values.single().jsonPrimitive.content }==listOf("ok")) { "Database integrity_check failed" }
    check(db.query("PRAGMA foreign_key_check").isEmpty()) { "Database foreign_key_check failed" }
}

private fun validateBlobs(db:Connection,data:Path) {
    for(row in db.query("SELECT sha256,size,path FROM blobs")) {
        val name=row.str("path");require(Path.of(name).fileName.toString()==name && name !in setOf(".",".."))
        val file=data.resolve("blobs").resolve(name)
        require(file.isRegularFile(LinkOption.NOFOLLOW_LINKS) && Files.size(file)==row.getValue("size").jsonPrimitive.long && fileHash(file)==row.str("sha256")) {
            "Referenced blob is missing or corrupt"
        }
    }
}

/** Exact rows, including audit clocks, protect rollback from overwriting subsequent activity. */
private fun exactState(db:Connection,course:String):String = canonical(buildJsonObject {
    put("state",databaseState(db,course))
    put("events",JsonArray(db.query("SELECT * FROM events ORDER BY seq")))
    if(db.query("SELECT name FROM sqlite_master WHERE name='migration_batches'").isNotEmpty())
        put("batches",JsonArray(db.query("SELECT * FROM migration_batches ORDER BY rowid")))
    put("user_version",JsonArray(db.query("PRAGMA user_version")))
    put("application_id",JsonArray(db.query("PRAGMA application_id")))
    if(db.query("SELECT name FROM sqlite_master WHERE name='sqlite_sequence'").isNotEmpty())
        put("sequences",JsonArray(db.query("SELECT * FROM sqlite_sequence ORDER BY name")))
})

/** Same predicates as LedgerStore.poolAvailable/constructedSupply, at a frozen reservation clock. */
internal fun scoringSupply(db:Connection,course:String,clock:Long):JsonObject = buildJsonObject {
    for(k in db.query("SELECT kp_id FROM outline_kp WHERE course_id=? ORDER BY kp_id",course)) {
        val kp=k.str("kp_id")
        put(kp,buildJsonObject {
            put("poolAvailable",db.query("""SELECT count(*) AS n FROM questions q JOIN question_kp k USING(question_id)
                WHERE q.course_id=? AND k.kp_id=? AND q.format='SINGLE_CHOICE' AND q.key_state='VERIFIED'
                AND NOT EXISTS (SELECT 1 FROM offline_reservations r WHERE r.question_id=q.question_id AND r.expires_at>?)
                AND NOT EXISTS (SELECT 1 FROM events e WHERE e.course_id=q.course_id AND e.type='question_posed'
                    AND json_extract(e.payload,'$.questionId')=q.question_id)""",course,kp,clock).single().getValue("n"))
            put("constructedSupply",db.query("""SELECT count(*) AS n FROM questions q JOIN constructed_details d USING(question_id)
                JOIN question_kp k USING(question_id) WHERE q.course_id=? AND k.kp_id=? AND q.key_state='VERIFIED'""",course,kp).single().getValue("n"))
        })
    }
}

internal fun scoringIsolation(db:Connection,plan:JsonObject):JsonObject {
    val questions=plan.array("records").map { it.jsonObject }.filter { it.str("kind")=="legacy_question" }
    var edges=0;var associated=0
    for(row in questions) {
        val id=row.str("id");val expected=row.obj("payload").array("kp_ids").map { it.jsonPrimitive.content }.toSet()
        check(db.query("SELECT key_state FROM questions WHERE question_id=?",id).single().str("key_state")=="UNREVIEWED")
        for(table in listOf("answer_keys","pool_details","constructed_details"))
            check(db.query("SELECT question_id FROM $table WHERE question_id=?",id).isEmpty()) { "Migrated question has grading details" }
        val actual=db.query("SELECT kp_id FROM question_kp WHERE question_id=?",id).map { it.str("kp_id") }.toSet()
        check(actual==expected) { "Migrated associations differ from confirmed input" }
        check(db.query("""SELECT k.kp_id FROM question_kp k LEFT JOIN outline_kp o ON o.kp_id=k.kp_id AND o.course_id=? AND o.retired=0
            WHERE k.question_id=? AND o.kp_id IS NULL""",plan.str("course_id"),id).isEmpty())
        edges+=actual.size;if(actual.isNotEmpty()) associated++
    }
    return buildJsonObject {
        put("questions",questions.size);put("unreviewed",questions.size);put("grading_details",0)
        put("association_edges",edges);put("associated_questions",associated);put("unassociated_questions",questions.size-associated)
    }
}

private fun freshDirectory(dir:Path) {
    Files.createDirectories(dir.parent)
    Files.createDirectory(dir) // CREATE_NEW semantics; an existing backup is never replaced.
    privateDir(dir)
}

internal fun applyConfirmed(data:Path,backup:Path,mapping:JsonObject,seed:JsonObject,plan:JsonObject,confirmationHash:String,recoveryCommand:String?=null):JsonObject = offlineData(data) {
    require(plan["human_confirmation"] is JsonObject) { "Formal import requires an explicit validated confirmation" }
    val course=plan.str("course_id");val now=System.currentTimeMillis()
    freshDirectory(backup)
    val source=data.resolve("coach.sqlite");val saved=backup.resolve("coach.sqlite")
    backupDatabase(source,saved);copyBlobs(data,backup)
    val blobs=blobInventory(backup)
    check(blobs==blobInventory(data)) { "Blobs changed during backup" }
    val before=openReadOnly(source).use { db -> integrity(db);exactState(db,course) }
    val beforeState=openReadOnly(saved).use { db -> integrity(db);validateBlobs(db,backup);check(exactState(db,course)==before);canonical(databaseState(db,course)) }
    backup.resolve("before-state.json").writeText(beforeState)
    val manifest=buildJsonObject {
        put("course_id",course);put("database_sha256",fileHash(saved));put("blobs",blobs)
        put("before_exact_sha256",sha(before));put("before_state_sha256",sha(beforeState));put("confirmation_sha256",confirmationHash)
    }
    backup.resolve("backup.json").writeText(canonical(manifest))
    // Exercise a real restore before touching the target, then preflight the exact writer on it.
    val restored=backup.resolve("restored");freshDirectory(restored)
    Files.copy(saved,restored.resolve("coach.sqlite"));copyBlobs(backup,restored)
    check(fileHash(saved)==fileHash(restored.resolve("coach.sqlite")) && blobInventory(restored)==blobs)
    val supply=openReadOnly(restored.resolve("coach.sqlite")).use { db ->
        integrity(db);validateBlobs(db,restored);check(exactState(db,course)==before);scoringSupply(db,course,now)
    }
    fun run(target:Path):JsonObject = DriverManager.getConnection("jdbc:sqlite:$target").use { db ->
        db.createStatement().use { it.execute("PRAGMA foreign_keys=ON");it.execute("PRAGMA busy_timeout=5000") }
        val first=writeHistory(db,plan,seed,mapping,clock={now})
        val state=databaseState(db,course);val second=writeHistory(db,plan,seed,mapping,clock={now})
        check(listOf("records_inserted","records_revised","events_added").all { second[it]==JsonPrimitive(0) })
        check(databaseState(db,course)==state)
        check(scoringSupply(db,course,now)==supply) { "Scorable supply changed" }
        integrity(db)
        buildJsonObject {
            put("first",first);put("second",second);put("isolation",scoringIsolation(db,plan))
            put("state_sha256",sha(canonical(state)));put("projection_sha256",sha(canonical(state.getValue("projection"))))
            put("after_exact_sha256",sha(exactState(db,course)));put("supply",supply)
        }
    }
    val preflight=run(restored.resolve("coach.sqlite"))
    // A frozen audit clock makes preflight and target exact matches. Persist the recovery guard before commit.
    val receipt=buildJsonObject {
        put("backup",manifest);put("expected_after_state_sha256",preflight.getValue("state_sha256"))
        put("expected_after_projection_sha256",preflight.getValue("projection_sha256"))
        put("expected_after_exact_sha256",preflight.getValue("after_exact_sha256"))
    }
    backup.resolve("receipt.json").writeText(canonical(receipt))
    if(recoveryCommand!=null) backup.resolve("rollback.sh").writeText("#!/usr/bin/env bash\nset -euo pipefail\n$recoveryCommand\n")
    check(openReadOnly(source).use { sha(exactState(it,course)) }==manifest.str("before_exact_sha256")) { "Target changed during preflight" }
    val result=run(source)
    check(result["state_sha256"]==preflight["state_sha256"] && result["after_exact_sha256"]==preflight["after_exact_sha256"])
    openReadOnly(source).use { db -> backup.resolve("after-state.json").writeText(canonical(databaseState(db,course))) }
    backup.resolve("result.json").writeText(canonical(JsonObject(result+mapOf(
        "backup_restore_verified" to JsonPrimitive(true),"before_state_sha256" to JsonPrimitive(sha(beforeState)),
        "backup_database_sha256" to manifest.getValue("database_sha256"),"blob_count" to JsonPrimitive(blobs.size),
        "confirmation_sha256" to JsonPrimitive(confirmationHash),"supply_clock" to JsonPrimitive(now)))))
    parseFile(backup.resolve("result.json")).jsonObject
}

/** Restores only this import's target state. Any later event, row or blob change prevents rollback. */
internal fun rollbackConfirmed(data:Path,backup:Path):JsonObject = offlineData(data) {
    val receipt=parseFile(backup.resolve("receipt.json")).jsonObject
    val manifest=parseFile(backup.resolve("backup.json")).jsonObject
    require(receipt.obj("backup")==manifest) { "Backup manifest changed" }
    val course=manifest.str("course_id");val saved=backup.resolve("coach.sqlite");val source=data.resolve("coach.sqlite")
    require(fileHash(saved)==manifest.str("database_sha256") && blobInventory(backup)==manifest.obj("blobs")) { "Backup damaged" }
    openReadOnly(saved).use { db -> integrity(db);require(sha(exactState(db,course))==manifest.str("before_exact_sha256")) }
    require(blobInventory(data)==manifest.obj("blobs")) { "Target blobs changed; refusing destructive rollback" }
    openReadOnly(source).use { db ->
        integrity(db)
        val exact=sha(exactState(db,course))
        if(exact!=manifest.str("before_exact_sha256")) {
            require(exact==receipt.str("expected_after_exact_sha256")) { "Target changed after import; refusing to discard subsequent activity" }
        }
    }
    // There are no other Coach connections while holding server.lock. Checkpoint/close before replacing main.
    DriverManager.getConnection("jdbc:sqlite:$source").use { db ->
        val checkpoint=db.query("PRAGMA wal_checkpoint(TRUNCATE)").single()
        require(checkpoint.values.first().jsonPrimitive.int==0) { "Database still has a writer or reader" }
    }
    val staged=data.resolve(".ton1095-rollback.sqlite")
    require(!staged.exists()) { "Staged rollback already exists" }
    Files.copy(saved,staged)
    Files.deleteIfExists(data.resolve("coach.sqlite-wal"));Files.deleteIfExists(data.resolve("coach.sqlite-shm"))
    Files.move(staged,source,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    check(fileHash(source)==manifest.str("database_sha256"))
    openReadOnly(source).use { db -> integrity(db);check(sha(exactState(db,course))==manifest.str("before_exact_sha256")) }
    buildJsonObject {
        put("database_byte_identical",true);put("state_byte_identical",true);put("blobs_byte_identical",true)
        put("database_sha256",manifest.getValue("database_sha256"));put("state_sha256",manifest.getValue("before_state_sha256"))
    }
}

fun formalImportCommand(command:String,args:List<String>) {
    val required=if(command=="apply-confirmed") setOf("--database","--backup","--snapshot","--seed-manifest","--proposal","--confirmation","--confirmation-sha256")
        else setOf("--database","--backup")
    require(args.size%2==0 && args.chunked(2).all { it[0] in required }) { "Unknown option or missing value" }
    val options=args.chunked(2).associate { it[0] to it[1] }
    require(options.keys==required && options.size*2==args.size) { "Supply each required option exactly once" }
    val data=Path.of(options.getValue("--database")).toRealPath()
    val backup=resolvedDestination(Path.of(options.getValue("--backup")))
    val protected=listOf(resolvedDestination(Path.of(System.getProperty("user.home")).resolve("DeepTutor")))
    for(path in listOf(data,backup)) requirePrivateDestination(path,protected)
    require(!backup.startsWith(data) && !data.startsWith(backup)) { "Backup and target must be separate directories" }
    if(command=="rollback-confirmed") { println(canonical(rollbackConfirmed(data,backup)));return }
    val inputPaths=listOf("--snapshot","--seed-manifest","--proposal","--confirmation").map { key -> Path.of(options.getValue(key)).toRealPath() }
    for(path in inputPaths) {
        requirePrivateDestination(path,protected)
        require(!path.startsWith(data) && !path.startsWith(backup) && !data.startsWith(path) && !backup.startsWith(path)) { "Inputs, backup and target must be separate" }
    }
    val (snapshot,seedFile,proposalFile,confirmationFile)=inputPaths
    val confirmationHash=fileHash(confirmationFile)
    require(confirmationHash==options.getValue("--confirmation-sha256")) { "Confirmation file SHA differs from reviewed decision" }
    val mapping=mapSnapshot(snapshot).mapping;val seed=parseFile(seedFile).jsonObject
    require(seed.obj("exam_weights")==prepareSeed(snapshot).manifest.obj("exam_weights"))
    val plan=importPlan(mapping,seed,parseFile(proposalFile).jsonObject,parseFile(confirmationFile).jsonObject)
    val launcher=System.getenv("COACH_MIGRATE_LAUNCHER") ?: error("Set COACH_MIGRATE_LAUNCHER to the installed migrate-deeptutor executable before writing")
    require(Path.of(launcher).isAbsolute && Files.isExecutable(Path.of(launcher))) { "Launcher must be an absolute executable path" }
    fun quote(v:String)="'"+v.replace("'","'\\''")+"'"
    val rollback="${quote(launcher)} rollback-confirmed --database ${quote(data.toString())} --backup ${quote(backup.toString())}"
    val report=applyConfirmed(data,backup,mapping,seed,plan,confirmationHash,rollback)
    println(canonical(report));println("Rollback: $rollback")
}
