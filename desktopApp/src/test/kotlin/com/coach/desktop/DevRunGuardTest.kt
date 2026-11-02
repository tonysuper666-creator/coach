package com.coach.desktop

import kotlin.test.*

class DevRunGuardTest {
    private val isolated = mapOf("COACH_URL" to "http://127.0.0.1:18742", "COACH_TOKEN_FILE" to "/t/token", "COACH_CLIENT_DIR" to "/t/client")
    private fun check(env: Map<String, String>, vararg props: Pair<String, String>) = refuseProductionDefaults({ env[it] }, { props.toMap()[it] })
    private val packaged = "jpackage.app-path" to "/Applications/Coach.app/Contents/MacOS/Coach"

    @Test fun packagedAppKeepsItsDefaults() = assertNull(check(emptyMap(), packaged))

    @Test fun gradleRunWithoutIsolationIsRefused() {
        assertTrue(check(emptyMap()).orEmpty().contains("COACH_URL, COACH_TOKEN_FILE, COACH_CLIENT_DIR"))
        assertTrue(check(mapOf("COACH_URL" to "http://127.0.0.1:18742")).orEmpty().contains("COACH_TOKEN_FILE, COACH_CLIENT_DIR"))
    }

    @Test fun measurementSwitchesMakeEvenThePackagedAppRefuse() {
        assertNotNull(check(mapOf("COACH_AUTOPLAY" to "1"), packaged))
        assertNotNull(check(mapOf("COACH_RENDER_STATE" to "/tmp/s.json"), packaged))
        assertNotNull(check(emptyMap(), packaged, "coach.capturePath" to "/tmp/w.png"))
        assertNotNull(check(emptyMap(), packaged, "coach.autoExitSeconds" to "3"))
    }

    @Test fun productionPortsAreRefusedEvenWhenNamedExplicitly() {
        for (port in productionPorts) assertTrue(check(isolated + ("COACH_URL" to "http://100.88.12.89:$port"))!!.contains("production port $port"))
        assertNotNull(check(isolated + ("COACH_URL" to "not a url")))
    }

    @Test fun isolatedServerIsAllowedForDevelopmentRuns() {
        assertNull(check(isolated))
        assertNull(check(isolated + ("COACH_AUTOPLAY" to "1"), packaged))
    }
}
