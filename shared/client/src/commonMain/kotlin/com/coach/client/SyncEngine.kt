package com.coach.client

import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

interface LedgerTransport {
    suspend fun hello(epoch: String?): Hello
    suspend fun snapshot(course: String): Snapshot
    suspend fun events(course: String, after: Long): EventsPage
    suspend fun command(command: WireCommand): Receipt
    suspend fun offlinePack(course: String, size: Int): OfflineChoicePack = error("Offline packs unavailable")
    suspend fun watch(onLedger: suspend () -> Unit)
}

/** Device-specific immutable outbox and disposable ledger replica. Database survives process restarts. */
class SyncEngine(private val db: ClientDatabase, val deviceId: String,
    private val transport: LedgerTransport, private val courses: List<String>,
    private val newId: () -> String, private val hash: (ByteArray) -> String) {
    private val mutex = Mutex()
    private val _connection = MutableStateFlow("offline")
    val connection = _connection.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    val epoch: String? get() = meta("epoch")
    private val q get() = db.ledgerQueries
    private val cache=ProjectionCache(db)
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
        q.prune(hello.backedUpThroughSeq)
        val pending = q.allCommands().executeAsList().filter { it.ack_seq == null }
        for(row in pending) {
            val receipt = transport.command(WireCommand(row.device_id,row.command_id,row.payload_bytes,row.payload_sha256))
            check(receipt.epoch == hello.epoch) { "Epoch changed during upload" }
            db.transaction { q.ackCommand(receipt.eventSeqs.maxOrNull() ?: 0,receipt.status,row.command_id);q.putMeta("receipt:${row.command_id}",LedgerJson.encodeToString(receipt)) }
            q.prune(receipt.backedUpThroughSeq)
        }
        for(course in courses) {
            var cursor = meta("cursor:$course")?.toLong() ?: 0
            do {
                val page = transport.events(course,cursor)
                check(page.epoch == hello.epoch) { "Epoch changed during catch-up" }
                db.transaction { save(page.events);q.putMeta("cursor:$course",page.throughSeq.toString()) }
                cursor = page.throughSeq
            } while(page.hasMore)
            if(projection(course).requiresServerSnapshot) {
                val snapshot=transport.snapshot(course)
                check(snapshot.hello.epoch==hello.epoch)
                db.transaction {
                    q.putMeta("serverProjection:$course",LedgerJson.encodeToString(snapshot.projection))
                    val seq=q.replicaSeq(course).executeAsOne()
                    cache.put(course,hello.epoch,seq,snapshot.projection.copy(lastSeq=seq,requiresServerSnapshot=true))
                }
            }
        }
        _connection.value = "online"
        _revision.value += 1
    }
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        // Polling is a recovery path for missed WS frames; both paths use the same serialized sync transaction.
        val polling = launch {
            while(isActive) { try { syncOnce() } catch(e: CancellationException) { throw e } catch(e: Exception) { _connection.value="offline" };delay(2000) }
        }
        try {
            var backoff = 250L
            while(isActive) {
                try { transport.watch { syncOnce() };backoff=250 }
                catch(e: CancellationException) { throw e }
                catch(e: Exception) { _connection.value="offline" }
                delay(backoff);backoff=(backoff*2).coerceAtMost(5000)
            }
        } finally { polling.cancelAndJoin() }
    }
}
