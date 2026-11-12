package com.coach.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.store.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class AgentRunClientTest {
    @Test fun agentOutboxIsMarkedBeforeHashingAndOldServerGetsNoUpload() = runBlocking {
        val root = Files.createTempDirectory("agent-client-")
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            ClientDatabase.Schema.create(driver)
            LedgerStore(root).use { store ->
                var supported = false
                var writes = 0
                val transport = object : LedgerTransport {
                    override suspend fun hello(epoch: String?) = store.hello().copy(agentRunsSupported=supported)
                    override suspend fun snapshot(course: String) = store.snapshot(course)
                    override suspend fun events(course: String, after: Long) = store.page(course,after,500)
                    override suspend fun command(command: WireCommand): Receipt { writes++; return store.command(command) }
                    override suspend fun watch(onLedger: suspend () -> Unit) {}
                }
                val client = SyncEngine(ClientDatabase(driver),"phone",transport,listOf("course"),{ UUID.randomUUID().toString() },::sha256,agentRunId="oppo")
                val cmd = client.enqueue(CommandBody("open_kp","course",1,json("kpId" to "kp")))
                val body = LedgerJson.decodeFromString<CommandBody>(cmd.payloadBytes)
                assertEquals(AGENT_RUN_LABEL,body.payload.str("sampleOrigin"))
                assertEquals(sha256(cmd.payloadBytes.toByteArray()),cmd.payloadSha256)
                assertFailsWith<IllegalStateException> { client.syncOnce() }
                assertEquals(0,writes)
                assertFailsWith<IllegalStateException> { client.downloadOfflinePack("course") }
                supported = true
                client.syncOnce()
                assertEquals(1,writes)
                assertEquals("OK",client.receipt(cmd.commandId)?.status)
                assertTrue(store.snapshot("course").projection.startedKpIds.isEmpty())
                assertEquals("oppo",store.events("course").single().payload.str("agentRunId"))
            }
        } finally { driver.close();root.toFile().deleteRecursively() }
    }
}
