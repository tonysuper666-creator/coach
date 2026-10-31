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
}
