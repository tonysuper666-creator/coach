package com.coach.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.UUID
import kotlin.test.*

/** Screens re-read local events on every revision; the decoded copy must follow new facts and epoch resets. */
class LocalEventsCacheTest {
    private class Ledger : LedgerTransport {
        @Volatile var epoch = "epoch-1"
        val events: MutableList<Event> = Collections.synchronizedList(mutableListOf())
        fun add(note: String) { events += Event(events.size + 1L, "$epoch-${events.size + 1}", "course", "note_saved", recordedAt = 1, occurredAt = 1, deviceId = "mac", actor = "SYSTEM", payload = json("noteId" to note)) }
        private fun hello() = Hello(epoch, events.size.toLong(), 0, 0)
        override suspend fun hello(epoch: String?) = hello()
        override suspend fun snapshot(course: String) = Snapshot(hello(), course, events.toList(), Projection(lastSeq = events.size.toLong()))
        override suspend fun events(course: String, after: Long) = EventsPage(epoch, events.filter { it.seq > after }, events.size.toLong())
        override suspend fun command(command: WireCommand): Receipt = error("no commands in this test")
        override suspend fun watch(onLedger: suspend () -> Unit) = awaitCancellation()
    }

    @Test fun repeatedReadsFollowNewEventsAndEpochResets() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ClientDatabase.Schema.create(it) }
        val ledger = Ledger()
        val sync = SyncEngine(ClientDatabase(driver), "phone", ledger, listOf("course"), { UUID.randomUUID().toString() }, { "hash" })
        fun notes() = sync.localEvents("course").map { it.payload.str("noteId") }
        try {
            ledger.add("a"); ledger.add("b"); sync.syncOnce()
            assertEquals(listOf("a", "b"), notes())
            ledger.add("c"); sync.syncOnce()
            assertEquals(listOf("a", "b", "c"), notes())
            assertEquals(notes(), notes())
            // A rebuilt server ledger reuses the same seqs for different facts.
            ledger.epoch = "epoch-2"; ledger.events.clear(); ledger.add("x"); ledger.add("y"); ledger.add("z"); sync.syncOnce()
            assertEquals(listOf("x", "y", "z"), notes())
        } finally { driver.close() }
    }
}
