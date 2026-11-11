package com.coach.store

import com.coach.domain.*
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class PendingProjectionUpgradeTest {
    @Test fun firstReadReplacesAnOtherwiseMatchingScoredVersionTenCache() {
        val root = Files.createTempDirectory("pending-cache-upgrade")
        try {
            val now = 1_800_000_000_000L
            LedgerStore(root) { now }.use { store ->
                store.append("C", "question_posed", json("attemptId" to "pending", "questionId" to "q",
                    "kpIds" to listOf("kp"), "mainline" to true, "keyState" to "UNREVIEWED",
                    "format" to "SHORT_ANSWER", "purpose" to "PRACTICE"))
                store.append("C", "answer_submitted", json("attemptId" to "pending", "answer" to "Synthetic answer"))
                store.append("C", "attempt_graded", json("attemptId" to "pending", "status" to "EXACT", "score" to 100, "max" to 100))
                val expected = project(store.events("C"))
                val old = expected.copy(version = 10, journal = expected.journal.mapValues { (_, day) -> day.copy(correct = 1) })
                DriverManager.getConnection("jdbc:sqlite:${root.resolve("coach.sqlite")}").use { db ->
                    db.prepareStatement("UPDATE projection_cache SET version=10,last_seq=?,blob=? WHERE name='all' AND course_id='C'").use {
                        it.setLong(1, expected.lastSeq)
                        it.setBytes(2, LedgerJson.encodeToString(old).toByteArray())
                        assertEquals(1, it.executeUpdate())
                    }
                }
            }
            LedgerStore(root) { now }.use { store ->
                val state = store.snapshot("C").projection
                assertEquals(12, state.version)
                assertEquals(0, state.journal.values.sumOf { it.correct })
                assertEquals(project(store.events("C")), state)
                DriverManager.getConnection("jdbc:sqlite:${root.resolve("coach.sqlite")}").use { db ->
                    db.createStatement().use { statement ->
                        statement.executeQuery("SELECT version,last_seq FROM projection_cache WHERE name='all' AND course_id='C'").use {
                            assertTrue(it.next())
                            assertEquals(12, it.getInt(1))
                            assertEquals(state.lastSeq, it.getLong(2))
                        }
                    }
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
