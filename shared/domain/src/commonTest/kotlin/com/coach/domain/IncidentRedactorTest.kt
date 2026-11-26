package com.coach.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncidentRedactorTest {
    @Test
    fun syntheticSecretsAreGoneAndSafeDiagnosticsStay() {
        val raw = buildString {
            appendLine("kind: crash")
            appendLine("version: 1.0.1")
            appendLine("os: Windows 11 amd64")
            appendLine("operations: app_start, import_book, sync_push")
            appendLine("  at com.coach.desktop.incident.IncidentLog.record(IncidentLog.kt:40)")
            appendLine(IncidentRedactor.syntheticBlob())
        }
        val redacted = IncidentRedactor.redact(raw)
        for (needle in IncidentRedactor.needles()) {
            assertFalse(redacted.contains(needle), needle)
        }
        assertTrue(redacted.contains("kind: crash"))
        assertTrue(redacted.contains("version: 1.0.1"))
        assertTrue(redacted.contains("operations: app_start, import_book, sync_push"))
        assertTrue(redacted.contains("at com.coach.desktop.incident.IncidentLog.record(IncidentLog.kt:40)"))
        assertEquals(redacted, IncidentRedactor.redact(redacted))
    }
}
