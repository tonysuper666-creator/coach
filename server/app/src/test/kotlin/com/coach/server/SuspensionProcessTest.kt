package com.coach.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.store.*
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class SuspensionProcessTest {
    @Test fun stopContinueProcessGroupTruncatesRequeuesAndClientReconnects() = runBlocking {
        val root=Files.createTempDirectory("coach-stop-cont-")
        val data=root.resolve("data");val log=root.resolve("server.log")
        val token="process-test-32-character-isolated-token"
        val tokenFile=root.resolve("token");Files.writeString(tokenFile,token)
        var job="";var beforeFence=0L
        LedgerStore(data).use { db ->
            db.enroll("phone",token);db.startMessage("thread","message","real partial message")
            job=db.enqueueJob("grade","synthetic-attempt");beforeFence=db.claimJob(job)!!.value
        }
        val port=ServerSocket(0).use { it.localPort }
        val binary=System.getProperty("coach.serverBinary")
        val builder=ProcessBuilder("python3","-c","import os,sys;os.setsid();os.execvp('nice',['nice','-n','15',sys.argv[1]])",binary)
        builder.environment().putAll(mapOf("COACH_DATA_DIR" to data.toString(),"COACH_TOKEN_FILE" to tokenFile.toString(),"COACH_DEVICE_ID" to "phone","COACH_PORT" to port.toString(),"COACH_TAIL_HOST" to "192.0.2.1"))
        builder.redirectErrorStream(true);builder.redirectOutput(log.toFile())
        val process=builder.start()
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY);ClientDatabase.Schema.create(driver)
        val transport=HttpLedgerTransport("http://127.0.0.1:$port","phone",token)
        val client=SyncEngine(ClientDatabase(driver),"phone",transport,listOf("course"),{UUID.randomUUID().toString()},::sha256)
        var loop: Job?=null
        fun signal(name: String) { check(ProcessBuilder("/bin/kill","-$name","-${process.pid()}").start().waitFor()==0) }
        try {
            withTimeout(15000) { while(true) { try { client.syncOnce();break } catch(e: Exception) { if(!process.isAlive) error(Files.readString(log));delay(100) } } }
            loop=client.start(this)
            delay(5500)
            val revision=client.revision.value
            signal("STOP")
            delay(32000)
            signal("CONT")
            withTimeout(20000) {
                while(!Files.readString(log).contains("wake detected") || client.connection.value!="online" || client.revision.value<=revision) delay(100)
            }
            DriverManager.getConnection("jdbc:sqlite:file:${data.resolve("coach.sqlite")}?mode=ro").use { db ->
                db.createStatement().use { s -> s.executeQuery("SELECT status,reason FROM messages WHERE message_id='message'").use { rs -> assertTrue(rs.next());assertEquals("truncated",rs.getString(1));assertEquals("suspended",rs.getString(2)) } }
                db.prepareStatement("SELECT state,fence FROM jobs WHERE job_id=?").use { s -> s.setString(1,job);s.executeQuery().use { rs -> assertTrue(rs.next());assertEquals("queued",rs.getString(1));assertTrue(rs.getLong(2)>beforeFence) } }
            }
            println("SUSPEND processGroupSTOP=32000ms wakeDetected=true message=truncated job=requeued clientAutoReconnected=true")
            println("CLOCK STOP/CONT exercises scheduling-gap detection; physical macOS sleep monotonic behavior remains unmeasured")
        } finally {
            loop?.cancelAndJoin();transport.close();driver.close()
            if(process.isAlive) { signal("CONT");signal("TERM");if(!process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)) { signal("KILL");process.waitFor() } }
            root.toFile().deleteRecursively()
        }
    }
}
