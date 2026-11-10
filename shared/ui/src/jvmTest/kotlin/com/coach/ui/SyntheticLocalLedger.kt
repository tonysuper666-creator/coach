package com.coach.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.protocol.*

/** Real device-local session storage; an exam-panel read must never contact a server. */
internal class SyntheticLocalLedger(course: String = "SYN") : AutoCloseable {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ClientDatabase.Schema.create(it) }
    private val transport = object : LedgerTransport {
        override suspend fun hello(epoch: String?): Hello = error("No external ledger")
        override suspend fun snapshot(course: String): Snapshot = error("No external ledger")
        override suspend fun events(course: String, after: Long): EventsPage = error("No external ledger")
        override suspend fun command(command: WireCommand): Receipt = error("Exam-panel rendering must be read-only")
        override suspend fun watch(onLedger: suspend () -> Unit) = error("No external ledger")
    }
    val sync = SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf(course), { java.util.UUID.randomUUID().toString() }, { "hash" })
    override fun close() { driver.close() }
}
