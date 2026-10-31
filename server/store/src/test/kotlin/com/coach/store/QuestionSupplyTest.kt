package com.coach.store

import com.coach.domain.*
import kotlinx.serialization.json.JsonArray
import java.nio.file.Files
import java.sql.DriverManager
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.*

class QuestionSupplyTest {
    @Test fun supplyRejectsBlankObjectivesAndExcerptsWithoutQueueingJobs() {
        val root=Files.createTempDirectory("supply-validation")
        try { LedgerStore(root).use { store ->
            for (blank in listOf("", " \t\n")) {
                assertFailsWith<IllegalArgumentException> { store.enqueueSupply("c","kp",blank,"source text") }
                assertFailsWith<IllegalArgumentException> { store.enqueueSupply("c","kp","objective",blank) }
            }
            assertTrue(store.supplyJobs().isEmpty())
        } } finally { root.toFile().deleteRecursively() }
    }

    @Test fun legacyInvalidSupplySourcesCannotRollbackLearningFacts() {
        val root=Files.createTempDirectory("supply-invalid-legacy")
        val records=mutableListOf<LogRecord>()
        val logger=Logger.getLogger(LedgerStore::class.java.name)
        val handler=object : Handler() {
            override fun publish(record: LogRecord) { records += record }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)
        try {
            // Bypass the public supply validator to reproduce jobs written by older versions.
            LedgerStore(root).use { store ->
                store.enqueueJob("gen_mcq",json("course" to "c","kp" to "kpA","objective" to "","excerpt" to "PRIVATE_EXCERPT_LEGACY").toString(),"legacy-pack")
                store.enqueueJob("gen_mcq",json("course" to "c","kp" to "kpB","objective" to "objective","excerpt" to " \t").toString(),"legacy-pack")
                store.enqueueSupply("c","valid","objective","PRIVATE_EXCERPT_VALID")
                store.enqueueJob("gen_mcq",json("course" to "c","kp" to "valid","objective" to " \t","excerpt" to "PRIVATE_EXCERPT_NEWER").toString(),"legacy-pack")
            }
            LedgerStore(root).use { store ->
                val facts=listOf(
                    "legacy_passed" to json("kpId" to "kpA"),
                    "legacy_passed" to json("kpId" to "kpB"),
                    "legacy_passed" to json("kpId" to "valid"),
                    "attempt_graded" to json("attemptId" to "unrelated","score" to 1,"max" to 1),
                    "grade_reviewed" to json("attemptId" to "unrelated","decision" to "CONFIRM"),
                    "kp_overridden" to json("kpId" to "kpA","kind" to "MARK_PASSED")
                )
                for ((type,payload) in facts) {
                    val before=store.events("c").size
                    val started=System.nanoTime()
                    val event=store.append("c",type,payload)
                    println("supply-hook append $type: ${(System.nanoTime()-started)/1_000_000.0} ms")
                    assertEquals(before+1,store.events("c").size)
                    assertEquals(event,store.events("c").last())
                }
                assertEquals(listOf("valid"),store.supplyJobs().filter { it.kind=="gen_constructed" }.map { it.subject.str("kp") })
            }
            LedgerStore(root).use { store ->
                assertEquals(6,store.events("c").size)
                assertTrue(store.snapshot("c").projection.mastery.getValue("kpA").passed)
            }
            assertTrue(records.any { it.message.contains("invalid source") },"Skipped sources must be logged")
            assertTrue(records.none { it.message.contains("PRIVATE_EXCERPT") },"Logs must not expose source text")
        } finally { logger.removeHandler(handler); root.toFile().deleteRecursively() }
    }

    @Test fun supplyWriteFailureRollsBackOnlyHookChangesAndCanRecover() {
        val root=Files.createTempDirectory("supply-write-failure")
        try {
            LedgerStore(root).use { store ->
                store.enqueueSupply("c","kp","objective","source text")
                DriverManager.getConnection("jdbc:sqlite:${root.resolve("coach.sqlite")}").use { connection ->
                    connection.createStatement().use { statement ->
                        // FAIL retains earlier trigger writes unless the hook rolls back to a savepoint.
                        statement.execute("""CREATE TRIGGER fail_constructed BEFORE INSERT ON jobs
                            WHEN NEW.kind='gen_constructed' BEGIN
                            INSERT INTO jobs(job_id,kind,subject,state,created_at,updated_at)
                                VALUES('partial-hook','gen_constructed',NEW.subject,'queued',0,0);
                            SELECT RAISE(FAIL,'synthetic supply failure'); END""")
                    }
                }
                val event=store.tutorTransaction { append("c","legacy_passed",json("kpId" to "kp")) }
                assertEquals(listOf(event),store.events("c"))
                assertTrue(store.supplyJobs().none { it.kind=="gen_constructed" },"Partial hook writes must roll back")
            }
            LedgerStore(root).use { store ->
                assertEquals(1,store.events("c").size)
                assertTrue(store.snapshot("c").projection.mastery.getValue("kp").passed)
                DriverManager.getConnection("jdbc:sqlite:${root.resolve("coach.sqlite")}").use { connection ->
                    connection.createStatement().use { it.execute("DROP TRIGGER fail_constructed") }
                }
                store.append("c","grade_reviewed",json("attemptId" to "unrelated","decision" to "CONFIRM"))
                assertEquals(2,store.events("c").size)
                assertEquals(1,store.supplyJobs().count { it.kind=="gen_constructed" })
                assertFailsWith<IllegalStateException> {
                    store.tutorTransaction {
                        append("c","legacy_passed",json("kpId" to "another"))
                        error("outer command failed")
                    }
                }
                assertEquals(2,store.events("c").size,"The hook must not commit the enclosing command")
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun reclaimedAndRestoredJobsRejectOldResults() {
        val root=Files.createTempDirectory("supply-fence")
        val dir=root.resolve("db")
        var store=LedgerStore(dir)
        try {
            val id=store.enqueueSupply("c","kp","objective","source")
            val job=store.supplyJobs().single()
            val first=store.claimJob(id)!!
            store.recoverSuspension()
            val second=store.claimJob(id)!!
            assertTrue(second.value > first.value)
            assertFalse(store.queueBlindReview(first,job,JsonArray(emptyList()),"claude"))
            assertEquals("running",store.jobState(id)!!.first)
            val backup=store.backup(root.resolve("backup"))
            store.close();store=LedgerStore.restore(dir,backup)
            assertFalse(store.queueBlindReview(second,job,JsonArray(emptyList()),"claude"))
            assertEquals(0,store.poolAvailable("c","kp"))
            assertEquals(1,store.supplyJobs().size)
            assertEquals("cancelled",store.jobState(id)!!.first)
        } finally { store.close();root.toFile().deleteRecursively() }
    }

    @Test fun fewerThanThreeUnseenQuestionsAreNotConsumedAndCoursesStaySeparate() {
        val root=Files.createTempDirectory("supply-short")
        LedgerStore(root).use { store ->
            val options=(1..4).map { Option("$it","Option text $it") }
            repeat(2) { store.registerQuestion("q$it","c","","SINGLE_CHOICE","A question $it","VERIFIED","1",options,listOf("kp")) }
            val result=store.takePoolGroup("c","kp","g")
            assertEquals("EMPTY",result.status);assertEquals(2,result.available);assertTrue(result.events.isEmpty())
            assertEquals(2,store.poolAvailable("c","kp"))
            assertEquals(0,store.poolAvailable("other","kp"))
            assertEquals(1,store.takePoolGroup("c","kp","single",size=1).events.size)
        }
        root.toFile().deleteRecursively()
    }
    @Test fun fallbackRequiresAFailedJobAndSuccessfulTakeAndEachFailureIsConsumedOnce() {
        val root=Files.createTempDirectory("fallback-semantics")
        LedgerStore(root).use { store ->
            assertEquals("EMPTY",store.takePoolGroup("c","kp","empty").status)
            assertTrue(store.events("c").isEmpty())
            val failure=store.enqueueSupply("c","kp","objective","source")
            store.failSupply(store.claimJob(failure)!!,"TIMEOUT")
            assertEquals("EMPTY",store.takePoolGroup("c","kp","failed-empty").status)
            assertTrue(store.events("c").isEmpty())
            val options=(1..4).map { Option("$it","Choice $it") }
            repeat(9) { store.registerQuestion("q$it","c","","SINGLE_CHOICE","A sufficiently long stem $it","VERIFIED","1",options,listOf("kp")) }
            repeat(3) { assertEquals(3,store.takePoolGroup("c","kp","group$it").events.size) }
            val fallback=store.events("c").filter { it.type=="fallback_used" }.single()
            assertEquals(failure,fallback.payload.str("jobId"));assertEquals("TIMEOUT",fallback.payload.str("reason"))
            store.takePoolGroup("c","kp","group0")
            assertEquals(1,store.events("c").count { it.type=="fallback_used" })
        }
        LedgerStore(root).use { assertEquals(1,it.events("c").count { e -> e.type=="fallback_used" }) }
        root.toFile().deleteRecursively()
    }

    @Test fun passingKpQueuesConstructedSupplyAtomicallyAndLegacyRemindersDoNotBreakWorkers() {
        val root=Files.createTempDirectory("passed-supply")
        LedgerStore(root).use { store ->
            store.ensureTutorPoolJob("c","unrelated")
            store.enqueueSupply("c","kp","Objective","Source supports every rubric item.")
            assertEquals(1,store.supplyJobs().size)
            store.append("c","legacy_passed",json("kpId" to "kp"))
            assertEquals(1,store.supplyJobs().count { it.kind=="gen_constructed" })
            store.append("c","legacy_passed",json("kpId" to "kp"))
            assertEquals(1,store.supplyJobs().count { it.kind=="gen_constructed" })
        }
        LedgerStore(root).use { assertEquals(1,it.supplyJobs().count { j -> j.kind=="gen_constructed" }) }
        root.toFile().deleteRecursively()
    }

}
