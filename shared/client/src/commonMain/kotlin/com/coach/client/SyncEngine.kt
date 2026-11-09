package com.coach.client

import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import kotlin.time.TimeSource
import kotlin.time.TimeMark

interface LedgerTransport {
    suspend fun hello(epoch: String?): Hello
    suspend fun snapshot(course: String): Snapshot
    suspend fun events(course: String, after: Long): EventsPage
    suspend fun command(command: WireCommand): Receipt
    suspend fun offlinePack(course: String, size: Int): OfflineChoicePack = error("Offline packs unavailable")
    suspend fun watch(onLedger: suspend () -> Unit)
    /** 同 [watch]；连接真正建立（握手完成）时调用 [onOpen]。重连退避只按建立之后的时长算，握手再慢也不算「用过」。 */
    suspend fun watch(onOpen: () -> Unit, onLedger: suspend () -> Unit) { onOpen(); watch(onLedger) }
}

/** Device-specific immutable outbox and disposable ledger replica. Database survives process restarts. */
class SyncEngine(private val db: ClientDatabase, val deviceId: String,
    private val transport: LedgerTransport, private val courses: List<String>,
    private val newId: () -> String, private val hash: (ByteArray) -> String,
    private val retentionTimeSource: TimeSource = TimeSource.Monotonic) {
    companion object {
        // Server retention: 14 daily days + the preceding 8 weekly weeks.
        const val OUTBOX_RETENTION_MILLIS = 70L * 24 * 60 * 60 * 1000
    }
    private data class RetentionAnchor(val generation: Long, val age: Long, val mark: TimeMark)
    private var retentionAnchors = emptyMap<String, RetentionAnchor>()
    private val mutex = Mutex()
    private val _connection = MutableStateFlow("offline")
    val connection = _connection.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    val epoch: String? get() = meta("epoch")
    private val q get() = db.ledgerQueries
    private val cache=ProjectionCache(db)
    /** Device-local reading/answer state, retained independently of disposable ledger snapshots. */
    fun mainlineState(course: String, page: String = "active"): JsonObject =
        meta("mainline:v1:$course:$page")?.let { runCatching { LedgerJson.parseToJsonElement(it).jsonObject }.getOrNull() } ?: json()
    fun saveMainlineState(course: String, state: JsonObject, page: String = "active") {
        q.putMeta("mainline:v1:$course:$page", state.toString())
    }
    /** Pairing identity scopes personal marks; they are never uploaded as course evidence. */
    internal fun readerState(course: String, week: Int, identity: String): JsonObject =
        mainlineState(course, "reader:${hash(identity.encodeToByteArray())}:$week")
    internal fun saveReaderState(course: String, week: Int, identity: String, state: JsonObject) =
        saveMainlineState(course, state, "reader:${hash(identity.encodeToByteArray())}:$week")
    fun enqueue(body: CommandBody): WireCommand {
        val bytes = canonicalJson(LedgerJson.encodeToJsonElement(body))
        val c = WireCommand(deviceId,newId(),bytes,hash(bytes.encodeToByteArray()))
        // Serialize/hash exactly once, before acknowledging the tap to the UI.
        q.putCommand(c.commandId,c.deviceId,c.payloadBytes,c.payloadSha256)
        return c
    }
    /** The UI receives cards only. Keys stay private and SQL only returns one after durable lock. */
    suspend fun downloadOfflinePack(course: String, size: Int = 10) = mutex.withLock {
        val pack = transport.offlinePack(course,size)
        require(pack.deviceId == deviceId && pack.courseId == course)
        db.transaction {
            q.putOfflinePack(pack.packId,course,pack.expiresAt,pack.epoch)
            pack.groups.flatten().forEachIndexed { index, event ->
                q.putOfflineItem(event.payload.str("attemptId"),pack.packId,event.payload.str("groupId"),index.toLong(),LedgerJson.encodeToString(event))
            }
            pack.keys.forEach { q.putOfflineKey(it.attemptId,it.correctOptionId) }
        }
        _revision.value += 1
    }

    fun offlineGroups(course: String, now: Long): List<QuizGroup> {
        val facts = localEvents(course)
        val grades = facts.filter { it.type == "attempt_graded" }.associateBy { it.payload.str("attemptId") }
        return q.offlineItems(course).executeAsList().filter { it.command_id != null || (it.expires_at > now && it.epoch == epoch) }
            .groupBy { it.group_id }.map { (id, rows) ->
                QuizGroup(id, rows.map { row ->
                    val p = LedgerJson.decodeFromString<Event>(row.posed_json).payload
                    val map = (p["letterMap"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
                    val options = (p["options"] as? JsonArray).orEmpty().map { it.jsonObject }.map { map.getValue(it.str("optionId")) to it.str("text") }.sortedBy { it.first }
                    val authoritative = grades[row.attempt_id]?.payload
                    val local = row.local_correct?.let { it == 1L }
                    val receipt = row.command_id?.let(::receipt)
                    val problem = when {
                        receipt?.status in setOf("REJECTED","CONFLICT") -> "服务端未计分，答案已保留"
                        facts.any { it.type == "answer_orphaned" && it.payload.str("attemptId") == row.attempt_id } -> "题目已失效，答案已保留、不计分"
                        else -> null
                    }
                    val grade = if (authoritative != null) {
                        val correct = authoritative.num("score") >= authoritative.num("max")
                        CardGrade(correct,authoritative.num("score").toInt(),authoritative.num("max").toInt(),authoritative.str("feedback"),
                            if (local != null && local != correct) "服务端已更正，以此结果为准" else "服务端已确认")
                    } else if (local != null) CardGrade(local,if(local) 1 else 0,1,
                        q.offlineKeyAfterLock(row.attempt_id).executeAsOneOrNull()?.let { choiceFeedback(it,row.answer.orEmpty(),map) }.orEmpty(),
                        problem ?: "本地判分，联网后确认") else null
                    QuizCard(row.attempt_id,p.str("stem"),options,p.flag("untaught"),row.answer,grade)
                })
            }
    }

    /** All members lock with their immutable outbox bytes in one transaction, before reading any key. */
    fun submitOfflineGroup(course: String, groupId: String, answers: Map<String,String>, now: Long) {
        db.transaction {
            val rows = q.offlineItems(course).executeAsList().filter { it.group_id == groupId }
            require(rows.isNotEmpty() && rows.all { it.command_id == null && it.expires_at > now && it.epoch == epoch }) { "题组已提交或已过期" }
            require(answers.keys == rows.map { it.attempt_id }.toSet())
            for (row in rows) {
                val p = LedgerJson.decodeFromString<Event>(row.posed_json).payload
                val answer = if (p.str("format") == "SINGLE_CHOICE") answers.getValue(row.attempt_id).trim().uppercase() else answers.getValue(row.attempt_id)
                if (p.str("format") == "SINGLE_CHOICE") require(answer in (p["letterMap"] as JsonObject).values.map { it.jsonPrimitive.content })
                val command = enqueue(CommandBody("submit_answer",course,now,json("attemptId" to row.attempt_id,"answer" to answer,"offline" to true,"posedSnapshot" to p)))
                q.lockOfflineAnswer(answer,command.commandId,row.attempt_id)
            }
            for (row in rows) {
                val key = q.offlineKeyAfterLock(row.attempt_id).executeAsOneOrNull() ?: continue // Constructed answers wait for server grading.
                val p = LedgerJson.decodeFromString<Event>(row.posed_json).payload
                val correct = (p["letterMap"] as JsonObject)[key]?.jsonPrimitive?.content == answers.getValue(row.attempt_id).trim().uppercase()
                q.putLocalGrade(if(correct) 1L else 0L,row.attempt_id)
            }
        }
        _revision.value += 1
    }

    fun retainedCommands(): List<WireCommand> = q.allCommands().executeAsList().map { WireCommand(it.device_id,it.command_id,it.payload_bytes,it.payload_sha256) }
    fun localEvents(course: String): List<Event> = q.allEvents(course).executeAsList().map { LedgerJson.decodeFromString(it) }
    fun projection(course: String): Projection = db.transactionWithResult {
        val epoch=meta("epoch").orEmpty()
        val seq=q.replicaSeq(course).executeAsOne()
        cache.get(course,epoch,seq)?.let { return@transactionWithResult it }
        val local=project(localEvents(course))
        val snapshot=if(local.requiresServerSnapshot) meta("serverProjection:$course")?.let { LedgerJson.decodeFromString<Projection>(it) } else null
        val state=snapshot?.copy(lastSeq=seq,requiresServerSnapshot=true,unsupportedTypes=local.unsupportedTypes) ?: local
        cache.put(course,epoch,seq,state)
        state
    }
    fun receipt(commandId: String): Receipt? = meta("receipt:$commandId")?.let { LedgerJson.decodeFromString(it) }
    private fun meta(key: String) = q.getMeta(key).executeAsOneOrNull()
    private fun save(events: List<Event>) { for(e in events) q.putEvent(e.seq,e.courseId,LedgerJson.encodeToString(e)) }

    /** Each row's persisted age is a lower bound on time since this coverage.
     * Anchor only AFTER reading that age, never at engine construction or hello.
     * age + elapsed remains a lower bound; MAX merges overlapping observers without
     * addition. A generation changes on every new coverage, even in the same epoch.
     * No shared clock offset can jump and instantly age another engine's coverage. */
    private fun retainOrPrune(expectedEpoch: String, backedUpThroughSeq: Long) {
        val nextAnchors = db.transactionWithResult {
            // Take the SQLite write lock before reading ages, including across processes.
            q.markBackupCovered(backedUpThroughSeq,expectedEpoch)
            check(meta("epoch") == expectedEpoch) { "Epoch changed during retention" }
            val next = mutableMapOf<String, RetentionAnchor>()
            for(row in q.allCommands().executeAsList()) {
                val age = row.backup_retained_millis ?: continue
                val anchor = retentionAnchors[row.command_id]?.takeIf { it.generation == row.backup_coverage_generation }
                if(anchor == null) {
                    next[row.command_id] = RetentionAnchor(row.backup_coverage_generation,age,retentionTimeSource.markNow())
                } else {
                    val elapsed = anchor.mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)
                    val candidate = anchor.age + elapsed.coerceAtMost(OUTBOX_RETENTION_MILLIS - anchor.age)
                    q.advanceBackupAge(candidate,row.command_id,anchor.generation)
                    next[row.command_id] = anchor
                }
            }
            q.prune(backedUpThroughSeq,OUTBOX_RETENTION_MILLIS)
            next
        }
        // A rolled-back coverage must never leave an anchor for a future generation.
        retentionAnchors = nextAnchors
    }

    suspend fun syncOnce() = mutex.withLock {
        val previousEpoch = meta("epoch")
        val hello = transport.hello(previousEpoch)
        if(previousEpoch != hello.epoch) {
            // Fetch every snapshot before committing epoch, so interruption cannot mark an incomplete reset as done.
            val snapshots = courses.map { transport.snapshot(it) }
            check(snapshots.all { it.hello.epoch == hello.epoch }) { "Epoch changed during snapshot" }
            db.transaction {
                q.clearReplica();q.clearProjections()
                // Ack seq belongs to its old epoch. Replay all retained commands before pruning
                // against this epoch's backup watermark; commandId makes existing facts duplicates.
                q.replayAfter(-1)
                for(s in snapshots) { save(s.events); q.putMeta("cursor:${s.courseId}",s.hello.lastSeq.toString());q.putMeta("serverProjection:${s.courseId}",LedgerJson.encodeToString(s.projection)) }
                q.putMeta("epoch",hello.epoch)
            }
        }
        retainOrPrune(hello.epoch,hello.backedUpThroughSeq)
        val pending = q.allCommands().executeAsList().filter { it.ack_seq == null }
        val blocked = mutableListOf<CommandBody?>()
        // Only independent retries may overtake an uncertain command within a course.
        // Include skipped commands as barriers too (e.g. a queued answer before another retry).
        val questions = courses.associateWith { course -> localEvents(course).filter { it.type == "question_posed" }
            .associate { it.payload.str("attemptId") to it.payload.str("questionId") } }
        fun mayOvertake(earlier: CommandBody?, later: CommandBody?): Boolean {
            if (earlier == null || later == null || earlier.courseId.isBlank() || later.courseId.isBlank()) return false
            if (earlier.courseId != later.courseId) return true
            if (earlier.type != "retry_week_mistake" || later.type != "retry_week_mistake") return false
            val ids = questions[earlier.courseId].orEmpty()
            val a = ids[earlier.payload.str("attemptId")].orEmpty()
            val b = ids[later.payload.str("attemptId")].orEmpty()
            return a.isNotBlank() && b.isNotBlank() && a != b
        }
        for(row in pending) {
            val body = try { LedgerJson.decodeFromString<CommandBody>(row.payload_bytes) } catch (_: Exception) { null }
            if (blocked.any { !mayOvertake(it, body) }) { blocked += body; continue }
            // 永久拒收的命令（如换过配对留下的旧设备命令、超过 1 MiB 的笔记）记为 REJECTED，不能挡住后面的命令和拉取。
            val receipt = try { transport.command(WireCommand(row.device_id,row.command_id,row.payload_bytes,row.payload_sha256)) }
                catch(e: CommandRefused) { Receipt(row.command_id,"REJECTED",error="HTTP_${e.status}",epoch=hello.epoch,backedUpThroughSeq=hello.backedUpThroughSeq) }
                catch(e: CommandUploadDeferred) { blocked += body; continue }
            check(receipt.epoch == hello.epoch) { "Epoch changed during upload" }
            db.transaction {
                q.ackCommand(receipt.eventSeqs.maxOrNull() ?: 0,receipt.status,row.command_id)
                check(meta("epoch") == hello.epoch) { "Epoch changed during acknowledgement" }
                q.putMeta("receipt:${row.command_id}",LedgerJson.encodeToString(receipt))
            }
            retainOrPrune(hello.epoch,receipt.backedUpThroughSeq)
        }
        pullCourses(hello.epoch)
        _connection.value = "online"
        _revision.value += 1
    }

    /** Event pages carry their epoch. A push with no outbox work needs one read, not
     * hello + events. Full polling still refreshes backup watermarks and uploads commands. */
    private suspend fun syncHint() {
        val caughtUp=mutex.withLock {
            val epoch=meta("epoch") ?: return@withLock false
            if(q.allCommands().executeAsList().any { it.ack_seq==null }) return@withLock false
            try { pullCourses(epoch) }
            catch(e: EpochChanged) { return@withLock false }
            _connection.value="online"
            _revision.value+=1
            true
        }
        if(!caughtUp) syncOnce()
    }

    private class EpochChanged: IllegalStateException("Epoch changed during catch-up")

    /** Caller holds mutex, for both full synchronization and push catch-up. */
    private suspend fun pullCourses(epoch: String) {
        for(course in courses) {
            var cursor = meta("cursor:$course")?.toLong() ?: 0
            do {
                val page = transport.events(course,cursor)
                if(page.epoch != epoch) throw EpochChanged()
                val quizThreads=if(PoolTrace.enabled) page.events.filter { it.type=="question_posed" }.mapNotNull { it.threadId }.distinct() else emptyList()
                quizThreads.forEach { PoolTrace.mark(it,"events_read") }
                db.transaction { save(page.events);q.putMeta("cursor:$course",page.throughSeq.toString()) }
                quizThreads.forEach { PoolTrace.mark(it,"events_saved") }
                cursor = page.throughSeq
            } while(page.hasMore)
            // 不认识的事实类型要靠服务端投影；整份快照只在本地副本前进后重下，不是每次轮询都下。
            val replicaSeq=q.replicaSeq(course).executeAsOne()
            if(projection(course).requiresServerSnapshot && meta("serverProjectionSeq:$course") != "$epoch:$replicaSeq") {
                val snapshot=transport.snapshot(course)
                if(snapshot.hello.epoch!=epoch) throw EpochChanged()
                db.transaction {
                    q.putMeta("serverProjection:$course",LedgerJson.encodeToString(snapshot.projection))
                    val seq=q.replicaSeq(course).executeAsOne()
                    q.putMeta("serverProjectionSeq:$course","$epoch:$seq")
                    cache.put(course,epoch,seq,snapshot.projection.copy(lastSeq=seq,requiresServerSnapshot=true))
                }
            }
        }
    }
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        // One quiz publishes several durable/state/card hints. Do not make the WS reader
        // wait for a full HTTP catch-up for each hint. Keep one follow-up while a sync is
        // in flight, so facts committed during that request are still fetched.
        val hints=Channel<Unit>(Channel.CONFLATED)
        val pushed=launch {
            for(hint in hints) {
                try { syncHint() }
                catch(e: CancellationException) { throw e }
                catch(e: Exception) { _connection.value="offline" }
            }
        }
        // Polling is a recovery path for missed WS frames; both paths use the same serialized sync transaction.
        val polling = launch {
            while(isActive) { try { syncOnce() } catch(e: CancellationException) { throw e } catch(e: Exception) { _connection.value="offline" };delay(2000) }
        }
        try {
            val backoff=ReconnectBackoff()
            while(isActive) {
                var opened: TimeMark?=null
                try { transport.watch(onOpen={ opened=TimeSource.Monotonic.markNow() }) { hints.trySend(Unit) } }
                catch(e: CancellationException) { throw e }
                catch(e: Exception) { _connection.value="offline" }
                delay(backoff.next(opened?.elapsedNow()?.inWholeMilliseconds ?: 0))
            }
        } finally { hints.close();pushed.cancelAndJoin();polling.cancelAndJoin() }
    }
}
