package com.coach.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.*

class ProjectionFileConcurrencyTest {
    private val transport = object : LedgerTransport {
        override suspend fun hello(epoch: String?): Hello = error("unused")
        override suspend fun snapshot(course: String): Snapshot = error("unused")
        override suspend fun events(course: String, after: Long): EventsPage = error("unused")
        override suspend fun command(command: WireCommand): Receipt = error("unused")
        override suspend fun watch(onLedger: suspend () -> Unit) = error("unused")
    }
    @Test fun projectionWaitsForConcurrentWriterInsteadOfUpgradingReadTransaction() {
        val file = Files.createTempFile("coach-projection-", ".sqlite")
        val url = "jdbc:sqlite:$file"
        val executor = Executors.newSingleThreadExecutor()
        try {
            JdbcSqliteDriver(url).use { driver ->
                ClientDatabase.Schema.create(driver)
                val db = ClientDatabase(driver)
                val sync = SyncEngine(db, "device", transport, listOf("SYN"), { "id" }, { "hash" })
                DriverManager.getConnection(url).use { writer ->
                    writer.autoCommit = false
                    writer.createStatement().use { it.executeUpdate("INSERT INTO sync_meta VALUES ('other-writer','pending')") }
                    val started = CountDownLatch(1)
                    val result = executor.submit<com.coach.domain.Projection> {
                        started.countDown()
                        sync.projection("SYN")
                    }
                    try {
                        assertTrue(started.await(5, TimeUnit.SECONDS))
                        assertFailsWith<TimeoutException> { result.get(1, TimeUnit.SECONDS) }
                    } finally {
                        writer.commit()
                    }
                    assertEquals(0, result.get(5, TimeUnit.SECONDS).lastSeq)
                    assertEquals(sync.projection("SYN"), ProjectionCache(db).get("SYN", "", 0))
                }
            }
        } finally {
            executor.shutdownNow()
            Files.deleteIfExists(file)
        }
    }

    @Test fun staleComputationsCannotOverwriteNewEpochSequenceOrSnapshot() {
        val file = Files.createTempFile("coach-cache-", ".sqlite")
        try {
            JdbcSqliteDriver("jdbc:sqlite:$file").use { driver ->
                ClientDatabase.Schema.create(driver)
                val db = ClientDatabase(driver)
                val q = db.ledgerQueries
                val cache = ProjectionCache(db)
                val old = Projection(lastSeq = 0, timeZone = "UTC")
                q.putMeta("epoch", "e1")
                cache.putIfCurrent("SYN", "e1", 0, null, old)
                assertEquals(old, cache.get("SYN", "e1", 0))
                q.putMeta("epoch", "e2")
                val newer = old.copy(timeZone = "Europe/London")
                cache.put("SYN", "e2", 0, newer)
                cache.putIfCurrent("SYN", "e1", 0, null, old)
                assertEquals(newer, cache.get("SYN", "e2", 0))

                val event = Event(1, "event", "SYN", "synthetic_unknown", recordedAt = 1, occurredAt = 1, deviceId = "d", actor = "a")
                q.putEvent(1, "SYN", LedgerJson.encodeToString(event))
                val seq1 = newer.copy(lastSeq = 1)
                cache.put("SYN", "e2", 1, seq1)
                cache.putIfCurrent("SYN", "e2", 0, null, old)
                assertEquals(seq1, cache.get("SYN", "e2", 1))

                q.putMeta("serverProjection:SYN", "snapshot-new")
                val snapshotState = seq1.copy(timeZone = "Asia/Tokyo")
                cache.put("SYN", "e2", 1, snapshotState)
                cache.putIfCurrent("SYN", "e2", 1, "snapshot-old", seq1)
                assertEquals(snapshotState, cache.get("SYN", "e2", 1))
                assertNull(cache.get("SYN", "e1", 1))
                assertNull(cache.get("SYN", "e2", 0))
                q.putProjection("SYN", (PROJECTION_VERSION - 1).toLong(), 1, "e2", LedgerJson.encodeToString(seq1))
                assertNull(cache.get("SYN", "e2", 1))
            }
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun aSnapshotCommittedDuringProjectionIsUsedOnTheNextRead() {
        val file = Files.createTempFile("coach-snapshot-", ".sqlite")
        val url = "jdbc:sqlite:$file"
        val executor = Executors.newSingleThreadExecutor()
        try {
            JdbcSqliteDriver(url).use { driver ->
                ClientDatabase.Schema.create(driver)
                val db = ClientDatabase(driver)
                val q = db.ledgerQueries
                q.putMeta("epoch", "e1")
                val unknown = Event(1, "event", "SYN", "synthetic_unknown", recordedAt = 1, occurredAt = 1, deviceId = "d", actor = "a")
                q.putEvent(1, "SYN", LedgerJson.encodeToString(unknown))
                val old = LedgerJson.encodeToString(Projection(lastSeq = 1, timeZone = "UTC"))
                val newer = LedgerJson.encodeToString(Projection(lastSeq = 1, timeZone = "Asia/Tokyo"))
                q.putMeta("serverProjection:SYN", old)
                val sync = SyncEngine(db, "device", transport, listOf("SYN"), { "id" }, { "hash" })
                DriverManager.getConnection(url).use { writer ->
                    writer.autoCommit = false
                    writer.prepareStatement("UPDATE sync_meta SET value=? WHERE key='serverProjection:SYN'").use {
                        it.setString(1, newer); it.executeUpdate()
                    }
                    val started = CountDownLatch(1)
                    val result = executor.submit<Projection> { started.countDown(); sync.projection("SYN") }
                    try {
                        assertTrue(started.await(5, TimeUnit.SECONDS))
                        assertFailsWith<TimeoutException> { result.get(1, TimeUnit.SECONDS) }
                    } finally { writer.commit() }
                    assertEquals("UTC", result.get(5, TimeUnit.SECONDS).timeZone)
                    assertEquals("Asia/Tokyo", sync.projection("SYN").timeZone)
                    assertEquals("Asia/Tokyo", ProjectionCache(db).get("SYN", "e1", 1)?.timeZone)
                }
            }
        } finally { executor.shutdownNow(); Files.deleteIfExists(file) }
    }

    @Test fun offlineSubmissionLocksBeforeValidatingAndCommitsOnce() {
        val file = Files.createTempFile("coach-offline-", ".sqlite")
        val url = "jdbc:sqlite:$file"
        val executor = Executors.newSingleThreadExecutor()
        try {
            JdbcSqliteDriver(url).use { driver ->
                ClientDatabase.Schema.create(driver)
                val db = ClientDatabase(driver)
                val q = db.ledgerQueries
                q.putMeta("epoch", "e1")
                q.putOfflinePack("p", "SYN", 1000, "e1")
                val event = Event(1, "event", "SYN", "question_posed", recordedAt = 1, occurredAt = 1, deviceId = "d", actor = "a",
                    payload = json("attemptId" to "a", "groupId" to "g", "format" to "SINGLE_CHOICE", "letterMap" to json("key" to "A")))
                q.putOfflineItem("a", "p", "g", 0, LedgerJson.encodeToString(event))
                q.putOfflineKey("a", "key")
                val sync = SyncEngine(db, "device", transport, listOf("SYN"), { "command" }, { "hash" })
                DriverManager.getConnection(url).use { writer ->
                    writer.autoCommit = false
                    writer.createStatement().use { it.executeUpdate("INSERT INTO sync_meta VALUES ('other-writer','pending')") }
                    val started = CountDownLatch(1)
                    val result = executor.submit<Unit> { started.countDown(); sync.submitOfflineGroup("SYN", "g", mapOf("a" to "A"), 1) }
                    try {
                        assertTrue(started.await(5, TimeUnit.SECONDS))
                        assertFailsWith<TimeoutException> { result.get(1, TimeUnit.SECONDS) }
                    } finally { writer.commit() }
                    result.get(5, TimeUnit.SECONDS)
                    assertEquals(1, sync.retainedCommands().size)
                    val row = q.offlineItems("SYN").executeAsOne()
                    assertEquals("command", row.command_id)
                    assertEquals(1L, row.local_correct)
                    assertFailsWith<IllegalArgumentException> { sync.submitOfflineGroup("SYN", "g", mapOf("a" to "A"), 1) }
                    assertEquals(1, sync.retainedCommands().size)
                }
            }
        } finally { executor.shutdownNow(); Files.deleteIfExists(file) }
    }
}
