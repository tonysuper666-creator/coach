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
import kotlin.random.Random

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private val idRandom = java.security.SecureRandom()
private fun uid() = ulid(System.currentTimeMillis(),ByteArray(10).also(idRandom::nextBytes))
data class Fence(val epoch: String, val value: Long, val id: String)
data class PairedDevice(val id: String, val name: String, val createdAt: Long, val revokedAt: Long?)
// Deliberately no generated toString: credentials must never reach logs.
class DeviceCredential(val deviceId: String, val token: String)
class PairingCode(val code: String, val expiresAt: Long)

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
        require(version <= 4) { "Database schema is newer than this server" }
        if (version < 2) {
            rawBackup(directory.resolve("backups/pre-V002-${uid()}"))
            transaction {
                // Historical events stay byte-for-byte intact. New questions and keys use option IDs.
                val legacy = query("SELECT question_id,stem_md,options FROM questions WHERE format='SINGLE_CHOICE'") {
                    Triple(it.getString(1), it.getString(2), it.getString(3))
                }
                for ((id, stem, raw) in legacy) {
                    val old = raw?.let { LedgerJson.parseToJsonElement(it) } as? JsonObject
                    if (old == null || old.size !in 2..26) {
                        sql("UPDATE questions SET key_state='UNREVIEWED' WHERE question_id=?", id)
                        sql("DELETE FROM answer_keys WHERE question_id=?", id)
                        continue
                    }
                    val options = old.entries.map { Option(uid(), it.value.jsonPrimitive.content) }
                    val key = query("SELECT key_json FROM answer_keys WHERE question_id=?", id) {
                        LedgerJson.parseToJsonElement(it.getString(1)).jsonObject.str("choice")
                    }.singleOrNull()
                    val correct = old.keys.indexOf(key).let { options.getOrNull(it)?.optionId }
                    sql("UPDATE questions SET options=?,fingerprint=? WHERE question_id=?",
                        LedgerJson.encodeToString(options), questionFingerprint(stem, options), id)
                    if (correct == null) {
                        sql("DELETE FROM answer_keys WHERE question_id=?", id)
                        sql("UPDATE questions SET key_state='UNREVIEWED' WHERE question_id=?", id)
                    } else sql("UPDATE answer_keys SET key_json=? WHERE question_id=?", json("correctOptionId" to correct).toString(), id)
                }
                sql("PRAGMA user_version=2")
            }
        }
        if (version < 3) {
            rawBackup(directory.resolve("backups/pre-V003-${uid()}"))
            transaction {
                // 断线续传按 (msg, i) 去重：i 必须随正文一起落库，重连才能接着往下发。
                // 老库可能已经被手工改回过 user_version（回档演练），列存在就不再 ALTER。
                val columns = query("PRAGMA table_info(messages)") { it.getString(2) }
                if ("delta_index" !in columns) sql("ALTER TABLE messages ADD COLUMN delta_index INTEGER NOT NULL DEFAULT 0")
                sql("PRAGMA user_version=3")
            }
        }
        if (version < 4) {
            transaction {
                sql("CREATE TABLE IF NOT EXISTS pairing_code(singleton INTEGER PRIMARY KEY CHECK(singleton=1), code_sha256 TEXT NOT NULL, expires_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0)")
                sql("PRAGMA user_version=4")
            }
        }
    }
    private fun sql(statement: String, vararg values: Any?) = connection.prepareStatement(statement).use { ps ->
        values.forEachIndexed { i,v -> ps.setObject(i+1,v) }; ps.execute(); ps.updateCount.coerceAtLeast(0)
    }
    private fun <T> query(statement: String, vararg values: Any?, read: (java.sql.ResultSet) -> T): List<T> = connection.prepareStatement(statement).use { ps ->
        values.forEachIndexed { i,v -> ps.setObject(i+1,v) }; ps.executeQuery().use { rs -> buildList { while(rs.next()) add(read(rs)) } }
    }
    private fun <T> transaction(block: () -> T): T {
        if (!connection.autoCommit) return block() // Nested operations join the actor-owned transaction.
        connection.autoCommit = false
        try { val v = block(); connection.commit(); return v } catch(e: Throwable) { connection.rollback(); throw e } finally { connection.autoCommit = true }
    }
    /** Core orchestrations commit state, cards and per-question answers as one unit. */
    fun <T> tutorTransaction(block: LedgerStore.() -> T): T = transaction { block() }

    /** A KP has exactly one course-bound thread; reopening cannot move it to another course or KP. */
    fun bindTutorThread(course: String, thread: String, kp: String) {
        require(course.isNotBlank() && course != "__system__" && thread.isNotBlank() && kp.isNotBlank())
        val row = query("SELECT course_id,kind,anchor_id FROM threads WHERE thread_id=?", thread) {
            Triple(it.getString(1), it.getString(2), it.getString(3))
        }.singleOrNull()
        if (row == null) sql("INSERT INTO threads(thread_id,course_id,kind,anchor_type,anchor_id) VALUES(?,?,'KP','KP',?)", thread,course,kp)
        else require(row == Triple(course,"KP",kp)) { "Thread binding mismatch" }
    }

    fun todayProgress(course: String, thread: String? = null): TodayProgress {
        val state = projection(course)
        val quick = state.quickAsks.values.firstOrNull { it.threadId == thread }
        val kp = thread?.let { query("SELECT anchor_id FROM threads WHERE thread_id=? AND course_id=? AND kind='KP'",it,course) { row -> row.getString(1) }.singleOrNull() }
        return state.today(clock(), if(quick != null) quick.kpId else kp ?: state.position.current)
    }

    fun requireChatThread(course: String, thread: String) {
        val bound = query("SELECT course_id FROM threads WHERE thread_id=?",thread) { it.getString(1) }.singleOrNull()
        require(bound == null || bound == course) { "Thread belongs to another course" }
    }

    fun journalDay(course: String, day: Long): JournalDay = projection(course).journal[day] ?: JournalDay()

    fun isQuickThread(course: String, thread: String): Boolean = query(
        "SELECT 1 FROM threads WHERE thread_id=? AND course_id=? AND kind='QUICK'", thread, course) { true }.isNotEmpty()

    /** The anchor is course + logical day; opening it never changes Position or a tutor turn. */
    fun openQuickAsk(course: String): QuickAskThread = transaction {
        require(course.isNotBlank() && course != "__system__")
        val state = projection(course)
        val day = logicalDay(clock(), state.timeZone)
        state.quickAsks[day]?.let { return@transaction it }
        val thread = uid()
        sql("INSERT INTO threads(thread_id,course_id,kind,anchor_type,anchor_id) VALUES(?,?,'QUICK','DAY',?)", thread,course,"$course:$day")
        val value = QuickAskThread(thread, course, day, state.position.current)
        append(course,"quick_ask_opened",json("day" to day,"kpId" to value.kpId),"quick:$course:$day",thread=thread)
        value
    }

    fun quickHistory(course: String, day: Long): JsonObject {
        val thread = projection(course).quickAsks[day] ?: return json()
        val messages = query("SELECT message_id,role,status,content_md FROM messages WHERE thread_id=? ORDER BY mseq",thread.threadId) {
            json("messageId" to it.getString(1),"role" to it.getString(2),"status" to it.getString(3),"text" to it.getString(4))
        }
        return json("thread" to LedgerJson.encodeToJsonElement(thread),"messages" to JsonArray(messages))
    }

    /** Only migration code reading an immutable copy may call this; no client import command. */
    fun importLegacyJournal(course: String, day: Long, sourceId: String, text: String) {
        require(sourceId.isNotBlank())
        if (events(course).none { it.type == "legacy_journal_imported" && it.payload.str("sourceId") == sourceId }) {
            append(course,"legacy_journal_imported",json("day" to day,"sourceId" to sourceId,"text" to text),"legacy-journal:$sourceId")
        }
    }

    fun quickQuestion(course: String, thread: String, text: String): String = transaction {
        require(isQuickThread(course,thread)) { "Not a quick-ask thread" }
        val id = uid()
        beginStreamingMessage(thread,id,"",role="user")
        finishMessage(id,"complete",null,text,0)
        append(course,"quick_ask_message",json("messageId" to id),thread=thread,actor="LEARNER")
        id
    }

    private fun fileQuickAsk(course: String, payload: JsonObject): JsonObject {
        val source = projection(course).quickAsks.values.singleOrNull { it.threadId == payload.str("threadId") }
            ?: throw IllegalArgumentException("Not a quick-ask thread")
        val message = messageSnapshot(payload.str("messageId")) ?: throw IllegalArgumentException("Missing message")
        require(message.threadId == source.threadId && message.status in setOf("complete","truncated")) { "Message not ready" }
        val action = payload.str("action")
        require(action in setOf("NOTE","MOVE")) { "Only NOTE and MOVE are supported" }
        var target: String? = null
        if (action == "MOVE") {
            val kp = requireNotNull(source.kpId) { "Quick ask has no anchored KP" }
            target = query("SELECT thread_id FROM threads WHERE course_id=? AND kind='KP' AND anchor_id=?",course,kp) { it.getString(1) }.singleOrNull()
                ?: uid().also { bindTutorThread(course,it,kp) }
            val copy = uid()
            val metadata = query("SELECT role,model FROM messages WHERE message_id=?",payload.str("messageId")) { it.getString(1) to it.getString(2) }.single()
            beginStreamingMessage(target,copy,metadata.second.orEmpty(),role=metadata.first)
            finishMessage(copy,message.status,null,message.content,0)
        }
        return json("sourceMessageId" to payload.str("messageId"),"kpId" to source.kpId,
            "content" to message.content,"action" to action,"targetThreadId" to target)
    }

    fun ensureTutorPoolJob(course: String, kp: String): String {
        val subject = "$course:$kp"
        return query("SELECT job_id FROM jobs WHERE kind='gen_mcq' AND subject=? AND state IN ('queued','running') ORDER BY created_at LIMIT 1", subject) { it.getString(1) }
            .singleOrNull() ?: enqueueJob("gen_mcq",subject)
    }

    fun tutorQuestions(course: String, kp: String): List<String> = query(
        "SELECT q.question_id FROM questions q JOIN question_kp k ON q.question_id=k.question_id WHERE q.course_id=? AND k.kp_id=? AND q.key_state='VERIFIED' AND q.format='SINGLE_CHOICE' ORDER BY q.question_id", course,kp
    ) { it.getString(1) }.filter { choiceOptions(it).size == 4 }

    /** Server-only answer key, never included in a card or model prompt. */
    fun tutorChoiceKey(questionId: String): ChoiceKey = ChoiceKey(choiceOptions(questionId), requireNotNull(choiceId(questionId)))

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
        if (type == "question_posed" && payload.str("format") == "SINGLE_CHOICE") {
            require(payload.str("groupId").isNotBlank() && validLetterMap(choiceOptions(payload.str("questionId")), letterMap(payload))) { "Invalid choice letter map" }
        }
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
        keyState: String, correctOptionId: String? = null, options: List<Option> = emptyList(),
        kpIds: List<String> = emptyList()) = transaction {
        if (format == "SINGLE_CHOICE") ChoiceKey(options, requireNotNull(correctOptionId))
        val fp = if (format == "SINGLE_CHOICE") questionFingerprint(stem, options) else fingerprint
        sql("INSERT INTO questions(question_id,course_id,format,stem_md,options,origin,fingerprint,key_state) VALUES(?,?,?,?,?,?,?,?)",
            id,course,format,stem,LedgerJson.encodeToString(options),"COACH",fp,keyState)
        if (correctOptionId != null) sql("INSERT INTO answer_keys(question_id,key_json) VALUES(?,?)",id,json("correctOptionId" to correctOptionId).toString())
        for (kp in kpIds) sql("INSERT INTO question_kp(question_id,kp_id,is_primary) VALUES(?,?,1)", id, kp)
    }

    private fun choiceOptions(id: String): List<Option> = query("SELECT options FROM questions WHERE question_id=?", id) {
        LedgerJson.decodeFromString<List<Option>>(it.getString(1) ?: "[]")
    }.singleOrNull().orEmpty()
    private fun choiceId(id: String): String? = query("SELECT key_json FROM answer_keys WHERE question_id=?",id) {
        LedgerJson.parseToJsonElement(it.getString(1)).jsonObject.str("correctOptionId").ifBlank { null }
    }.singleOrNull()
    private fun letterMap(payload: JsonObject): Map<String,String> {
        val raw = payload["letterMap"] as? JsonObject ?: return emptyMap()
        if (raw.values.any { it !is JsonPrimitive || !it.isString }) return emptyMap()
        return raw.mapValues { it.value.jsonPrimitive.content }
    }

    /** Called inside the single-writer actor; all letters and all group events commit together. */
    fun poseChoiceGroup(course: String, groupId: String, requests: List<JsonObject>,
        rng: Random = Random.Default, offline: Boolean = false, device: String = "server", thread: String? = null): List<Event> = transaction {
        poseChoiceGroupInTransaction(course, groupId, requests, rng, device, offline, thread)
    }
    private fun poseChoiceGroupInTransaction(course: String, groupId: String, requests: List<JsonObject>,
        rng: Random, device: String, offline: Boolean = true, thread: String? = null): List<Event> {
        require(groupId.isNotBlank() && requests.isNotEmpty())
        require(requests.map { it.str("attemptId") }.distinct().size == requests.size)
        val existing = events(course).filter { it.type == "question_posed" && it.payload.str("groupId") == groupId }
        if (existing.isNotEmpty()) {
            require(existing.map { it.payload.str("attemptId") to it.payload.str("questionId") } ==
                requests.map { it.str("attemptId") to it.str("questionId") }) { "Group identity mismatch" }
            return existing
        }
        val keys = requests.map { request ->
            require(request.str("attemptId").isNotBlank() && request.strings("kpIds").isNotEmpty())
            require(request.str("purpose") in Purpose.entries.map { it.name } && request.num("policyVersion") > 0)
            val valid = query("SELECT key_state FROM questions WHERE question_id=? AND course_id=? AND format='SINGLE_CHOICE'",request.str("questionId"),course) { it.getString(1) }.singleOrNull()
            require(valid == "VERIFIED") { "Only verified choices can be posed" }
            ChoiceKey(choiceOptions(request.str("questionId")), requireNotNull(choiceId(request.str("questionId"))))
        }
        val previous = events(course).lastOrNull { it.type == "question_posed" && it.payload.str("format") == "SINGLE_CHOICE" }
            ?.payload?.let { letterMap(it)[choiceId(it.str("questionId"))] }
        val maps = assignLetters(keys, previous, rng)
        return requests.mapIndexed { index, request ->
            val qid = request.str("questionId")
            val content = query("SELECT stem_md,fingerprint FROM questions WHERE question_id=?",qid) { it.getString(1) to it.getString(2) }.single()
            val payload = JsonObject(request + mapOf(
                "groupId" to JsonPrimitive(groupId), "letterMap" to JsonObject(maps[index].mapValues { JsonPrimitive(it.value) }),
                "stem" to JsonPrimitive(content.first), "options" to LedgerJson.encodeToJsonElement(keys[index].options),
                "fingerprint" to JsonPrimitive(content.second), "format" to JsonPrimitive("SINGLE_CHOICE"),
                "keyState" to JsonPrimitive("VERIFIED"), "offlinePack" to JsonPrimitive(offline)
            ) - "correctOptionId")
            append(course,"question_posed",payload,"attempt:${request.str("attemptId")}",device=device, thread=thread)
        }
    }

    /** Offline selection supplies the same requests as online selection; default groups have three questions. */
    fun offlineChoicePack(course: String, packId: String, requests: List<JsonObject>, device: String,
        rng: Random = Random.Default): OfflineChoicePack = transaction {
        require(requests.isNotEmpty())
        val groups = requests.chunked(3).mapIndexed { index, group ->
            // Inline implementation shares this transaction with all groups.
            poseChoiceGroupInTransaction(course, "$packId:$index", group, rng, device)
        }
        OfflineChoicePack(groups, groups.flatten().map { OfflineKey(it.payload.str("attemptId"), requireNotNull(choiceId(it.payload.str("questionId")))) })
    }

    fun authorize(device: String, token: String): Boolean {
        if (device.length > 128 || token.length !in 32..256) return false
        val expected = query("SELECT token_sha256 FROM devices WHERE device_id=? AND revoked_at IS NULL",device) { it.getString(1) }.singleOrNull()
            ?: "0".repeat(64)
        return MessageDigest.isEqual(expected.toByteArray(), sha256(token.toByteArray(Charsets.UTF_8)).toByteArray())
    }
    fun enroll(device: String, token: String) { require(token.length >= 32); sql("INSERT INTO devices(device_id,token_sha256,created_at) VALUES(?,?,?) ON CONFLICT(device_id) DO UPDATE SET token_sha256=excluded.token_sha256,revoked_at=NULL",device,sha256(token.toByteArray(Charsets.UTF_8)),clock()) }

    fun createPairingCode(): PairingCode {
        val code = idRandom.nextInt(1_000_000).toString().padStart(6, '0')
        val expires = clock() + 300_000
        sql("INSERT INTO pairing_code(singleton,code_sha256,expires_at,attempts) VALUES(1,?,?,0) ON CONFLICT(singleton) DO UPDATE SET code_sha256=excluded.code_sha256,expires_at=excluded.expires_at,attempts=0", sha256(code.toByteArray()), expires)
        return PairingCode(code, expires)
    }

    /** Wall clock expiry, five total guesses, single use across both listeners/restarts. */
    fun pair(code: String, name: String): DeviceCredential? = transaction {
        val active = query("SELECT code_sha256,expires_at,attempts FROM pairing_code WHERE singleton=1") {
            Triple(it.getString(1), it.getLong(2), it.getInt(3))
        }.singleOrNull() ?: return@transaction null
        if (clock() >= active.second || active.third >= 5) return@transaction null
        sql("UPDATE pairing_code SET attempts=attempts+1 WHERE singleton=1")
        if (!code.matches(Regex("[0-9]{6}")) || name.isBlank() || name.length > 80 ||
            !MessageDigest.isEqual(active.first.toByteArray(), sha256(code.toByteArray()).toByteArray())) return@transaction null
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(idRandom::nextBytes))
        val device = uid()
        sql("INSERT INTO devices(device_id,name,token_sha256,created_at) VALUES(?,?,?,?)", device, name, sha256(token.toByteArray()), clock())
        sql("DELETE FROM pairing_code WHERE singleton=1")
        DeviceCredential(device, token)
    }

    fun pairedDevices(): List<PairedDevice> = query("SELECT device_id,name,created_at,revoked_at FROM devices ORDER BY created_at,device_id") {
        PairedDevice(it.getString(1), it.getString(2) ?: it.getString(1), it.getLong(3), it.getObject(4)?.let { value -> (value as Number).toLong() })
    }
    fun revokeDevice(device: String) { sql("UPDATE devices SET revoked_at=? WHERE device_id=? AND revoked_at IS NULL", clock(), device) }

    fun command(c: WireCommand, tutorGroup: Boolean = false): Receipt {
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
            val quickReadOnly = isQuickThread(body.courseId,p.str("threadId")) && body.type !in setOf("file_quick_ask","save_note")
            when {
                quickReadOnly -> { status="REJECTED"; error="QUICK_READ_ONLY" }
                else -> when(body.type) {
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
                            val tutorThread = posed?.threadId
                            val tutorState = tutorThread?.let { thread -> events(body.courseId).lastOrNull { it.threadId == thread && it.type == "tutor_state_changed" } }
                            val activeReply = tutorState?.payload?.str("turnId").orEmpty().isNotEmpty()
                            val tutorGroupSize = if (tutorState == null) 0 else events(body.courseId).count {
                                it.type == "question_posed" && it.payload.str("groupId") == posed?.payload?.str("groupId")
                            }
                            if (activeReply) {
                                status="REJECTED"; error="TUTOR_BUSY"
                            } else if (!tutorGroup && tutorGroupSize > 1) {
                                status="REJECTED"; error="GROUP_SUBMISSION_REQUIRED"
                            } else if (question == null) {
                                emit("answer_orphaned",json("attemptId" to attemptId,"posedSnapshot" to posedSnapshot,"rawAnswer" to p.str("answer"),"reason" to "QUESTION_MISSING"),"attempt:$attemptId:answer")
                            } else if (posed == null && question.second != posedSnapshot.str("fingerprint")) {
                                emit("answer_orphaned",json("attemptId" to attemptId,"posedSnapshot" to posedSnapshot,"rawAnswer" to p.str("answer"),"reason" to "QUESTION_CHANGED"),"attempt:$attemptId:answer")
                            } else if (question.first == "SINGLE_CHOICE" &&
                                (posedSnapshot.str("groupId").isBlank() || !validLetterMap(choiceOptions(qid), letterMap(posedSnapshot)) ||
                                 (posed != null && (letterMap(posed.payload) != letterMap(posedSnapshot) || posed.payload.str("groupId") != posedSnapshot.str("groupId"))))) {
                                status="REJECTED"; error="INVALID_LETTER_MAP"
                            } else {
                                if (posed == null) {
                                    // Key state and format come from authoritative storage, never the client snapshot.
                                    val snapshot = JsonObject(posedSnapshot + mapOf("format" to JsonPrimitive(question.first),"keyState" to JsonPrimitive(question.third)))
                                    emit("question_posed",snapshot,"attempt:$attemptId")
                                }
                                emit("answer_submitted",json("attemptId" to attemptId,"answer" to p.str("answer"),"offline" to p.flag("offline")),"attempt:$attemptId:answer")
                                if (question.first == "SINGLE_CHOICE") {
                                    val correct = choiceId(qid)
                                    val frozenMap = letterMap(posed?.payload ?: posedSnapshot)
                                    if (correct != null) emit("attempt_graded",json("attemptId" to attemptId,
                                        "score" to if(frozenMap[correct] == p.str("answer").trim().uppercase()) 1 else 0,
                                        "max" to 1,"status" to "EXACT",
                                        "feedback" to choiceFeedback(correct,p.str("answer"),frozenMap)),"attempt:$attemptId:grade:exact")
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
                "configure_course" -> {
                    if (runCatching { java.time.ZoneId.of(p.str("timeZone")) }.isFailure) { status="REJECTED"; error="INVALID_TIME_ZONE" }
                    else emit("course_configured",p)
                }
                "set_exam_weights" -> {
                    val weights = p["examWeights"] as? JsonObject
                    val values = weights?.values?.map { (it as? JsonPrimitive)?.intOrNull }
                    if (weights == null || weights.keys.any { it.isBlank() } || values!!.any { it == null || it < 0 } ||
                        values.filterNotNull().sumOf { it.toLong() } > Int.MAX_VALUE) { status="REJECTED"; error="INVALID_EXAM_WEIGHTS" }
                    else emit("exam_weights_set",json("weights" to weights))
                }
                "file_quick_ask" -> {
                    val filed = runCatching { fileQuickAsk(body.courseId,p) }
                    if (filed.isFailure) { status="REJECTED"; error=filed.exceptionOrNull()?.message }
                    else {
                        result = filed.getOrThrow()
                        if (p.str("action") == "NOTE") emit("note_saved",result)
                        emit("quick_ask_filed",result)
                    }
                }
                "save_note" -> emit("note_saved",p)
                "edit_journal" -> {
                    val revision = query("SELECT rev FROM journal_revisions WHERE course_id=? AND day=?",body.courseId,p.num("day")) { it.getLong(1) }.singleOrNull() ?: 0
                    if (revision != p.num("expectedRev")) { status="CONFLICT"; error="REV_MISMATCH"; result=json("currentRev" to revision,"content" to journalDay(body.courseId,p.num("day")).manual) }
                    else {
                        val rev = revision+1
                        sql("INSERT INTO journal_revisions(course_id,day,rev,content_sha) VALUES(?,?,?,?) ON CONFLICT(course_id,day) DO UPDATE SET rev=excluded.rev,content_sha=excluded.content_sha",body.courseId,p.num("day"),rev,p.str("contentSha"))
                        emit("journal_edited",JsonObject(p + ("rev" to JsonPrimitive(rev))))
                        result=json("currentRev" to rev)
                    }
                }
                else -> { status="REJECTED"; error="UNSUPPORTED_COMMAND" }
            }
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

    /** 流式消息的一生：streaming 立即可见（§4.2），每秒把已可见正文与 `(msg, i)` 计数一起落库。 */
    fun beginStreamingMessage(thread: String, message: String, model: String, role: String = "assistant") {
        sql("INSERT INTO messages(thread_id,mseq,message_id,role,model,status,content_md,delta_index,created_at) VALUES(?,coalesce((SELECT max(mseq)+1 FROM messages WHERE thread_id=?),1),?,?,?,'streaming','',0,?)",
            thread,thread,message,role,model,clock())
    }

    fun updateStreamingMessage(message: String, content: String, index: Int) {
        sql("UPDATE messages SET content_md=?,delta_index=? WHERE message_id=? AND status='streaming'",content,index,message)
    }

    fun finishMessage(message: String, status: String, reason: String?, content: String, index: Int) {
        sql("UPDATE messages SET status=?,reason=?,content_md=?,delta_index=? WHERE message_id=?",status,reason,content,index,message)
    }

    data class MessageSnapshot(val status: String, val reason: String?, val content: String, val index: Int, val threadId: String)

    fun messageSnapshot(id: String): MessageSnapshot? =
        query("SELECT status,reason,coalesce(content_md,''),delta_index,thread_id FROM messages WHERE message_id=?",id) {
            MessageSnapshot(it.getString(1),it.getString(2),it.getString(3),it.getInt(4),it.getString(5))
        }.singleOrNull()

    /** 最近几轮对话（追问带最近 12 轮，§5.2）。只取已经写完的消息，流式中的一条不喂回模型。 */
    fun recentTurns(thread: String, limit: Int = 12): List<Pair<String,String>> =
        query("SELECT role,coalesce(content_md,'') FROM messages WHERE thread_id=? AND status IN ('complete','truncated') ORDER BY mseq DESC LIMIT ?",thread,limit) {
            (it.getString(1) ?: "assistant") to it.getString(2)
        }.reversed()

    /** `ai_calls`（§5.7）：只记长度与耗时，不记内容。 */
    fun recordAiCall(callId: String, task: String, requestedModel: String, servedBy: String?, ttftMs: Long?,
        durMs: Long, inChars: Int, outChars: Int, status: String, queueMs: Long?, physicalCalls: Int) {
        sql("INSERT OR REPLACE INTO ai_calls(call_id,task,requested_model,served_by,ttft_ms,dur_ms,in_chars,out_chars,status,request_type,effort,pack_chars,queue_ms,physical_calls,cache_read,cache_create) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            callId,task,requestedModel,servedBy,ttftMs,durMs,inChars,outChars,status,
            "dev_live",null,null,queueMs,physicalCalls,null,null)
    }

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
