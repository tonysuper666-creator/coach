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

/** Regression from the independent review: real SQLite stores with an in-process transport adapter. */
class TwoRestoresOfflineTest {
    private class Direct(var store: LedgerStore) : LedgerTransport {
        override suspend fun hello(epoch: String?) = store.hello()
        override suspend fun snapshot(course: String) = store.snapshot(course)
        override suspend fun events(course: String, after: Long) = store.page(course, after, 500)
        override suspend fun command(command: WireCommand) = store.command(command)
        override suspend fun watch(onLedger: suspend () -> Unit) {}
    }
    private fun posed(id: String)=json("attemptId" to id,"questionId" to id,"fingerprint" to id,"kpIds" to listOf("kp"),"purpose" to "PRACTICE","policyVersion" to 1,"format" to "SINGLE_CHOICE")
    private fun submit(id: String)=CommandBody("submit_answer","course",System.currentTimeMillis(),json("attemptId" to id,"answer" to "A","offline" to true,"posedSnapshot" to posed(id)))

    @Test fun twoRestoresWhileClientOffline(): Unit = runBlocking { exercise(false) }
    @Test fun retainedCommandAlreadyInRestoredBackupIsDeduplicated(): Unit = runBlocking { exercise(true) }

    private suspend fun exercise(answerInBackup: Boolean) {
        val root=Files.createTempDirectory("coach-two-restores-")
        var store=LedgerStore(root.resolve("server"))
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val data=root.resolve("server")
            store.registerQuestion("q1","course","q1","SINGLE_CHOICE","Synthetic choice","VERIFIED","A")
            val initial=store.backup(root.resolve("b0"))
            val transport=Direct(store)
            ClientDatabase.Schema.create(driver)
            val client=SyncEngine(ClientDatabase(driver),"phone",transport,listOf("course"),{ UUID.randomUUID().toString() },::sha256)
            client.syncOnce()
            val command=client.enqueue(submit("q1"));client.syncOnce()
            val acked=store.hello().lastSeq
            assertEquals(1,client.retainedCommands().size)
            val oldEpoch=client.epoch
            // The phone misses both epoch changes. The second backup's seq exceeds its old ack.
            val first=if(answerInBackup) store.backup(root.resolve("with-answer")) else initial
            store.close();store=LedgerStore.restore(data,first)
            repeat(5) { store.append("course","note_saved",json("noteId" to "synthetic-$it")) }
            val second=store.backup(root.resolve("b1"))
            store.close();store=LedgerStore.restore(data,second)
            transport.store=store
            assertTrue(acked<store.hello().lastSeqBefore)
            assertNotEquals(oldEpoch,store.hello().epoch)
            client.syncOnce()
            val facts=store.events("course")
            assertEquals(1,facts.count { it.type=="answer_submitted" && it.payload.str("attemptId")=="q1" })
            assertEquals("A",facts.single { it.type=="answer_submitted" }.payload.str("answer"))
            assertEquals(store.hello().epoch,client.epoch)
            assertEquals(store.snapshot("course").projection.bytes(),client.projection("course").bytes())
            if(answerInBackup) {
                assertEquals("DUPLICATE",client.receipt(command.commandId)!!.status)
                assertTrue(client.retainedCommands().isEmpty())
            } else {
                assertEquals(command,client.retainedCommands().single())
                assertEquals("OK",client.receipt(command.commandId)!!.status)
            }
            val seq=store.hello().lastSeq
            client.syncOnce();assertEquals(seq,store.hello().lastSeq)
            store.backup(root.resolve("recovered"));client.syncOnce()
            assertTrue(client.retainedCommands().isEmpty())
            println("TWO_RESTORES alreadyInBackup=$answerInBackup recovered=1 lost=0 duplicateFirstAnswers=0 backupCoveredPruning=true")
        } finally { driver.close();store.close();root.toFile().deleteRecursively() }
    }
}
