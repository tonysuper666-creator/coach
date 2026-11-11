package com.coach.ui

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.SyncEngine
import com.coach.client.LedgerTransport
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class QaLocalEventsEpochRaceTest {
    private class InterleavingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        var beforeReplicaRead: (() -> Unit)? = null

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?
        ): QueryResult<R> {
            if (sql.startsWith("SELECT event_json FROM replica")) {
                val interleave = beforeReplicaRead
                beforeReplicaRead = null
                interleave?.invoke()
            }
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }
    }

    private class Ledger : LedgerTransport {
        var epoch = "epoch-1"
        val events = mutableListOf<Event>()

        fun replace(epoch: String, notes: List<String>) {
            this.epoch = epoch
            events.clear()
            notes.forEachIndexed { index, note ->
                events += Event(
                    index + 1L, "$epoch-$index", "course", "note_saved",
                    recordedAt = 1, occurredAt = 1, deviceId = "mac", actor = "SYSTEM",
                    payload = json("noteId" to note)
                )
            }
        }

        private fun hello() = Hello(epoch, events.size.toLong(), 0, 0)
        override suspend fun hello(epoch: String?) = hello()
        override suspend fun snapshot(course: String) =
            Snapshot(hello(), course, events.toList(), Projection(lastSeq = events.size.toLong()))
        override suspend fun events(course: String, after: Long) =
            EventsPage(epoch, events.filter { it.seq > after }, events.size.toLong())
        override suspend fun command(command: WireCommand): Receipt = error("No commands")
        override suspend fun watch(onLedger: suspend () -> Unit) = awaitCancellation()
    }

    @Test
    fun epochResetBetweenMetadataAndReplicaReadReturnsCoherentEvents() = runBlocking {
        val driver = InterleavingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        ClientDatabase.Schema.create(driver)
        val ledger = Ledger()
        val sync = SyncEngine(
            ClientDatabase(driver), "phone", ledger, listOf("course"),
            { UUID.randomUUID().toString() }, { "hash" }
        )
        fun notes() = sync.localEvents("course").map { it.payload.str("noteId") }

        try {
            ledger.replace("epoch-1", listOf("a", "b"))
            sync.syncOnce()
            assertEquals(listOf("a", "b"), notes())

            // Complete a normal sync at the point where another thread can reset the replica.
            driver.beforeReplicaRead = {
                runBlocking {
                    ledger.replace("epoch-2", listOf("x", "y", "z"))
                    sync.syncOnce()
                }
            }
            assertEquals(listOf("x", "y", "z"), notes())
        } finally {
            driver.close()
        }
    }
}
