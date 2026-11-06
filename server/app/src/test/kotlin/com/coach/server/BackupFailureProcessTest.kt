package com.coach.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.store.*
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class BackupFailureProcessTest {
    @Test fun failedStartupBackupKeepsTheServerServing() = runBlocking {
        val root=Files.createTempDirectory("coach-backup-fail-")
        val data=root.resolve("data");val log=root.resolve("server.log")
        val token="process-test-32-character-isolated-token"
        val tokenFile=root.resolve("token");Files.writeString(tokenFile,token)
        LedgerStore(data).use { db -> db.enroll("phone",token);db.append("course","note_saved",com.coach.domain.json("noteId" to "kept")) }
        // A directory cannot be hard-linked, so every backup fails the same way a full disk would.
        Files.createDirectory(data.resolve("blobs/unreadable-entry"))
        val port=ServerSocket(0).use { it.localPort }
        val builder=ProcessBuilder(System.getProperty("coach.serverBinary"))
        builder.environment().putAll(mapOf("COACH_DATA_DIR" to data.toString(),"COACH_TOKEN_FILE" to tokenFile.toString(),"COACH_DEVICE_ID" to "phone","COACH_PORT" to port.toString(),"COACH_TAIL_HOST" to "192.0.2.1"))
        builder.redirectErrorStream(true);builder.redirectOutput(log.toFile())
        val process=builder.start()
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY);ClientDatabase.Schema.create(driver)
        val transport=HttpLedgerTransport("http://127.0.0.1:$port","phone",token)
        val client=SyncEngine(ClientDatabase(driver),"phone",transport,listOf("course"),{UUID.randomUUID().toString()},::sha256)
        try {
            withTimeout(20000) { while(true) { try { client.syncOnce();break } catch(e: Exception) {
                assertTrue(process.isAlive, "server died on a failed backup:\n"+Files.readString(log));delay(100) } } }
            // Survive past the first maintenance tick, which retries nothing until the backoff expires.
            delay(6000)
            assertTrue(process.isAlive, Files.readString(log))
            client.syncOnce()
            assertTrue(Files.readString(log).contains("COACH backup failed="))
            println("BACKUP startupBackupFailed=true serverAlive=true syncAfterFailure=ok")
        } finally {
            transport.close();driver.close()
            if(process.isAlive) { process.destroy();if(!process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly();process.waitFor() } }
            root.toFile().deleteRecursively()
        }
    }
}
