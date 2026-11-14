package com.coach.store

import com.coach.domain.*
import com.coach.domain.reviewqueue.*
import com.coach.protocol.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/** Synthetic configuration command -> authoritative projection -> real due entry -> exact allocation. */
class ReviewPriorityScheduleTest {
    private fun configure(store: LedgerStore, time: Long, priorities: JsonElement): Receipt {
        val body = CommandBody("configure_course", "C", time,
            json("timeZone" to "Europe/London", "reviewPriorities" to priorities))
        val bytes = canonicalJson(LedgerJson.encodeToJsonElement(CommandBody.serializer(), body))
        return store.command(WireCommand("synthetic", UUID.randomUUID().toString(), bytes, sha256(bytes.toByteArray())))
    }

    @Test fun checkedConfigurationReallyAdvancesTheServerAllocationDeadline() {
        var clock = 1_794_052_800_000L
        val day = logicalDay(clock, "Europe/London")
        val root = Files.createTempDirectory("synthetic-priority-")
        LedgerStore(root.resolve("server").also { Files.createDirectories(it) }) { clock }.use { store ->
            store.enroll("synthetic", "synthetic-priority-token-32-characters")
            store.append("C", "outline_revised", json("ops" to JsonArray(listOf(json("kpId" to "K", "title" to "Synthetic")))))
            store.append("C", "legacy_passed", json("kpId" to "K"))
            store.append("C", "legacy_schedule", json("kpId" to "K", "dueDay" to day + 20, "interval" to 20))
            store.registerQuestion("fresh-variant", "C", "", "SINGLE_CHOICE", "Synthetic new question", "VERIFIED", "o1",
                listOf(Option("o1", "One"), Option("o2", "Two")), listOf("K"))
            assertTrue(store.snapshot("C").projection.reviewsOn(day + 9).isEmpty())
            assertEquals("OK", configure(store, clock, json("K" to json("stars" to 3, "reviewed" to true))).status)
            val projected = store.snapshot("C").projection
            assertEquals(.95, projected.review.getValue("K").targetRetention)
            assertEquals(day + 9, projected.review.getValue("K").dueDay)
            clock += 9 * 86_400_000L
            val queue = ledgerQueueCandidate("C", store.events("C"), day + 9).allocateVariants(
                VariantCandidatePort { course, _, kp -> store.reviewVariantCandidates(course, kp) }).queue
            val entry = queue.dueEntries(day + 9).single()
            val variant = entry.variants.single()
            val posed = store.takeReviewVariant("C", "synthetic", null, "K", entry.id, variant.attemptId, variant.question).single()
            assertEquals("fresh-variant", posed.payload.str("questionId"))
            assertEquals(1, store.events("C").count { it.type == "question_posed" })
            println("SYNTHETIC_SERVER target=0.90->0.95 due=20d->9d allocated=fresh-variant day=9")
        }
    }

    @Test fun malformedConfigurationIsRejectedWithoutFactsAndUncheckedStarsDoNotBoost() {
        val clock = 1_794_052_800_000L
        val root = Files.createTempDirectory("synthetic-priority-input-")
        LedgerStore(root.resolve("server").also { Files.createDirectories(it) }) { clock }.use { store ->
            store.enroll("synthetic", "synthetic-priority-token-32-characters")
            val before = store.events("C")
            for (bad in listOf(JsonNull, json("K" to json("stars" to 5, "reviewed" to true)),
                    json("K" to json("stars" to "3", "reviewed" to true)), json("K" to json("stars" to 3)))) {
                assertEquals("REJECTED", configure(store, clock, bad).status)
                assertEquals(before, store.events("C"))
            }
            assertEquals("OK", configure(store, clock, json("K" to json("stars" to 3, "reviewed" to false))).status)
            assertTrue(store.snapshot("C").projection.reviewStars.isEmpty())
        }
    }
}
