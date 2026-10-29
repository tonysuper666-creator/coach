package com.coach.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.store.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.encodeToString
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class OfflineRestoreTest {
    private class Server(val store: LedgerStore, port: Int=0) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val actor=LedgerActor(scope,store)
        val pushes=MutableSharedFlow<String>(extraBufferCapacity=64)
        val engine=embeddedServer(Netty,host="127.0.0.1",port=port) { ledgerRoutes(actor,pushes) }.apply {
            engineConfig.apply { connectionGroupSize=1;workerGroupSize=1;callGroupSize=2 }
            start(wait=false)
        }
        suspend fun port()=engine.engine.resolvedConnectors().first().port
        suspend fun close() { engine.stopSuspend(0,1000);actor.close();scope.cancel() }
    }
    private val token="isolated-test-token-32-characters-minimum"
    private fun posed(id: String)=json("attemptId" to id,"questionId" to id,"fingerprint" to id,"kpIds" to listOf("kp"),"purpose" to "PRACTICE","policyVersion" to 1,"format" to "SINGLE_CHOICE","keyState" to "VERIFIED","offlinePack" to true)
    private fun submit(id: String)=CommandBody("submit_answer","course",System.currentTimeMillis(),json("attemptId" to id,"answer" to "A","offline" to true,"posedSnapshot" to posed(id)))

    @Test fun offlineFiveAnswersRestartRollbackAndAutomaticEpochResyncLoseNothing() = runBlocking {
        val root=Files.createTempDirectory("coach-e2e-")
        val data=root.resolve("server");val clientFile=root.resolve("phone.sqlite")
        var server: Server?=null
        var driver: JdbcSqliteDriver?=null
        var transport: HttpLedgerTransport?=null
        var loop: Job?=null
        try {
            var store=LedgerStore(data)
            store.enroll("phone",token)
            for(id in listOf("online","q1","q2","q3","q4")) store.registerQuestion(id,"course",id,"SINGLE_CHOICE","Synthetic choice $id","VERIFIED","A")
            val baseline=store.backup(root.resolve("old-backup"))
            // This question and the downloaded pack do not exist in the baseline backup.
            store.registerQuestion("q5","course","q5","SINGLE_CHOICE","Synthetic missing question","VERIFIED","A")
            for(i in 1..5) store.append("course","question_posed",posed("q$i"),"attempt:q$i")
            server=Server(store);val port=server.port()
            transport=HttpLedgerTransport("http://127.0.0.1:$port","phone",token)
            fun openClient(): SyncEngine {
                driver=JdbcSqliteDriver("jdbc:sqlite:$clientFile")
                if(!Files.exists(root.resolve("client-created"))) { ClientDatabase.Schema.create(driver!!);Files.createFile(root.resolve("client-created")) }
                return SyncEngine(ClientDatabase(driver!!),"phone",transport!!,listOf("course"),{ UUID.randomUUID().toString() },::sha256)
            }
            var client=openClient();client.syncOnce()
            // Acknowledged online answer, newer than the old backup, must remain in the outbox.
            client.enqueue(submit("online"));client.syncOnce();assertEquals(1,client.retainedCommands().size)
            val oldEpoch=store.hello().epoch
            server.close();server=null
            for(i in 1..5) client.enqueue(submit("q$i"))
            assertEquals(6,client.retainedCommands().size)
            assertFails { client.syncOnce() }
            // Kill/recreate the local client process state; SQLDelight remains the only source of pending bytes.
            driver!!.close();driver=null;client=openClient()
            store=LedgerStore(data);server=Server(store,port)
            client.syncOnce()
            assertEquals(6,store.events("course").count { it.type=="answer_submitted" })
            assertEquals(6,client.retainedCommands().size)
            server.close();server=null
            store=LedgerStore.restore(data,baseline);assertNotEquals(oldEpoch,store.hello().epoch)
            server=Server(store,port)
            // Real HTTP + WS reconnect loop detects the changed epoch without a user action.
            loop=client.start(this)
            withTimeout(15000) {
                while(client.epoch != store.hello().epoch || client.localEvents("course").count { it.type in setOf("answer_submitted","answer_orphaned") } != 6) delay(50)
            }
            loop.cancelAndJoin();loop=null
            val facts=store.events("course")
            val answers=facts.filter { it.type in setOf("answer_submitted","answer_orphaned") }
            assertEquals(6,answers.size);assertEquals(6,answers.map { it.payload.str("attemptId") }.distinct().size)
            assertEquals(5,facts.count { it.type=="answer_submitted" });assertEquals(1,facts.count { it.type=="answer_orphaned" })
            assertEquals("A",facts.single { it.type=="answer_orphaned" }.payload.str("rawAnswer"))
            assertEquals(store.snapshot("course").projection.bytes(),client.projection("course").bytes())
            assertEquals(5,client.projection("course").journal.values.sumOf { it.answers })
            val seq=store.hello().lastSeq;client.syncOnce();assertEquals(seq,store.hello().lastSeq)
            assertEquals(6,client.retainedCommands().size)
            server.actor.execute { backup(root.resolve("new-backup")) }
            client.syncOnce();assertTrue(client.retainedCommands().isEmpty())
            println("E2E offline=5 onlineAcknowledged=1 recovered=6 lost=0 duplicateFirstAnswers=0 orphans=1 epochChanged=true replicaBytesEqual=true")
        } finally {
            loop?.cancelAndJoin();transport?.close();driver?.close();server?.close();root.toFile().deleteRecursively()
        }
    }
    @Test fun immutableOldSerializationReplaysAndHttpAuthBindsDevice() = runBlocking {
        val root=Files.createTempDirectory("coach-wire-")
        val store=LedgerStore(root.resolve("server"));store.enroll("phone",token)
        val server=Server(store)
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY);ClientDatabase.Schema.create(driver)
        val transport=HttpLedgerTransport("http://127.0.0.1:${server.port()}","phone",token)
        try {
            val url="http://127.0.0.1:${server.port()}/v1/hello"
            val connection=URI(url).toURL().openConnection() as HttpURLConnection
            assertEquals(401,connection.responseCode);connection.disconnect()
            val db=ClientDatabase(driver)
            val raw="""{ "type":"open_kp", "courseId":"course", "clientTime":1793188800000, "payload":{"kpId":"kp"}, "oldSerializerField":true }"""
            db.ledgerQueries.putCommand("old-command","phone",raw,sha256(raw.toByteArray()))
            val client=SyncEngine(db,"phone",transport,listOf("course"),{ UUID.randomUUID().toString() },::sha256)
            client.syncOnce()
            assertEquals(raw,client.retainedCommands().single().payloadBytes)
            val r=transport.command(client.retainedCommands().single());assertEquals("DUPLICATE",r.status)
            val forged=client.retainedCommands().single().copy(deviceId="other")
            assertFails { transport.command(forged) }
            server.actor.execute { append("course","future_learning_fact",json("keep" to "raw")) }
            client.syncOnce()
            assertTrue(client.localEvents("course").any { it.type=="future_learning_fact" })
            assertTrue(client.projection("course").requiresServerSnapshot)
            println("WIRE oldBytesUnchanged=true duplicateWithoutPayloadMismatch=true unauthorized=401 forgedDevice=403")
        } finally { transport.close();driver.close();server.close();root.toFile().deleteRecursively() }
    }
    @Test fun suspendedSamplingUsesBothClocks() {
        val detector=SuspensionDetector(1000,1000000000)
        assertFalse(detector.sample(6000,6000000000));assertTrue(detector.sample(41000,41000000000))
        assertTrue(detector.sample(80000,42000000000))
    }
}
