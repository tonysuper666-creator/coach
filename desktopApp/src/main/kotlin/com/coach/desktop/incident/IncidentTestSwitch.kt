package com.coach.desktop.incident

import com.coach.domain.IncidentRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/**
 * Acceptance switch. Set `COACH_INCIDENT_TEST` to `crash`, `freeze` or `import`.
 * Absent in normal use. `crash` stops the process; reopen without the variable to see the prompt.
 */
internal suspend fun runIncidentTestSwitch() {
    when (System.getenv("COACH_INCIDENT_TEST")?.trim()) {
        "crash" -> IncidentLog.crashForTest()
        "import" -> IncidentLog.recordImportFailure(IncidentRedactor.syntheticBlob())
        "freeze" -> {
            val deadline = System.currentTimeMillis() + 5_000
            while (!IncidentWatchdog.armed && System.currentTimeMillis() < deadline) delay(50)
            withContext(Dispatchers.Swing) { Thread.sleep(12_000) }
        }
    }
}
