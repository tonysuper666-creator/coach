package com.coach.store

import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LedgerStoreTest {
    private val options = listOf(Option("one","First synthetic option"), Option("two","Second synthetic option"),
        Option("three","Third synthetic option"), Option("four","Fourth synthetic option"))
    private val frozenMap = kotlinx.serialization.json.JsonObject(
        assignLetters(listOf(ChoiceKey(options,"one")),null,kotlin.random.Random(1042)).single()
            .mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) })

    private fun wire(id: String, answer: String = frozenMap.str("one"), device: String = "phone", snapshot: JsonSnapshot = JsonSnapshot()): WireCommand {
        val body=CommandBody("submit_answer","course",1793188800000L,json("attemptId" to snapshot.attempt,"answer" to answer,"posedSnapshot" to snapshot.payload(),"offline" to true))
        val bytes=canonicalJson(LedgerJson.encodeToJsonElement(body))
        return WireCommand(device,id,bytes,sha256(bytes.toByteArray()))
    }
    private inner class JsonSnapshot(val attempt: String="attempt",val question: String="q") {
        fun payload()=json("attemptId" to attempt,"questionId" to question,"fingerprint" to questionFingerprint("Synthetic choice", options),"kpIds" to listOf("kp"),"purpose" to "PRACTICE","policyVersion" to 1,"groupId" to "synthetic-group", "letterMap" to frozenMap, "format" to "SINGLE_CHOICE")
    }
    @Test fun commandsAreAtomicIdempotentAndCrossDeviceConflictsRetainText() {
        val dir=Files.createTempDirectory("coach-command-")
        try { LedgerStore(dir).use { db ->
            db.registerQuestion("q","course","q","SINGLE_CHOICE","Synthetic choice","VERIFIED","one", options)
            val c=wire("command")
            val first=db.command(c);assertEquals("OK",first.status);assertEquals(3,first.eventSeqs.size)
            assertEquals("DUPLICATE",db.command(c).status);assertEquals(3,db.events("course").size)
            assertEquals("SYSTEM",db.events("course").single { it.type=="attempt_graded" }.actor)
            assertEquals("LEARNER",db.events("course").single { it.type=="answer_submitted" }.actor)
            assertEquals("PAYLOAD_MISMATCH",db.command(wire("command",frozenMap.str("two"))).error)
            val conflict=db.command(wire("other","different raw answer","mac"));assertEquals("CONFLICT",conflict.status)
            assertEquals(1,db.events("course").count { it.type=="answer_submitted" })
            assertEquals("different raw answer",db.events("course").single { it.type=="answer_orphaned" }.payload.str("rawAnswer"))
            assertEquals("CONFLICT",db.command(wire("other","different raw answer","mac")).status)
            assertEquals(4,db.events("course").size)
        } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun backupIncludesBlobsMessagesAndRestoresANewEpochWithFences() {
        val dir=Files.createTempDirectory("coach-backup-")
        val backupDir=Files.createTempDirectory("coach-archive-").resolve("snapshot")
        var db=LedgerStore(dir)
        try {
            db.registerQuestion("q","course","q","SINGLE_CHOICE","Synthetic choice","VERIFIED","one", options)
            val blob=db.putBlob("immutable synthetic blob".toByteArray(),"text/plain")
            db.startMessage("thread","msg","partial text")
            val job=db.enqueueJob("grade","attempt","v1","hash")
            val pre=db.claimJob(job)!!
            val lease=db.acquireLease("thread","phone")!!
            val oldEpoch=db.hello().epoch
            db.command(wire("1"));val seq=db.hello().lastSeq
            db.backup(backupDir)
            assertEquals(seq,db.hello().backedUpThroughSeq)
            assertTrue(Files.readString(backupDir.resolve("events.jsonl")).contains("partial text"))
            db.command(wire("2",snapshot=JsonSnapshot("later")))
            db.close();db=LedgerStore.restore(dir,backupDir)
            assertNotEquals(oldEpoch,db.hello().epoch);assertEquals(seq,db.hello().lastSeqBefore)
            assertEquals("cancelled",db.jobState(job)!!.first)
            assertFalse(db.completeJob(pre,"v1","hash"));assertFalse(db.renewLease(lease,"phone"))
            assertEquals("truncated" to "server_restored",db.messageStatus("msg"))
            assertEquals("immutable synthetic blob",Files.readString(dir.resolve("blobs/$blob")))
            assertEquals("OK",db.command(wire("2",snapshot=JsonSnapshot("later"))).status)
            assertEquals(2,db.events("course").count { it.type=="answer_submitted" })
        } finally { db.close();dir.toFile().deleteRecursively();backupDir.parent.toFile().deleteRecursively() }
    }
    @Test fun suspensionInvalidatesRunningJobsAndLeasesAndFinishesMessages() {
        val dir=Files.createTempDirectory("coach-wake-")
        try { LedgerStore(dir).use { db ->
            val id=db.enqueueJob("grade","q","v1","hash");val token=db.claimJob(id)!!
            val lease=db.acquireLease("t","phone")!!;db.startMessage("t","m")
            db.recoverSuspension()
            assertFalse(db.completeJob(token,"v1","hash"));assertFalse(db.renewLease(lease,"phone"))
            val second=db.claimJob(id)!!;assertTrue(second.value>token.value)
            assertFalse(db.completeJob(second,"v2","hash"));assertTrue(db.completeJob(second,"v1","hash"))
            assertFalse(db.completeJob(second,"v1","hash"));assertEquals("truncated" to "suspended",db.messageStatus("m"))
        } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun leaseRenewalRetainsFenceNewGrantIncreasesAndPreMigrationBackupExists() {
        val dir=Files.createTempDirectory("coach-lease-");var time=1793188800000L
        try { LedgerStore(dir) { time }.use { db ->
            val first=db.acquireLease("t","a")!!;assertNull(db.acquireLease("t","b"))
            assertTrue(db.renewLease(first,"a"));assertEquals(first,db.acquireLease("t","a"))
            time+=120001;val next=db.acquireLease("t","b")!!;assertTrue(next.value>first.value)
            assertFalse(db.renewLease(first,"a"))
            assertTrue(Files.list(dir.resolve("backups")).use { it.anyMatch { p -> p.fileName.toString().startsWith("pre-V001-") && Files.exists(p.resolve("coach.sqlite")) } })
        } } finally { dir.toFile().deleteRecursively() }
    }
}
