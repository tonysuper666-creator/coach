package com.coach.store

import com.coach.domain.*
import kotlinx.serialization.json.JsonArray
import java.nio.file.Files
import kotlin.test.*

class QuestionSupplyTest {
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
