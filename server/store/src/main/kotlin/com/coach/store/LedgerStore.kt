package com.coach.store

import com.coach.domain.*
import com.coach.domain.reviewqueue.QueueAvailability
import com.coach.domain.reviewqueue.QueueQuestion
import com.coach.domain.reviewqueue.VariantCandidate
import com.coach.domain.reviewqueue.ledgerQueueCandidate
import com.coach.protocol.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.nio.file.*
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.logging.Logger
import kotlin.random.Random

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private val idRandom = java.security.SecureRandom()
private val ledgerLogger = Logger.getLogger(LedgerStore::class.java.name)
private fun uid() = ulid(System.currentTimeMillis(),ByteArray(10).also(idRandom::nextBytes))
data class Fence(val epoch: String, val value: Long, val id: String)
data class PairedDevice(val id: String, val name: String, val createdAt: Long, val revokedAt: Long?)
// Deliberately no generated toString: credentials must never reach logs.
class DeviceCredential(val deviceId: String, val token: String)
class PairingCode(val code: String, val expiresAt: Long)

class ReviewVariantRejected(val code: String) : IllegalArgumentException(code)

/** Fields only the server-side review allocation writes; an offline pack never carries any of them. */
private val REVIEW_RESERVED_FIELDS = setOf("exactReview","reviewIdentity","reviewEntryId","availability")
private fun JsonObject.hasReviewReservedField(): Boolean = REVIEW_RESERVED_FIELDS.any { containsKey(it) }

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
        // V5 is the combined alpha schema. Validate the known additive tables before accepting it.
        val deployedAlpha = version in 5..7 &&
            query("PRAGMA table_info(pool_details)") { it.getString(2) }.containsAll(listOf("question_id","pack_hash","explanation","source_quote","generator","reviewer")) &&
            query("PRAGMA table_info(pairing_code)") { it.getString(2) }.containsAll(listOf("singleton","code_sha256","expires_at","attempts"))
        require(version <= 4 || deployedAlpha) { "Database schema is newer than this server" }
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
        if (version < 5) {
            rawBackup(directory.resolve("backups/pre-V005-${uid()}"))
            transaction {
                sql("CREATE TABLE IF NOT EXISTS pool_details(question_id TEXT PRIMARY KEY REFERENCES questions(question_id), pack_hash TEXT NOT NULL, explanation TEXT NOT NULL, source_quote TEXT NOT NULL, generator TEXT NOT NULL, reviewer TEXT NOT NULL)")
                sql("CREATE TABLE IF NOT EXISTS pairing_code(singleton INTEGER PRIMARY KEY CHECK(singleton=1), code_sha256 TEXT NOT NULL, expires_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0)")
                sql("PRAGMA user_version=5")
            }
        }
        sql("CREATE TABLE IF NOT EXISTS pairing_code(singleton INTEGER PRIMARY KEY CHECK(singleton=1), code_sha256 TEXT NOT NULL, expires_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0)")
        sql("CREATE TABLE IF NOT EXISTS offline_packs(pack_id TEXT PRIMARY KEY,course_id TEXT NOT NULL,device_id TEXT NOT NULL,expires_at INTEGER NOT NULL,pack_json TEXT NOT NULL)")
        sql("CREATE TABLE IF NOT EXISTS offline_reservations(question_id TEXT PRIMARY KEY,pack_id TEXT NOT NULL,expires_at INTEGER NOT NULL)")
        sql("CREATE TABLE IF NOT EXISTS choice_allocations(id INTEGER PRIMARY KEY AUTOINCREMENT,course_id TEXT NOT NULL,correct_letter TEXT NOT NULL)")
        if (version < 6) {
            rawBackup(directory.resolve("backups/pre-V006-${uid()}"))
            transaction {
                // Keep the cutoff stable across restarts, without backfilling speech.
                sql("CREATE TABLE IF NOT EXISTS chat_history_coverage(singleton INTEGER PRIMARY KEY CHECK(singleton=1),started_at INTEGER NOT NULL)")
                sql("CREATE TABLE IF NOT EXISTS chat_history_legacy(thread_id TEXT PRIMARY KEY)")
                if (sql("INSERT OR IGNORE INTO chat_history_coverage VALUES(1,?)",clock()) > 0) {
                    // Thread IDs survive VACUUM/restore; events only identify uncovered history, never speech.
                    sql("INSERT OR IGNORE INTO chat_history_legacy SELECT thread_id FROM messages UNION SELECT thread_id FROM events WHERE thread_id IS NOT NULL")
                }
                sql("CREATE TABLE IF NOT EXISTS chat_inputs(device_id TEXT NOT NULL,client_message_id TEXT NOT NULL,message_id TEXT NOT NULL UNIQUE REFERENCES messages(message_id),course_id TEXT NOT NULL,thread_id TEXT NOT NULL,PRIMARY KEY(device_id,client_message_id))")
                sql("PRAGMA user_version=6")
            }
        }
        if (version < 7) {
            rawBackup(directory.resolve("backups/pre-V007-${uid()}"))
            transaction {
                val columns = query("PRAGMA table_info(source_docs)") { it.getString(2) }
                if ("learner_visible" !in columns) sql("ALTER TABLE source_docs ADD COLUMN learner_visible INTEGER NOT NULL DEFAULT 1")
                // Preserve the old browse restriction on existing tutor-invisible documents.
                sql("UPDATE source_docs SET learner_visible=0 WHERE tutor_visible=0")
                val hidden = query("SELECT doc_id,title,kind FROM source_docs") {
                    Triple(it.getString(1),it.getString(2).orEmpty(),it.getString(3).orEmpty())
                }.filter { !tutorSourceAllowed(it.first,it.second,it.third) }
                hidden.forEach { sql("UPDATE source_docs SET learner_visible=0 WHERE doc_id=?",it.first) }
                sql("PRAGMA user_version=7")
            }
        }
        ensureSourceIndexes()
        if (query("SELECT name FROM sqlite_master WHERE type='table' AND name='pool_details'") { it.getString(1) }.isEmpty()) {
            rawBackup(directory.resolve("backups/pre-pool-${uid()}"))
            transaction {
                sql("CREATE TABLE pool_details(question_id TEXT PRIMARY KEY REFERENCES questions(question_id), pack_hash TEXT NOT NULL, explanation TEXT NOT NULL, source_quote TEXT NOT NULL, generator TEXT NOT NULL, reviewer TEXT NOT NULL)")
            }
        }
        if (query("SELECT name FROM sqlite_master WHERE type='table' AND name='constructed_details'") { it.getString(1) }.isEmpty()) {
            rawBackup(directory.resolve("backups/pre-constructed-${uid()}"))
            sql("CREATE TABLE constructed_details(question_id TEXT PRIMARY KEY REFERENCES questions(question_id), pack_hash TEXT NOT NULL, reference_answer TEXT NOT NULL, rubric TEXT NOT NULL, rubric_fingerprint TEXT NOT NULL, generator TEXT NOT NULL, reviewer TEXT NOT NULL)")
        }
        // Tables made by earlier test builds of this feature lack the save order column; both creating and widening the table back up first.
        if ("answers_seq" !in query("PRAGMA table_info(mock_sessions)") { it.getString(2) }) {
            rawBackup(directory.resolve("backups/pre-mock-${uid()}"))
            transaction {
                // The frozen paper (answers included) never leaves the server before submission.
                sql("CREATE TABLE IF NOT EXISTS mock_sessions(mock_id TEXT PRIMARY KEY,course_id TEXT NOT NULL,kind TEXT NOT NULL,purpose TEXT NOT NULL,paper_json TEXT NOT NULL,started_at INTEGER NOT NULL,deadline INTEGER NOT NULL,answers_json TEXT NOT NULL DEFAULT '{}',submitted_at INTEGER,auto INTEGER NOT NULL DEFAULT 0,answers_seq INTEGER NOT NULL DEFAULT 0)")
                if ("answers_seq" !in query("PRAGMA table_info(mock_sessions)") { it.getString(2) }) sql("ALTER TABLE mock_sessions ADD COLUMN answers_seq INTEGER NOT NULL DEFAULT 0")
            }
        }

    }

    /** Capability repair is independent of version numbers used by other features or deployments. */
    private fun ensureSourceIndexes() = transaction {
        val pages = query("SELECT count(*) FROM source_pages") { it.getLong(1) }.single()
        for (table in listOf("fts_en","fts_zh")) {
            sql("CREATE TRIGGER IF NOT EXISTS ${table}_insert AFTER INSERT ON source_pages BEGIN INSERT INTO $table(text,doc_id,pdf_page) VALUES(new.text,new.doc_id,new.pdf_page); END")
            sql("CREATE TRIGGER IF NOT EXISTS ${table}_delete AFTER DELETE ON source_pages BEGIN DELETE FROM $table WHERE doc_id=old.doc_id AND pdf_page=old.pdf_page; END")
            sql("CREATE TRIGGER IF NOT EXISTS ${table}_update AFTER UPDATE ON source_pages BEGIN DELETE FROM $table WHERE doc_id=old.doc_id AND pdf_page=old.pdf_page; INSERT INTO $table(text,doc_id,pdf_page) VALUES(new.text,new.doc_id,new.pdf_page); END")
            val indexed = query("SELECT count(*) FROM $table") { it.getLong(1) }.single()
            // Equal counts can still hide an update made while the maintenance triggers were absent.
            val stale = indexed != pages || query("SELECT EXISTS(SELECT doc_id,pdf_page,text FROM source_pages EXCEPT SELECT doc_id,pdf_page,text FROM $table)") { it.getInt(1) != 0 }.single()
            if (stale) {
                sql("DELETE FROM $table")
                sql("INSERT INTO $table(text,doc_id,pdf_page) SELECT text,doc_id,pdf_page FROM source_pages")
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
        val run = agentRunId(thread)
        val kind = if (run == null) "KP" else "AGENT_KP"
        val anchor = if (run == null) kp else "$course:$run:$kp"
        val boundThread = query(
            "SELECT thread_id FROM threads WHERE course_id=? AND kind=? AND anchor_type='KP' AND anchor_id=?",
            course,kind,anchor
        ) { it.getString(1) }.singleOrNull()
        require(boundThread == null || boundThread == thread) {
            "KP $kp in course $course is already bound to thread $boundThread; requested $thread"
        }
        val row = query("SELECT course_id,kind,anchor_id FROM threads WHERE thread_id=?", thread) {
            Triple(it.getString(1), it.getString(2), it.getString(3))
        }.singleOrNull()
        if (row == null) sql("INSERT INTO threads(thread_id,course_id,kind,anchor_type,anchor_id) VALUES(?,?,?,'KP',?)", thread,course,kind,anchor)
        else if (row == Triple(course,"CHAT",thread)) sql("UPDATE threads SET kind=?,anchor_type='KP',anchor_id=? WHERE thread_id=?",kind,anchor,thread)
        else require(row == Triple(course,kind,anchor)) { "Thread binding mismatch" }
    }

    /** A separate course-bound review thread preserves the ordinary KP/Continue binding. */
    fun bindReviewThread(course: String, thread: String, kp: String) {
        require(course.isNotBlank() && course != "__system__" && thread.isNotBlank() && kp.isNotBlank())
        val run = agentRunId(thread)
        val kind = if (run == null) "REVIEW" else "AGENT_REVIEW"
        val anchor = if (run == null) "$course:$kp" else "$course:$run:$kp"
        val old = query("SELECT course_id,kind,anchor_id FROM threads WHERE thread_id=?",thread) {
            Triple(it.getString(1),it.getString(2),it.getString(3))
        }.singleOrNull()
        if (old == null) sql("INSERT INTO threads(thread_id,course_id,kind,anchor_type,anchor_id) VALUES(?,?,?,'COURSE_KP',?)",thread,course,kind,anchor)
        else require(old == Triple(course,kind,anchor)) { "Review thread binding mismatch" }
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

    /** A retry after midnight still belongs to the original daily thread. */
    fun quickAskForInput(course: String, device: String, clientMessageId: String): QuickAskThread {
        val prior = query("SELECT course_id,thread_id FROM chat_inputs WHERE device_id=? AND client_message_id=?",device,clientMessageId) {
            it.getString(1) to it.getString(2)
        }.singleOrNull() ?: return openQuickAsk(course)
        require(prior.first == course && isQuickThread(course,prior.second)) { "Client message ID payload mismatch" }
        return projection(course).quickAsks.values.single { it.threadId == prior.second }
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

    data class SavedInput(val messageId: String, val inserted: Boolean)

    fun chatInputFrame(message: String): JsonObject = query(
        "SELECT m.thread_id,m.content_md,m.created_at,i.client_message_id,i.device_id FROM messages m JOIN chat_inputs i USING(message_id) WHERE m.message_id=?",message) {
        json("t" to "msg.user","msg" to message,"role" to "user","status" to "complete","threadId" to it.getString(1),
            "text" to it.getString(2),"createdAt" to it.getLong(3),"clientMsgId" to it.getString(4),"deviceId" to it.getString(5)).let { frame ->
                JsonObject(frame + agentRunMetadata(agentRunId(it.getString(1))))
            }
    }.single()

    /** The authenticated device and immutable client ID identify a send, including after a restart. */
    fun saveChatInput(course: String, thread: String, text: String, device: String, clientMessageId: String): SavedInput = transaction {
        require(course.isNotBlank() && course != "__system__" && thread.isNotBlank() && text.isNotBlank()) { "Missing chat fields" }
        require(clientMessageId.isNotBlank() && clientMessageId.length <= 200) { "Invalid client message ID" }
        requireChatThread(course,thread)
        val prior = query("SELECT i.message_id,i.course_id,i.thread_id,m.content_md FROM chat_inputs i JOIN messages m USING(message_id) WHERE i.device_id=? AND i.client_message_id=?",device,clientMessageId) {
            listOf(it.getString(1),it.getString(2),it.getString(3),it.getString(4))
        }.singleOrNull()
        if (prior != null) {
            require(prior.drop(1) == listOf(course,thread,text)) { "Client message ID payload mismatch" }
            return@transaction SavedInput(prior[0],false)
        }
        // Standalone chat can precede tutor.open; bind it so history cannot cross courses.
        sql("INSERT OR IGNORE INTO threads(thread_id,course_id,kind,anchor_type,anchor_id) VALUES(?,?,'CHAT','THREAD',?)",thread,course,thread)
        val id = uid()
        beginStreamingMessage(thread,id,"",role="user")
        finishMessage(id,"complete",null,text,0)
        sql("INSERT INTO chat_inputs VALUES(?,?,?,?,?)",device,clientMessageId,id,course,thread)
        if (isQuickThread(course,thread)) append(course,"quick_ask_message",json("messageId" to id),thread=thread,actor="LEARNER")
        SavedInput(id,true)
    }

    fun chatHistory(course: String, thread: String): JsonObject {
        requireChatThread(course,thread)
        val coverageSince = query("SELECT started_at FROM chat_history_coverage WHERE singleton=1") { it.getLong(1) }.single()
        val legacy = query("SELECT EXISTS(SELECT 1 FROM chat_history_legacy WHERE thread_id=?)",thread) { it.getBoolean(1) }.single()
        val messages = query("SELECT m.message_id,m.role,m.status,m.content_md,m.created_at,m.model,m.delta_index,m.reason,i.client_message_id,i.device_id FROM messages m LEFT JOIN chat_inputs i USING(message_id) WHERE m.thread_id=? ORDER BY m.mseq",thread) {
            json("t" to if (it.getString(2)=="user") "msg.user" else "msg.snapshot",
                "msg" to it.getString(1),"role" to it.getString(2),"status" to it.getString(3),"text" to it.getString(4),
                "createdAt" to it.getLong(5),"model" to it.getString(6),"i" to it.getInt(7),"reason" to it.getString(8),
                "clientMsgId" to it.getString(9),"deviceId" to it.getString(10),"threadId" to thread).let { frame ->
                    JsonObject(frame + agentRunMetadata(agentRunId(thread)))
                }
        }
        return json("t" to "msg.history","courseId" to course,"threadId" to thread,"messages" to JsonArray(messages),
            "userTextCoverageSince" to coverageSince,"legacyHistoryUncovered" to legacy)
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
                ?: "kp:$kp".also { bindTutorThread(course,it,kp) }
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
        "SELECT q.question_id FROM questions q JOIN question_kp k ON q.question_id=k.question_id WHERE q.course_id=? AND k.kp_id=? AND q.key_state='VERIFIED' AND q.format='SINGLE_CHOICE' AND NOT EXISTS (SELECT 1 FROM offline_reservations r WHERE r.question_id=q.question_id AND r.expires_at>?) ORDER BY q.question_id", course,kp,clock()
    ) { it.getString(1) }.filter { choiceOptions(it).size == 4 }

    /** Server-only answer key, never included in a card or model prompt. */
    fun tutorChoiceKey(questionId: String): ChoiceKey = ChoiceKey(choiceOptions(questionId), requireNotNull(choiceId(questionId)))

    /** Available only on the server; callers expose this after submission, never in a posed card. */
    fun verifiedChoiceExplanation(questionId: String): String = query(
        """SELECT d.explanation FROM pool_details d JOIN questions q USING(question_id)
           WHERE question_id=? AND q.key_state='VERIFIED' AND d.generator<>'' AND d.reviewer<>''""", questionId
    ) { it.getString(1).trim() }.singleOrNull().orEmpty()

    private fun gradedChoiceFeedback(questionId: String, correct: String, chosen: String, map: Map<String,String>): String {
        val verdict = choiceFeedback(correct,chosen,map)
        if (map[correct] == chosen.trim().uppercase()) return verdict
        val options = choiceOptions(questionId)
        val selected = options.singleOrNull { map[it.optionId] == chosen.trim().uppercase() }?.text.orEmpty()
        val right = options.single { it.optionId == correct }.text
        val explanation = verifiedChoiceExplanation(questionId)
        return verdict + "\n你选的 ${chosen.trim().uppercase()}：$selected\n正确项 ${map.getValue(correct)}：$right\n" +
            if (explanation.isNotBlank()) "你的选择与已复核解析不符：$explanation"
            else "这道题暂无已复核解析，不能仅凭选项判断你的具体错因。点「换个例子再讲」可结合原题解释。"
    }


    fun hello(): Hello = query("SELECT epoch,backed_up_through_seq,last_seq_before FROM ledger_meta") {
        Hello(it.getString(1),lastSeq(),it.getLong(2),it.getLong(3),serverTime=clock(),agentRunsSupported=true)
    }.single()
    private fun lastSeq(): Long = query("SELECT coalesce(max(seq),0) FROM events") { it.getLong(1) }.single()
    fun events(course: String, after: Long = 0, limit: Int = Int.MAX_VALUE, through: Long = Long.MAX_VALUE): List<Event> = query("SELECT event_json FROM events WHERE course_id=? AND seq>? AND seq<=? ORDER BY seq LIMIT ?",course,after,through,limit) { LedgerJson.decodeFromString<Event>(it.getString(1)) }
    /** Watermarks only: notification sockets never need to load historical event payloads. */
    fun courseHeads(): Map<String, Long> = query("SELECT course_id,max(seq) FROM events WHERE course_id<>'__system__' GROUP BY course_id ORDER BY course_id") { it.getString(1) to it.getLong(2) }.toMap()
    fun courses(): List<String> = query("SELECT DISTINCT course_id FROM events WHERE course_id<>'__system__' ORDER BY course_id") { it.getString(1) }
    private val contentStore by lazy { CourseContentStore(connection) }
    /** Private generated essay prompts; Main points this at the materials directory, tests at a fixture. */
    var essays: EssayBank = EssayBank(null)
    /** Later revisions (the DeepTutor migration's prerequisite ops) carry no `course`; the name is the latest one any revision gave. */
    private fun courseName(course: String): String = events(course).asReversed().filter { it.type == "outline_revised" }
        .firstNotNullOfOrNull { (it.payload["course"] as? JsonObject)?.str("name")?.takeIf(String::isNotBlank) }.orEmpty()
    /** Bank prompts the learner can pick now; prompts whose week has no knowledge point yet stay hidden. */
    fun essayPrompts(course: String): JsonObject {
        val points = weekPoints(events(course))
        val all = essays.prompts(courseName(course))
        val usable = all.filter { essayKp(points, it.week, it.kp) != null }
        return json("prompts" to JsonArray(usable.map { e ->
            json("id" to e.id, "week" to e.week, "kp" to e.kp, "marks" to e.marks, "wordTarget" to e.wordTarget,
                "stem" to e.stem, "pack" to e.packTitle, "fromMockPaper" to (e.kind == "paper"))
        }), "unmatched" to all.size - usable.size, "weeks" to points.flatMap { it.weeks }.distinct().sorted())
    }
    private fun essayKp(points: List<WeekPoint>, week: Int, topic: String): String? {
        val inWeek = points.filter { week in it.weeks }.ifEmpty { return null }
        val words = Essay.words(topic).filter { it.length > 3 }
        val named = inWeek.maxByOrNull { p -> words.count { it in p.title.lowercase() } }?.takeIf { p -> words.any { it in p.title.lowercase() } }
        return (named ?: inWeek.firstOrNull { "【Essay】" in it.title } ?: inWeek.first()).id
    }
    /** Bank prompts are VERIFIED by the content gate; a pasted prompt has no reviewed key and never counts as evidence. */
    private fun essayQuestion(course: String, essayId: String, pasted: String, week: Int): JsonObject? {
        val bank = if (essayId.isBlank()) null else essays.prompts(courseName(course)).firstOrNull { it.id == essayId } ?: return null
        val stem = bank?.stem ?: pasted.trim().takeIf { it.length in 15..4000 } ?: return null
        val kp = essayKp(weekPoints(events(course)), bank?.week ?: week, bank?.kp.orEmpty()) ?: return null
        val rubric = essayRubric(bank?.marks ?: 20.0)
        val rubricFingerprint = sha256(rubric.toString().toByteArray(Charsets.UTF_8))
        val keyState = if (bank == null) "UNREVIEWED" else "VERIFIED"
        val reference = bank?.modelAnswer.orEmpty()
        val fp = sha256(listOf("essay", kp, stem, reference, rubricFingerprint).joinToString("\u0000").toByteArray(Charsets.UTF_8))
        val wordTarget = bank?.wordTarget ?: Essay.WORD_TARGET
        val id = query("SELECT question_id FROM questions WHERE course_id=? AND fingerprint=?", course, fp) { it.getString(1) }.firstOrNull() ?: uid().also { id ->
            val key = json("stem" to stem, "reference_answer" to reference, "rubric" to rubric, "essay" to json(
                "essayId" to essayId.ifBlank { null }, "marks" to (bank?.marks ?: 20.0), "wordTarget" to wordTarget,
                "markingNotes" to (bank?.rubric ?: JsonArray(emptyList())), "source" to bank?.source))
            sql("INSERT INTO questions(question_id,course_id,format,stem_md,origin,fingerprint,key_state) VALUES(?,?,'ESSAY',?,'COACH',?,?)", id, course, stem, fp, keyState)
            sql("INSERT INTO question_kp VALUES(?,?,1)", id, kp)
            sql("INSERT INTO answer_keys(question_id,key_json,reviewed_by,reviewed_at) VALUES(?,?,?,?)", id, key.toString(), if (bank == null) "learner" else "bank", clock())
            sql("INSERT INTO constructed_details VALUES(?,?,?,?,?,?,?)", id, essayId.ifBlank { "pasted" }, reference, rubric.toString(), rubricFingerprint, "bank", if (bank == null) "learner" else "bank")
        }
        return json("questionId" to id, "stem" to stem, "fingerprint" to fp, "format" to "ESSAY", "rubricFingerprint" to rubricFingerprint,
            "kpIds" to listOf(kp), "keyState" to keyState, "wordTarget" to wordTarget, "essayId" to essayId.ifBlank { null })
    }
    /** Offline CLI or server actor only: one transaction installs content and its two events. */
    fun seedContent(seed: ContentSeed): Boolean {
        val content = contentStore
        return transaction { content.install(seed, { course, type, payload, idem -> append(course, type, payload, idem, actor="MIGRATION") }, ::putBlob) }
    }
    /** Offline CLI or actor only. New points, metadata and their replay event commit together. */
    fun addOutline(payload: JsonObject): Boolean {
        return transaction { OutlineAdditionStore(connection).install(payload, CourseContentStore(connection)) { course, event, idem ->
            append(course, "outline_revised", event, idem, actor="MIGRATION")
        } }
    }
    fun courseContent(course: String): CourseContent? = contentStore.course(course)
    fun teachingPages(course: String, kp: String): List<Pair<SourceAnchor, String>> = contentStore.teachingPages(course, kp)
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
        // The posed attempt is authoritative for late/asynchronous grades, even without a thread.
        val posed = payload.str("attemptId").takeIf { it.isNotBlank() }?.let { attempt ->
            query("SELECT event_json FROM events WHERE course_id=? AND type='question_posed' AND json_extract(payload,'$.attemptId')=? ORDER BY seq LIMIT 1",course,attempt) {
                LedgerJson.decodeFromString<Event>(it.getString(1))
            }.singleOrNull()
        }
        val inheritedRun = posed?.let { agentRunId(it.threadId) ?: it.payload.str("agentRunId").ifBlank { null } }
        val threadRun = agentRunId(thread)
        val payloadRun = payload.str("agentRunId").ifBlank { null }
        require(threadRun == null || payloadRun == null || threadRun == payloadRun) { "Agent run mismatch" }
        val explicitRun = threadRun ?: payloadRun
        require(posed == null || explicitRun == null || explicitRun == inheritedRun) { "Cannot move an attempt between evidence origins" }
        val run = explicitRun ?: inheritedRun
        val markedPayload = JsonObject(payload + agentRunMetadata(run))
        val e = Event(seq,uid(),course,type,recordedAt=at,occurredAt=occurredAt.coerceAtMost(at),
            deviceId=device,actor=actor,commandId=command?.commandId,effectIndex=effect,threadId=thread,idemKey=idem,payload=markedPayload)
        sql("INSERT INTO events(seq,id,course_id,type,schema,recorded_at,occurred_at,device_id,actor,command_id,effect_index,idem_key,payload_sha256,thread_id,payload,event_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            e.seq,e.id,e.courseId,e.type,e.schema,e.recordedAt,e.occurredAt,e.deviceId,e.actor,e.commandId,e.effectIndex,e.idemKey,command?.payloadSha256,e.threadId,markedPayload.toString(),LedgerJson.encodeToString(e))
        if(type=="question_key_changed") sql("UPDATE questions SET key_state=? WHERE question_id=? AND course_id=?",payload.str("keyState"),payload.str("questionId"),course)
        sql("DELETE FROM projection_cache WHERE course_id=?",course)
        if (type in setOf("attempt_graded","grade_reviewed","legacy_passed","kp_overridden")) {
            // Validate/project the learning fact as before; only the optional supply work is best-effort.
            val passed=projection(course).mastery.filterValues { it.passed }.keys
            enqueuePassedConstructedSupply(course,passed)
        }
        return e
    }

    private fun enqueuePassedConstructedSupply(course: String, passed: Set<String>) {
        val savepoint=try { connection.setSavepoint() } catch (error: Exception) {
            ledgerLogger.warning("Constructed supply skipped: savepoint unavailable (${error.javaClass.simpleName})")
            return
        }
        try {
            // Only previously supplied packs qualify. Skip invalid legacy packs before selecting the latest per KP.
            val sources=query("SELECT subject FROM jobs WHERE kind='gen_mcq' AND json_valid(subject) AND json_extract(subject,'$.course')=? ORDER BY created_at DESC,rowid DESC",course) {
                LedgerJson.parseToJsonElement(it.getString(1)).jsonObject
            }.filter { source ->
                val valid=source.str("kp").isNotBlank() && source.str("objective").isNotBlank() && source.str("excerpt").isNotBlank()
                if (!valid) ledgerLogger.warning("Constructed supply skipped: invalid source")
                valid
            }.distinctBy { it.str("kp") }
            for (source in sources) if (source.str("kp") in passed)
                enqueueConstructedInTransaction(course,source.str("kp"),source.str("objective"),source.str("excerpt"))
        } catch (error: Exception) {
            // A failed INSERT may already have made partial changes. Never commit those or undo the fact.
            connection.rollback(savepoint)
            ledgerLogger.warning("Constructed supply skipped: hook failed (${error.javaClass.simpleName})")
        } finally { connection.releaseSavepoint(savepoint) }
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
        rng: Random, device: String, offline: Boolean = true, thread: String? = null, reserveOnly: Boolean = false): List<Event> {
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
        val recent = query("SELECT correct_letter FROM choice_allocations WHERE course_id=? ORDER BY id DESC LIMIT 2",course) { it.getString(1) }.reversed().toMutableList()
        if (recent.isEmpty()) recent += events(course).filter { it.type == "question_posed" && it.payload.str("format") == "SINGLE_CHOICE" }.takeLast(2)
            .mapNotNull { letterMap(it.payload)[choiceId(it.payload.str("questionId"))] }
        val maps = keys.map { key ->
            val letters = optionLetters(key.options.size)
            // Two-option questions must alternate once both letters have been used.
            val candidates = letters.filter { it !in recent.takeLast(2) }
                .ifEmpty { letters.filter { it != recent.lastOrNull() } }
            val correct = candidates.random(rng)
            val rest = optionLetters(key.options.size).filter { it != correct }.shuffled(rng).iterator()
            recent += correct
            sql("INSERT INTO choice_allocations(course_id,correct_letter) VALUES(?,?)",course,correct)
            key.options.associate { it.optionId to if (it.optionId == key.correctOptionId) correct else rest.next() }
        }
        return requests.mapIndexed { index, request ->
            val qid = request.str("questionId")
            val content = query("SELECT stem_md,fingerprint FROM questions WHERE question_id=?",qid) { it.getString(1) to it.getString(2) }.single()
            val payload = JsonObject(request + mapOf(
                "groupId" to JsonPrimitive(groupId), "letterMap" to JsonObject(maps[index].mapValues { JsonPrimitive(it.value) }),
                "stem" to JsonPrimitive(content.first), "options" to LedgerJson.encodeToJsonElement(keys[index].options),
                "fingerprint" to JsonPrimitive(content.second), "format" to JsonPrimitive("SINGLE_CHOICE"),
                "keyState" to JsonPrimitive("VERIFIED"), "offlinePack" to JsonPrimitive(offline)
            ) - "correctOptionId")
            if (reserveOnly) Event(0,uid(),course,"question_posed",recordedAt=clock(),occurredAt=clock(),deviceId=device,
                actor="SYSTEM",idemKey="attempt:${request.str("attemptId")}",payload=payload)
            else append(course,"question_posed",payload,"attempt:${request.str("attemptId")}",device=device, thread=thread)
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

    /** Download is a reservation, not a learning fact. Retry returns the same unexpired pack. */
    fun downloadOfflinePack(course: String, device: String, size: Int = 10, ttlMillis: Long = 86_400_000): OfflineChoicePack = transaction {
        require(size in 1..30 && ttlMillis > 0 && course.isNotBlank() && course != "__system__")
        query("SELECT pack_json FROM offline_packs WHERE course_id=? AND device_id=? AND expires_at>? ORDER BY rowid DESC LIMIT 1",course,device,clock()) {
            LedgerJson.decodeFromString<OfflineChoicePack>(it.getString(1)).copy(epoch=hello().epoch)
        }.singleOrNull()?.let { pack ->
            val answered = events(course).filter { it.type in setOf("answer_submitted","answer_orphaned") }.map { it.payload.str("attemptId") }.toSet()
            if (pack.groups.flatten().any { it.payload.str("attemptId") !in answered }) return@transaction pack
        }
        sql("DELETE FROM offline_reservations WHERE expires_at<=?",clock())
        sql("DELETE FROM offline_packs WHERE expires_at<=?",clock())
        val state = projection(course)
        val today = logicalDay(clock(), state.timeZone)
        val due = state.reviewsOn(today).keys.toList()
        val kps = (due + listOfNotNull(state.position.continueTarget, state.position.current) +
            query("SELECT DISTINCT kp_id FROM question_kp JOIN questions USING(question_id) WHERE course_id=? ORDER BY question_kp.rowid",course) { it.getString(1) }).distinct()
        val ids = kps.flatMap { kp -> unseenPoolIds(course,kp).map { it to kp } }.distinctBy { it.first }.take(size)
        val expires = clock() + ttlMillis
        val packId = uid()
        val requests = ids.map { (qid,kp) -> json("questionId" to qid,"attemptId" to uid(),"kpIds" to listOf(kp),
            "purpose" to "RECALL","review" to true,"policyVersion" to 1,"offlinePack" to true,"expiresAt" to expires) }
        val groups = mutableListOf<List<Event>>()
        var offset = 0
        while (offset < requests.size) {
            val count = if (requests.size-offset >= 3) 3 else 1
            groups += poseChoiceGroupInTransaction(course,"$packId:${groups.size}",requests.subList(offset,offset+count),Random.Default,device,reserveOnly=true)
            offset += count
        }
        val pack = OfflineChoicePack(groups,groups.flatten().map { OfflineKey(it.payload.str("attemptId"),requireNotNull(choiceId(it.payload.str("questionId")))) },packId,course,device,expires,hello().epoch)
        if (ids.isNotEmpty()) {
            sql("INSERT INTO offline_packs VALUES(?,?,?,?,?)",packId,course,device,expires,LedgerJson.encodeToString(pack))
            ids.forEach { (qid,_) -> sql("INSERT INTO offline_reservations VALUES(?,?,?)",qid,packId,expires) }
        }
        pack
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
        val requestedRun = body.payload.str("agentRunId").ifBlank { null }
        val validOrigin = runCatching {
            agentRunMetadata(requestedRun)
            val threadRun = agentRunId(body.payload.str("threadId"))
            require(threadRun == null || threadRun == requestedRun)
            val attempt = body.payload.str("attemptId")
            val posed = if (attempt.isBlank()) null else events(body.courseId).firstOrNull {
                it.type == "question_posed" && it.payload.str("attemptId") == attempt
            }
            if (posed != null && requestedRun != null) require(requestedRun ==
                (agentRunId(posed.threadId) ?: posed.payload.str("agentRunId").ifBlank { null }))
        }.isSuccess
        if (!validOrigin) return receipt("REJECTED",error="INVALID_AGENT_RUN")
        return transaction {
            val effects = mutableListOf<Long>()
            fun emit(type: String, payload: JsonObject, idem: String? = null) {
                val actor=if(type in setOf("answer_submitted","kp_opened","hint_revealed","followup_asked","review_snoozed","kp_overridden","override_cleared","exam_set","note_saved","journal_edited","study_scope_set","study_scope_advanced")) "LEARNER" else "SYSTEM"
                val marked = JsonObject(payload + agentRunMetadata(body.payload.str("agentRunId").ifBlank { null }))
                effects.add(append(body.courseId,type,marked,idem ?: "cmd:${c.deviceId}:${c.commandId}:${effects.size}",c.deviceId,actor,c,effects.size,body.clientTime,body.payload.str("threadId").ifBlank { null }).seq)
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
                    val posedFacts = events(body.courseId).filter { it.type == "question_posed" && it.payload.str("attemptId") == attemptId }
                    // Exact review exists only as a fact this store posed inside takeReviewVariant. A snapshot
                    // that merely says exactReview proves nothing: submit_answer emits through the command path,
                    // so any pose it creates carries a command id and can never satisfy provenExactPose.
                    val exactReview = posedFacts.firstOrNull { provenExactPose(it, body.courseId, attemptId) }
                    val unprovenReviewPose = posedFacts.firstOrNull { it.payload.hasReviewReservedField() }
                    val reviewKey = exactReview?.let { pose -> query("SELECT fingerprint,key_state FROM questions WHERE course_id=? AND question_id=?", body.courseId, pose.payload.str("questionId")) { it.getString(1) to it.getString(2) }.singleOrNull() }
                    if (exactReview != null && (exactReview.deviceId != c.deviceId ||
                            exactReview.payload.str("agentRunId").ifBlank { null } != requestedRun || posedSnapshot != exactReview.payload ||
                            reviewKey?.first != exactReview.payload.str("fingerprint") || reviewKey?.second != "VERIFIED")) {
                        status="REJECTED"; error="REVIEW_SCOPE_OR_QUESTION_CHANGED"
                    } else if (exactReview == null && (unprovenReviewPose != null || posedSnapshot?.hasReviewReservedField() == true)) {
                        // Reserved allocation fields without a server allocation are a forgery, not an offline answer.
                        status="REJECTED"; error="REVIEW_RESERVED_FIELDS"
                    } else if (attemptId.isBlank() || posedSnapshot == null || posedSnapshot.str("attemptId") != attemptId || posedSnapshot.strings("kpIds").isEmpty() || posedSnapshot.str("questionId").isBlank() || posedSnapshot.str("fingerprint").isBlank() || posedSnapshot.str("purpose").isBlank() || posedSnapshot.num("policyVersion")<1) {
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
                            val reserved = query("SELECT p.device_id,p.pack_json FROM offline_reservations r JOIN offline_packs p USING(pack_id) WHERE r.question_id=? AND r.expires_at>?",qid,clock()) { it.getString(1) to LedgerJson.decodeFromString<OfflineChoicePack>(it.getString(2)) }.singleOrNull()
                            val reservedSnapshot = reserved?.second?.groups?.flatten()?.firstOrNull { it.payload.str("attemptId") == attemptId }?.payload
                            // Only an attempt the live pack itself handed out is bound to its device and snapshot;
                            // late answers from an expired pack and online re-poses of the same question are not.
                            if (reserved != null && reservedSnapshot != null && (reserved.first != c.deviceId || reservedSnapshot != posedSnapshot)) {
                                status="REJECTED"; error="OFFLINE_RESERVATION_MISMATCH"
                            } else if (activeReply) {
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
                                    // Reserved allocation fields were already refused above, so this pose stays a plain fact.
                                    val snapshot = JsonObject(posedSnapshot + mapOf("format" to JsonPrimitive(question.first),"keyState" to JsonPrimitive(question.third)))
                                    emit("question_posed",snapshot,"attempt:$attemptId")
                                }
                                // A timed attempt handed in after its limit is kept and graded, but never counts as timed evidence.
                                val limit = posed?.payload?.num("timeLimitSec") ?: 0
                                val overtime = limit > 0 && clock() - posed!!.payload.num("posedAt") > (limit + Essay.GRACE_SEC) * 1000
                                emit("answer_submitted",JsonObject(json("attemptId" to attemptId,"answer" to p.str("answer"),"offline" to p.flag("offline"),"finalAnswer" to p.str("finalAnswer")) +
                                    if (overtime) json("overtime" to true) else json()),"attempt:$attemptId:answer")
                                if (question.first == "SINGLE_CHOICE") {
                                    val correct = choiceId(qid)
                                    val frozenMap = letterMap(posed?.payload ?: posedSnapshot)
                                    if (correct != null) emit("attempt_graded",json("attemptId" to attemptId,
                                        "score" to if(frozenMap[correct] == p.str("answer").trim().uppercase()) 1 else 0,
                                        "max" to 1,"status" to "EXACT",
                                        "feedback" to gradedChoiceFeedback(qid,correct,p.str("answer"),frozenMap)),"attempt:$attemptId:grade:exact")
                                } else enqueueConstructedGrade(body.courseId,attemptId,qid,p.str("answer"),posed?.threadId,p.str("finalAnswer"))
                            }
                        }
                    }
                }
                "pose_essay" -> {
                    val attemptId = p.str("attemptId")
                    val limit = p.num("timeLimitSec", Essay.TIME_LIMIT_SEC.toLong())
                    val taken = query("SELECT 1 FROM events WHERE course_id=? AND idem_key=?",body.courseId,"attempt:$attemptId") { it.getInt(1) }.isNotEmpty()
                    val question = if (attemptId.isBlank() || taken || limit !in 60L..3600L) null
                        else essayQuestion(body.courseId, p.str("essayId"), p.str("stem"), p.num("week").toInt())
                    if (question == null) { status="REJECTED"; error=if (attemptId.isBlank() || taken || limit !in 60L..3600L) "INVALID_ESSAY" else "ESSAY_UNAVAILABLE" }
                    else {
                        // Drill attempts never become the Today page's continue target.
                        val posed = JsonObject(question + json("attemptId" to attemptId, "purpose" to "TIMED", "policyVersion" to 1,
                            "posedAt" to clock(), "origin" to "COACH", "drill" to true, "timeLimitSec" to limit))
                        emit("question_posed", posed, "attempt:$attemptId")
                        result = json("posedSnapshot" to posed)
                    }
                }
                "retry_week_mistake" -> {
                    val old = weekMistakes(events(body.courseId), p.num("week").toInt()).firstOrNull { it.payload.str("attemptId") == p.str("attemptId") }
                    if (old == null) { status="REJECTED"; error="MISTAKE_CHANGED" }
                    else {
                        val qid = old.payload.str("questionId")
                        val verified = query("SELECT key_state FROM questions WHERE course_id=? AND question_id=? AND format='SINGLE_CHOICE'", body.courseId, qid) { it.getString(1) }.singleOrNull() == "VERIFIED"
                        val options = if (verified) choiceOptions(qid) else emptyList()
                        val key = if (verified) choiceId(qid).orEmpty() else ""
                        val usable = try { ChoiceKey(options, key); true } catch (_: IllegalArgumentException) { false }
                        if (!verified) { status="REJECTED"; error="QUESTION_UNAVAILABLE" }
                        else if (!usable) {
                            status="REJECTED"; error="QUESTION_OPTIONS_UNAVAILABLE"
                        }
                        else {
                            val thread = "week-review:${p.num("week")}:$qid"
                            val posed = poseChoiceGroup(body.courseId, "week:${c.commandId}", listOf(JsonObject(old.payload - setOf("mockId", "marks") + mapOf(
                                "attemptId" to JsonPrimitive(uid()), "purpose" to JsonPrimitive("RECALL"),
                                // The answer was just shown in feedback; re-picking it is practice, not a spaced review.
                                "review" to JsonPrimitive(false), "weekReview" to p.getValue("week")
                            ))), device=c.deviceId, thread=thread)
                            effects.addAll(posed.map { it.seq })
                            result = json("threadId" to thread)
                        }
                    }
                }
                "set_study_scope" -> {
                    val week = p.num("week").toInt()
                    val points = weekPoints(events(body.courseId))
                    if (week != 0 && points.none { week in it.weeks }) { status="REJECTED"; error="UNKNOWN_WEEK" }
                    else {
                        val state = snapshot(body.courseId).projection
                        val ids = points.filter { week in it.weeks && state.mastery[it.id]?.let { m -> m.passed && !m.needsRepair } != true }.map { it.id }
                        emit("study_scope_set", json("week" to week, "kpIds" to ids, "basis" to "COURSE_WEEK_OR_W_LABEL"))
                        result = json("target" to ids.firstOrNull())
                    }
                }
                "advance_study_scope" -> {
                    val state = snapshot(body.courseId).projection
                    val scope = state.studyScope
                    val available = weekPoints(events(body.courseId)).map { it.id }.toSet()
                    if (scope == null || scope.id != p.str("scopeId") || scope.target(state.mastery, available) != p.str("kpId")) {
                        status="CONFLICT"; error="STUDY_SCOPE_MOVED"
                    } else {
                        emit("study_scope_advanced", json("scopeId" to scope.id, "kpId" to p.str("kpId")))
                        result = json("target" to scope.copy(cursor=scope.kpIds.indexOf(p.str("kpId")) + 1).target(state.mastery, available))
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
                    else emit("exam_weights_set",if(p.str("scope")=="FINAL") json("weights" to weights,"scope" to "FINAL") else json("weights" to weights))
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
        // Clean up only a directory this call created: an explicit target may already hold a complete backup.
        Files.createDirectories(target.toAbsolutePath().parent)
        val created = try { Files.createDirectory(target); true } catch(e: java.nio.file.FileAlreadyExistsException) { false }
        try {
            // Inside the cleanup: a VACUUM INTO that fails on a full disk leaves a partial copy behind.
            rawBackup(target)
            val blobDir = Files.createDirectories(target.resolve("blobs"))
            Files.list(directory.resolve("blobs")).use { files -> files.forEach { Files.createLink(blobDir.resolve(it.fileName),it) } }
            Files.newBufferedWriter(target.resolve("events.jsonl"),Charsets.UTF_8).use { out ->
                query("SELECT event_json FROM events ORDER BY seq") { it.getString(1) }.forEach { out.write(it);out.newLine() }
                query("SELECT thread_id,mseq,message_id,role,model,status,content_md,reason,mode,citations,created_at FROM messages ORDER BY thread_id,mseq") { rs -> json("recordType" to "message","threadId" to rs.getString(1),"mseq" to rs.getLong(2),"messageId" to rs.getString(3),"role" to rs.getString(4),"model" to rs.getString(5),"status" to rs.getString(6),"contentMd" to rs.getString(7),"reason" to rs.getString(8),"mode" to rs.getString(9),"citations" to rs.getString(10),"createdAt" to rs.getLong(11)).let { JsonObject(it + agentRunMetadata(agentRunId(rs.getString(1)))) }.toString() }.forEach { out.write(it);out.newLine() }
            }
            // Publish the watermark only after DB + blobs + export all succeeded.
            DriverManager.getConnection("jdbc:sqlite:${target.resolve("coach.sqlite")}").use { backup -> backup.prepareStatement("UPDATE ledger_meta SET backed_up_through_seq=?,last_backup_at=?").use { ps -> ps.setLong(1,seq);ps.setLong(2,clock());ps.execute(); ps.updateCount.coerceAtLeast(0) } }
            Files.writeString(target.resolve("complete"),seq.toString(),StandardOpenOption.CREATE_NEW)
            sql("UPDATE ledger_meta SET backed_up_through_seq=?,last_backup_at=?",seq,clock())
            return target
        } catch(e: Throwable) { if(created) target.toFile().deleteRecursively(); throw e }
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
    /** All supply mutations use the same actor/connection as learning facts. */
    fun enqueueSupply(course: String, kp: String, objective: String, excerpt: String, background: Boolean = false): String = transaction {
        require(course.isNotBlank() && course != "__system__" && kp.isNotBlank() && objective.isNotBlank() && excerpt.isNotBlank())
        val hash = sha256((objective + "\u0000" + excerpt).toByteArray(Charsets.UTF_8))
        val active = supplyJobs(course, kp).firstOrNull { it.packHash == hash && it.kind in setOf("gen_mcq","blind_review") }
        active?.id ?: enqueueJob("gen_mcq", json("course" to course, "kp" to kp, "objective" to objective,
            "excerpt" to excerpt, "background" to background).toString(), hash)
    }
    fun supplyJobs(course: String? = null, kp: String? = null): List<SupplyJob> =
        query("SELECT job_id,kind,subject,question_version FROM jobs WHERE kind IN ('gen_mcq','blind_review','gen_constructed','review_constructed') AND json_valid(subject) AND state IN ('queued','running') ORDER BY created_at,job_id") {
            SupplyJob(it.getString(1), it.getString(2), LedgerJson.parseToJsonElement(it.getString(3)).jsonObject, it.getString(4))
        }.filter { (course == null || it.subject.str("course") == course) && (kp == null || it.subject.str("kp") == kp) }

    fun recordAiCall(call: JsonObject, requestType: String = "background") {
        sql("INSERT OR IGNORE INTO ai_calls(call_id,task,requested_model,served_by,ttft_ms,dur_ms,in_chars,out_chars,status,request_type,queue_ms,physical_calls) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            call.str("call_id"),call.str("task"),call.str("requested_model"),
            (call["served_by"] as? JsonPrimitive)?.contentOrNull,call.num("ttft_ms"),call.num("dur_ms"),
            call.num("in_chars"),call.num("out_chars"),call.str("status"),requestType,call.num("queue_ms"),call.num("physical_calls"))
    }
    fun supplyCallCount(): Long = query("SELECT coalesce(sum(physical_calls),0) FROM ai_calls") { it.getLong(1) }.single()
    fun failSupply(token: Fence, reason: String): Boolean {
        require(reason in setOf("EMPTY", "TEXT_ONLY", "MALFORMED", "TIMEOUT", "CLI_ERROR"))
        if (token.epoch != hello().epoch) return false
        return sql("UPDATE jobs SET state='failed',last_error=?,updated_at=? WHERE job_id=? AND fence=? AND state='running'",
            reason,clock(),token.id,token.value) == 1
    }
    /** A claim abandoned by shutdown goes back to the queue without spending an attempt. */
    fun releaseJob(token: Fence): Boolean {
        if (token.epoch != hello().epoch) return false
        return sql("UPDATE jobs SET state='queued',fence=fence+1,attempts=MAX(attempts-1,0),updated_at=? WHERE job_id=? AND fence=? AND state='running'",
            clock(),token.id,token.value) == 1
    }
    /** Retain the failed job, and atomically queue one fresh schema-constrained attempt. */
    fun retryMalformedSupply(token: Fence, job: SupplyJob): Boolean = transaction {
        if (!failSupply(token,"MALFORMED")) return@transaction false
        if (job.subject.flag("retryMalformed")) return@transaction false
        enqueueJob("gen_mcq", JsonObject(job.subject + mapOf(
            "retryMalformed" to JsonPrimitive(true), "retryOf" to JsonPrimitive(job.id)
        )).toString(), job.packHash)
        true
    }

    fun queueBlindReview(token: Fence, job: SupplyJob, questions: JsonArray, generator: String): Boolean = transaction {
        if (!completeJob(token,job.packHash,null)) return@transaction false
        enqueueJob("blind_review", JsonObject(job.subject + mapOf("questions" to questions, "generator" to JsonPrimitive(generator))).toString(),job.packHash)
        true
    }
    fun storeVerifiedBatch(token: Fence, job: SupplyJob, questions: List<VerifiedPoolQuestion>): Int = transaction {
        if (!completeJob(token,job.packHash,null)) return@transaction 0
        val course=job.subject.str("course"); val kp=job.subject.str("kp")
        var inserted=0
        for (q in questions) {
            ChoiceKey(q.options,q.correctOptionId)
            require(q.options.size == 4 && q.generator.isNotBlank() && q.reviewer.isNotBlank())
            val fp=questionFingerprint(q.stem,q.options)
            if (query("SELECT 1 FROM questions WHERE course_id=? AND fingerprint=?",course,fp) { it.getInt(1) }.isNotEmpty()) continue
            val id=uid()
            sql("INSERT INTO questions(question_id,course_id,format,stem_md,options,origin,fingerprint,key_state) VALUES(?,?,'SINGLE_CHOICE',?,?,'COACH',?,'VERIFIED')",id,course,q.stem,LedgerJson.encodeToString(q.options),fp)
            sql("INSERT INTO question_kp(question_id,kp_id,is_primary) VALUES(?,?,1)",id,kp)
            sql("INSERT INTO answer_keys(question_id,key_json,reviewed_by,reviewed_at) VALUES(?,?,?,?)",id,json("correctOptionId" to q.correctOptionId).toString(),q.reviewer,clock())
            sql("INSERT INTO pool_details VALUES(?,?,?,?,?,?)",id,job.packHash,q.explanation,q.sourceQuote,q.generator,q.reviewer)
            inserted++
        }
        inserted
    }
    fun constructedAvailable(course: String, kp: String): Int = query("SELECT count(*) FROM questions q JOIN question_kp k USING(question_id) JOIN constructed_details d USING(question_id) WHERE q.course_id=? AND k.kp_id=? AND q.key_state='VERIFIED' AND q.format<>'ESSAY' AND NOT EXISTS (SELECT 1 FROM events e WHERE e.course_id=q.course_id AND e.type='question_posed' AND json_extract(e.payload,'$.questionId')=q.question_id)",course,kp) { it.getInt(1) }.single()
    fun constructedQuestions(course: String, kp: String): List<JsonObject> = query("SELECT q.question_id,q.stem_md,d.reference_answer,d.rubric,d.rubric_fingerprint FROM questions q JOIN question_kp k USING(question_id) JOIN constructed_details d USING(question_id) WHERE q.course_id=? AND k.kp_id=? AND q.key_state='VERIFIED' AND q.format<>'ESSAY'",course,kp) {
        json("questionId" to it.getString(1),"stem" to it.getString(2),"referenceAnswer" to it.getString(3),"rubric" to LedgerJson.parseToJsonElement(it.getString(4)),"rubricFingerprint" to it.getString(5))
    }
    fun enqueueConstructed(course: String, kp: String, objective: String, excerpt: String): String? = transaction {
        enqueueConstructedInTransaction(course,kp,objective,excerpt)
    }
    private fun enqueueConstructedInTransaction(course: String, kp: String, objective: String, excerpt: String): String? {
        require(excerpt.isNotBlank() && objective.isNotBlank())
        if (projection(course).mastery[kp]?.passed != true || constructedAvailable(course,kp) >= 2) return null
        val hash=sha256((objective+"\u0000"+excerpt).toByteArray(Charsets.UTF_8))
        return supplyJobs(course,kp).firstOrNull { it.packHash==hash && it.kind in setOf("gen_constructed","review_constructed") }?.id
            ?: enqueueJob("gen_constructed",json("course" to course,"kp" to kp,"objective" to objective,"excerpt" to excerpt,"background" to true).toString(),hash)
    }
    fun queueConstructedReviews(token: Fence, job: SupplyJob, questions: List<JsonObject>, generator: String): Boolean = transaction {
        if (!completeJob(token,job.packHash,null)) return@transaction false
        for (q in questions) enqueueJob("review_constructed",JsonObject(job.subject + mapOf("question" to q,"generator" to JsonPrimitive(generator))).toString(),job.packHash)
        true
    }
    fun storeConstructed(token: Fence, job: SupplyJob, question: JsonObject, fingerprint: String, reviewer: String): Boolean = transaction {
        if (!completeJob(token,job.packHash,null)) return@transaction false
        val course=job.subject.str("course"); val kp=job.subject.str("kp"); val stem=question.str("stem")
        val fp=sha256((stem+question.str("reference_answer")+fingerprint).toByteArray(Charsets.UTF_8))
        if (query("SELECT 1 FROM questions WHERE course_id=? AND fingerprint=?",course,fp) { it.getInt(1) }.isNotEmpty()) return@transaction false
        val id=uid()
        sql("INSERT INTO questions(question_id,course_id,format,stem_md,origin,fingerprint,key_state) VALUES(?,?,?,?, 'COACH',?,'VERIFIED')",id,course,if(question["numeric_final"] is JsonObject) "CALCULATION" else "SHORT_ANSWER",stem,fp)
        sql("INSERT INTO question_kp VALUES(?,?,1)",id,kp)
        sql("INSERT INTO answer_keys(question_id,key_json,reviewed_by,reviewed_at) VALUES(?,?,?,?)",id,question.toString(),reviewer,clock())
        sql("INSERT INTO constructed_details VALUES(?,?,?,?,?,?,?)",id,job.packHash,question.str("reference_answer"),question.getValue("rubric").toString(),fingerprint,job.subject.str("generator"),reviewer)
        true
    }

    /** A constructed question is always posed from the reviewed pool, never a client-supplied rubric. */
    fun takeConstructed(course: String, kp: String, attempt: String, purpose: String = "CLOSED_BOOK", thread: String? = null): Event = transaction {
        require(attempt.isNotBlank() && purpose in setOf("PRACTICE","CLOSED_BOOK"))
        events(course).firstOrNull { it.type=="question_posed" && it.payload.str("attemptId")==attempt }?.let {
            require(kp in it.payload.strings("kpIds") && it.payload.str("format")!="SINGLE_CHOICE")
            return@transaction it
        }
        val q=query("""SELECT q.question_id,q.stem_md,q.fingerprint,q.format,d.rubric_fingerprint
            FROM questions q JOIN constructed_details d USING(question_id) JOIN question_kp k USING(question_id)
            WHERE q.course_id=? AND k.kp_id=? AND q.key_state='VERIFIED' AND q.format<>'ESSAY'
            AND NOT EXISTS(SELECT 1 FROM events e WHERE e.course_id=q.course_id AND e.type='question_posed'
                AND json_extract(e.payload,'$.questionId')=q.question_id) ORDER BY q.question_id LIMIT 1""",course,kp) {
            json("questionId" to it.getString(1),"stem" to it.getString(2),"fingerprint" to it.getString(3),
                "format" to it.getString(4),"rubricFingerprint" to it.getString(5))
        }.singleOrNull() ?: throw IllegalArgumentException("构答题池暂空，正在补题")
        append(course,"question_posed",JsonObject(q + json("attemptId" to attempt,"kpIds" to listOf(kp),
            "purpose" to purpose,"keyState" to "VERIFIED","policyVersion" to 1,"posedAt" to clock(),"origin" to "COACH")),
            "attempt:$attempt",thread=thread)
    }

    private fun enqueueConstructedGrade(course: String, attempt: String, question: String, answer: String, thread: String?, finalAnswer: String = "") {
        val frozen=query("""SELECT q.stem_md,k.key_json,d.rubric_fingerprint FROM questions q
            JOIN answer_keys k USING(question_id) JOIN constructed_details d USING(question_id)
            WHERE q.question_id=? AND q.course_id=?""",question,course) {
            val key=LedgerJson.parseToJsonElement(it.getString(2)).jsonObject
            Triple(it.getString(1),key,it.getString(3))
        }.singleOrNull() ?: return
        val subject=json("course" to course,"attempt" to attempt,"questionId" to question,"thread" to thread,
            "answer" to answer,"finalAnswer" to finalAnswer,"question" to JsonObject(frozen.second + ("stem" to JsonPrimitive(frozen.first))),
            "rubricFingerprint" to frozen.third,"deadline" to (clock()+86_400_000))
        enqueueJob("grade_attempt",subject.toString(),frozen.third,sha256(json("answer" to answer,"finalAnswer" to finalAnswer).toString().toByteArray(Charsets.UTF_8)))
        gradeTransition(course,thread,"GRADE")
    }

    fun claimGradeWork(): GradeWork? = transaction {
        // A wall-clock deadline survives shutdown, suspension and restart. Never promote expired drafts.
        sql("UPDATE jobs SET state='failed',last_error='review_deadline_expired' WHERE kind='review_grade' AND state='queued' AND json_extract(subject,'$.deadline')<=?",clock())
        val next=query("""SELECT job_id,kind,subject,question_version,answer_hash,attempts FROM jobs
            WHERE kind IN ('grade_attempt','review_grade') AND state='queued' AND updated_at<=?
            ORDER BY CASE kind WHEN 'grade_attempt' THEN 0 ELSE 1 END,created_at,job_id LIMIT 1""",clock()) {
            GradeWork(it.getString(1),it.getString(2),LedgerJson.parseToJsonElement(it.getString(3)).jsonObject,
                it.getString(4),it.getString(5),it.getInt(6)+1)
        }.singleOrNull() ?: return@transaction null
        claimJob(next.id)?.let { next.copy(token=it) }
    }

    fun persistGradeDraft(work: GradeWork, payload: JsonObject, grader: String): Boolean = transaction {
        if(!completeJob(requireNotNull(work.token),work.fingerprint,work.answerHash)) return@transaction false
        val input=work.subject; val course=input.str("course"); val attempt=input.str("attempt")
        require(payload.str("status")=="AI_UNREVIEWED" && grader.isNotBlank())
        append(course,"attempt_graded",JsonObject(payload + ("attemptId" to JsonPrimitive(attempt))),"attempt:$attempt:grade:draft",thread=input.str("thread").ifBlank { null })
        val review=JsonObject(input + mapOf("draft" to payload,"grader" to JsonPrimitive(grader)))
        enqueueJob("review_grade",review.toString(),work.fingerprint,work.answerHash)
        gradeTransition(course,input.str("thread").ifBlank { null },"REVIEW")
        true
    }

    fun persistGradeReview(work: GradeWork, payload: JsonObject): Boolean = transaction {
        if(clock() >= work.subject.num("deadline")) return@transaction failGradeWork(work,"review_deadline_expired",false).let { false }
        if(!completeJob(requireNotNull(work.token),work.fingerprint,work.answerHash)) return@transaction false
        require(payload.str("decision") in setOf("CONFIRM","REVISE"))
        val input=work.subject
        append(input.str("course"),"grade_reviewed",JsonObject(payload + ("attemptId" to JsonPrimitive(input.str("attempt")))),
            "attempt:${input.str("attempt")}:review",thread=input.str("thread").ifBlank { null })
        gradeTransition(input.str("course"),input.str("thread").ifBlank { null },"FEEDBACK")
        true
    }

    fun failGradeWork(work: GradeWork, reason: String, retry: Boolean = true): Boolean = transaction {
        val token=requireNotNull(work.token)
        if(token.epoch != hello().epoch) return@transaction false
        val again=retry && work.attempt <= 3 && clock() < work.subject.num("deadline")
        val delay=when(work.attempt) { 1 -> 60_000L; 2 -> 3_600_000L; else -> 21_600_000L }
        sql("UPDATE jobs SET state=?,last_error=?,updated_at=? WHERE job_id=? AND state='running' AND fence=?",
            if(again) "queued" else "failed",reason,if(again) minOf(clock()+delay,work.subject.num("deadline")) else clock(),work.id,token.value)==1
    }

    /** Full frozen question and all grading facts are returned only after an answer exists. */
    fun constructedRecord(course: String, attempt: String): JsonObject? {
        val es=events(course).filter { it.payload.str("attemptId")==attempt }
        val answer=es.firstOrNull { it.type=="answer_submitted" } ?: return null
        val posed=es.firstOrNull { it.type=="question_posed" } ?: return null
        // Restore re-enqueues pending grades, so one attempt can own several grade_attempt rows.
        val job=query("SELECT subject FROM jobs WHERE kind='grade_attempt' AND json_extract(subject,'$.course')=? AND json_extract(subject,'$.attempt')=? ORDER BY created_at DESC,job_id DESC LIMIT 1",course,attempt) {
            LedgerJson.parseToJsonElement(it.getString(1)).jsonObject
        }.firstOrNull() ?: return null
        // An essay's reference answer and source stay on the Mac: the learner gets a rewrite of their own essay instead.
        val frozen=job.getValue("question").jsonObject
        val question=if(posed.payload.str("format")=="ESSAY") JsonObject(frozen - setOf("reference_answer","essay")) else frozen
        return json("posed" to posed.payload,"answer" to answer.payload,"question" to question,
            "rubricFingerprint" to job.str("rubricFingerprint"),"deadline" to job.num("deadline"),
            "jobs" to JsonArray(query("SELECT kind,state,attempts,last_error,updated_at FROM jobs WHERE kind IN ('grade_attempt','review_grade') AND json_extract(subject,'$.course')=? AND json_extract(subject,'$.attempt')=? ORDER BY created_at,job_id",course,attempt) {
                json("kind" to it.getString(1),"state" to it.getString(2),"attempts" to it.getInt(3),"lastError" to it.getString(4),"updatedAt" to it.getLong(5))
            }),"grades" to JsonArray(es.filter { it.type in setOf("attempt_graded","grade_reviewed") }.map { it.payload }))
    }

    fun createMock(session: MockSession) = sql("INSERT INTO mock_sessions(mock_id,course_id,kind,purpose,paper_json,started_at,deadline) VALUES(?,?,?,?,?,?,?)",
        session.id,session.course,session.kind,session.purpose,session.paper.toString(),session.startedAt,session.deadline)
    fun mock(id: String): MockSession? = mockRows("mock_id=?",id).singleOrNull()
    /** Newest first; drill sampling reads recent sessions to avoid repeating the same questions. */
    fun mocks(course: String, limit: Int = 50): List<MockSession> = mockRows("course_id=? ORDER BY started_at DESC LIMIT ?",course,limit)
    fun courseSeq(course: String): Long = query("SELECT coalesce(max(seq),0) FROM events WHERE course_id=?",course) { it.getLong(1) }.single()
    fun openMocks(): List<MockSession> = mockRows("submitted_at IS NULL ORDER BY deadline")
    private fun mockRows(where: String, vararg values: Any?) = query("SELECT mock_id,course_id,kind,purpose,paper_json,started_at,deadline,answers_json,submitted_at,auto,answers_seq FROM mock_sessions WHERE $where",*values) {
        MockSession(it.getString(1),it.getString(2),it.getString(3),it.getString(4),LedgerJson.parseToJsonElement(it.getString(5)).jsonObject,
            it.getLong(6),it.getLong(7),LedgerJson.parseToJsonElement(it.getString(8)).jsonObject,(it.getObject(9) as? Number)?.toLong(),it.getInt(10)==1,it.getLong(11))
    }
    /** With [seq], a save numbered no higher than the last stored one is dropped: a request that arrives late cannot bring back older answers. */
    fun saveMockAnswers(id: String, answers: JsonObject, seq: Long? = null): Boolean =
        if (seq == null) sql("UPDATE mock_sessions SET answers_json=? WHERE mock_id=? AND submitted_at IS NULL",answers.toString(),id)==1
        else sql("UPDATE mock_sessions SET answers_json=?,answers_seq=? WHERE mock_id=? AND submitted_at IS NULL AND answers_seq<?",answers.toString(),seq,id,seq)==1
    /** Only the first submission wins; manual and deadline submissions cannot both grade. */
    fun closeMock(id: String, at: Long, auto: Boolean): Boolean = sql("UPDATE mock_sessions SET submitted_at=?,auto=? WHERE mock_id=? AND submitted_at IS NULL",at,if(auto) 1 else 0,id)==1
    fun questionRegistered(id: String): Boolean = query("SELECT 1 FROM questions WHERE question_id=?",id) { true }.isNotEmpty()

    private fun gradeTransition(course: String, thread: String?, state: String) {
        if(thread==null) return
        val previous=events(course).lastOrNull { it.threadId==thread && it.type=="tutor_state_changed" } ?: return
        append(course,"tutor_state_changed",json("kpId" to previous.payload.str("kpId"),"from" to previous.payload.str("state"),"state" to state,"turnId" to ""),thread=thread)
    }

    private fun unseenPoolIds(course: String, kp: String): List<String> = query(
        """SELECT q.question_id FROM questions q JOIN question_kp k USING(question_id)
           WHERE q.course_id=? AND k.kp_id=? AND q.format='SINGLE_CHOICE' AND q.key_state='VERIFIED'
           AND NOT EXISTS (SELECT 1 FROM offline_reservations r WHERE r.question_id=q.question_id AND r.expires_at>?)
           AND NOT EXISTS (SELECT 1 FROM events e WHERE e.course_id=q.course_id AND e.type='question_posed'
               AND json_extract(e.payload,'$.questionId')=q.question_id)
           ORDER BY q.rowid""",course,kp,clock()) { it.getString(1) }
    fun poolAvailable(course: String, kp: String): Int = unseenPoolIds(course,kp).size
    fun poolStems(course: String, kp: String): List<String> = query(
        "SELECT stem_md FROM questions JOIN question_kp USING(question_id) WHERE course_id=? AND kp_id=?",course,kp) { it.getString(1) }

    /**
     * 复习变式的候选：就是主线选题用的那条池子（同课程、同考点、单选、已核键、还没出过题）。
     * 池子里没有就返回空，由队列保持「缺题」；这里绝不补题、不改出题状态。
     */
    fun reviewVariantCandidates(course: String, kp: String): List<VariantCandidate> =
        unseenPoolIds(course, kp).mapNotNull { id ->
            query("SELECT fingerprint FROM questions WHERE course_id=? AND question_id=?", course, id) { it.getString(1) }
                .singleOrNull()?.let { fingerprint ->
                    VariantCandidate(course, kp, QueueQuestion(id, null, fingerprint.ifBlank { null }),
                        QueueAvailability.VALID, keyVerified = true)
                }
        }

    /**
     * The only client-uncopyable proof that a posed fact came from [takeReviewVariant]: this store poses it
     * inside its own transaction, so it has no command id, owns the exact review thread and group, and its
     * review identity recomputes to itself. A client-written snapshot cannot produce any of that, because
     * every command-path emit carries a command id.
     */
    private fun provenExactPose(e: Event, course: String, attempt: String): Boolean {
        if (e.type != "question_posed" || e.commandId != null) return false
        if (e.idemKey != "attempt:$attempt" || e.threadId != "review-practice:$course:$attempt") return false
        if (e.payload.str("groupId") != "review:$attempt" || !e.payload.flag("exactReview")) return false
        val identity = e.payload.str("reviewIdentity")
        if (identity.isBlank()) return false
        val kp = e.payload.strings("kpIds").singleOrNull() ?: return false
        val recomputed = canonicalJson(json("course" to course,"device" to e.deviceId,"run" to null,"kp" to kp,
            "entry" to e.payload.str("reviewEntryId"),"attempt" to attempt,"question" to e.payload.str("questionId"),
            "fingerprint" to e.payload.str("fingerprint")))
        return recomputed == identity
    }

    /** Exact selection, consumption and one frozen posed fact share the actor-owned transaction. */
    fun takeReviewVariant(course: String, device: String, run: String?, kp: String, entryId: String,
        attemptId: String, expected: QueueQuestion): List<Event> = transaction {
        fun refuse(code: String): Nothing = throw ReviewVariantRejected(code)
        if (course.isBlank() || course == "__system__" || kp.isBlank() || entryId.isBlank() || attemptId.isBlank()) refuse("INVALID_REVIEW_REQUEST")
        if (query("SELECT 1 FROM devices WHERE device_id=? AND revoked_at IS NULL", device) { true }.isEmpty()) refuse("REVIEW_DEVICE_UNAUTHORIZED")
        // The current board is learner-only; never relabel its entries as agent evidence.
        if (run != null) refuse("REVIEW_SCOPE_MISMATCH")
        val question = query("SELECT fingerprint,key_state FROM questions q JOIN question_kp k USING(question_id) WHERE q.course_id=? AND q.question_id=? AND k.kp_id=? AND q.format='SINGLE_CHOICE'",course,expected.id,kp) {
            it.getString(1) to it.getString(2)
        }.firstOrNull() ?: refuse("REVIEW_QUESTION_UNAVAILABLE")
        if (question.second != "VERIFIED") refuse("REVIEW_QUESTION_UNAVAILABLE")
        // This pool's authoritative revision is the fingerprint; it has no semantic version column.
        if (expected.version != null || expected.fingerprint.isNullOrBlank() || expected.fingerprint != question.first) refuse("REVIEW_QUESTION_CHANGED")
        val identity = canonicalJson(json("course" to course,"device" to device,"run" to run,"kp" to kp,
            "entry" to entryId,"attempt" to attemptId,"question" to expected.id,"fingerprint" to expected.fingerprint))
        val facts = events(course)
        val previous = facts.filter { it.type == "question_posed" && it.payload.str("attemptId") == attemptId }
        if (previous.isNotEmpty()) {
            val pose = previous.singleOrNull() ?: refuse("REVIEW_IDENTITY_CONFLICT")
            if (pose.deviceId != device) refuse("REVIEW_SCOPE_MISMATCH")
            // A replay may only land on a pose this store allocated; a client-written exactReview is not proof.
            if (!provenExactPose(pose, course, attemptId) || pose.payload.str("reviewIdentity") != identity) refuse("REVIEW_IDENTITY_CONFLICT")
            if (facts.any { it.type == "attempt_voided" && it.payload.str("attemptId") == attemptId }) refuse("REVIEW_QUESTION_UNAVAILABLE")
            return@transaction previous
        }
        // Another attempt already owns this review group; refuse as a conflict instead of failing inside pose allocation.
        if (facts.any { it.type == "question_posed" && it.payload.str("groupId") == "review:$attemptId" }) refuse("REVIEW_IDENTITY_CONFLICT")
        val state = projection(course)
        if (state.requiresServerSnapshot) refuse("REVIEW_SNAPSHOT_REQUIRED")
        val day = logicalDay(clock(), state.timeZone)
        val entry = ledgerQueueCandidate(course, facts, day).dueEntries(day).find { it.id == entryId && it.kpId == kp }
            ?: refuse("REVIEW_ENTRY_UNAVAILABLE")
        if (entry.originalQuestion?.id == expected.id || expected.id !in unseenPoolIds(course, kp)) refuse("REVIEW_QUESTION_UNAVAILABLE")
        val request = json("attemptId" to attemptId,"questionId" to expected.id,"kpIds" to listOf(kp),
            "purpose" to "RECALL","policyVersion" to 1,"review" to true,"posedAt" to clock(),
            "availability" to "VALID","exactReview" to true,"reviewEntryId" to entryId,"reviewIdentity" to identity)
        // The posed fact itself consumes this question for every selector, atomically.
        poseChoiceGroupInTransaction(course, "review:$attemptId", listOf(request), Random.Default, device,
            offline = false, thread = "review-practice:$course:$attemptId")
    }

    /** No model output can produce a card. Selection, consumption and question_posed are atomic. */
    fun takePoolGroup(course: String, kp: String, groupId: String, size: Int = 3,
        purpose: String = "PRACTICE", untaught: Boolean = false, thread: String? = null, review: Boolean = false): PoolTake = transaction {
        require(size in setOf(1,3) && groupId.isNotBlank())
        require(purpose in Purpose.entries.map { it.name })
        val prior=events(course).filter { it.type == "question_posed" && it.payload.str("groupId") == groupId }
        if (prior.isNotEmpty()) {
            require(prior.size == size && prior.all { it.payload.strings("kpIds") == listOf(kp) }) { "Group identity mismatch" }
            return@transaction PoolTake(prior,"READY",poolAvailable(course,kp),emptyList(),null)
        }
        val ids=unseenPoolIds(course,kp)
        val jobs=supplyJobs(course,kp).filter { it.kind in setOf("gen_mcq","blind_review") }.map { it.id to jobState(it.id)!!.first }
        if (ids.size < size) return@transaction PoolTake(emptyList(),if(jobs.isEmpty()) "EMPTY" else "REFILLING",ids.size,jobs,
            if(jobs.isEmpty()) "题池暂空，补题尚未运行" else "正在准备题目（约 30 秒，含独立复核；重试可能更久）")
        // A fallback is a successful reserve take caused by an actual, not-yet-consumed failure.
        val failure=query("""SELECT j.job_id,j.last_error FROM jobs j
            WHERE j.kind IN ('gen_mcq','blind_review') AND j.state='failed' AND json_valid(j.subject)
            AND json_extract(j.subject,'$.course')=? AND json_extract(j.subject,'$.kp')=?
            AND NOT EXISTS (SELECT 1 FROM events e WHERE e.course_id=? AND e.idem_key='fallback-job:'||j.job_id)
            ORDER BY j.updated_at DESC,j.rowid DESC LIMIT 1""",course,kp,course) { it.getString(1) to it.getString(2) }.singleOrNull()
        if (failure != null) append(course,"fallback_used",json("groupId" to groupId,"kpId" to kp,
            "jobId" to failure.first,"from" to "TASK","reason" to failure.second),"fallback-job:${failure.first}",thread=thread)
        val requests=ids.take(size).map { id -> json("questionId" to id,"attemptId" to uid(),
            "kpIds" to listOf(kp),"purpose" to purpose,"policyVersion" to 1,"untaught" to untaught,"review" to review) }
        val posed=poseChoiceGroupInTransaction(course,groupId,requests,Random.Default,"server",false,thread)
        PoolTake(posed,"READY",ids.size-size,jobs,null)
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
        // The interrupted claim never reached a result; it must not use up the job's retry budget.
        sql("UPDATE jobs SET state='queued',fence=fence+1,attempts=MAX(attempts-1,0),updated_at=? WHERE state='running'",clock())
        sql("DELETE FROM turn_leases")
    }
    fun startMessage(thread: String, message: String, content: String = "") {
        beginStreamingMessage(thread,message,"")
        updateStreamingMessage(message,content,0)
    }
    fun messageStatus(id: String): Pair<String,String?>? = query("SELECT status,reason FROM messages WHERE message_id=?",id) { it.getString(1) to it.getString(2) }.singleOrNull()

    /** 流式消息的一生：streaming 立即可见（§4.2），每秒把已可见正文与 `(msg, i)` 计数一起落库。 */
    fun beginStreamingMessage(thread: String, message: String, model: String, role: String = "assistant") {
        // The immutable namespaced thread persists the run ID; mode persists the readable label.
        sql("INSERT INTO messages(thread_id,mseq,message_id,role,model,status,content_md,delta_index,created_at,mode) VALUES(?,coalesce((SELECT max(mseq)+1 FROM messages WHERE thread_id=?),1),?,?,?,'streaming','',0,?,?)",
            thread,thread,message,role,model,clock(),if (agentRunId(thread) == null) null else AGENT_RUN_LABEL)
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
    fun recentTurns(thread: String, limit: Int = 12, beforeMessage: String? = null): List<Pair<String,String>> =
        query("SELECT role,coalesce(content_md,'') FROM messages WHERE thread_id=? AND status IN ('complete','truncated') AND (? IS NULL OR mseq<(SELECT mseq FROM messages WHERE message_id=?)) ORDER BY mseq DESC LIMIT ?",thread,beforeMessage,beforeMessage,limit) {
            (it.getString(1) ?: "assistant") to it.getString(2)
        }.reversed()

    /** `ai_calls`（§5.7）：只记长度与耗时，不记内容。 */
    fun recordAiCall(callId: String, task: String, requestedModel: String, servedBy: String?, ttftMs: Long?,
        durMs: Long, inChars: Int, outChars: Int, status: String, queueMs: Long?, physicalCalls: Int,
        requestType: String = "dev_live") {
        sql("INSERT OR REPLACE INTO ai_calls(call_id,task,requested_model,served_by,ttft_ms,dur_ms,in_chars,out_chars,status,request_type,effort,pack_chars,queue_ms,physical_calls,cache_read,cache_create) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            callId,task,requestedModel,servedBy,ttftMs,durMs,inChars,outChars,status,
            requestType,null,null,queueMs,physicalCalls,null,null)
    }

    /** Import API keeps both language indexes in the same transaction as the source page. */
    fun registerSourcePage(course: String, doc: String, title: String, page: Int, text: String,
        tutorVisible: Boolean = true, kind: String = "textbook", learnerVisible: Boolean = true) = transaction {
        require(page > 0 && doc.isNotBlank())
        val owner = query("SELECT course_id FROM source_docs WHERE doc_id=?",doc) { it.getString(1) }.singleOrNull()
        require(owner == null || owner == course) { "Source belongs to another course" }
        sql("INSERT INTO source_docs(doc_id,course_id,kind,title,tutor_visible,learner_visible) VALUES(?,?,?,?,?,?) ON CONFLICT(doc_id) DO UPDATE SET title=excluded.title,kind=excluded.kind,tutor_visible=excluded.tutor_visible,learner_visible=excluded.learner_visible",
            doc,course,kind,title,if(tutorVisible) 1 else 0,if(learnerVisible) 1 else 0)
        sql("INSERT INTO source_pages(doc_id,pdf_page,text,text_sha256) VALUES(?,?,?,?) ON CONFLICT(doc_id,pdf_page) DO UPDATE SET text=excluded.text,text_sha256=excluded.text_sha256",doc,page,text,sha256(text.toByteArray()))
    }

    /**
     * Read-only course text for the learner's Resources screen. Only documents the tutor may also see are listed: answer keys and
     * sealed material stay out of every learner-facing surface, so browsing cannot leak a first-answer key.
     */
    fun browsableSources(course: String): JsonArray = JsonArray(query(
        """SELECT d.doc_id,d.title,d.kind,min(p.pdf_page),max(p.pdf_page),min(p.book_page),max(p.book_page) FROM source_docs d
           JOIN source_pages p ON p.doc_id=d.doc_id WHERE d.course_id=? AND d.learner_visible=1 GROUP BY d.doc_id ORDER BY min(p.pdf_page),d.title""",course) {
        if (!tutorSourceAllowed(it.getString(1),it.getString(2).orEmpty(),it.getString(3).orEmpty())) null
        else json("docId" to it.getString(1),"title" to it.getString(2),"kind" to it.getString(3),"firstPdfPage" to it.getInt(4),"lastPdfPage" to it.getInt(5),
            "firstBookPage" to it.getObject(6)?.let { _ -> it.getInt(6) },"lastBookPage" to it.getObject(7)?.let { _ -> it.getInt(7) })
    }.filterNotNull())

    fun browsablePage(course: String, doc: String, pdfPage: Int): JsonObject? = query(
        """SELECT d.doc_id,d.title,d.kind,p.book_page,p.text FROM source_pages p JOIN source_docs d ON d.doc_id=p.doc_id
           WHERE d.course_id=? AND d.doc_id=? AND p.pdf_page=? AND d.learner_visible=1""",course,doc,pdfPage) {
        if (!tutorSourceAllowed(it.getString(1),it.getString(2).orEmpty(),it.getString(3).orEmpty())) null
        else json("docId" to it.getString(1),"pdfPage" to pdfPage,"bookPage" to it.getObject(4)?.let { _ -> it.getInt(4) },"text" to it.getString(5))
    }.singleOrNull()

    /** Recheck the exact evidence snapshot before releasing a delayed reader answer. */
    fun readerSourceCurrent(course: String, page: SourcePage, scope: com.coach.domain.scope.VisibleScope): Boolean {
        if (!page.learnerVisible || page.pdfPage <= 0 || !scope.permits(page.docId, page.pdfPage, page.learnerVisible)) return false
        val visible = browsablePage(course, page.docId, page.pdfPage) ?: return false
        if (visible.str("text") != page.text) return false
        return query("SELECT blob_sha256 FROM source_docs WHERE course_id=? AND doc_id=? AND tutor_visible=1",
            course, page.docId) { it.getString(1) == page.sourceSha256 }.singleOrNull() == true
    }

    /** Reviewed (VERIFIED) constructed questions per knowledge point; a knowledge point missing here has none, and the stable layer is out of reach. */
    fun constructedSupply(course: String): Map<String,Int> = query(
        """SELECT k.kp_id,count(*) FROM questions q JOIN constructed_details d USING(question_id) JOIN question_kp k USING(question_id)
           WHERE q.course_id=? AND q.key_state='VERIFIED' AND q.format<>'ESSAY' GROUP BY k.kp_id""",course) { it.getString(1) to it.getInt(2) }.toMap()

    /** FTS syntax comes from escaped tokens, never from a model or a raw MATCH expression. */
    fun searchSourcePages(course: String, question: String, scope: com.coach.domain.scope.VisibleScope): List<SourcePage> {
        val unique = matchSourcePages(course, question).filter { scope.permits(it.docId, it.pdfPage, it.learnerVisible) }
        return (unique.filter { it.learnerVisible }.sortedByDescending { it.score } +
            unique.filterNot { it.learnerVisible }.sortedByDescending { it.score }.take(2)).take(6)
    }

    /** Every tutor-visible page of the course matching the query, best score per page, before any scope or page budget is applied. */
    private fun matchSourcePages(course: String, question: String): List<SourcePage> {
        val terms = Regex("[\\p{IsHan}]{3,}|[A-Za-z0-9]{2,}").findAll(question.take(2000))
            .flatMap { match ->
                val value = match.value
                if (value.first().code in 0x4e00..0x9fff) value.windowed(3).asSequence() else sequenceOf(value)
            }.filter { it.lowercase() !in setOf("the","is","of","a","an","what","how","in","to","and","does","why") }.distinct().take(32).toList()
        if (terms.isEmpty()) return emptyList()
        val found = mutableListOf<SourcePage>()
        for (table in listOf("fts_en","fts_zh")) {
            val expression = terms.filter { table == "fts_en" || it.length >= 3 }.joinToString(" OR ") { "\"$it\"" }
            if (expression.isBlank()) continue
            found += query("SELECT p.doc_id,p.pdf_page,p.text,bm25($table),d.title,d.kind,d.learner_visible,d.blob_sha256 FROM $table JOIN source_pages p ON p.doc_id=$table.doc_id AND p.pdf_page=$table.pdf_page JOIN source_docs d ON d.doc_id=p.doc_id WHERE $table MATCH ? AND d.course_id=? AND d.tutor_visible=1 ORDER BY bm25($table)",expression,course) {
                SourcePage(it.getString(1),it.getInt(2),it.getString(3),-it.getDouble(4),
                    it.getInt(7) == 1 && tutorSourceAllowed(it.getString(1),it.getString(5).orEmpty(),it.getString(6).orEmpty()), it.getString(8))
            }
        }
        return found.groupBy { it.key }.values.map { it.maxBy { page -> page.score } }
    }

    /** Texts of protected pages matching a query. For OUTPUT GUARDS ONLY: they must never be placed in a model request. */
    fun restrictedForGuard(course: String, question: String): List<String> =
        // Independent of the public pack's page budget and uncapped: every protected page this query matches is known to the guard.
        matchSourcePages(course, question).filterNot { it.learnerVisible }.sortedByDescending { it.score }.map { it.text }

    /** Outline anchors per active knowledge point, in outline order (module order, then point order). */
    /** Knowledge point ids are compared trimmed and case-folded so a cosmetic mismatch between facts and outline does not disable a course. */
    private fun kpKey(id: String) = id.trim().lowercase()

    private fun outlineAnchors(course: String): List<Pair<String, List<Pair<String, Int>>>> {
        val moduleOrder = runCatching { query("SELECT module_id,ord FROM course_modules WHERE course_id=?", course) { it.getString(1) to it.getInt(2) }.toMap() }.getOrDefault(emptyMap())
        return query("SELECT kp_id,module_id,ord,anchors FROM outline_kp WHERE course_id=? AND coalesce(retired,0)=0", course) {
            val anchors = runCatching { LedgerJson.parseToJsonElement(it.getString(4) ?: "[]").jsonArray.map { a -> a.jsonObject.str("docId") to a.jsonObject.num("pdfPage").toInt() } }.getOrDefault(emptyList())
            Triple(kpKey(it.getString(1)), (moduleOrder[it.getString(2)] ?: 0) * 100000 + it.getInt(3), anchors)
        }.sortedBy { it.second }.map { it.first to it.third }
    }

    /**
     * Trusted "already studied" ceiling, derived from the learning record: knowledge points with these ids are learned, every other
     * active point is not. Fails closed ([VisibleScope.NONE]) when nothing learned can be mapped to source pages.
     */
    fun learnedScope(course: String, base: com.coach.domain.scope.VisibleScope, learnedKps: Set<String>): com.coach.domain.scope.VisibleScope {
        val all = outlineAnchors(course)
        val keys = learnedKps.map(::kpKey).toSet()
        val learned = all.filter { it.first in keys }.flatMap { it.second }
        if (learned.isEmpty()) return com.coach.domain.scope.VisibleScope.NONE
        val unlearned = all.filter { it.first !in keys }.flatMap { it.second }
        // Only documents the learned points are anchored in take part; a document without outline anchors cannot be placed, so it is closed.
        return base.narrowToDocs(*learned.map { it.first }.distinct().toTypedArray())
            .limitPages(com.coach.domain.scope.VisibleScope.learnedWindows(learned, unlearned))
    }

    /** Learned = every point up to and including [currentKp] in outline order. */
    fun learnedThrough(course: String, base: com.coach.domain.scope.VisibleScope, currentKp: String?): com.coach.domain.scope.VisibleScope =
        learnedThrough(course, base, listOfNotNull(currentKp))

    /** Several current points (one reading section may cite more than one): the latest of them in outline order bounds what is learned. */
    fun learnedThrough(course: String, base: com.coach.domain.scope.VisibleScope, currentKps: Collection<String>): com.coach.domain.scope.VisibleScope {
        val order = outlineAnchors(course).map { it.first }
        val idx = currentKps.maxOfOrNull { order.indexOf(kpKey(it)) } ?: -1
        if (idx < 0) return com.coach.domain.scope.VisibleScope.NONE
        return learnedScope(course, base, order.take(idx + 1).toSet())
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

data class MockSession(val id: String, val course: String, val kind: String, val purpose: String, val paper: JsonObject,
    val startedAt: Long, val deadline: Long, val answers: JsonObject = JsonObject(emptyMap()), val submittedAt: Long? = null, val auto: Boolean = false,
    val answersSeq: Long = 0)
