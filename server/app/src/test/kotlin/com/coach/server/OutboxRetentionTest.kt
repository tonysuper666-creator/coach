package com.coach.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.json
import com.coach.protocol.*
import com.coach.store.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Properties
import java.util.UUID
import kotlin.test.*
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

@OptIn(kotlin.time.ExperimentalTime::class)
class OutboxRetentionTest {
    private class Direct(var store: LedgerStore) : LedgerTransport {
        var failUpload = false
        var afterCommand: () -> Unit = {}
        val uploads = mutableMapOf<String,Int>()
        override suspend fun hello(epoch: String?) = store.hello()
        override suspend fun snapshot(course: String) = store.snapshot(course)
        override suspend fun events(course: String, after: Long) = store.page(course,after,500)
        override suspend fun command(command: WireCommand): Receipt {
            check(!failUpload) { "Isolated offline upload" }
            uploads[command.commandId] = (uploads[command.commandId] ?: 0) + 1
            return store.command(command).also { afterCommand() }
        }
        override suspend fun watch(onLedger: suspend () -> Unit) {}
    }
    private fun client(db: ClientDatabase, transport: LedgerTransport, clock: TestTimeSource) =
        SyncEngine(db,"phone",transport,listOf("course"),{ UUID.randomUUID().toString() },::sha256,clock)
    private fun body(type: String = "open_kp") = CommandBody(type,"course",Long.MIN_VALUE,json("kpId" to "synthetic-kp"))

    @Test fun slowReceiptStartsWindowWhenCoverageIsObserved() = runBlocking {
        val root=Files.createTempDirectory("coach-retention-slow-receipt-")
        val store=LedgerStore(root.resolve("server"))
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            ClientDatabase.Schema.create(driver)
            val clock=TestTimeSource();val transport=Direct(store)
            val db=ClientDatabase(driver);val sync=client(db,transport,clock)
            sync.syncOnce()
            // A lost prior receipt means the server already has (and has backed up) this command.
            val command=sync.enqueue(body());store.command(command)
            store.backup(root.resolve("covered"))
            // Hello cannot mark the still-pending row. Its duplicate receipt arrives much later.
            transport.afterCommand={ clock += 100.days }
            sync.syncOnce()
            assertEquals("DUPLICATE",sync.receipt(command.commandId)!!.status)
            sync.syncOnce();assertEquals(command,sync.retainedCommands().single())
            clock += 70.days - 1.milliseconds
            sync.syncOnce();assertEquals(command,sync.retainedCommands().single())
            clock += 1.milliseconds
            sync.syncOnce();assertTrue(sync.retainedCommands().isEmpty())
            println("RETENTION slowReceipt=100d freshCoverageWindow=70d prematurePruning=0")
        } finally { driver.close();store.close();root.toFile().deleteRecursively() }
    }

    @Test fun wallJumpsAndProcessDowntimeCannotShortenSeventyDayCoverageWindow() = runBlocking {
        val root=Files.createTempDirectory("coach-retention-clock-")
        var wall=1_800_000_000_000L
        val store=LedgerStore(root.resolve("server")) { wall }
        var driver: JdbcSqliteDriver?=null
        try {
            val transport=Direct(store)
            val url="jdbc:sqlite:${root.resolve("client.sqlite")}"
            fun open(): ClientDatabase {
                driver=JdbcSqliteDriver(url,Properties(),ClientDatabase.Schema)
                return ClientDatabase(driver!!)
            }
            var clock=TestTimeSource()
            var sync=client(open(),transport,clock)
            sync.syncOnce()
            val command=sync.enqueue(body());sync.syncOnce()
            // Acknowledged but not backed up: even 200 observed days are insufficient.
            clock += 200.days;sync.syncOnce()
            assertEquals(command,sync.retainedCommands().single())
            store.backup(root.resolve("covered"));sync.syncOnce()
            val initialServerTime=transport.hello(null).serverTime
            wall += 500.days.inWholeMilliseconds
            assertTrue(transport.hello(null).serverTime > initialServerTime)
            sync.syncOnce();assertEquals(command,sync.retainedCommands().single())
            wall -= 1000.days.inWholeMilliseconds
            sync.syncOnce();assertEquals(command,sync.retainedCommands().single())
            clock += 69.days;sync.syncOnce()
            driver!!.close();driver=null
            // A new process cannot establish elapsed downtime from either wall clock.
            wall += 3000.days.inWholeMilliseconds
            clock=TestTimeSource();sync=client(open(),transport,clock)
            sync.syncOnce();assertEquals(command,sync.retainedCommands().single())
            clock += 1.days - 1.milliseconds
            sync.syncOnce();assertEquals(command,sync.retainedCommands().single())
            clock += 1.milliseconds
            sync.syncOnce();assertTrue(sync.retainedCommands().isEmpty())
            val seq=store.hello().lastSeq
            sync.syncOnce();assertEquals(seq,store.hello().lastSeq)
            assertEquals(1,store.events("course").count { it.type=="kp_opened" })
            println("RETENTION boundary=70d beforeBoundary=retained atBoundary=pruned wallForward=500d wallBackward=1000d downtime=3000d duplicateEffects=0")
        } finally { driver?.close();store.close();root.toFile().deleteRecursively() }
    }

    @Test fun pendingNeverExpiresAndRejectedNeverReplaysAcrossEpochs() = runBlocking {
        val root=Files.createTempDirectory("coach-retention-terminal-")
        val data=root.resolve("server")
        var store=LedgerStore(data)
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            ClientDatabase.Schema.create(driver)
            val db=ClientDatabase(driver);val clock=TestTimeSource();val transport=Direct(store)
            val sync=client(db,transport,clock)
            val old=store.backup(root.resolve("old"))
            sync.syncOnce()
            val rejected=sync.enqueue(body("unsupported-command"));sync.syncOnce()
            assertEquals("REJECTED",sync.receipt(rejected.commandId)!!.status)
            val pending=sync.enqueue(body())
            transport.failUpload=true
            assertFails { sync.syncOnce() }
            clock += 140.days
            store.backup(root.resolve("watermark"))
            assertFails { sync.syncOnce() }
            assertEquals(listOf(rejected,pending),sync.retainedCommands())
            assertNull(db.ledgerQueries.allCommands().executeAsList().single { it.command_id==pending.commandId }.ack_seq)
            repeat(2) { round ->
                store.close();store=LedgerStore.restore(data,old);transport.store=store
                transport.failUpload=false
                sync.syncOnce()
                assertEquals(1,store.events("course").count { it.type=="kp_opened" })
                assertEquals(1,transport.uploads[rejected.commandId])
                store.backup(root.resolve("covered-$round"));sync.syncOnce()
                // Replayed commands receive a fresh full window, including after long offline gaps.
                clock += 70.days - 1.milliseconds;sync.syncOnce()
                assertEquals(listOf(rejected,pending),sync.retainedCommands())
                clock += 100.days
            }
            sync.syncOnce()
            assertEquals(listOf(rejected),sync.retainedCommands())
            assertEquals(1,transport.uploads[rejected.commandId])
            println("RETENTION pendingAfter140d=retained rejectedUploads=1 epochs=3 renewedWindow=true")
        } finally { driver.close();store.close();root.toFile().deleteRecursively() }
    }

    @Test fun schemaThreeUpgradePreservesImmutableBytesAndStartsFullWindowForUnknownAge() = runBlocking {
        val root=Files.createTempDirectory("coach-retention-upgrade-")
        val store=LedgerStore(root.resolve("server"))
        var driver: JdbcSqliteDriver?=null
        try {
            val url="jdbc:sqlite:${root.resolve("client.sqlite")}"
            driver=JdbcSqliteDriver(url,Properties(),ClientDatabase.Schema)
            // Model the deployed v3 outbox exactly: no age column and no age metadata.
            driver.execute(null,"DROP TABLE outbox",0)
            driver.execute(null,"CREATE TABLE outbox(command_id TEXT PRIMARY KEY,device_id TEXT NOT NULL,payload_bytes TEXT NOT NULL,payload_sha256 TEXT NOT NULL,ack_seq INTEGER,receipt_status TEXT)",0)
            val raw="""{ "type":"open_kp", "courseId":"course", "clientTime":-9223372036854775808, "payload":{"kpId":"synthetic-kp"}, "oldSerializerField":true }"""
            val hash=sha256(raw.toByteArray())
            val ack=store.command(WireCommand("phone","legacy-acked",raw,hash))
            val db=ClientDatabase(driver)
            db.ledgerQueries.putMeta("epoch",store.hello().epoch)
            driver.execute(null,"INSERT INTO outbox VALUES('legacy-acked','phone',?,?,?,'OK')",3) {
                bindString(0,raw);bindString(1,hash);bindLong(2,ack.eventSeqs.max())
            }
            driver.execute(null,"INSERT INTO outbox VALUES('legacy-pending','phone',?,?,NULL,NULL)",2) {
                bindString(0,raw);bindString(1,hash)
            }
            driver.execute(null,"INSERT INTO outbox VALUES('legacy-rejected','phone',?,?,0,'REJECTED')",2) {
                bindString(0,raw);bindString(1,hash)
            }
            driver.execute(null,"PRAGMA user_version=3",0)
            driver.close();driver=null
            // The same schema-aware open used by the desktop applies migration 3 -> 4.
            driver=JdbcSqliteDriver(url,Properties(),ClientDatabase.Schema)
            val upgraded=ClientDatabase(driver)
            assertTrue(upgraded.ledgerQueries.allCommands().executeAsList().all { it.backup_covered_at==null && it.payload_bytes==raw && it.payload_sha256==hash })
            val transport=Direct(store);val clock=TestTimeSource();val sync=client(upgraded,transport,clock)
            store.backup(root.resolve("before-upgrade"));sync.syncOnce()
            assertEquals(3,sync.retainedCommands().size)
            assertNull(transport.uploads["legacy-acked"])
            assertNull(transport.uploads["legacy-rejected"])
            assertEquals(1,transport.uploads["legacy-pending"])
            store.backup(root.resolve("all-covered"));sync.syncOnce()
            clock += 70.days - 1.milliseconds;sync.syncOnce()
            assertEquals(3,sync.retainedCommands().size)
            clock += 1.milliseconds;sync.syncOnce()
            assertEquals("legacy-rejected",sync.retainedCommands().single().commandId)
            println("RETENTION_MIGRATION schema=3->4 oldBytesPreserved=3 unknownAgeFullWindow=true rejectedReplays=0")
        } finally { driver?.close();store.close();root.toFile().deleteRecursively() }
    }
}
