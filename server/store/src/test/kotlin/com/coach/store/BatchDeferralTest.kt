package com.coach.store

import java.nio.file.Files
import kotlin.test.*

class BatchDeferralTest {
    @Test fun deferralSurvivesRestartAndFencesOutTheOldClaimWithoutUsingRetries() {
        val dir = Files.createTempDirectory("batch-deferral")
        var now = 1794787200000L
        var db = LedgerStore(dir) { now }
        try {
            val id = db.enqueueSupply("c", "kp", "Objective", "Synthetic source", background = true)
            val first = db.claimJob(id)!!
            assertTrue(db.deferSupply(first, 60_000))
            assertFalse(db.deferSupply(first, 60_000))
            assertFalse(db.completeJob(first, db.supplyJobs().single().packHash, null))
            assertNull(db.claimJob(id))
            db.close(); db = LedgerStore(dir) { now }
            assertNull(db.claimJob(id))
            now += 60_000
            val next = db.claimJob(id)!!
            assertEquals(first.epoch, next.epoch)
            assertTrue(next.value > first.value)
            assertTrue(db.deferSupply(next, 60_000))
            // Verify no retry inflation using the persisted contract, rather than an implementation counter.
            java.sql.DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { c ->
                c.createStatement().use { s -> s.executeQuery("SELECT attempts FROM jobs").use { r ->
                    assertTrue(r.next()); assertEquals(0, r.getInt(1))
                } }
            }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun foregroundConstructedSupplyIsIndependentOfPendingBackgroundWork() {
        val dir = Files.createTempDirectory("batch-constructed")
        try { LedgerStore(dir).use { db ->
            db.append("c", "legacy_passed", com.coach.domain.json("kpId" to "kp"))
            val background = db.enqueueConstructed("c", "kp", "Objective", "Synthetic source", true)
            val foreground = db.enqueueConstructed("c", "kp", "Objective", "Synthetic source", false)
            assertNotNull(background); assertNotNull(foreground)
            assertNotEquals(background, foreground)
            assertEquals(foreground, db.enqueueConstructed("c", "kp", "Objective", "Synthetic source", false))
        } } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun deferralCannotChangeAnInteractiveGradeJob() {
        val dir = Files.createTempDirectory("batch-grade")
        try { LedgerStore(dir).use { db ->
            val id = db.enqueueJob("grade_attempt", "synthetic")
            assertFalse(db.deferSupply(db.claimJob(id)!!, 60_000))
            assertEquals("running", db.jobState(id)!!.first)
        } } finally { dir.toFile().deleteRecursively() }
    }
}
