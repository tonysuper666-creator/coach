package com.coach.desktop.incident

import com.coach.domain.IncidentRedactor
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncidentStoreTest {
    @Test fun recordStripsSyntheticSecretsAndKeepsDiagnostics() {
        val dir = Files.createTempDirectory("incident-redact")
        try {
            val store = IncidentStore(dir)
            store.note("Not Valid")
            store.note("sync_push")
            store.recordImportFailure(IncidentRedactor.syntheticBlob())
            store.record("Crash", "app_start", "nope")
            val file = store.incidentFiles().single()
            val text = Files.readString(dir.resolve(file.name))
            for (needle in IncidentRedactor.needles()) assertFalse(text.contains(needle), needle)
            assertTrue(text.contains("kind: import_failure"))
            assertTrue(text.contains("version: ${coachVersion()}"))
            assertTrue(text.contains("operations: sync_push, import_book"))
            assertFalse(text.contains("Not"))
            assertFalse(text.contains(System.getProperty("user.home")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun rotationDropsOldAndOversizedFiles() {
        val aged = Files.createTempDirectory("incident-age")
        try {
            val clock = AtomicReference(Instant.parse("2026-01-01T00:00:00Z"))
            val store = IncidentStore(aged, clock = { clock.get() })
            store.record("crash", "app_start", "old")
            val oldName = store.incidentFiles().single().name
            clock.set(Instant.parse("2026-02-01T00:00:00Z"))
            store.record("freeze", "ui_unresponsive", "new")
            assertFalse(Files.exists(aged.resolve(oldName)))
            assertEquals("freeze", store.incidentFiles().single().name.substringAfterLast('-').removeSuffix(".txt"))
        } finally {
            aged.toFile().deleteRecursively()
        }

        val sized = Files.createTempDirectory("incident-size")
        try {
            val clock = AtomicReference(Instant.parse("2026-03-01T00:00:00Z"))
            val probe = IncidentStore(sized, clock = { clock.get() })
            probe.record("crash", "app_start", "same-body")
            val one = probe.incidentFiles().single().bytes
            assertTrue(one > 64)
            sized.toFile().deleteRecursively()
            Files.createDirectories(sized)
            val cap = one + 64
            val tight = IncidentStore(sized, maxBytes = cap, clock = { clock.get() })
            tight.record("crash", "app_start", "same-body")
            clock.set(clock.get().plusSeconds(2))
            tight.record("crash", "app_start", "same-body")
            val left = tight.incidentFiles()
            assertEquals(1, left.size)
            assertTrue(left.single().epoch > Instant.parse("2026-03-01T00:00:00Z").toEpochMilli())
            assertTrue(left.sumOf { it.bytes } <= cap)
        } finally {
            sized.toFile().deleteRecursively()
        }
    }

    @Test fun promptFollowsUncleanExitAndConsentKeepsTheFileLocal() {
        val same = Files.createTempDirectory("incident-same-process")
        try {
            val pid = AtomicLong(10)
            val store = IncidentStore(same, pid = { pid.get() })
            assertNull(store.beginSession())
            store.recordImportFailure("importer failed")
            assertNull(IncidentStore(same, pid = { pid.get() }).beginSession())
            assertEquals(1, store.incidentFiles().size)
        } finally {
            same.toFile().deleteRecursively()
        }

        val crashed = Files.createTempDirectory("incident-crashed")
        try {
            val clock = AtomicReference(Instant.parse("2026-04-01T00:00:00Z"))
            val pid = AtomicLong(20)
            val first = IncidentStore(crashed, clock = { clock.get() }, pid = { pid.get() })
            assertNull(first.beginSession())
            clock.set(clock.get().plusSeconds(1))
            first.recordImportFailure("importer failed")
            pid.set(21)
            val next = IncidentStore(crashed, clock = { clock.get().plusSeconds(1) }, pid = { pid.get() })
            val offer = next.beginSession()
            assertNotNull(offer)
            val stored = Files.readString(crashed.resolve(offer.files.single()))
            assertEquals(stored, offer.text)
            assertTrue(offer.text.contains("kind: import_failure"))
            next.decline(offer)
            assertTrue(Files.exists(crashed.resolve(offer.files.single())))
            assertTrue(Files.exists(crashed.resolve("consent.txt")))
            pid.set(22)
            val again = IncidentStore(crashed, clock = { clock.get().plusSeconds(30) }, pid = { pid.get() })
            val second = again.beginSession()
            assertNotNull(second)
            assertTrue(second.text.contains("kind: unclean_exit"))
            assertFalse(second.text.contains("kind: import_failure"))
            assertEquals(2, again.incidentFiles().size)
            again.clear()
            assertTrue(again.incidentFiles().isEmpty())
            assertTrue(Files.exists(crashed.resolve("session.txt")))
            assertFalse(Files.exists(crashed.resolve("consent.txt")))
        } finally {
            crashed.toFile().deleteRecursively()
        }

        val clean = Files.createTempDirectory("incident-clean")
        try {
            val pid = AtomicLong(30)
            val store = IncidentStore(clean, pid = { pid.get() })
            assertNull(store.beginSession())
            store.record("freeze", "ui_unresponsive", "stuck")
            store.markClean()
            pid.set(31)
            val next = IncidentStore(clean, pid = { pid.get() })
            assertNull(next.beginSession())
            assertEquals(1, next.incidentFiles().size)
        } finally {
            clean.toFile().deleteRecursively()
        }
    }

    @Test fun freezeDueMatchesTheTenSecondRule() {
        assertFalse(freezeDue(10_000, 0, armed = false, modal = false, lastFreezeAt = 0))
        assertFalse(freezeDue(10_000, 0, armed = true, modal = false, lastFreezeAt = 0))
        assertFalse(freezeDue(10_000, 1, armed = true, modal = false, lastFreezeAt = 0))
        assertTrue(freezeDue(10_001, 1, armed = true, modal = false, lastFreezeAt = 0))
        assertFalse(freezeDue(10_001, 1, armed = true, modal = true, lastFreezeAt = 0))
        assertFalse(freezeDue(20_000, 1, armed = true, modal = false, lastFreezeAt = 10_001))
        assertTrue(freezeDue(70_001, 1, armed = true, modal = false, lastFreezeAt = 10_001))
    }
}
