package com.coach.store

import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.nio.file.*
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private val idRandom = java.security.SecureRandom()
private fun uid() = ulid(System.currentTimeMillis(),ByteArray(10).also(idRandom::nextBytes))
data class Fence(val epoch: String, val value: Long, val id: String)

class LedgerStore(val directory: Path, private val clock: () -> Long = System::currentTimeMillis) : AutoCloseable {
    private val dbFile = directory.resolve("coach.sqlite")
    private val connection: Connection
    init {
        Files.createDirectories(directory)
        Files.createDirectories(directory.resolve("blobs"))
        connection = DriverManager.getConnection("jdbc:sqlite:$dbFile")
        sql("PRAGMA journal_mode=WAL"); sql("PRAGMA foreign_keys=ON"); sql("PRAGMA busy_timeout=5000")
        val version = query("PRAGMA user_version") { it.getInt(1) }.single()
        if (version < 1) {
            // Even the empty pre-migration DB gets an independently restorable SQLite file.
            rawBackup(directory.resolve("backups/pre-V001-${uid()}"))
            transaction {
                javaClass.getResourceAsStream("/migrations/V001__ledger.sql")!!.bufferedReader().use { it.readText() }
                    .split(';').map(String::trim).filter(String::isNotEmpty).forEach { sql(it) }
                sql("INSERT INTO ledger_meta(singleton,epoch) VALUES(1,?)",uid())
                sql("PRAGMA user_version=1")
            }
        }
        require(version <= 1) { "Database schema is newer than this server" }
    }
    private fun sql(statement: String, vararg values: Any?) = connection.prepareStatement(statement).use { ps ->
        values.forEachIndexed { i,v -> ps.setObject(i+1,v) }; ps.execute(); ps.updateCount.coerceAtLeast(0)
    }
    private fun <T> query(statement: String, vararg values: Any?, read: (java.sql.ResultSet) -> T): List<T> = connection.prepareStatement(statement).use { ps ->
        values.forEachIndexed { i,v -> ps.setObject(i+1,v) }; ps.executeQuery().use { rs -> buildList { while(rs.next()) add(read(rs)) } }
    }
    private fun <T> transaction(block: () -> T): T {
        check(connection.autoCommit) { "Nested write transaction" }
        connection.autoCommit = false
        try { val v = block(); connection.commit(); return v } catch(e: Throwable) { connection.rollback(); throw e } finally { connection.autoCommit = true }
    }
    fun hello(): Hello = query("SELECT epoch,backed_up_through_seq,last_seq_before FROM ledger_meta") {
        Hello(it.getString(1),lastSeq(),it.getLong(2),it.getLong(3),serverTime=clock())
    }.single()
    private fun lastSeq(): Long = query("SELECT coalesce(max(seq),0) FROM events") { it.getLong(1) }.single()
    fun events(course: String, after: Long = 0, limit: Int = Int.MAX_VALUE): List<Event> = query("SELECT event_json FROM events WHERE course_id=? AND seq>? ORDER BY seq LIMIT ?",course,after,limit) { LedgerJson.decodeFromString<Event>(it.getString(1)) }
    fun courses(): List<String> = query("SELECT DISTINCT course_id FROM events WHERE course_id<>'__system__' ORDER BY course_id") { it.getString(1) }
    fun snapshot(course: String): Snapshot {
        val events = events(course)
        return Snapshot(hello(),course,events,projection(course,events))
    }
    private fun projection(course: String, supplied: List<Event>? = null): Projection {
        val seq=query("SELECT coalesce(max(seq),0) FROM events WHERE course_id=?",course) { it.getLong(1) }.single()
        val cached=query("SELECT blob FROM projection_cache WHERE name='all' AND course_id=? AND version=? AND last_seq=?",course,PROJECTION_VERSION,seq) { it.getBytes(1) }.singleOrNull()
        if(cached!=null) return LedgerJson.decodeFromString<Projection>(cached.toString(Charsets.UTF_8))
        val state=project(supplied ?: events(course))
        sql("INSERT INTO projection_cache(name,course_id,version,last_seq,blob) VALUES('all',?,?,?,?) ON CONFLICT(name,course_id) DO UPDATE SET version=excluded.version,last_seq=excluded.last_seq,blob=excluded.blob",course,PROJECTION_VERSION,seq,LedgerJson.encodeToString(state).toByteArray(Charsets.UTF_8))
        return state
    }
    fun page(course: String, after: Long, limit: Int): EventsPage {
        val n = limit.coerceIn(1,500)
        val es = events(course,after,n+1)
        return EventsPage(hello().epoch,es.take(n),if(es.size>n) es[n-1].seq else lastSeq(),es.size>n)
    }
    /** Internal fact append. Public clients can only use validated commands. */
    fun append(course: String, type: String, payload: JsonObject, idem: String = uid(),
        device: String = "server", actor: String = "SYSTEM", command: WireCommand? = null,
        effect: Int = 0, occurredAt: Long = clock(), thread: String? = null): Event {
        if(connection.autoCommit) return transaction { append(course,type,payload,idem,device,actor,command,effect,occurredAt,thread) }
        val at = clock()
        val seq = lastSeq() + 1
        val e = Event(seq,uid(),course,type,recordedAt=at,occurredAt=occurredAt.coerceAtMost(at),
            deviceId=device,actor=actor,commandId=command?.commandId,effectIndex=effect,threadId=thread,idemKey=idem,payload=payload)
        sql("INSERT INTO events(seq,id,course_id,type,schema,recorded_at,occurred_at,device_id,actor,command_id,effect_index,idem_key,payload_sha256,thread_id,payload,event_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            e.seq,e.id,e.courseId,e.type,e.schema,e.recordedAt,e.occurredAt,e.deviceId,e.actor,e.commandId,e.effectIndex,e.idemKey,command?.payloadSha256,e.threadId,payload.toString(),LedgerJson.encodeToString(e))
        if(type=="question_key_changed") sql("UPDATE questions SET key_state=? WHERE question_id=? AND course_id=?",payload.str("keyState"),payload.str("questionId"),course)
        sql("DELETE FROM projection_cache WHERE course_id=?",course)
        return e
    }
    fun registerQuestion(id: String, course: String, fingerprint: String, format: String, stem: String,
        keyState: String, correctChoice: String? = null) = transaction {
        sql("INSERT INTO questions(question_id,course_id,format,stem_md,origin,fingerprint,key_state) VALUES(?,?,?,?,?,?,?)",id,course,format,stem,"COACH",fingerprint,keyState)
        if (correctChoice != null) sql("INSERT INTO answer_keys(question_id,key_json) VALUES(?,?)",id,json("choice" to correctChoice).toString())
    }
    fun authorize(device: String, token: String): Boolean = query("SELECT token_sha256 FROM devices WHERE device_id=? AND revoked_at IS NULL",device) { it.getString(1) }.singleOrNull() == sha256(token.toByteArray(Charsets.UTF_8))
    fun enroll(device: String, token: String) { require(token.length >= 32); sql("INSERT INTO devices(device_id,token_sha256,created_at) VALUES(?,?,?) ON CONFLICT(device_id) DO UPDATE SET token_sha256=excluded.token_sha256,revoked_at=NULL",device,sha256(token.toByteArray(Charsets.UTF_8)),clock()) }

    fun command(c: WireCommand): Receipt {
        val meta = hello()
        fun receipt(status: String, seqs: List<Long> = emptyList(), result: JsonObject = json(), error: String? = null) = Receipt(c.commandId,status,seqs,result,error,meta.epoch,meta.backedUpThroughSeq)
        if (sha256(c.payloadBytes.toByteArray(Charsets.UTF_8)) != c.payloadSha256) return receipt("REJECTED",error="PAYLOAD_MISMATCH")
        val old = query("SELECT payload_sha256,result FROM command_log WHERE device_id=? AND command_id=?",c.deviceId,c.commandId) { it.getString(1) to it.getString(2) }.singleOrNull()
        if (old != null) {
            if (old.first != c.payloadSha256) return receipt("REJECTED",error="PAYLOAD_MISMATCH")
            val r = LedgerJson.decodeFromString<Receipt>(old.second)
            return r.copy(status=if(r.status == "OK") "DUPLICATE" else r.status,epoch=meta.epoch,backedUpThroughSeq=meta.backedUpThroughSeq)
        }
        val body = try { LedgerJson.decodeFromString<CommandBody>(c.payloadBytes) } catch(e: Exception) { return receipt("REJECTED",error="INVALID_COMMAND") }
        if (body.courseId.isBlank() || body.courseId == "__system__") return receipt("REJECTED",error="INVALID_COURSE")
        return transaction {
            val effects = mutableListOf<Long>()
            fun emit(type: String, payload: JsonObject, idem: String? = null) {
                val actor=if(type in setOf("answer_submitted","kp_opened","hint_revealed","followup_asked","review_snoozed","kp_overridden","override_cleared","exam_set","note_saved","journal_edited")) "LEARNER" else "SYSTEM"
                effects.add(append(body.courseId,type,payload,idem ?: "cmd:${c.deviceId}:${c.commandId}:${effects.size}",c.deviceId,actor,c,effects.size,body.clientTime,body.payload.str("threadId").ifBlank { null }).seq)
            }
            val p = body.payload
            var result = json()
            var status = "OK"
            var error: String? = null
            when(body.type) {
                "submit_answer" -> {
                    val posedSnapshot = p["posedSnapshot"] as? JsonObject
                    val attemptId = p.str("attemptId")
                    if (attemptId.isBlank() || posedSnapshot == null || posedSnapshot.str("attemptId") != attemptId || posedSnapshot.strings("kpIds").isEmpty() || posedSnapshot.str("questionId").isBlank() || posedSnapshot.str("fingerprint").isBlank() || posedSnapshot.str("purpose").isBlank() || posedSnapshot.num("policyVersion")<1) {
                        status="REJECTED"; error="INVALID_POSED_SNAPSHOT"
                    } else {
                        val existing = query("SELECT event_json FROM events WHERE course_id=? AND idem_key=?",body.courseId,"attempt:$attemptId:answer") { LedgerJson.decodeFromString<Event>(it.getString(1)) }.singleOrNull()
                        if (existing != null) {
                            status = "CONFLICT"
                            val grades = events(body.courseId).filter { it.payload.str("attemptId") == attemptId && it.type in setOf("attempt_graded","grade_reviewed") }
                            result = json("existingAnswer" to existing.payload,"grades" to JsonArray(grades.map { LedgerJson.encodeToJsonElement(it) }))
                            // The losing offline text is retained for the record view, never scored twice.
                            emit("answer_orphaned",json("attemptId" to attemptId,"posedSnapshot" to posedSnapshot,"rawAnswer" to p.str("answer"),"reason" to "CONCURRENT_ANSWER"))
                        } else {
                            val posed = query("SELECT event_json FROM events WHERE course_id=? AND idem_key=?",body.courseId,"attempt:$attemptId") { LedgerJson.decodeFromString<Event>(it.getString(1)) }.singleOrNull()
                            val qid = posed?.payload?.str("questionId") ?: posedSnapshot.str("questionId")
                            val question = query("SELECT format,fingerprint,key_state FROM questions WHERE question_id=? AND course_id=?",qid,body.courseId) { Triple(it.getString(1),it.getString(2),it.getString(3)) }.singleOrNull()
                            if (question == null) {
                                emit("answer_orphaned",json("attemptId" to attemptId,"posedSnapshot" to posedSnapshot,"rawAnswer" to p.str("answer"),"reason" to "QUESTION_MISSING"),"attempt:$attemptId:answer")
                            } else if (posed == null && question.second != posedSnapshot.str("fingerprint")) {
                                emit("answer_orphaned",json("attemptId" to attemptId,"posedSnapshot" to posedSnapshot,"rawAnswer" to p.str("answer"),"reason" to "QUESTION_CHANGED"),"attempt:$attemptId:answer")
                            } else {
                                if (posed == null) {
                                    // Key state and format come from authoritative storage, never the client snapshot.
                                    val snapshot = JsonObject(posedSnapshot + mapOf("format" to JsonPrimitive(question.first),"keyState" to JsonPrimitive(question.third)))
                                    emit("question_posed",snapshot,"attempt:$attemptId")
                                }
                                emit("answer_submitted",json("attemptId" to attemptId,"answer" to p.str("answer"),"offline" to p.flag("offline")),"attempt:$attemptId:answer")
                                if (question.first == "SINGLE_CHOICE") {
                                    val key = query("SELECT key_json FROM answer_keys WHERE question_id=?",qid) { LedgerJson.parseToJsonElement(it.getString(1)).jsonObject }.singleOrNull()
                                    if (key != null) emit("attempt_graded",json("attemptId" to attemptId,"score" to if(key.str("choice") == p.str("answer")) 1 else 0,"max" to 1,"status" to "EXACT"),"attempt:$attemptId:grade:exact")
                                }
                            }
                        }
                    }
                }
                "open_kp" -> emit("kp_opened",p)
                "reveal_hint" -> emit("hint_revealed",JsonObject(p + ("cause" to JsonPrimitive("BUTTON"))))
                "ask" -> emit("followup_asked",p)
                "snooze_review" -> emit("review_snoozed",p)
                "override_kp" -> emit("kp_overridden",p)
                "clear_override" -> emit("override_cleared",p)
                "set_exam" -> emit("exam_set",p)
                "save_note" -> emit("note_saved",p)
                "edit_journal" -> {
                    val revision = query("SELECT rev FROM journal_revisions WHERE course_id=? AND day=?",body.courseId,p.num("day")) { it.getLong(1) }.singleOrNull() ?: 0
                    if (revision != p.num("expectedRev")) { status="CONFLICT"; error="REV_MISMATCH"; result=json("currentRev" to revision) }
                    else {
                        val rev = revision+1
                        sql("INSERT INTO journal_revisions(course_id,day,rev,content_sha) VALUES(?,?,?,?) ON CONFLICT(course_id,day) DO UPDATE SET rev=excluded.rev,content_sha=excluded.content_sha",body.courseId,p.num("day"),rev,p.str("contentSha"))
                        emit("journal_edited",JsonObject(p + ("rev" to JsonPrimitive(rev))))
                    }
                }
                else -> { status="REJECTED"; error="UNSUPPORTED_COMMAND" }
            }
            if(effects.isNotEmpty()) projection(body.courseId)
            val r = receipt(status,effects,result,error)
            sql("INSERT INTO command_log(device_id,command_id,payload_sha256,payload_bytes,result,at) VALUES(?,?,?,?,?,?)",c.deviceId,c.commandId,c.payloadSha256,c.payloadBytes,LedgerJson.encodeToString(r),clock())
            r
        }
    }
    /** Immutable content-addressed blobs make hard-link backups safe. */
    fun putBlob(bytes: ByteArray, mime: String): String {
        val hash = sha256(bytes)
        val file = directory.resolve("blobs/$hash")
        if (!Files.exists(file)) Files.write(file,bytes,StandardOpenOption.CREATE_NEW)
        require(sha256(Files.readAllBytes(file)) == hash) { "Corrupt content-addressed blob" }
        sql("INSERT OR IGNORE INTO blobs(sha256,size,mime,path) VALUES(?,?,?,?)",hash,bytes.size,mime,hash)
        return hash
    }
    private fun rawBackup(target: Path) {
        Files.createDirectories(target)
        connection.createStatement().use { it.execute("VACUUM INTO '${target.resolve("coach.sqlite").toString().replace("'","''")}'") }
    }
    fun backup(target: Path = directory.resolve("backups/${clock()}-${uid()}")): Path {
        val seq = lastSeq()
        rawBackup(target)
        try {
            val blobDir = Files.createDirectories(target.resolve("blobs"))
            Files.list(directory.resolve("blobs")).use { files -> files.forEach { Files.createLink(blobDir.resolve(it.fileName),it) } }
            Files.newBufferedWriter(target.resolve("events.jsonl"),Charsets.UTF_8).use { out ->
                query("SELECT event_json FROM events ORDER BY seq") { it.getString(1) }.forEach { out.write(it);out.newLine() }
                query("SELECT thread_id,mseq,message_id,role,model,status,content_md,reason,mode,citations,created_at FROM messages ORDER BY thread_id,mseq") { rs -> json("recordType" to "message","threadId" to rs.getString(1),"mseq" to rs.getLong(2),"messageId" to rs.getString(3),"role" to rs.getString(4),"model" to rs.getString(5),"status" to rs.getString(6),"contentMd" to rs.getString(7),"reason" to rs.getString(8),"mode" to rs.getString(9),"citations" to rs.getString(10),"createdAt" to rs.getLong(11)).toString() }.forEach { out.write(it);out.newLine() }
            }
            // Publish the watermark only after DB + blobs + export all succeeded.
            DriverManager.getConnection("jdbc:sqlite:${target.resolve("coach.sqlite")}").use { backup -> backup.prepareStatement("UPDATE ledger_meta SET backed_up_through_seq=?,last_backup_at=?").use { ps -> ps.setLong(1,seq);ps.setLong(2,clock());ps.execute(); ps.updateCount.coerceAtLeast(0) } }
            Files.writeString(target.resolve("complete"),seq.toString(),StandardOpenOption.CREATE_NEW)
            sql("UPDATE ledger_meta SET backed_up_through_seq=?,last_backup_at=?",seq,clock())
            return target
        } catch(e: Throwable) { target.toFile().deleteRecursively(); throw e }
    }
    fun backupIfDue(): Path? {
        val at = query("SELECT last_backup_at FROM ledger_meta") { it.getLong(1) }.single()
        val now=java.time.Instant.ofEpochMilli(clock()).atZone(java.time.ZoneId.systemDefault())
        val last=java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
        val scheduled=now.toLocalTime() >= java.time.LocalTime.of(3,30) && last.toLocalDate() < now.toLocalDate()
        return if(clock()-at >= 86400000 || scheduled) backup().also { retainBackups() } else null
    }
    private fun retainBackups() {
        val root=directory.resolve("backups")
        if(!Files.isDirectory(root)) return
        val complete=Files.list(root).use { files -> files.filter { Files.isRegularFile(it.resolve("complete")) }.toList() }
            .sortedByDescending { Files.getLastModifiedTime(it.resolve("complete")).toMillis() }
        val today=java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay()
        val days=mutableSetOf<Long>();val weeks=mutableSetOf<Long>()
        for(path in complete) {
            val day=java.time.Instant.ofEpochMilli(Files.getLastModifiedTime(path.resolve("complete")).toMillis()).atZone(java.time.ZoneOffset.UTC).toLocalDate().toEpochDay()
            val keep=if(today-day<14) days.add(day) else today-day<70 && weeks.add(Math.floorDiv(day+3,7))
            if(!keep) path.toFile().deleteRecursively()
        }
    }
    fun enqueueJob(kind: String, subject: String, questionVersion: String? = null, answerHash: String? = null): String {
        val id = uid(); sql("INSERT INTO jobs(job_id,kind,subject,state,created_at,updated_at,question_version,answer_hash) VALUES(?,?,?,'queued',?,?,?,?)",id,kind,subject,clock(),clock(),questionVersion,answerHash);return id
    }
    fun claimJob(id: String): Fence? = transaction {
        if(sql("UPDATE jobs SET state='running',fence=fence+1,attempts=attempts+1,updated_at=? WHERE job_id=? AND state='queued'",clock(),id) == 0) null
        else Fence(hello().epoch,query("SELECT fence FROM jobs WHERE job_id=?",id) { it.getLong(1) }.single(),id)
    }
    fun completeJob(token: Fence, questionVersion: String?, answerHash: String?): Boolean {
        if (token.epoch != hello().epoch) return false
        return sql("UPDATE jobs SET state='completed',updated_at=? WHERE job_id=? AND fence=? AND state='running' AND question_version IS ? AND answer_hash IS ?",clock(),token.id,token.value,questionVersion,answerHash) == 1
    }
    fun commitJobResult(token: Fence, questionVersion: String?, answerHash: String?, course: String,
        type: String, payload: JsonObject): Boolean = transaction {
        if(!completeJob(token,questionVersion,answerHash)) return@transaction false
        append(course,type,payload,"job:${token.id}:${token.epoch}:${token.value}")
        true
    }
    fun acquireLease(thread: String, device: String, ttlMillis: Long = 120000): Fence? = transaction {
        val active = query("SELECT device_id,fence FROM turn_leases WHERE thread_id=? AND expires_at>?",thread,clock()) { it.getString(1) to it.getLong(2) }.singleOrNull()
        if (active != null) return@transaction if (active.first == device) Fence(hello().epoch,active.second,thread) else null
        sql("INSERT INTO lease_fences(thread_id,fence) VALUES(?,1) ON CONFLICT(thread_id) DO UPDATE SET fence=fence+1",thread)
        val fence = query("SELECT fence FROM lease_fences WHERE thread_id=?",thread) { it.getLong(1) }.single()
        sql("INSERT INTO turn_leases(thread_id,turn_id,device_id,fence,expires_at) VALUES(?,?,?,?,?) ON CONFLICT(thread_id) DO UPDATE SET turn_id=excluded.turn_id,device_id=excluded.device_id,fence=excluded.fence,expires_at=excluded.expires_at",thread,uid(),device,fence,clock()+ttlMillis)
        Fence(hello().epoch,fence,thread)
    }
    fun renewLease(token: Fence, device: String): Boolean = token.epoch == hello().epoch && sql("UPDATE turn_leases SET expires_at=? WHERE thread_id=? AND device_id=? AND fence=? AND expires_at>?",clock()+120000,token.id,device,token.value,clock()) == 1
    fun recoverSuspension() = transaction {
        sql("UPDATE messages SET status='truncated',reason='suspended' WHERE status='streaming'")
        sql("UPDATE jobs SET state='queued',fence=fence+1,updated_at=? WHERE state='running'",clock())
        sql("DELETE FROM turn_leases")
    }
    fun startMessage(thread: String, message: String, content: String = "") { sql("INSERT INTO messages(thread_id,mseq,message_id,status,content_md,created_at) VALUES(?,coalesce((SELECT max(mseq)+1 FROM messages WHERE thread_id=?),1),?,'streaming',?,?)",thread,thread,message,content,clock()) }
    fun messageStatus(id: String): Pair<String,String?>? = query("SELECT status,reason FROM messages WHERE message_id=?",id) { it.getString(1) to it.getString(2) }.singleOrNull()
    fun jobState(id: String): Pair<String,Long>? = query("SELECT state,fence FROM jobs WHERE job_id=?",id) { it.getString(1) to it.getLong(2) }.singleOrNull()
    private fun markRestored(from: String) = transaction {
        val last = lastSeq(); val epoch = uid()
        sql("UPDATE ledger_meta SET epoch=?,last_seq_before=?",epoch,last)
        val jobs = query("SELECT job_id,kind,subject,fence,question_version,answer_hash FROM jobs WHERE state IN ('queued','running')") { rs -> (1..6).map { rs.getString(it) } }
        sql("UPDATE jobs SET state='cancelled',last_error='server_restored',updated_at=? WHERE state IN ('queued','running')",clock())
        jobs.forEach { row -> enqueueJob(row[1],row[2],row[4],row[5]) }
        sql("DELETE FROM turn_leases")
        sql("UPDATE messages SET status='truncated',reason='server_restored' WHERE status='streaming'")
        append("__system__","server_restored",json("epoch" to epoch,"fromBackup" to from,"lastSeqBefore" to last))
    }
    override fun close() { connection.close() }
    companion object {
        /** Offline operation: caller must stop the server first. Existing data is moved to a safety copy. */
        fun restore(directory: Path, backup: Path): LedgerStore {
            require(Files.isRegularFile(backup.resolve("complete"))) { "Incomplete backup" }
            require(!backup.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize().resolve("blobs")))
            DriverManager.getConnection("jdbc:sqlite:${backup.resolve("coach.sqlite")}").use { db -> db.createStatement().use { s -> s.executeQuery("PRAGMA integrity_check").use { rs -> require(rs.next() && rs.getString(1)=="ok") } } }
            Files.createDirectories(directory)
            val safety = Files.createDirectories(directory.resolve("before-restore-${uid()}"))
            for (name in listOf("coach.sqlite","coach.sqlite-wal","coach.sqlite-shm","blobs")) {
                val source = directory.resolve(name)
                if (Files.exists(source)) Files.move(source,safety.resolve(name))
            }
            Files.copy(backup.resolve("coach.sqlite"),directory.resolve("coach.sqlite"))
            val blobs = Files.createDirectories(directory.resolve("blobs"))
            Files.list(backup.resolve("blobs")).use { files -> files.forEach { Files.createLink(blobs.resolve(it.fileName),it) } }
            return LedgerStore(directory).also { it.markRestored(backup.fileName.toString()) }
        }
    }
}
