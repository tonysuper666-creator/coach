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
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.Default) {
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
